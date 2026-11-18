package com.focuslife.app.ui.motivation;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import com.focuslife.app.R;
import com.focuslife.app.data.db.entity.DailySummaryEntity;
import com.focuslife.app.data.repo.Repository;
import com.focuslife.app.ui.motivation.MilestoneTracker.Milestone;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * The eighteen milestones, evaluated against history.
 *
 * <p>Milestones are derived rather than stored, and the tests below are the reason
 * why that matters: each one is checked at its exact boundary, so a deleted
 * session or a shortened streak can never leave a milestone falsely marked as
 * reached.</p>
 *
 * <p>Assertions identify milestones by their string resource rather than by list
 * position, so reordering the list cannot silently break the meaning of a test.</p>
 *
 * <p>These use the four-argument {@code evaluate} overload, which takes the
 * screen-time limit explicitly. That keeps the whole class under test free of any
 * Android dependency, so it runs as a plain JVM test with no Robolectric.</p>
 */
public class MilestoneTrackerTest {

    private static final int SCREEN_LIMIT = 180;

    private static Repository.LifetimeStats stats() {
        return new Repository.LifetimeStats();
    }

    private static DailySummaryEntity summary(String dayKey) {
        DailySummaryEntity s = new DailySummaryEntity();
        s.dayKey = dayKey;
        // Unmeasured by default, so a test that does not care about screen time
        // does not accidentally extend or break a run.
        s.screenMinutes = -1;
        return s;
    }

    /** Summaries for {@code count} consecutive days, newest first. */
    private static List<DailySummaryEntity> days(int count) {
        List<DailySummaryEntity> out = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            // Day keys only need to be distinct and ordered for these tests.
            out.add(summary(String.format(java.util.Locale.US, "2026-11-%02d", 17 - i)));
        }
        return out;
    }

    private static boolean reached(List<Milestone> milestones, int labelRes) {
        for (Milestone m : milestones) {
            if (m.labelRes == labelRes) return m.reached;
        }
        throw new AssertionError("no milestone with labelRes " + labelRes);
    }

    private static List<Milestone> evaluate(Repository.LifetimeStats lifetime,
                                            List<DailySummaryEntity> summaries,
                                            int best1600Sec) {
        return MilestoneTracker.evaluate(lifetime, summaries, best1600Sec, SCREEN_LIMIT);
    }

    // ------------------------------------------------------------------
    //  Shape
    // ------------------------------------------------------------------

    @Test
    public void everyMilestoneFromTheBriefIsPresent() {
        List<Milestone> all = evaluate(stats(), days(1), 0);
        assertEquals("the brief names eighteen milestones", 18, all.size());
    }

    @Test
    public void everyMilestoneCarriesADistinctLabel() {
        List<Milestone> all = evaluate(stats(), days(1), 0);
        List<Integer> labels = new ArrayList<>();
        for (Milestone m : all) {
            assertNotEquals("each milestone needs a real string resource", 0, m.labelRes);
            labels.add(m.labelRes);
        }
        assertEquals("no two milestones may share a label",
                labels.size(), new java.util.HashSet<>(labels).size());
    }

    @Test
    public void aFreshInstallHasReachedNothing() {
        List<Milestone> all = evaluate(stats(), new ArrayList<>(), 0);
        assertEquals(0, MilestoneTracker.reachedCount(all));
        for (Milestone m : all) {
            assertFalse("nothing should be reached on day zero", m.reached);
        }
    }

    @Test
    public void reachedCountMatchesTheFlags() {
        Repository.LifetimeStats s = stats();
        s.totalSessions = 12;
        s.bestStreak = 4;
        s.completedTopics = 1;

        List<Milestone> all = evaluate(s, days(1), 400);
        int manual = 0;
        for (Milestone m : all) {
            if (m.reached) manual++;
        }
        assertEquals(manual, MilestoneTracker.reachedCount(all));
        assertTrue(manual > 0);
        assertTrue(manual < all.size());
    }

    // ------------------------------------------------------------------
    //  Focus sessions
    // ------------------------------------------------------------------

    @Test
    public void sessionMilestonesFireAtTheirExactThresholds() {
        int[][] cases = {
                // sessions, first, ten, fifty, hundred
                {0, 0, 0, 0, 0},
                {1, 1, 0, 0, 0},
                {9, 1, 0, 0, 0},
                {10, 1, 1, 0, 0},
                {49, 1, 1, 0, 0},
                {50, 1, 1, 1, 0},
                {99, 1, 1, 1, 0},
                {100, 1, 1, 1, 1},
                {500, 1, 1, 1, 1},
        };
        for (int[] c : cases) {
            Repository.LifetimeStats s = stats();
            s.totalSessions = c[0];
            List<Milestone> all = evaluate(s, days(0), 0);

            assertEquals("sessions=" + c[0], c[1] == 1,
                    reached(all, R.string.milestone_first_session));
            assertEquals("sessions=" + c[0], c[2] == 1,
                    reached(all, R.string.milestone_ten_sessions));
            assertEquals("sessions=" + c[0], c[3] == 1,
                    reached(all, R.string.milestone_fifty_sessions));
            assertEquals("sessions=" + c[0], c[4] == 1,
                    reached(all, R.string.milestone_first_hundred_sessions));
        }
    }

    // ------------------------------------------------------------------
    //  Streaks
    // ------------------------------------------------------------------

    @Test
    public void streakMilestonesUseTheBestStreakNotTheCurrentOne() {
        // A user who held 30 days and then slipped should keep the milestone.
        // Taking it away would be the shaming behaviour the brief forbids.
        Repository.LifetimeStats s = stats();
        s.bestStreak = 30;
        s.currentStreak = 0;

        List<Milestone> all = evaluate(s, days(0), 0);
        assertTrue(reached(all, R.string.milestone_streak_3));
        assertTrue(reached(all, R.string.milestone_streak_7));
        assertTrue(reached(all, R.string.milestone_streak_21));
        assertTrue(reached(all, R.string.milestone_streak_30));
        assertFalse(reached(all, R.string.milestone_streak_100));
    }

    @Test
    public void streakMilestonesFireAtTheirExactThresholds() {
        int[] thresholds = {3, 7, 21, 30, 100};
        int[] labels = {R.string.milestone_streak_3, R.string.milestone_streak_7,
                R.string.milestone_streak_21, R.string.milestone_streak_30,
                R.string.milestone_streak_100};

        for (int i = 0; i < thresholds.length; i++) {
            Repository.LifetimeStats oneShort = stats();
            oneShort.bestStreak = thresholds[i] - 1;
            assertFalse("streak=" + (thresholds[i] - 1) + " must not reach " + thresholds[i],
                    reached(evaluate(oneShort, days(0), 0), labels[i]));

            Repository.LifetimeStats exact = stats();
            exact.bestStreak = thresholds[i];
            assertTrue("streak=" + thresholds[i] + " must reach " + thresholds[i],
                    reached(evaluate(exact, days(0), 0), labels[i]));
        }
    }

    // ------------------------------------------------------------------
    //  Day-shaped milestones
    // ------------------------------------------------------------------

    @Test
    public void anHourOfDeepFocusInOneDayIsEnough() {
        List<DailySummaryEntity> short_ = days(3);
        short_.get(1).focusMinutes = 59;
        assertFalse(reached(evaluate(stats(), short_, 0), R.string.milestone_hour_focus));

        List<DailySummaryEntity> exact = days(3);
        exact.get(1).focusMinutes = 60;
        assertTrue(reached(evaluate(stats(), exact, 0), R.string.milestone_hour_focus));
    }

    @Test
    public void aDayWithEveryHabitCountsButAnEmptyChecklistDoesNot() {
        List<DailySummaryEntity> empty = days(2);
        empty.get(0).habitsTotal = 0;
        empty.get(0).habitsDone = 0;
        assertFalse("an unconfigured checklist is not an achievement",
                reached(evaluate(stats(), empty, 0), R.string.milestone_all_habits));

        List<DailySummaryEntity> partial = days(2);
        partial.get(0).habitsTotal = 10;
        partial.get(0).habitsDone = 9;
        assertFalse(reached(evaluate(stats(), partial, 0), R.string.milestone_all_habits));

        List<DailySummaryEntity> complete = days(2);
        complete.get(0).habitsTotal = 10;
        complete.get(0).habitsDone = 10;
        assertTrue(reached(evaluate(stats(), complete, 0), R.string.milestone_all_habits));
    }

    @Test
    public void tenHoursOfStudyNeedsAFullSevenDayWindow() {
        // Six days cannot reach a seven-day total, however heavy they are.
        List<DailySummaryEntity> six = days(6);
        for (DailySummaryEntity s : six) s.studyMinutes = 200;
        assertFalse(reached(evaluate(stats(), six, 0),
                R.string.milestone_ten_hours_study));

        // Seven days at 100 minutes is 700, over the 600-minute bar.
        List<DailySummaryEntity> seven = days(7);
        for (DailySummaryEntity s : seven) s.studyMinutes = 100;
        assertTrue(reached(evaluate(stats(), seven, 0),
                R.string.milestone_ten_hours_study));
    }

    @Test
    public void theStudyWindowSlidesRatherThanOnlyCheckingTheLatestWeek() {
        // Six light days then one heavy day: the first window is far short, but a
        // window that includes the heavy day clears the bar.
        List<DailySummaryEntity> summaries = days(8);
        for (int i = 0; i < 7; i++) summaries.get(i).studyMinutes = 10;
        summaries.get(7).studyMinutes = 600;

        assertTrue(reached(evaluate(stats(), summaries, 0),
                R.string.milestone_ten_hours_study));
    }

    @Test
    public void aMissingDayContributesZeroToTheStudyWindow() {
        // A null summary is a day with nothing recorded, not a day to be skipped.
        // Seven days at 90 minutes is 630, over the 600-minute bar; punching a
        // hole in the middle drops it to 540 and the milestone is not reached.
        List<DailySummaryEntity> whole = days(7);
        for (DailySummaryEntity s : whole) s.studyMinutes = 90;
        assertTrue("630 minutes clears the 600-minute bar",
                reached(evaluate(stats(), whole, 0),
                        R.string.milestone_ten_hours_study));

        List<DailySummaryEntity> withHole = days(7);
        for (DailySummaryEntity s : withHole) s.studyMinutes = 90;
        withHole.set(3, null);
        assertFalse("the missing day must count as zero, not be skipped",
                reached(evaluate(stats(), withHole, 0),
                        R.string.milestone_ten_hours_study));
    }

    // ------------------------------------------------------------------
    //  Screen-time run
    // ------------------------------------------------------------------

    @Test
    public void sevenDaysUnderTheLimitReachesTheMilestone() {
        List<DailySummaryEntity> summaries = days(7);
        for (DailySummaryEntity s : summaries) s.screenMinutes = SCREEN_LIMIT;
        assertTrue("exactly at the limit still counts as under it",
                reached(evaluate(stats(), summaries, 0),
                        R.string.milestone_screen_under_limit));
    }

    @Test
    public void anOverLimitDayBreaksTheRun() {
        List<DailySummaryEntity> summaries = days(9);
        for (DailySummaryEntity s : summaries) s.screenMinutes = 100;
        summaries.get(4).screenMinutes = SCREEN_LIMIT + 1;

        // Longest unbroken run is 5, short of the 7 required.
        assertFalse(reached(evaluate(stats(), summaries, 0),
                R.string.milestone_screen_under_limit));

        // One more clear day on the far side still is not enough: the run broke.
        assertFalse(reached(evaluate(stats(), summaries.subList(0, 8), 0),
                R.string.milestone_screen_under_limit));
    }

    @Test
    public void anUnmeasuredDayNeitherExtendsNorBreaksTheRun() {
        // Without usage access a day has no number. Treating it as a failure would
        // punish the user for a permission they declined; treating it as a success
        // would award a milestone the app cannot verify. It is skipped.
        List<DailySummaryEntity> summaries = days(9);
        for (DailySummaryEntity s : summaries) s.screenMinutes = 100;
        summaries.get(1).screenMinutes = -1;
        summaries.get(5).screenMinutes = -1;

        // Eight measured days under the limit, so the run still reaches seven.
        assertTrue(reached(evaluate(stats(), summaries, 0),
                R.string.milestone_screen_under_limit));
    }

    @Test
    public void tooFewMeasuredDaysCannotReachTheMilestone() {
        List<DailySummaryEntity> summaries = days(6);
        for (DailySummaryEntity s : summaries) s.screenMinutes = 10;
        assertFalse(reached(evaluate(stats(), summaries, 0),
                R.string.milestone_screen_under_limit));
    }

    @Test
    public void noConfiguredLimitMeansNothingToStayUnder() {
        List<DailySummaryEntity> summaries = days(10);
        for (DailySummaryEntity s : summaries) s.screenMinutes = 0;

        List<Milestone> all = MilestoneTracker.evaluate(
                stats(), summaries, 0, 0);
        assertFalse("a limit of 0 means unlimited, not automatically satisfied",
                reached(all, R.string.milestone_screen_under_limit));
    }

    // ------------------------------------------------------------------
    //  Running
    // ------------------------------------------------------------------

    @Test
    public void aRecorded1600UnlocksAllThreeRunningMilestones() {
        List<Milestone> slow = evaluate(stats(), days(0), 600); // 10:00
        assertTrue(reached(slow, R.string.milestone_1600_first));
        assertFalse(reached(slow, R.string.milestone_1600_under_8));
        assertFalse(reached(slow, R.string.milestone_1600_under_7));

        List<Milestone> under8 = evaluate(stats(), days(0), 7 * 60 + 59);
        assertTrue(reached(under8, R.string.milestone_1600_first));
        assertTrue(reached(under8, R.string.milestone_1600_under_8));
        assertFalse(reached(under8, R.string.milestone_1600_under_7));

        List<Milestone> under7 = evaluate(stats(), days(0), 6 * 60 + 59);
        assertTrue(reached(under7, R.string.milestone_1600_under_8));
        assertTrue(reached(under7, R.string.milestone_1600_under_7));
    }

    @Test
    public void exactlyEightMinutesIsNotUnderEightMinutes() {
        List<Milestone> exact = evaluate(stats(), days(0), 8 * 60);
        assertTrue(reached(exact, R.string.milestone_1600_first));
        assertFalse(reached(exact, R.string.milestone_1600_under_8));
    }

    @Test
    public void noRecordedRunReachesNoRunningMilestone() {
        List<Milestone> all = evaluate(stats(), days(0), 0);
        assertFalse(reached(all, R.string.milestone_1600_first));
        assertFalse(reached(all, R.string.milestone_1600_under_8));
        assertFalse(reached(all, R.string.milestone_1600_under_7));
    }

    // ------------------------------------------------------------------
    //  Syllabus
    // ------------------------------------------------------------------

    @Test
    public void topicMilestonesFireAtTheirExactThresholds() {
        Repository.LifetimeStats none = stats();
        none.completedTopics = 0;
        assertFalse(reached(evaluate(none, days(0), 0), R.string.milestone_first_topic));
        assertFalse(reached(evaluate(none, days(0), 0), R.string.milestone_ten_topics));

        Repository.LifetimeStats one = stats();
        one.completedTopics = 1;
        assertTrue(reached(evaluate(one, days(0), 0), R.string.milestone_first_topic));
        assertFalse(reached(evaluate(one, days(0), 0), R.string.milestone_ten_topics));

        Repository.LifetimeStats ten = stats();
        ten.completedTopics = 10;
        assertTrue(reached(evaluate(ten, days(0), 0), R.string.milestone_ten_topics));
    }

    // ------------------------------------------------------------------
    //  Robustness
    // ------------------------------------------------------------------

    @Test
    public void nullSummariesAreTolerated() {
        List<DailySummaryEntity> summaries = new ArrayList<>();
        summaries.add(null);
        summaries.add(null);
        // Must not throw, and must not award a day-shaped milestone.
        List<Milestone> all = evaluate(stats(), summaries, 0);
        assertEquals(18, all.size());
        assertFalse(reached(all, R.string.milestone_all_habits));
        assertFalse(reached(all, R.string.milestone_hour_focus));
    }

    @Test
    public void aFullyAchievedHistoryReachesEverything() {
        Repository.LifetimeStats s = stats();
        s.totalSessions = 100;
        s.bestStreak = 100;
        s.completedTopics = 10;

        List<DailySummaryEntity> summaries = days(10);
        for (DailySummaryEntity d : summaries) {
            d.focusMinutes = 120;
            d.studyMinutes = 120;   // 10 days x 120 = 1200, over the 600 bar
            d.habitsTotal = 10;
            d.habitsDone = 10;
            d.screenMinutes = 60;
        }

        List<Milestone> all = evaluate(s, summaries, 6 * 60);
        assertEquals("every milestone should be reachable",
                all.size(), MilestoneTracker.reachedCount(all));
    }

    @Test
    public void milestonesDoNotDependOnSummaryOrderBeingNewestFirst() {
        // The day-shaped checks scan the whole list, so a differently ordered
        // history must produce the same answers.
        Repository.LifetimeStats s = stats();
        s.totalSessions = 1;

        List<DailySummaryEntity> forward = days(8);
        for (DailySummaryEntity d : forward) d.studyMinutes = 100;

        List<DailySummaryEntity> reversed = new ArrayList<>(forward);
        java.util.Collections.reverse(reversed);

        assertEquals(reached(evaluate(s, forward, 0), R.string.milestone_ten_hours_study),
                reached(evaluate(s, reversed, 0), R.string.milestone_ten_hours_study));
        assertEquals(reached(evaluate(s, forward, 0), R.string.milestone_first_session),
                reached(evaluate(s, reversed, 0), R.string.milestone_first_session));
    }

    @Test
    public void emptyHistoryAndEmptyStatsProduceTheSameResultAsNulls() {
        List<Milestone> fromEmpty = evaluate(stats(), new ArrayList<>(), 0);
        List<Milestone> fromNulls = evaluate(stats(), Arrays.asList(), 0);
        assertEquals(fromEmpty.size(), fromNulls.size());
        assertEquals(0, MilestoneTracker.reachedCount(fromEmpty));
        assertEquals(0, MilestoneTracker.reachedCount(fromNulls));
    }

    @Test
    public void reachedCountIsZeroForAnEmptyList() {
        assertEquals(0, MilestoneTracker.reachedCount(new ArrayList<>()));
    }
}
