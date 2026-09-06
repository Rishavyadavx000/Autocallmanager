# PROMPT_CONTRACT_V1.0.5 — Admin Panel (implemented)

`PROMPT_CONTRACT_V1.0.4.md` specified what a future admin backend should look
like. This document records what `admin-server/` actually implements against
that spec, and where it deliberately differs. See `admin-server/README.md`
for setup/deployment and the full endpoint reference.

## What matches the original contract exactly
- Canonical prompt schema (`id`, `version`, `type`, `name`, `enabled`,
  `outputFormat`, `maxWords`, `variables`, `template`) — unchanged.
- `GET /v1/prompts` is public and returns only enabled prompts, in the exact
  shape `PromptStore.parse()` on the Android side already expects.
- Prompt IDs are permanent. Admin edits create new template *versions*; they
  never mutate a prompt's `id`.
- Admin authorization is enforced server-side (Bearer session tokens checked
  on every mutating request) — not a client-side "hidden button."
- `message.generate.v1` remains the default prompt ID old saved schedules
  fall back to if their stored `promptId` is missing or disabled.

## Where this goes further than the contract asked for
- **Rollback**: the contract implied versioning; this implementation adds an
  explicit `POST /v1/prompts/:id/rollback` plus a full version-history view,
  since "an admin can revert a bad edit in one click" seemed like the actual
  point of versioning, not just an audit trail.
- **Test-before-publish**: `POST /v1/prompts/:id/test` renders a template
  against sample variables with zero external calls by default, and can
  optionally call Gemini live if the server operator sets `GEMINI_API_KEY`
  — a separate, server-side key, never the end user's on-device key.
- **Audit log**: every create/update/version/rollback/login is recorded with
  who and when, visible in the admin panel.

## Where this deliberately differs from a literal reading
- **No delete endpoint.** A schedule on a phone stores the `promptId` and
  `promptVersion` it was created with. Deleting a prompt ID out from under
  already-saved schedules would break them silently. `enabled: false` is the
  supported way to retire a prompt — it disappears from `GET /v1/prompts`
  immediately, but its ID and history stay intact for any device that
  already cached it.
- **`PUT` vs. version creation are separate on purpose.** `PUT /v1/prompts/:id`
  only ever touches metadata (name, enabled, maxWords, variables,
  outputFormat). Changing the template text is only possible through
  `POST /v1/prompts/:id/versions`, which is the one action that bumps
  `current_version`. This makes "what actually changed the words a user
  hears" a single, auditable, reversible action rather than something that
  can happen incidentally as part of an unrelated metadata edit.
- **`X-App-Key` is opt-in and explicitly not real security.** The contract
  didn't specify an app-level key at all. This implementation adds one as an
  optional, off-by-default abuse-limiter, documented plainly as extractable
  from the APK — not a substitute for the admin auth model.

## What the Android app actually does differently now
Nothing changes for a device with no Admin Catalog URL configured — it
behaves exactly like V1.0.4. When a URL is configured and at least one sync
has succeeded, `PromptStore.list()` reads from a locally cached copy of the
last successful `GET /v1/prompts` response instead of the bundled asset.
Rendering, variable substitution, and the AI request path are unchanged
either way — the app always renders `{{variables}}` on-device; the server
never renders on the app's behalf (the admin panel's "test" button is the
one exception, and it's admin-only tooling, not part of the end-user path).
