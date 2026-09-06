"""
Independent verification harness for ScheduleCalculator.kt (v1.0.8+, java.time-based).

This is a line-for-line Python port of the actual normaliseSlots()/nextRecurring()
algorithm, using Python's zoneinfo (same IANA tzdata family java.time draws from) as
an independent DST-aware oracle. It exists because this project is regularly reviewed
in sandboxes with no kotlinc/Android SDK/network -- when that's the case, this script
is what "verified" means: real, executed, independently-reasoned checks of the
algorithm's logic, not a substitute for `./gradlew testDebugUnitTest`
(app/src/test/java/.../ScheduleCalculatorTest.kt) under a real JVM.

Covers scenarios A-H, matching ScheduleCalculatorTest.kt one-to-one -- including C2,
a regression guard for a bug this project actually shipped once: java.time.DayOfWeek is
Mon=1..Sun=7 (ISO-8601), but repeatDays uses Sun=1..Sat=7 (the wizard UI's data-day chips,
and the java.util.Calendar convention the codebase used before switching to java.time).
Without the (isoValue % 7) + 1 conversion in nextRecurring(), every weekly schedule fired
one day later than the day the user actually picked.
"""
from datetime import datetime, timedelta
from zoneinfo import ZoneInfo

# UI convention (auto_call_ui.html data-day chips / legacy java.util.Calendar): Sun=1..Sat=7
SUN, MON, TUE, WED, THU, FRI, SAT = 1, 2, 3, 4, 5, 6, 7
ALL_DAYS = {SUN, MON, TUE, WED, THU, FRI, SAT}


def safe_zone(zone_id):
    """Mirrors: runCatching { ZoneId.of(task.timezoneId) }.getOrDefault(ZoneId.systemDefault())"""
    try:
        return ZoneInfo(zone_id) if zone_id else ZoneInfo("UTC")  # UTC stands in for systemDefault() here
    except Exception:
        return ZoneInfo("UTC")


def normalise_slots(task):
    slots = sorted(set(x for x in task.get("timeSlots", []) if 0 <= x <= 1439))
    if slots:
        return slots
    zone = safe_zone(task.get("timezoneId", ""))
    dt = datetime.fromtimestamp(task["triggerAt"] / 1000, tz=zone)
    return [dt.hour * 60 + dt.minute]


def next_recurring(task, now_millis):
    if task["recurrence"] == "ONCE":
        return None
    zone = safe_zone(task.get("timezoneId", ""))
    slots = normalise_slots(task)
    if not slots:
        return None
    template = datetime.fromtimestamp(task["triggerAt"] / 1000, tz=zone)
    second = max(0, min(59, template.second))
    if task["recurrence"] == "WEEKLY" and task.get("repeatDays"):
        allowed_days = set(task["repeatDays"])
    else:
        allowed_days = set(ALL_DAYS)
    base_date = datetime.fromtimestamp(now_millis / 1000, tz=zone).date()
    for offset in range(0, 371):
        day_date = base_date + timedelta(days=offset)
        iso = day_date.isoweekday()      # Mon=1..Sun=7, same convention as java.time.DayOfWeek.value
        day_of_week = (iso % 7) + 1      # THE FIX: convert ISO -> Sun=1..Sat=7
        if day_of_week not in allowed_days:
            continue
        for minutes in slots:
            hh, mm = divmod(minutes, 60)
            candidate = datetime(day_date.year, day_date.month, day_date.day, hh, mm, second, tzinfo=zone)
            candidate_millis = int(candidate.timestamp() * 1000)
            if candidate_millis > now_millis:
                return candidate_millis
    return None


def epoch(zone_id, y, mo, d, h, mi, s=0):
    return int(datetime(y, mo, d, h, mi, s, tzinfo=ZoneInfo(zone_id)).timestamp() * 1000)


def wall(millis, zone_id):
    return datetime.fromtimestamp(millis / 1000, tz=ZoneInfo(zone_id))


passed, failed = 0, 0


def check(name, cond, detail=""):
    global passed, failed
    if cond:
        passed += 1
        print(f"PASS  {name}")
    else:
        failed += 1
        print(f"FAIL  {name}  {detail}")


if __name__ == "__main__":
    print("=== A. Daily schedule created in Asia/Kolkata ===")
    t = dict(recurrence="DAILY", timezoneId="Asia/Kolkata",
              triggerAt=epoch("Asia/Kolkata", 2026, 8, 30, 8, 0, 0), timeSlots=[], repeatDays=set())
    w = wall(next_recurring(t, epoch("Asia/Kolkata", 2026, 8, 30, 6, 0, 0)), "Asia/Kolkata")
    check("A1 before-fire same day", w.day == 30 and w.hour == 8 and w.minute == 0, str(w))
    w = wall(next_recurring(t, epoch("Asia/Kolkata", 2026, 8, 30, 9, 0, 0)), "Asia/Kolkata")
    check("A2 after-fire rolls to next day", w.day == 31 and w.hour == 8 and w.minute == 0, str(w))

    print("\n=== B. Result stays zone-anchored regardless of the instant 'now' is framed from ===")
    lt1 = wall(next_recurring(t, epoch("America/New_York", 2026, 9, 15, 1, 0, 0)), "Asia/Kolkata")
    check("B1 stays Kolkata 08:00 (asked from a NY instant)", lt1.hour == 8 and lt1.minute == 0, str(lt1))
    lt2 = wall(next_recurring(t, epoch("Australia/Sydney", 2026, 10, 3, 23, 0, 0)), "Asia/Kolkata")
    check("B2 stays Kolkata 08:00 (asked from a Sydney instant)", lt2.hour == 8 and lt2.minute == 0, str(lt2))

    print("\n=== C. Weekly schedule + Sun=1..Sat=7 <-> ISO day-of-week conversion ===")
    names = ["Sun", "Mon", "Tue", "Wed", "Thu", "Fri", "Sat"]
    start = datetime(2026, 8, 30, tzinfo=ZoneInfo("America/New_York"))  # a real Sunday
    all_ok = all((start + timedelta(days=i)).date().isoweekday() % 7 + 1 == i + 1 for i in range(7))
    check("C0 Sun=1..Sat=7 conversion holds for all 7 real calendar days", all_ok)

    t = dict(recurrence="WEEKLY", timezoneId="America/New_York",
              triggerAt=epoch("America/New_York", 2026, 8, 24, 18, 30, 0), timeSlots=[],
              repeatDays={MON, THU})
    now = epoch("America/New_York", 2026, 8, 25, 0, 0, 0)
    w = wall(next_recurring(t, now), "America/New_York")
    check("C weekly picks next allowed day (Thu 8/27), not Wed", w.day == 27 and w.hour == 18 and w.minute == 30, str(w))

    t_sun = dict(recurrence="WEEKLY", timezoneId="America/New_York",
                  triggerAt=epoch("America/New_York", 2026, 8, 23, 10, 0, 0), timeSlots=[],
                  repeatDays={SUN})
    now_sun = epoch("America/New_York", 2026, 8, 24, 0, 0, 0)
    w_sun = wall(next_recurring(t_sun, now_sun), "America/New_York")
    check("C2 Sunday-only weekly lands on the following Sunday (8/30), not Monday -- the shipped bug, guarded",
          w_sun.day == 30 and w_sun.isoweekday() == 7, str(w_sun))

    print("\n=== D. One-time schedule ===")
    t = dict(recurrence="ONCE", timezoneId="Asia/Kolkata",
              triggerAt=epoch("Asia/Kolkata", 2026, 8, 30, 8, 0, 0), timeSlots=[], repeatDays=set())
    check("D ONCE never recurs", next_recurring(t, epoch("Asia/Kolkata", 2026, 8, 30, 0, 0, 0)) is None)

    print("\n=== E. 12:00 AM ===")
    t = dict(recurrence="DAILY", timezoneId="Europe/London",
              triggerAt=epoch("Europe/London", 2026, 6, 1, 0, 0, 0), timeSlots=[], repeatDays=set())
    w = wall(next_recurring(t, epoch("Europe/London", 2026, 6, 1, 12, 0, 0)), "Europe/London")
    check("E midnight resolves to hour 0, next day", w.day == 2 and w.hour == 0 and w.minute == 0, str(w))

    print("\n=== F. 12:00 PM ===")
    t = dict(recurrence="DAILY", timezoneId="Europe/London",
              triggerAt=epoch("Europe/London", 2026, 6, 1, 12, 0, 0), timeSlots=[], repeatDays=set())
    w = wall(next_recurring(t, epoch("Europe/London", 2026, 6, 1, 6, 0, 0)), "Europe/London")
    check("F noon resolves to hour 12, same day", w.day == 1 and w.hour == 12 and w.minute == 0, str(w))

    print("\n=== G. DST-observing timezone (America/New_York, spring-forward Mar 8 2026) ===")
    t = dict(recurrence="DAILY", timezoneId="America/New_York",
              triggerAt=epoch("America/New_York", 2026, 3, 1, 9, 0, 0), timeSlots=[], repeatDays=set())
    nb = next_recurring(t, epoch("America/New_York", 2026, 3, 7, 10, 0, 0))
    wb = wall(nb, "America/New_York")
    check("G1 holds 09:00 local just before DST", wb.hour == 9 and wb.minute == 0 and wb.day == 8, str(wb))
    na = next_recurring(t, epoch("America/New_York", 2026, 3, 9, 10, 0, 0))
    wa = wall(na, "America/New_York")
    check("G2 holds 09:00 local just after DST", wa.hour == 9 and wa.minute == 0 and wa.day == 10, str(wa))
    off_orig = datetime.fromtimestamp(t["triggerAt"] / 1000, tz=ZoneInfo("America/New_York")).utcoffset()
    off_after = datetime.fromtimestamp(nb / 1000, tz=ZoneInfo("America/New_York")).utcoffset()
    check("G3 UTC offset genuinely differs vs. creation instant (real DST lookup, not hardcoded)",
          off_orig != off_after, f"{off_orig} vs {off_after}")

    print("\n=== H. Blank timezoneId falls back safely (no crash, no silent GMT) ===")
    t = dict(recurrence="DAILY", timezoneId="", triggerAt=epoch("UTC", 2026, 5, 1, 8, 0, 0), timeSlots=[], repeatDays=set())
    nxt = next_recurring(t, epoch("UTC", 2026, 5, 1, 9, 0, 0))
    check("H falls back without raising", nxt is not None, str(nxt))

    print(f"\n=== RESULT: {passed} passed, {failed} failed ===")
