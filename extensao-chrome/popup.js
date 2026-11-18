import { saveReel, listRecent, deleteReel, parseReel, getConfig, errorMessage, heartbeat } from "./lib.js";

const $status = document.getElementById("status");
const $recent = document.getElementById("recent");

document.getElementById("opts").onclick = (e) => {
  e.preventDefault();
  chrome.runtime.openOptionsPage();
};

function show(text, kind = "") {
  $status.textContent = text;
  $status.className = "status " + kind;
}

async function save(url) {
  show("Salvando…");
  try {
    const r = await saveReel(url, "desktop");
    show(r.status === "saved" ? "✓ Salvo na playlist" : "Esse Reel já estava na playlist", "ok");
    await refresh();
  } catch (e) {
    show(errorMessage(e), "err");
  }
}

async function refresh() {
  try {
    const items = await listRecent(5);
    $recent.innerHTML = "";
    if (!items.length) {
      $recent.innerHTML = '<li class="muted">Nada salvo ainda.</li>';
      return;
    }
    for (const it of items) {
      const li = document.createElement("li");
      const a = document.createElement("a");
      a.href = it.url;
      a.target = "_blank";
      a.textContent = (it.watched ? "✔ " : "") + it.shortcode;
      const del = document.createElement("button");
      del.className = "del";
      del.title = "Remover";
      del.textContent = "✕";
      del.onclick = async () => {
        await deleteReel(it.shortcode).catch(() => {});
        refresh();
      };
      li.append(a, del);
      $recent.append(li);
    }
  } catch (e) {
    $recent.innerHTML = `<li class="muted">${errorMessage(e)}</li>`;
  }
}

document.getElementById("manual").onsubmit = (e) => {
  e.preventDefault();
  const v = document.getElementById("link").value.trim();
  if (v) save(v);
};

(async () => {
  if (!(await getConfig())) {
    show("Configure a extensão primeiro (clique em ⚙️).", "err");
    $recent.innerHTML = "";
    return;
  }
  if (!(await heartbeat())) {
    show("Este Chrome foi removido da playlist por outro aparelho. Configure de novo em ⚙️.", "err");
    $recent.innerHTML = "";
    return;
  }
  // Ao abrir o popup numa página de Reel, já salva automaticamente (1 clique / Alt+Shift+S).
  const [tab] = await chrome.tabs.query({ active: true, currentWindow: true });
  if (tab?.url && parseReel(tab.url)) {
    await save(tab.url);
  } else {
    show("Abra um Reel e clique aqui, ou cole um link abaixo.");
    refresh();
  }
})();
