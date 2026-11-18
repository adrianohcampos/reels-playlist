// Funções compartilhadas: reconhecer links de Reels e falar com o Firestore (API REST).

const REEL_RE = /instagram\.com\/(?:[A-Za-z0-9._]+\/)?(?:reel|reels|p|tv)\/([A-Za-z0-9_-]{5,})/i;

/** Extrai o código do Reel de qualquer formato de link. Retorna null se não for um Reel/post. */
export function parseReel(url) {
  if (!url) return null;
  const m = String(url).match(REEL_RE);
  if (!m) return null;
  const shortcode = m[1];
  return { shortcode, url: `https://www.instagram.com/reel/${shortcode}/` };
}

export async function getConfig() {
  const { projectId, apiKey, playlistCode } = await chrome.storage.sync.get([
    "projectId", "apiKey", "playlistCode",
  ]);
  if (!projectId || !apiKey || !playlistCode) return null;
  return { projectId, apiKey, playlistCode };
}

function base(cfg) {
  return `https://firestore.googleapis.com/v1/projects/${cfg.projectId}/databases/(default)/documents/playlists/${cfg.playlistCode}`;
}

/**
 * Salva um Reel. Retorna { status: "saved" | "duplicate", shortcode }.
 * Usa o shortcode como ID do documento, então salvar duas vezes não duplica.
 */
export async function saveReel(rawUrl, source = "desktop") {
  const cfg = await getConfig();
  if (!cfg) throw new Error("NOT_CONFIGURED");
  const reel = parseReel(rawUrl);
  if (!reel) throw new Error("NOT_A_REEL");

  const res = await fetch(
    `${base(cfg)}/items?documentId=${reel.shortcode}&key=${cfg.apiKey}`,
    {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({
        fields: {
          url: { stringValue: reel.url },
          shortcode: { stringValue: reel.shortcode },
          addedAt: { timestampValue: new Date().toISOString() },
          watched: { booleanValue: false },
          source: { stringValue: source },
        },
      }),
    }
  );
  if (res.status === 409) return { status: "duplicate", shortcode: reel.shortcode };
  if (!res.ok) throw new Error(`HTTP_${res.status}: ${await res.text()}`);
  return { status: "saved", shortcode: reel.shortcode };
}

/** Últimos N itens salvos (mais recentes primeiro). */
export async function listRecent(limit = 5) {
  const cfg = await getConfig();
  if (!cfg) throw new Error("NOT_CONFIGURED");
  const res = await fetch(`${base(cfg)}:runQuery?key=${cfg.apiKey}`, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({
      structuredQuery: {
        from: [{ collectionId: "items" }],
        orderBy: [{ field: { fieldPath: "addedAt" }, direction: "DESCENDING" }],
        limit,
      },
    }),
  });
  if (!res.ok) throw new Error(`HTTP_${res.status}: ${await res.text()}`);
  const rows = await res.json();
  return rows
    .filter((r) => r.document)
    .map((r) => {
      const f = r.document.fields;
      return {
        shortcode: f.shortcode?.stringValue,
        url: f.url?.stringValue,
        watched: f.watched?.booleanValue ?? false,
        addedAt: f.addedAt?.timestampValue,
      };
    });
}

/** Remove um item da playlist. */
export async function deleteReel(shortcode) {
  const cfg = await getConfig();
  if (!cfg) throw new Error("NOT_CONFIGURED");
  const res = await fetch(`${base(cfg)}/items/${shortcode}?key=${cfg.apiKey}`, { method: "DELETE" });
  if (!res.ok) throw new Error(`HTTP_${res.status}`);
}

/**
 * Conecta uma TV: grava pairing/{PIN} com o código da playlist.
 * A TV fica consultando esse documento, lê o código e apaga o pedido.
 */
export async function pairTv(rawPin) {
  const cfg = await getConfig();
  if (!cfg) throw new Error("NOT_CONFIGURED");
  const pin = String(rawPin).toUpperCase().replace(/[^A-Z0-9]/g, "");
  if (pin.length !== 8) throw new Error("BAD_PIN");
  const res = await fetch(
    `https://firestore.googleapis.com/v1/projects/${cfg.projectId}/databases/(default)/documents/pairing?documentId=${pin}&key=${cfg.apiKey}`,
    {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({
        fields: {
          playlistCode: { stringValue: cfg.playlistCode },
          createdAt: { timestampValue: new Date().toISOString() },
        },
      }),
    }
  );
  if (res.status === 409) return; // já enviado
  if (!res.ok) throw new Error(`HTTP_${res.status}: ${await res.text()}`);
}

/**
 * Registra este Chrome na lista de aparelhos (no máximo a cada 10 min).
 * Se outro aparelho o removeu, apaga o código salvo e retorna false.
 */
export async function heartbeat(force = false) {
  const cfg = await getConfig();
  if (!cfg) return true;
  let { deviceId, lastBeat = 0 } = await chrome.storage.local.get(["deviceId", "lastBeat"]);
  if (!force && Date.now() - lastBeat < 10 * 60 * 1000) return true;
  if (!deviceId) {
    deviceId = Array.from(crypto.getRandomValues(new Uint8Array(20)), (b) => "abcdefghijkmnopqrstuvwxyz0123456789"[b % 35]).join("");
    await chrome.storage.local.set({ deviceId });
  }
  const url = `${base(cfg)}/devices/${deviceId}`;
  try {
    const res = await fetch(`${url}?key=${cfg.apiKey}`);
    const now = { timestampValue: new Date().toISOString() };
    if (res.ok) {
      const f = (await res.json()).fields || {};
      if (f.revoked?.booleanValue) {
        await fetch(`${url}?key=${cfg.apiKey}`, { method: "DELETE" });
        await chrome.storage.sync.remove("playlistCode");
        return false;
      }
      await fetch(`${url}?updateMask.fieldPaths=lastSeen&key=${cfg.apiKey}`, {
        method: "PATCH", headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ fields: { lastSeen: now } }),
      });
    } else if (res.status === 404) {
      await fetch(`${url}?key=${cfg.apiKey}`, {
        method: "PATCH", headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ fields: {
          name: { stringValue: "Chrome (computador)" }, type: { stringValue: "desktop" },
          pairedAt: now, lastSeen: now, revoked: { booleanValue: false },
        } }),
      });
    } else return true;
    await chrome.storage.local.set({ lastBeat: Date.now() });
  } catch {}
  return true;
}

/** Gera um código secreto de playlist (32 caracteres). */
export function generateCode() {
  const chars = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnpqrstuvwxyz23456789";
  const bytes = crypto.getRandomValues(new Uint8Array(32));
  return Array.from(bytes, (b) => chars[b % chars.length]).join("");
}

export function errorMessage(err) {
  const msg = String(err?.message || err);
  if (msg === "NOT_CONFIGURED") return "Configure a extensão primeiro (clique em ⚙️).";
  if (msg === "NOT_A_REEL") return "Isso não parece um link de Reel.";
  if (msg === "BAD_PIN") return "O código da TV tem 8 caracteres (ex.: ABCD-1234).";
  if (msg.startsWith("HTTP_403")) return "Acesso negado pelo Firebase. Confira as regras e o código da playlist.";
  if (msg.startsWith("HTTP_400")) return "Configuração inválida. Confira o Project ID e a API key.";
  return "Erro ao salvar: " + msg.slice(0, 120);
}
