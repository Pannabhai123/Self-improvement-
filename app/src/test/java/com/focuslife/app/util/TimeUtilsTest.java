package com.focuslife.app.util;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Calendar;
import java.util.List;

/**
 * TimeUtils underpins every day key, streak, schedule and formatted duration in
 * the app, so its arithmetic is pinned here.
 *
 * <p>Assertions avoid anything locale- or timezone-dependent: the formatters that
 * build their output with {@code Locale.US} are compared exactly, while the ones
 * that follow the device locale are only checked for shape. Day-key arithmetic is
 * compared against explicit keys rather than against {@code new Date()}, so the
 * tests give the same answer whenever and wherever CI runs them.</p>
 */
public class TimeUtilsTest {

    // ------------------------------------------------------------------
    //  Day keys
    // ------------------------------------------------------------------

    @Test
    public void dayKeyIsIsoLikeAndTenCharacters() {
        String today = TimeUtils.today();
        assertEquals(10, today.length());
        assertEquals("day key must be yyyy-MM-dd", '-', today.charAt(4));
        assertEquals('-', today.charAt(7));
    }

    @Test
    public void todayAndDayKeyOfNowAgree() {
        assertEquals(TimeUtils.today(), TimeUtils.dayKey(System.currentTimeMillis()));
    }

    @Test
    public void startOfDayRoundTripsThroughDayKey() {
        String today = TimeUtils.today();
        assertEquals(today, TimeUtils.dayKey(TimeUtils.startOfDay(today)));
    }

    @Test
    public void startOfDayIsLocalMidnight() {
        assertEquals(0, TimeUtils.minuteOfDay(TimeUtils.startOfDay(TimeUtils.today())));
    }

    @Test
    public void endOfDayIsOneMillisecondBeforeMidnight() {
        String today = TimeUtils.today();
        assertEquals(TimeUtils.DAY_MS - 1,
                TimeUtils.endOfDay(today) - TimeUtils.startOfDay(today));
    }

    @Test
    public void dayOffsetStepsBackwardsAndForwards() {
        assertEquals("2026-11-16", TimeUtils.dayOffset("2026-11-17", -1));
        assertEquals("2026-11-18", TimeUtils.dayOffset("2026-11-17", 1));
        assertEquals("2026-11-17", TimeUtils.dayOffset("2026-11-17", 0));
    }

    @Test
    public void dayOffsetCrossesMonthAndYearBoundaries() {
        assertEquals("2026-02-28", TimeUtils.dayOffset("2026-03-01", -1));
        assertEquals("2025-12-31", TimeUtils.dayOffset("2026-01-01", -1));
        assertEquals("2027-01-01", TimeUtils.dayOffset("2026-12-31", 1));
    }

    @Test
    public void dayOffsetCrossesALeapDay() {
        // 2024 is a leap year, so the day before 1 March is 29 February.
        assertEquals("2024-02-29", TimeUtils.dayOffset("2024-03-01", -1));
        // 2026 is not.
        assertEquals("2026-02-28", TimeUtils.dayOffset("2026-03-01", -1));
    }

    @Test
    public void daysBetweenIsSignedAndWholeDays() {
        assertEquals(7, TimeUtils.daysBetween("2026-11-10", "2026-11-17"));
        assertEquals(-7, TimeUtils.daysBetween("2026-11-17", "2026-11-10"));
        assertEquals(0, TimeUtils.daysBetween("2026-11-17", "2026-11-17"));
        assertEquals(1, TimeUtils.daysBetween("2026-12-31", "2027-01-01"));
    }

    @Test
    public void isTodayAndIsYesterdayAgreeWithDayOffset() {
        assertTrue(TimeUtils.isToday(TimeUtils.today()));
        assertTrue(TimeUtils.isYesterday(TimeUtils.dayOffsetFromToday(-1)));
        assertTrue(!TimeUtils.isToday(TimeUtils.dayOffsetFromToday(-1)));
        assertTrue(!TimeUtils.isYesterday(TimeUtils.today()));
    }

    @Test
    public void malformedDayKeyFallsBackInsteadOfCrashing() {
        // parseDay swallows a parse failure and returns today, so a corrupted
        // stored key cannot take the app down.
        Calendar c = TimeUtils.parseDay("not-a-date");
        assertEquals(TimeUtils.today(), TimeUtils.dayKey(c));
    }

    // ------------------------------------------------------------------
    //  Day sequences
    // ------------------------------------------------------------------

    @Test
    public void lastSevenDayKeysEndsTodayAndIsChronological() {
        List<String> keys = TimeUtils.lastSevenDayKeys();
        assertEquals(7, keys.size());
        assertEquals(TimeUtils.today(), keys.get(6));
        for (int i = 1; i < keys.size(); i++) {
            assertEquals("each key must be exactly one day after the previous",
                    1, TimeUtils.daysBetween(keys.get(i - 1), keys.get(i)));
        }
    }

    @Test
    public void lastNDayKeysRespectsTheRequestedCount() {
        assertEquals(3, TimeUtils.lastNDayKeys(3).size());
        assertEquals(1, TimeUtils.lastNDayKeys(1).size());
        assertEquals(TimeUtils.today(), TimeUtils.lastNDayKeys(1).get(0));
        assertEquals(30, TimeUtils.lastNDayKeys(30).size());
    }

    @Test
    public void currentWeekRangeStartsMondayAndSpansSevenDays() {
        String[] range = TimeUtils.currentWeekRange();
        assertEquals(2, range.length);
        assertEquals(6, TimeUtils.daysBetween(range[0], range[1]));

        Calendar start = TimeUtils.parseDay(range[0]);
        assertEquals("the week must start on a Monday",
                Calendar.MONDAY, start.get(Calendar.DAY_OF_WEEK));

        String today = TimeUtils.today();
        assertTrue("today must fall inside the current week",
                TimeUtils.daysBetween(range[0], today) >= 0
                        && TimeUtils.daysBetween(today, range[1]) >= 0);
    }

    @Test
    public void rollingWindowAndCalendarWeekBothSpanSevenDaysButAreAnchoredDifferently() {
        // lastSevenDayKeys is a rolling window anchored to today;
        // currentWeekRange is a calendar week anchored to Monday. The two coincide
        // exactly when today is Sunday, so the relationship is asserted rather than
        // a difference, which would make this test fail one day in seven.
        String[] range = TimeUtils.currentWeekRange();
        List<String> rolling = TimeUtils.lastSevenDayKeys();

        assertEquals(7, rolling.size());
        assertEquals(TimeUtils.today(), rolling.get(6));
        assertEquals(6, TimeUtils.daysBetween(range[0], range[1]));
        assertEquals(6, TimeUtils.daysBetween(rolling.get(0), rolling.get(6)));

        boolean todayIsSunday = Calendar.SUNDAY
                == TimeUtils.parseDay(TimeUtils.today()).get(Calendar.DAY_OF_WEEK);
        if (todayIsSunday) {
            assertEquals("on a Sunday the two windows are the same seven days",
                    range[0], rolling.get(0));
            assertEquals(range[1], rolling.get(6));
        } else {
            assertEquals("the calendar week always ends on a Sunday",
                    Calendar.SUNDAY,
                    TimeUtils.parseDay(range[1]).get(Calendar.DAY_OF_WEEK));
            // The rolling window ends today, which is not Sunday, so the anchors
            // cannot coincide.
            assertNotEquals(range[1], rolling.get(6));
        }
    }

    // ------------------------------------------------------------------
    //  Minute of day
    // ------------------------------------------------------------------

    @Test
    public void toMinuteOfDayConvertsAndClamps() {
        assertEquals(270, TimeUtils.toMinuteOfDay(4, 30));
        assertEquals(0, TimeUtils.toMinuteOfDay(0, 0));
        assertEquals(1439, TimeUtils.toMinuteOfDay(23, 59));
        // Out-of-range input is clamped rather than producing a nonsense minute.
        assertEquals(1439, TimeUtils.toMinuteOfDay(99, 99));
        assertEquals(0, TimeUtils.toMinuteOfDay(-5, -5));
    }

    @Test
    public void minuteOfDayNowIsWithinTheDay() {
        int minute = TimeUtils.minuteOfDayNow();
        assertTrue(minute >= 0 && minute <= 1439);
        assertEquals(minute / 60, TimeUtils.hourOfDayNow());
    }

    @Test
    public void millisForMinuteLandsOnTheRequestedMinute() {
        String today = TimeUtils.today();
        long at = TimeUtils.millisForMinute(today, 270);
        assertEquals(270, TimeUtils.minuteOfDay(at));
        assertEquals(today, TimeUtils.dayKey(at));
    }

    @Test
    public void millisUntilMidnightIsAlwaysPositiveAndWithinADay() {
        long millis = TimeUtils.millisUntilMidnight();
        assertTrue("must never be zero or negative", millis >= TimeUtils.MINUTE_MS);
        assertTrue("must never exceed one day", millis <= TimeUtils.DAY_MS);
    }

    @Test
    public void dayPartIsOneOfTheFourBuckets() {
        int part = TimeUtils.dayPart();
        assertTrue(part >= TimeUtils.DAY_PART_MORNING && part <= TimeUtils.DAY_PART_NIGHT);
    }

    // ------------------------------------------------------------------
    //  Formatters — these use Locale.US, so exact comparison is safe
    // ------------------------------------------------------------------

    @Test
    public void formatMinutesRendersHoursAndMinutes() {
        assertEquals("0m", TimeUtils.formatMinutes(0));
        assertEquals("45m", TimeUtils.formatMinutes(45));
        assertEquals("1h", TimeUtils.formatMinutes(60));
        assertEquals("1h 30m", TimeUtils.formatMinutes(90));
        assertEquals("6h", TimeUtils.formatMinutes(360));
        assertEquals("23h 59m", TimeUtils.formatMinutes(1439));
    }

    @Test
    public void formatMinutesNeverShowsANegativeDuration() {
        assertEquals("0m", TimeUtils.formatMinutes(-5));
    }

    @Test
    public void formatDistanceSwitchesToKilometresAtOneThousandMetres() {
        assertEquals("0 m", TimeUtils.formatDistance(0));
        assertEquals("800 m", TimeUtils.formatDistance(800));
        assertEquals("999 m", TimeUtils.formatDistance(999));
        assertEquals("1.0 km", TimeUtils.formatDistance(1000));
        assertEquals("1.6 km", TimeUtils.formatDistance(1600));
        assertEquals("0 m", TimeUtils.formatDistance(-1));
    }

    @Test
    public void formatDurationAddsAnHourSegmentOnlyWhenNeeded() {
        assertEquals("00:00", TimeUtils.formatDuration(0));
        assertEquals("01:05", TimeUtils.formatDuration(65_000L));
        assertEquals("1:02:05", TimeUtils.formatDuration(3_725_000L));
        assertEquals("00:00", TimeUtils.formatDuration(-1L));
    }

    @Test
    public void formatSecondsMatchesFormatDuration() {
        assertEquals(TimeUtils.formatDuration(65_000L), TimeUtils.formatSeconds(65));
        assertEquals("00:00", TimeUtils.formatSeconds(0));
    }

    @Test
    public void formatPaceIsAPlaceholderWhenThereIsNoPace() {
        assertEquals("--", TimeUtils.formatPace(0));
        assertEquals("--", TimeUtils.formatPace(-10));
        assertEquals("5'20\"/km", TimeUtils.formatPace(320));
        assertEquals("4'00\"/km", TimeUtils.formatPace(240));
    }

    @Test
    public void formatClock12HandlesMidnightAndNoon() {
        assertEquals("12:00 AM", TimeUtils.formatClock12(0));
        assertEquals("04:30 AM", TimeUtils.formatClock12(270));
        assertEquals("12:00 PM", TimeUtils.formatClock12(720));
        assertEquals("01:30 PM", TimeUtils.formatClock12(810));
        assertEquals("11:59 PM", TimeUtils.formatClock12(1439));
    }

    @Test
    public void formatClock24IsZeroPadded() {
        assertEquals("00:00", TimeUtils.formatClock24(0));
        assertEquals("04:30", TimeUtils.formatClock24(270));
        assertEquals("23:59", TimeUtils.formatClock24(1439));
    }

    @Test
    public void theSeededWakeUpSlotRendersAsFourThirtyInTheMorning() {
        // The brief's routine starts at 04:30; both clock formats must show it
        // unambiguously, in either the user's 12- or 24-hour preference.
        int wakeUp = TimeUtils.toMinuteOfDay(4, 30);
        assertEquals("04:30 AM", TimeUtils.formatClock12(wakeUp));
        assertEquals("04:30", TimeUtils.formatClock24(wakeUp));
    }

    // ------------------------------------------------------------------
    //  Locale-dependent labels: shape only
    // ------------------------------------------------------------------

    @Test
    public void weekdayLabelsAreNonEmptyAndShort() {
        String today = TimeUtils.today();
        String weekday = TimeUtils.shortWeekday(today);
        String initial = TimeUtils.weekdayInitial(today);
        String date = TimeUtils.shortDate(today);

        assertTrue(weekday != null && !weekday.isEmpty());
        assertTrue("short weekday must fit a chart axis", weekday.length() <= 4);
        assertTrue(initial != null && !initial.isEmpty());
        assertTrue(date != null && !date.isEmpty());
    }

    @Test
    public void consecutiveDaysGetDifferentWeekdayLabels() {
        String today = TimeUtils.today();
        assertNotEquals(TimeUtils.shortWeekday(today),
                TimeUtils.shortWeekday(TimeUtils.dayOffset(today, 1)));
    }
}
