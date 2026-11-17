package com.focuslife.app.data.prefs;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.focuslife.app.util.TimeUtils;

/**
 * User settings and daily targets.
 *
 * <p>Two separate files on purpose:</p>
 * <ul>
 *   <li>{@code focuslife_prefs} — durable choices that should follow the user
 *       across a device transfer, and therefore participate in backup.</li>
 *   <li>{@code focuslife_state} — transient runtime state such as the active
 *       mode and the running timer, which must NOT be restored onto a new
 *       device (a restored "mode active" flag with no running service would be
 *       a stuck state).</li>
 * </ul>
 *
 * <p>A third file, {@code focuslife_unlock}, holds the emergency-unlock window.
 * It is excluded from backup so that a device transfer can never silently carry
 * over an active bypass.</p>
 */
public final class Prefs {

    private static final String FILE_PREFS = "focuslife_prefs";
    private static final String FILE_STATE = "focuslife_state";
    private static final String FILE_UNLOCK = "focuslife_unlock";

    // ---- Durable settings ----
    private static final String K_TARGET_STUDY_MIN = "target_study_min";
    private static final String K_TARGET_SESSIONS = "target_focus_sessions";
    private static final String K_TARGET_RUN_M = "target_run_m";
    private static final String K_TARGET_SCREEN_MIN = "target_screen_min";
    private static final String K_TARGET_WORKOUTS = "target_workouts";
    private static final String K_LANG = "language_override";
    private static final String K_CLOCK_24 = "clock_24h";
    private static final String K_ONBOARDED = "onboarding_complete";
    private static final String K_DEFAULT_FOCUS_MIN = "default_focus_min";
    private static final String K_DEFAULT_BREAK_MIN = "default_break_min";
    private static final String K_DEFAULT_ROUNDS = "default_rounds";
    private static final String K_DEEP_FOCUS_DEFAULT = "deep_focus_default";
    private static final String K_COOLDOWN_SEC = "block_cooldown_sec";
    private static final String K_LAST_QUOTE_INDEX = "last_quote_index";
    private static final String K_LAST_QUOTE_DAY = "last_quote_day";
    private static final String K_SCREEN_TIME_TIPS_SHOWN = "screen_tips_shown";
    private static final String K_STREAK_MIN_SCORE = "streak_min_score";

    // ---- Runtime state ----
    private static final String K_MODE = "active_mode";
    private static final String K_MODE_SINCE = "mode_since";
    private static final String K_REST_DAY_KEY = "rest_day_key";
    private static final String K_REST_DAY_ENABLED = "rest_day_enabled";
    private static final String K_LAST_USAGE_SNAPSHOT = "last_usage_snapshot_ms";
    private static final String K_LAST_REPORT_DAY = "last_report_day";
    private static final String K_LAST_LIMIT_WARN_DAY = "limit_warn_day";

    // ---- Running study timer ----
    // Stored as an epoch deadline rather than an elapsed count so a throttled or
    // killed process cannot drift, and so the timer survives a reboot honestly.
    private static final String K_STUDY_TIMER_SINCE = "study_timer_since";
    private static final String K_STUDY_TIMER_SUBJECT = "study_timer_subject";
    private static final String K_STUDY_TIMER_EXAM = "study_timer_exam";
    private static final String K_STUDY_TIMER_TOPIC = "study_timer_topic_id";

    /** Epoch millis of the armed routine alarm; 0 when none is pending. */
    private static final String K_NEXT_ROUTINE_ALARM = "next_routine_alarm_at";

    // ---- Emergency unlock ----
    private static final String K_UNLOCK_UNTIL = "unlock_until";
    private static final String K_UNLOCK_PACKAGE = "unlock_package";
    private static final String K_UNLOCK_DAY = "unlock_day";
    private static final String K_UNLOCK_COUNT = "unlock_count";
    private static final String K_LAST_UNLOCK_AT = "last_unlock_at";

    // ---- Defaults (also the values shown in Profile before any edit) ----
    public static final int DEFAULT_TARGET_STUDY_MIN = 360;   // 6 hours
    public static final int DEFAULT_TARGET_SESSIONS = 4;
    public static final int DEFAULT_TARGET_RUN_M = 1600;
    public static final int DEFAULT_TARGET_SCREEN_MIN = 180;  // 3 hours
    public static final int DEFAULT_TARGET_WORKOUTS = 3;
    public static final int DEFAULT_FOCUS_MIN = 25;
    public static final int DEFAULT_BREAK_MIN = 5;
    public static final int DEFAULT_ROUNDS = 4;
    public static final int DEFAULT_COOLDOWN_SEC = 20;
    public static final int DEFAULT_STREAK_MIN_SCORE = 55;

    public static final String LANG_SYSTEM = "system";
    public static final String LANG_EN = "en";
    public static final String LANG_HI = "hi";

    private Prefs() {
    }

    // ------------------------------------------------------------------
    //  Accessors
    // ------------------------------------------------------------------

    @NonNull
    private static SharedPreferences prefs(@NonNull Context c) {
        return c.getApplicationContext().getSharedPreferences(FILE_PREFS, Context.MODE_PRIVATE);
    }

    @NonNull
    private static SharedPreferences state(@NonNull Context c) {
        return c.getApplicationContext().getSharedPreferences(FILE_STATE, Context.MODE_PRIVATE);
    }

    @NonNull
    private static SharedPreferences unlock(@NonNull Context c) {
        return c.getApplicationContext().getSharedPreferences(FILE_UNLOCK, Context.MODE_PRIVATE);
    }

    // ------------------------------------------------------------------
    //  Daily targets
    // ------------------------------------------------------------------

    public static int targetStudyMinutes(@NonNull Context c) {
        return prefs(c).getInt(K_TARGET_STUDY_MIN, DEFAULT_TARGET_STUDY_MIN);
    }

    public static void setTargetStudyMinutes(@NonNull Context c, int minutes) {
        prefs(c).edit().putInt(K_TARGET_STUDY_MIN, clamp(minutes, 0, 1440)).apply();
    }

    public static int targetFocusSessions(@NonNull Context c) {
        return prefs(c).getInt(K_TARGET_SESSIONS, DEFAULT_TARGET_SESSIONS);
    }

    public static void setTargetFocusSessions(@NonNull Context c, int sessions) {
        prefs(c).edit().putInt(K_TARGET_SESSIONS, clamp(sessions, 0, 40)).apply();
    }

    public static int targetRunMetres(@NonNull Context c) {
        return prefs(c).getInt(K_TARGET_RUN_M, DEFAULT_TARGET_RUN_M);
    }

    public static void setTargetRunMetres(@NonNull Context c, int metres) {
        prefs(c).edit().putInt(K_TARGET_RUN_M, clamp(metres, 0, 100_000)).apply();
    }

    public static int targetScreenMinutes(@NonNull Context c) {
        return prefs(c).getInt(K_TARGET_SCREEN_MIN, DEFAULT_TARGET_SCREEN_MIN);
    }

    public static void setTargetScreenMinutes(@NonNull Context c, int minutes) {
        prefs(c).edit().putInt(K_TARGET_SCREEN_MIN, clamp(minutes, 0, 1440)).apply();
    }

    public static int targetWorkouts(@NonNull Context c) {
        return prefs(c).getInt(K_TARGET_WORKOUTS, DEFAULT_TARGET_WORKOUTS);
    }

    public static void setTargetWorkouts(@NonNull Context c, int count) {
        prefs(c).edit().putInt(K_TARGET_WORKOUTS, clamp(count, 0, 30)).apply();
    }

    public static int streakMinScore(@NonNull Context c) {
        return prefs(c).getInt(K_STREAK_MIN_SCORE, DEFAULT_STREAK_MIN_SCORE);
    }

    public static void setStreakMinScore(@NonNull Context c, int score) {
        prefs(c).edit().putInt(K_STREAK_MIN_SCORE, clamp(score, 0, 100)).apply();
    }

    // ------------------------------------------------------------------
    //  Timer defaults
    // ------------------------------------------------------------------

    public static int defaultFocusMinutes(@NonNull Context c) {
        return prefs(c).getInt(K_DEFAULT_FOCUS_MIN, DEFAULT_FOCUS_MIN);
    }

    public static void setDefaultFocusMinutes(@NonNull Context c, int minutes) {
        prefs(c).edit().putInt(K_DEFAULT_FOCUS_MIN, clamp(minutes, 1, 240)).apply();
    }

    public static int defaultBreakMinutes(@NonNull Context c) {
        return prefs(c).getInt(K_DEFAULT_BREAK_MIN, DEFAULT_BREAK_MIN);
    }

    public static void setDefaultBreakMinutes(@NonNull Context c, int minutes) {
        prefs(c).edit().putInt(K_DEFAULT_BREAK_MIN, clamp(minutes, 0, 60)).apply();
    }

    public static int defaultRounds(@NonNull Context c) {
        return prefs(c).getInt(K_DEFAULT_ROUNDS, DEFAULT_ROUNDS);
    }

    public static void setDefaultRounds(@NonNull Context c, int rounds) {
        prefs(c).edit().putInt(K_DEFAULT_ROUNDS, clamp(rounds, 1, 12)).apply();
    }

    public static boolean deepFocusByDefault(@NonNull Context c) {
        return prefs(c).getBoolean(K_DEEP_FOCUS_DEFAULT, true);
    }

    public static void setDeepFocusByDefault(@NonNull Context c, boolean on) {
        prefs(c).edit().putBoolean(K_DEEP_FOCUS_DEFAULT, on).apply();
    }

    /** Seconds the block warning holds before offering a bypass. */
    public static int blockCooldownSeconds(@NonNull Context c) {
        return prefs(c).getInt(K_COOLDOWN_SEC, DEFAULT_COOLDOWN_SEC);
    }

    public static void setBlockCooldownSeconds(@NonNull Context c, int seconds) {
        prefs(c).edit().putInt(K_COOLDOWN_SEC, clamp(seconds, 0, 120)).apply();
    }

    // ------------------------------------------------------------------
    //  Running study timer
    // ------------------------------------------------------------------

    /**
     * Epoch millis at which the study timer started, or 0 when it is not running.
     *
     * <p>Lives in the excluded state file: a "timer running" flag restored onto a
     * new device would credit study minutes that were never studied.</p>
     */
    public static long studyTimerStartedAt(@NonNull Context c) {
        return state(c).getLong(K_STUDY_TIMER_SINCE, 0L);
    }

    public static void setStudyTimerStartedAt(@NonNull Context c, long millis) {
        state(c).edit().putLong(K_STUDY_TIMER_SINCE, millis).apply();
    }

    @NonNull
    public static String studyTimerSubject(@NonNull Context c) {
        return state(c).getString(K_STUDY_TIMER_SUBJECT, "");
    }

    @NonNull
    public static String studyTimerExam(@NonNull Context c) {
        return state(c).getString(K_STUDY_TIMER_EXAM, "");
    }

    public static long studyTimerTopicId(@NonNull Context c) {
        return state(c).getLong(K_STUDY_TIMER_TOPIC, 0L);
    }

    /** Writes or clears the whole timer record in one atomic edit. */
    public static void setStudyTimer(@NonNull Context c, long startedAt, @NonNull String subject,
                                     @NonNull String exam, long topicId) {
        state(c).edit()
                .putLong(K_STUDY_TIMER_SINCE, startedAt)
                .putString(K_STUDY_TIMER_SUBJECT, subject)
                .putString(K_STUDY_TIMER_EXAM, exam)
                .putLong(K_STUDY_TIMER_TOPIC, topicId)
                .apply();
    }

    public static void clearStudyTimer(@NonNull Context c) {
        state(c).edit()
                .remove(K_STUDY_TIMER_SINCE)
                .remove(K_STUDY_TIMER_SUBJECT)
                .remove(K_STUDY_TIMER_EXAM)
                .remove(K_STUDY_TIMER_TOPIC)
                .apply();
    }

    public static boolean isStudyTimerRunning(@NonNull Context c) {
        return studyTimerStartedAt(c) > 0L;
    }

    /**
     * When the next routine reminder is armed, or 0 when nothing is pending.
     *
     * <p>Diagnostic only — the alarm itself is owned by AlarmManager. Recording
     * the instant lets the routine screen tell the user whether a reminder is
     * queued rather than leaving them to guess.</p>
     */
    public static long nextRoutineAlarmAt(@NonNull Context c) {
        return state(c).getLong(K_NEXT_ROUTINE_ALARM, 0L);
    }

    public static void setNextRoutineAlarmAt(@NonNull Context c, long millis) {
        state(c).edit().putLong(K_NEXT_ROUTINE_ALARM, millis).apply();
    }

    // ------------------------------------------------------------------
    //  Language and clock
    // ------------------------------------------------------------------

    @NonNull
    public static String languageOverride(@NonNull Context c) {
        return prefs(c).getString(K_LANG, LANG_SYSTEM) == null
                ? LANG_SYSTEM
                : prefs(c).getString(K_LANG, LANG_SYSTEM);
    }

    public static void setLanguageOverride(@NonNull Context c, @NonNull String lang) {
        prefs(c).edit().putString(K_LANG, lang).apply();
    }

    public static boolean use24HourClock(@NonNull Context c) {
        return prefs(c).getBoolean(K_CLOCK_24, false);
    }

    public static void setUse24HourClock(@NonNull Context c, boolean on) {
        prefs(c).edit().putBoolean(K_CLOCK_24, on).apply();
    }

    // ------------------------------------------------------------------
    //  Onboarding / misc UI state
    // ------------------------------------------------------------------

    public static boolean onboardingComplete(@NonNull Context c) {
        return prefs(c).getBoolean(K_ONBOARDED, false);
    }

    public static void setOnboardingComplete(@NonNull Context c, boolean done) {
        prefs(c).edit().putBoolean(K_ONBOARDED, done).apply();
    }

    /**
     * The quote index the user advanced to, but only if it was chosen today.
     *
     * <p>Stored alongside a day key so a pick made yesterday cannot override
     * today's deterministic rotation. Returns -1 when nothing was chosen today,
     * meaning "use the day's own quote".</p>
     */
    public static int lastQuoteIndex(@NonNull Context c) {
        String day = prefs(c).getString(K_LAST_QUOTE_DAY, null);
        if (day == null || !day.equals(TimeUtils.today())) return -1;
        return prefs(c).getInt(K_LAST_QUOTE_INDEX, -1);
    }

    /** Records an advanced quote pick for today. */
    public static void setLastQuoteIndex(@NonNull Context c, int index) {
        prefs(c).edit()
                .putInt(K_LAST_QUOTE_INDEX, index)
                .putString(K_LAST_QUOTE_DAY, TimeUtils.today())
                .apply();
    }

    public static boolean screenTipsShown(@NonNull Context c) {
        return prefs(c).getBoolean(K_SCREEN_TIME_TIPS_SHOWN, false);
    }

    public static void setScreenTipsShown(@NonNull Context c, boolean shown) {
        prefs(c).edit().putBoolean(K_SCREEN_TIME_TIPS_SHOWN, shown).apply();
    }

    // ------------------------------------------------------------------
    //  Active mode (runtime state, not backed up)
    // ------------------------------------------------------------------

    @Nullable
    public static String activeMode(@NonNull Context c) {
        return state(c).getString(K_MODE, null);
    }

    public static void setActiveMode(@NonNull Context c, @Nullable String mode) {
        SharedPreferences.Editor e = state(c).edit();
        if (mode == null) {
            e.remove(K_MODE).remove(K_MODE_SINCE);
        } else {
            e.putString(K_MODE, mode).putLong(K_MODE_SINCE, System.currentTimeMillis());
        }
        e.apply();
    }

    public static long modeStartedAt(@NonNull Context c) {
        return state(c).getLong(K_MODE_SINCE, 0L);
    }

    // ------------------------------------------------------------------
    //  Rest day
    // ------------------------------------------------------------------

    /** True when today is marked as a planned rest day. */
    public static boolean isRestDayToday(@NonNull Context c) {
        SharedPreferences s = state(c);
        return s.getBoolean(K_REST_DAY_ENABLED, false)
                && TimeUtils.today().equals(s.getString(K_REST_DAY_KEY, ""));
    }

    public static void setRestDayToday(@NonNull Context c, boolean rest) {
        state(c).edit()
                .putBoolean(K_REST_DAY_ENABLED, rest)
                .putString(K_REST_DAY_KEY, TimeUtils.today())
                .apply();
    }

    // ------------------------------------------------------------------
    //  Worker bookkeeping
    // ------------------------------------------------------------------

    public static long lastUsageSnapshotAt(@NonNull Context c) {
        return state(c).getLong(K_LAST_USAGE_SNAPSHOT, 0L);
    }

    public static void setLastUsageSnapshotAt(@NonNull Context c, long millis) {
        state(c).edit().putLong(K_LAST_USAGE_SNAPSHOT, millis).apply();
    }

    @Nullable
    public static String lastReportDay(@NonNull Context c) {
        return state(c).getString(K_LAST_REPORT_DAY, null);
    }

    public static void setLastReportDay(@NonNull Context c, @NonNull String dayKey) {
        state(c).edit().putString(K_LAST_REPORT_DAY, dayKey).apply();
    }

    /**
     * Per-app-per-day de-duplication for limit notifications, so that hitting a
     * ceiling once does not produce a notification every time the app is opened.
     */
    public static boolean alreadyWarnedLimit(@NonNull Context c, @NonNull String pkg, @NonNull String dayKey) {
        String stored = state(c).getString(K_LAST_LIMIT_WARN_DAY, "");
        return stored != null && stored.equals(pkg + "|" + dayKey);
    }

    public static void markLimitWarned(@NonNull Context c, @NonNull String pkg, @NonNull String dayKey) {
        state(c).edit().putString(K_LAST_LIMIT_WARN_DAY, pkg + "|" + dayKey).apply();
    }

    // ------------------------------------------------------------------
    //  Emergency unlock window
    // ------------------------------------------------------------------

    /** Epoch millis until which a specific package is temporarily allowed. */
    public static long unlockUntil(@NonNull Context c) {
        return unlock(c).getLong(K_UNLOCK_UNTIL, 0L);
    }

    @Nullable
    public static String unlockPackage(@NonNull Context c) {
        return unlock(c).getString(K_UNLOCK_PACKAGE, null);
    }

    public static void grantUnlock(@NonNull Context c, @NonNull String pkg, long untilMillis) {
        String today = TimeUtils.today();
        SharedPreferences s = unlock(c);
        SharedPreferences.Editor e = s.edit()
                .putString(K_UNLOCK_PACKAGE, pkg)
                .putLong(K_UNLOCK_UNTIL, untilMillis)
                .putLong(K_LAST_UNLOCK_AT, System.currentTimeMillis());
        // Count per day so "used N times today" is honest.
        if (today.equals(s.getString(K_UNLOCK_DAY, ""))) {
            e.putInt(K_UNLOCK_COUNT, s.getInt(K_UNLOCK_COUNT, 0) + 1);
        } else {
            e.putString(K_UNLOCK_DAY, today).putInt(K_UNLOCK_COUNT, 1);
        }
        e.apply();
    }

    public static void revokeUnlock(@NonNull Context c) {
        unlock(c).edit().remove(K_UNLOCK_UNTIL).remove(K_UNLOCK_PACKAGE).apply();
    }

    /** True when {@code pkg} is currently inside a granted emergency window. */
    public static boolean isUnlocked(@NonNull Context c, @NonNull String pkg) {
        SharedPreferences s = unlock(c);
        long until = s.getLong(K_UNLOCK_UNTIL, 0L);
        String stored = s.getString(K_UNLOCK_PACKAGE, null);
        return until > System.currentTimeMillis() && pkg.equals(stored);
    }

    /** Any package unlocked right now, regardless of which one is asked about. */
    public static boolean isAnyUnlocked(@NonNull Context c) {
        return unlock(c).getLong(K_UNLOCK_UNTIL, 0L) > System.currentTimeMillis();
    }

    public static long unlockRemainingMillis(@NonNull Context c) {
        return Math.max(0L, unlock(c).getLong(K_UNLOCK_UNTIL, 0L) - System.currentTimeMillis());
    }

    public static int unlockCountToday(@NonNull Context c) {
        SharedPreferences s = unlock(c);
        if (!TimeUtils.today().equals(s.getString(K_UNLOCK_DAY, ""))) {
            return 0;
        }
        return s.getInt(K_UNLOCK_COUNT, 0);
    }

    public static long lastUnlockAt(@NonNull Context c) {
        return unlock(c).getLong(K_LAST_UNLOCK_AT, 0L);
    }

    /** Clears every emergency unlock record. Used by "erase all data". */
    public static void clearUnlockHistory(@NonNull Context c) {
        unlock(c).edit().clear().apply();
    }

    // ------------------------------------------------------------------

    private static int clamp(int v, int lo, int hi) {
        return v < lo ? lo : Math.min(v, hi);
    }
}
