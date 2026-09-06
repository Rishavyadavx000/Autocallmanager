# AutoCallManager V1.0.6 Core Build Check

Version: 1.0.6 / versionCode 106

## Static checks
- AndroidManifest XML: PASS
- JavaScript syntax: PASS
- Duplicate JavaScript functions: PASS (0)
- Root Gradle project or ZIP extraction workflow: PASS
- Contact-only Step 1 retained: PASS
- Contact picker search: PASS
- Dual-SIM selector: PASS
- Direct hour/minute/second input: PASS
- 12-hour AM/PM: PASS
- Current device time / NOW / minute shortcuts: PASS
- Live selected-time validation: PASS
- Real-time Overview markers: PASS
- Gemini + Prompt architecture retained: PASS
- Admin Panel excluded: PASS
- `setExactAndAllowWhileIdle()` present: PASS
- Exact alarm permission check present: PASS
- `PARTIAL_WAKE_LOCK` execution guard present: PASS
- `BroadcastReceiver.goAsync()` present: PASS
- `TelecomManager.placeCall()` present: PASS
- Specific `PhoneAccountHandle` validation present: PASS
- `isOutgoingCallPermitted()` check present: PASS
- Reboot / package-update rescheduling retained: PASS

## Runtime verification required
- Screen ON scheduled call
- Screen OFF scheduled call
- Device LOCKED scheduled call
- Doze/idle scheduled call
- Dual-SIM selected call
- Reboot + recurring schedule

Android/OEM policies can still affect exact timing and telecom execution, so runtime testing on the target device is required.

## Build verification
The current execution runtime does not include a configured Android SDK/Gradle installation. GitHub Actions remains the authoritative `assembleDebug` and APK verification environment.
