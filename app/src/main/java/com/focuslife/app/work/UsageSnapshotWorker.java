package com.focuslife.app.work;

import android.content.Context;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

import com.focuslife.app.data.repo.Repository;
import com.focuslife.app.util.PermissionManager;
import com.focuslife.app.util.TimeUtils;

/**
 * Copies per-app usage out of {@code UsageStatsManager} into the local database.
 *
 * <h2>Why this worker exists</h2>
 * <ol>
 *   <li><b>The system's data is transient.</b> Android keeps detailed usage for a
 *       limited window and can evict it. Snapshotting each day locally is what
 *       makes a six-month weekly comparison possible.</li>
 *   <li><b>Reading the system API is slow.</b> {@code queryEvents} over a whole
 *       day can take hundreds of milliseconds on a Helio G85. Doing that on the
 *       dashboard's critical path would make the home screen feel broken, so it
 *       happens here, in the background, and the UI reads Room.</li>
 * </ol>
 *
 * <h2>Cost control</h2>
 * Runs every 6 hours and only for the days that matter: today always, yesterday
 * only when it has no snapshot yet (which covers a phone that was off at
 * midnight). It exits immediately when usage access is not granted, so a user who
 * declined that permission pays nothing.
 */
public class UsageSnapshotWorker extends Worker {

    private static final String TAG = "UsageSnapshotWorker";

    public static final String UNIQUE_NAME = "focuslife_usage_snapshot";

    public UsageSnapshotWorker(@NonNull Context context, @NonNull WorkerParameters params) {
        super(context, params);
    }

    @NonNull
    @Override
    public Result doWork() {
        Context context = getApplicationContext();

        if (!PermissionManager.hasUsageAccess(context)) {
            // Not an error: the user declined. Everything except screen time
            // still works, and retrying would just burn cycles.
            Log.i(TAG, "usage access not granted; skipping snapshot");
            return Result.success();
        }

        try {
            Repository repo = Repository.get(context);

            int todayRows = repo.refreshUsageSnapshot(TimeUtils.today());

            // Yesterday only if it was never captured. A device that was powered
            // off overnight would otherwise lose that day permanently, because
            // the system may still have the data now but will not in a week.
            String yesterday = TimeUtils.dayOffsetFromToday(-1);
            if (repo.db().usageDao().totalMinutesOnDay(yesterday) <= 0) {
                repo.refreshUsageSnapshot(yesterday);
            }

            // Prune snapshots older than 180 days. Long enough for a meaningful
            // year-on-year view, short enough that the table cannot grow without
            // bound on a device with limited storage.
            String cutoff = TimeUtils.dayOffsetFromToday(-180);
            repo.db().usageDao().deleteOlderThan(cutoff);

            Log.i(TAG, "snapshot complete: " + todayRows + " app rows for today");
            return Result.success();
        } catch (Exception e) {
            Log.e(TAG, "snapshot failed", e);
            // Retry with backoff: a transient failure (e.g. the system service
            // being unavailable during boot) is worth another attempt, but a
            // permanent one should not spin forever.
            return Result.retry();
        }
    }
}
