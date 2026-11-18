package com.focuslife.app.work;

import android.content.Context;
import android.text.TextUtils;

import androidx.annotation.NonNull;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

import com.focuslife.app.R;
import com.focuslife.app.notify.NotifHelper;
import com.focuslife.app.util.PermissionManager;

/**
 * Re-posts a routine reminder after a snooze.
 *
 * <p>A dedicated one-shot worker rather than an {@code AlarmManager} timer, so
 * snoozes inherit WorkManager's Doze deferral instead of needing a second
 * scheduling path with its own boot-receiver wiring.</p>
 *
 * <p>The snoozed reminder uses a synthetic slot id so it lands on a distinct
 * notification id and replaces the original rather than stacking beside it.</p>
 */
public class SnoozeWorker extends Worker {

    public static final String INPUT_TITLE = "title";

    /** Notification id used for snoozed reminders; outside the routine range. */
    private static final int SNOOZE_NOTIF_ID = 2900;

    public SnoozeWorker(@NonNull Context context, @NonNull WorkerParameters params) {
        super(context, params);
    }

    @NonNull
    @Override
    public Result doWork() {
        Context context = getApplicationContext();
        if (!PermissionManager.canPostNotifications(context)) {
            return Result.success();
        }

        String title = getInputData().getString(INPUT_TITLE);
        if (TextUtils.isEmpty(title)) {
            return Result.success();
        }

        // A snooze means "not now", so the re-posted reminder says the activity
        // is due now rather than naming a time that has already passed.
        NotifHelper.notifyRoutine(context, SNOOZE_NOTIF_ID, title, "",
                context.getString(R.string.notif_routine_title_now, title));
        return Result.success();
    }
}
