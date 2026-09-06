# AutoCallManager V1.0.6

## V1.0.6 Smart Time + Real-Time + Core Reliability
- 12-hour `hh:mm:ss AM/PM` schedule UI retained.
- Current device time, direct text entry, +/- controls, NOW, and minute shortcuts retained.
- Overview uses real schedule/history metrics and live next-event countdown.
- Contact-only wizard and contact search retained.
- Dual-SIM selector retained and used only when two call-capable SIM accounts are detected.
- Gemini AI + stable versioned prompt architecture retained.
- Admin Panel excluded.

### Screen-off / locked-device call reliability
- Exact schedules use `AlarmManager.setExactAndAllowWhileIdle()`.
- Alarm receiver uses `goAsync()` and a short `PARTIAL_WAKE_LOCK` during execution.
- Scheduled calls use `TelecomManager.placeCall()` without starting a background Activity.
- Specific SIM selection is validated with `PhoneAccountHandle` and `isOutgoingCallPermitted()`.
- Call results include explicit failure reason codes in history.
- Reboot and package-update schedule re-registration retained.
