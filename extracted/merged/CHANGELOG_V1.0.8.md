# AutoCallManager V1.0.8

## Combined reliability + AI Voice safety release

- V1.0.7 Android scheduled-call engine retained.
- Added persisted `timezoneId` to schedules; recurring calculation now uses the schedule creation timezone rather than the device's current timezone.
- Added explicit `executionMode`: `NORMAL_SIM` or `AI_VOICE`. Existing schedules migrate to `NORMAL_SIM`.
- Added server-side recipient consent / revoke / do-not-call safety gate for AI Voice.
- Added audit trail for consent and opt-out events.
- Added RBAC-protected Admin Panel AI Voice Safety controls.
- Added server-originated AI Voice call path scaffold using a telephony provider service and Gemini-backed conversational responses.
- AI Voice provider secrets remain server-side only.
- Added idempotent voice-call execution records and explicit provider/AI states.
- Existing normal SIM call path does not inject Android TTS into the cellular uplink.
- Android can request an AI Voice call through the configured Admin backend; the provider owns the telephone audio path.
- Added explicit workflow validation for Android root project, Admin server, and consent tests.

## Important

Real AI Voice outbound calling requires a publicly reachable HTTPS backend, provider credentials, an AI provider key, and successful real-phone testing. This package does not claim that a real provider account is configured.
