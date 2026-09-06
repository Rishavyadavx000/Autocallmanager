# AutoCallManager V1.0.9

## Reliability release

- Fixed the missing `retryDelaySecondsFor()` compile reference.
- Added first-OFFHOOK timestamp tracking so repeated OFFHOOK broadcasts do not reset the duration clock.
- Replaced the old `success = sawOffhook` shortcut with duration-based call-end classification.
- Added the `CALL_OFFHOOK_TOO_BRIEF` outcome for short OFFHOOK events.
- Added pure unit-testable retry/classification helpers.
- Preserved normal SIM calling, exact alarms, screen-off/locked execution path, dual SIM, timezone/recurrence, reboot recovery, history/timeline, Admin, AI Voice safety and consent architecture.
- Frosted Glass UI 2.0 remains explicitly excluded from this release.
- GitHub workflow now validates compile, unit tests, APK build and artifact generation with Gradle build cache disabled.
