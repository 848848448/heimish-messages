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
4. Set the admin token (used as `Authorization: Bearer <token>`) as a secret —
   it is never committed to this repo. CI does this on every deploy from the
   `ADMIN_TOKEN` repository secret, so normally nothing to do by hand:
   ```
   wrangler secret put ADMIN_TOKEN
   ```
   The APK receives the token at build time via the `ADMIN_TOKEN` GitHub Actions
   secret (see `.github/workflows/build.yml`); local builds use `local.properties`.
   Keep all three in sync. Rotating the token requires a new APK release.
5. Deploy:
   ```
   wrangler deploy
   ```

CI deploys this folder automatically on push to `worker/**`
(see `.github/workflows/deploy-worker.yml`), using the
`CLOUDFLARE_API_TOKEN` and `CLOUDFLARE_ACCOUNT_ID` repository secrets.

## API

All endpoints except `/ping` require the header `Authorization: Bearer <ADMIN_TOKEN>`.
`/ping` is public and reports `configured: true/false` (whether the secret is set).
A missing secret answers 503 with a clear message.

| Method | Path                        | Purpose                                         |
| ------ | --------------------------- | ----------------------------------------------- |
| GET    | `/contacts?q=&device=`      | Search contacts (deduped by number). `q` filters by name/number; optional `device` limits to one device. Returns up to 500. |
| POST   | `/contacts/sync`            | Upload a device's contacts: `{ device, contacts:[...] }`. |
| GET    | `/devices`                  | List synced devices with contact counts.        |
| DELETE | `/contacts/device/:id`      | Remove one device's contacts.                   |
| PUT    | `/photo/:deviceId/:key`     | Upload a contact photo (`Content-Type: image/jpeg`, max 100 KB). `key` = last 10 digits of the number. |
| DELETE | `/photo/:deviceId/:key`     | Remove that device's photo for the number.      |
| GET    | `/photo/:key`               | The newest photo for the number from any device (`ETag`, `Cache-Control: private, max-age=86400`, 304 on `If-None-Match`). |
| GET    | `/ping`                     | Health check (no auth).                         |

CORS is open (`*`) so the web app can call it directly.

## How it works

- Each device uploads its contacts to R2 at `devices/<deviceId>.json`.
- Search reads across all device files, deduplicates by phone number (last 10 digits, so
  `+1 718…` and `718-…` are one contact), and returns matches. A name query matches names;
  number matching only happens when the query contains digits.
- Contact photos live at `photos/<key>/<deviceId>.jpg`. A synced contact with `"photo": true`
  makes search results carry `hasPhoto: true` and `photoKey` (the number's key).
- The app's worker address comes from the `HEIMISH_WORKER_URL` repository variable
  (build.yml); it must be this worker's workers.dev URL.
- The web Admin Panel (in `docs/index.html`) reads from this worker to search contacts.
