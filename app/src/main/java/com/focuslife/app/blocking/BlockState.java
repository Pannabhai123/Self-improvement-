package com.focuslife.app.blocking;

import android.content.Context;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.focuslife.app.data.db.AppDatabase;
import com.focuslife.app.data.db.entity.BlockScheduleEntity;
import com.focuslife.app.data.db.entity.BlockedAppEntity;
import com.focuslife.app.data.prefs.Prefs;
import com.focuslife.app.util.TimeUtils;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Holds the in-memory {@link BlockDecisionEngine.Snapshot} and keeps it fresh.
 *
 * <h2>Why a cache exists</h2>
 * The accessibility service receives a foreground-change event every time the
 * user switches apps. Running a Room query on that path would add latency to
 * every app switch on a 4 GB device and could produce a visible pause before the
 * warning appears. So the policy is loaded once into an immutable snapshot and
 * the hot path becomes a HashMap lookup.
 *
 * <h2>How staleness is prevented</h2>
 * The snapshot carries the minute-of-day and day-of-week it was built with.
 * Because schedules are time-dependent, {@link #evaluate} rebuilds the time
 * fields cheaply on every call rather than trusting a stale clock, and a full
 * refresh is triggered whenever the block list, the schedules or the mode
 * changes. A daily boundary therefore cannot leave yesterday's window open.
 *
 * <p>Thread-safe: the snapshot reference is atomic and listeners are held in a
 * copy-on-write list, so refreshes from the IO executor and reads from the
 * accessibility thread never contend on a lock.</p>
 */
public final class BlockState {

    private static final String TAG = "BlockState";

    private static final BlockState INSTANCE = new BlockState();

    @NonNull
    public static BlockState get() {
        return INSTANCE;
    }

    private final AtomicReference<BlockDecisionEngine.Snapshot> snapshotRef =
            new AtomicReference<>(null);

    /** The block list alone, kept for the UI ("what would be blocked right now"). */
    private final AtomicReference<List<BlockedAppEntity>> appsRef =
            new AtomicReference<>(Collections.emptyList());

    private final CopyOnWriteArrayList<Listener> listeners = new CopyOnWriteArrayList<>();

    @Nullable
    private volatile Context appContext;

    private BlockState() {
    }

    /** Callback invoked on the IO executor whenever the snapshot changes. */
    public interface Listener {
        void onBlockStateChanged(@NonNull BlockDecisionEngine.Snapshot snapshot);
    }

    public void addListener(@NonNull Listener l) {
        listeners.addIfAbsent(l);
    }

    public void removeListener(@NonNull Listener l) {
        listeners.remove(l);
    }

    /** Binds the application context. Called from {@code FocusLifeApp}. */
    public void attach(@NonNull Context context) {
        appContext = context.getApplicationContext();
    }

    // ------------------------------------------------------------------
    //  Reading
    // ------------------------------------------------------------------

    /**
     * The current snapshot, or a permissive empty one if nothing has loaded yet.
     *
     * <p>An empty snapshot blocks nothing, which is the correct fail-safe: if
     * the database has not loaded, the user must not find their phone
     * unusable.</p>
     */
    @NonNull
    public BlockDecisionEngine.Snapshot snapshot() {
        BlockDecisionEngine.Snapshot s = snapshotRef.get();
        return s != null ? s : EMPTY;
    }

    private static final BlockDecisionEngine.Snapshot EMPTY = new BlockDecisionEngine.Snapshot(
            null, null, BlockDecisionEngine.MODE_NONE, null, false, 0, 0, "");

    @NonNull
    public List<BlockedAppEntity> blockedApps() {
        return appsRef.get();
    }

    /**
     * Evaluates a foreground package.
     *
     * <p>Rebuilds the snapshot when the cached one was built for a different
     * minute-of-day or a different day, so schedule windows open and close on
     * time even if no explicit refresh was requested.</p>
     */
    @NonNull
    public BlockDecisionEngine.Decision evaluate(@NonNull Context context, @Nullable String pkg) {
        BlockDecisionEngine.Snapshot s = freshSnapshot(context);
        return BlockDecisionEngine.shouldBlock(s, pkg);
    }

    /**
     * Returns a snapshot whose time fields are current.
     *
     * <p>Only the two time-derived fields and the unlock window are refreshed;
     * the block list and schedules are reused from cache. This keeps the cost
     * proportional to the schedule count (a handful), not the app list.</p>
     */
    @NonNull
    private BlockDecisionEngine.Snapshot freshSnapshot(@NonNull Context context) {
        BlockDecisionEngine.Snapshot cached = snapshotRef.get();

        long now = System.currentTimeMillis();
        int minuteOfDay = TimeUtils.minuteOfDay(now);
        java.util.Calendar cal = java.util.Calendar.getInstance();
        cal.setTimeInMillis(now);
        int dayOfWeek = cal.get(java.util.Calendar.DAY_OF_WEEK);
        String dayKey = TimeUtils.today();

        String mode = nz(Prefs.activeMode(context), BlockDecisionEngine.MODE_NONE);
        boolean unlockActive = Prefs.isAnyUnlocked(context);
        String unlockPkg = Prefs.unlockPackage(context);

        boolean reusable = cached != null
                && cached.minuteOfDay == minuteOfDay
                && cached.dayOfWeek == dayOfWeek
                && cached.dayKey.equals(dayKey)
                && cached.activeMode.equals(mode)
                && cached.unlockActive == unlockActive
                && java.util.Objects.equals(cached.unlockedPackage, unlockPkg);

        if (reusable) {
            return cached;
        }

        // Rebuild from the cached lists without touching the database. This is
        // cheap and runs on whatever thread asked, which must be acceptable
        // because the accessibility service calls it synchronously.
        BlockDecisionEngine.Snapshot rebuilt = BlockDecisionEngine.snapshotNow(
                cached == null ? Collections.<BlockedAppEntity>emptyList() : cached.blockedSnapshotApps(),
                cached == null ? Collections.<BlockScheduleEntity>emptyList() : cached.blockedSnapshotSchedules(),
                mode, unlockPkg, unlockActive, now, dayKey);
        snapshotRef.set(rebuilt);
        return rebuilt;
    }

    // ------------------------------------------------------------------
    //  Refreshing
    // ------------------------------------------------------------------

    /** Asynchronous reload from Room. Safe to call from any thread. */
    public void refreshAsync(@NonNull Context context) {
        final Context app = context.getApplicationContext();
        Executor io = AppDatabase.io();
        io.execute(() -> refreshSync(app));
    }

    /**
     * Synchronous reload. Must be called off the main thread.
     *
     * @return the newly built snapshot.
     */
    @NonNull
    public BlockDecisionEngine.Snapshot refreshSync(@NonNull Context context) {
        Context app = context.getApplicationContext();
        try {
            AppDatabase db = AppDatabase.get(app);
            List<BlockedAppEntity> apps = db.blockerDao().enabledApps();
            List<BlockScheduleEntity> schedules = db.blockerDao().enabledSchedules();

            appsRef.set(Collections.unmodifiableList(new ArrayList<>(apps)));

            long now = System.currentTimeMillis();
            String mode = nz(Prefs.activeMode(app), BlockDecisionEngine.MODE_NONE);
            boolean unlockActive = Prefs.isAnyUnlocked(app);
            String unlockPkg = Prefs.unlockPackage(app);

            BlockDecisionEngine.Snapshot s = BlockDecisionEngine.snapshotNow(
                    apps, schedules, mode, unlockPkg, unlockActive, now, TimeUtils.today());
            snapshotRef.set(s);

            for (Listener l : listeners) {
                try {
                    l.onBlockStateChanged(s);
                } catch (Exception e) {
                    Log.w(TAG, "listener failed", e);
                }
            }
            return s;
        } catch (Exception e) {
            Log.e(TAG, "refresh failed; leaving previous snapshot in place", e);
            return snapshot();
        }
    }

    /** Refresh only the mode/unlock fields, without re-reading the block list. */
    public void refreshMode(@NonNull Context context) {
        final Context app = context.getApplicationContext();
        AppDatabase.io().execute(() -> {
            BlockDecisionEngine.Snapshot cached = snapshotRef.get();
            if (cached == null) {
                refreshSync(app);
                return;
            }
            long now = System.currentTimeMillis();
            BlockDecisionEngine.Snapshot s = BlockDecisionEngine.snapshotNow(
                    cached.blockedSnapshotApps(),
                    cached.blockedSnapshotSchedules(),
                    nz(Prefs.activeMode(app), BlockDecisionEngine.MODE_NONE),
                    Prefs.unlockPackage(app),
                    Prefs.isAnyUnlocked(app),
                    now,
                    TimeUtils.today());
            snapshotRef.set(s);
            for (Listener l : listeners) {
                try {
                    l.onBlockStateChanged(s);
                } catch (Exception e) {
                    Log.w(TAG, "listener failed", e);
                }
            }
        });
    }

    /** Drops the cache, e.g. after "erase all data". */
    public void invalidate() {
        snapshotRef.set(null);
        appsRef.set(Collections.emptyList());
    }

    @NonNull
    private static String nz(@Nullable String value, @NonNull String fallback) {
        return value == null || value.isEmpty() ? fallback : value;
    }
}
