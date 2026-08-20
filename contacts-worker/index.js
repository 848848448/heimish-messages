/**
 * Heimish Messages — Contacts Worker
 * Admin can store contacts → all devices can look them up
 * Devices report if a number exists in their SMS
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
    const isAdmin = auth === 'Bearer ' + (env.ADMIN_TOKEN || 'hm_admin_avrumy_2024');

    // ── GET /contacts — list all admin contacts (public, for all devices)
    if (path === '/contacts' && request.method === 'GET') {
      const data = await env.CONTACTS.get('contacts');
      return json(JSON.parse(data || '[]'));
    }

    // ── POST /contacts — add a contact (admin only)
    if (path === '/contacts' && request.method === 'POST') {
      if (!isAdmin) return json({ error: 'Unauthorized' }, 401);
      const { name, addr } = await request.json();
      if (!addr) return json({ error: 'addr required' }, 400);
      const contacts = JSON.parse(await env.CONTACTS.get('contacts') || '[]');
      const existing = contacts.findIndex(c => c.addr === addr);
      const contact = { name: name || addr, addr, added: new Date().toISOString() };
      if (existing >= 0) contacts[existing] = contact;
      else contacts.push(contact);
      await env.CONTACTS.put('contacts', JSON.stringify(contacts));
      return json({ ok: true, contact });
    }

    // ── DELETE /contacts/:addr — remove a contact (admin only)
    if (path.startsWith('/contacts/') && request.method === 'DELETE') {
      if (!isAdmin) return json({ error: 'Unauthorized' }, 401);
      const addr = decodeURIComponent(path.slice(10));
      const contacts = JSON.parse(await env.CONTACTS.get('contacts') || '[]');
      const filtered = contacts.filter(c => c.addr !== addr);
      await env.CONTACTS.put('contacts', JSON.stringify(filtered));
      return json({ ok: true });
    }

    // ── POST /report — device reports if a number exists in its SMS
    if (path === '/report' && request.method === 'POST') {
      const { deviceId, addr, found, lastSeen } = await request.json();
      if (!addr || !deviceId) return json({ error: 'missing fields' }, 400);
      const key = 'report:' + addr;
      const reports = JSON.parse(await env.CONTACTS.get(key) || '[]');
      const idx = reports.findIndex(r => r.deviceId === deviceId);
      const report = { deviceId, found: !!found, lastSeen: lastSeen || null, time: new Date().toISOString() };
      if (idx >= 0) reports[idx] = report;
      else reports.push(report);
      await env.CONTACTS.put(key, JSON.stringify(reports), { expirationTtl: 86400 * 30 });
      return json({ ok: true });
    }

    // ── GET /reports/:addr — get all device reports for a number (admin only)
    if (path.startsWith('/reports/') && request.method === 'GET') {
      if (!isAdmin) return json({ error: 'Unauthorized' }, 401);
      const addr = decodeURIComponent(path.slice(9));
      const reports = JSON.parse(await env.CONTACTS.get('report:' + addr) || '[]');
      return json(reports);
    }

    return json({ error: 'Not found' }, 404);
  }
};

function json(data, status = 200) {
  return new Response(JSON.stringify(data), {
    status, headers: { 'Content-Type': 'application/json', ...CORS }
  });
}
