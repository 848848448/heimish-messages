# Heimish Messages — Contacts Worker

A Cloudflare Worker that stores per-device contact lists in R2 and exposes a small,
token-protected search API. The web app's Admin Panel calls this worker to search
contacts across all synced devices.

## Setup

1. Install wrangler: `npm install -g wrangler`
2. Login: `wrangler login`
3. Create the R2 bucket (name must match `wrangler.toml`):
   ```
   wrangler r2 bucket create heimish-contacts
   ```
4. Set the admin token (used as `Authorization: Bearer <token>`):
   - For production, override the default in `wrangler.toml` via a secret:
     ```
     wrangler secret put ADMIN_TOKEN
     ```
   - The app sends this token from its Admin Panel. Keep the two in sync.
5. Deploy:
   ```
   wrangler deploy
   ```

CI deploys this folder automatically on push to `worker/**`
(see `.github/workflows/deploy-worker.yml`), using the
`CLOUDFLARE_API_TOKEN` and `CLOUDFLARE_ACCOUNT_ID` repository secrets.

## API

All endpoints require the header `Authorization: Bearer <ADMIN_TOKEN>`.

| Method | Path                        | Purpose                                         |
| ------ | --------------------------- | ----------------------------------------------- |
| GET    | `/contacts?q=&device=`      | Search contacts (deduped by number). `q` filters by name/number; optional `device` limits to one device. Returns up to 500. |
| POST   | `/contacts/sync`            | Upload a device's contacts: `{ device, contacts:[...] }`. |
| GET    | `/devices`                  | List synced devices with contact counts.        |
| DELETE | `/contacts/device/:id`      | Remove one device's contacts.                   |
| GET    | `/ping`                     | Health check.                                   |

CORS is open (`*`) so the web app can call it directly.

## How it works

- Each device uploads its contacts to R2 at `devices/<deviceId>.json`.
- Search reads across all device files, deduplicates by phone number, and returns matches.
- The web Admin Panel (in `docs/index.html`) reads from this worker to search contacts.
