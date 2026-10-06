// Kill-switch service worker: replaces the old PWA cache-first worker inside the Android app.
self.addEventListener('install', function () { self.skipWaiting(); });
self.addEventListener('activate', function (event) {
  event.waitUntil((async function () {
    try { var keys = await caches.keys(); await Promise.all(keys.map(function (k) { return caches.delete(k); })); } catch (e) {}
    try { await self.registration.unregister(); } catch (e) {}
    try { var cs = await self.clients.matchAll({ type: 'window' }); cs.forEach(function (c) { c.navigate(c.url); }); } catch (e) {}
  })());
});
