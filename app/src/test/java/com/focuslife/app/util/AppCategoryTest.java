package com.focuslife.app.util;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import com.focuslife.app.data.db.entity.AppUsageSnapshotEntity;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Package-name categorisation for the usage analyzer.
 *
 * <p>The important guarantee here is not that every app is classified correctly —
 * it cannot be, from a package name alone — but that the failure mode is safe. An
 * app the list does not recognise must land in {@link AppCategory#OTHER} and be
 * excluded from every claim the screen makes, so the worst outcome of a wrong
 * guess is a slightly less specific suggestion rather than the app telling the
 * user something untrue about their own habits.</p>
 */
public class AppCategoryTest {

    private static final String TODAY = "2026-11-17";

    private static AppUsageSnapshotEntity usage(String pkg, int minutes) {
        return new AppUsageSnapshotEntity(TODAY, pkg, pkg, minutes);
    }

    // ------------------------------------------------------------------
    //  Classification
    // ------------------------------------------------------------------

    @Test
    public void recognisesSocialAndMessagingApps() {
        for (String pkg : new String[]{
                "com.instagram.android", "com.facebook.katana", "com.facebook.lite",
                "com.whatsapp", "org.telegram.messenger", "com.snapchat",
                "com.twitter.android", "com.reddit.frontpage", "com.linkedin.android",
                "com.discord", "com.skype.raider", "com.google.android.apps.messaging"}) {
            assertEquals("should be SOCIAL: " + pkg,
                    AppCategory.SOCIAL, AppCategory.of(pkg));
        }
    }

    @Test
    public void recognisesVideoApps() {
        for (String pkg : new String[]{
                "com.google.android.youtube", "com.netflix.mediaclient",
                "com.amazon.avod.thirdpartyclient", "com.hotstar",
                "com.disney.disneyplus", "com.zee5.player", "com.sonyliv",
                "org.videolan.vlc", "com.mxtech.videoplayer.adfree"}) {
            assertEquals("should be VIDEO: " + pkg,
                    AppCategory.VIDEO, AppCategory.of(pkg));
        }
    }

    @Test
    public void recognisesGames() {
        for (String pkg : new String[]{
                "com.dts.freefireth", "com.garena.game.codm", "com.pubg.imobile",
                "com.tencent.ig", "com.kiloo.subwaysurf", "com.supercell.clashofclans",
                "com.mojang.minecraftpe", "com.roblox.client", "com.king.candycrushsaga",
                "com.miniclip.eightballpool", "com.playrix.gardenscapes",
                "ludo.king", "com.dream11.dream11"}) {
            assertEquals("should be GAMES: " + pkg,
                    AppCategory.GAMES, AppCategory.of(pkg));
        }
    }

    @Test
    public void everythingUnrecognisedFallsToOther() {
        for (String pkg : new String[]{
                "com.android.settings", "com.focuslife.app", "com.google.android.gms",
                "com.android.chrome", "com.microsoft.office.outlook",
                "in.co.example.unknownapp", "com.shopclues", "com.spotify.music"}) {
            assertEquals("should be OTHER: " + pkg,
                    AppCategory.OTHER, AppCategory.of(pkg));
        }
    }

    @Test
    public void systemAndOwnPackagesAreNeverClassifiedAsDistractions() {
        // Classifying the phone's own settings or this app as "social" would put a
        // misleading entry in the report.
        assertEquals(AppCategory.OTHER, AppCategory.of("com.android.settings"));
        assertEquals(AppCategory.OTHER, AppCategory.of("com.focuslife.app"));
        assertEquals(AppCategory.OTHER, AppCategory.of("android"));
        assertEquals(AppCategory.OTHER, AppCategory.of("com.android.systemui"));
    }

    @Test
    public void nullBlankAndWhitespacePackagesAreOther() {
        assertEquals(AppCategory.OTHER, AppCategory.of(null));
        assertEquals(AppCategory.OTHER, AppCategory.of(""));
        assertEquals(AppCategory.OTHER, AppCategory.of("   "));
    }

    @Test
    public void classificationIgnoresCase() {
        assertEquals(AppCategory.SOCIAL, AppCategory.of("COM.INSTAGRAM.ANDROID"));
        assertEquals(AppCategory.GAMES, AppCategory.of("Com.Dts.FreeFireTh"));
        assertEquals(AppCategory.VIDEO, AppCategory.of("com.google.android.YOUTUBE"));
    }

    @Test
    public void categoriesAreDistinctConstants() {
        assertEquals(4, Arrays.asList(AppCategory.SOCIAL, AppCategory.GAMES,
                AppCategory.VIDEO, AppCategory.OTHER).stream().distinct().count());
    }

    // ------------------------------------------------------------------
    //  Reporting surface
    // ------------------------------------------------------------------

    @Test
    public void onlyTheThreeRealCategoriesAreReportable() {
        assertTrue(AppCategory.isReportable(AppCategory.SOCIAL));
        assertTrue(AppCategory.isReportable(AppCategory.GAMES));
        assertTrue(AppCategory.isReportable(AppCategory.VIDEO));
        assertFalse("OTHER must never be reported as a category",
                AppCategory.isReportable(AppCategory.OTHER));
    }

    @Test
    public void otherHasNoLabelSoTheUiShowsNothingRatherThanAGuess() {
        assertEquals(0, AppCategory.labelRes(AppCategory.OTHER));
        assertNotEquals(0, AppCategory.labelRes(AppCategory.SOCIAL));
        assertNotEquals(0, AppCategory.labelRes(AppCategory.GAMES));
        assertNotEquals(0, AppCategory.labelRes(AppCategory.VIDEO));
    }

    // ------------------------------------------------------------------
    //  Aggregation
    // ------------------------------------------------------------------

    @Test
    public void minutesInCategorySumsOnlyThatCategory() {
        List<AppUsageSnapshotEntity> rows = Arrays.asList(
                usage("com.instagram.android", 40),
                usage("com.whatsapp", 20),
                usage("com.google.android.youtube", 30),
                usage("com.dts.freefireth", 10),
                usage("com.android.settings", 5));

        assertEquals(60, AppCategory.minutesInCategory(rows, AppCategory.SOCIAL));
        assertEquals(30, AppCategory.minutesInCategory(rows, AppCategory.VIDEO));
        assertEquals(10, AppCategory.minutesInCategory(rows, AppCategory.GAMES));
        assertEquals(5, AppCategory.minutesInCategory(rows, AppCategory.OTHER));
    }

    @Test
    public void minutesInCategoryIsNullSafe() {
        assertEquals(0, AppCategory.minutesInCategory(null, AppCategory.SOCIAL));
    }

    @Test
    public void nullRowsAndNegativeMinutesCannotCorruptTheTotal() {
        List<AppUsageSnapshotEntity> rows = new ArrayList<>();
        rows.add(null);
        rows.add(usage("com.instagram.android", 25));
        rows.add(usage("com.whatsapp", -50)); // a bad snapshot value
        assertEquals(25, AppCategory.minutesInCategory(rows, AppCategory.SOCIAL));
    }

    @Test
    public void dominantCategoryIsTheLargestOneAboveTheShareFloor() {
        List<AppUsageSnapshotEntity> rows = Arrays.asList(
                usage("com.instagram.android", 30),
                usage("com.dts.freefireth", 50),
                usage("com.google.android.youtube", 20));

        assertEquals(AppCategory.GAMES,
                AppCategory.dominantCategory(rows, 100, 0.25));
    }

    @Test
    public void dominantCategoryRefusesToNameAThinLead() {
        // Ten minutes of social media out of three hours is not "your largest
        // category", and saying so would be noise dressed up as insight.
        List<AppUsageSnapshotEntity> rows = Arrays.asList(
                usage("com.instagram.android", 10),
                usage("com.android.settings", 45),
                usage("com.google.android.calendar", 45));

        assertEquals(AppCategory.OTHER,
                AppCategory.dominantCategory(rows, 100, 0.25));
    }

    @Test
    public void dominantCategoryIsOtherWhenNothingWasMeasured() {
        List<AppUsageSnapshotEntity> rows =
                Arrays.asList(usage("com.instagram.android", 30));
        assertEquals(AppCategory.OTHER, AppCategory.dominantCategory(rows, 0, 0.25));
        assertEquals(AppCategory.OTHER,
                AppCategory.dominantCategory(rows, -1, 0.25));
        assertEquals(AppCategory.OTHER, AppCategory.dominantCategory(null, 100, 0.25));
    }

    @Test
    public void dominantCategoryIsNeverOtherWhenAShareFloorOfZeroIsRequested() {
        List<AppUsageSnapshotEntity> rows =
                Arrays.asList(usage("com.google.android.youtube", 5));
        assertEquals(AppCategory.VIDEO,
                AppCategory.dominantCategory(rows, 100, 0.0));
    }

    @Test
    public void unclassifiedTimeDoesNotInventADominantCategory() {
        // A day spent entirely in unrecognised apps must report no category at all.
        List<AppUsageSnapshotEntity> rows = Arrays.asList(
                usage("com.android.settings", 60),
                usage("com.example.notes", 40));
        assertEquals(AppCategory.OTHER,
                AppCategory.dominantCategory(rows, 100, 0.25));
        assertEquals(0, AppCategory.minutesInCategory(rows, AppCategory.SOCIAL));
    }
}
