# AutoCallManager V1.0.7 Final Changelog

## Combined release
- V1.0.6 real-time/screen-off/locked-device scheduled-call core retained.
- Provided authenticated Admin Panel backend integrated.
- Settings now exposes a dedicated Administration section.
- Remote Prompt Catalog configuration and sync retained.

## Reliability fixes after V1.0.6 recheck
- Added background telephony state receiver so execution tracking does not depend on the Activity being visible.
- Retry is no longer scheduled immediately after `placeCall()` returns success; it waits for call-state outcome/watchdog handling.
- Added persistent active-call execution tracker and watchdog.
- Added stale-state protection after `placeCall()` so a fast background telephony event is not overwritten by an old task snapshot.
- Overview terminal counts now use actual call-state outcomes instead of generic history/notification events.
- New schedules no longer introduce the removed Contact Group field as `Default`; empty group is used for new contact-only schedules.

## User-visible features retained
- 12-hour `hh:mm:ss AM/PM` time format.
- Direct time typing + +/- adjustment.
- Current-time/quick-minute helpers.
- Contact-only Step 1 with name/number search in the picker.
- Dual-SIM selector only when two usable SIM accounts are available.
- Gemini + stable versioned prompt IDs.
- Voice/TTS, notifications, history/timeline, Pause/Resume/Stop All, backup/restore, reboot/update rescheduling.

## Admin scope
The supplied Admin Panel provides authenticated prompt management, version history, rollback, audit, and optional server-side Gemini testing. It does not provide a general multi-provider API-key vault or multiple admin roles in this release.

## Version
- Android `versionName`: `1.0.7`
- Android `versionCode`: `107`
- Admin server package version: `1.0.7`
