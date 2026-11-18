package com.focuslife.app.blocking;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.focuslife.app.data.db.entity.BlockScheduleEntity;
import com.focuslife.app.data.db.entity.BlockedAppEntity;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Collections;
import java.util.List;

/**
 * The blocking decision is the one piece of this app that can stand between a
 * user and their own phone, so it is tested for what it must <em>allow</em> at
 * least as carefully as for what it must block.
 *
 * <p>Two invariants dominate everything below:</p>
 * <ol>
 *   <li><b>Fail open.</b> A null snapshot, a null package, an unknown mode or a
 *       malformed schedule must never produce a block. Locking a user out of
 *       their phone because of a bug is worse than letting one distraction
 *       through.</li>
 *   <li><b>Safety first.</b> The dialer, SMS, system UI, the permission
 *       controllers and this app itself are never blocked, even when the user has
 *       explicitly added them to the block list with "always" and "strict" on.
 *       An app that could block an emergency call would be dangerous.</li>
 * </ol>
 */
public class BlockDecisionEngineTest {

    private static final String INSTAGRAM = "com.instagram.android";
    private static final String TODAY = "2026-11-17";

    /** 10:00 on a Tuesday — inside a typical study window, outside sleep. */
    private static final int MINUTE_10AM = 10 * 60;
    private static final int TUESDAY = Calendar.TUESDAY;

    private static BlockedAppEntity app(String pkg) {
        BlockedAppEntity a = new BlockedAppEntity(pkg, pkg);
        // A fresh entity blocks in every mode by default; most tests want that.
        return a;
    }

    private static BlockDecisionEngine.Snapshot snapshot(List<BlockedAppEntity> apps,
                                                         List<BlockScheduleEntity> schedules,
                                                         String mode,
                                                         String unlockedPkg,
                                                         boolean unlockActive) {
        return new BlockDecisionEngine.Snapshot(apps, schedules, mode, unlockedPkg,
                unlockActive, MINUTE_10AM, TUESDAY, TODAY);
    }

    private static BlockDecisionEngine.Snapshot simple(String mode, BlockedAppEntity... apps) {
        return snapshot(Arrays.asList(apps), Collections.emptyList(), mode, null, false);
    }

    // ------------------------------------------------------------------
    //  Fail open
    // ------------------------------------------------------------------

    @Test
    public void nullSnapshotAllows() {
        BlockDecisionEngine.Decision d = BlockDecisionEngine.shouldBlock(null, INSTAGRAM);
        assertFalse(d.shouldBlock);
        assertEquals(BlockDecisionEngine.REASON_NONE, d.reason);
    }

    @Test
    public void nullOrEmptyPackageAllows() {
        BlockDecisionEngine.Snapshot s = simple(BlockDecisionEngine.MODE_STUDY, app(INSTAGRAM));
        assertFalse(BlockDecisionEngine.shouldBlock(s, null).shouldBlock);
        assertFalse(BlockDecisionEngine.shouldBlock(s, "").shouldBlock);
    }

    @Test
    public void unknownModeStringIsNormalisedToNoneAndDoesNotBlock() {
        BlockedAppEntity a = app(INSTAGRAM);
        a.blockAlways = false;
        // The helper supplies the clock and day fields; this case only cares that
        // an unrecognised mode string degrades to MODE_NONE instead of blocking.
        BlockDecisionEngine.Snapshot s = snapshot(
                Collections.singletonList(a), Collections.emptyList(),
                "not-a-real-mode", null, false);

        assertEquals(BlockDecisionEngine.MODE_NONE, s.activeMode);
        assertFalse("an unrecognised mode must not block anything by itself",
                BlockDecisionEngine.shouldBlock(s, INSTAGRAM).shouldBlock);
    }

    @Test
    public void nullCollectionsAreTolerated() {
        BlockDecisionEngine.Snapshot s = new BlockDecisionEngine.Snapshot(
                null, null, null, null, false, MINUTE_10AM, TUESDAY, null);
        assertEquals(0, s.blockedCount());
        assertEquals("", s.dayKey);
        assertFalse(BlockDecisionEngine.shouldBlock(s, INSTAGRAM).shouldBlock);
    }

    // ------------------------------------------------------------------
    //  Safety allow-list
    // ------------------------------------------------------------------

    @Test
    public void ownPackageIsNeverBlocked() {
        assertTrue(BlockDecisionEngine.isAlwaysAllowed("com.focuslife.app"));
    }

    @Test
    public void dialerAndSmsAreNeverBlockedEvenWhenExplicitlyBlocked() {
        // The user cannot configure their way into blocking an emergency call.
        for (String pkg : new String[]{"com.google.android.dialer", "com.android.dialer",
                "com.coloros.dialer", "com.android.incallui", "com.android.mms",
                "com.google.android.apps.messaging"}) {
            assertTrue("must be on the safety allow-list: " + pkg,
                    BlockDecisionEngine.isAlwaysAllowed(pkg));

            BlockedAppEntity a = app(pkg);
            a.blockAlways = true;
            a.strict = true;
            a.dailyLimitMin = 1;
            a.usedTodayMin = 999;
            a.usageDayKey = TODAY;

            BlockDecisionEngine.Snapshot s = simple(BlockDecisionEngine.MODE_STUDY, a);
            BlockDecisionEngine.Decision d = BlockDecisionEngine.shouldBlock(s, pkg);
            assertFalse("safety package must never be blocked: " + pkg, d.shouldBlock);
        }
    }

    @Test
    public void systemUiAndSettingsAreNeverBlocked() {
        assertTrue(BlockDecisionEngine.isAlwaysAllowed("com.android.systemui"));
        assertTrue(BlockDecisionEngine.isAlwaysAllowed("android"));
        assertTrue(BlockDecisionEngine.isAlwaysAllowed("com.android.settings"));
        assertTrue(BlockDecisionEngine.isAlwaysAllowed("com.android.permissioncontroller"));
    }

    @Test
    public void oppoSpecificSafetyPackagesAreNeverBlocked() {
        // The target device is an OPPO A18, so ColorOS's own management apps must
        // stay reachable or the user cannot undo a permission from inside the app.
        assertTrue(BlockDecisionEngine.isAlwaysAllowed("com.coloros.safecenter"));
        assertTrue(BlockDecisionEngine.isAlwaysAllowed("com.coloros.phonemanager"));
        assertTrue(BlockDecisionEngine.isAlwaysAllowed("com.oppo.safe"));
    }

    @Test
    public void anOrdinarySocialAppIsNotOnTheAllowList() {
        assertFalse(BlockDecisionEngine.isAlwaysAllowed(INSTAGRAM));
    }

    // ------------------------------------------------------------------
    //  Emergency unlock
    // ------------------------------------------------------------------

    @Test
    public void liveUnlockAllowsExactlyTheUnlockedPackage() {
        BlockedAppEntity unlocked = app(INSTAGRAM);
        unlocked.blockAlways = true;
        BlockedAppEntity other = app("com.facebook.katana");
        other.blockAlways = true;

        BlockDecisionEngine.Snapshot s = snapshot(
                Arrays.asList(unlocked, other), Collections.emptyList(),
                BlockDecisionEngine.MODE_NONE, INSTAGRAM, true);

        assertFalse(BlockDecisionEngine.shouldBlock(s, INSTAGRAM).shouldBlock);
        assertTrue("the unlock must not leak to other apps",
                BlockDecisionEngine.shouldBlock(s, "com.facebook.katana").shouldBlock);
    }

    @Test
    public void expiredUnlockDoesNotAllow() {
        BlockedAppEntity a = app(INSTAGRAM);
        a.blockAlways = true;
        BlockDecisionEngine.Snapshot s = snapshot(
                Collections.singletonList(a), Collections.emptyList(),
                BlockDecisionEngine.MODE_NONE, INSTAGRAM, false);

        assertTrue("an expired unlock window must stop allowing",
                BlockDecisionEngine.shouldBlock(s, INSTAGRAM).shouldBlock);
    }

    // ------------------------------------------------------------------
    //  Block list membership
    // ------------------------------------------------------------------

    @Test
    public void unlistedPackageIsAllowed() {
        BlockDecisionEngine.Snapshot s = simple(BlockDecisionEngine.MODE_STUDY, app(INSTAGRAM));
        assertFalse(BlockDecisionEngine.shouldBlock(s, "com.some.other.app").shouldBlock);
        assertFalse(s.isOnBlockList("com.some.other.app"));
        assertTrue(s.isOnBlockList(INSTAGRAM));
        assertNull(s.app("com.some.other.app"));
        assertNotNull(s.app(INSTAGRAM));
    }

    @Test
    public void disabledAppIsNotBlocked() {
        BlockedAppEntity a = app(INSTAGRAM);
        a.enabled = false;
        a.blockAlways = true;

        BlockDecisionEngine.Snapshot s = simple(BlockDecisionEngine.MODE_STUDY, a);
        assertEquals("disabled rows must not enter the lookup map", 0, s.blockedCount());
        assertFalse(BlockDecisionEngine.shouldBlock(s, INSTAGRAM).shouldBlock);
    }

    @Test
    public void appWithEmptyPackageNameIsIgnored() {
        BlockedAppEntity blank = new BlockedAppEntity();
        blank.blockAlways = true;
        BlockDecisionEngine.Snapshot s = simple(BlockDecisionEngine.MODE_STUDY, blank);
        assertEquals(0, s.blockedCount());
    }

    @Test
    public void nullEntriesInTheListAreIgnored() {
        List<BlockedAppEntity> apps = new ArrayList<>();
        apps.add(null);
        apps.add(app(INSTAGRAM));
        BlockDecisionEngine.Snapshot s = simple(BlockDecisionEngine.MODE_STUDY,
                apps.toArray(new BlockedAppEntity[0]));
        assertEquals(1, s.blockedCount());
    }

    // ------------------------------------------------------------------
    //  Modes
    // ------------------------------------------------------------------

    @Test
    public void studyModeBlocksAnAppFlaggedForStudy() {
        BlockDecisionEngine.Snapshot s = simple(BlockDecisionEngine.MODE_STUDY, app(INSTAGRAM));
        BlockDecisionEngine.Decision d = BlockDecisionEngine.shouldBlock(s, INSTAGRAM);

        assertTrue(d.shouldBlock);
        assertEquals(BlockDecisionEngine.REASON_MODE, d.reason);
        assertEquals(BlockDecisionEngine.MODE_STUDY, d.mode);
        assertEquals(INSTAGRAM, d.blockedPackage());
    }

    @Test
    public void perAppModeFlagIsRespected() {
        BlockedAppEntity a = app(INSTAGRAM);
        a.blockInStudy = false;
        a.blockAlways = false;

        BlockDecisionEngine.Snapshot s = simple(BlockDecisionEngine.MODE_STUDY, a);
        assertFalse("opting out of Study Mode must actually opt out",
                BlockDecisionEngine.shouldBlock(s, INSTAGRAM).shouldBlock);
    }

    @Test
    public void noModeActiveDoesNotBlockByMode() {
        BlockedAppEntity a = app(INSTAGRAM);
        a.blockAlways = false;
        BlockDecisionEngine.Snapshot s = simple(BlockDecisionEngine.MODE_NONE, a);
        assertFalse(BlockDecisionEngine.shouldBlock(s, INSTAGRAM).shouldBlock);
    }

    @Test
    public void everyDeclaredModeBlocksItsOwnFlag() {
        String[] modes = {BlockDecisionEngine.MODE_FOCUS, BlockDecisionEngine.MODE_STUDY,
                BlockDecisionEngine.MODE_RUN, BlockDecisionEngine.MODE_SLEEP};
        for (String mode : modes) {
            BlockedAppEntity a = app(INSTAGRAM);
            // Turn every flag off, then enable only the one under test.
            a.blockInFocus = false;
            a.blockInStudy = false;
            a.blockInRun = false;
            a.blockInSleep = false;
            if (mode.equals(BlockDecisionEngine.MODE_FOCUS)) a.blockInFocus = true;
            if (mode.equals(BlockDecisionEngine.MODE_STUDY)) a.blockInStudy = true;
            if (mode.equals(BlockDecisionEngine.MODE_RUN)) a.blockInRun = true;
            if (mode.equals(BlockDecisionEngine.MODE_SLEEP)) a.blockInSleep = true;

            BlockDecisionEngine.Decision d =
                    BlockDecisionEngine.shouldBlock(simple(mode, a), INSTAGRAM);
            assertTrue("mode " + mode + " should block its flagged app", d.shouldBlock);
            assertEquals(BlockDecisionEngine.REASON_MODE, d.reason);
            assertEquals(mode, d.mode);
        }
    }

    // ------------------------------------------------------------------
    //  Always-on
    // ------------------------------------------------------------------

    @Test
    public void alwaysBlockWorksOutsideAnyMode() {
        BlockedAppEntity a = app(INSTAGRAM);
        a.blockAlways = true;
        BlockDecisionEngine.Snapshot s = simple(BlockDecisionEngine.MODE_NONE, a);

        BlockDecisionEngine.Decision d = BlockDecisionEngine.shouldBlock(s, INSTAGRAM);
        assertTrue(d.shouldBlock);
        assertEquals(BlockDecisionEngine.REASON_ALWAYS, d.reason);
    }

    // ------------------------------------------------------------------
    //  Schedules
    // ------------------------------------------------------------------

    private static BlockScheduleEntity schedule(int startMinute, int endMinute, int daysMask,
                                                String mode, boolean enabled) {
        BlockScheduleEntity s = new BlockScheduleEntity();
        s.label = "test";
        s.startMinute = startMinute;
        s.endMinute = endMinute;
        s.daysMask = daysMask;
        s.mode = mode;
        s.enabled = enabled;
        return s;
    }

    @Test
    public void openScheduleBlocksEvenWithNoModeActive() {
        BlockedAppEntity a = app(INSTAGRAM);
        a.blockAlways = false;
        a.blockInStudy = true;

        BlockScheduleEntity window = schedule(9 * 60, 12 * 60,
                BlockScheduleEntity.ALL_DAYS, BlockDecisionEngine.MODE_STUDY, true);

        BlockDecisionEngine.Snapshot s = snapshot(Collections.singletonList(a),
                Collections.singletonList(window), BlockDecisionEngine.MODE_NONE, null, false);

        BlockDecisionEngine.Decision d = BlockDecisionEngine.shouldBlock(s, INSTAGRAM);
        assertTrue(d.shouldBlock);
        assertEquals(BlockDecisionEngine.REASON_SCHEDULE, d.reason);
        assertEquals("the decision must report the schedule's mode",
                BlockDecisionEngine.MODE_STUDY, d.mode);
    }

    @Test
    public void closedScheduleDoesNotBlock() {
        BlockedAppEntity a = app(INSTAGRAM);
        a.blockAlways = false;

        // 13:00-15:00, and it is 10:00.
        BlockScheduleEntity window = schedule(13 * 60, 15 * 60,
                BlockScheduleEntity.ALL_DAYS, BlockDecisionEngine.MODE_STUDY, true);

        BlockDecisionEngine.Snapshot s = snapshot(Collections.singletonList(a),
                Collections.singletonList(window), BlockDecisionEngine.MODE_NONE, null, false);
        assertFalse(BlockDecisionEngine.shouldBlock(s, INSTAGRAM).shouldBlock);
    }

    @Test
    public void windowEndIsExclusive() {
        // Exactly at endMinute the window is already closed.
        BlockScheduleEntity window = schedule(9 * 60, 10 * 60,
                BlockScheduleEntity.ALL_DAYS, BlockDecisionEngine.MODE_STUDY, true);
        assertFalse(window.isOpenAt(TUESDAY, 10 * 60));
        assertTrue(window.isOpenAt(TUESDAY, 10 * 60 - 1));
        assertTrue(window.isOpenAt(TUESDAY, 9 * 60));
    }

    @Test
    public void disabledScheduleIsIgnored() {
        BlockedAppEntity a = app(INSTAGRAM);
        a.blockAlways = false;
        BlockScheduleEntity window = schedule(9 * 60, 12 * 60,
                BlockScheduleEntity.ALL_DAYS, BlockDecisionEngine.MODE_STUDY, false);

        BlockDecisionEngine.Snapshot s = snapshot(Collections.singletonList(a),
                Collections.singletonList(window), BlockDecisionEngine.MODE_NONE, null, false);
        assertFalse(BlockDecisionEngine.shouldBlock(s, INSTAGRAM).shouldBlock);
    }

    @Test
    public void scheduleRespectsTheDayMask() {
        // Monday only; today is Tuesday.
        BlockScheduleEntity window = schedule(9 * 60, 12 * 60,
                BlockScheduleEntity.MON, BlockDecisionEngine.MODE_STUDY, true);
        assertFalse(window.isOpenAt(TUESDAY, MINUTE_10AM));
        assertTrue(window.isOpenAt(Calendar.MONDAY, MINUTE_10AM));
    }

    @Test
    public void scheduleSpanningMidnightIsOpenOnBothSides() {
        // 21:00 to 04:30, the seeded Sleep window.
        BlockScheduleEntity window = schedule(21 * 60, 4 * 60 + 30,
                BlockScheduleEntity.ALL_DAYS, BlockDecisionEngine.MODE_SLEEP, true);

        assertTrue("late evening belongs to today's window",
                window.isOpenAt(TUESDAY, 22 * 60));
        assertTrue("early morning belongs to yesterday's window",
                window.isOpenAt(TUESDAY, 3 * 60));
        assertFalse("mid-afternoon is outside",
                window.isOpenAt(TUESDAY, 14 * 60));
    }

    @Test
    public void midnightSpanningWindowChecksYesterdayAgainstTheDayMask() {
        // Sunday-night-only window: open late Sunday, but the early hours of
        // Monday belong to Sunday and must still be covered.
        BlockScheduleEntity window = schedule(21 * 60, 4 * 60 + 30,
                BlockScheduleEntity.SUN, BlockDecisionEngine.MODE_SLEEP, true);

        assertTrue(window.isOpenAt(Calendar.SUNDAY, 22 * 60));
        assertTrue("Monday 03:00 is the tail of Sunday's window",
                window.isOpenAt(Calendar.MONDAY, 3 * 60));
        assertFalse("Tuesday 03:00 has no Sunday behind it",
                window.isOpenAt(TUESDAY, 3 * 60));
    }

    @Test
    public void nullScheduleEntriesAreIgnored() {
        List<BlockScheduleEntity> list = new ArrayList<>();
        list.add(null);
        list.add(schedule(9 * 60, 12 * 60, BlockScheduleEntity.ALL_DAYS,
                BlockDecisionEngine.MODE_STUDY, true));
        BlockedAppEntity a = app(INSTAGRAM);
        a.blockAlways = false;

        BlockDecisionEngine.Snapshot s = snapshot(Collections.singletonList(a), list,
                BlockDecisionEngine.MODE_NONE, null, false);
        assertTrue(BlockDecisionEngine.shouldBlock(s, INSTAGRAM).shouldBlock);
    }

    // ------------------------------------------------------------------
    //  Daily limits
    // ------------------------------------------------------------------

    @Test
    public void limitBlocksOnceReached() {
        BlockedAppEntity a = app(INSTAGRAM);
        a.blockAlways = false;
        a.blockInStudy = false;
        a.dailyLimitMin = 30;
        a.usedTodayMin = 30;
        a.usageDayKey = TODAY;

        BlockDecisionEngine.Snapshot s = simple(BlockDecisionEngine.MODE_NONE, a);
        BlockDecisionEngine.Decision d = BlockDecisionEngine.shouldBlock(s, INSTAGRAM);

        assertTrue(d.shouldBlock);
        assertEquals(BlockDecisionEngine.REASON_LIMIT, d.reason);
        assertTrue(d.isLimitBlock());
        assertEquals(0, a.remainingLimitMinutes());
    }

    @Test
    public void limitAllowsWhileUnder() {
        BlockedAppEntity a = app(INSTAGRAM);
        a.blockAlways = false;
        a.blockInStudy = false;
        a.dailyLimitMin = 30;
        a.usedTodayMin = 29;
        a.usageDayKey = TODAY;

        BlockDecisionEngine.Snapshot s = simple(BlockDecisionEngine.MODE_NONE, a);
        assertFalse(BlockDecisionEngine.shouldBlock(s, INSTAGRAM).shouldBlock);
        assertEquals(1, a.remainingLimitMinutes());
    }

    @Test
    public void staleUsageCounterFromAnotherDayCannotBlock() {
        // Without this guard, yesterday's total would block the app all morning
        // until the next usage snapshot landed.
        BlockedAppEntity a = app(INSTAGRAM);
        a.blockAlways = false;
        a.blockInStudy = false;
        a.dailyLimitMin = 30;
        a.usedTodayMin = 900;
        a.usageDayKey = "2020-01-01";

        BlockDecisionEngine.Snapshot s = simple(BlockDecisionEngine.MODE_NONE, a);
        assertFalse(BlockDecisionEngine.shouldBlock(s, INSTAGRAM).shouldBlock);
        assertFalse(a.dayKeyMatches(TODAY));
    }

    @Test
    public void zeroLimitMeansNoLimit() {
        BlockedAppEntity a = app(INSTAGRAM);
        a.blockAlways = false;
        a.blockInStudy = false;
        a.dailyLimitMin = 0;
        a.usedTodayMin = 9999;
        a.usageDayKey = TODAY;

        BlockDecisionEngine.Snapshot s = simple(BlockDecisionEngine.MODE_NONE, a);
        assertFalse("a limit of 0 means unlimited, not blocked",
                BlockDecisionEngine.shouldBlock(s, INSTAGRAM).shouldBlock);
        assertEquals(-1, a.remainingLimitMinutes());
    }

    // ------------------------------------------------------------------
    //  Reason precedence and Decision surface
    // ------------------------------------------------------------------

    @Test
    public void modeTakesPrecedenceOverAlwaysAndLimit() {
        BlockedAppEntity a = app(INSTAGRAM);
        a.blockAlways = true;
        a.dailyLimitMin = 1;
        a.usedTodayMin = 99;
        a.usageDayKey = TODAY;

        BlockDecisionEngine.Decision d =
                BlockDecisionEngine.shouldBlock(simple(BlockDecisionEngine.MODE_STUDY, a),
                        INSTAGRAM);
        assertTrue(d.shouldBlock);
        assertEquals(BlockDecisionEngine.REASON_MODE, d.reason);
    }

    @Test
    public void alwaysTakesPrecedenceOverScheduleAndLimit() {
        BlockedAppEntity a = app(INSTAGRAM);
        a.blockAlways = true;
        a.blockInStudy = false;
        a.dailyLimitMin = 1;
        a.usedTodayMin = 99;
        a.usageDayKey = TODAY;

        BlockScheduleEntity window = schedule(9 * 60, 12 * 60,
                BlockScheduleEntity.ALL_DAYS, BlockDecisionEngine.MODE_STUDY, true);
        BlockDecisionEngine.Snapshot s = snapshot(Collections.singletonList(a),
                Collections.singletonList(window), BlockDecisionEngine.MODE_NONE, null, false);

        assertEquals(BlockDecisionEngine.REASON_ALWAYS,
                BlockDecisionEngine.shouldBlock(s, INSTAGRAM).reason);
    }

    @Test
    public void strictFlagSurfacesOnTheDecision() {
        BlockedAppEntity strict = app(INSTAGRAM);
        strict.strict = true;
        BlockedAppEntity lenient = app("com.facebook.katana");

        assertTrue(BlockDecisionEngine.shouldBlock(
                simple(BlockDecisionEngine.MODE_STUDY, strict), INSTAGRAM).isStrict());
        assertFalse(BlockDecisionEngine.shouldBlock(
                simple(BlockDecisionEngine.MODE_STUDY, lenient),
                "com.facebook.katana").isStrict());
    }

    @Test
    public void allowedDecisionCarriesNoAppAndNoLabel() {
        BlockDecisionEngine.Decision d = BlockDecisionEngine.shouldBlock(
                simple(BlockDecisionEngine.MODE_NONE, app(INSTAGRAM)), "com.unlisted.app");
        assertFalse(d.shouldBlock);
        assertNull(d.app);
        assertNull(d.blockedPackage());
        assertNull(d.blockedLabel());
        assertFalse(d.isStrict());
        assertFalse(d.isLimitBlock());
        assertEquals(BlockDecisionEngine.REASON_NONE, d.reason);
    }

    @Test
    public void blockedDecisionCarriesTheLabel() {
        BlockedAppEntity a = new BlockedAppEntity(INSTAGRAM, "Instagram");
        BlockDecisionEngine.Decision d = BlockDecisionEngine.shouldBlock(
                simple(BlockDecisionEngine.MODE_STUDY, a), INSTAGRAM);
        assertEquals("Instagram", d.blockedLabel());
    }

    @Test
    public void snapshotRetainsTheRowsItWasBuiltFrom() {
        BlockedAppEntity kept = app(INSTAGRAM);
        BlockedAppEntity dropped = app("com.disabled.app");
        dropped.enabled = false;

        BlockDecisionEngine.Snapshot s = simple(BlockDecisionEngine.MODE_NONE, kept, dropped);
        assertEquals(1, s.blockedCount());
        assertEquals(1, s.blockedSnapshotApps().size());
        assertEquals(INSTAGRAM, s.blockedSnapshotApps().get(0).packageName);
    }

    @Test
    public void modeConstantsAreAllRecognised() {
        for (String mode : BlockDecisionEngine.ALL_MODES) {
            assertTrue("ALL_MODES entry must be recognised: " + mode,
                    BlockDecisionEngine.isKnownMode(mode));
        }
        assertFalse(BlockDecisionEngine.isKnownMode(null));
        assertFalse(BlockDecisionEngine.isKnownMode("sleeping"));
    }
}
