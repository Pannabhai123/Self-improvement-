package com.focuslife.app.ui.motivation;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.annotation.StringRes;

import com.focuslife.app.R;
import com.focuslife.app.data.db.entity.DailySummaryEntity;
import com.focuslife.app.data.repo.Repository;

import java.util.ArrayList;
import java.util.List;

/**
 * The eighteen milestones from the brief, evaluated against real history.
 *
 * <h2>Why these are computed and not stored</h2>
 * A milestone is a fact about what the user has already done, not a goal they
 * typed. Storing it as a row would mean it could drift from the data it
 * describes — a deleted focus session would leave "10 sessions" permanently
 * marked as reached. Deriving it on read keeps it true by construction, and the
 * whole evaluation is a handful of aggregate queries that already exist.
 *
 * <h2>What it costs</h2>
 * Everything here comes from {@link Repository.LifetimeStats} plus the materialised
 * {@link DailySummaryEntity} rows, so it is a few indexed reads. It runs on the IO
 * executor with the rest of the screen's data, never on the main thread.
 */
public final class MilestoneTracker {

    private MilestoneTracker() {
    }

    /** One milestone and whether it has been reached. */
    public static final class Milestone {
        @StringRes
        public final int labelRes;
        public final boolean reached;

        Milestone(@StringRes int labelRes, boolean reached) {
            this.labelRes = labelRes;
            this.reached = reached;
        }
    }

    /** Minutes of focus in one day that count as "an hour of deep focus". */
    private static final int HOUR_FOCUS_MINUTES = 60;

    /** Minutes of study in one week that count as "ten hours". */
    private static final int TEN_HOURS_STUDY_MINUTES = 600;

    /** Consecutive days under the screen-time limit required for that milestone. */
    private static final int SCREEN_DAYS_REQUIRED = 7;

    private static final int SECONDS_8_MIN = 8 * 60;
    private static final int SECONDS_7_MIN = 7 * 60;

    /**
     * Evaluates every milestone.
     *
     * @param lifetime       aggregate totals across all history
     * @param summaries      recent per-day rows, newest first, used for the
     *                       day-shaped milestones that no aggregate can answer
     * @param best1600Sec    fastest recorded 1600 m in seconds, 0 if none
     */
    @NonNull
    public static List<Milestone> evaluate(@NonNull Repository.LifetimeStats lifetime,
                                           @NonNull List<DailySummaryEntity> summaries,
                                           int best1600Sec) {
        List<Milestone> out = new ArrayList<>(18);

        // Focus sessions.
        out.add(new Milestone(R.string.milestone_first_session, lifetime.totalSessions >= 1));
        out.add(new Milestone(R.string.milestone_ten_sessions, lifetime.totalSessions >= 10));
        out.add(new Milestone(R.string.milestone_fifty_sessions, lifetime.totalSessions >= 50));
        out.add(new Milestone(R.string.milestone_first_hundred_sessions,
                lifetime.totalSessions >= 100));

        // Streaks.
        out.add(new Milestone(R.string.milestone_streak_3, lifetime.bestStreak >= 3));
        out.add(new Milestone(R.string.milestone_streak_7, lifetime.bestStreak >= 7));
        out.add(new Milestone(R.string.milestone_streak_21, lifetime.bestStreak >= 21));
        out.add(new Milestone(R.string.milestone_streak_30, lifetime.bestStreak >= 30));
        out.add(new Milestone(R.string.milestone_streak_100, lifetime.bestStreak >= 100));

        // Day-shaped: no lifetime aggregate can answer these, so they read the
        // materialised per-day summaries.
        out.add(new Milestone(R.string.milestone_hour_focus,
                anyDayWithFocusAtLeast(summaries, HOUR_FOCUS_MINUTES)));
        out.add(new Milestone(R.string.milestone_ten_hours_study,
                anyWeekWithStudyAtLeast(summaries, TEN_HOURS_STUDY_MINUTES)));
        out.add(new Milestone(R.string.milestone_all_habits,
                anyDayWithEveryHabit(summaries)));
        out.add(new Milestone(R.string.milestone_screen_under_limit,
                consecutiveDaysUnderScreenLimit(summaries) >= SCREEN_DAYS_REQUIRED));

        // Running.
        out.add(new Milestone(R.string.milestone_1600_first, best1600Sec > 0));
        out.add(new Milestone(R.string.milestone_1600_under_8,
                best1600Sec > 0 && best1600Sec < SECONDS_8_MIN));
        out.add(new Milestone(R.string.milestone_1600_under_7,
                best1600Sec > 0 && best1600Sec < SECONDS_7_MIN));

        // Syllabus.
        out.add(new Milestone(R.string.milestone_first_topic, lifetime.completedTopics >= 1));
        out.add(new Milestone(R.string.milestone_ten_topics, lifetime.completedTopics >= 10));

        return out;
    }

    /** How many of the milestones have been reached. */
    public static int reachedCount(@NonNull List<Milestone> milestones) {
        int n = 0;
        for (Milestone m : milestones) {
            if (m.reached) n++;
        }
        return n;
    }

    private static boolean anyDayWithFocusAtLeast(@NonNull List<DailySummaryEntity> summaries,
                                                  int minutes) {
        for (DailySummaryEntity s : summaries) {
            if (s != null && s.focusMinutes >= minutes) return true;
        }
        return false;
    }

    private static boolean anyDayWithEveryHabit(@NonNull List<DailySummaryEntity> summaries) {
        for (DailySummaryEntity s : summaries) {
            // A day with zero habits configured does not count: an empty checklist
            // is not an achievement, and counting it would award the milestone on
            // the very first launch.
            if (s != null && s.habitsTotal > 0 && s.habitsDone >= s.habitsTotal) return true;
        }
        return false;
    }

    /**
     * Whether any rolling seven-day window totals at least {@code minutes}.
     *
     * <p>Summaries arrive newest first and may have gaps, so the window is built
     * over consecutive list positions rather than calendar days. A missing day is
     * a day with no summary, which contributes zero — the honest reading.</p>
     */
    private static boolean anyWeekWithStudyAtLeast(@NonNull List<DailySummaryEntity> summaries,
                                                   int minutes) {
        if (summaries.size() < 7) return false;
        int window = 0;
        for (int i = 0; i < 7; i++) {
            DailySummaryEntity s = summaries.get(i);
            window += s == null ? 0 : s.studyMinutes;
        }
        if (window >= minutes) return true;

        for (int i = 7; i < summaries.size(); i++) {
            DailySummaryEntity leaving = summaries.get(i - 7);
            DailySummaryEntity entering = summaries.get(i);
            window -= leaving == null ? 0 : leaving.studyMinutes;
            window += entering == null ? 0 : entering.studyMinutes;
            if (window >= minutes) return true;
        }
        return false;
    }

    /**
     * Longest current run of consecutive days under the screen-time limit.
     *
     * <p>Days where usage could not be measured ({@code screenMinutes < 0}) are
     * skipped rather than treated as failures: an unmeasured day is not a day the
     * user went over, and breaking the run on it would be a false negative.</p>
     */
    private static int consecutiveDaysUnderScreenLimit(@NonNull List<DailySummaryEntity> summaries) {
        int longest = 0;
        int current = 0;
        for (DailySummaryEntity s : summaries) {
            if (s == null) {
                current = 0;
                continue;
            }
            if (s.screenMinutes < 0) {
                // Unmeasured: leave the run intact but do not extend it.
                continue;
            }
            // The limit is not stored on the summary row, so the comparison uses
            // the current target. That is deliberate: the milestone asks about
            // the user's own limit, and comparing against a stale historical
            // target would reward lowering it later.
            if (s.screenMinutes <= screenLimitFor()) {
                current++;
                if (current > longest) longest = current;
            } else {
                current = 0;
            }
        }
        return longest;
    }

    private static int screenLimitFor() {
        Context context = com.focuslife.app.util.AppContext.get();
        if (context == null) return com.focuslife.app.data.prefs.Prefs.DEFAULT_TARGET_SCREEN_MIN;
        return com.focuslife.app.data.prefs.Prefs.targetScreenMinutes(context);
    }
}
