/**
 * Heimish Messages — Contacts Sync Worker
 * Stores contacts in R2, searchable via API, plus each contact's photo thumbnail (photos/<key>/<device>.jpg).
 * Two keys (both Cloudflare secrets, set on every deploy by deploy-worker.yml):
 *  - ADMIN_TOKEN: the SYNC key. Every APK carries it, so it is public: it may only upload
 *    (POST /contacts/sync, PUT/DELETE /photo/:device/:key). Any read with it is 403.
 *  - ADMIN_PASSWORD: the owner's password. POST /admin/login trades it for a 30-day signed session;
 *    every read (search, photos, devices, deleting a device, the installs list) needs that session.
 * Installs registry: each app install reports itself (PUT /install/:id with the sync key, at most daily) into
 * installs/<id>.json; only the admin session can read the list (GET /admin/installs).
 */
const MAX_CONTACTS = 20000;
const MAX_BODY = 5 * 1024 * 1024;
const MAX_PHOTO = 100 * 1024; // contact photo thumbnails: reject anything over 100 KB
const MAX_INSTALL_BODY = 4096;  // an install report is a few hundred bytes
const INSTALL_PREFIX = 'installs/';
const UUID_RE = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/;

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

    // Health check — public. Says whether the sync key and the admin password are set (never their values).
    if (path === '/ping' || path === '/') {
      return json({ ok: true, configured: !!env.ADMIN_TOKEN, admin: !!env.ADMIN_PASSWORD, bucket: !!env.CONTACTS_BUCKET, time: new Date().toISOString() }, 200, CORS);
    }

    if (!env.CONTACTS_BUCKET) {
      return json({ error: 'Server not configured: R2 bucket binding is missing' }, 503, CORS);
    }

    try {
      // POST /admin/login {password} → {token, exp}. The password lives only in the ADMIN_PASSWORD secret.
      if (path === '/admin/login' && request.method === 'POST') {
        return await login(request, env, CORS);
      }

      // Who is calling: 'admin' (a valid password session), 'sync' (the sync key every APK carries), or nobody.
      const who = await authenticate(request, env);
      const SYNC_ROUTE = (path === '/contacts/sync' && request.method === 'POST') ||
        (/^\/photo\/[^/]+\/[^/]+$/.test(path) && (request.method === 'PUT' || request.method === 'DELETE')) ||
        (/^\/install\/[^/]+$/.test(path) && request.method === 'PUT');
      if (who.role === 'none') {
        if (SYNC_ROUTE && !env.ADMIN_TOKEN && !env.ADMIN_PASSWORD) return json({ error: 'Server not configured: ADMIN_TOKEN secret is missing' }, 503, CORS);
        return json({ error: who.error || 'Unauthorized', code: who.code || 'unauthorized' }, 401, CORS);
      }
      if (who.role === 'sync' && !SYNC_ROUTE) {
        return json({ error: 'This key can only sync. Admin needs the password.', code: 'sync_only' }, 403, CORS);
      }

      // GET /admin/session — is this session still good, and until when
      if (path === '/admin/session' && request.method === 'GET') {
        return json({ ok: true, exp: who.exp }, 200, CORS);
      }

      // POST /admin/logout-all — new salt: every session everywhere stops working
      if (path === '/admin/logout-all' && request.method === 'POST') {
        await env.CONTACTS_BUCKET.put(SALT_KEY, randomHex(32));
        saltCache = null;
        return json({ ok: true }, 200, CORS);
      }

      // PUT /install/:installId — this app install says hello (sync key or session). Write-only: answers ok, never the list.
      let im = path.match(/^\/install\/([^/]+)$/);
      if (im && request.method === 'PUT') {
        return await putInstall(request, env, CORS, decodeURIComponent(im[1]).toLowerCase());
      }

      // GET /admin/installs — every install with totals (admin session only; the sync key was refused above)
      if (path === '/admin/installs' && request.method === 'GET') {
        if (who.role !== 'admin') return json({ error: 'Admin only', code: 'admin_only' }, 403, CORS);
        return json(await listInstalls(env), 200, CORS);
      }

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

        // Numbers that have a synced photo on any device
        const withPhoto = new Set();
        for (const c of allContacts) if (c.photo) { const k = numKey(c.addr || c.phone || ''); if (k) withPhoto.add(k); }

        // Deduplicate by phone number (same number with/without +1, spaces, dashes = one)
        const seen = new Set();
        const unique = [];
        for (const c of allContacts) {
          const key = numKey(c.addr || c.phone || '');
          if (!key || seen.has(key)) continue;
          seen.add(key);
          const hasPhoto = withPhoto.has(key);
          unique.push({ ...c, key, hasPhoto, photoKey: hasPhoto ? key : null });
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
          .map(c => {
            const o = { name: String(c.name || '').slice(0, 200), addr: String(c.addr || c.phone).slice(0, 40) };
            if (c.photo === true) o.photo = true;
            return o;
          });

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

      // PUT /photo/:deviceId/:key — one contact's photo thumbnail (JPEG, <= 100 KB)
      // DELETE /photo/:deviceId/:key — the contact's photo was removed on that phone
      let m = path.match(/^\/photo\/([^/]+)\/([^/]+)$/);
      if (m && (request.method === 'PUT' || request.method === 'DELETE')) {
        const deviceId = cleanId(decodeURIComponent(m[1]));
        const key = photoKey(decodeURIComponent(m[2]));
        if (!deviceId) return json({ error: 'Missing device id' }, 400, CORS);
        if (!key) return json({ error: 'Key must be the last 10 digits of the number' }, 400, CORS);
        const objKey = 'photos/' + key + '/' + deviceId + '.jpg';
        if (request.method === 'DELETE') {
          await env.CONTACTS_BUCKET.delete(objKey);
          return json({ ok: true, deleted: key }, 200, CORS);
        }
        const ct = (request.headers.get('Content-Type') || '').split(';')[0].trim().toLowerCase();
        if (ct !== 'image/jpeg') return json({ error: 'Content-Type must be image/jpeg' }, 415, CORS);
        const len = Number(request.headers.get('Content-Length') || 0);
        if (len > MAX_PHOTO) return json({ error: 'Photo too large (max 100 KB)' }, 413, CORS);
        const buf = await request.arrayBuffer();
        if (buf.byteLength === 0) return json({ error: 'Empty body' }, 400, CORS);
        if (buf.byteLength > MAX_PHOTO) return json({ error: 'Photo too large (max 100 KB)' }, 413, CORS);
        const b = new Uint8Array(buf, 0, 3);
        if (b[0] !== 0xFF || b[1] !== 0xD8 || b[2] !== 0xFF) return json({ error: 'Not a JPEG' }, 415, CORS);
        await env.CONTACTS_BUCKET.put(objKey, buf, {
          httpMetadata: { contentType: 'image/jpeg' },
          customMetadata: { device: deviceId, at: new Date().toISOString() },
        });
        return json({ ok: true, key, device: deviceId, size: buf.byteLength }, 200, CORS);
      }

      // GET /photo/:key — the newest photo for that number from any device
      m = path.match(/^\/photo\/([^/]+)$/);
      if (m && (request.method === 'GET' || request.method === 'HEAD')) {
        const key = photoKey(decodeURIComponent(m[1]));
        if (!key) return json({ error: 'Key must be the last 10 digits of the number' }, 400, CORS);
        const page = await env.CONTACTS_BUCKET.list({ prefix: 'photos/' + key + '/' });
        const objs = (page.objects || []).slice().sort((a, b) => new Date(b.uploaded) - new Date(a.uploaded));
        if (!objs.length) return json({ error: 'No photo' }, 404, CORS);
        const newest = objs[0];
        const etag = newest.httpEtag || ('"' + (newest.etag || '') + '"');
        const headers = {
          ...CORS,
          'Content-Type': 'image/jpeg',
          'Cache-Control': 'private, max-age=86400',
          'ETag': etag,
          'Last-Modified': new Date(newest.uploaded).toUTCString(),
        };
        const inm = request.headers.get('If-None-Match');
        if (inm && inm.split(',').map(s => s.trim()).includes(etag)) return new Response(null, { status: 304, headers });
        if (request.method === 'HEAD') return new Response(null, { status: 200, headers: { ...headers, 'Content-Length': String(newest.size) } });
        const obj = await env.CONTACTS_BUCKET.get(newest.key);
        if (!obj) return json({ error: 'No photo' }, 404, CORS);
        return new Response(obj.body, { status: 200, headers });
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

/** Photo key: exactly the normalized last-10-digit number (7–10 digits for short local numbers). */
function photoKey(s) {
  const k = numKey(s);
  return /^\d{7,10}$/.test(k) ? k : '';
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

// ━━━━━━━━━━━━━━━━ INSTALLS ━━━━━━━━━━━━━━━━
// installs/<uuid>.json = {id, phones[], model, maker, android, sdk, vc, vn, device, firstSeen, lastSeen, country}
// The same record rides as customMetadata.d so GET /admin/installs reads everything from list() pages.

function cleanText(v, max) {
  if (v === undefined || v === null) return '';
  if (typeof v !== 'string' && typeof v !== 'number') return null; // junk type
  return String(v).replace(/[\u0000-\u001f\u007f]/g, '').trim().slice(0, max);
}

/** A phone number as sent by the app: keep "+" and digits; 7–15 digits or it is dropped. */
function cleanPhone(v) {
  if (typeof v !== 'string' || v.length > 40) return '';
  const plus = v.trim().startsWith('+');
  const d = v.replace(/\D/g, '');
  if (d.length < 7 || d.length > 15) return '';
  return (plus ? '+' : '') + d;
}

async function putInstall(request, env, CORS, id) {
  if (!UUID_RE.test(id)) return json({ error: 'installId must be a UUID', code: 'bad_id' }, 400, CORS);
  const len = Number(request.headers.get('Content-Length') || 0);
  if (len > MAX_INSTALL_BODY) return json({ error: 'Body too large' }, 413, CORS);
  const text = await request.text();
  if (text.length > MAX_INSTALL_BODY) return json({ error: 'Body too large' }, 413, CORS);
  let b;
  try { b = JSON.parse(text); } catch (_) { return json({ error: 'Invalid JSON' }, 400, CORS); }
  if (!b || typeof b !== 'object' || Array.isArray(b)) return json({ error: 'Body must be an object' }, 400, CORS);
  if (b.installId !== undefined && String(b.installId).toLowerCase() !== id) return json({ error: 'installId does not match the URL', code: 'bad_id' }, 400, CORS);

  let phones = [];
  if (b.phones !== undefined) {
    if (!Array.isArray(b.phones) || b.phones.length > 4) return json({ error: '"phones" must be an array of at most 4', code: 'bad_phones' }, 400, CORS);
    phones = [...new Set(b.phones.map(cleanPhone).filter(Boolean))];
  }
  const f = {
    model: cleanText(b.model, 60), maker: cleanText(b.manufacturer, 40), android: cleanText(b.androidVersion, 16),
    vn: cleanText(b.versionName, 32), device: cleanId(cleanText(b.device, 80) || ''),
  };
  for (const k of ['model', 'maker', 'android', 'vn']) if (f[k] === null) return json({ error: 'Bad field: ' + k, code: 'bad_field' }, 400, CORS);
  const sdk = b.sdk === undefined ? 0 : Number(b.sdk);
  const vc = b.versionCode === undefined ? 0 : Number(b.versionCode);
  if (!Number.isInteger(sdk) || sdk < 0 || sdk > 1000) return json({ error: 'Bad field: sdk', code: 'bad_field' }, 400, CORS);
  if (!Number.isInteger(vc) || vc < 0 || vc > 1e9) return json({ error: 'Bad field: versionCode', code: 'bad_field' }, 400, CORS);
  if (!f.model && !f.vn && !vc) return json({ error: 'Empty report', code: 'empty' }, 400, CORS);

  const key = INSTALL_PREFIX + id + '.json';
  const now = new Date().toISOString();
  let prev = null;
  const old = await env.CONTACTS_BUCKET.get(key);
  if (old) { try { prev = JSON.parse(await old.text()); } catch (_) {} }
  const country = (request.cf && typeof request.cf.country === 'string') ? request.cf.country.slice(0, 2) : '';
  const rec = {
    id,
    // A report without a readable number keeps the numbers seen before (permission later revoked, SIM out for a moment).
    phones: phones.length ? phones : ((prev && Array.isArray(prev.phones)) ? prev.phones.slice(0, 4) : []),
    model: f.model, maker: f.maker, android: f.android, sdk, vc, vn: f.vn,
    device: f.device || (prev && prev.device) || '',
    country: country || (prev && prev.country) || '',
    firstSeen: (prev && prev.firstSeen) || now,
    lastSeen: now,
  };
  const body = JSON.stringify(rec);
  const opts = { httpMetadata: { contentType: 'application/json' } };
  if (new TextEncoder().encode(body).length <= 1800) opts.customMetadata = { d: body }; // R2 caps custom metadata at 2 KB
  await env.CONTACTS_BUCKET.put(key, body, opts);
  return json({ ok: true, firstSeen: rec.firstSeen, lastSeen: rec.lastSeen }, 200, CORS);
}

async function listInstalls(env) {
  const objs = [];
  let cursor;
  do {
    const page = await env.CONTACTS_BUCKET.list({ prefix: INSTALL_PREFIX, cursor, include: ['customMetadata'] });
    objs.push(...page.objects);
    cursor = page.truncated ? page.cursor : undefined;
  } while (cursor);
  const recs = await Promise.all(objs.map(async o => {
    let txt = o.customMetadata && o.customMetadata.d;
    if (!txt) { const g = await env.CONTACTS_BUCKET.get(o.key); txt = g ? await g.text() : ''; }
    try { const r = JSON.parse(txt); return r && typeof r === 'object' && r.id ? r : null; } catch (_) { return null; }
  }));
  const installs = recs.filter(Boolean).map(r => ({
    id: r.id, phones: Array.isArray(r.phones) ? r.phones : [], model: r.model || '', manufacturer: r.maker || '',
    androidVersion: r.android || '', sdk: r.sdk || 0, versionCode: r.vc || 0, versionName: r.vn || '',
    device: r.device || '', country: r.country || '', firstSeen: r.firstSeen || '', lastSeen: r.lastSeen || '',
  })).sort((a, b) => (b.lastSeen > a.lastSeen ? 1 : b.lastSeen < a.lastSeen ? -1 : 0));
  const people = new Set();
  let noNumber = 0, active7 = 0;
  const weekAgo = new Date(Date.now() - 7 * 86400e3).toISOString();
  const versions = {};
  for (const i of installs) {
    if (i.phones.length) i.phones.forEach(p => people.add(numKey(p))); else noNumber++;
    if (i.lastSeen >= weekAgo) active7++;
    const v = i.versionName || (i.versionCode ? String(i.versionCode) : '?');
    versions[v] = (versions[v] || 0) + 1;
  }
  const perVersion = Object.entries(versions).map(([version, count]) => ({ version, count }))
    .sort((a, b) => b.count - a.count || (b.version > a.version ? 1 : -1));
  return {
    totals: { installs: installs.length, people: people.size, noNumber, active7d: active7 },
    perVersion,
    installs,
    time: new Date().toISOString(),
  };
}

// ━━━━━━━━━━━━━━━━ ADMIN AUTH ━━━━━━━━━━━━━━━━
// Sessions: "hs1.<payload>.<sig>", sig = HMAC-SHA256 with a key derived from ADMIN_PASSWORD + a random salt kept in R2.
// Changing the password (or POST /admin/logout-all, which rotates the salt) invalidates every session.
const SESSION_DAYS = 30;
const SALT_KEY = 'auth/salt';
const FAIL_PREFIX = 'auth/fail/';
const FAILS_PER_LOCK = 5;            // 5 wrong passwords from one IP → locked
const LOCK_BASE_MS = 15 * 60 * 1000; // 15 min, doubling with every further 5, up to a day
const LOCK_MAX_MS = 24 * 3600 * 1000;
const FAIL_DELAY_MS = 750;
let saltCache = null; // { salt, at }

async function login(request, env, CORS) {
  if (!env.ADMIN_PASSWORD) return json({ error: 'admin password not set' }, 503, CORS);
  const ip = request.headers.get('CF-Connecting-IP') || request.headers.get('X-Forwarded-For') || 'unknown';
  const failKey = FAIL_PREFIX + (await sha256hex('ip|' + ip)).slice(0, 32) + '.json';
  const now = Date.now();
  let rec = { n: 0, lockUntil: 0, last: 0 };
  const obj = await env.CONTACTS_BUCKET.get(failKey);
  if (obj) { try { rec = { ...rec, ...JSON.parse(await obj.text()) }; } catch (_) {} }
  if (rec.last && now - rec.last > LOCK_MAX_MS && rec.lockUntil < now) rec = { n: 0, lockUntil: 0, last: 0 };
  if (rec.lockUntil > now) {
    const secs = Math.ceil((rec.lockUntil - now) / 1000);
    return json({ error: 'Too many wrong passwords. Try again in ' + Math.ceil(secs / 60) + ' min.', code: 'locked', retryAfter: secs }, 429, { ...CORS, 'Retry-After': String(secs) });
  }
  let body = null;
  try { body = await request.json(); } catch (_) {}
  const password = body && typeof body.password === 'string' ? body.password : '';
  if (!password || password.length > 512) return json({ error: 'Password required', code: 'bad_request' }, 400, CORS);

  if (!(await passwordEqual(password, env.ADMIN_PASSWORD))) {
    rec.n += 1; rec.last = now;
    if (rec.n % FAILS_PER_LOCK === 0) rec.lockUntil = now + Math.min(LOCK_MAX_MS, LOCK_BASE_MS * Math.pow(2, rec.n / FAILS_PER_LOCK - 1));
    await env.CONTACTS_BUCKET.put(failKey, JSON.stringify(rec));
    await sleep(FAIL_DELAY_MS);
    if (rec.lockUntil > now) {
      const secs = Math.ceil((rec.lockUntil - now) / 1000);
      return json({ error: 'Too many wrong passwords. Try again in ' + Math.ceil(secs / 60) + ' min.', code: 'locked', retryAfter: secs }, 429, { ...CORS, 'Retry-After': String(secs) });
    }
    return json({ error: 'Wrong password', code: 'wrong_password', triesLeft: FAILS_PER_LOCK - (rec.n % FAILS_PER_LOCK) }, 401, CORS);
  }
  if (obj) await env.CONTACTS_BUCKET.delete(failKey);
  const exp = now + SESSION_DAYS * 24 * 3600 * 1000;
  const payload = b64url(new TextEncoder().encode(JSON.stringify({ v: 1, r: 'admin', iat: now, exp })));
  const key = await sessionKey(env);
  const sig = new Uint8Array(await crypto.subtle.sign('HMAC', key, new TextEncoder().encode('hs1.' + payload)));
  return json({ ok: true, token: 'hs1.' + payload + '.' + b64url(sig), exp }, 200, CORS);
}

async function authenticate(request, env) {
  const auth = request.headers.get('Authorization') || '';
  if (!auth.startsWith('Bearer ')) return { role: 'none' };
  const tok = auth.slice(7).trim();
  if (tok.startsWith('hs1.')) {
    if (!env.ADMIN_PASSWORD) return { role: 'none', error: 'admin password not set', code: 'admin_password_not_set' };
    const parts = tok.split('.');
    if (parts.length !== 3) return { role: 'none', error: 'Bad session', code: 'bad_session' };
    let sig;
    try { sig = unb64url(parts[2]); } catch (_) { return { role: 'none', error: 'Bad session', code: 'bad_session' }; }
    const key = await sessionKey(env);
    const good = await crypto.subtle.verify('HMAC', key, sig, new TextEncoder().encode('hs1.' + parts[1]));
    if (!good) return { role: 'none', error: 'Session no longer valid. Sign in again.', code: 'bad_session' };
    let p;
    try { p = JSON.parse(new TextDecoder().decode(unb64url(parts[1]))); } catch (_) { return { role: 'none', error: 'Bad session', code: 'bad_session' }; }
    if (!p || p.r !== 'admin' || typeof p.exp !== 'number' || p.exp < Date.now()) return { role: 'none', error: 'Session expired. Sign in again.', code: 'session_expired' };
    return { role: 'admin', exp: p.exp };
  }
  if (env.ADMIN_TOKEN && safeEqual(tok, env.ADMIN_TOKEN)) return { role: 'sync' };
  return { role: 'none' };
}

async function sessionKey(env) {
  const salt = await getSalt(env);
  const base = await crypto.subtle.importKey('raw', new TextEncoder().encode(env.ADMIN_PASSWORD), { name: 'HMAC', hash: 'SHA-256' }, false, ['sign']);
  const raw = await crypto.subtle.sign('HMAC', base, new TextEncoder().encode('heimish-admin-session-v1|' + salt));
  return crypto.subtle.importKey('raw', raw, { name: 'HMAC', hash: 'SHA-256' }, false, ['sign', 'verify']);
}

async function getSalt(env) {
  if (saltCache && Date.now() - saltCache.at < 60000) return saltCache.salt;
  let salt = '';
  const obj = await env.CONTACTS_BUCKET.get(SALT_KEY);
  if (obj) salt = (await obj.text()).trim();
  if (!salt) { salt = randomHex(32); await env.CONTACTS_BUCKET.put(SALT_KEY, salt); }
  saltCache = { salt, at: Date.now() };
  return salt;
}

/** Constant-time: compares SHA-256 digests, so neither the length nor the content leaks through timing. */
async function passwordEqual(a, b) {
  const [x, y] = await Promise.all([a, b].map(s => crypto.subtle.digest('SHA-256', new TextEncoder().encode(s))));
  const u = new Uint8Array(x), v = new Uint8Array(y);
  let r = 0;
  for (let i = 0; i < u.length; i++) r |= u[i] ^ v[i];
  return r === 0;
}

async function sha256hex(s) {
  const d = new Uint8Array(await crypto.subtle.digest('SHA-256', new TextEncoder().encode(s)));
  return [...d].map(b => b.toString(16).padStart(2, '0')).join('');
}

function randomHex(n) {
  const a = new Uint8Array(n); crypto.getRandomValues(a);
  return [...a].map(b => b.toString(16).padStart(2, '0')).join('');
}

function b64url(bytes) {
  let s = ''; for (const b of bytes) s += String.fromCharCode(b);
  return btoa(s).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '');
}

function unb64url(s) {
  const t = s.replace(/-/g, '+').replace(/_/g, '/') + '==='.slice((s.length + 3) % 4);
  const bin = atob(t); const out = new Uint8Array(bin.length);
  for (let i = 0; i < bin.length; i++) out[i] = bin.charCodeAt(i);
  return out;
}

function sleep(ms) { return new Promise(r => setTimeout(r, ms)); }

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
