package com.focuslife.app.util;

/**
 * Spaced-repetition scheduling for syllabus revision.
 *
 * <p>Pure arithmetic so it is covered by JVM unit tests. The intervals are the
 * classic expanding schedule — a topic returns after 1 day, then 3, then 7, 15
 * and 30 — which matches how exam syllabi are actually revised: frequently at
 * first, then only to keep it alive.</p>
 *
 * <p>Deliberately not a full SM-2 algorithm. SM-2 needs per-item difficulty and
 * quality ratings to be meaningful, and asking a student to grade their recall on
 * a five-point scale for every chapter is enough friction that they stop
 * revising at all. A fixed expanding schedule needs no input and still captures
 * most of the benefit.</p>
 */
public final class RevisionSchedule {

    private RevisionSchedule() {
    }

    /** Days after the previous revision at which the next one is due. */
    public static final int[] INTERVALS_DAYS = {1, 3, 7, 15, 30};

    /**
     * Epoch millis at which the next revision becomes due.
     *
     * @param revisionsDone how many revisions have already been completed
     * @param fromMillis    the reference instant, normally now
     */
    public static long nextRevisionAt(int revisionsDone, long fromMillis) {
        int index = revisionsDone < 0 ? 0 : revisionsDone;
        if (index >= INTERVALS_DAYS.length) {
            // Past the end of the schedule: keep repeating the longest interval
            // rather than declaring the topic permanently revised, because a
            // topic dropped from the schedule is a topic forgotten.
            index = INTERVALS_DAYS.length - 1;
        }
        return fromMillis + INTERVALS_DAYS[index] * TimeUtils.DAY_MS;
    }

    /** The interval, in days, that produced {@code nextRevisionAt}. */
    public static int intervalDaysFor(int revisionsDone) {
        int index = revisionsDone < 0 ? 0 : Math.min(revisionsDone, INTERVALS_DAYS.length - 1);
        return INTERVALS_DAYS[index];
    }

    /**
     * Days until a revision is due; negative when it is overdue.
     *
     * @return {@link Integer#MAX_VALUE} when no revision is scheduled, so callers
     *         can sort scheduled items before unscheduled ones without a special
     *         case.
     */
    public static int daysUntil(long nextRevisionAt, long nowMillis) {
        if (nextRevisionAt <= 0) return Integer.MAX_VALUE;
        long diff = nextRevisionAt - nowMillis;
        // Round toward zero so "due in 12 hours" reads as 0 days rather than 1.
        return (int) (diff / TimeUtils.DAY_MS);
    }

    public static boolean isDue(long nextRevisionAt, long nowMillis) {
        return nextRevisionAt > 0 && nextRevisionAt <= nowMillis;
    }

    /**
     * Whether the schedule is exhausted, meaning the topic is on its permanent
     * long-interval cycle.
     */
    public static boolean isFullySpaced(int revisionsDone) {
        return revisionsDone >= INTERVALS_DAYS.length;
    }

    /** Total number of scheduled passes before the long-interval cycle begins. */
    public static int totalScheduledPasses() {
        return INTERVALS_DAYS.length;
    }
}
