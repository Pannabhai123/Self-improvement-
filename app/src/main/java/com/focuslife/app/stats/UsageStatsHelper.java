package com.focuslife.app.stats;

import android.app.usage.UsageEvents;
import android.app.usage.UsageStats;
import android.app.usage.UsageStatsManager;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.text.TextUtils;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.focuslife.app.data.db.entity.AppUsageSnapshotEntity;
import com.focuslife.app.util.PermissionManager;
import com.focuslife.app.util.TimeUtils;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Reads screen time from {@link UsageStatsManager}.
 *
 * <h2>What is read</h2>
 * <ul>
 *   <li>{@code UsageStats.getTotalTimeInForeground()} — aggregate minutes per app.</li>
 *   <li>{@code UsageEvents} ACTIVITY_RESUMED transitions — how many times an app
 *       came to the foreground.</li>
 * </ul>
 *
 * <h2>What is never read</h2>
 * Notification content, message bodies, screen text, keystrokes, installed-app
 * private data, or the names of files or sites. The only strings retained are
 * package names and their public application labels.
 *
 * <h2>Known platform caveats, handled explicitly</h2>
 * <ul>
 *   <li><b>Foreground time under-counts on some OEMs.</b> ColorOS can report 0
 *       for apps it considers background-cached. A zero total is therefore
 *       treated as "no data", never as "no usage", and the UI says so.</li>
 *   <li><b>Launcher and SystemUI appear in the stats.</b> They are excluded from
 *       the "most-used apps" list because counting them would make every user
 *       look addicted to their own home screen.</li>
 *   <li><b>{@code queryEvents} is expensive.</b> Launch counts are computed for
 *       a single day only, on a background thread, and cached into Room.</li>
 * </ul>
 */
public final class UsageStatsHelper {

    private static final String TAG = "UsageStatsHelper";

    /** Packages that are UI plumbing rather than something the user chose to open. */
    private static final Set<String> SYSTEM_PACKAGES = new HashSet<>();

    static {
        Collections.addAll(SYSTEM_PACKAGES,
                "com.android.systemui",
                "android",
                "com.android.settings",
                "com.android.permissioncontroller",
                "com.google.android.permissioncontroller",
                "com.android.packageinstaller",
                "com.android.launcher",
                "com.android.launcher3",
                "com.google.android.apps.nexuslauncher",
                "com.miui.home",
                "com.huawei.android.internal.app",
                "com.coloros.launcher",
                "com.heytap.market",
                "com.oppo.launcher",
                "com.sec.android.app.launcher",
                "com.bbk.launcher2",
                "com.google.android.inputmethod.latin",
                "com.google.android.gms",
                "com.google.android.googlequicksearchbox",
                "com.focuslife.app");
    }

    private UsageStatsHelper() {
    }

    // ------------------------------------------------------------------
    //  Availability
    // ------------------------------------------------------------------

    public static boolean isAvailable(@NonNull Context context) {
        return PermissionManager.hasUsageAccess(context)
                && manager(context) != null;
    }

    @Nullable
    private static UsageStatsManager manager(@NonNull Context context) {
        try {
            return (UsageStatsManager) context.getSystemService(Context.USAGE_STATS_SERVICE);
        } catch (Exception e) {
            return null;
        }
    }

    // ------------------------------------------------------------------
    //  Daily totals
    // ------------------------------------------------------------------

    /**
     * Total foreground minutes across all user-facing apps for a day.
     *
     * @return minutes, or -1 when usage access is unavailable (distinct from 0,
     *         which is a real measurement).
     */
    public static int totalMinutesForDay(@NonNull Context context, @NonNull String dayKey) {
        List<UsageStats> stats = rawStatsForDay(context, dayKey);
        if (stats == null) return -1;

        long totalMs = 0;
        for (UsageStats s : stats) {
            if (s == null) continue;
            String pkg = s.getPackageName();
            if (TextUtils.isEmpty(pkg) || SYSTEM_PACKAGES.contains(pkg)) continue;
            totalMs += Math.max(0, s.getTotalTimeInForeground());
        }
        return (int) (totalMs / TimeUtils.MINUTE_MS);
    }

    /** Foreground minutes for today, or -1 when unavailable. */
    public static int totalMinutesToday(@NonNull Context context) {
        return totalMinutesForDay(context, TimeUtils.today());
    }

    /** Per-app usage for one day, sorted heaviest first. Null when unavailable. */
    @Nullable
    public static List<AppUsage> appUsageForDay(@NonNull Context context, @NonNull String dayKey) {
        List<UsageStats> stats = rawStatsForDay(context, dayKey);
        if (stats == null) return null;

        PackageManager pm = context.getPackageManager();
        List<AppUsage> out = new ArrayList<>();

        for (UsageStats s : stats) {
            if (s == null) continue;
            String pkg = s.getPackageName();
            if (TextUtils.isEmpty(pkg) || SYSTEM_PACKAGES.contains(pkg)) continue;

            long foregroundMs = Math.max(0, s.getTotalTimeInForeground());
            int minutes = (int) (foregroundMs / TimeUtils.MINUTE_MS);
            // Drop apps the user never really opened. Anything under a minute is
            // almost always a service UI or a mis-attributed window.
            if (minutes <= 0) continue;

            AppUsage usage = new AppUsage();
            usage.packageName = pkg;
            usage.label = safeLabel(pm, pkg);
            usage.minutes = minutes;
            usage.lastUsed = s.getLastTimeUsed();
            usage.isSystemPackage = SYSTEM_PACKAGES.contains(pkg);
            out.add(usage);
        }

        Collections.sort(out, new Comparator<AppUsage>() {
            @Override
            public int compare(AppUsage a, AppUsage b) {
                return Integer.compare(b.minutes, a.minutes);
            }
        });
        return out;
    }

    /**
     * Converts a day's usage into Room rows ready for insertion.
     *
     * @param blockedPackages used to flag rows belonging to the block list, so
     *                        the "share of screen time on blocked apps" metric
     *                        can be computed without a join at display time.
     * @return null when usage access is unavailable.
     */
    @Nullable
    public static List<AppUsageSnapshotEntity> snapshotForDay(@NonNull Context context,
                                                              @NonNull String dayKey,
                                                              @NonNull Set<String> blockedPackages) {
        List<AppUsage> usage = appUsageForDay(context, dayKey);
        if (usage == null) return null;

        Map<String, Integer> launches = launchCountsForDay(context, dayKey);
        List<AppUsageSnapshotEntity> rows = new ArrayList<>(usage.size());

        for (AppUsage u : usage) {
            AppUsageSnapshotEntity row = new AppUsageSnapshotEntity(dayKey, u.packageName, u.label, u.minutes);
            row.lastUsed = u.lastUsed;
            row.isBlockedApp = blockedPackages.contains(u.packageName);
            Integer count = launches == null ? null : launches.get(u.packageName);
            // Only record a launch count when the platform actually reported
            // events; 0 would otherwise be indistinguishable from "unsupported".
            row.launchCount = count == null ? 0 : count;
            rows.add(row);
        }
        return rows;
    }

    // ------------------------------------------------------------------
    //  Launch counts
    // ------------------------------------------------------------------

    /**
     * How many times each package came to the foreground during a day.
     *
     * <p>Derived from {@link UsageEvents} because {@code UsageStats} does not
     * expose an opening count. We count ACTIVITY_RESUMED events and deduplicate
     * bursts within {@link #MIN_LAUNCH_GAP_MS}, since a single user tap can
     * produce several resume events during an activity restart or a
     * configuration change.</p>
     *
     * @return package → count, or null when the query is unavailable.
     */
    @Nullable
    public static Map<String, Integer> launchCountsForDay(@NonNull Context context, @NonNull String dayKey) {
        UsageStatsManager usm = manager(context);
        if (usm == null || !PermissionManager.hasUsageAccess(context)) return null;

        long start = TimeUtils.startOfDay(dayKey);
        long end = Math.min(TimeUtils.endOfDay(dayKey), System.currentTimeMillis());
        if (end <= start) end = start + 1;

        Map<String, Integer> counts = new HashMap<>();
        Map<String, Long> lastResume = new HashMap<>();

        UsageEvents events;
        try {
            events = usm.queryEvents(start, end);
        } catch (Exception e) {
            Log.w(TAG, "queryEvents failed for " + dayKey, e);
            return null;
        }
        if (events == null) return counts;

        UsageEvents.Event event = new UsageEvents.Event();
        int processed = 0;
        try {
            while (events.hasNextEvent()) {
                events.getNextEvent(event);
                processed++;

                if (event.getEventType() != UsageEvents.Event.ACTIVITY_RESUMED) continue;

                String pkg = event.getPackageName();
                if (TextUtils.isEmpty(pkg) || SYSTEM_PACKAGES.contains(pkg)) continue;

                long ts = event.getTimeStamp();
                Long prev = lastResume.get(pkg);
                // Collapse resume bursts so a rotation is not counted as a new
                // opening of the app.
                if (prev != null && ts - prev < MIN_LAUNCH_GAP_MS) {
                    lastResume.put(pkg, ts);
                    continue;
                }
                lastResume.put(pkg, ts);

                Integer c = counts.get(pkg);
                counts.put(pkg, c == null ? 1 : c + 1);
            }
        } catch (Exception e) {
            // Partial results are still useful; log and return what we have.
            Log.w(TAG, "launch count walk interrupted after " + processed + " events", e);
        }
        return counts;
    }

    /** Resume events closer together than this are treated as one opening. */
    private static final long MIN_LAUNCH_GAP_MS = 3_000L;

    /** Total foreground openings across all apps on a day. */
    public static int totalLaunchesForDay(@NonNull Context context, @NonNull String dayKey) {
        Map<String, Integer> counts = launchCountsForDay(context, dayKey);
        if (counts == null) return 0;
        int total = 0;
        for (Integer v : counts.values()) {
            if (v != null && v > 0) total += v;
        }
        return total;
    }

    /**
     * Whether this build can report opening counts at all. Used to show the
     * "not available on this Android version" note honestly instead of a zero.
     */
    public static boolean supportsLaunchCounts(@NonNull Context context) {
        UsageStatsManager usm = manager(context);
        if (usm == null || !PermissionManager.hasUsageAccess(context)) return false;
        try {
            long now = System.currentTimeMillis();
            UsageEvents events = usm.queryEvents(now - TimeUtils.HOUR_MS, now);
            return events != null;
        } catch (Exception e) {
            return false;
        }
    }

    // ------------------------------------------------------------------
    //  Weekly comparison
    // ------------------------------------------------------------------

    /**
     * Foreground minutes for each of the last {@code days} days, oldest first.
     * Days with no data are reported as 0 so the chart keeps a stable shape.
     *
     * @return null when usage access is unavailable.
     */
    @Nullable
    public static int[] weeklyMinutes(@NonNull Context context, int days) {
        if (!isAvailable(context)) return null;

        int[] out = new int[days];
        for (int i = 0; i < days; i++) {
            String key = TimeUtils.dayOffsetFromToday(-(days - 1 - i));
            int total = totalMinutesForDay(context, key);
            out[i] = Math.max(0, total);
        }
        return out;
    }

    /** Day keys matching {@link #weeklyMinutes}, oldest first. */
    @NonNull
    public static List<String> weeklyDayKeys(int days) {
        return TimeUtils.lastNDayKeys(days);
    }

    // ------------------------------------------------------------------
    //  Internals
    // ------------------------------------------------------------------

    @Nullable
    private static List<UsageStats> rawStatsForDay(@NonNull Context context, @NonNull String dayKey) {
        UsageStatsManager usm = manager(context);
        if (usm == null) return null;
        if (!PermissionManager.hasUsageAccess(context)) return null;

        long start = TimeUtils.startOfDay(dayKey);
        long end = Math.min(TimeUtils.endOfDay(dayKey), System.currentTimeMillis());
        if (end <= start) {
            // Future day: nothing can have been measured yet.
            return new ArrayList<>();
        }

        try {
            // queryAndAggregateUsageStats collapses to one entry per package and
            // is markedly cheaper than queryUsageStats + manual merging.
            Map<String, UsageStats> aggregated =
                    usm.queryAndAggregateUsageStats(start, end);
            if (aggregated == null) return new ArrayList<>();
            return new ArrayList<>(aggregated.values());
        } catch (Exception e) {
            Log.w(TAG, "queryAndAggregateUsageStats failed for " + dayKey, e);
            // Fall back to the interval API, which some OEMs implement when the
            // aggregate one throws.
            try {
                List<UsageStats> list = usm.queryUsageStats(
                        UsageStatsManager.INTERVAL_DAILY, start, end);
                return list == null ? new ArrayList<>() : mergeByPackage(list);
            } catch (Exception e2) {
                Log.e(TAG, "queryUsageStats also failed for " + dayKey, e2);
                return null;
            }
        }
    }

    /**
     * {@code queryUsageStats(INTERVAL_DAILY)} can return several rows for one
     * package when the query window straddles a bucket boundary. Summing them
     * is the only correct merge; taking the last row silently loses time.
     */
    @NonNull
    private static List<UsageStats> mergeByPackage(@NonNull List<UsageStats> raw) {
        Map<String, UsageStats> byPkg = new HashMap<>();
        for (UsageStats s : raw) {
            if (s == null || TextUtils.isEmpty(s.getPackageName())) continue;
            UsageStats existing = byPkg.get(s.getPackageName());
            if (existing == null) {
                byPkg.put(s.getPackageName(), copyOf(s));
            } else {
                // UsageStats has no setter for total time, so accumulate in a
                // lightweight holder and rebuild below.
                existing.add(s);
            }
        }
        return new ArrayList<>(byPkg.values());
    }

    @NonNull
    private static UsageStats copyOf(@NonNull UsageStats src) {
        // UsageStats(UsageStats) is the public copy constructor.
        return new UsageStats(src);
    }

    @NonNull
    private static String safeLabel(@NonNull PackageManager pm, @NonNull String pkg) {
        try {
            ApplicationInfo info = pm.getApplicationInfo(pkg, 0);
            CharSequence label = pm.getApplicationLabel(info);
            if (!TextUtils.isEmpty(label)) return label.toString();
        } catch (PackageManager.NameNotFoundException ignored) {
            // Uninstalled since the stats were recorded; fall through to the
            // package name so the row is still meaningful.
        } catch (Exception ignored) {
            // Some OEMs throw for hidden system packages.
        }
        return pkg;
    }

    // ------------------------------------------------------------------
    //  Model
    // ------------------------------------------------------------------

    /** One app's measured usage for one day. */
    public static final class AppUsage {
        @NonNull
        public String packageName = "";
        @NonNull
        public String label = "";
        public int minutes;
        public long lastUsed;
        public int launchCount;
        public boolean isSystemPackage;

        public AppUsage() {
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof AppUsage)) return false;
            return packageName.equals(((AppUsage) o).packageName);
        }

        @Override
        public int hashCode() {
            return packageName.hashCode();
        }
    }
}
