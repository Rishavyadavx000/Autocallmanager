# AutoCallManager V1.0.6 Core Reliability Update

## Core scheduled-call reliability
- Exact scheduled calls use `AlarmManager.setExactAndAllowWhileIdle()`.
- The execution receiver uses `goAsync()` and a short `PARTIAL_WAKE_LOCK` so the call request has a reliable execution window when the screen is off or the device is locked.
- The receiver does not launch the app UI or an arbitrary activity from the background.
- Calls are submitted directly to `TelecomManager.placeCall()`.
- Selected dual-SIM `PhoneAccountHandle` is validated before use.
- `TelecomManager.isOutgoingCallPermitted()` is checked when a specific SIM/account is selected.
- Call failures are recorded with a reason code instead of a generic boolean error.
- Reboot/app-update rescheduling is retained.

## User experience
- Dashboard now exposes a real scheduled-call engine readiness state.
- Existing Smart Time, 12-hour format, direct time input, NOW/+minute shortcuts, contact search, contact-only wizard and dual-SIM controls remain.
- Existing Gemini/prompt architecture remains.
- Admin Panel remains excluded.

## Platform constraints
- Screen-off/locked execution is designed around Android exact alarms + Telecom APIs.
- Android/OEM restrictions can still affect delivery or exact timing, so the UI reports readiness/errors rather than claiming an unconditional guarantee.
