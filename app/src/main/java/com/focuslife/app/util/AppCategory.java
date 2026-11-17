package com.focuslife.app.util;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.StringRes;

import com.focuslife.app.R;
import com.focuslife.app.data.db.entity.AppUsageSnapshotEntity;

import java.util.List;

/**
 * Classifies an installed app into a broad usage category.
 *
 * <h2>Why this is here and how coarse it is allowed to be</h2>
 * The usage analyzer needs to say "social apps are your largest category today"
 * rather than only listing packages. Doing that needs some grouping, and the only
 * grouping available offline is the package name. That is deliberately crude: it
 * matches well-known package-name fragments and nothing else.
 *
 * <p>Crucially it is <b>not</b> content inspection. No app data is read, no
 * titles or messages are examined, and an app that does not match any fragment is
 * reported as {@link #OTHER} and excluded from every category claim. A wrong
 * guess therefore costs a slightly less precise suggestion and never produces a
 * false accusation.</p>
 *
 * <p>Pure Java: no Android framework imports, so it is unit-testable on the JVM.</p>
 */
public final class AppCategory {

    private AppCategory() {
    }

    /** Social and messaging apps. */
    public static final int SOCIAL = 0;
    /** Games. */
    public static final int GAMES = 1;
    /** Video and streaming apps. */
    public static final int VIDEO = 2;
    /** Everything else, including system apps and anything unrecognised. */
    public static final int OTHER = 3;

    // Package-name fragments, lower-cased. Matching is a `contains` on the
    // lower-cased package, which is why each fragment must be specific enough
    // not to collide: "game" alone would catch anything with "game" in its name,
    // which is exactly the intent, while "chat" is left out because it appears in
    // too many unrelated packages.
    private static final String[] SOCIAL_FRAGMENTS = {
            "com.instagram", "com.facebook", "facebook.lite", "com.whatsapp",
            "org.telegram", "com.snapchat", "com.twitter", "com.zhiliaoapp.musically",
            "com.reddit", "com.linkedin", "com.pinterest", "com.tumblr",
            "com.bumble", "com.match", "in.mohalla.sharechat", "com.hike",
            "com.jio.jiomeet", "com.google.android.apps.messaging",
            "com.microsoft.emmx.dualscreen", "com.discord", "com.skype",
            "com.viber", "com.linecorp", "com.kakao.talk", "com.tencent.mm",
    };

    private static final String[] VIDEO_FRAGMENTS = {
            "com.google.android.youtube", "youtube.tv", "com.netflix",
            "com.amazon.avod", "in.amazon.mshop", "com.hotstar", "com.disney",
            "com.jio.media", "com.jiocinema", "com.sonyliv", "com.zee5",
            "com.mxtech", "com.vimeo", "com.dailymotion", "com.twitch",
            "org.videolan.vlc", "com.hungama", "com.spotify.music",
            "com.gaana", "com.jiosaavn", "com.wynk.music",
    };

    private static final String[] GAME_FRAGMENTS = {
            "com.dts.freefireth", "com.garena.game", "freefire", "com.pubg",
            "com.tencent.ig", "com.ig.android", "com.kiloo.subwaysurf",
            "com.supercell", "clashofclans", "clashroyale", "com.mojang.minecraftpe",
            "com.roblox", "com.ea.gp", "com.zynga", "com.king.", "candyrush",
            "com.miniclip", "8ballpool", "com.activision", "com.ril.rgames",
            "com.dream11", "com.mpl", "com.ludo", "ludo.king", "com.carrom",
            "com.bgame", "com.playrix", "com.rovio", "com.ea.game",
    };

    /** Bare keyword used as a last resort for games. */
    private static final String[] GAME_KEYWORDS = {
            ".game", "games.", "arcade", "casino", "poker", "rummy", "teenpatti",
    };

    /**
     * Returns {@link #SOCIAL}, {@link #GAMES}, {@link #VIDEO} or {@link #OTHER}.
     *
     * @param packageName the app's package name; null or empty yields {@link #OTHER}
     */
    public static int of(@Nullable String packageName) {
        if (packageName == null) return OTHER;
        String pkg = packageName.toLowerCase(java.util.Locale.ROOT).trim();
        if (pkg.isEmpty()) return OTHER;

        if (containsAny(pkg, SOCIAL_FRAGMENTS)) return SOCIAL;
        // Video is checked before games because some streaming packages carry
        // "game" in a marketing name ("...gameplay"), and the streaming identity
        // is the more specific one.
        if (containsAny(pkg, VIDEO_FRAGMENTS)) return VIDEO;
        if (containsAny(pkg, GAME_FRAGMENTS)) return GAMES;
        if (containsAny(pkg, GAME_KEYWORDS)) return GAMES;
        return OTHER;
    }

    /** Whether this category is one of the three the analyzer reports on. */
    public static boolean isReportable(int category) {
        return category == SOCIAL || category == GAMES || category == VIDEO;
    }

    /**
     * A short label for the category, used in the app-wise list.
     *
     * @return 0 when the category is {@link #OTHER}, meaning "show no label"
     */
    @StringRes
    public static int labelRes(int category) {
        switch (category) {
            case SOCIAL:
                return R.string.usage_category_social;
            case GAMES:
                return R.string.usage_category_games;
            case VIDEO:
                return R.string.usage_category_video;
            default:
                return 0;
        }
    }

    /** Minutes summed across every app in {@code category}. */
    public static int minutesInCategory(@Nullable List<AppUsageSnapshotEntity> snapshots, int category) {
        if (snapshots == null) return 0;
        int total = 0;
        for (AppUsageSnapshotEntity row : snapshots) {
            if (row == null) continue;
            if (of(row.packageName) == category) total += Math.max(0, row.minutes);
        }
        return total;
    }

    /**
     * The single largest reportable category, or {@link #OTHER} when nothing
     * classified app accounts for at least {@code minShare} of {@code totalMinutes}.
     *
     * <p>The share floor matters: naming "your largest category" when it holds two
     * minutes out of three hundred would be noise presented as insight.</p>
     */
    public static int dominantCategory(@Nullable List<AppUsageSnapshotEntity> snapshots, int totalMinutes, double minShare) {
        if (snapshots == null || totalMinutes <= 0) return OTHER;

        int best = OTHER;
        int bestMinutes = 0;
        for (int category : new int[]{SOCIAL, GAMES, VIDEO}) {
            int minutes = minutesInCategory(snapshots, category);
            if (minutes > bestMinutes) {
                bestMinutes = minutes;
                best = category;
            }
        }
        return bestMinutes >= totalMinutes * minShare ? best : OTHER;
    }

    private static boolean containsAny(@NonNull String haystack, @NonNull String[] needles) {
        for (String needle : needles) {
            if (!needle.isEmpty() && haystack.contains(needle)) return true;
        }
        return false;
    }
}
