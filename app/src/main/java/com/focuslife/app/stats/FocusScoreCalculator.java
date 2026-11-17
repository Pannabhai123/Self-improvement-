package com.focuslife.app.stats;

/**
 * Computes the Focus Score and the 0-100% daily progress circle.
 *
 * <p>This class is deliberately free of any Android dependency so that the
 * scoring maths is covered by real JVM unit tests
 * ({@code FocusScoreCalculatorTest}) rather than being asserted by eye.</p>
 *
 * <h2>Design principles</h2>
 * <ol>
 *   <li><b>Additive, never punitive.</b> Every component contributes between 0
 *       and its weight. Doing nothing scores 0; there is no negative score and
 *       no penalty that can push a partially-good day below a worse one.</li>
 *   <li><b>Capped, not competitive.</b> Exceeding a target yields the full
 *       weight and no more, so the score reads as "did I do my day" rather
 *       than "how much more than yesterday".</li>
 *   <li><b>Screen time is an inverse component.</b> Staying under the daily
 *       ceiling earns points; going over earns nothing but subtracts nothing.</li>
 *   <li><b>Missing data is neutral.</b> When a target is 0 (the user has not
 *       configured it) that component is excluded and the remaining weights are
 *       renormalised, so an unset fitness goal cannot drag the score down.</li>
 * </ol>
 */
public final class FocusScoreCalculator {

    private FocusScoreCalculator() {
    }

    // ------------------------------------------------------------------
    //  Weights (must total WEIGHT_TOTAL)
    // ------------------------------------------------------------------

    public static final int W_STUDY = 26;
    public static final int W_FOCUS_SESSIONS = 12;
    public static final int W_HABITS = 28;
    public static final int W_TASKS = 10;
    public static final int W_FITNESS = 12;
    public static final int W_SCREEN_TIME = 8;
    public static final int W_DISTRACTION = 4;

    public static final int WEIGHT_TOTAL =
            W_STUDY + W_FOCUS_SESSIONS + W_HABITS + W_TASKS
                    + W_FITNESS + W_SCREEN_TIME + W_DISTRACTION;

    /** Focus score at or above which a day counts toward the streak. */
    public static final int STREAK_THRESHOLD = 55;

    // ------------------------------------------------------------------
    //  Inputs
    // ------------------------------------------------------------------

    /**
     * Everything the score needs for one day. All values are raw counts; the
     * caller (repository layer) is responsible for reading them out of Room.
     *
     * <p>Targets are user-configurable; a target of 0 disables that component.</p>
     */
    public static final class Day {

        // ---- Study ----
        /** Minutes logged against the study planner. */
        public int studyMinutes;
        /** Daily study target in minutes. 0 disables the component. */
        public int studyTargetMinutes = 360;

        // ---- Focus sessions ----
        /** Completed focus sessions (25/50/custom) on the day. */
        public int focusSessionsCompleted;
        /** Daily session target. 0 disables the component. */
        public int focusSessionTarget = 4;
        /** Total seconds actually focused, used as a tie-breaker within the component. */
        public int focusedSeconds;

        // ---- Habits ----
        public int habitsDone;
        public int habitsTotal;

        // ---- Tasks ----
        public int tasksDone;
        public int tasksTotal;

        // ---- Fitness ----
        /** Metres run on the day. */
        public int runDistanceM;
        /** Daily running target in metres. 0 disables the component. */
        public int runTargetM = 1600;
        /** Exercise checklist items completed. */
        public int workoutsDone;
        /** Exercise checklist target. 0 disables the sub-component. */
        public int workoutTarget = 3;
        /** True on a scheduled rest day, which awards the fitness weight in full. */
        public boolean restDay;

        // ---- Screen time ----
        /** Total foreground screen minutes reported by UsageStatsManager. */
        public int screenMinutes;
        /** Daily screen-time ceiling in minutes. 0 disables the component. */
        public int screenLimitMinutes = 180;
        /** True when usage access was denied, so screenMinutes is meaningless. */
        public boolean screenTimeUnknown;

        // ---- Distraction control ----
        /** Times a blocked app was intercepted. */
        public int blockHits;
        /** Urges the user logged and resisted. */
        public int urgesResisted;
        /** Urges the user logged in total. */
        public int urgesTotal;
        /** Minutes the user recorded as lost to distraction. */
        public int distractionMinutesLost;

        public Day() {
        }
    }

    // ------------------------------------------------------------------
    //  Result
    // ------------------------------------------------------------------

    /**
     * The score plus its breakdown, so the UI can show <em>why</em> the day
     * scored what it did and what the single highest-leverage next action is.
     */
    public static final class Result {

        /** 0-100 overall. This is the dashboard circle. */
        public final int score;

        /** Points earned per component, each out of its weight. */
        public final int studyPoints;
        public final int focusPoints;
        public final int habitPoints;
        public final int taskPoints;
        public final int fitnessPoints;
        public final int screenPoints;
        public final int distractionPoints;

        /** Sum of the weights of components that were actually applicable. */
        public final int applicableWeight;

        /**
         * {@link Improvement} with the largest available gain, or {@code null}
         * when everything applicable is already maxed.
         */
        public final Improvement topImprovement;

        Result(int score, int studyPoints, int focusPoints, int habitPoints, int taskPoints,
               int fitnessPoints, int screenPoints, int distractionPoints, int applicableWeight,
               Improvement topImprovement) {
            this.score = score;
            this.studyPoints = studyPoints;
            this.focusPoints = focusPoints;
            this.habitPoints = habitPoints;
            this.taskPoints = taskPoints;
            this.fitnessPoints = fitnessPoints;
            this.screenPoints = screenPoints;
            this.distractionPoints = distractionPoints;
            this.applicableWeight = applicableWeight;
            this.topImprovement = topImprovement;
        }
    }

    /** One concrete "area requiring improvement" with its potential gain. */
    public static final class Improvement {
        /** Stable key so the UI can map it to a translated string. */
        public final String key;
        /** Points still available in this component, 0 when maxed. */
        public final int availablePoints;
        /** How much more of the underlying quantity would max it, or -1. */
        public final int remainingUnits;
        /** Unit label key: "minutes", "sessions", "habits", "tasks", "metres". */
        public final String unitKey;

        Improvement(String key, int availablePoints, int remainingUnits, String unitKey) {
            this.key = key;
            this.availablePoints = availablePoints;
            this.remainingUnits = remainingUnits;
            this.unitKey = unitKey;
        }
    }

    public static final String KEY_STUDY = "study";
    public static final String KEY_FOCUS = "focus";
    public static final String KEY_HABITS = "habits";
    public static final String KEY_TASKS = "tasks";
    public static final String KEY_FITNESS = "fitness";
    public static final String KEY_SCREEN = "screen";
    public static final String KEY_DISTRACTION = "distraction";

    public static final String UNIT_MINUTES = "minutes";
    public static final String UNIT_SESSIONS = "sessions";
    public static final String UNIT_HABITS = "habits";
    public static final String UNIT_TASKS = "tasks";
    public static final String UNIT_METRES = "metres";
    public static final String UNIT_URGES = "urges";

    // ------------------------------------------------------------------
    //  Scoring
    // ------------------------------------------------------------------

    /**
     * Scores a day.
     *
     * <p>Components whose target is 0 (or which have no denominator) are
     * skipped, and the final score is renormalised over the applicable weight
     * only. That means a user who never sets a running target still gets a
     * fair 0-100 score instead of being permanently capped at 88.</p>
     */
    public static Result compute(Day d) {
        if (d == null) d = new Day();

        int applicable = 0;

        // ---- Study ------------------------------------------------
        int studyPoints = 0;
        int studyRemaining = -1;
        if (d.studyTargetMinutes > 0) {
            applicable += W_STUDY;
            studyPoints = scale(d.studyMinutes, d.studyTargetMinutes, W_STUDY);
            studyRemaining = Math.max(0, d.studyTargetMinutes - d.studyMinutes);
        }

        // ---- Focus sessions ---------------------------------------
        int focusPoints = 0;
        int focusRemaining = -1;
        if (d.focusSessionTarget > 0) {
            applicable += W_FOCUS_SESSIONS;
            focusPoints = scale(d.focusSessionsCompleted, d.focusSessionTarget, W_FOCUS_SESSIONS);
            focusRemaining = Math.max(0, d.focusSessionTarget - d.focusSessionsCompleted);
        }

        // ---- Habits ------------------------------------------------
        int habitPoints = 0;
        int habitRemaining = -1;
        if (d.habitsTotal > 0) {
            applicable += W_HABITS;
            habitPoints = scale(d.habitsDone, d.habitsTotal, W_HABITS);
            habitRemaining = Math.max(0, d.habitsTotal - d.habitsDone);
        }

        // ---- Tasks -------------------------------------------------
        int taskPoints = 0;
        int taskRemaining = -1;
        if (d.tasksTotal > 0) {
            applicable += W_TASKS;
            taskPoints = scale(d.tasksDone, d.tasksTotal, W_TASKS);
            taskRemaining = Math.max(0, d.tasksTotal - d.tasksDone);
        }

        // ---- Fitness -----------------------------------------------
        int fitnessPoints = 0;
        int fitnessRemaining = -1;
        boolean fitnessApplicable = d.runTargetM > 0 || d.workoutTarget > 0;
        if (fitnessApplicable) {
            applicable += W_FITNESS;
            if (d.restDay) {
                // A planned rest day is a completed decision, not a miss.
                fitnessPoints = W_FITNESS;
                fitnessRemaining = 0;
            } else {
                int runPart = 0;
                int workoutPart = 0;
                int runWeight = d.runTargetM > 0 ? (d.workoutTarget > 0 ? W_FITNESS / 2 : W_FITNESS) : 0;
                int workoutWeight = W_FITNESS - runWeight;

                if (runWeight > 0) {
                    runPart = scale(d.runDistanceM, d.runTargetM, runWeight);
                    fitnessRemaining = Math.max(0, d.runTargetM - d.runDistanceM);
                }
                if (workoutWeight > 0) {
                    workoutPart = scale(d.workoutsDone, d.workoutTarget, workoutWeight);
                    int workoutRemaining = Math.max(0, d.workoutTarget - d.workoutsDone);
                    // Report whichever sub-goal is further from done, in its own unit.
                    if (fitnessRemaining < 0 || workoutRemaining > 0) {
                        if (runWeight == 0 || workoutRemaining > 0) {
                            fitnessRemaining = workoutRemaining;
                        }
                    }
                }
                fitnessPoints = clamp(runPart + workoutPart, 0, W_FITNESS);
            }
        }

        // ---- Screen time (inverse) ----------------------------------
        int screenPoints = 0;
        int screenRemaining = -1;
        if (d.screenLimitMinutes > 0 && !d.screenTimeUnknown) {
            applicable += W_SCREEN_TIME;
            // Full marks at or under the ceiling, 0 at twice the ceiling.
            int over = d.screenMinutes - d.screenLimitMinutes;
            if (over <= 0) {
                screenPoints = W_SCREEN_TIME;
                screenRemaining = 0;
            } else {
                screenPoints = scale(d.screenLimitMinutes - over, d.screenLimitMinutes, W_SCREEN_TIME);
                screenRemaining = over; // minutes to cut to get back under
            }
        }

        // ---- Distraction control ------------------------------------
        int distractionPoints = 0;
        int distractionRemaining = -1;
        if (d.urgesTotal > 0) {
            applicable += W_DISTRACTION;
            // Reward resisting urges; a day with many blocks but many resistances
            // still scores, because logging the urge is itself the desired behaviour.
            distractionPoints = scale(d.urgesResisted, d.urgesTotal, W_DISTRACTION);
            distractionRemaining = Math.max(0, d.urgesTotal - d.urgesResisted);
        }

        // ---- Combine -------------------------------------------------
        int earned = studyPoints + focusPoints + habitPoints + taskPoints
                + fitnessPoints + screenPoints + distractionPoints;

        int score;
        if (applicable <= 0) {
            // Nothing configured yet: a fresh install shows 0 rather than a
            // misleading 100, and the empty state explains how to start.
            score = 0;
        } else {
            score = (int) Math.round(earned * 100.0 / applicable);
        }
        score = clamp(score, 0, 100);

        Improvement top = pickTop(
                new Improvement(KEY_STUDY, W_STUDY - studyPoints, studyRemaining, UNIT_MINUTES),
                new Improvement(KEY_FOCUS, W_FOCUS_SESSIONS - focusPoints, focusRemaining, UNIT_SESSIONS),
                new Improvement(KEY_HABITS, W_HABITS - habitPoints, habitRemaining, UNIT_HABITS),
                new Improvement(KEY_TASKS, W_TASKS - taskPoints, taskRemaining, UNIT_TASKS),
                new Improvement(KEY_FITNESS, W_FITNESS - fitnessPoints, fitnessRemaining, UNIT_METRES),
                new Improvement(KEY_SCREEN, W_SCREEN_TIME - screenPoints, screenRemaining, UNIT_MINUTES),
                new Improvement(KEY_DISTRACTION, W_DISTRACTION - distractionPoints, distractionRemaining, UNIT_URGES)
        );

        return new Result(score, studyPoints, focusPoints, habitPoints, taskPoints,
                fitnessPoints, screenPoints, distractionPoints, applicable, top);
    }

    /**
     * The 0-100% ring on the dashboard. It is the Focus Score, but expressed as
     * a percentage of the applicable weight so that it never exceeds 100 even
     * when several targets are over-achieved.
     */
    public static int progressPercent(Result r) {
        return r == null ? 0 : clamp(r.score, 0, 100);
    }

    // ------------------------------------------------------------------
    //  Streaks
    // ------------------------------------------------------------------

    /**
     * Current streak length given the day keys that qualified, descending, and
     * today's key.
     *
     * <p><b>Forgiving by design.</b> If today does not yet qualify but yesterday
     * does, the streak is preserved rather than broken — the day is not over.
     * This is what keeps the streak a motivator instead of a source of shame.</p>
     *
     * @param qualifyingDaysDesc distinct day keys with score &gt;= threshold, newest first
     * @param todayKey           today's {@code yyyy-MM-dd}
     */
    public static int currentStreak(java.util.List<String> qualifyingDaysDesc, String todayKey) {
        if (qualifyingDaysDesc == null || qualifyingDaysDesc.isEmpty() || todayKey == null) {
            return 0;
        }

        // Normalise into a set for O(1) membership tests.
        java.util.Set<String> done = new java.util.HashSet<>(qualifyingDaysDesc);

        // Decide whether the streak can still be extended today.
        String cursor;
        if (done.contains(todayKey)) {
            cursor = todayKey;
        } else {
            String yesterday = offset(todayKey, -1);
            if (done.contains(yesterday)) {
                // Today is still in progress: keep the streak alive.
                cursor = yesterday;
            } else {
                return 0;
            }
        }

        int streak = 0;
        // Guard against a pathological list by capping the walk at 4000 days.
        for (int i = 0; i < 4000; i++) {
            if (done.contains(cursor)) {
                streak++;
                cursor = offset(cursor, -1);
            } else {
                break;
            }
        }
        return streak;
    }

    /**
     * Best streak ever, computed over all qualifying days regardless of whether
     * the chain is still alive.
     */
    public static int bestStreak(java.util.List<String> qualifyingDaysDesc) {
        if (qualifyingDaysDesc == null || qualifyingDaysDesc.isEmpty()) return 0;

        // Sort ascending so consecutive days are adjacent.
        java.util.List<String> days = new java.util.ArrayList<>(qualifyingDaysDesc);
        java.util.Collections.sort(days);

        int best = 1;
        int run = 1;
        for (int i = 1; i < days.size(); i++) {
            if (offset(days.get(i - 1), 1).equals(days.get(i))) {
                run++;
                if (run > best) best = run;
            } else if (!days.get(i).equals(days.get(i - 1))) {
                run = 1;
            }
        }
        return best;
    }

    /**
     * Habit streak for one habit, using the same forgiving rule.
     *
     * @param completionDaysDesc day keys the habit was completed, newest first
     */
    public static int habitStreak(java.util.List<String> completionDaysDesc, String todayKey) {
        return currentStreak(completionDaysDesc, todayKey);
    }

    // ------------------------------------------------------------------
    //  Helpers
    // ------------------------------------------------------------------

    /**
     * Maps {@code value/target} onto {@code 0..weight}, capped at the weight.
     * Negative inputs are treated as 0. Returns 0 when the target is not
     * positive so callers cannot divide by zero.
     */
    static int scale(int value, int target, int weight) {
        if (target <= 0 || weight <= 0) return 0;
        if (value <= 0) return 0;
        if (value >= target) return weight;
        // Round so that a single completed habit out of ten still registers
        // some progress instead of truncating to zero.
        return clamp((int) Math.round(value * (double) weight / target), 0, weight);
    }

    /**
     * Bounds a value.
     *
     * <p>Public because screens outside this package derive their own percentages
     * (habit completion, topic progress) and must clamp with the same bounds the
     * score engine uses, or a chart and the score can disagree.</p>
     */
    public static int clamp(int v, int lo, int hi) {
        return v < lo ? lo : Math.min(v, hi);
    }

    private static Improvement pickTop(Improvement... candidates) {
        Improvement best = null;
        for (Improvement c : candidates) {
            if (c == null) continue;
            // Only consider components that were applicable (available < weight)
            // and still have points to give.
            if (c.availablePoints <= 0) continue;
            if (best == null
                    || c.availablePoints > best.availablePoints
                    || (c.availablePoints == best.availablePoints
                        && c.remainingUnits >= 0
                        && (best.remainingUnits < 0 || c.remainingUnits < best.remainingUnits))) {
                best = c;
            }
        }
        return best;
    }

    /** Day-key arithmetic without dragging TimeUtils (and its Calendar use) in. */
    private static String offset(String dayKey, int days) {
        try {
            int y = Integer.parseInt(dayKey.substring(0, 4));
            int m = Integer.parseInt(dayKey.substring(5, 7));
            int d = Integer.parseInt(dayKey.substring(8, 10));

            java.util.Calendar c = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC"));
            c.clear();
            c.set(y, m - 1, d);
            c.add(java.util.Calendar.DAY_OF_YEAR, days);

            return String.format(java.util.Locale.US, "%04d-%02d-%02d",
                    c.get(java.util.Calendar.YEAR),
                    c.get(java.util.Calendar.MONTH) + 1,
                    c.get(java.util.Calendar.DAY_OF_MONTH));
        } catch (Exception e) {
            return dayKey;
        }
    }
}
