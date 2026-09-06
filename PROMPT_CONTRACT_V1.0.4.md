# AutoCallManager V1.0.6 Prompt Contract

## Purpose

V1.0.6 uses stable, versioned prompt IDs so a future V1.0.6 admin tooling can manage prompts without breaking the mobile UI, schedule data, or AI request code.

## Canonical prompt schema

Each prompt definition uses these fields:

- `id`: stable identifier, for example `message.generate.v1`
- `version`: integer prompt version
- `type`: semantic operation such as `MESSAGE_GENERATE`
- `name`: human-readable label
- `enabled`: whether the prompt may be selected
- `outputFormat`: currently `plain_text`
- `maxWords`: output budget used by the client
- `variables`: allowed template variables
- `template`: prompt body

The current catalog is `app/src/main/assets/ai_prompts_v1.json`.

## Reserved prompt IDs

| ID | Purpose | V1.0.6 use |
|---|---|---|
| `message.generate.v1` | Create a reminder from an instruction | Default and wizard |
| `message.rewrite.v1` | Rewrite existing text | AI Studio option |
| `message.short.v1` | Make a concise reminder | AI Studio option |
| `message.formal.v1` | Professional reminder | AI Studio option |
| `voice.optimize.v1` | Make text more natural for TTS | AI Studio option |

## Template rules

Templates use double-brace placeholders. V1.0.6 supports:

- `{{instruction}}`
- `{{maxWords}}`

Future admin-managed prompts may add fields only after updating the prompt schema and client renderer in a backwards-compatible release.

## Request contract

WebView calls:

`NativeBridge.generateAIWithPrompt(promptId, instruction)`

The native layer resolves the prompt ID, validates it, renders the template, and sends the final prompt to the configured AI provider.

The older call remains supported:

`NativeBridge.generateAI(instruction)`

It always uses `message.generate.v1`.

## Schedule contract

Every saved schedule stores:

- `promptId`
- `promptVersion`

If these fields are missing in older saved data, the app falls back to `message.generate.v1` version `1`.

## Admin V1.0.6 compatibility

V1.0.6 can replace the local prompt catalog with an authenticated backend catalog as long as it preserves these fields and IDs. A schedule keeps the prompt ID/version that was selected when it was created, preventing an admin prompt edit from silently changing already-saved schedules.

Recommended future backend endpoints:

- `GET /v1/prompts`
- `GET /v1/prompts/{id}`
- `POST /v1/prompts`
- `PUT /v1/prompts/{id}`
- `POST /v1/prompts/{id}/versions`
- `POST /v1/prompts/{id}/test`
- `POST /v1/prompts/{id}/rollback`

Admin authorization must be enforced server-side. Hiding an Admin button in the WebView is not an access control mechanism.

## Safety and output behavior

The client requests plain text only. The AI response is treated as untrusted text and is escaped before being inserted into HTML UI. Prompts should not request private contact databases, passwords, API keys, or unrelated sensitive information.
