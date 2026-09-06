# AutoCallManager V1.0.9

V1.0.9 is a reliability-focused release built on the V1.0.9 core. It preserves Normal SIM Call, optional AI Voice architecture, Smart Time, timezone-aware recurrence, dual-SIM selection, contact search, notifications, history/timeline, consent safety, and the authenticated Admin Control Center. V1.0.9 adds outcome-aware retry decisions, real-time device diagnostics, stronger background-readiness visibility, and a more robust release verification workflow.

## Scheduled-call reliability
- Exact user schedules use `AlarmManager.setExactAndAllowWhileIdle()`.
- `CallReceiver` uses `goAsync()` and a short partial wake lock for the execution path.
- The receiver does not launch the main Activity for scheduled execution.
- Outgoing calls are submitted through `TelecomManager.placeCall()`.
- A selected dual-SIM `PhoneAccountHandle` is validated before use.
- `TelecomManager.isOutgoingCallPermitted()` is checked for a selected account.
- Call-state tracking continues in the background through `TelephonyManager.ACTION_PHONE_STATE_CHANGED`.
- Retry is scheduled only after an observed failed/no-offhook outcome or watchdog timeout, rather than immediately after a successful `placeCall()` request.
- A short watchdog prevents a permanently stuck call-request state, while long-running off-hook calls continue to be tracked.
- Boot/package-update/exact-alarm-permission flows re-register future schedules.

### Call-result semantics
Android phone state `OFFHOOK` means the device has entered the off-hook/dialing/active phone state; it does not by itself prove that the remote party answered. The app therefore records `CALL_OFFHOOK` / `CALL_OFFHOOK_ENDED` rather than claiming remote-party connection proof.

## Smart Time
- Date defaults to the device date.
- Hour, minute, and second are directly editable and support +/- controls.
- 12-hour AM/PM UI is used throughout.
- Current device time is shown live.
- Selected time updates immediately after edits.
- `NOW`, `+1 MIN`, `+5 MIN`, and `+10 MIN` shortcuts are available.
- Past time on today's date is blocked.
- Countdown remains `HH:MM:SS` as a duration.

## Overview
- Active schedule count.
- Today's actual terminal call outcomes.
- Success and failure counts based on call-state history, not generic notifications.
- Pending schedule count.
- Real success-rate progress bar.
- Recent activity from persisted history.
- Live refresh timestamp.
- Native readiness/permission status.

## Contact and SIM flow
- Step 1 contains only phone number + Pick Contact.
- Contact picker searches by contact name and number.
- Dual-SIM selector appears only when two usable call-capable accounts are detected.
- Single-SIM devices require no SIM selection.

## AI and prompts
- Gemini integration and the V1.0.4 prompt contract are retained.
- Prompt IDs/versions remain stable across the Android app and Admin Panel.
- Saved schedules keep prompt metadata so later prompt revisions do not silently change existing schedules.
- API keys are not bundled into the repository or backup files.

## Admin Panel
The provided Admin Panel is a separate authenticated backend, not a hidden client-side page. It provides:
- admin login/session management
- prompt catalog management
- prompt versioning and rollback
- enable/disable prompt state
- audit log
- optional server-side Gemini prompt test when `GEMINI_API_KEY` is configured
- public enabled prompt catalog for Android synchronization

The Admin Control Center remains the protected backend for privileged configuration. V1.0.9 does not introduce a new multi-provider secret vault beyond the existing backend contract.

## Admin server
- Node.js >= 22.5 (uses built-in `node:sqlite`)
- No runtime npm dependencies
- Run `bash test.sh` for API/auth tests.
- Run `bash test_fullstack.sh` for static UI + end-to-end session tests.
- Configure `ADMIN_BOOTSTRAP_USER`, `ADMIN_BOOTSTRAP_PASSWORD`, `PUBLIC_APP_KEY` (optional), and `GEMINI_API_KEY` as appropriate for deployment.
- Use HTTPS directly or put the server behind a TLS-terminating reverse proxy in production.

## Runtime note
Screen-off and locked-device execution is designed around Android exact-alarm and Telecom APIs, but OEM battery policies, permissions, active-call state, SIM availability, and device restrictions can still affect real-world behavior. V1.0.9 surfaces readiness and failure states instead of claiming an unconditional 100% guarantee.


## V1.0.9 additions
- Recurring schedule timezone persistence (`timezoneId`).
- Explicit `NORMAL_SIM` / `AI_VOICE` execution mode metadata.
- Server-side AI Voice recipient consent and do-not-call safety gate.
- Consent audit trail and protected Admin Panel safety controls.
- Optional server-originated AI Voice call integration using Twilio + Gemini when configured.

AI Voice production requirements: `PUBLIC_BASE_URL`, `PUBLIC_APP_KEY`, `TWILIO_ACCOUNT_SID`, `TWILIO_AUTH_TOKEN`, `TWILIO_FROM_NUMBER`, and `GEMINI_API_KEY`.


## V1.0.9 combined-source note

This release combines the latest execution-trace project bundle with the
latest supplied `CallManager.kt` and `CallReceiver.kt` observability fixes.

The combined source contains:
- ExecutionTraceStore
- persistent CallExecutionStore executionId correlation
- trace-aware CallExecutionManager
- trace-aware CallScheduler
- trace-aware CallReceiver
- early-return tracing in CallManager
- V1.0.9 unit tests
- Admin + AI Voice backend tests
- GitHub Actions Android + Admin workflow

The supplied observability patch is included separately as
`observability-fix-v1.0.9-applied.patch` for audit/reference only.

IMPORTANT:
A static/package merge does not prove Android runtime behavior. The final
release must still be verified by GitHub Actions and a real device test,
especially Screen OFF + LOCKED scheduled calling.
