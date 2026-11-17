package com.focuslife.app.notify;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.work.ExistingPeriodicWorkPolicy;
import androidx.work.OneTimeWorkRequest;
import androidx.work.PeriodicWorkRequest;
import androidx.work.WorkManager;

import com.focuslife.app.blocking.BlockDecisionEngine;
import com.focuslife.app.blocking.BlockForegroundService;
import com.focuslife.app.data.prefs.Prefs;
import com.focuslife.app.work.DailyReportWorker;
import com.focuslife.app.work.ReminderWorker;
import com.focuslife.app.work.UsageSnapshotWorker;

import java.util.concurrent.TimeUnit;

/**
 * Re-arms everything after a reboot or an app update.
 *
 * <p>Periodic work survives a reboot in principle, but OEM power managers
 * (ColorOS included) routinely clear pending work, and an app update replaces the
 * process outright. Re-enqueueing on {@code BOOT_COMPLETED} and
 * {@code MY_PACKAGE_REPLACED} makes reminders dependable without asking the user
 * to do anything.</p>
 *
 * <p>The receiver also restores an active mode, so a Sleep Mode set before a
 * reboot is still running afterwards. Work is dispatched to a background
 * executor because {@code onReceive} has a ten-second budget on the main
 * thread.</p>
 */
public class BootReceiver extends BroadcastReceiver {

    private static final String TAG = "BootReceiver";

    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent == null ? null : intent.getAction();
        if (action == null) return;

        if (!Intent.ACTION_BOOT_COMPLETED.equals(action)
                && !Intent.ACTION_LOCKED_BOOT_COMPLETED.equals(action)
                && !Intent.ACTION_MY_PACKAGE_REPLACED.equals(action)) {
            return;
        }

        final Context app = context.getApplicationContext();
        // goAsync() keeps the process alive while the pending result finishes,
        // which is required because WorkManager enqueueing is asynchronous.
        final PendingResult pending = goAsync();

        com.focuslife.app.data.db.AppDatabase.io().execute(() -> {
            try {
                // Locked boot fires before user storage is unlocked, and Room
                // cannot open its database then. Only do real work on the
                // unlocked variants.
                if (!Intent.ACTION_LOCKED_BOOT_COMPLETED.equals(action)) {
                    NotifHelper.createChannels(app);
                    rescheduleWork(app);
                    restoreMode(app);
                    generateMissedReport(app);
                }
            } catch (Exception e) {
                Log.e(TAG, "post-boot re-arm failed", e);
            } finally {
                pending.finish();
            }
        });
    }

    /** Re-enqueues the three periodic workers as unique work. */
    private void rescheduleWork(@NonNull Context context) {
        WorkManager wm = WorkManager.getInstance(context);

        wm.enqueueUniquePeriodicWork(
                UsageSnapshotWorker.UNIQUE_NAME,
                ExistingPeriodicWorkPolicy.UPDATE,
                new PeriodicWorkRequest.Builder(UsageSnapshotWorker.class, 6, TimeUnit.HOURS)
                        .setInitialDelay(5, TimeUnit.MINUTES)
                        .build());

        wm.enqueueUniquePeriodicWork(
                ReminderWorker.UNIQUE_NAME,
                ExistingPeriodicWorkPolicy.UPDATE,
                new PeriodicWorkRequest.Builder(ReminderWorker.class, 30, TimeUnit.MINUTES)
                        .setInitialDelay(2, TimeUnit.MINUTES)
                        .build());

        wm.enqueueUniquePeriodicWork(
                DailyReportWorker.UNIQUE_NAME,
                ExistingPeriodicWorkPolicy.UPDATE,
                new PeriodicWorkRequest.Builder(DailyReportWorker.class, 24, TimeUnit.HOURS)
                        .setInitialDelay(10, TimeUnit.MINUTES)
                        .build());
    }

    /**
     * Restarts the mode service if a mode was active before the reboot.
     *
     * <p>A reboot is exactly when a user's commitment is most likely to be
     * silently dropped, and exactly when they would least notice.</p>
     */
    private void restoreMode(@NonNull Context context) {
        String mode = Prefs.activeMode(context);
        if (!BlockDecisionEngine.isKnownMode(mode)) return;
        try {
            BlockForegroundService.startMode(context, mode);
        } catch (Exception e) {
            Log.w(TAG, "could not restore mode " + mode, e);
        }
    }

    /**
     * Generates the report for any day that was missed while the phone was off.
     *
     * <p>Bounded to the last three days: regenerating a fortnight of reports
     * after a holiday would be slow and pointless.</p>
     */
    private void generateMissedReport(@NonNull Context context) {
        try {
            com.focuslife.app.data.repo.Repository repo =
                    com.focuslife.app.data.repo.Repository.get(context);
            for (int i = 1; i <= 3; i++) {
                String day = com.focuslife.app.util.TimeUtils.dayOffsetFromToday(-i);
                if (repo.db().usageDao().findSummary(day) != null) continue;

                WorkManager.getInstance(context).enqueue(
                        new OneTimeWorkRequest.Builder(DailyReportWorker.class)
                                .setInputData(new androidx.work.Data.Builder()
                                        .putString(DailyReportWorker.INPUT_DAY_KEY, day)
                                        .build())
                                .build());
                // One catch-up report per boot is plenty; more would be noise.
                break;
            }
        } catch (Exception e) {
            Log.w(TAG, "catch-up report scheduling failed", e);
        }
    }
}
