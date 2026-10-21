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

Two keys, both Cloudflare secrets pushed by CI from the repository secrets of the same name:

- `ADMIN_TOKEN` is the **sync key**. Every APK carries it, so treat it as public. It may only
  upload: `POST /contacts/sync`, `PUT`/`DELETE /photo/:deviceId/:key`. Anything else answers 403 `sync_only`.
- `ADMIN_PASSWORD` is the owner's password. `POST /admin/login {"password"}` returns
  `{token, exp}`: a 30-day session signed with HMAC-SHA256 under a key derived from the password and a random
  salt in R2 (`auth/salt`). All reads need `Authorization: Bearer <session>`. Changing the password, or
  `POST /admin/logout-all` (which rotates the salt), ends every session. 5 wrong passwords from one IP lock it out
  for 15 min, doubling with each further 5 (max 24 h), and every wrong try waits 0.75 s. Without the secret, login
  answers 503 `{"error":"admin password not set"}`. `.github/workflows/admin-password-sync.yml` pushes the repo
  secret to the worker within ~10 min of it being added or changed (run it by hand for an immediate push).

`/ping` is public and reports `configured` (sync key set) and `admin` (password set), never their values.

| Method | Path | Who |
| ------ | ---- | --- |
| POST | `/admin/login` | anyone (password) |
| GET | `/admin/session` | session |
| POST | `/admin/logout-all` | session |
| POST | `/contacts/sync`, PUT/DELETE `/photo/:deviceId/:key` | sync key or session |
| GET, DELETE | `/contacts`, `/devices`, `/contacts/device/:id`, `/photo/:key` | session only |

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
