/**
 * Heimish Messages — Contacts Worker
 * Cross-device contact sync for admin
 */
const CORS = {
  'Access-Control-Allow-Origin': '*',
  'Access-Control-Allow-Methods': 'GET,POST,DELETE,OPTIONS',
  'Access-Control-Allow-Headers': 'Content-Type,Authorization',
};

export default {
  async fetch(request, env) {
    if (request.method === 'OPTIONS') return new Response(null, { headers: CORS });
    const url = new URL(request.url);
    const path = url.pathname;
    const auth = request.headers.get('Authorization') || '';
    const token = env.ADMIN_TOKEN || 'hm_admin_avrumy_2024';
    const isAdmin = auth === 'Bearer ' + token;

    if (path === '/ping') return json({ ok: true, time: new Date().toISOString() });

    if (path === '/contacts' && request.method === 'GET') {
      const data = (await env.CONTACTS?.get('contacts')) || '[]';
      return json(JSON.parse(data));
    }
    if (path === '/contacts' && request.method === 'POST') {
      if (!isAdmin) return json({ error: 'Unauthorized' }, 401);
      const { name, addr } = await request.json();
      if (!addr) return json({ error: 'addr required' }, 400);
      const contacts = JSON.parse((await env.CONTACTS?.get('contacts')) || '[]');
      const idx = contacts.findIndex(c => c.addr === addr);
      const contact = { name: name || addr, addr, added: new Date().toISOString() };
      if (idx >= 0) contacts[idx] = contact;
      else contacts.push(contact);
      await env.CONTACTS?.put('contacts', JSON.stringify(contacts));
      return json({ ok: true, contact });
    }
    if (path.startsWith('/contacts/') && request.method === 'DELETE') {
      if (!isAdmin) return json({ error: 'Unauthorized' }, 401);
      const addr = decodeURIComponent(path.slice(10));
      const contacts = JSON.parse((await env.CONTACTS?.get('contacts')) || '[]');
      await env.CONTACTS?.put('contacts', JSON.stringify(contacts.filter(c => c.addr !== addr)));
      return json({ ok: true });
    }
    if (path === '/report' && request.method === 'POST') {
      const { deviceId, addr, found, lastSeen } = await request.json();
      if (!addr || !deviceId) return json({ error: 'missing' }, 400);
      const key = 'rpt:' + addr.replace(/[^0-9+]/g,'');
      const reports = JSON.parse((await env.CONTACTS?.get(key)) || '[]');
      const idx = reports.findIndex(r => r.deviceId === deviceId);
      const rep = { deviceId, found: !!found, lastSeen: lastSeen||null, time: new Date().toISOString() };
      if (idx >= 0) reports[idx] = rep; else reports.push(rep);
      await env.CONTACTS?.put(key, JSON.stringify(reports), { expirationTtl: 2592000 });
      return json({ ok: true });
    }
    if (path.startsWith('/reports/') && request.method === 'GET') {
      if (!isAdmin) return json({ error: 'Unauthorized' }, 401);
      const addr = decodeURIComponent(path.slice(9));
      const key = 'rpt:' + addr.replace(/[^0-9+]/g,'');
      return json(JSON.parse((await env.CONTACTS?.get(key)) || '[]'));
    }
    return json({ error: 'Not found' }, 404);
  }
};

function json(data, status = 200) {
  return new Response(JSON.stringify(data), {
    status, headers: { 'Content-Type': 'application/json', ...CORS }
  });
}
