addEventListener('fetch', event => {
  event.respondWith(handleRequest(event.request))
})

const CORS = {
  'Access-Control-Allow-Origin': '*',
  'Access-Control-Allow-Methods': 'GET,POST,DELETE,OPTIONS',
  'Access-Control-Allow-Headers': 'Content-Type,Authorization',
}

// Simple in-memory store (resets on worker restart - will add KV later)
let memContacts = []
let memReports = {}

async function handleRequest(request) {
  if (request.method === 'OPTIONS') return new Response(null, { headers: CORS })
  
  const url = new URL(request.url)
  const path = url.pathname
  const auth = request.headers.get('Authorization') || ''
  const isAdmin = auth === 'Bearer hm_admin_avrumy_2024'

  if (path === '/ping') return json({ ok: true })

  // Try KV if available, fallback to memory
  const store = typeof CONTACTS !== 'undefined' ? CONTACTS : null

  if (path === '/contacts' && request.method === 'GET') {
    if (store) {
      const data = await store.get('contacts')
      return json(data ? JSON.parse(data) : [])
    }
    return json(memContacts)
  }

  if (path === '/contacts' && request.method === 'POST') {
    if (!isAdmin) return json({ error: 'Unauthorized' }, 401)
    const { name, addr } = await request.json()
    if (!addr) return json({ error: 'addr required' }, 400)
    const contact = { name: name || addr, addr, added: new Date().toISOString() }
    if (store) {
      const contacts = JSON.parse((await store.get('contacts')) || '[]')
      const idx = contacts.findIndex(c => c.addr === addr)
      if (idx >= 0) contacts[idx] = contact; else contacts.push(contact)
      await store.put('contacts', JSON.stringify(contacts))
    } else {
      const idx = memContacts.findIndex(c => c.addr === addr)
      if (idx >= 0) memContacts[idx] = contact; else memContacts.push(contact)
    }
    return json({ ok: true, contact })
  }

  if (path.startsWith('/contacts/') && request.method === 'DELETE') {
    if (!isAdmin) return json({ error: 'Unauthorized' }, 401)
    const addr = decodeURIComponent(path.slice(10))
    if (store) {
      const contacts = JSON.parse((await store.get('contacts')) || '[]')
      await store.put('contacts', JSON.stringify(contacts.filter(c => c.addr !== addr)))
    } else {
      memContacts = memContacts.filter(c => c.addr !== addr)
    }
    return json({ ok: true })
  }

  if (path === '/report' && request.method === 'POST') {
    const { deviceId, addr, found, lastSeen } = await request.json()
    if (!addr || !deviceId) return json({ error: 'missing' }, 400)
    const key = 'rpt:' + addr.replace(/\D/g, '')
    const rep = { deviceId, found: !!found, lastSeen: lastSeen || null, time: new Date().toISOString() }
    if (store) {
      const reports = JSON.parse((await store.get(key)) || '[]')
      const idx = reports.findIndex(r => r.deviceId === deviceId)
      if (idx >= 0) reports[idx] = rep; else reports.push(rep)
      await store.put(key, JSON.stringify(reports), { expirationTtl: 2592000 })
    } else {
      if (!memReports[key]) memReports[key] = []
      const idx = memReports[key].findIndex(r => r.deviceId === deviceId)
      if (idx >= 0) memReports[key][idx] = rep; else memReports[key].push(rep)
    }
    return json({ ok: true })
  }

  if (path.startsWith('/reports/') && request.method === 'GET') {
    if (!isAdmin) return json({ error: 'Unauthorized' }, 401)
    const addr = decodeURIComponent(path.slice(9))
    const key = 'rpt:' + addr.replace(/\D/g, '')
    if (store) return json(JSON.parse((await store.get(key)) || '[]'))
    return json(memReports[key] || [])
  }

  return json({ error: 'Not found' }, 404)
}

function json(data, status) {
  return new Response(JSON.stringify(data), {
    status: status || 200,
    headers: Object.assign({ 'Content-Type': 'application/json' }, CORS)
  })
}
// Thu Aug 20 07:45:02 UTC 2026
