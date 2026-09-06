# AutoCallManager Admin Server (V1.0.9)

An authenticated backend that lets you manage AutoCallManager's AI prompt
catalog remotely, per `PROMPT_CONTRACT_V1.0.4.md` at the repo root. The
Android app can keep using its bundled local catalog, or point at this
server to update prompts without shipping a new APK.

**Zero runtime dependencies.** Everything runs on Node's built-ins:
`http`/`https`, `node:sqlite`, and `crypto`. No `npm install` required.

## Requirements

- Node.js **>= 22.5** (for `node:sqlite`). Tested on 22.x.
- `node:sqlite` is marked **experimental** by Node — stable in practice, but
  the Node team reserves the right to change the API in a future major
  version. If you want a long-term-stable driver instead, swap `src/db.js`
  for `better-sqlite3`; the `.prepare(sql).run()/.get()/.all()` calls used
  throughout this project are close to a drop-in match.

## Quick start

```bash
cd admin-server
cp .env.example .env
# edit .env — at minimum set ADMIN_BOOTSTRAP_PASSWORD before first run,
# or let the server generate one and print it once to the console.
node --env-file=.env src/server.js
# or without --env-file: just export the variables you need and run
#   node src/server.js
```

On first boot the server:
1. Seeds the prompt catalog from `seed/prompts.seed.json` (a copy of the
   catalog currently bundled in the app, so a fresh backend starts in parity
   with what's already shipping).
2. Creates one admin account. If `ADMIN_BOOTSTRAP_PASSWORD` isn't set, a
   random password is generated and printed **once** to the console —
   copy it before you lose that terminal output.

Then open `http://localhost:8787/admin` and log in.

## Endpoints

Public, unauthenticated (consumed by the Android app):

| Method | Path | Notes |
|---|---|---|
| GET | `/v1/health` | Liveness check |
| GET | `/v1/prompts` | Enabled prompts only, in the exact shape the client already parses |
| GET | `/v1/prompts/:id` | Single enabled prompt |

Admin, requires `Authorization: Bearer <token>`:

| Method | Path | Notes |
|---|---|---|
| POST | `/v1/admin/login` | `{ username, password }` → session token |
| POST | `/v1/admin/logout` | Invalidates the current token |
| POST | `/v1/admin/change-password` | `{ currentPassword, newPassword }` |
| GET | `/v1/admin/audit` | Last 200 audit events |
| GET | `/v1/admin/prompts` | All prompts (enabled or not), with templates |
| GET | `/v1/admin/prompts/:id` | Single prompt, admin view |
| GET | `/v1/admin/prompts/:id/versions` | Full version history |
| POST | `/v1/prompts` | Create a prompt (seeds version 1) |
| PUT | `/v1/prompts/:id` | Update metadata (name, enabled, maxWords, variables, outputFormat) — **not** the template |
| POST | `/v1/prompts/:id/versions` | `{ template }` → new version, becomes current |
| POST | `/v1/prompts/:id/rollback` | `{ version }` → re-point current_version at an existing version |
| POST | `/v1/prompts/:id/test` | `{ variables }` → renders the template; calls Gemini live only if `GEMINI_API_KEY` is set on the server |

There is intentionally **no delete endpoint.** A schedule saved on a phone
stores the `promptId`/`promptVersion` it was created with; deleting a prompt
ID out from under already-saved schedules would break them. Disable a
prompt instead (`enabled: false`) — it disappears from `GET /v1/prompts`
immediately but its history and ID stay intact.

## Security model

- **Passwords**: hashed with `scrypt` (Node's built-in, no external crypto
  library), unique salt per user, compared with `crypto.timingSafeEqual`.
- **Sessions**: opaque random tokens (`crypto.randomBytes(32)`), stored
  server-side with an expiry (`SESSION_TTL_HOURS`, default 12h). Revocation
  is a single row delete — no JWT blacklist to manage.
- **Login rate limiting** is in-memory per process: 5 failed attempts per
  username+IP locks that pair out for 15 minutes. This resets on server
  restart and doesn't share state across multiple instances — fine for a
  small team on one process; move it to a shared store if you scale out.
- **`PUBLIC_APP_KEY`** is optional and OFF by default. If you set it, the
  Android app must send it as `X-App-Key` to read `/v1/prompts`. Treat this
  as a basic abuse-limiter only, **not real security** — any value baked
  into the APK can be extracted by decompiling it. Real authorization for
  the admin routes is the Bearer session token, enforced server-side; there
  is no "hidden button" anywhere in this design that only looks protected.
- **Transport**: this process speaks plain HTTP unless you set `TLS_CERT_PATH`
  and `TLS_KEY_PATH`. In production, either provide real certs or — more
  commonly — put this behind a reverse proxy/platform load balancer that
  terminates TLS (nginx+certbot, Render, Railway, Fly.io, etc.) and only
  proxies to this process over localhost. Bearer tokens go in a header on
  every request; don't run this over plain HTTP across the open internet.

## Data

SQLite file lives at `DATA_DIR/admin.sqlite` (`./data` by default). Back
this file up like you would any small production database — it holds your
entire prompt catalog, version history, and admin accounts.

## Testing

Two scripts exercise the whole API end-to-end against a fresh throwaway
database (they delete `./data` first, so don't point `DATA_DIR` at anything
you care about while testing):

```bash
bash test.sh            # auth, rate limiting, CRUD, versioning, rollback, audit
bash test_fullstack.sh  # static admin UI serving + a full simulated session
```

## Deploying independently of the Android repo

This folder is self-contained — it doesn't read anything from `../app`.
You can copy just `admin-server/` to its own repo or container image and
deploy it on its own.


## V1.0.9 AI Voice
The server contains an optional provider-backed AI Voice path. It is disabled until secure provider configuration is supplied. AI Voice execution is blocked unless the recipient is recorded as `CONSENTED` and is not marked `DO_NOT_CALL`.
