package com.focuslife.app.notify;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.text.TextUtils;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.focuslife.app.data.db.AppDatabase;
import com.focuslife.app.data.db.entity.RoutineSlotEntity;
import com.focuslife.app.data.repo.Repository;
import com.focuslife.app.util.PermissionManager;
import com.focuslife.app.util.TimeUtils;

/**
 * Fires one routine reminder, marks that slot as announced for today, and arms
 * the alarm for the slot after it.
 *
 * <p>{@link #goAsync()} plus the shared IO executor: reading the slot and writing
 * its notified flag are database operations, and doing them inline in
 * {@code onReceive} would either block the main thread or be cut off by the
 * receiver's ~10 second limit.</p>
 */
public class RoutineAlarmReceiver extends BroadcastReceiver {

    private static final String TAG = "RoutineAlarmReceiver";

    public static final String ACTION_ROUTINE_DUE = "com.focuslife.app.ROUTINE_DUE";

    public static final String EXTRA_SLOT_ID = "extra_slot_id";

    @Override
    public void onReceive(@Nullable final Context context, @Nullable final Intent intent) {
        if (context == null || intent == null) return;
        if (!ACTION_ROUTINE_DUE.equals(intent.getAction())) return;

        final Context app = context.getApplicationContext();
        final long slotId = intent.getLongExtra(EXTRA_SLOT_ID, 0L);

        if (!PermissionManager.canPostNotifications(app)) {
            // Notifications were revoked after this alarm was armed. Re-arm for
            // the next slot anyway so reminders resume without a restart.
            RoutineAlarmScheduler.scheduleNext(app);
            return;
        }

        if (slotId <= 0L) {
            RoutineAlarmScheduler.scheduleNext(app);
            return;
        }

        final PendingResult pending = goAsync();
        AppDatabase.io().execute(() -> {
            try {
                handle(app, slotId);
            } catch (Exception e) {
                android.util.Log.w(TAG, "routine reminder failed", e);
            } finally {
                // Always re-arm, even when this one failed: a dropped alarm must
                // not cost the user every reminder that follows it.
                try {
                    RoutineAlarmScheduler.scheduleNext(app);
                } finally {
                    pending.finish();
                }
            }
        });
    }

    private void handle(@NonNull Context context, long slotId) {
        AppDatabase db = AppDatabase.get(context);
        String today = TimeUtils.today();

        RoutineSlotEntity slot = db.routineDao().findById(slotId);
        if (slot == null) return;

        // The slot may have been disabled or moved to another day since the alarm
        // was armed. Honouring the current state rather than the armed one is what
        // makes editing the routine feel immediate.
        if (!slot.enabled) return;
        if (!RoutineAlarmScheduler.dayMaskIncludesToday(slot.daysMask)) return;

        // The polling worker may already have announced this slot. Its
        // once-per-day flag is the single source of truth for that, so checking it
        // here prevents two notifications for one activity.
        if (today.equals(slot.notifiedDayKey)) return;

        Repository repo = Repository.get(context);
        String title = repo.resolveString(slot.titleRes, slot.title);
        if (TextUtils.isEmpty(title)) return;

        String whenText = TimeUtils.formatClock24(slot.minuteOfDay);
        boolean isNow = slot.remindBeforeMin <= 0;
        String body = isNow
                ? context.getString(com.focuslife.app.R.string.routine_reminder_now, title)
                : context.getString(com.focuslife.app.R.string.routine_reminder_upcoming,
                        title, whenText);

        NotifHelper.notifyRoutine(context, slotId, title, whenText, body);
        db.routineDao().markNotified(slotId, today);
    }
}
