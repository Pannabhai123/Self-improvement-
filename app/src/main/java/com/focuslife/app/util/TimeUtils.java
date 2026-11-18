package com.focuslife.app.util;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;

/**
 * Date and duration helpers.
 *
 * <p>Every "day" in Focus Life is keyed by a {@code yyyy-MM-dd} string produced
 * in the <em>device's default</em> timezone. This is deliberate: a study session
 * at 11 pm local time must count toward the local day the user experiences, and
 * the key is stored alongside every row so later queries never re-derive it.</p>
 *
 * <p>All methods are pure and static so they are unit-testable on the JVM.</p>
 */
public final class TimeUtils {

    private TimeUtils() {
    }

    private static final ThreadLocal<SimpleDateFormat> DAY_FORMAT =
            ThreadLocal.withInitial(() -> new SimpleDateFormat("yyyy-MM-dd", Locale.US));

    private static final ThreadLocal<SimpleDateFormat> CLOCK_FORMAT =
            ThreadLocal.withInitial(() -> new SimpleDateFormat("HH:mm", Locale.US));

    public static final long MINUTE_MS = 60_000L;
    public static final long HOUR_MS = 3_600_000L;
    public static final long DAY_MS = 86_400_000L;

    // ------------------------------------------------------------------
    //  Day keys
    // ------------------------------------------------------------------

    /** Day key for "now". */
    public static String today() {
        return dayKey(System.currentTimeMillis());
    }

    /** Day key for an arbitrary epoch-millis instant. */
    public static String dayKey(long epochMillis) {
        return DAY_FORMAT.get().format(new Date(epochMillis));
    }

    /** Day key for a {@link Calendar}, used when walking backwards for streaks. */
    public static String dayKey(Calendar calendar) {
        return dayKey(calendar.getTimeInMillis());
    }

    /** Day key {@code daysFromNow} days away (negative = in the past). */
    public static String dayOffsetFromToday(int daysFromNow) {
        Calendar c = Calendar.getInstance();
        c.add(Calendar.DAY_OF_YEAR, daysFromNow);
        return dayKey(c);
    }

    /** Day key {@code daysFromNow} days away from an arbitrary day key. */
    public static String dayOffset(String dayKey, int daysFromNow) {
        Calendar c = parseDay(dayKey);
        c.add(Calendar.DAY_OF_YEAR, daysFromNow);
        return dayKey(c);
    }

    /** Midnight at the start of the day described by {@code dayKey}. */
    public static long startOfDay(String dayKey) {
        return parseDay(dayKey).getTimeInMillis();
    }

    /** One millisecond before midnight at the end of that day. */
    public static long endOfDay(String dayKey) {
        return startOfDay(dayKey) + DAY_MS - 1;
    }

    /** Midnight at the start of today. */
    public static long startOfToday() {
        return startOfDay(today());
    }

    /** Parses a {@code yyyy-MM-dd} key into a Calendar at local midnight. */
    public static Calendar parseDay(String dayKey) {
        Calendar c = Calendar.getInstance();
        try {
            Date d = DAY_FORMAT.get().parse(dayKey);
            if (d != null) {
                c.setTime(d);
            }
        } catch (Exception ignored) {
            // Malformed key: fall back to today rather than crashing.
            c.setTimeInMillis(System.currentTimeMillis());
        }
        c.set(Calendar.HOUR_OF_DAY, 0);
        c.set(Calendar.MINUTE, 0);
        c.set(Calendar.SECOND, 0);
        c.set(Calendar.MILLISECOND, 0);
        return c;
    }

    /**
     * The seven day keys ending today, oldest first. Used by every weekly
     * chart so the bars are always in chronological order even when some days
     * have no data at all.
     */
    public static List<String> lastSevenDayKeys() {
        return lastNDayKeys(7);
    }

    public static List<String> lastNDayKeys(int n) {
        List<String> keys = new ArrayList<>(n);
        for (int i = n - 1; i >= 0; i--) {
            keys.add(dayOffsetFromToday(-i));
        }
        return keys;
    }

    /** Range covering the current week, Monday first. */
    public static String[] currentWeekRange() {
        Calendar c = Calendar.getInstance();
        // Shift so that Monday is the first day of the week.
        int dow = c.get(Calendar.DAY_OF_WEEK); // SUNDAY=1 .. SATURDAY=7
        int daysFromMonday = (dow == Calendar.SUNDAY) ? 6 : dow - Calendar.MONDAY;
        c.add(Calendar.DAY_OF_YEAR, -daysFromMonday);
        String start = dayKey(c);
        c.add(Calendar.DAY_OF_YEAR, 6);
        String end = dayKey(c);
        return new String[]{start, end};
    }

    /**
     * Whole days between two day keys, {@code to - from}. Returns 0 for the
     * same day and a negative number when {@code to} precedes {@code from}.
     */
    public static int daysBetween(String fromDayKey, String toDayKey) {
        long a = startOfDay(fromDayKey);
        long b = startOfDay(toDayKey);
        // Round rather than floor: DST shifts can make the raw difference
        // 23 or 25 hours, which would otherwise corrupt streak maths.
        return (int) Math.round((b - a) / (double) DAY_MS);
    }

    // ------------------------------------------------------------------
    //  Minute-of-day
    // ------------------------------------------------------------------

    /** Minutes since local midnight, 0-1439. */
    public static int minuteOfDay(long epochMillis) {
        Calendar c = Calendar.getInstance();
        c.setTimeInMillis(epochMillis);
        return c.get(Calendar.HOUR_OF_DAY) * 60 + c.get(Calendar.MINUTE);
    }

    public static int minuteOfDayNow() {
        return minuteOfDay(System.currentTimeMillis());
    }

    public static int hourOfDayNow() {
        return minuteOfDayNow() / 60;
    }

    public static int toMinuteOfDay(int hour, int minute) {
        return Math.max(0, Math.min(1439, hour * 60 + minute));
    }

    /**
     * Epoch millis for {@code minuteOfDay} on the given day key.
     */
    public static long millisForMinute(String dayKey, int minuteOfDay) {
        Calendar c = parseDay(dayKey);
        c.add(Calendar.MINUTE, minuteOfDay);
        return c.getTimeInMillis();
    }

    /**
     * Next future occurrence of {@code minuteOfDay}, today or tomorrow.
     * Used for scheduling routine reminders.
     */
    public static long nextOccurrenceOfMinute(int minuteOfDay) {
        Calendar c = Calendar.getInstance();
        c.set(Calendar.HOUR_OF_DAY, minuteOfDay / 60);
        c.set(Calendar.MINUTE, minuteOfDay % 60);
        c.set(Calendar.SECOND, 0);
        c.set(Calendar.MILLISECOND, 0);
        long now = System.currentTimeMillis();
        if (c.getTimeInMillis() <= now) {
            c.add(Calendar.DAY_OF_YEAR, 1);
        }
        return c.getTimeInMillis();
    }

    // ------------------------------------------------------------------
    //  Formatting
    // ------------------------------------------------------------------

    /** {@code mm:ss}, or {@code h:mm:ss} past an hour. Used by all timers. */
    public static String formatDuration(long millis) {
        if (millis < 0) millis = 0;
        long totalSec = millis / 1000;
        long h = totalSec / 3600;
        long m = (totalSec % 3600) / 60;
        long s = totalSec % 60;
        if (h > 0) {
            return String.format(Locale.US, "%d:%02d:%02d", h, m, s);
        }
        return String.format(Locale.US, "%02d:%02d", m, s);
    }

    /** Compact {@code mm:ss} for a seconds count. */
    public static String formatSeconds(int totalSeconds) {
        return formatDuration(totalSeconds * 1000L);
    }

    /** Minutes rendered as "2h 15m" / "45m". */
    public static String formatMinutes(int minutes) {
        if (minutes < 0) minutes = 0;
        int h = minutes / 60;
        int m = minutes % 60;
        if (h > 0 && m > 0) return h + "h " + m + "m";
        if (h > 0) return h + "h";
        return m + "m";
    }

    /** Metres rendered as "1.6 km" / "800 m". */
    public static String formatDistance(int metres) {
        if (metres < 0) metres = 0;
        if (metres >= 1000) {
            return String.format(Locale.US, "%.1f km", metres / 1000.0);
        }
        return metres + " m";
    }

    /** Pace rendered as "5'20\"/km". */
    public static String formatPace(int secPerKm) {
        if (secPerKm <= 0) return "--";
        int m = secPerKm / 60;
        int s = secPerKm % 60;
        return String.format(Locale.US, "%d'%02d\"/km", m, s);
    }

    /** 12-hour clock label, e.g. "04:30 AM". */
    public static String formatClock12(int minuteOfDay) {
        int h24 = minuteOfDay / 60;
        int m = minuteOfDay % 60;
        String suffix = h24 < 12 ? "AM" : "PM";
        int h12 = h24 % 12;
        if (h12 == 0) h12 = 12;
        return String.format(Locale.US, "%02d:%02d %s", h12, m, suffix);
    }

    /** 24-hour clock label, e.g. "04:30". */
    public static String formatClock24(int minuteOfDay) {
        return String.format(Locale.US, "%02d:%02d", minuteOfDay / 60, minuteOfDay % 60);
    }

    /** Short weekday name for a day key, e.g. "Mon". */
    public static String shortWeekday(String dayKey) {
        Calendar c = parseDay(dayKey);
        return new SimpleDateFormat("EEE", Locale.getDefault()).format(c.getTime());
    }

    /** Short weekday initial, e.g. "M" — for the weekly habit calendar. */
    public static String weekdayInitial(String dayKey) {
        Calendar c = parseDay(dayKey);
        return new SimpleDateFormat("EEEEE", Locale.getDefault()).format(c.getTime());
    }

    /** "17 Nov" style label for a day key. */
    public static String shortDate(String dayKey) {
        Calendar c = parseDay(dayKey);
        return new SimpleDateFormat("d MMM", Locale.getDefault()).format(c.getTime());
    }

    /** True when the day key is today in the device timezone. */
    public static boolean isToday(String dayKey) {
        return today().equals(dayKey);
    }

    public static boolean isYesterday(String dayKey) {
        return dayOffsetFromToday(-1).equals(dayKey);
    }

    /**
     * A greeting bucket for the dashboard's motivational line, so that the copy
     * matches the time of day without needing a location or timezone lookup.
     */
    public static int dayPart() {
        int h = hourOfDayNow();
        if (h < 5) return DAY_PART_NIGHT;
        if (h < 12) return DAY_PART_MORNING;
        if (h < 17) return DAY_PART_AFTERNOON;
        if (h < 21) return DAY_PART_EVENING;
        return DAY_PART_NIGHT;
    }

    public static final int DAY_PART_MORNING = 0;
    public static final int DAY_PART_AFTERNOON = 1;
    public static final int DAY_PART_EVENING = 2;
    public static final int DAY_PART_NIGHT = 3;

    /** Milliseconds until local midnight — used to schedule the daily report. */
    public static long millisUntilMidnight() {
        Calendar c = Calendar.getInstance();
        c.set(Calendar.HOUR_OF_DAY, 23);
        c.set(Calendar.MINUTE, 59);
        c.set(Calendar.SECOND, 0);
        c.set(Calendar.MILLISECOND, 0);
        long target = c.getTimeInMillis() + MINUTE_MS;
        return Math.max(MINUTE_MS, target - System.currentTimeMillis());
    }

    /** UTC offset in millis, exposed for diagnostics only. */
    public static long timeZoneOffsetMillis() {
        return TimeZone.getDefault().getOffset(System.currentTimeMillis());
    }
}
