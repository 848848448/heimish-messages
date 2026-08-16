/**
 * Heimish Messages — Cloudflare Worker
 * - Receives SMS sync from Android app (POST /api/sync)
 * - Serves web panel UI (GET /)
 * - Google OAuth login
 * - REST API for conversations / messages / send / delete / search
 */

const HTML = `<!DOCTYPE html>
<html lang="en">
<head>
<meta charset="UTF-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>Heimish Messages</title>
<style>
*{box-sizing:border-box;margin:0;padding:0;font-family:-apple-system,BlinkMacSystemFont,'Segoe UI',sans-serif}
body{display:flex;height:100vh;overflow:hidden;background:#f0f2f5}
/* sidebar */
#sidebar{width:340px;min-width:260px;background:#fff;border-right:1px solid #e9edef;display:flex;flex-direction:column}
#sidebar-header{background:#128C7E;color:#fff;padding:14px 16px;display:flex;align-items:center;gap:10px}
#sidebar-header h1{font-size:18px;font-weight:600;flex:1}
#search-box{padding:8px 12px;background:#f0f2f5}
#search-box input{width:100%;padding:8px 14px;border-radius:20px;border:none;background:#fff;font-size:14px;outline:none}
#conv-list{flex:1;overflow-y:auto}
.conv-item{display:flex;align-items:center;padding:12px 16px;cursor:pointer;border-bottom:1px solid #f0f2f5;gap:12px}
.conv-item:hover,.conv-item.active{background:#f0f2f5}
.avatar{width:46px;height:46px;border-radius:50%;display:flex;align-items:center;justify-content:center;font-weight:700;font-size:18px;color:#fff;flex-shrink:0}
.conv-info{flex:1;min-width:0}
.conv-name{font-weight:600;font-size:15px;color:#111b21;white-space:nowrap;overflow:hidden;text-overflow:ellipsis}
.conv-snippet{font-size:13px;color:#667781;white-space:nowrap;overflow:hidden;text-overflow:ellipsis;margin-top:2px}
.conv-meta{text-align:right;font-size:11px;color:#667781;flex-shrink:0}
.unread-dot{background:#128C7E;color:#fff;border-radius:50%;width:18px;height:18px;font-size:10px;display:flex;align-items:center;justify-content:center;margin-top:4px;margin-left:auto}
/* main */
#main{flex:1;display:flex;flex-direction:column;background:#ece5dd}
#chat-header{background:#128C7E;color:#fff;padding:12px 16px;display:flex;align-items:center;gap:12px}
#chat-header .avatar{width:38px;height:38px;font-size:15px}
#chat-header-info{flex:1}
#chat-header-info h2{font-size:16px;font-weight:600}
#chat-header-info span{font-size:12px;opacity:.8}
#chat-actions button{background:none;border:none;color:#fff;cursor:pointer;font-size:20px;padding:6px;border-radius:50%;display:flex;align-items:center;justify-content:center}
#chat-actions button:hover{background:rgba(255,255,255,.15)}
#messages{flex:1;overflow-y:auto;padding:12px 16px;display:flex;flex-direction:column;gap:2px}
.day-label{text-align:center;margin:10px 0}
.day-label span{background:rgba(255,255,255,.85);padding:4px 12px;border-radius:10px;font-size:12px;color:#54656f}
.bubble-wrap{display:flex;margin:1px 0}
.bubble-wrap.out{justify-content:flex-end}
.bubble-wrap.in{justify-content:flex-start}
.bubble{max-width:65%;padding:7px 12px 5px;border-radius:8px;position:relative;box-shadow:0 1px 2px rgba(0,0,0,.12)}
.bubble.in{background:#fff;border-top-left-radius:2px}
.bubble.out{background:#dcf8c6;border-top-right-radius:2px}
.bubble-text{font-size:15px;color:#111b21;line-height:1.4;word-break:break-word}
.bubble-time{font-size:11px;color:#667781;text-align:right;margin-top:2px;display:flex;align-items:center;justify-content:flex-end;gap:3px}
.tick{color:#53bdeb;font-size:13px}
#compose{background:#f0f2f5;padding:10px 12px;display:flex;align-items:flex-end;gap:10px}
#compose textarea{flex:1;resize:none;border:none;border-radius:22px;padding:10px 16px;font-size:15px;max-height:120px;outline:none;background:#fff;font-family:inherit}
#send-btn{background:#128C7E;color:#fff;border:none;border-radius:50%;width:46px;height:46px;font-size:20px;cursor:pointer;display:flex;align-items:center;justify-content:center;flex-shrink:0}
#send-btn:hover{background:#0e7268}
/* empty state */
#empty-main{flex:1;display:flex;flex-direction:column;align-items:center;justify-content:center;color:#667781;gap:12px}
#empty-main svg{opacity:.3}
/* login */
#login-screen{position:fixed;inset:0;background:#128C7E;display:flex;align-items:center;justify-content:center;z-index:999}
#login-box{background:#fff;border-radius:12px;padding:40px 36px;text-align:center;max-width:360px;width:90%}
#login-box h2{font-size:22px;margin-bottom:8px;color:#111}
#login-box p{color:#667781;margin-bottom:28px;font-size:14px}
#google-btn{display:flex;align-items:center;gap:10px;background:#fff;border:1px solid #ddd;border-radius:8px;padding:12px 20px;cursor:pointer;font-size:15px;font-weight:500;width:100%;justify-content:center}
#google-btn:hover{background:#f5f5f5}
/* toast */
#toast{position:fixed;bottom:24px;left:50%;transform:translateX(-50%);background:#333;color:#fff;padding:10px 20px;border-radius:8px;font-size:14px;opacity:0;transition:opacity .3s;pointer-events:none;z-index:1000}
#toast.show{opacity:1}
/* responsive */
@media(max-width:600px){
  #sidebar{width:100%;display:none}
  #sidebar.mobile-show{display:flex}
  #main{display:none}
  #main.mobile-show{display:flex}
}
#loading{text-align:center;padding:40px;color:#667781}
</style>
</head>
<body>

<!-- Login screen -->
<div id="login-screen">
  <div id="login-box">
    <div style="font-size:48px;margin-bottom:12px">💬</div>
    <h2>Heimish Messages</h2>
    <p>Sign in to access your messages</p>
    <button id="google-btn" onclick="signInGoogle()">
      <svg width="20" height="20" viewBox="0 0 48 48"><path fill="#EA4335" d="M24 9.5c3.54 0 6.71 1.22 9.21 3.6l6.85-6.85C35.9 2.38 30.47 0 24 0 14.62 0 6.51 5.38 2.56 13.22l7.98 6.19C12.43 13.72 17.74 9.5 24 9.5z"/><path fill="#4285F4" d="M46.98 24.55c0-1.57-.15-3.09-.38-4.55H24v9.02h12.94c-.58 2.96-2.26 5.48-4.78 7.18l7.73 6c4.51-4.18 7.09-10.36 7.09-17.65z"/><path fill="#FBBC05" d="M10.53 28.59c-.48-1.45-.76-2.99-.76-4.59s.27-3.14.76-4.59l-7.98-6.19C.92 16.46 0 20.12 0 24c0 3.88.92 7.54 2.56 10.78l7.97-6.19z"/><path fill="#34A853" d="M24 48c6.48 0 11.93-2.13 15.89-5.81l-7.73-6c-2.18 1.48-4.97 2.31-8.16 2.31-6.26 0-11.57-4.22-13.47-9.91l-7.98 6.19C6.51 42.62 14.62 48 24 48z"/></svg>
      Sign in with Google
    </button>
  </div>
</div>

<div id="toast"></div>

<!-- Sidebar -->
<div id="sidebar">
  <div id="sidebar-header">
    <span style="font-size:22px">💬</span>
    <h1>Heimish Messages</h1>
    <button onclick="signOut()" style="background:none;border:none;color:#fff;cursor:pointer;font-size:13px;opacity:.8">Sign out</button>
  </div>
  <div id="search-box">
    <input type="text" id="search-input" placeholder="🔍  Search messages…" oninput="filterConvs(this.value)">
  </div>
  <div id="conv-list"><div id="loading">Loading…</div></div>
</div>

<!-- Main chat area -->
<div id="main">
  <div id="empty-main" id="empty-main">
    <svg width="80" height="80" viewBox="0 0 24 24" fill="#667781"><path d="M20 2H4c-1.1 0-2 .9-2 2v18l4-4h14c1.1 0 2-.9 2-2V4c0-1.1-.9-2-2-2z"/></svg>
    <p>Select a conversation</p>
  </div>
</div>

<script>
const GOOGLE_CLIENT_ID = '__GOOGLE_CLIENT_ID__';
let token = localStorage.getItem('hm_token');
let convs = [];
let activeThread = null;
let refreshTimer = null;

// ── Auth ──────────────────────────────────────────────────────────────────────
function signInGoogle() {
  const params = new URLSearchParams({
    client_id: GOOGLE_CLIENT_ID,
    redirect_uri: location.origin + '/auth/callback',
    response_type: 'code',
    scope: 'email profile',
    access_type: 'offline',
    prompt: 'select_account'
  });
  location.href = 'https://accounts.google.com/o/oauth2/v2/auth?' + params;
}

function signOut() {
  localStorage.removeItem('hm_token');
  location.reload();
}

async function checkAuth() {
  // token from URL after OAuth callback
  const u = new URL(location.href);
  const t = u.searchParams.get('token');
  if (t) {
    token = t;
    localStorage.setItem('hm_token', t);
    history.replaceState({}, '', '/');
  }
  if (!token) return;
  // verify
  const r = await api('/api/me');
  if (!r.ok) { token = null; localStorage.removeItem('hm_token'); return; }
  document.getElementById('login-screen').style.display = 'none';
  loadConvs();
}

// ── API ───────────────────────────────────────────────────────────────────────
async function api(path, opts = {}) {
  const res = await fetch(path, {
    ...opts,
    headers: { 'Authorization': 'Bearer ' + token, 'Content-Type': 'application/json', ...(opts.headers || {}) }
  });
  return res;
}

// ── Conversations ─────────────────────────────────────────────────────────────
async function loadConvs() {
  const r = await api('/api/conversations');
  if (!r.ok) return;
  convs = await r.json();
  renderConvs(convs);
}

function filterConvs(q) {
  const f = q.trim().toLowerCase();
  renderConvs(f ? convs.filter(c => c.displayName.toLowerCase().includes(f) || c.snippet.toLowerCase().includes(f)) : convs);
}

const COLORS = ['#1F6F5C','#2E5E8C','#8C4A2E','#6A2E8C','#8C2E5E','#2E8C7A','#8C7A2E','#1A237E'];
function avatarColor(s) { let h=0; for(let c of s) h=(h*31+c.charCodeAt(0))>>>0; return COLORS[h%COLORS.length]; }
function initials(s) { return (s||'?')[0].toUpperCase(); }

function renderConvs(list) {
  const el = document.getElementById('conv-list');
  if (!list.length) { el.innerHTML = '<div id="loading">No conversations</div>'; return; }
  el.innerHTML = list.map(c => \`
    <div class="conv-item\${activeThread===c.threadId?' active':''}" onclick="openThread(\${c.threadId}, \${JSON.stringify(c).replace(/"/g,'&quot;')})">
      <div class="avatar" style="background:\${avatarColor(c.address)}">\${initials(c.displayName)}</div>
      <div class="conv-info">
        <div class="conv-name">\${esc(c.displayName)}</div>
        <div class="conv-snippet">\${esc(c.snippet)}</div>
      </div>
      <div class="conv-meta">
        <div>\${shortTime(c.date)}</div>
        \${c.unread?'<div class="unread-dot">•</div>':''}
      </div>
    </div>
  \`).join('');
}

// ── Thread ────────────────────────────────────────────────────────────────────
async function openThread(threadId, conv) {
  activeThread = threadId;
  renderConvs(convs); // re-highlight

  const main = document.getElementById('main');
  main.innerHTML = \`
    <div id="chat-header">
      <div class="avatar" style="background:\${avatarColor(conv.address)}">\${initials(conv.displayName)}</div>
      <div id="chat-header-info">
        <h2>\${esc(conv.displayName)}</h2>
        <span>\${esc(conv.address)}</span>
      </div>
      <div id="chat-actions">
        <button onclick="deleteConv(\${threadId})" title="Delete conversation">🗑️</button>
      </div>
    </div>
    <div id="messages"><div id="loading">Loading…</div></div>
    <div id="compose">
      <textarea id="msg-input" placeholder="Message…" rows="1" onkeydown="handleKey(event,'\${esc(conv.address)}')" oninput="autoResize(this)"></textarea>
      <button id="send-btn" onclick="sendMsg('\${esc(conv.address)}')">➤</button>
    </div>
  \`;

  await loadMessages(threadId);
  clearInterval(refreshTimer);
  refreshTimer = setInterval(() => loadMessages(threadId), 4000);
}

async function loadMessages(threadId) {
  const r = await api(\`/api/messages/\${threadId}\`);
  if (!r.ok) return;
  const msgs = await r.json();
  renderMessages(msgs);
}

function renderMessages(msgs) {
  const el = document.getElementById('messages');
  if (!el) return;
  if (!msgs.length) { el.innerHTML = '<div style="text-align:center;color:#667781;padding:40px">No messages</div>'; return; }

  // group by day
  let lastDay = '', html = '';
  for (const m of msgs) {
    const day = dayLabel(m.date);
    if (day !== lastDay) {
      html += \`<div class="day-label"><span>\${day}</span></div>\`;
      lastDay = day;
    }
    const cls = m.incoming ? 'in' : 'out';
    html += \`
      <div class="bubble-wrap \${cls}">
        <div class="bubble \${cls}">
          <div class="bubble-text">\${esc(m.body)}</div>
          <div class="bubble-time">
            \${msgTime(m.date)}
            \${!m.incoming ? '<span class="tick">✓✓</span>' : ''}
            \${!m.incoming ? \`<button onclick="deleteMsg(\${m.id})" style="background:none;border:none;cursor:pointer;font-size:11px;color:#999;padding:0 0 0 4px">✕</button>\` : ''}
          </div>
        </div>
      </div>
    \`;
  }
  el.innerHTML = html;
  el.scrollTop = el.scrollHeight;
}

async function sendMsg(address) {
  const inp = document.getElementById('msg-input');
  const body = inp.value.trim();
  if (!body) return;
  inp.value = '';
  autoResize(inp);
  const r = await api('/api/send', { method: 'POST', body: JSON.stringify({ address, body }) });
  if (!r.ok) { toast('Failed to send'); return; }
  if (activeThread) await loadMessages(activeThread);
}

function handleKey(e, address) {
  if (e.key === 'Enter' && !e.shiftKey) { e.preventDefault(); sendMsg(address); }
}

async function deleteMsg(id) {
  if (!confirm('Delete this message?')) return;
  await api(\`/api/messages/\${id}\`, { method: 'DELETE' });
  if (activeThread) await loadMessages(activeThread);
}

async function deleteConv(threadId) {
  if (!confirm('Delete entire conversation?')) return;
  await api(\`/api/conversations/\${threadId}\`, { method: 'DELETE' });
  activeThread = null;
  document.getElementById('main').innerHTML = '<div id="empty-main" style="flex:1;display:flex;flex-direction:column;align-items:center;justify-content:center;color:#667781;gap:12px"><p>Select a conversation</p></div>';
  await loadConvs();
}

// ── Helpers ───────────────────────────────────────────────────────────────────
function esc(s) { return (s||'').replace(/&/g,'&amp;').replace(/</g,'&lt;').replace(/>/g,'&gt;').replace(/"/g,'&quot;'); }

function shortTime(ms) {
  if (!ms) return '';
  const d = new Date(ms), n = new Date();
  if (d.toDateString() === n.toDateString()) return d.toLocaleTimeString([],{hour:'numeric',minute:'2-digit'});
  return d.toLocaleDateString([],{month:'short',day:'numeric'});
}
function msgTime(ms) { return ms ? new Date(ms).toLocaleTimeString([],{hour:'numeric',minute:'2-digit'}) : ''; }
function dayLabel(ms) {
  if (!ms) return '';
  const d = new Date(ms), n = new Date();
  if (d.toDateString() === n.toDateString()) return 'Today';
  const y = new Date(n); y.setDate(y.getDate()-1);
  if (d.toDateString() === y.toDateString()) return 'Yesterday';
  return d.toLocaleDateString([],{month:'long',day:'numeric',year:'numeric'});
}
function autoResize(el) { el.style.height='auto'; el.style.height=Math.min(el.scrollHeight,120)+'px'; }
function toast(msg) {
  const t = document.getElementById('toast');
  t.textContent = msg; t.classList.add('show');
  setTimeout(() => t.classList.remove('show'), 3000);
}

checkAuth();
// refresh conv list every 10s
setInterval(loadConvs, 10000);
</script>
</body>
</html>`;

export default {
  async fetch(request, env) {
    const url = new URL(request.url);
    const path = url.pathname;

    // ── OAuth callback ───────────────────────────────────────────────────────
    if (path === '/auth/callback') {
      const code = url.searchParams.get('code');
      if (!code) return Response.redirect(url.origin, 302);

      // Exchange code for tokens
      const tokenRes = await fetch('https://oauth2.googleapis.com/token', {
        method: 'POST',
        headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
        body: new URLSearchParams({
          code,
          client_id: env.GOOGLE_CLIENT_ID,
          client_secret: env.GOOGLE_CLIENT_SECRET,
          redirect_uri: url.origin + '/auth/callback',
          grant_type: 'authorization_code'
        })
      });
      const tokens = await tokenRes.json();
      if (!tokens.access_token) return new Response('Auth failed', { status: 401 });

      // Get user info
      const userRes = await fetch('https://www.googleapis.com/oauth2/v2/userinfo', {
        headers: { Authorization: 'Bearer ' + tokens.access_token }
      });
      const user = await userRes.json();

      // Check allowed emails
      const allowed = (env.ADMIN_EMAILS || '').split(',').map(e => e.trim().toLowerCase());
      if (allowed.length && !allowed.includes(user.email.toLowerCase())) {
        return new Response('Access denied', { status: 403 });
      }

      // Create session token
      const sessionToken = crypto.randomUUID();
      await env.SMS_KV.put(`session:${sessionToken}`, JSON.stringify({ email: user.email, name: user.name }), { expirationTtl: 86400 * 30 });

      return Response.redirect(`${url.origin}/?token=${sessionToken}`, 302);
    }

    // ── API routes ───────────────────────────────────────────────────────────
    if (path.startsWith('/api/')) {

      // Android sync endpoint — uses API key not session token
      if (path === '/api/sync' && request.method === 'POST') {
        const apiKey = request.headers.get('X-API-Key');
        if (apiKey !== env.SYNC_API_KEY) return json({ error: 'Unauthorized' }, 401);
        const data = await request.json();
        await handleSync(env, data);
        return json({ ok: true });
      }

      // All other API routes require session auth
      const authHeader = request.headers.get('Authorization') || '';
      const sessionToken = authHeader.replace('Bearer ', '');
      const sessionData = sessionToken ? await env.SMS_KV.get(`session:${sessionToken}`) : null;
      if (!sessionData) return json({ error: 'Unauthorized' }, 401);

      // GET /api/me
      if (path === '/api/me') return json(JSON.parse(sessionData));

      // GET /api/conversations
      if (path === '/api/conversations') {
        const convs = await getConversations(env);
        return json(convs);
      }

      // GET /api/messages/:threadId
      const msgMatch = path.match(/^\/api\/messages\/(\d+)$/);
      if (msgMatch) {
        if (request.method === 'GET') {
          const msgs = await getMessages(env, msgMatch[1]);
          return json(msgs);
        }
        if (request.method === 'DELETE') {
          await env.SMS_KV.delete(`msg:${msgMatch[1]}`);
          return json({ ok: true });
        }
      }

      // DELETE /api/conversations/:threadId
      const convMatch = path.match(/^\/api\/conversations\/(\d+)$/);
      if (convMatch && request.method === 'DELETE') {
        await deleteConversation(env, convMatch[1]);
        return json({ ok: true });
      }

      // POST /api/send
      if (path === '/api/send' && request.method === 'POST') {
        const { address, body } = await request.json();
        // Store as outgoing message — Android app will actually send it via pending queue
        const msgId = Date.now();
        const msg = { id: msgId, body, date: Date.now(), incoming: false, pending: true };
        await storePendingOutgoing(env, address, msg);
        return json({ ok: true, id: msgId });
      }

      // GET /api/pending — Android polls for web-panel-queued outgoing msgs
      if (path === '/api/pending') {
        const apiKey = request.headers.get('X-API-Key');
        if (apiKey !== env.SYNC_API_KEY) return json({ error: 'Unauthorized' }, 401);
        const pending = JSON.parse(await env.SMS_KV.get('pending_outgoing') || '[]');
        return json({ pending });
      }

      // POST /api/pending/ack — Android confirms it sent the msg
      if (path === '/api/pending/ack' && request.method === 'POST') {
        const apiKey = request.headers.get('X-API-Key');
        if (apiKey !== env.SYNC_API_KEY) return json({ error: 'Unauthorized' }, 401);
        const { id } = await request.json();
        const pending = JSON.parse(await env.SMS_KV.get('pending_outgoing') || '[]');
        const filtered = pending.filter(p => p.id !== id);
        await env.SMS_KV.put('pending_outgoing', JSON.stringify(filtered));
        return json({ ok: true });
      }

      return json({ error: 'Not found' }, 404);
    }

    // ── Serve web UI ─────────────────────────────────────────────────────────
    const clientId = env.GOOGLE_CLIENT_ID || '';
    const html = HTML.replace('__GOOGLE_CLIENT_ID__', clientId);
    return new Response(html, { headers: { 'Content-Type': 'text/html;charset=UTF-8' } });
  }
};

// ── KV helpers ────────────────────────────────────────────────────────────────

async function handleSync(env, data) {
  // data = { conversations: [...], messages: { threadId: [...] } }
  if (data.conversations) {
    await env.SMS_KV.put('convs', JSON.stringify(data.conversations));
  }
  if (data.messages) {
    for (const [threadId, msgs] of Object.entries(data.messages)) {
      const existing = JSON.parse(await env.SMS_KV.get(`msgs:${threadId}`) || '[]');
      const existingIds = new Set(existing.map(m => m.id));
      const merged = [...existing, ...msgs.filter(m => !existingIds.has(m.id))];
      merged.sort((a, b) => a.date - b.date);
      await env.SMS_KV.put(`msgs:${threadId}`, JSON.stringify(merged));
    }
  }
}

async function getConversations(env) {
  const raw = await env.SMS_KV.get('convs');
  if (!raw) return [];
  return JSON.parse(raw);
}

async function getMessages(env, threadId) {
  const raw = await env.SMS_KV.get(`msgs:${threadId}`);
  if (!raw) return [];
  return JSON.parse(raw);
}

async function deleteConversation(env, threadId) {
  const convs = await getConversations(env);
  const filtered = convs.filter(c => String(c.threadId) !== String(threadId));
  await env.SMS_KV.put('convs', JSON.stringify(filtered));
  await env.SMS_KV.delete(`msgs:${threadId}`);
}

async function storePendingOutgoing(env, address, msg) {
  const pending = JSON.parse(await env.SMS_KV.get('pending_outgoing') || '[]');
  pending.push({ address, ...msg });
  await env.SMS_KV.put('pending_outgoing', JSON.stringify(pending));

  // Also add to the conversation messages optimistically
  const convs = await getConversations(env);
  const conv = convs.find(c => c.address === address);
  if (conv) {
    const msgs = JSON.parse(await env.SMS_KV.get(`msgs:${conv.threadId}`) || '[]');
    msgs.push(msg);
    await env.SMS_KV.put(`msgs:${conv.threadId}`, JSON.stringify(msgs));
    conv.snippet = msg.body;
    conv.date = msg.date;
    await env.SMS_KV.put('convs', JSON.stringify(convs));
  }
}

function json(data, status = 200) {
  return new Response(JSON.stringify(data), {
    status,
    headers: { 'Content-Type': 'application/json', 'Access-Control-Allow-Origin': '*' }
  });
}
