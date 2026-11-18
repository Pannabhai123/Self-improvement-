package com.focuslife.app.util;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * Spaced repetition for syllabus revision.
 *
 * <p>The property that matters most is the tail of the schedule: a topic that has
 * been revised five times must keep coming back on the longest interval rather
 * than dropping off entirely. A topic removed from the schedule is a topic
 * forgotten, which for an exam syllabus is the exact failure mode the feature
 * exists to prevent. That is asserted explicitly below.</p>
 */
public class RevisionScheduleTest {

    /** A fixed reference instant, so expectations do not move with the clock. */
    private static final long T = 1_800_000_000_000L;

    private static final long DAY = TimeUtils.DAY_MS;

    // ------------------------------------------------------------------
    //  The interval table
    // ------------------------------------------------------------------

    @Test
    public void intervalsAreTheExpandingScheduleFromTheBrief() {
        int[] expected = {1, 3, 7, 15, 30};
        assertEquals(expected.length, RevisionSchedule.INTERVALS_DAYS.length);
        for (int i = 0; i < expected.length; i++) {
            assertEquals("interval " + i, expected[i], RevisionSchedule.INTERVALS_DAYS[i]);
        }
    }

    @Test
    public void intervalsStrictlyExpand() {
        int[] intervals = RevisionSchedule.INTERVALS_DAYS;
        for (int i = 1; i < intervals.length; i++) {
            assertTrue("each interval must be longer than the one before",
                    intervals[i] > intervals[i - 1]);
        }
    }

    @Test
    public void totalScheduledPassesMatchesTheTable() {
        assertEquals(RevisionSchedule.INTERVALS_DAYS.length,
                RevisionSchedule.totalScheduledPasses());
        assertEquals(5, RevisionSchedule.totalScheduledPasses());
    }

    // ------------------------------------------------------------------
    //  intervalDaysFor
    // ------------------------------------------------------------------

    @Test
    public void intervalForEachPassCount() {
        assertEquals(1, RevisionSchedule.intervalDaysFor(0));
        assertEquals(3, RevisionSchedule.intervalDaysFor(1));
        assertEquals(7, RevisionSchedule.intervalDaysFor(2));
        assertEquals(15, RevisionSchedule.intervalDaysFor(3));
        assertEquals(30, RevisionSchedule.intervalDaysFor(4));
    }

    @Test
    public void intervalPastTheEndOfTheScheduleRepeatsTheLongest() {
        assertEquals(30, RevisionSchedule.intervalDaysFor(5));
        assertEquals(30, RevisionSchedule.intervalDaysFor(9));
        assertEquals(30, RevisionSchedule.intervalDaysFor(1000));
    }

    @Test
    public void negativePassCountIsTreatedAsTheFirstInterval() {
        // A corrupted or uninitialised counter must not produce a negative gap,
        // which would schedule the revision in the past forever.
        assertEquals(1, RevisionSchedule.intervalDaysFor(-1));
        assertEquals(1, RevisionSchedule.intervalDaysFor(-99));
    }

    // ------------------------------------------------------------------
    //  nextRevisionAt
    // ------------------------------------------------------------------

    @Test
    public void nextRevisionIsExactlyOneIntervalAway() {
        assertEquals(T + 1 * DAY, RevisionSchedule.nextRevisionAt(0, T));
        assertEquals(T + 3 * DAY, RevisionSchedule.nextRevisionAt(1, T));
        assertEquals(T + 7 * DAY, RevisionSchedule.nextRevisionAt(2, T));
        assertEquals(T + 15 * DAY, RevisionSchedule.nextRevisionAt(3, T));
        assertEquals(T + 30 * DAY, RevisionSchedule.nextRevisionAt(4, T));
    }

    @Test
    public void nextRevisionNeverStopsBeingScheduled() {
        // The core guarantee: the schedule is exhausted but the topic is not.
        for (int passes = 5; passes < 200; passes++) {
            long next = RevisionSchedule.nextRevisionAt(passes, T);
            assertTrue("pass " + passes + " must still schedule a revision",
                    next > T);
            assertEquals(T + 30 * DAY, next);
        }
    }

    @Test
    public void nextRevisionNeverMovesBackwardsAsPassesIncrease() {
        long previous = RevisionSchedule.nextRevisionAt(0, T);
        for (int passes = 1; passes <= 50; passes++) {
            long next = RevisionSchedule.nextRevisionAt(passes, T);
            assertTrue("pass " + passes + " must not shorten the interval",
                    next >= previous);
            previous = next;
        }
    }

    @Test
    public void negativePassCountStillSchedulesTheFirstInterval() {
        assertEquals(T + DAY, RevisionSchedule.nextRevisionAt(-5, T));
    }

    @Test
    public void nextRevisionAgreesWithIntervalDaysFor() {
        for (int passes = 0; passes <= 40; passes++) {
            long expected = T + RevisionSchedule.intervalDaysFor(passes) * DAY;
            assertEquals("pass " + passes,
                    expected, RevisionSchedule.nextRevisionAt(passes, T));
        }
    }

    // ------------------------------------------------------------------
    //  daysUntil
    // ------------------------------------------------------------------

    @Test
    public void daysUntilIsWholeDaysAhead() {
        assertEquals(1, RevisionSchedule.daysUntil(T + DAY, T));
        assertEquals(3, RevisionSchedule.daysUntil(T + 3 * DAY, T));
        assertEquals(30, RevisionSchedule.daysUntil(T + 30 * DAY, T));
    }

    @Test
    public void daysUntilIsNegativeWhenOverdue() {
        assertEquals(-3, RevisionSchedule.daysUntil(T - 3 * DAY, T));
    }

    @Test
    public void daysUntilTruncatesTowardZeroSoTwelveHoursReadsAsToday() {
        // "Due in 12 hours" should read as 0 days, not 1 — and the symmetric
        // overdue case reads as 0 too, because it is still the same day.
        assertEquals(0, RevisionSchedule.daysUntil(T + DAY / 2, T));
        assertEquals(0, RevisionSchedule.daysUntil(T - DAY / 2, T));
    }

    @Test
    public void unscheduledRevisionSortsLast() {
        // MAX_VALUE rather than 0 or -1, so a topic with no revision pending never
        // appears ahead of one that is due.
        assertEquals(Integer.MAX_VALUE, RevisionSchedule.daysUntil(0L, T));
        assertEquals(Integer.MAX_VALUE, RevisionSchedule.daysUntil(-1L, T));
        assertTrue(RevisionSchedule.daysUntil(0L, T)
                > RevisionSchedule.daysUntil(T + 30 * DAY, T));
    }

    // ------------------------------------------------------------------
    //  isDue
    // ------------------------------------------------------------------

    @Test
    public void dueExactlyAtTheScheduledInstant() {
        assertTrue(RevisionSchedule.isDue(T, T));
    }

    @Test
    public void overdueIsDue() {
        assertTrue(RevisionSchedule.isDue(T - 1, T));
        assertTrue(RevisionSchedule.isDue(T - 30 * DAY, T));
    }

    @Test
    public void futureRevisionIsNotDue() {
        assertFalse(RevisionSchedule.isDue(T + 1, T));
        assertFalse(RevisionSchedule.isDue(T + DAY, T));
    }

    @Test
    public void nothingScheduledIsNeverDue() {
        // Without this, every topic on a fresh install would show a revision badge.
        assertFalse(RevisionSchedule.isDue(0L, T));
        assertFalse(RevisionSchedule.isDue(-1L, T));
    }

    @Test
    public void aRevisionBecomesDueExactlyWhenNextRevisionAtSaysItShould() {
        for (int passes = 0; passes <= 10; passes++) {
            long due = RevisionSchedule.nextRevisionAt(passes, T);
            assertFalse("not due one millisecond early (pass " + passes + ")",
                    RevisionSchedule.isDue(due, due - 1));
            assertTrue("due at the scheduled instant (pass " + passes + ")",
                    RevisionSchedule.isDue(due, due));
        }
    }

    // ------------------------------------------------------------------
    //  isFullySpaced
    // ------------------------------------------------------------------

    @Test
    public void fullySpacedOnlyAfterEveryScheduledPass() {
        assertFalse(RevisionSchedule.isFullySpaced(0));
        assertFalse(RevisionSchedule.isFullySpaced(4));
        assertTrue(RevisionSchedule.isFullySpaced(5));
        assertTrue(RevisionSchedule.isFullySpaced(50));
        assertFalse("a negative counter is not a completed schedule",
                RevisionSchedule.isFullySpaced(-1));
    }

    @Test
    public void fullySpacedTopicsStillGetAFutureRevision() {
        // Being fully spaced must not mean being dropped. This is the pair with
        // nextRevisionNeverStopsBeingScheduled, checked from the flag's side.
        assertTrue(RevisionSchedule.isFullySpaced(7));
        assertTrue(RevisionSchedule.nextRevisionAt(7, T) > T);
    }
}
