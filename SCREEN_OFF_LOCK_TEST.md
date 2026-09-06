# V1.0.6 Screen-Off / Locked Call Test

1. Grant CALL_PHONE and READ_PHONE_STATE.
2. Grant Exact Alarm access in Android Settings.
3. Keep Notifications enabled so diagnostics are visible.
4. On a dual-SIM device, enable specific SIM only when two call-capable accounts are shown.
5. Create a one-time schedule 1-2 minutes in the future.
6. Lock the phone / turn the screen off.
7. Wait for the scheduled time.
8. Confirm the system initiated the call and that the schedule/history records the execution.
9. Repeat once with the screen on.
10. Repeat after rebooting with a future recurring schedule.

Notes:
- `setExactAndAllowWhileIdle()` is used for time-critical execution, but Android/OEM battery and telecom policies can still affect behavior.
- The test should be run on the actual target phone and Android version.
- The app does not start its own Activity from the background just to force a call screen.
