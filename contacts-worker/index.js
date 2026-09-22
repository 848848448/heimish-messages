// DEPRECATED stub. The real Contacts worker is in ../worker (full R2 implementation).
// This file is kept only so an accidental deploy of this folder is a harmless no-op.
// Safe to delete this whole `contacts-worker/` directory.
addEventListener('fetch', event => {
  event.respondWith(new Response('Deprecated stub — use the worker in ../worker', {
    status: 410,
    headers: { 'Content-Type': 'text/plain', 'Access-Control-Allow-Origin': '*' }
  }))
})
