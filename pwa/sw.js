// Service worker: deixa o app instalável e abrindo rápido.
// Arquivos do app: rede primeiro, cache se estiver offline. Firestore: nunca entra no cache.
const CACHE = "reels-playlist-v2";
const SHELL = ["./", "index.html", "app.js", "config.js", "manifest.webmanifest", "icons/192.png"];

self.addEventListener("install", (e) => {
  e.waitUntil(caches.open(CACHE).then((c) => c.addAll(SHELL)).then(() => self.skipWaiting()));
});

self.addEventListener("activate", (e) => {
  e.waitUntil(
    caches.keys()
      .then((keys) => Promise.all(keys.filter((k) => k !== CACHE).map((k) => caches.delete(k))))
      .then(() => self.clients.claim())
  );
});

self.addEventListener("fetch", (e) => {
  const url = new URL(e.request.url);
  if (e.request.method !== "GET" || url.origin !== location.origin) return;
  e.respondWith(
    fetch(e.request)
      .then((res) => {
        const copy = res.clone();
        caches.open(CACHE).then((c) => c.put(e.request, copy));
        return res;
      })
      // ignoreSearch: a abertura via "Compartilhar" vem com ?text=... na URL
      .catch(() => caches.match(e.request, { ignoreSearch: true }))
  );
});
