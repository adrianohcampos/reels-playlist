import config from "./config.js";

const $ = (id) => document.getElementById(id);
const BASE = `https://firestore.googleapis.com/v1/projects/${config.projectId}/databases/(default)/documents`;
const KEY = `key=${config.apiKey}`;
const PIN_CHARS = "ABCDEFGHJKMNPQRSTUVWXYZ23456789";

// ---------- Utilidades ----------

const REEL_RE = /instagram\.com\/(?:[A-Za-z0-9._]+\/)?(?:reel|reels|p|tv)\/([A-Za-z0-9_-]{5,})/i;
function parseReel(text) {
  const m = String(text || "").match(REEL_RE);
  return m ? { shortcode: m[1], url: `https://www.instagram.com/reel/${m[1]}/` } : null;
}

const getCode = () => localStorage.getItem("playlistCode");
const setCode = (c) => localStorage.setItem("playlistCode", c);

function randomId(n, chars = "abcdefghijkmnopqrstuvwxyz0123456789") {
  return Array.from(crypto.getRandomValues(new Uint8Array(n)), (b) => chars[b % chars.length]).join("");
}
function myDeviceId() {
  let id = localStorage.getItem("deviceId");
  if (!id) localStorage.setItem("deviceId", (id = randomId(20)));
  return id;
}
const isMobile = /Android|iPhone|iPad|Mobile/i.test(navigator.userAgent);
const myType = isMobile ? "celular" : "navegador";
const myDefaultName = /Android/i.test(navigator.userAgent) ? "Celular Android" : isMobile ? "Celular" : "Navegador";

let statusTimer;
function status(text, kind = "", big = false, ms = 3500) {
  const el = $("status");
  el.textContent = text;
  el.className = `status ${kind} ${big ? "big" : ""}`;
  clearTimeout(statusTimer);
  if (ms) statusTimer = setTimeout(() => el.classList.add("hidden"), ms);
}

function relTime(iso) {
  if (!iso) return "nunca";
  const s = (Date.now() - new Date(iso).getTime()) / 1000;
  if (s < 120) return "agora";
  if (s < 3600) return `há ${Math.round(s / 60)} min`;
  if (s < 86400) return `há ${Math.round(s / 3600)} h`;
  return `há ${Math.round(s / 86400)} dias`;
}

// ---------- Firestore (REST) ----------

const enc = (v) =>
  typeof v === "boolean" ? { booleanValue: v } : v instanceof Date ? { timestampValue: v.toISOString() } : { stringValue: String(v) };
const dec = (f = {}) =>
  Object.fromEntries(Object.entries(f).map(([k, v]) => [k, v.stringValue ?? v.booleanValue ?? v.timestampValue ?? null]));

async function fs(path, { method = "GET", body, query = "" } = {}) {
  const res = await fetch(`${BASE}/${path}?${query}${KEY}`, {
    method,
    headers: body ? { "Content-Type": "application/json" } : {},
    body: body ? JSON.stringify(body) : undefined,
  });
  if (res.status === 404 && method === "GET") return null;
  if (!res.ok) {
    const err = new Error(res.status === 403 ? "Acesso negado. Confira as regras do Firebase." : `Erro ${res.status}`);
    err.status = res.status;
    throw err;
  }
  return res.status === 204 ? {} : res.json();
}

/** PATCH: com `mask` atualiza só esses campos; sem `mask` cria/substitui o documento. */
function fsPatch(path, fields, mask) {
  const query = mask ? mask.map((f) => `updateMask.fieldPaths=${f}&`).join("") : "";
  const body = { fields: Object.fromEntries(Object.entries(fields).map(([k, v]) => [k, enc(v)])) };
  return fs(path, { method: "PATCH", body, query });
}

const P = () => `playlists/${getCode()}`;

// ---------- Itens ----------

const state = { items: [] };
const byCode = (sc) => state.items.find((i) => i.shortcode === sc);

async function loadItems() {
  const rows = await fetch(`${BASE}/${P()}:runQuery?${KEY}`, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({
      structuredQuery: {
        from: [{ collectionId: "items" }],
        orderBy: [{ field: { fieldPath: "addedAt" }, direction: "ASCENDING" }],
        limit: 500,
      },
    }),
  }).then((r) => (r.ok ? r.json() : Promise.reject(new Error(`Erro ${r.status}`))));
  state.items = rows.filter((r) => r.document).map((r) => {
    const d = dec(r.document.fields);
    return { shortcode: d.shortcode, url: d.url, title: d.title, watched: !!d.watched, addedAt: d.addedAt };
  });
  renderList();
  renderWatchSummary();
}

async function saveReel(raw) {
  const reel = parseReel(raw);
  if (!reel) throw new Error("Isso não parece um link de Reel.");
  try {
    await fs(`${P()}/items`, {
      method: "POST",
      query: `documentId=${reel.shortcode}&`,
      body: {
        fields: {
          url: enc(reel.url),
          shortcode: enc(reel.shortcode),
          addedAt: enc(new Date()),
          watched: enc(false),
          source: enc("celular"),
        },
      },
    });
    return "saved";
  } catch (e) {
    if (e.status === 409) return "duplicate";
    throw e;
  }
}

function setWatched(sc, watched) {
  const it = byCode(sc);
  if (it) it.watched = watched;
  fsPatch(`${P()}/items/${sc}`, { watched }, ["watched"]).catch(() => {});
}

function renderList() {
  const ul = $("recent");
  const items = [...state.items].reverse().slice(0, 30);
  ul.innerHTML = items.length ? "" : '<li class="muted">Nada salvo ainda.</li>';
  for (const it of items) {
    const li = document.createElement("li");
    const box = document.createElement("div");
    box.className = "grow";
    const name = document.createElement("div");
    name.className = "name";
    name.textContent = "▶ " + (it.title || it.shortcode);
    name.onclick = () => openPlayer(it.shortcode);
    box.append(name);
    const tag = document.createElement("button");
    tag.className = "ghost small";
    tag.textContent = it.watched ? "✔ visto" : "novo";
    tag.title = "Alternar visto / novo";
    tag.onclick = () => { setWatched(it.shortcode, !it.watched); renderList(); renderWatchSummary(); };
    const del = document.createElement("button");
    del.className = "x";
    del.textContent = "✕";
    del.title = "Remover";
    del.onclick = async () => {
      if (!confirm("Remover da playlist?")) return;
      await fs(`${P()}/items/${it.shortcode}`, { method: "DELETE" }).catch(() => {});
      state.items = state.items.filter((x) => x !== it);
      renderList();
      renderWatchSummary();
    };
    li.append(box, tag, del);
    ul.append(li);
  }
}

async function doSave(raw, fromShare = false) {
  status("Salvando…", "", fromShare, 0);
  try {
    const r = await saveReel(raw);
    status(r === "saved" ? "✓ Salvo na playlist" : "Esse Reel já estava na playlist", "ok", fromShare);
    $("link").value = "";
    loadItems().catch(() => {});
    // Veio do "Compartilhar": tenta fechar e voltar ao Instagram
    if (fromShare) setTimeout(() => window.close(), 1200);
  } catch (e) {
    status(e.message, "err", fromShare, 6000);
  }
}

// ---------- Player (embed oficial do Instagram) ----------

let queue = [];
let qi = 0;
let viewBeforePlayer = "viewHome";

function renderWatchSummary() {
  const novos = state.items.filter((i) => !i.watched).length;
  $("watchSummary").textContent = `${novos} novos · ${state.items.length} na playlist`;
  $("btnWatchNew").disabled = novos === 0;
  $("btnWatchAll").disabled = state.items.length === 0;
}

function openPlayer(startSc = null, mode = null) {
  const start = startSc && byCode(startSc);
  mode = mode || (start?.watched ? "all" : "new");
  const base = mode === "all" ? state.items : state.items.filter((i) => !i.watched || i.shortcode === startSc);
  queue = base.map((i) => i.shortcode);
  qi = Math.max(0, startSc ? queue.indexOf(startSc) : 0);
  if (!queue.length) return status("Nada novo para assistir 🎉", "ok");
  viewBeforePlayer = currentView;
  $("viewPlayer").classList.remove("hidden");
  renderPlayer();
}

function renderPlayer() {
  const sc = queue[qi];
  const it = byCode(sc);
  $("frame").src = `https://www.instagram.com/reel/${sc}/embed/`;
  $("pCount").textContent = `${qi + 1} / ${queue.length}`;
  $("pTitle").textContent = it?.title || "";
  $("pOpen").href = `https://www.instagram.com/reel/${sc}/`;
  $("pPrev").disabled = qi === 0;
  $("pNext").textContent = qi === queue.length - 1 ? "✓ Visto · Concluir" : "✓ Visto · Próximo ▶";
}

function playerNext() {
  setWatched(queue[qi], true);
  if (qi < queue.length - 1) {
    qi++;
    renderPlayer();
  } else {
    closePlayer();
    status("Fim da playlist 🎉", "ok");
  }
}
function playerPrev() {
  if (qi > 0) { qi--; renderPlayer(); }
}
function closePlayer() {
  $("frame").src = "about:blank";
  $("viewPlayer").classList.add("hidden");
  renderList();
  renderWatchSummary();
  show(viewBeforePlayer);
}

$("pNext").onclick = playerNext;
$("pPrev").onclick = playerPrev;
$("pClose").onclick = closePlayer;
$("btnWatchNew").onclick = () => openPlayer(null, "new");
$("btnWatchAll").onclick = () => openPlayer(null, "all");
document.addEventListener("keydown", (e) => {
  if ($("viewPlayer").classList.contains("hidden")) return;
  if (e.key === "ArrowRight" || e.key === "PageDown") playerNext();
  else if (e.key === "ArrowLeft" || e.key === "PageUp") playerPrev();
  else if (e.key === "Escape") closePlayer();
});

// ---------- Aparelhos ----------

const ICON = { tv: "📺", celular: "📱", desktop: "💻", navegador: "🌐" };

/** Registra/atualiza este aparelho. Retorna false se ele foi removido por outro aparelho. */
async function heartbeat() {
  const path = `${P()}/devices/${myDeviceId()}`;
  const doc = await fs(path).catch(() => undefined);
  if (doc === undefined) return true; // sem rede: tenta depois
  if (doc && dec(doc.fields).revoked) {
    await fs(path, { method: "DELETE" }).catch(() => {});
    localStorage.removeItem("playlistCode");
    return false;
  }
  if (doc) await fsPatch(path, { lastSeen: new Date() }, ["lastSeen"]).catch(() => {});
  else {
    const now = new Date();
    await fsPatch(path, { name: myDefaultName, type: myType, pairedAt: now, lastSeen: now, revoked: false }).catch(() => {});
  }
  return true;
}

async function loadDevices() {
  const ul = $("devices");
  try {
    const res = await fs(`${P()}/devices`, { query: "pageSize=100&" });
    const list = (res?.documents || []).map((d) => ({ id: d.name.split("/").pop(), ...dec(d.fields) }));
    list.sort((a, b) => (b.id === myDeviceId()) - (a.id === myDeviceId()) || String(b.lastSeen).localeCompare(String(a.lastSeen)));
    ul.innerHTML = list.length ? "" : '<li class="muted">Nenhum aparelho registrado ainda.</li>';
    for (const d of list) {
      const me = d.id === myDeviceId();
      const li = document.createElement("li");
      const icon = document.createElement("span");
      icon.className = "icon";
      icon.textContent = ICON[d.type] || "❔";
      const box = document.createElement("div");
      box.className = "grow";
      const name = document.createElement("div");
      name.className = "name";
      name.textContent = (d.name || "Sem nome") + (me ? " (este aparelho)" : "");
      name.title = "Renomear";
      name.onclick = async () => {
        const n = prompt("Nome do aparelho:", d.name || "");
        if (!n || !n.trim()) return;
        await fsPatch(`${P()}/devices/${d.id}`, { name: n.trim().slice(0, 60) }, ["name"]).catch((e) => status(e.message, "err"));
        loadDevices();
      };
      const tag = document.createElement("div");
      tag.className = "tag";
      tag.textContent = d.revoked
        ? "removido · será desconectado quando abrir o app"
        : `visto ${relTime(d.lastSeen)} · conectado ${relTime(d.pairedAt)}`;
      box.append(name, tag);
      li.append(icon, box);
      if (!me) {
        const btn = document.createElement("button");
        btn.className = "ghost small";
        if (d.revoked) {
          btn.textContent = "Apagar";
          btn.onclick = async () => { await fs(`${P()}/devices/${d.id}`, { method: "DELETE" }).catch(() => {}); loadDevices(); };
        } else {
          btn.textContent = "Remover";
          btn.onclick = async () => {
            if (!confirm(`Remover "${d.name}"? Ele será desconectado da playlist na próxima vez que abrir o app.`)) return;
            await fsPatch(`${P()}/devices/${d.id}`, { revoked: true }, ["revoked"]).catch((e) => status(e.message, "err"));
            loadDevices();
          };
        }
        li.append(btn);
      }
      ul.append(li);
    }
  } catch (e) {
    ul.innerHTML = `<li class="muted">Não foi possível carregar (${e.message}).</li>`;
  }
}

$("btnPair").onclick = async () => {
  const pin = $("pairPin").value.toUpperCase().replace(/[^A-Z0-9]/g, "");
  if (pin.length !== 8) return status("O código tem 8 caracteres (ex.: ABCD-1234).", "err");
  try {
    await fs(`pairing`, {
      method: "POST",
      query: `documentId=${pin}&`,
      body: { fields: { playlistCode: enc(getCode()), createdAt: enc(new Date()) } },
    });
  } catch (e) {
    if (e.status !== 409) return status(e.message, "err");
  }
  $("pairPin").value = "";
  status("✓ Enviado! O aparelho deve conectar em alguns segundos.", "ok", false, 5000);
  setTimeout(loadDevices, 8000);
};

$("btnCopy").onclick = async () => {
  try { await navigator.clipboard.writeText(getCode()); status("Código copiado", "ok"); }
  catch { status("Não foi possível copiar", "err"); }
};

$("btnDisconnect").onclick = async () => {
  if (!confirm("Desconectar este aparelho? A playlist continua salva.")) return;
  await fs(`${P()}/devices/${myDeviceId()}`, { method: "DELETE" }).catch(() => {});
  localStorage.removeItem("playlistCode");
  startPairing();
};

// ---------- Pareamento deste aparelho ----------

let pairTimer = null;
function startPairing() {
  clearInterval(pairTimer);
  show("viewPair");
  const pin = randomId(8, PIN_CHARS);
  $("pin").textContent = pin.slice(0, 4) + "-" + pin.slice(4);
  pairTimer = setInterval(async () => {
    try {
      const doc = await fs(`pairing/${pin}`);
      const code = doc && dec(doc.fields).playlistCode;
      if (code && code.length >= 24) {
        clearInterval(pairTimer);
        setCode(code);
        fs(`pairing/${pin}`, { method: "DELETE" }).catch(() => {});
        await startMain();
        status("✓ Aparelho conectado!", "ok");
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

// ---------- Navegação ----------

let currentView = "viewHome";
function show(view) {
  for (const v of ["viewPair", "viewHome", "viewWatch", "viewDevices"]) $(v).classList.toggle("hidden", v !== view);
  $("nav").classList.toggle("hidden", view === "viewPair");
  for (const b of document.querySelectorAll("nav button")) b.classList.toggle("active", b.dataset.view === view);
  if (view !== "viewPair") currentView = view;
  if (view === "viewDevices") {
    loadDevices();
    $("secret").textContent = getCode() || "";
  }
}
for (const b of document.querySelectorAll("nav button")) b.onclick = () => show(b.dataset.view);

async function startMain(view = "viewHome") {
  show(view);
  if (!(await heartbeat())) {
    alert("Este aparelho foi removido da playlist. Conecte de novo se quiser.");
    startPairing();
    return false;
  }
  loadItems().catch((e) => { $("recent").innerHTML = `<li class="muted">Não foi possível carregar (${e.message}).</li>`; });
  return true;
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

// Ao voltar para o app, atualiza a lista
document.addEventListener("visibilitychange", () => {
  if (document.visibilityState === "visible" && getCode() && $("viewPlayer").classList.contains("hidden")) {
    loadItems().catch(() => {});
  }
});

// ---------- Início ----------

if ("serviceWorker" in navigator) navigator.serviceWorker.register("sw.js");

if (!config.projectId || !config.apiKey) {
  status("Site publicado sem FIREBASE_PROJECT_ID / FIREBASE_API_KEY. Veja o LEIA-ME.", "err", false, 0);
} else {
  const params = new URLSearchParams(location.search);
  const shared = [params.get("url"), params.get("text"), params.get("title")].filter(Boolean).join(" ");
  if (shared) history.replaceState(null, "", location.pathname);

  if (!getCode()) {
    startPairing();
    if (shared) alert("Conecte este aparelho primeiro. Depois compartilhe o Reel de novo.");
  } else {
    startMain().then((ok) => { if (ok && shared) doSave(shared, true); });
  }
}
