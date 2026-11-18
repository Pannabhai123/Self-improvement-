package com.focuslife.app.notify;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.text.TextUtils;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.work.Data;
import androidx.work.OneTimeWorkRequest;
import androidx.work.WorkManager;

import com.focuslife.app.blocking.BlockDecisionEngine;
import com.focuslife.app.blocking.ModeController;
import com.focuslife.app.data.db.AppDatabase;
import com.focuslife.app.data.db.entity.HabitEntity;
import com.focuslife.app.data.db.entity.TaskEntity;
import com.focuslife.app.data.repo.Repository;
import com.focuslife.app.util.TimeUtils;
import com.focuslife.app.work.SnoozeWorker;

import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Handles actions taken directly from a notification, so the user never has to
 * open the app to tick something off.
 *
 * <p>This matters more than it sounds: for a discipline app, every extra step
 * between the reminder and the action is a chance to lose the action entirely.
 * Marking a habit done from the notification shade keeps the loop under two
 * seconds.</p>
 *
 * <p>All work is dispatched to the IO executor; {@code onReceive} returns
 * immediately and a {@code goAsync()} pending result keeps the process alive
 * until the database write completes.</p>
 */
public class NotificationActionReceiver extends BroadcastReceiver {

    private static final String TAG = "NotifAction";

    public static final String ACTION_STOP_MODE = "com.focuslife.app.action.STOP_MODE";
    public static final String ACTION_DONE_TASK = "com.focuslife.app.action.DONE_TASK";
    public static final String ACTION_DONE_HABIT = "com.focuslife.app.action.DONE_HABIT";
    public static final String ACTION_SNOOZE_ROUTINE = "com.focuslife.app.action.SNOOZE_ROUTINE";
    public static final String ACTION_DISMISS = "com.focuslife.app.action.DISMISS";

    public static final String EXTRA_TASK_ID = "task_id";
    public static final String EXTRA_HABIT_ID = "habit_id";
    public static final String EXTRA_NOTIFICATION_ID = "notification_id";
    public static final String EXTRA_SLOT_TITLE = "slot_title";

    @Override
    public void onReceive(Context context, @Nullable Intent intent) {
        if (intent == null || TextUtils.isEmpty(intent.getAction())) return;

        final Context app = context.getApplicationContext();
        final String action = intent.getAction();
        final long taskId = intent.getLongExtra(EXTRA_TASK_ID, -1L);
        final long habitId = intent.getLongExtra(EXTRA_HABIT_ID, -1L);
        final int notificationId = intent.getIntExtra(EXTRA_NOTIFICATION_ID, -1);
        final String slotTitle = intent.getStringExtra(EXTRA_SLOT_TITLE);

        // Dismissal needs no background work.
        if (ACTION_DISMISS.equals(action)) {
            if (notificationId >= 0) NotifHelper.cancel(app, notificationId);
            return;
        }

        // Stopping a mode touches prefs and a service, both cheap and both
        // required to happen promptly, so it runs inline.
        if (ACTION_STOP_MODE.equals(action)) {
            ModeController.stop(app);
            if (notificationId >= 0) NotifHelper.cancel(app, notificationId);
            return;
        }

        final PendingResult pending = goAsync();
        AppDatabase.io().execute(() -> {
            try {
                switch (action) {
                    case ACTION_DONE_TASK:
                        handleTaskDone(app, taskId, notificationId);
                        break;
                    case ACTION_DONE_HABIT:
                        handleHabitDone(app, habitId, notificationId);
                        break;
                    case ACTION_SNOOZE_ROUTINE:
                        handleSnooze(app, slotTitle, notificationId);
                        break;
                    default:
                        Log.w(TAG, "unknown action: " + action);
                        break;
                }
            } catch (Exception e) {
                Log.e(TAG, "action failed: " + action, e);
            } finally {
                pending.finish();
            }
        });
    }

    /** Marks a task done and refreshes today's summary so the score moves at once. */
    private void handleTaskDone(@NonNull Context context, long taskId, int notificationId) {
        if (taskId < 0) return;

        Repository repo = Repository.get(context);
        TaskEntity task = repo.db().taskDao().findById(taskId);
        if (task == null) return;

        repo.db().taskDao().setDone(taskId, true, System.currentTimeMillis());
        repo.scheduleSummaryRefresh(task.dayKey);

        if (notificationId >= 0) NotifHelper.cancel(context, notificationId);
    }

    /**
     * Ticks a habit from the notification.
     *
     * <p>Only ticks — it never unticks. A notification action is a one-way
     * gesture and undoing from the shade would be ambiguous.</p>
     */
    private void handleHabitDone(@NonNull Context context, long habitId, int notificationId) {
        if (habitId < 0) return;

        Repository repo = Repository.get(context);
        String today = TimeUtils.today();
        if (repo.db().habitDao().findLog(habitId, today) != null) {
            // Already done; just clear the notification.
            if (notificationId >= 0) NotifHelper.cancel(context, notificationId);
            return;
        }

        repo.toggleHabit(habitId, today);

        // When the checklist is now complete, say so. Positive feedback at the
        // moment of completion is the cheapest reinforcement available.
        List<HabitEntity> habits = repo.db().habitDao().activeHabits();
        int done = repo.db().habitDao().doneCountOnDay(today);
        if (!habits.isEmpty() && done >= habits.size()) {
            NotifHelper.notifyHabitsRemaining(context, done, habits.size());
        }

        if (notificationId >= 0) NotifHelper.cancel(context, notificationId);
    }

    /**
     * Re-posts a routine reminder ten minutes later.
     *
     * <p>Implemented as a delayed one-shot worker rather than an alarm, so it
     * inherits WorkManager's Doze handling instead of needing a second scheduling
     * mechanism.</p>
     */
    private void handleSnooze(@NonNull Context context, @Nullable String slotTitle, int notificationId) {
        if (notificationId >= 0) NotifHelper.cancel(context, notificationId);

        final String title = TextUtils.isEmpty(slotTitle) ? "" : slotTitle;
        Data input = new Data.Builder()
                .putString(SnoozeWorker.INPUT_TITLE, title)
                .build();

        WorkManager.getInstance(context).enqueue(
                new OneTimeWorkRequest.Builder(SnoozeWorker.class)
                        .setInputData(input)
                        .setInitialDelay(10, TimeUnit.MINUTES)
                        .build());
    }
}
