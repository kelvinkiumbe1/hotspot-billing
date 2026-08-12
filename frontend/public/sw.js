// Minimal service worker — its presence (with a fetch handler) is what makes
// the app installable; it deliberately does no caching so it can never serve
// a stale build of a billing app.
self.addEventListener('install', () => self.skipWaiting())
self.addEventListener('activate', (event) => event.waitUntil(self.clients.claim()))
self.addEventListener('fetch', () => { /* pass through to network */ })
