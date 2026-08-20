addEventListener('fetch', event => {
  event.respondWith(new Response('Heimish Contacts Worker OK', {
    headers: { 'Content-Type': 'text/plain', 'Access-Control-Allow-Origin': '*' }
  }))
})
