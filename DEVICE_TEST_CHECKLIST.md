# AutoCallManager v1.0.9 — Real-Device Execution Test Checklist

Fill this in on the actual device. Nothing here is pre-filled or assumed.
"Do not say fixed until Screen OFF + LOCKED passes on a real device" — this
file exists so that claim can be checked against evidence, not memory.

## 0. Build

```
./gradlew clean
./gradlew :app:compileDebugKotlin
./gradlew :app:testDebugUnitTest
./gradlew :app:assembleDebug
```

compileDebugKotlin result: ______________________
testDebugUnitTest result: ______________________ (includes the 2 new files:
  ExecutionTraceStoreTest, BackgroundAdvisorTest — 15 new test cases total)
assembleDebug result: ______________________

## 1. Before each test

- Diagnostics → RECHECK — confirm all rows still PASS (unchanged from before)
- Diagnostics → REFRESH TRACE — tap CLEAR/re-check trace is empty or from a
  prior run (old rows won't be confused with this run's, since each row
  carries its own executionId, but a clean slate is easier to read)
- Note device: manufacturer/model, Android version, single or dual SIM

## 2. Run each scenario

For each row below: create a real schedule ~90 seconds out (or use
Diagnostics → RUN BACKGROUND TEST for A–F), put the device in the listed
state, wait, then reopen the app → Diagnostics → Execution Trace and record
what actually happened. Copy straight from the trace — do not infer.

| # | Scenario | ALARM_REGISTERED | ALARM_DELIVERED | WAKELOCK | CALL_EXECUTION_BEGIN | PHONE_ACCOUNT_RESOLVED | PLACE_CALL | CALL_STATE_CALLBACK | FINAL_OUTCOME |
|---|----------|---|---|---|---|---|---|---|---|
| A | Screen ON + unlocked | | | | | | | | |
| B | Screen ON + locked | | | | | | | | |
| C | Screen OFF + unlocked | | | | | | | | |
| D | Screen OFF + locked | | | | | | | | |
| E | App UI closed (swiped away) | | | | | | | | |
| F | Device in Doze (leave idle 5+ min, screen off, unplugged) | | | | | | | | |
| G | Single SIM | | | | | | | | |
| H | Dual SIM (test both slots) | | | | | | | | |

For each PASS/FAIL cell, paste the exact `detail` text from that trace row
(e.g. "SecurityException: ...", "SIM_ACCOUNT_UNAVAILABLE") rather than just
PASS/FAIL — the detail is what actually tells you what to fix next if D
fails.

## 3. If D (Screen OFF + locked) fails

Check, in this order, since these are the most common real-world causes on
Android once the code path itself is confirmed correct (see the accompanying
write-up for what was verified in code review):

1. Diagnostics → the OEM advisory banner (only appears if this device is a
   manufacturer known to add its own restrictions on top of stock Android).
   Tap "OPEN BACKGROUND SETTINGS" and enable Autostart / "No restrictions" /
   remove from any "deep sleep" or "purify" list, per that screen.
2. Settings → Battery → this app → make sure it's "Unrestricted", not just
   "Optimized".
3. Recent-apps → lock this app's card (many OEMs kill unlocked cards on
   screen-off regardless of the in-app battery setting).
4. Re-run test D after each change — don't change more than one setting
   between runs, or you won't know which one mattered.

## 4. Export

Diagnostics → EXPORT DIAGNOSTIC LOG after the full matrix is done, and keep
the exported .txt alongside this file — it has every stage, timestamped,
for every run in this session.
