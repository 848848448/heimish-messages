/**
 * Heimish Messages — Contacts Sync Worker
 * Stores contacts in R2, searchable via API.
 * ADMIN_TOKEN is a Cloudflare secret (set on every deploy by deploy-worker.yml).
 */
const MAX_CONTACTS = 20000;
const MAX_BODY = 5 * 1024 * 1024;

export default {
  async fetch(request, env) {
    const CORS = {
      'Access-Control-Allow-Origin': '*',
      'Access-Control-Allow-Methods': 'GET,POST,PUT,DELETE,OPTIONS',
      'Access-Control-Allow-Headers': 'Content-Type,Authorization',
      'Access-Control-Max-Age': '86400',
    };
    if (request.method === 'OPTIONS') return new Response(null, { status: 204, headers: CORS });

    const url = new URL(request.url);
    const path = url.pathname.replace(/\/+$/, '') || '/';

    // Health check — public, says whether the worker is configured (never the token).
    if (path === '/ping' || path === '/') {
      return json({ ok: true, configured: !!env.ADMIN_TOKEN, bucket: !!env.CONTACTS_BUCKET, time: new Date().toISOString() }, 200, CORS);
    }

    if (!env.ADMIN_TOKEN) {
      return json({ error: 'Server not configured: ADMIN_TOKEN secret is missing' }, 503, CORS);
    }
    if (!env.CONTACTS_BUCKET) {
      return json({ error: 'Server not configured: R2 bucket binding is missing' }, 503, CORS);
    }
    const auth = request.headers.get('Authorization') || '';
    if (!safeEqual(auth, 'Bearer ' + env.ADMIN_TOKEN)) {
      return json({ error: 'Unauthorized' }, 401, CORS);
    }

    try {
      // GET /contacts?q=search&device=deviceId
      if (path === '/contacts' && request.method === 'GET') {
        const q = (url.searchParams.get('q') || '').toLowerCase().trim();
        const qDigits = q.replace(/\D/g, '');
        const device = cleanId(url.searchParams.get('device') || '');

        let allContacts = [];
        if (device) {
          allContacts = tag(await readDevice(env, 'devices/' + device + '.json'), device);
        } else {
          const keys = await listDeviceKeys(env);
          const lists = await Promise.all(keys.map(k => readDevice(env, k.key)));
          lists.forEach((l, i) => { allContacts = allContacts.concat(tag(l, deviceName(keys[i].key))); });
        }

        // Deduplicate by phone number (same number with/without +1, spaces, dashes = one)
        const seen = new Set();
        const unique = [];
        for (const c of allContacts) {
          const key = numKey(c.addr || c.phone || '');
          if (!key || seen.has(key)) continue;
          seen.add(key);
          unique.push(c);
        }

        let results = unique;
        if (q) {
          results = unique.filter(c => {
            if ((c.name || '').toLowerCase().includes(q)) return true;
            // Only match numbers when the query actually contains digits
            // (otherwise "" matched every contact).
            if (qDigits.length === 0) return false;
            const d = String(c.addr || c.phone || '').replace(/\D/g, '');
            return d.includes(qDigits) || numKey(d).includes(numKey(qDigits));
          });
        }

        return json({ total: unique.length, results: results.length, contacts: results.slice(0, 500) }, 200, CORS);
      }

      // POST /contacts/sync — upload contacts from a device
      if (path === '/contacts/sync' && request.method === 'POST') {
        const len = Number(request.headers.get('Content-Length') || 0);
        if (len > MAX_BODY) return json({ error: 'Body too large' }, 413, CORS);
        let body;
        try { body = await request.json(); } catch (_) { return json({ error: 'Invalid JSON' }, 400, CORS); }
        const deviceId = cleanId(body && body.device) || 'unknown';
        if (!Array.isArray(body && body.contacts)) return json({ error: '"contacts" must be an array' }, 400, CORS);
        const contacts = body.contacts
          .filter(c => c && typeof c === 'object' && (c.addr || c.phone))
          .slice(0, MAX_CONTACTS)
          .map(c => ({ name: String(c.name || '').slice(0, 200), addr: String(c.addr || c.phone).slice(0, 40) }));

        await env.CONTACTS_BUCKET.put('devices/' + deviceId + '.json', JSON.stringify(contacts),
          { httpMetadata: { contentType: 'application/json' } });

        return json({ ok: true, device: deviceId, count: contacts.length, synced: new Date().toISOString() }, 200, CORS);
      }

      // GET /devices — list all synced devices
      if (path === '/devices' && request.method === 'GET') {
        const keys = await listDeviceKeys(env);
        const lists = await Promise.all(keys.map(k => readDevice(env, k.key)));
        const devices = keys.map((k, i) => ({ id: deviceName(k.key), contacts: lists[i].length, lastSync: k.uploaded }));
        return json({ devices }, 200, CORS);
      }

      // DELETE /contacts/device/:id — remove a device's contacts
      if (path.startsWith('/contacts/device/') && request.method === 'DELETE') {
        const deviceId = cleanId(decodeURIComponent(path.slice('/contacts/device/'.length)));
        if (!deviceId) return json({ error: 'Missing device id' }, 400, CORS);
        await env.CONTACTS_BUCKET.delete('devices/' + deviceId + '.json');
        return json({ ok: true, deleted: deviceId }, 200, CORS);
      }

      return json({ error: 'Not found' }, 404, CORS);
    } catch (e) {
      return json({ error: (e && e.message) || 'Internal error' }, 500, CORS);
    }
  }
};

/** Last 10 digits of a number, so +1 718…, 1-718… and 718… are the same contact. */
function numKey(s) {
  const d = String(s || '').replace(/\D/g, '');
  return d.length > 10 ? d.slice(-10) : d;
}

/** Device ids become R2 key names: keep them to safe characters. */
function cleanId(s) {
  return String(s || '').replace(/[^A-Za-z0-9._-]/g, '-').replace(/\.{2,}/g, '.').replace(/^[.-]+/, '').slice(0, 80);
}

function deviceName(key) {
  return key.replace(/^devices\//, '').replace(/\.json$/, '');
}

function tag(list, dev) {
  return list.map(c => ({ ...c, _device: dev }));
}

async function listDeviceKeys(env) {
  const out = [];
  let cursor;
  do {
    const page = await env.CONTACTS_BUCKET.list({ prefix: 'devices/', cursor });
    out.push(...page.objects);
    cursor = page.truncated ? page.cursor : undefined;
  } while (cursor);
  return out;
}

async function readDevice(env, key) {
  const obj = await env.CONTACTS_BUCKET.get(key);
  if (!obj) return [];
  try {
    const v = JSON.parse(await obj.text());
    return Array.isArray(v) ? v : [];
  } catch (_) {
    return []; // one corrupt device file must not break search for all devices
  }
}

function safeEqual(a, b) {
  if (a.length !== b.length) return false;
  let r = 0;
  for (let i = 0; i < a.length; i++) r |= a.charCodeAt(i) ^ b.charCodeAt(i);
  return r === 0;
}

function json(data, status = 200, cors = {}) {
  return new Response(JSON.stringify(data), {
    status,
    headers: { 'Content-Type': 'application/json', 'Cache-Control': 'no-store', ...cors }
  });
}
