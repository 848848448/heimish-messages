# Heimish Messages — Cloudflare Worker

## Setup

1. Install wrangler: `npm install -g wrangler`
2. Login: `wrangler login`
3. Create KV namespace:
   ```
   wrangler kv:namespace create SMS_KV
   ```
   Copy the ID into `wrangler.toml`

4. Set secrets:
   ```
   wrangler secret put GOOGLE_CLIENT_ID
   wrangler secret put GOOGLE_CLIENT_SECRET
   wrangler secret put SYNC_API_KEY        ← random strong key, put same in Android app
   wrangler secret put ADMIN_EMAILS        ← your@gmail.com
   ```

5. Deploy:
   ```
   wrangler deploy
   ```

6. In Android app: open Settings → paste Worker URL + API key

## How it works
- Android syncs SMS every 30s → Worker stores in KV
- Web panel reads from KV → shows all conversations
- Web panel "Send" → stores in KV pending queue
- Android polls pending queue → sends SMS → acks
