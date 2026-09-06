# AutoCallManager V1.0.8 - Timezone-Fix Branch Merge

Merges `AutoCallManager-v1_0_7-TIMEZONE-FIX.zip` (a targeted timezone-persistence patch
built on plain V1.0.7, pre-RBAC) into the V1.0.8-FIXED base (RBAC + AI Voice Call/consent
+ the day-of-week and phone-validation fixes from the previous pass).

## Why not a file-level overwrite

The timezone-fix branch's `admin-server` predates RBAC entirely (no `permissions.js`,
smaller `auth.js`/`db.js`/`server.js`) and has none of the AI Voice Call feature. Its
Android files (`MainActivity.kt`, `TaskStore.kt`, `BackupManager.kt`) also diverge from
V1.0.8 for unrelated reasons (AI Voice fields). Copying those files wholesale would have
deleted RBAC and AI Voice Call. Only the timezone-specific *intent* was ported onto
V1.0.8's current files, not the files themselves.

## What V1.0.8 already had independently

V1.0.8's `ScheduleCalculator.kt` was already rewritten around `java.time`
(Instant/ZoneId/ZonedDateTime) rather than `java.util.Calendar`, and already reads
`task.timezoneId` for every recurrence calculation -- the same core problem the
timezone-fix branch solves, reached by a different (and, once the day-of-week conversion
above was fixed, equivalent) route. `TaskStore.kt`/`BackupManager.kt` already had
`timezoneId` persisted and mirrored. **No `localTime` string is persisted separately**:
`java.time`'s `Instant.atZone()` correctly recovers the wall-clock time at the exact
instant `triggerAt` represents, so the extra field the Calendar-based branch needed as a
DST-drift workaround isn't required here.

## What was actually ported over

- **Bug fix**: `MainActivity.kt`'s `applyPayload()` (the edit-existing-schedule path) was
  never updating `task.timezoneId` or `task.executionMode` -- only new-task creation set
  them. The review screen already showed a live "Timezone" row implying the current zone
  would be saved on every save, including edits; it silently wasn't. Adopted the
  timezone-fix branch's explicit design choice ("re-anchor to current zone on edit, same
  rule as creation") and applied it to both fields.
- **Test suite**: `app/src/test/java/.../ScheduleCalculatorTest.kt`, adapted from the
  branch's scenarios A-H to this codebase's actual `java.time` API (no `localTime` param).
  Added `testImplementation("junit:junit:4.13.2")` to `app/build.gradle.kts`. Added a
  "Run unit tests" CI step (`gradlew :app:testDebugUnitTest`) -- previously nothing ran
  Kotlin-level tests at all, only structural/`node --check` validation.
- **`verify_schedule_logic.py`**: kept the branch's pattern of an independent
  zoneinfo-backed Python port for sandboxes with no Kotlin compiler, rewritten against
  V1.0.8's actual algorithm (14/14 checks pass, including a dedicated Sunday-only-weekly
  case for the day-of-week regression fixed last pass).

## Verification performed here

No Android SDK/Gradle/network in this sandbox, so nothing above was compiled. What was
run: `verify_schedule_logic.py` (14/14), all four `admin-server` test suites (unaffected,
re-confirmed passing), the WebView JS validation, and the CI's structural/file-existence
checks. Run `./gradlew testDebugUnitTest` and a full `assembleDebug` before shipping.
