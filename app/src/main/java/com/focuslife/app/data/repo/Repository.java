package com.focuslife.app.data.repo;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.WorkerThread;

import com.focuslife.app.R;
import com.focuslife.app.blocking.BlockDecisionEngine;
import com.focuslife.app.blocking.BlockState;
import com.focuslife.app.data.db.AppDatabase;
import com.focuslife.app.data.db.dao.FocusSessionDao;
import com.focuslife.app.data.db.entity.AppUsageSnapshotEntity;
import com.focuslife.app.data.db.entity.BlockedAppEntity;
import com.focuslife.app.data.db.entity.DailySummaryEntity;
import com.focuslife.app.data.db.entity.FocusSessionEntity;
import com.focuslife.app.data.db.entity.HabitEntity;
import com.focuslife.app.data.db.entity.HabitLogEntity;
import com.focuslife.app.data.prefs.Prefs;
import com.focuslife.app.stats.FocusScoreCalculator;
import com.focuslife.app.stats.UsageStatsHelper;
import com.focuslife.app.util.PermissionManager;
import com.focuslife.app.util.TimeUtils;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Assembles everything a screen needs from the local database, preferences and
 * the usage-stats API.
 *
 * <p>All blocking reads are {@code @WorkerThread}: Room is configured without
 * {@code allowMainThreadQueries} for the persisted database, so a main-thread
 * call would throw. Every caller goes through {@link AppDatabase#io()}.</p>
 */
public final class Repository {

    private static volatile Repository instance;

    @NonNull
    public static Repository get(@NonNull Context context) {
        Repository local = instance;
        if (local == null) {
            synchronized (Repository.class) {
                local = instance;
                if (local == null) {
                    local = new Repository(context.getApplicationContext());
                    instance = local;
                }
            }
        }
        return local;
    }

    @NonNull
    private final Context context;

    @NonNull
    private final AppDatabase db;

    private Repository(@NonNull Context context) {
        this.context = context;
        this.db = AppDatabase.get(context);
    }

    @NonNull
    public AppDatabase db() {
        return db;
    }

    @NonNull
    public Context context() {
        return context;
    }

    // ==================================================================
    //  Dashboard
    // ==================================================================

    /** Everything the home screen renders, computed in one pass. */
    public static final class DashboardData {
        @NonNull
        public String dayKey = "";
        public FocusScoreCalculator.Result score = null;
        public int progressPercent;
        public int streak;
        public int bestStreak;

        public int studyMinutes;
        public int studyTargetMinutes;

        public int focusMinutes;
        public int focusSessionsDone;
        public int focusSessionTarget;
        public int blockHits;

        public int tasksDone;
        public int tasksTotal;
        public int tasksRemaining;
        public int remainingMinutes;

        public int habitsDone;
        public int habitsTotal;

        public int runDistanceM;
        public int runTargetM;
        public int runMinutes;
        public int workoutsDone;
        public int workoutTarget;
        public boolean restDay;

        public int screenMinutes = -1;
        public int screenTargetMinutes;
        public boolean screenTimeAvailable;
        public int screenDeltaVsYesterday;

        public int urgesResisted;
        public int urgesTotal;
        public int distractionMinutesLost;

        /** Day keys of the last seven days, oldest first, for the charts. */
        @NonNull
        public List<String> weekKeys = new ArrayList<>();
        /** Study minutes per day over {@link #weekKeys}; 0 where no data. */
        @NonNull
        public int[] weekStudyMinutes = new int[0];
        /** Focus minutes per day over {@link #weekKeys}. */
        @NonNull
        public int[] weekFocusMinutes = new int[0];
        /** Habit completion percentage per day over {@link #weekKeys}. */
        @NonNull
        public int[] weekHabitPercent = new int[0];
        /** Screen minutes per day over {@link #weekKeys}; -1 where unavailable. */
        @NonNull
        public int[] weekScreenMinutes = new int[0];

        @Nullable
        public String nextRoutineTitle;
        public int nextRoutineMinute = -1;

        @NonNull
        public String quoteKey = "";
        public int quoteIndex;

        /** Whether a mode is running, and which. */
        @Nullable
        public String activeMode;
        @NonNull
        public String activeModeElapsed = "";
        public int blockedAppCount;
        public int enforcementLevel;
    }

    /**
     * Builds the dashboard payload.
     *
     * <p>Screen time is read from the local snapshot table when one exists for
     * today, and from {@code UsageStatsManager} otherwise. Reading the system
     * API on every dashboard open is too slow for a low-end device, so the
     * snapshot is the normal path and {@code UsageSnapshotWorker} keeps it
     * current.</p>
     */
    @WorkerThread
    @NonNull
    public DashboardData loadDashboard() {
        DashboardData d = new DashboardData();
        String today = TimeUtils.today();
        d.dayKey = today;

        // ---- Study ----
        d.studyMinutes = db.studyDao().minutesOnDay(today);
        d.studyTargetMinutes = Prefs.targetStudyMinutes(context);

        // ---- Focus sessions ----
        int focusedSec = db.focusSessionDao().focusedSecOnDay(today);
        d.focusMinutes = focusedSec / 60;
        d.focusSessionsDone = db.focusSessionDao().completedCountOnDay(today);
        d.focusSessionTarget = Prefs.targetFocusSessions(context);
        d.blockHits = db.focusSessionDao().blockHitsOnDay(today);

        // ---- Tasks ----
        d.tasksTotal = db.taskDao().totalOnDay(today);
        d.tasksDone = db.taskDao().doneOnDay(today);
        d.tasksRemaining = Math.max(0, d.tasksTotal - d.tasksDone);
        d.remainingMinutes = db.taskDao().remainingMinutes(today);

        // ---- Habits ----
        d.habitsTotal = db.habitDao().activeHabits().size();
        d.habitsDone = db.habitDao().doneCountOnDay(today);

        // ---- Fitness ----
        d.runDistanceM = db.fitnessDao().distanceOnDay(today);
        d.runMinutes = db.fitnessDao().runSecondsOnDay(today) / 60;
        d.runTargetM = Prefs.targetRunMetres(context);
        d.workoutsDone = db.fitnessDao().workoutsDoneOnDay(today);
        d.workoutTarget = Prefs.targetWorkouts(context);
        d.restDay = Prefs.isRestDayToday(context);

        // ---- Screen time ----
        d.screenTargetMinutes = Prefs.targetScreenMinutes(context);
        d.screenTimeAvailable = PermissionManager.hasUsageAccess(context);
        d.screenMinutes = screenMinutesForDay(today);
        d.screenDeltaVsYesterday = computeScreenDelta(today);

        // ---- Distraction diary ----
        String weekStart = TimeUtils.dayOffsetFromToday(-6);
        d.urgesTotal = db.controlDao().totalUrgeCount(weekStart, today);
        d.urgesResisted = db.controlDao().resistedCount(weekStart, today);
        d.distractionMinutesLost = db.controlDao().minutesLostOnDay(today);

        // ---- Score ----
        FocusScoreCalculator.Day input = toScoreInput(d);
        d.score = FocusScoreCalculator.compute(input);
        d.progressPercent = FocusScoreCalculator.progressPercent(d.score);

        // ---- Streak ----
        int threshold = Prefs.streakMinScore(context);
        List<String> qualifying = db.usageDao().daysWithScoreAtLeast(threshold);
        // Today may not have a summary row yet; fold the live score in so the
        // streak updates as the day progresses instead of only at midnight.
        if (d.progressPercent >= threshold && !qualifying.contains(today)) {
            List<String> merged = new ArrayList<>(qualifying.size() + 1);
            merged.add(today);
            merged.addAll(qualifying);
            qualifying = merged;
        }
        d.streak = FocusScoreCalculator.currentStreak(qualifying, today);
        d.bestStreak = FocusScoreCalculator.bestStreak(qualifying);

        // ---- Weekly series ----
        d.weekKeys = TimeUtils.lastSevenDayKeys();
        d.weekStudyMinutes = fillSeries(db.studyDao().minutesPerDay(weekStart, today), d.weekKeys);
        d.weekFocusMinutes = fillMinutesFromSeconds(
                db.focusSessionDao().focusMinutesPerDay(weekStart, today), d.weekKeys);
        d.weekHabitPercent = habitPercentSeries(weekStart, today, d.weekKeys, d.habitsTotal);
        d.weekScreenMinutes = screenSeries(weekStart, d.weekKeys);

        // ---- Next routine slot ----
        int minuteNow = TimeUtils.minuteOfDayNow();
        com.focuslife.app.data.db.entity.RoutineSlotEntity next = db.routineDao().nextSlot(minuteNow);
        if (next != null) {
            d.nextRoutineTitle = resolveString(next.titleRes, next.title);
            d.nextRoutineMinute = next.minuteOfDay;
        }

        // ---- Quote of the day ----
        int quoteCount = QUOTE_RES.length;
        int index = quoteIndexForDay(today, quoteCount);
        d.quoteIndex = index;
        d.quoteKey = String.valueOf(QUOTE_RES[index]);

        // ---- Mode / blocking ----
        d.activeMode = com.focuslife.app.blocking.ModeController.activeMode(context);
        if (d.activeMode != null) {
            d.activeModeElapsed = com.focuslife.app.blocking.ModeController.elapsedLabel(context);
        } else {
            d.activeModeElapsed = "";
        }
        d.blockedAppCount = db.blockerDao().enabledCount();
        d.enforcementLevel = com.focuslife.app.blocking.ModeController.enforcementLevel(context);

        return d;
    }

    /** Fills the {@link FocusScoreCalculator.Day} input from assembled data. */
    @NonNull
    private static FocusScoreCalculator.Day toScoreInput(@NonNull DashboardData d) {
        FocusScoreCalculator.Day in = new FocusScoreCalculator.Day();
        in.studyMinutes = d.studyMinutes;
        in.studyTargetMinutes = d.studyTargetMinutes;
        in.focusSessionsCompleted = d.focusSessionsDone;
        in.focusSessionTarget = d.focusSessionTarget;
        in.focusedSeconds = d.focusMinutes * 60;
        in.habitsDone = d.habitsDone;
        in.habitsTotal = d.habitsTotal;
        in.tasksDone = d.tasksDone;
        in.tasksTotal = d.tasksTotal;
        in.runDistanceM = d.runDistanceM;
        in.runTargetM = d.runTargetM;
        in.workoutsDone = d.workoutsDone;
        in.workoutTarget = d.workoutTarget;
        in.restDay = d.restDay;
        in.screenMinutes = Math.max(0, d.screenMinutes);
        in.screenLimitMinutes = d.screenTargetMinutes;
        // When usage access is missing, screenMinutes is -1 and meaningless;
        // telling the calculator it is unknown keeps that component out of the
        // denominator instead of awarding free points.
        in.screenTimeUnknown = !d.screenTimeAvailable || d.screenMinutes < 0;
        in.blockHits = d.blockHits;
        in.urgesResisted = d.urgesResisted;
        in.urgesTotal = d.urgesTotal;
        in.distractionMinutesLost = d.distractionMinutesLost;
        return in;
    }

    // ==================================================================
    //  Screen time helpers
    // ==================================================================

    /**
     * Screen minutes for a day, preferring the local snapshot and falling back
     * to a live system query only for today.
     *
     * @return -1 when usage access is unavailable.
     */
    @WorkerThread
    public int screenMinutesForDay(@NonNull String dayKey) {
        if (!PermissionManager.hasUsageAccess(context)) return -1;

        int fromSnapshot = db.usageDao().totalMinutesOnDay(dayKey);
        if (fromSnapshot > 0) return fromSnapshot;

        // No snapshot yet: measure live. Only worth doing for today, since older
        // days are the snapshot worker's responsibility.
        if (TimeUtils.isToday(dayKey)) {
            int live = UsageStatsHelper.totalMinutesForDay(context, dayKey);
            return live < 0 ? 0 : live;
        }
        return 0;
    }

    @WorkerThread
    private int computeScreenDelta(@NonNull String today) {
        int now = screenMinutesForDay(today);
        int yesterday = screenMinutesForDay(TimeUtils.dayOffsetFromToday(-1));
        if (now < 0 || yesterday < 0) return 0;
        return now - yesterday;
    }

    /**
     * Refreshes today's per-app snapshot from the system API into Room.
     *
     * @return number of rows written, or -1 when usage access is unavailable.
     */
    @WorkerThread
    public int refreshUsageSnapshot(@NonNull String dayKey) {
        if (!PermissionManager.hasUsageAccess(context)) return -1;

        Set<String> blocked = new HashSet<>(db.blockerDao().enabledPackageNames());
        List<AppUsageSnapshotEntity> rows =
                UsageStatsHelper.snapshotForDay(context, dayKey, blocked);
        if (rows == null) return -1;

        db.usageDao().deleteDay(dayKey);
        if (!rows.isEmpty()) {
            db.usageDao().insertSnapshots(rows);
        }
        Prefs.setLastUsageSnapshotAt(context, System.currentTimeMillis());

        // Sync each blocked app's "used today" counter, which is what makes the
        // daily-limit enforcement in BlockDecisionEngine work.
        for (AppUsageSnapshotEntity row : rows) {
            if (blocked.contains(row.packageName)) {
                db.blockerDao().setUsedToday(row.packageName, row.minutes, dayKey);
            }
        }
        db.blockerDao().resetStaleUsage(dayKey);
        BlockState.get().refreshAsync(context);
        return rows.size();
    }

    @WorkerThread
    @NonNull
    public List<AppUsageSnapshotEntity> topApps(@NonNull String dayKey, int limit) {
        return db.usageDao().topApps(dayKey, limit);
    }

    // ==================================================================
    //  Series helpers
    // ==================================================================

    /**
     * Expands a sparse (dayKey → value) result into a dense array aligned with
     * {@code keys}, so charts always draw seven bars even when data is missing.
     */
    @NonNull
    private static int[] fillSeries(@Nullable List<FocusSessionDao.ChartPoint> points,
                                    @NonNull List<String> keys) {
        int[] out = new int[keys.size()];
        if (points == null || points.isEmpty()) return out;

        Map<String, Integer> byKey = new HashMap<>();
        for (FocusSessionDao.ChartPoint p : points) {
            if (p == null || p.label == null) continue;
            byKey.put(p.label, p.value);
        }
        for (int i = 0; i < keys.size(); i++) {
            Integer v = byKey.get(keys.get(i));
            out[i] = v == null ? 0 : v;
        }
        return out;
    }

    @NonNull
    private static int[] fillMinutesFromSeconds(@Nullable List<FocusSessionDao.ChartPoint> points,
                                                @NonNull List<String> keys) {
        int[] raw = fillSeries(points, keys);
        for (int i = 0; i < raw.length; i++) {
            raw[i] = raw[i] / 60;
        }
        return raw;
    }

    @NonNull
    private int[] habitPercentSeries(@NonNull String from, @NonNull String to,
                                     @NonNull List<String> keys, int habitsTotalToday) {
        int[] out = new int[keys.size()];
        int denominator = Math.max(1, habitsTotalToday);

        // Habit count changes over time, so use the count that was active on
        // each day where possible; fall back to today's count.
        Map<String, Integer> donePerDay = new HashMap<>();
        List<FocusSessionDao.ChartPoint> points = db.habitDao().completionsPerDay(from, to);
        if (points != null) {
            for (FocusSessionDao.ChartPoint p : points) {
                if (p != null && p.label != null) donePerDay.put(p.label, p.value);
            }
        }

        for (int i = 0; i < keys.size(); i++) {
            String key = keys.get(i);
            Integer done = donePerDay.get(key);
            int count = done == null ? 0 : done;
            out[i] = FocusScoreCalculator.clamp(
                    (int) Math.round(count * 100.0 / denominator), 0, 100);
        }
        return out;
    }

    @NonNull
    private int[] screenSeries(@NonNull String from, @NonNull List<String> keys) {
        int[] out = new int[keys.size()];
        if (!PermissionManager.hasUsageAccess(context)) {
            java.util.Arrays.fill(out, -1);
            return out;
        }
        Map<String, Integer> byKey = new HashMap<>();
        List<FocusSessionDao.ChartPoint> points = db.usageDao().minutesPerDay(from, TimeUtils.today());
        if (points != null) {
            for (FocusSessionDao.ChartPoint p : points) {
                if (p != null && p.label != null) byKey.put(p.label, p.value);
            }
        }
        for (int i = 0; i < keys.size(); i++) {
            String key = keys.get(i);
            Integer v = byKey.get(key);
            if (v == null) {
                out[i] = TimeUtils.isToday(key) ? Math.max(0, screenMinutesForDay(key)) : 0;
            } else {
                out[i] = v;
            }
        }
        return out;
    }

    // ==================================================================
    //  Quotes
    // ==================================================================

    /** The twenty quotes required by the brief, as resource ids. */
    public static final int[] QUOTE_RES = {
            R.string.quote_1, R.string.quote_2, R.string.quote_3, R.string.quote_4,
            R.string.quote_5, R.string.quote_6, R.string.quote_7, R.string.quote_8,
            R.string.quote_9, R.string.quote_10, R.string.quote_11, R.string.quote_12,
            R.string.quote_13, R.string.quote_14, R.string.quote_15, R.string.quote_16,
            R.string.quote_17, R.string.quote_18, R.string.quote_19, R.string.quote_20
    };

    /**
     * Deterministic quote for a day key.
     *
     * <p>Derived from the day rather than randomised, so the same day always
     * shows the same quote even after the app restarts. That matters: a quote
     * that changes on every launch feels like noise, while one that stays put
     * for the day can actually be remembered.</p>
     */
    public static int quoteIndexForDay(@NonNull String dayKey, int count) {
        if (count <= 0) return 0;
        int hash = 0;
        for (int i = 0; i < dayKey.length(); i++) {
            hash = (hash * 31 + dayKey.charAt(i)) & 0x7fffffff;
        }
        return hash % count;
    }

    public static int quoteIndexForToday() {
        return quoteIndexForDay(TimeUtils.today(), QUOTE_RES.length);
    }

    /** The next quote, for the "another quote" action. */
    public static int nextQuoteIndex(int current) {
        return (current + 1) % QUOTE_RES.length;
    }

    // ==================================================================
    //  Habits
    // ==================================================================

    /** A habit plus whether it is done on a given day. */
    public static final class HabitRow {
        @NonNull
        public final HabitEntity habit;
        public final boolean done;
        public final int streak;
        public final int totalCompletions;
        /** Resolved display name, locale-correct. */
        @NonNull
        public final String displayName;

        HabitRow(@NonNull HabitEntity habit, boolean done, int streak, int totalCompletions,
                 @NonNull String displayName) {
            this.habit = habit;
            this.done = done;
            this.streak = streak;
            this.totalCompletions = totalCompletions;
            this.displayName = displayName;
        }
    }

    @WorkerThread
    @NonNull
    public List<HabitRow> loadHabitRows(@NonNull String dayKey) {
        List<HabitEntity> habits = db.habitDao().activeHabits();
        Set<Long> done = new HashSet<>(db.habitDao().habitIdsDoneOnDay(dayKey));
        List<HabitRow> rows = new ArrayList<>(habits.size());

        for (HabitEntity h : habits) {
            List<String> days = db.habitDao().completionDaysForHabit(h.id);
            int streak = FocusScoreCalculator.habitStreak(days, TimeUtils.today());
            rows.add(new HabitRow(
                    h,
                    done.contains(h.id),
                    streak,
                    days.size(),
                    resolveString(h.nameRes, h.name)));
        }
        return rows;
    }

    /** Toggles a habit and returns the new state. */
    @WorkerThread
    public boolean toggleHabit(long habitId, @NonNull String dayKey) {
        boolean nowDone = db.habitDao().toggle(habitId, dayKey, System.currentTimeMillis());
        if (nowDone) {
            // A completed habit may have pushed the score over the streak
            // threshold, so re-summarise the day in the background.
            scheduleSummaryRefresh(dayKey);
        }
        return nowDone;
    }

    /** Seven-day completion map for the weekly calendar: dayKey → done count. */
    @WorkerThread
    @NonNull
    public Map<String, Integer> weekHabitCompletions() {
        String from = TimeUtils.dayOffsetFromToday(-6);
        String to = TimeUtils.today();
        List<HabitLogEntity> logs = db.habitDao().logsBetween(from, to);
        Map<String, Integer> out = new HashMap<>();
        for (HabitLogEntity l : logs) {
            if (l == null || l.dayKey == null) continue;
            Integer c = out.get(l.dayKey);
            out.put(l.dayKey, c == null ? 1 : c + 1);
        }
        return out;
    }

    /**
     * Missed-habit analysis: per-habit completion percentage over the last
     * {@code days} days, sorted weakest first.
     */
    @WorkerThread
    @NonNull
    public List<HabitConsistency> habitConsistency(int days) {
        String from = TimeUtils.dayOffsetFromToday(-(days - 1));
        String to = TimeUtils.today();
        List<HabitEntity> habits = db.habitDao().activeHabits();
        List<HabitConsistency> out = new ArrayList<>(habits.size());

        for (HabitEntity h : habits) {
            List<String> completedDays = new ArrayList<>();
            for (String day : db.habitDao().completionDaysForHabit(h.id)) {
                if (day != null && day.compareTo(from) >= 0 && day.compareTo(to) <= 0) {
                    completedDays.add(day);
                }
            }
            int pct = days <= 0 ? 0
                    : FocusScoreCalculator.clamp(
                            (int) Math.round(completedDays.size() * 100.0 / days), 0, 100);
            out.add(new HabitConsistency(h.id, resolveString(h.nameRes, h.name), pct,
                    completedDays.size(), days));
        }

        // Weakest first: the list is meant to show where support is needed, not
        // to rank the user.
        out.sort((a, b) -> Integer.compare(a.percent, b.percent));
        return out;
    }

    public static final class HabitConsistency {
        public final long habitId;
        @NonNull
        public final String name;
        public final int percent;
        public final int doneDays;
        public final int totalDays;

        HabitConsistency(long habitId, @NonNull String name, int percent, int doneDays, int totalDays) {
            this.habitId = habitId;
            this.name = name;
            this.percent = percent;
            this.doneDays = doneDays;
            this.totalDays = totalDays;
        }
    }

    // ==================================================================
    //  Blocker
    // ==================================================================

    @WorkerThread
    @NonNull
    public List<BlockedAppEntity> loadBlockedApps() {
        return db.blockerDao().all();
    }

    @WorkerThread
    public void addBlockedApp(@NonNull String pkg, @NonNull String label) {
        BlockedAppEntity existing = db.blockerDao().find(pkg);
        if (existing != null) {
            existing.enabled = true;
            existing.appLabel = label;
            db.blockerDao().updateApp(existing);
        } else {
            BlockedAppEntity app = new BlockedAppEntity(pkg, label);
            app.addedAt = System.currentTimeMillis();
            // Non-strict by default. Making every app un-bypassable on the first
            // tap is the fastest way to get the whole system switched off.
            app.strict = false;
            db.blockerDao().insertApp(app);
        }
        BlockState.get().refreshAsync(context);
    }

    @WorkerThread
    public void removeBlockedApp(@NonNull String pkg) {
        db.blockerDao().removeApp(pkg);
        BlockState.get().refreshAsync(context);
    }

    @WorkerThread
    public void updateBlockedApp(@NonNull BlockedAppEntity app) {
        db.blockerDao().updateApp(app);
        BlockState.get().refreshAsync(context);
    }

    /**
     * Apps that are over their daily ceiling right now — used to show an
     * immediate, actionable list on the blocker screen.
     */
    @WorkerThread
    @NonNull
    public List<BlockedAppEntity> appsOverLimitToday() {
        return db.blockerDao().overLimitToday(TimeUtils.today());
    }

    // ==================================================================
    //  Daily summary / reports
    // ==================================================================

    /**
     * Materialises the daily report for {@code dayKey}.
     *
     * <p>Idempotent: re-running for the same day recomputes and replaces, so it
     * is safe to call both from the scheduled worker and from opening the report
     * screen.</p>
     */
    @WorkerThread
    @NonNull
    public DailySummaryEntity generateDailySummary(@NonNull String dayKey) {
        DashboardData d = loadDashboardForDay(dayKey);

        DailySummaryEntity s = new DailySummaryEntity();
        s.dayKey = dayKey;
        s.focusScore = d.progressPercent;
        s.studyMinutes = d.studyMinutes;
        s.focusMinutes = d.focusMinutes;
        s.focusSessions = d.focusSessionsDone;
        s.screenMinutes = Math.max(0, d.screenMinutes);
        s.tasksTotal = d.tasksTotal;
        s.tasksDone = d.tasksDone;
        s.habitsTotal = d.habitsTotal;
        s.habitsDone = d.habitsDone;
        s.runDistanceM = d.runDistanceM;
        s.runMinutes = d.runMinutes;
        s.runDone = d.restDay || (d.runTargetM > 0 && d.runDistanceM >= d.runTargetM);
        s.workoutsDone = d.workoutsDone;
        s.blockHits = d.blockHits;
        s.distractionMinutesLost = d.distractionMinutesLost;
        s.streak = d.streak;
        s.improvementNotes = buildImprovementNotes(d);
        s.generatedAt = System.currentTimeMillis();

        // Preserve the "already notified" flag so a manual regeneration does not
        // produce a second report notification.
        DailySummaryEntity previous = db.usageDao().findSummary(dayKey);
        s.notified = previous != null && previous.notified;

        db.usageDao().insertSummary(s);
        return s;
    }

    /** Recomputes the summary for a day without changing its notified state. */
    public void scheduleSummaryRefresh(@NonNull final String dayKey) {
        AppDatabase.io().execute(() -> {
            try {
                generateDailySummary(dayKey);
            } catch (Exception e) {
                android.util.Log.w("Repository", "summary refresh failed for " + dayKey, e);
            }
        });
    }

    /**
     * Produces the "areas requiring improvement" list.
     *
     * <p>Wording is factual and forward-looking. Nothing here is phrased as a
     * failure, which the brief asks for explicitly and which behaviour-design
     * practice supports: shame produces avoidance, not change.</p>
     */
    @NonNull
    private String buildImprovementNotes(@NonNull DashboardData d) {
        List<String> notes = new ArrayList<>();

        if (d.studyTargetMinutes > 0 && d.studyMinutes < d.studyTargetMinutes) {
            notes.add(context.getString(R.string.improve_study,
                    TimeUtils.formatMinutes(d.studyMinutes),
                    TimeUtils.formatMinutes(d.studyTargetMinutes)));
        }
        if (d.focusSessionTarget > 0 && d.focusSessionsDone == 0) {
            notes.add(context.getString(R.string.improve_focus));
        }
        int habitsLeft = d.habitsTotal - d.habitsDone;
        if (habitsLeft > 0 && d.habitsTotal > 0) {
            notes.add(habitsLeft == 1
                    ? context.getString(R.string.improve_habits, habitsLeft)
                    : context.getString(R.string.improve_habits_plural, habitsLeft));
        }
        int tasksLeft = d.tasksTotal - d.tasksDone;
        if (tasksLeft > 0) {
            notes.add(tasksLeft == 1
                    ? context.getString(R.string.improve_tasks, tasksLeft)
                    : context.getString(R.string.improve_tasks_plural, tasksLeft));
        }
        if (d.runTargetM > 0 && !d.restDay && d.runDistanceM < d.runTargetM) {
            notes.add(context.getString(R.string.improve_run,
                    TimeUtils.formatDistance(d.runTargetM - d.runDistanceM)));
        }
        if (d.screenTimeAvailable && d.screenTargetMinutes > 0
                && d.screenMinutes > d.screenTargetMinutes) {
            notes.add(context.getString(R.string.improve_screen,
                    TimeUtils.formatMinutes(d.screenMinutes - d.screenTargetMinutes),
                    TimeUtils.formatMinutes(d.screenTargetMinutes)));
        }
        if (d.distractionMinutesLost > 0) {
            notes.add(context.getString(R.string.improve_distraction,
                    TimeUtils.formatMinutes(d.distractionMinutesLost)));
        }

        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < notes.size(); i++) {
            if (i > 0) sb.append('\n');
            sb.append(notes.get(i));
        }
        return sb.toString();
    }

    /**
     * Loads dashboard data for an arbitrary day.
     *
     * <p>The streak and weekly series are always computed relative to today,
     * because they are properties of the current run of days rather than of the
     * day being reported.</p>
     */
    @WorkerThread
    @NonNull
    public DashboardData loadDashboardForDay(@NonNull String dayKey) {
        if (TimeUtils.isToday(dayKey)) return loadDashboard();

        DashboardData d = new DashboardData();
        d.dayKey = dayKey;

        d.studyMinutes = db.studyDao().minutesOnDay(dayKey);
        d.studyTargetMinutes = Prefs.targetStudyMinutes(context);
        d.focusMinutes = db.focusSessionDao().focusedSecOnDay(dayKey) / 60;
        d.focusSessionsDone = db.focusSessionDao().completedCountOnDay(dayKey);
        d.focusSessionTarget = Prefs.targetFocusSessions(context);
        d.blockHits = db.focusSessionDao().blockHitsOnDay(dayKey);

        d.tasksTotal = db.taskDao().totalOnDay(dayKey);
        d.tasksDone = db.taskDao().doneOnDay(dayKey);
        d.tasksRemaining = Math.max(0, d.tasksTotal - d.tasksDone);

        d.habitsTotal = db.habitDao().activeHabits().size();
        d.habitsDone = db.habitDao().doneCountOnDay(dayKey);

        d.runDistanceM = db.fitnessDao().distanceOnDay(dayKey);
        d.runMinutes = db.fitnessDao().runSecondsOnDay(dayKey) / 60;
        d.runTargetM = Prefs.targetRunMetres(context);
        d.workoutsDone = db.fitnessDao().workoutsDoneOnDay(dayKey);
        d.workoutTarget = Prefs.targetWorkouts(context);
        d.restDay = false; // rest-day marking only applies to today

        d.screenTargetMinutes = Prefs.targetScreenMinutes(context);
        d.screenTimeAvailable = PermissionManager.hasUsageAccess(context);
        d.screenMinutes = screenMinutesForDay(dayKey);

        d.urgesTotal = db.controlDao().totalUrgeCount(dayKey, dayKey);
        d.urgesResisted = db.controlDao().resistedCount(dayKey, dayKey);
        d.distractionMinutesLost = db.controlDao().minutesLostOnDay(dayKey);

        d.score = FocusScoreCalculator.compute(toScoreInput(d));
        d.progressPercent = FocusScoreCalculator.progressPercent(d.score);

        List<String> qualifying = db.usageDao().daysWithScoreAtLeast(Prefs.streakMinScore(context));
        d.streak = FocusScoreCalculator.currentStreak(qualifying, TimeUtils.today());
        d.bestStreak = FocusScoreCalculator.bestStreak(qualifying);

        int quoteIndex = quoteIndexForDay(dayKey, QUOTE_RES.length);
        d.quoteIndex = quoteIndex;
        d.quoteKey = String.valueOf(QUOTE_RES[quoteIndex]);

        return d;
    }

    /** Weekly aggregate for the performance report. */
    public static final class WeekData {
        @NonNull
        public List<String> dayKeys = new ArrayList<>();
        public int avgScore;
        public int bestScore;
        public int worstScore;
        @Nullable
        public String bestDayKey;
        @Nullable
        public String worstDayKey;
        public int studyMinutes;
        public int focusMinutes;
        public int focusSessions;
        public int screenMinutes;
        public int runDistanceM;
        public int runDays;
        public int habitsDone;
        public int habitsPossible;
        public int tasksDone;
        public int tasksTotal;
        public int blockHits;
        public int trend;
        /** Per-day scores aligned with {@link #dayKeys}. */
        @NonNull
        public int[] dailyScores = new int[0];
        @NonNull
        public int[] dailyStudy = new int[0];
        @NonNull
        public int[] dailyScreen = new int[0];
    }

    public static final int TREND_IMPROVING = 1;
    public static final int TREND_FLAT = 0;
    public static final int TREND_DECLINING = -1;

    @WorkerThread
    @NonNull
    public WeekData loadWeek() {
        WeekData w = new WeekData();
        w.dayKeys = TimeUtils.lastSevenDayKeys();
        String from = w.dayKeys.get(0);
        String to = w.dayKeys.get(w.dayKeys.size() - 1);

        w.dailyScores = new int[w.dayKeys.size()];
        w.dailyStudy = new int[w.dayKeys.size()];
        w.dailyScreen = new int[w.dayKeys.size()];

        int scoreSum = 0;
        int scoreCount = 0;
        w.bestScore = -1;
        w.worstScore = 101;

        for (int i = 0; i < w.dayKeys.size(); i++) {
            String key = w.dayKeys.get(i);
            DailySummaryEntity s = db.usageDao().findSummary(key);
            int score;
            int study;
            int screen;
            if (s != null) {
                score = s.focusScore;
                study = s.studyMinutes;
                screen = s.screenMinutes;
                w.focusSessions += s.focusSessions;
                w.focusMinutes += s.focusMinutes;
                w.runDistanceM += s.runDistanceM;
                w.habitsDone += s.habitsDone;
                w.habitsPossible += s.habitsTotal;
                w.tasksDone += s.tasksDone;
                w.tasksTotal += s.tasksTotal;
                w.blockHits += s.blockHits;
                if (s.runDone) w.runDays++;
            } else {
                // No summary row: fall back to the raw tables so a week is never
                // reported as empty just because the worker had not run.
                study = db.studyDao().minutesOnDay(key);
                screen = Math.max(0, screenMinutesForDay(key));
                score = FocusScoreCalculator.compute(dayInputForKey(key, study, screen)).score;
            }

            w.dailyScores[i] = score;
            w.dailyStudy[i] = study;
            w.dailyScreen[i] = screen;

            scoreSum += score;
            scoreCount++;
            if (score > w.bestScore) {
                w.bestScore = score;
                w.bestDayKey = key;
            }
            if (score < w.worstScore) {
                w.worstScore = score;
                w.worstDayKey = key;
            }
            w.studyMinutes += study;
            w.screenMinutes += screen;
        }

        w.avgScore = scoreCount == 0 ? 0 : Math.round(scoreSum / (float) scoreCount);
        if (w.bestScore < 0) w.bestScore = 0;
        if (w.worstScore > 100) w.worstScore = 0;
        w.trend = computeTrend(w.dailyScores);
        return w;
    }

    /**
     * Compares the mean of the first half of the week with the second half.
     *
     * <p>A simple first-vs-last comparison is too noisy: one bad Monday and one
     * good Sunday would read as a strong improvement. Halves are more stable and
     * still cheap.</p>
     */
    static int computeTrend(@NonNull int[] dailyScores) {
        if (dailyScores.length < 4) return TREND_FLAT;
        int mid = dailyScores.length / 2;
        int firstSum = 0;
        int secondSum = 0;
        for (int i = 0; i < mid; i++) firstSum += dailyScores[i];
        for (int i = mid; i < dailyScores.length; i++) secondSum += dailyScores[i];

        float firstAvg = firstSum / (float) mid;
        float secondAvg = secondSum / (float) (dailyScores.length - mid);
        float delta = secondAvg - firstAvg;

        // A 5-point band counts as steady, so small wobble is not reported as a
        // trend in either direction.
        if (delta >= 5f) return TREND_IMPROVING;
        if (delta <= -5f) return TREND_DECLINING;
        return TREND_FLAT;
    }

    @NonNull
    private FocusScoreCalculator.Day dayInputForKey(@NonNull String dayKey, int studyMinutes, int screenMinutes) {
        FocusScoreCalculator.Day in = new FocusScoreCalculator.Day();
        in.studyMinutes = studyMinutes;
        in.studyTargetMinutes = Prefs.targetStudyMinutes(context);
        in.focusSessionsCompleted = db.focusSessionDao().completedCountOnDay(dayKey);
        in.focusSessionTarget = Prefs.targetFocusSessions(context);
        in.habitsDone = db.habitDao().doneCountOnDay(dayKey);
        in.habitsTotal = db.habitDao().activeHabits().size();
        in.tasksDone = db.taskDao().doneOnDay(dayKey);
        in.tasksTotal = db.taskDao().totalOnDay(dayKey);
        in.runDistanceM = db.fitnessDao().distanceOnDay(dayKey);
        in.runTargetM = Prefs.targetRunMetres(context);
        in.workoutsDone = db.fitnessDao().workoutsDoneOnDay(dayKey);
        in.workoutTarget = Prefs.targetWorkouts(context);
        in.screenMinutes = screenMinutes;
        in.screenLimitMinutes = Prefs.targetScreenMinutes(context);
        in.screenTimeUnknown = !PermissionManager.hasUsageAccess(context);
        return in;
    }

    // ==================================================================
    //  Lifetime totals (Profile)
    // ==================================================================

    public static final class LifetimeStats {
        public int totalStudyMinutes;
        public int totalFocusMinutes;
        public int totalSessions;
        public int totalDistanceM;
        public int currentStreak;
        public int bestStreak;
        public int totalBlockHits;
        public int completedTopics;
        public int totalTopics;
        public int achievedGoals;
    }

    @WorkerThread
    @NonNull
    public LifetimeStats loadLifetimeStats() {
        LifetimeStats s = new LifetimeStats();

        // Study total over the full retained range. Room handles the aggregate;
        // pulling every row into memory would be far worse on a 4 GB device.
        s.totalStudyMinutes = db.studyDao().minutesBetween("1970-01-01", TimeUtils.today());

        int focusSec = 0;
        int completed = 0;
        List<FocusSessionEntity> sessions = db.focusSessionDao().recent(10_000);
        for (FocusSessionEntity f : sessions) {
            if (f == null) continue;
            focusSec += f.focusedSec;
            // Every milestone and every report says "sessions completed", so an
            // abandoned session must not count toward them. Its focused minutes
            // still count, because the time was genuinely spent.
            if (f.completed) completed++;
        }
        s.totalSessions = completed;
        s.totalFocusMinutes = focusSec / 60;

        s.totalDistanceM = db.fitnessDao().distanceBetween("1970-01-01", TimeUtils.today());
        s.totalBlockHits = db.blockerDao().totalBlockCount();
        s.completedTopics = db.studyDao().completedTopicCount();
        s.totalTopics = db.studyDao().topicCount();
        s.achievedGoals = db.goalDao().achievedCount();

        List<String> qualifying = db.usageDao().daysWithScoreAtLeast(Prefs.streakMinScore(context));
        s.currentStreak = FocusScoreCalculator.currentStreak(qualifying, TimeUtils.today());
        s.bestStreak = FocusScoreCalculator.bestStreak(qualifying);
        return s;
    }

    // ==================================================================
    //  Focus sessions
    // ==================================================================

    /** Starts a focus session and returns its row id. */
    @WorkerThread
    public long startFocusSession(@NonNull String taskLabel, int durationSec,
                                  boolean deepFocus, @NonNull String mode) {
        FocusSessionEntity s = new FocusSessionEntity(
                taskLabel, durationSec, System.currentTimeMillis(), TimeUtils.today());
        s.active = true;
        s.deepFocus = deepFocus;
        s.mode = mode;
        s.focusedSec = 0;
        // Any orphaned active session from a crash is closed out first, so there
        // is never more than one "active" row to restore.
        db.focusSessionDao().clearActiveFlags();
        return db.focusSessionDao().insert(s);
    }

    @WorkerThread
    @Nullable
    public FocusSessionEntity activeSession() {
        return db.focusSessionDao().findActive();
    }

    /**
     * Finishes a session.
     *
     * @param focusedSec seconds actually spent focused
     * @param completed  true only when the full planned duration was reached
     */
    @WorkerThread
    public void finishFocusSession(long sessionId, int focusedSec, boolean completed) {
        FocusSessionEntity s = db.focusSessionDao().findById(sessionId);
        if (s == null) return;
        s.focusedSec = focusedSec;
        s.completed = completed;
        s.active = false;
        s.endTime = System.currentTimeMillis();
        db.focusSessionDao().update(s);
        scheduleSummaryRefresh(s.dayKey);
    }

    /** Records a distraction attempt against the running session. */
    @WorkerThread
    public void recordBlockHitOnActiveSession() {
        FocusSessionEntity s = db.focusSessionDao().findActive();
        if (s == null) return;
        s.blockHits++;
        db.focusSessionDao().update(s);
    }

    // ==================================================================
    //  Misc
    // ==================================================================

    /**
     * Resolves a display name, preferring the string resource so built-in rows
     * follow the current language while user-typed rows are preserved verbatim.
     */
    @NonNull
    public String resolveString(int resId, @Nullable String fallback) {
        if (resId != 0) {
            try {
                return context.getString(resId);
            } catch (Exception ignored) {
                // Resource removed in an upgrade; fall through to stored text.
            }
        }
        return fallback == null ? "" : fallback;
    }

    /**
     * Resets today's activity, leaving earlier history intact. Used by Profile →
     * "Reset today's data".
     */
    @WorkerThread
    public void resetToday() {
        String today = TimeUtils.today();

        db.taskDao().deleteDay(today);

        for (HabitLogEntity l : db.habitDao().logsForDay(today)) {
            db.habitDao().deleteLog(l.habitId, today);
        }
        for (com.focuslife.app.data.db.entity.WorkoutLogEntity l
                : db.fitnessDao().workoutLogsForDay(today)) {
            db.fitnessDao().deleteWorkoutLog(l.workoutId, today);
        }

        db.usageDao().deleteDay(today);
        db.usageDao().deleteSummary(today);

        for (FocusSessionEntity s : db.focusSessionDao().between(today, today)) {
            db.focusSessionDao().delete(s);
        }
        for (com.focuslife.app.data.db.entity.RunLogEntity r
                : db.fitnessDao().runsOnDay(today)) {
            db.fitnessDao().deleteRun(r);
        }
        for (com.focuslife.app.data.db.entity.DistractionLogEntity l
                : db.controlDao().between(today, today)) {
            db.controlDao().deleteDistraction(l);
        }

        Prefs.setRestDayToday(context, false);
        BlockState.get().refreshAsync(context);
        scheduleSummaryRefresh(today);
    }

    /** Erases everything and re-seeds the defaults. */
    @WorkerThread
    public void eraseAll() {
        db.usageDao().deleteAll();
        db.usageDao().deleteAllSummaries();
        db.controlDao().deleteAllDistractions();
        db.controlDao().deleteAllReasons();
        db.goalDao().deleteAll();
        db.routineDao().deleteAll();
        db.fitnessDao().deleteAllRuns();
        db.fitnessDao().deleteAllWorkoutLogs();
        db.fitnessDao().deleteAllWorkouts();
        db.blockerDao().deleteAllApps();
        db.blockerDao().deleteAllSchedules();
        db.habitDao().deleteAllLogs();
        db.habitDao().deleteAllHabits();
        db.studyDao().deleteAllLogs();
        db.studyDao().deleteAllTopics();
        db.taskDao().deleteAll();
        db.focusSessionDao().deleteAll();

        Prefs.clearUnlockHistory(context);
        Prefs.setRestDayToday(context, false);
        BlockState.get().invalidate();

        com.focuslife.app.data.db.DatabaseSeeder.seedFirstLaunch(context);
    }

    /**
     * Rolls yesterday's unfinished tasks into today.
     *
     * @return how many were carried over.
     */
    @WorkerThread
    public int rollOverTasks() {
        String yesterday = TimeUtils.dayOffsetFromToday(-1);
        String today = TimeUtils.today();
        List<com.focuslife.app.data.db.entity.TaskEntity> undone = db.taskDao().undoneOn(yesterday);
        long now = System.currentTimeMillis();
        int moved = 0;
        for (com.focuslife.app.data.db.entity.TaskEntity t : undone) {
            com.focuslife.app.data.db.entity.TaskEntity copy =
                    new com.focuslife.app.data.db.entity.TaskEntity();
            copy.title = t.title;
            copy.notes = t.notes;
            copy.dayKey = today;
            copy.done = false;
            copy.estimateMin = t.estimateMin;
            copy.priority = t.priority;
            copy.createdAt = now;
            db.taskDao().insert(copy);
            moved++;
        }
        return moved;
    }
}
