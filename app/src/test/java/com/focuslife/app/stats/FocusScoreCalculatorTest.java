package com.focuslife.app.stats;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.focuslife.app.stats.FocusScoreCalculator.Day;
import com.focuslife.app.stats.FocusScoreCalculator.Improvement;
import com.focuslife.app.stats.FocusScoreCalculator.Result;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * The Focus Score is the number the whole app is judged on, so its guarantees are
 * pinned down here rather than left to manual checking.
 *
 * <p>The properties that matter are not "the arithmetic is right" but the three
 * promises the brief makes about it: the score is additive and never punitive, a
 * target the user never set cannot cap their score, and an unmeasurable component
 * is dropped rather than scored as a failure. Each has a test below.</p>
 *
 * <p>Note that {@code new Day()} is deliberately <em>not</em> a blank day: it
 * carries the seeded defaults (360 study minutes, 4 sessions, 1600 m, 3 workouts,
 * 180 screen minutes). {@link #emptyDay()} exists so a test can start from a day
 * where genuinely nothing is configured.</p>
 */
public class FocusScoreCalculatorTest {

    /** A day with every target switched off, so nothing is applicable. */
    private static Day emptyDay() {
        Day d = new Day();
        d.studyTargetMinutes = 0;
        d.focusSessionTarget = 0;
        d.habitsTotal = 0;
        d.tasksTotal = 0;
        d.runTargetM = 0;
        d.workoutTarget = 0;
        d.screenLimitMinutes = 0;
        d.urgesTotal = 0;
        return d;
    }

    /** A day where every applicable component is fully satisfied. */
    private static Day perfectDay() {
        Day d = new Day();
        d.studyMinutes = 360;
        d.focusSessionsCompleted = 4;
        d.habitsDone = 10;
        d.habitsTotal = 10;
        d.tasksDone = 5;
        d.tasksTotal = 5;
        d.runDistanceM = 1600;
        d.workoutsDone = 3;
        d.screenMinutes = 120;
        d.urgesResisted = 5;
        d.urgesTotal = 5;
        return d;
    }

    // ------------------------------------------------------------------
    //  Weights
    // ------------------------------------------------------------------

    @Test
    public void weightsTotalOneHundred() {
        int sum = FocusScoreCalculator.W_STUDY
                + FocusScoreCalculator.W_FOCUS_SESSIONS
                + FocusScoreCalculator.W_HABITS
                + FocusScoreCalculator.W_TASKS
                + FocusScoreCalculator.W_FITNESS
                + FocusScoreCalculator.W_SCREEN_TIME
                + FocusScoreCalculator.W_DISTRACTION;
        assertEquals("component weights must total 100 so a full day scores 100",
                100, sum);
        assertEquals(sum, FocusScoreCalculator.WEIGHT_TOTAL);
    }

    // ------------------------------------------------------------------
    //  Endpoints
    // ------------------------------------------------------------------

    @Test
    public void perfectDayScoresOneHundred() {
        Result r = FocusScoreCalculator.compute(perfectDay());
        assertEquals(100, r.score);
        assertEquals(FocusScoreCalculator.WEIGHT_TOTAL, r.applicableWeight);
        assertEquals(100, FocusScoreCalculator.progressPercent(r));
    }

    @Test
    public void freshInstallWithNothingConfiguredScoresZeroNotOneHundred() {
        // A brand-new install has recorded nothing. Reporting 100 would be a lie;
        // reporting 0 with an empty state that explains how to start is honest.
        Result r = FocusScoreCalculator.compute(emptyDay());
        assertEquals(0, r.score);
        assertEquals(0, r.applicableWeight);
    }

    @Test
    public void nullDayScoresZeroBecauseNothingIsApplicable() {
        // A null Day means no data was measured. It must not be treated as a blank
        // day carrying the seeded default targets, because those defaults make
        // screen time look measured - and zero minutes then earns full marks for a
        // day that never happened.
        Result r = FocusScoreCalculator.compute(null);
        assertNotNull(r);
        assertEquals(0, r.score);
        assertEquals(0, r.applicableWeight);
        assertEquals(0, r.screenPoints);
        assertNull("no component was applicable, so there is nothing to improve",
                r.topImprovement);
    }

    // ------------------------------------------------------------------
    //  Additive, never punitive
    // ------------------------------------------------------------------

    @Test
    public void aTerribleDayFloorsAtZeroAndNeverGoesNegative() {
        Day d = new Day();
        d.studyMinutes = 0;
        d.focusSessionsCompleted = 0;
        d.habitsTotal = 0;
        d.tasksTotal = 0;
        d.runDistanceM = 0;
        d.workoutsDone = 0;
        // Far past every limit, plus time explicitly recorded as lost.
        d.screenMinutes = 100_000;
        d.distractionMinutesLost = 999;
        d.blockHits = 500;
        d.urgesResisted = 0;
        d.urgesTotal = 10;

        Result r = FocusScoreCalculator.compute(d);
        assertEquals("the worst possible day must floor at zero", 0, r.score);
        assertTrue(r.score >= 0);
    }

    @Test
    public void overAchievingOneComponentCannotPushPastOneHundred() {
        Day d = emptyDay();
        d.studyTargetMinutes = 60;
        d.studyMinutes = 600; // ten times the target
        Result r = FocusScoreCalculator.compute(d);
        assertEquals(100, r.score);
        // The point of the cap: the component earns its weight and no more, so ten
        // times the target is 26 points rather than 260. Without that, one
        // component could carry the whole score and hide the rest of the day.
        assertEquals(FocusScoreCalculator.W_STUDY, r.studyPoints);
        assertEquals(FocusScoreCalculator.W_STUDY, r.applicableWeight);
    }

    @Test
    public void partialProgressStillRegistersInsteadOfTruncatingToZero() {
        // One habit out of ten must not round down to no credit at all, or the
        // ring would look stuck for the first several days of use.
        Day d = emptyDay();
        d.habitsTotal = 10;
        d.habitsDone = 1;
        Result r = FocusScoreCalculator.compute(d);
        assertTrue("one of ten habits should earn some points", r.habitPoints > 0);
        assertTrue(r.habitPoints < FocusScoreCalculator.W_HABITS);
    }

    // ------------------------------------------------------------------
    //  Unset targets are skipped and the score renormalised
    // ------------------------------------------------------------------

    @Test
    public void aUserWithOnlyHabitsConfiguredCanStillReachOneHundred() {
        Day d = emptyDay();
        d.habitsTotal = 10;
        d.habitsDone = 10;

        Result r = FocusScoreCalculator.compute(d);
        assertEquals(FocusScoreCalculator.W_HABITS, r.applicableWeight);
        assertEquals("an unset running target must not cap the score at 88",
                100, r.score);
    }

    @Test
    public void restDayAwardsFullFitnessWeight() {
        Day rested = emptyDay();
        rested.runTargetM = 1600;
        rested.workoutTarget = 3;
        rested.restDay = true;

        Day notRested = emptyDay();
        notRested.runTargetM = 1600;
        notRested.workoutTarget = 3;

        Result restResult = FocusScoreCalculator.compute(rested);
        Result activeResult = FocusScoreCalculator.compute(notRested);

        assertEquals(FocusScoreCalculator.W_FITNESS, restResult.fitnessPoints);
        assertEquals(100, restResult.score);
        assertEquals("a rest day with nothing logged is a completed plan",
                0, activeResult.fitnessPoints);
        assertTrue(restResult.score > activeResult.score);
    }

    @Test
    public void unmeasurableScreenTimeIsDroppedRatherThanScoredAsFailure() {
        Day measured = emptyDay();
        measured.studyTargetMinutes = 360;
        measured.studyMinutes = 180;
        measured.screenLimitMinutes = 180;
        measured.screenMinutes = 360; // twice the limit

        Day unknown = emptyDay();
        unknown.studyTargetMinutes = 360;
        unknown.studyMinutes = 180;
        unknown.screenLimitMinutes = 180;
        unknown.screenMinutes = 360;
        unknown.screenTimeUnknown = true;

        Result measuredResult = FocusScoreCalculator.compute(measured);
        Result unknownResult = FocusScoreCalculator.compute(unknown);

        // The measured day is scored on 26 + 8 weight and earns 13 of it.
        assertEquals(34, measuredResult.applicableWeight);
        assertEquals(38, measuredResult.score);

        // Without usage access the component is removed from the denominator
        // entirely, so the same study effort scores higher rather than being
        // dragged down by a number the app could never read.
        assertEquals(26, unknownResult.applicableWeight);
        assertEquals(50, unknownResult.score);
        assertTrue(unknownResult.score > measuredResult.score);
    }

    // ------------------------------------------------------------------
    //  Improvement advice
    // ------------------------------------------------------------------

    @Test
    public void perfectDayHasNoImprovementToSuggest() {
        Result r = FocusScoreCalculator.compute(perfectDay());
        assertNull(r.topImprovement);
    }

    @Test
    public void improvementAdviceNeverNamesAComponentTheUserHasNotConfigured() {
        // Regression guard: pickTop used to see `weight - 0` points available on
        // every skipped component, so a day with only a study target advised
        // completing habits that did not exist.
        Day d = emptyDay();
        d.studyTargetMinutes = 360;
        d.studyMinutes = 0;

        Result r = FocusScoreCalculator.compute(d);
        Improvement top = r.topImprovement;

        assertNotNull(top);
        assertEquals(FocusScoreCalculator.KEY_STUDY, top.key);
        assertEquals(FocusScoreCalculator.W_STUDY, top.availablePoints);
        assertEquals(360, top.remainingUnits);
        assertEquals(FocusScoreCalculator.UNIT_MINUTES, top.unitKey);
    }

    @Test
    public void improvementAdvicePicksTheLargestAvailableGain() {
        Day d = new Day();
        d.studyMinutes = 350;          // 10 of 360 short
        d.focusSessionsCompleted = 4;  // done
        d.habitsDone = 0;              // all 10 outstanding
        d.habitsTotal = 10;
        d.tasksDone = 5;               // done
        d.tasksTotal = 5;
        d.runDistanceM = 1600;         // done
        d.workoutsDone = 3;            // done
        d.screenMinutes = 0;           // under the limit
        d.urgesTotal = 0;              // not applicable

        Result r = FocusScoreCalculator.compute(d);
        assertNotNull(r.topImprovement);
        assertEquals("habits carry the largest weight, so they should lead",
                FocusScoreCalculator.KEY_HABITS, r.topImprovement.key);
    }

    // ------------------------------------------------------------------
    //  Streaks
    // ------------------------------------------------------------------

    @Test
    public void streakCountsConsecutiveQualifyingDaysEndingToday() {
        List<String> qualifying = Arrays.asList(
                "2026-11-17", "2026-11-16", "2026-11-15");
        assertEquals(3, FocusScoreCalculator.currentStreak(qualifying, "2026-11-17"));
    }

    @Test
    public void streakSurvivesAnIncompleteTodayBecauseTheDayIsNotOver() {
        // This is the forgiving rule. Breaking the streak at 9am because today has
        // not qualified yet would make the streak a source of shame.
        List<String> qualifying = Arrays.asList(
                "2026-11-16", "2026-11-15", "2026-11-14");
        assertEquals(3, FocusScoreCalculator.currentStreak(qualifying, "2026-11-17"));
    }

    @Test
    public void streakIsZeroWhenTheChainAlreadyBroke() {
        List<String> qualifying = Arrays.asList("2026-11-14", "2026-11-13");
        assertEquals(0, FocusScoreCalculator.currentStreak(qualifying, "2026-11-17"));
    }

    @Test
    public void streakIsZeroForEmptyAndNullInput() {
        assertEquals(0, FocusScoreCalculator.currentStreak(new ArrayList<>(), "2026-11-17"));
        assertEquals(0, FocusScoreCalculator.currentStreak(null, "2026-11-17"));
        assertEquals(0, FocusScoreCalculator.currentStreak(
                Arrays.asList("2026-11-17"), null));
    }

    @Test
    public void bestStreakFindsTheLongestRunEvenWhenItIsNotCurrent() {
        List<String> qualifying = Arrays.asList(
                "2026-11-19", "2026-11-18", "2026-11-17",
                "2026-11-15", "2026-11-14");
        assertEquals(3, FocusScoreCalculator.bestStreak(qualifying));
    }

    @Test
    public void bestStreakIgnoresDuplicateDayKeys() {
        List<String> qualifying = Arrays.asList(
                "2026-11-17", "2026-11-17", "2026-11-16");
        assertEquals(2, FocusScoreCalculator.bestStreak(qualifying));
    }

    @Test
    public void bestStreakIsZeroForEmptyInput() {
        assertEquals(0, FocusScoreCalculator.bestStreak(new ArrayList<>()));
        assertEquals(0, FocusScoreCalculator.bestStreak(null));
    }

    @Test
    public void singleQualifyingDayIsAStreakOfOne() {
        assertEquals(1, FocusScoreCalculator.bestStreak(Arrays.asList("2026-11-17")));
    }

    // ------------------------------------------------------------------
    //  Helpers
    // ------------------------------------------------------------------

    @Test
    public void clampBoundsBothEnds() {
        assertEquals(100, FocusScoreCalculator.clamp(150, 0, 100));
        assertEquals(0, FocusScoreCalculator.clamp(-5, 0, 100));
        assertEquals(50, FocusScoreCalculator.clamp(50, 0, 100));
    }

    @Test
    public void streakThresholdIsBelowAPerfectDayAndAboveAnEmptyOne() {
        int threshold = FocusScoreCalculator.STREAK_THRESHOLD;
        assertTrue(threshold > 0);
        assertTrue(threshold < 100);
        assertTrue(FocusScoreCalculator.compute(perfectDay()).score >= threshold);
        assertTrue(FocusScoreCalculator.compute(emptyDay()).score < threshold);
    }
}
