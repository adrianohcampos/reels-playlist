import config from "./config.js";

const $ = (id) => document.getElementById(id);
const BASE = `https://firestore.googleapis.com/v1/projects/${config.projectId}/databases/(default)/documents`;
const KEY = `key=${config.apiKey}`;

// ---------- Utilidades ----------

const REEL_RE = /instagram\.com\/(?:[A-Za-z0-9._]+\/)?(?:reel|reels|p|tv)\/([A-Za-z0-9_-]{5,})/i;
function parseReel(text) {
  const m = String(text || "").match(REEL_RE);
  return m ? { shortcode: m[1], url: `https://www.instagram.com/reel/${m[1]}/` } : null;
}

const getCode = () => localStorage.getItem("playlistCode");
const setCode = (c) => localStorage.setItem("playlistCode", c);

function show(view) {
  for (const v of ["viewPair", "viewMain", "viewSettings"]) $(v).classList.toggle("hidden", v !== view);
  $("btnSettings").classList.toggle("hidden", view !== "viewMain");
}

function status(text, kind = "", big = false) {
  const el = $("status");
  el.textContent = text;
  el.className = `status ${kind} ${big ? "big" : ""}`;
}

// ---------- Firestore ----------

async function saveReel(raw) {
  const reel = parseReel(raw);
  if (!reel) throw new Error("Isso não parece um link de Reel.");
  const res = await fetch(`${BASE}/playlists/${getCode()}/items?documentId=${reel.shortcode}&${KEY}`, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({
      fields: {
        url: { stringValue: reel.url },
        shortcode: { stringValue: reel.shortcode },
        addedAt: { timestampValue: new Date().toISOString() },
        watched: { booleanValue: false },
        source: { stringValue: "celular" },
      },
    }),
  });
  if (res.status === 409) return "duplicate";
  if (res.status === 403) throw new Error("Acesso negado. Confira as regras do Firebase.");
  if (!res.ok) throw new Error(`Erro ${res.status} ao salvar.`);
  return "saved";
}

async function listRecent(limit = 20) {
  const res = await fetch(`${BASE}/playlists/${getCode()}:runQuery?${KEY}`, {
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
  if (!res.ok) throw new Error(`Erro ${res.status}`);
  return (await res.json()).filter((r) => r.document).map((r) => {
    const f = r.document.fields;
    return {
      shortcode: f.shortcode?.stringValue,
      url: f.url?.stringValue,
      title: f.title?.stringValue,
      watched: f.watched?.booleanValue ?? false,
    };
  });
}

async function deleteReel(sc) {
  await fetch(`${BASE}/playlists/${getCode()}/items/${sc}?${KEY}`, { method: "DELETE" });
}

// ---------- Pareamento (mesmo fluxo da TV) ----------

let pairTimer = null;
function startPairing() {
  show("viewPair");
  const chars = "ABCDEFGHJKMNPQRSTUVWXYZ23456789";
  const pin = Array.from(crypto.getRandomValues(new Uint8Array(8)), (b) => chars[b % chars.length]).join("");
  $("pin").textContent = pin.slice(0, 4) + "-" + pin.slice(4);
  clearInterval(pairTimer);
  pairTimer = setInterval(async () => {
    try {
      const res = await fetch(`${BASE}/pairing/${pin}?${KEY}`);
      if (!res.ok) return;
      const code = (await res.json()).fields?.playlistCode?.stringValue;
      if (code && code.length >= 24) {
        clearInterval(pairTimer);
        setCode(code);
        fetch(`${BASE}/pairing/${pin}?${KEY}`, { method: "DELETE" }).catch(() => {});
        startMain();
        status("✓ Celular conectado!", "ok");
      }
    } catch {}
  }, 3000);
}

$("btnManual").onclick = () => {
  const c = $("manualCode").value.trim();
  if (c.length < 24) return alert("O código precisa ter pelo menos 24 caracteres.");
  clearInterval(pairTimer);
  setCode(c);
  startMain();
};

// ---------- Tela principal ----------

async function refresh() {
  const ul = $("recent");
  try {
    const items = await listRecent();
    ul.innerHTML = "";
    if (!items.length) ul.innerHTML = '<li class="muted">Nada salvo ainda.</li>';
    for (const it of items) {
      const li = document.createElement("li");
      const a = document.createElement("a");
      a.href = it.url;
      a.target = "_blank";
      a.rel = "noopener";
      a.textContent = it.title || it.shortcode;
      const tag = document.createElement("span");
      tag.className = "tag";
      tag.textContent = it.watched ? "✔ visto" : "novo";
      const del = document.createElement("button");
      del.textContent = "✕";
      del.title = "Remover";
      del.onclick = async () => {
        if (!confirm("Remover da playlist?")) return;
        await deleteReel(it.shortcode);
        refresh();
      };
      li.append(a, tag, del);
      ul.append(li);
    }
  } catch (e) {
    ul.innerHTML = `<li class="muted">Não foi possível carregar (${e.message}).</li>`;
  }
}

async function doSave(raw, fromShare = false) {
  status("Salvando…", "", fromShare);
  try {
    const r = await saveReel(raw);
    status(r === "saved" ? "✓ Salvo na playlist" : "Esse Reel já estava na playlist", "ok", fromShare);
    $("link").value = "";
    refresh();
    // Veio do "Compartilhar": tenta fechar e voltar ao Instagram
    if (fromShare) setTimeout(() => window.close(), 1200);
  } catch (e) {
    status(e.message, "err", fromShare);
  }
}

function startMain() {
  show("viewMain");
  refresh();
}

$("btnSave").onclick = () => doSave($("link").value);
$("link").addEventListener("keydown", (e) => e.key === "Enter" && doSave($("link").value));
$("btnPaste").onclick = async () => {
  try {
    const t = await navigator.clipboard.readText();
    $("link").value = t;
    if (parseReel(t)) doSave(t);
  } catch {
    $("link").focus();
    alert("Não consegui ler a área de transferência. Cole manualmente no campo.");
  }
};
$("btnSettings").onclick = () => show("viewSettings");
$("btnBack").onclick = () => startMain();
$("btnDisconnect").onclick = () => {
  if (!confirm("Desconectar este celular? A playlist continua salva.")) return;
  localStorage.removeItem("playlistCode");
  startPairing();
};

// ---------- Início ----------

if ("serviceWorker" in navigator) navigator.serviceWorker.register("sw.js");

if (!config.projectId || !config.apiKey) {
  document.body.insertAdjacentHTML("beforeend",
    '<div class="status err">Site publicado sem FIREBASE_PROJECT_ID / FIREBASE_API_KEY. Veja o LEIA-ME.</div>');
} else {
  const params = new URLSearchParams(location.search);
  const shared = [params.get("url"), params.get("text"), params.get("title")].filter(Boolean).join(" ");
  if (shared) history.replaceState(null, "", location.pathname);

  if (!getCode()) {
    startPairing();
    if (shared) alert("Conecte este celular primeiro. Depois compartilhe o Reel de novo.");
  } else {
    startMain();
    if (shared) doSave(shared, true);
  }
}
