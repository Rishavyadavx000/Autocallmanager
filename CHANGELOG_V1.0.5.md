# AutoCallManager V1.0.6

## Release focus
Stable V1.0.4 continuation with 12-hour user-facing clock formatting. Admin Panel is intentionally excluded.

## Time
- Wizard hour range is 1–12 with AM/PM selector.
- 12-hour input is converted to the existing 24-hour internal timestamp before scheduling.
- Dashboard, schedules, history, timeline and backup timestamps display `hh:mm:ss AM/PM`.
- Countdown remains `HH:MM:SS` as a duration.
- Existing epoch-millisecond schedule data remains compatible.

## AI and prompts
- Preserves the V1.0.4 Gemini flow and stable prompt IDs/versions.
- No Admin Panel or admin-only API manager is included.

## Build
- Version 1.0.6 / versionCode 106.
- GitHub Actions uses current Node24-based checkout/setup actions and Gradle setup.
