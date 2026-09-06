# AutoCallManager V1.1.0

## Home UI reorder + new Phone/Calls module

### Dashboard (Home)
- Reordered the Home screen to: Header, Add Schedule, System Ready, Overview, Today, Next Event, Modules, Recent Activity. Add Schedule and Recent Activity moved; System Ready, Overview/Today, Next Event and Modules keep their existing content and data untouched.
- Split the merged Overview/Today block into two peer sections (`Overview`, `Today`) so the on-screen order matches the required list item-for-item. No calculation, id, or data-binding changed.
- Header is now `[Phone icon] AUTO CALL MANAGER [Schedule icon] [Menu icon]`:
  - Phone icon (brand mark) now opens the new Phone module.
  - Added a dedicated Schedule (calendar) icon that opens the existing Schedules screen (`screen-calls`) -- no duplicate schedule page was created.
  - The existing settings icon is now drawn as a menu glyph; it still opens the same Settings screen as before.

### New: Phone/Calls module (`PhoneActivity`)
- Real screen reachable from the Home header's phone icon: Recent Calls (All/Missed, search), Contacts (search), a persistent dial pad, and Calls/Contacts bottom navigation.
- Manual calling places an actual cellular call through Android's public Telecom API by calling the Scheduler's own `CallManager.placeCallDetailed()` -- the same validated, multi-SIM-aware call path the Scheduler already uses. No new Telecom-calling code was written; nothing here is a simulated or fake call.
- If two active SIMs are detected, a SIM picker is shown before dialing; with 0-1 SIMs it dials directly.
- Call status (Calling.../Ringing/In call/Call ended) is read from the platform's own call-state broadcast and shown as-is. When the state can't be determined (e.g. permission not granted) it is shown as "Status unavailable" rather than guessed. Once a call is placed, the app cannot mute/hold/hang it up -- that requires being the default dialer -- so the UI points the user to their phone's own call screen for that.
- Added `READ_CALL_LOG` permission, requested only when the Phone module is opened (not at first app launch).
- New files: `PhoneActivity.kt`, `PhoneCallController.kt` (delegates to `CallManager`), `PhoneCallStateWatcher.kt` (independent call-state listener, intentionally not sharing `CallStateManager`'s singleton -- see its KDoc), `RecentCallsRepository.kt`, `PhoneContactsRepository.kt`, `phone_ui.html`, `activity_phone.xml`.

### Explicitly unchanged
- Scheduler, AI Voice, backend, diagnostics, alarms, retry logic, notification logic, reboot restoration, and all existing stored schedule data.
- The Modules row's existing `CALLS` tile still opens the Schedules list, exactly as before.

### Known issue noted, not fixed in this release
- `CallExecutionManager.kt`'s scheduled-call classification still treats OFFHOOK under `MIN_CONNECTED_OFFHOOK_MS` (6s) as `CALL_OFFHOOK_TOO_BRIEF` / failed (added in V1.0.9). The new Phone module's manual-call status display does not use duration to classify success or failure anywhere. Fixing the Scheduler's classifier itself was out of scope for this change and was left untouched.
