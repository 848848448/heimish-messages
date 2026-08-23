/**
 * Heimish Messages — Contacts Sync Worker
 * Stores contacts in R2, searchable via API
 */
export default {
  async fetch(request, env) {
    const CORS = {
      'Access-Control-Allow-Origin': '*',
      'Access-Control-Allow-Methods': 'GET,POST,PUT,DELETE,OPTIONS',
      'Access-Control-Allow-Headers': 'Content-Type,Authorization',
    };
    if (request.method === 'OPTIONS') return new Response(null, { headers: CORS });

    const url = new URL(request.url);
    const path = url.pathname;
    const auth = request.headers.get('Authorization') || '';
    const validToken = env.ADMIN_TOKEN || 'hm_admin_avrumy_2024';

    if (auth !== 'Bearer ' + validToken) {
      return json({ error: 'Unauthorized' }, 401, CORS);
    }

    try {
      // GET /contacts?q=search&device=deviceId
      if (path === '/contacts' && request.method === 'GET') {
        const q = (url.searchParams.get('q') || '').toLowerCase().trim();
        const device = url.searchParams.get('device') || '';
        
        // List all device files or a specific one
        let allContacts = [];
        if (device) {
          const obj = await env.CONTACTS_BUCKET.get('devices/' + device + '.json');
          if (obj) allContacts = JSON.parse(await obj.text());
        } else {
          // List all devices
          const list = await env.CONTACTS_BUCKET.list({ prefix: 'devices/' });
          for (const item of list.objects) {
            const obj = await env.CONTACTS_BUCKET.get(item.key);
            if (obj) {
              const devContacts = JSON.parse(await obj.text());
              const devName = item.key.replace('devices/', '').replace('.json', '');
              devContacts.forEach(c => { c._device = devName; });
              allContacts = allContacts.concat(devContacts);
            }
          }
        }

        // Deduplicate by phone number
        const seen = {};
        const unique = [];
        for (const c of allContacts) {
          const key = (c.addr || c.phone || '').replace(/\D/g, '');
          if (!key || seen[key]) continue;
          seen[key] = true;
          unique.push(c);
        }

        // Search
        let results = unique;
        if (q) {
          results = unique.filter(c =>
            (c.name || '').toLowerCase().includes(q) ||
            (c.addr || c.phone || '').replace(/\D/g, '').includes(q.replace(/\D/g, ''))
          );
        }

        return json({
          total: unique.length,
          results: results.length,
          contacts: results.slice(0, 500)
        }, 200, CORS);
      }

      // POST /contacts/sync — upload contacts from a device
      if (path === '/contacts/sync' && request.method === 'POST') {
        const body = await request.json();
        const deviceId = body.device || 'unknown';
        const contacts = body.contacts || [];
        
        await env.CONTACTS_BUCKET.put(
          'devices/' + deviceId + '.json',
          JSON.stringify(contacts),
          { httpMetadata: { contentType: 'application/json' } }
        );

        return json({
          ok: true,
          device: deviceId,
          count: contacts.length,
          synced: new Date().toISOString()
        }, 200, CORS);
      }

      // GET /devices — list all synced devices
      if (path === '/devices' && request.method === 'GET') {
        const list = await env.CONTACTS_BUCKET.list({ prefix: 'devices/' });
        const devices = [];
        for (const item of list.objects) {
          const name = item.key.replace('devices/', '').replace('.json', '');
          const obj = await env.CONTACTS_BUCKET.get(item.key);
          const contacts = obj ? JSON.parse(await obj.text()) : [];
          devices.push({
            id: name,
            contacts: contacts.length,
            lastSync: item.uploaded
          });
        }
        return json({ devices }, 200, CORS);
      }

      // DELETE /contacts/device/:id — remove a device's contacts
      if (path.startsWith('/contacts/device/') && request.method === 'DELETE') {
        const deviceId = path.replace('/contacts/device/', '');
        await env.CONTACTS_BUCKET.delete('devices/' + deviceId + '.json');
        return json({ ok: true, deleted: deviceId }, 200, CORS);
      }

      // GET /ping
      if (path === '/ping') return json({ ok: true, time: new Date().toISOString() }, 200, CORS);

      return json({ error: 'Not found' }, 404, CORS);
    } catch (e) {
      return json({ error: e.message }, 500, CORS);
    }
  }
};

function json(data, status = 200, cors = {}) {
  return new Response(JSON.stringify(data), {
    status,
    headers: { 'Content-Type': 'application/json', ...cors }
  });
}
