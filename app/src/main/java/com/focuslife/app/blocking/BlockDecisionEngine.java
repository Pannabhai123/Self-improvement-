package com.focuslife.app.blocking;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.focuslife.app.data.db.entity.BlockScheduleEntity;
import com.focuslife.app.data.db.entity.BlockedAppEntity;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Decides whether an app that just came to the foreground should be intercepted.
 *
 * <p>This is the single source of truth for blocking. It is deliberately a pure
 * function of an immutable {@link Snapshot} with no Android dependencies, which
 * means the entire blocking policy is covered by JVM unit tests
 * ({@code BlockDecisionEngineTest}) instead of requiring a device to validate.</p>
 *
 * <h2>Why the policy lives in one place</h2>
 * The same decision has to be reachable from three different contexts: the
 * accessibility service (foreground change), the usage snapshot worker (daily
 * limit checks) and the dashboard (showing what is currently blocked). Splitting
 * the rules across those call sites is how blocking apps end up inconsistent, so
 * all three call this class.
 *
 * <h2>Decision order</h2>
 * <ol>
 *   <li>Is the package ours, or an explicitly allowed system package? → never block.</li>
 *   <li>Is there a live emergency unlock for this package? → never block.</li>
 *   <li>Is the app on the block list and enabled? If not → never block.</li>
 *   <li>Does the app's own per-mode flag cover the current mode? → block.</li>
 *   <li>Is "block always" set? → block.</li>
 *   <li>Is a scheduled window open that implies this mode? → block.</li>
 *   <li>Has the daily limit been exceeded and a limit is configured? → block.</li>
 * </ol>
 */
public final class BlockDecisionEngine {

    private BlockDecisionEngine() {
    }

    // ------------------------------------------------------------------
    //  Modes
    // ------------------------------------------------------------------

    public static final String MODE_NONE = "none";
    public static final String MODE_FOCUS = "focus";
    public static final String MODE_STUDY = "study";
    public static final String MODE_RUN = "run";
    public static final String MODE_SLEEP = "sleep";

    public static final String[] ALL_MODES = {
            MODE_FOCUS, MODE_STUDY, MODE_RUN, MODE_SLEEP
    };

    public static boolean isKnownMode(@Nullable String mode) {
        if (mode == null) return false;
        for (String m : ALL_MODES) {
            if (m.equals(mode)) return true;
        }
        return false;
    }

    // ------------------------------------------------------------------
    //  Reasons
    // ------------------------------------------------------------------

    public static final int REASON_NONE = 0;
    public static final int REASON_MODE = 1;
    public static final int REASON_ALWAYS = 2;
    public static final int REASON_SCHEDULE = 3;
    public static final int REASON_LIMIT = 4;

    // ------------------------------------------------------------------
    //  Snapshot
    // ------------------------------------------------------------------

    /**
     * Everything the decision needs, captured once.
     *
     * <p>The accessibility service builds one of these when the block list
     * changes and reuses it for every foreground event, so the hot path does
     * no database work at all. That is what keeps the guard cheap enough to run
     * continuously on a Helio G85.</p>
     */
    public static final class Snapshot {

        /** package name → entity, for enabled blocked apps only. */
        @NonNull
        private final Map<String, BlockedAppEntity> blocked;

        @NonNull
        private final List<BlockScheduleEntity> schedules;

        /** The mode active right now; {@link #MODE_NONE} when nothing is on. */
        @NonNull
        public final String activeMode;

        /** Package currently inside an emergency-unlock window, or null. */
        @Nullable
        public final String unlockedPackage;

        /** True when the unlock window is still in the future. */
        public final boolean unlockActive;

        /** Packages that must never be blocked regardless of configuration. */
        @NonNull
        private final java.util.Set<String> allowList;

        /**
         * The raw collections this snapshot was built from, retained so a caller
         * can rebuild with fresh time fields without re-querying the database.
         * Immutable, and never modified after construction.
         */
        @NonNull
        private final List<BlockedAppEntity> appsSource;

        @NonNull
        private final List<BlockScheduleEntity> schedulesSource;

        /** Minute of day at snapshot creation, for schedule evaluation. */
        public final int minuteOfDay;

        /** {@link Calendar#SUNDAY}..{@link Calendar#SATURDAY}. */
        public final int dayOfWeek;

        /** {@code yyyy-MM-dd} for the limit comparison. */
        @NonNull
        public final String dayKey;

        public Snapshot(@Nullable Collection<BlockedAppEntity> blockedApps,
                        @Nullable Collection<BlockScheduleEntity> schedules,
                        @Nullable String activeMode,
                        @Nullable String unlockedPackage,
                        boolean unlockActive,
                        int minuteOfDay,
                        int dayOfWeek,
                        @Nullable String dayKey) {
            this.blocked = new HashMap<>();
            List<BlockedAppEntity> appSource = new ArrayList<>();
            if (blockedApps != null) {
                for (BlockedAppEntity a : blockedApps) {
                    if (a != null && a.enabled && a.packageName != null && !a.packageName.isEmpty()) {
                        this.blocked.put(a.packageName, a);
                        appSource.add(a);
                    }
                }
            }
            this.appsSource = Collections.unmodifiableList(appSource);

            List<BlockScheduleEntity> schedSource = new ArrayList<>();
            if (schedules != null) {
                for (BlockScheduleEntity s : schedules) {
                    if (s != null && s.enabled) schedSource.add(s);
                }
            }
            this.schedulesSource = Collections.unmodifiableList(schedSource);
            this.schedules = new ArrayList<>(schedSource);

            this.activeMode = isKnownMode(activeMode) ? activeMode : MODE_NONE;
            this.unlockedPackage = unlockedPackage;
            this.unlockActive = unlockActive;
            this.allowList = defaultAllowList();
            this.minuteOfDay = minuteOfDay;
            this.dayOfWeek = dayOfWeek;
            this.dayKey = dayKey == null ? "" : dayKey;
        }

        public int blockedCount() {
            return blocked.size();
        }

        public boolean isOnBlockList(@NonNull String pkg) {
            return blocked.containsKey(pkg);
        }

        @Nullable
        public BlockedAppEntity app(@NonNull String pkg) {
            return blocked.get(pkg);
        }

        /** The enabled blocked-app rows this snapshot was built from. */
        @NonNull
        public List<BlockedAppEntity> blockedSnapshotApps() {
            return appsSource;
        }

        /** The enabled schedule rows this snapshot was built from. */
        @NonNull
        public List<BlockScheduleEntity> blockedSnapshotSchedules() {
            return schedulesSource;
        }
    }

    /**
     * Packages that can never be blocked, because blocking them would lock the
     * user out of the phone or out of Focus Life itself.
     *
     * <p>This is a safety invariant, not a preference: if the settings app or
     * the dialer could be intercepted, an aggressive block list would be able to
     * prevent the user from turning blocking off.</p>
     */
    @NonNull
    private static java.util.Set<String> defaultAllowList() {
        java.util.Set<String> set = new java.util.HashSet<>();
        set.add("com.focuslife.app");
        set.add("com.android.systemui");
        set.add("android");
        set.add("com.android.settings");
        set.add("com.coloros.safecenter");
        set.add("com.oppo.safe");
        set.add("com.heytap.market");
        set.add("com.android.permissioncontroller");
        set.add("com.google.android.permissioncontroller");
        // Phone and SMS must stay reachable: an app that blocks emergency calls
        // would be genuinely dangerous.
        set.add("com.google.android.dialer");
        set.add("com.android.dialer");
        set.add("com.android.incallui");
        set.add("com.coloros.dialer");
        set.add("com.android.mms");
        set.add("com.google.android.apps.messaging");
        set.add("com.coloros.phonemanager");
        return set;
    }

    // ------------------------------------------------------------------
    //  The decision
    // ------------------------------------------------------------------

    /**
     * Evaluates whether {@code pkg} should be intercepted right now.
     *
     * @return a {@link Decision} that is never null. When {@code shouldBlock} is
     *         false, {@code reason} is {@link #REASON_NONE}.
     */
    @NonNull
    public static Decision shouldBlock(@Nullable Snapshot snapshot, @Nullable String pkg) {
        if (snapshot == null || pkg == null || pkg.isEmpty()) {
            return Decision.allow();
        }

        // 1. Never block ourselves or a safety-critical system package.
        if (snapshot.allowList.contains(pkg)) {
            return Decision.allow();
        }

        // 2. Honour a live emergency unlock for this exact package.
        if (snapshot.unlockActive && pkg.equals(snapshot.unlockedPackage)) {
            return Decision.allow();
        }

        BlockedAppEntity app = snapshot.blocked.get(pkg);

        // 3. Not on the list at all.
        if (app == null) {
            return Decision.allow();
        }

        // 4. Per-mode flag for the mode that is currently running.
        if (coversMode(app, snapshot.activeMode)) {
            return Decision.block(REASON_MODE, app, snapshot.activeMode);
        }

        // 5. Always-on guard, independent of any mode.
        if (app.blockAlways) {
            return Decision.block(REASON_ALWAYS, app, snapshot.activeMode);
        }

        // 6. A scheduled window that is open right now and implies blocking.
        BlockScheduleEntity open = findOpenSchedule(snapshot);
        if (open != null && coversMode(app, open.mode)) {
            return Decision.block(REASON_SCHEDULE, app, open.mode);
        }

        // 7. Daily ceiling exceeded. Only enforced when the recorded usage
        //    belongs to today, so a stale counter can never block unfairly.
        if (app.dailyLimitMin > 0
                && app.dayKeyMatches(snapshot.dayKey)
                && app.usedTodayMin >= app.dailyLimitMin) {
            return Decision.block(REASON_LIMIT, app, snapshot.activeMode);
        }

        return Decision.allow();
    }

    /** Does this app's configuration cover the given mode? */
    private static boolean coversMode(@NonNull BlockedAppEntity app, @Nullable String mode) {
        if (mode == null || MODE_NONE.equals(mode)) return false;
        switch (mode) {
            case MODE_FOCUS: return app.blockInFocus;
            case MODE_STUDY: return app.blockInStudy;
            case MODE_RUN:   return app.blockInRun;
            case MODE_SLEEP: return app.blockInSleep;
            default:         return false;
        }
    }

    @Nullable
    private static BlockScheduleEntity findOpenSchedule(@NonNull Snapshot snapshot) {
        for (BlockScheduleEntity s : snapshot.schedules) {
            if (s.isOpenAt(snapshot.dayOfWeek, snapshot.minuteOfDay)) {
                return s;
            }
        }
        return null;
    }

    /** The schedule window that is open right now, for display purposes. */
    @Nullable
    public static BlockScheduleEntity openScheduleNow(@Nullable Snapshot snapshot) {
        return snapshot == null ? null : findOpenSchedule(snapshot);
    }

    /**
     * The effective mode right now: the manually started mode if any, otherwise
     * the mode implied by an open schedule window.
     */
    @NonNull
    public static String effectiveMode(@Nullable Snapshot snapshot) {
        if (snapshot == null) return MODE_NONE;
        if (!MODE_NONE.equals(snapshot.activeMode)) return snapshot.activeMode;
        BlockScheduleEntity open = findOpenSchedule(snapshot);
        return open == null ? MODE_NONE : open.mode;
    }

    // ------------------------------------------------------------------
    //  Decision
    // ------------------------------------------------------------------

    /** The outcome of a block evaluation, carrying enough context for the UI. */
    public static final class Decision {

        public final boolean shouldBlock;
        public final int reason;

        /** The app row that triggered the block; null when allowed. */
        @Nullable
        public final BlockedAppEntity app;

        /** The mode that was in force when the decision was made. */
        @NonNull
        public final String mode;

        private Decision(boolean shouldBlock, int reason,
                         @Nullable BlockedAppEntity app, @NonNull String mode) {
            this.shouldBlock = shouldBlock;
            this.reason = reason;
            this.app = app;
            this.mode = mode;
        }

        @NonNull
        static Decision allow() {
            return new Decision(false, REASON_NONE, null, MODE_NONE);
        }

        @NonNull
        static Decision block(int reason, @NonNull BlockedAppEntity app, @NonNull String mode) {
            return new Decision(true, reason, app, mode);
        }

        /** Strict apps get no bypass button at all. */
        public boolean isStrict() {
            return app != null && app.strict;
        }

        @Nullable
        public String blockedPackage() {
            return app == null ? null : app.packageName;
        }

        @Nullable
        public String blockedLabel() {
            return app == null ? null : app.appLabel;
        }

        public boolean isLimitBlock() {
            return reason == REASON_LIMIT;
        }
    }

    // ------------------------------------------------------------------
    //  Snapshot construction helper
    // ------------------------------------------------------------------

    /**
     * Builds a snapshot for "now" from already-loaded collections. The caller
     * supplies the data so this class stays free of Android imports.
     */
    @NonNull
    public static Snapshot snapshotNow(@Nullable Collection<BlockedAppEntity> apps,
                                       @Nullable Collection<BlockScheduleEntity> schedules,
                                       @Nullable String activeMode,
                                       @Nullable String unlockedPackage,
                                       boolean unlockActive,
                                       long nowMillis,
                                       @NonNull String dayKey) {
        Calendar c = Calendar.getInstance();
        c.setTimeInMillis(nowMillis);
        int minuteOfDay = c.get(Calendar.HOUR_OF_DAY) * 60 + c.get(Calendar.MINUTE);
        int dayOfWeek = c.get(Calendar.DAY_OF_WEEK);
        return new Snapshot(apps, schedules, activeMode, unlockedPackage, unlockActive,
                minuteOfDay, dayOfWeek, dayKey);
    }
}
