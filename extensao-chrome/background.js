import { saveReel, parseReel } from "./lib.js";

const PATTERNS = [
  "*://*.instagram.com/reel/*",
  "*://*.instagram.com/reels/*",
  "*://*.instagram.com/p/*",
  "*://*.instagram.com/*/reel/*",
];

chrome.runtime.onInstalled.addListener((details) => {
  chrome.contextMenus.removeAll(() => {
    // Botão direito em um link de Reel (WhatsApp Web, e-mail, qualquer site)
    chrome.contextMenus.create({
      id: "save-link",
      title: "Salvar Reel na playlist",
      contexts: ["link"],
      targetUrlPatterns: PATTERNS,
    });
    // Botão direito em qualquer lugar da página de um Reel
    chrome.contextMenus.create({
      id: "save-page",
      title: "Salvar este Reel na playlist",
      contexts: ["page", "video"],
      documentUrlPatterns: PATTERNS,
    });
  });
  if (details.reason === "install") chrome.runtime.openOptionsPage();
});

chrome.contextMenus.onClicked.addListener(async (info, tab) => {
  const url = info.menuItemId === "save-link" ? info.linkUrl : info.pageUrl;
  try {
    const r = await saveReel(url, "desktop");
    flash(tab?.id, r.status === "saved" ? "✓" : "=", "#16a34a");
  } catch (e) {
    console.error(e);
    flash(tab?.id, "!", "#dc2626");
  }
});

function flash(tabId, text, color) {
  const opts = tabId ? { tabId } : {};
  chrome.action.setBadgeBackgroundColor({ ...opts, color });
  chrome.action.setBadgeText({ ...opts, text });
  setTimeout(() => chrome.action.setBadgeText({ ...opts, text: "" }), 2500);
}

// Exposto para testes manuais no console do service worker
self.parseReel = parseReel;
