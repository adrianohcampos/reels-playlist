import { generateCode, listRecent, errorMessage, pairTv } from "./lib.js";

const ids = ["projectId", "apiKey", "playlistCode"];
const $ = (id) => document.getElementById(id);
const msg = (t, k = "") => { $("msg").textContent = t; $("msg").className = "status " + k; };

chrome.storage.sync.get(ids).then((v) => ids.forEach((k) => ($(k).value = v[k] || "")));

$("gen").onclick = () => {
  if ($("playlistCode").value && !confirm("Gerar um código novo cria uma playlist nova e vazia. Continuar?")) return;
  $("playlistCode").value = generateCode();
};

async function persist() {
  const data = Object.fromEntries(ids.map((k) => [k, $(k).value.trim()]));
  if (data.playlistCode && data.playlistCode.length < 24) {
    msg("O código precisa ter pelo menos 24 caracteres. Use o botão Gerar.", "err");
    return false;
  }
  await chrome.storage.sync.set(data);
  return true;
}

$("save").onclick = async () => { if (await persist()) msg("✓ Salvo", "ok"); };

$("pair").onclick = async () => {
  const out = (t, k = "") => { $("pairMsg").textContent = t; $("pairMsg").className = "status " + k; };
  if (!(await persist())) return;
  out("Conectando…");
  try {
    await pairTv($("pin").value);
    out("✓ Enviado! A TV deve mudar para a playlist em alguns segundos.", "ok");
    $("pin").value = "";
  } catch (e) {
    out(errorMessage(e), "err");
  }
};

$("test").onclick = async () => {
  if (!(await persist())) return;
  msg("Testando…");
  try {
    const items = await listRecent(1);
    msg(`✓ Conectado! A playlist tem ${items.length ? "itens" : "0 itens por enquanto"}.`, "ok");
  } catch (e) {
    msg(errorMessage(e), "err");
  }
};
