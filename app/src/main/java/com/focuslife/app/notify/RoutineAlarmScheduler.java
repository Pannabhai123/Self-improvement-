package com.focuslife.app.notify;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.os.Build;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.focuslife.app.data.db.AppDatabase;
import com.focuslife.app.data.db.entity.BlockScheduleEntity;
import com.focuslife.app.data.db.entity.RoutineSlotEntity;
import com.focuslife.app.data.prefs.Prefs;
import com.focuslife.app.util.PermissionManager;
import com.focuslife.app.util.TimeUtils;

import java.util.Calendar;
import java.util.List;

/**
 * Arms exactly one alarm: the next routine reminder that is due.
 *
 * <h2>Why this exists alongside {@code ReminderWorker}</h2>
 * WorkManager's minimum periodic interval is 15 minutes, so a slot with a
 * 5-minute lead time could be announced up to 15 minutes late — which for
 * "04:30 wake up" means the reminder arrives after the moment it was meant to
 * protect. The polling worker is kept as a safety net (it survives reboot and
 * process death without any alarm bookkeeping), and this scheduler provides the
 * precision on top of it.
 *
 * <h2>Why one alarm and not thirteen</h2>
 * A full routine of thirteen slots would need thirteen alarms, each of which has
 * to be cancelled and re-armed on every edit, every day-mask change and every
 * reboot. Keeping a single pending alarm for the next due slot means there is
 * exactly one thing to get right, and re-arming is a single call after each fire.
 *
 * <h2>Exact alarms and honesty about them</h2>
 * Android 12 (API 31) requires {@code SCHEDULE_EXACT_ALARM} for exact alarms and
 * the user can revoke it. When it is unavailable this falls back to an inexact
 * window rather than silently failing, and {@link #canScheduleExact(Context)}
 * lets the UI tell the user that reminders may be approximate. The polling worker
 * still guarantees delivery either way.
 */
public final class RoutineAlarmScheduler {

    private RoutineAlarmScheduler() {
    }

    private static final int REQUEST_CODE = 7101;

    /** How far ahead to look for the next due slot, in milliseconds. */
    private static final long LOOKAHEAD_MS = 8 * TimeUtils.HOUR_MS;

    /**
     * Whether exact alarms can be scheduled right now.
     *
     * <p>Below Android 12 exact alarms need no permission. From 12 on, they need
     * {@code SCHEDULE_EXACT_ALARM}, which the user can withdraw at any time.</p>
     */
    public static boolean canScheduleExact(@NonNull Context context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            return true;
        }
        AlarmManager am = alarmManager(context);
        return am != null && am.canScheduleExactAlarms();
    }

    /**
     * Schedules the next due reminder, replacing any alarm already pending.
     *
     * <p>Safe to call from anywhere and as often as needed: it is idempotent, and
     * every routine edit, boot and reminder fire calls it again.</p>
     */
    public static void scheduleNext(@NonNull final Context context) {
        final Context app = context.getApplicationContext();
        AppDatabase.io().execute(() -> {
            try {
                doSchedule(app);
            } catch (Exception e) {
                android.util.Log.w("RoutineAlarm", "could not schedule the next reminder", e);
            }
        });
    }

    private static void doSchedule(@NonNull Context context) {
        AlarmManager am = alarmManager(context);
        if (am == null) return;

        PendingIntent pending = pendingIntent(context);
        // Always clear first: a stale alarm for a slot that has since been edited
        // would fire with the old time and the old title.
        am.cancel(pending);

        if (!PermissionManager.canPostNotifications(context)) {
            // Arming an alarm that cannot post is wasted work and a wake-up for
            // nothing, which matters on a 4 GB battery-constrained device.
            return;
        }

        List<RoutineSlotEntity> slots;
        try {
            slots = AppDatabase.get(context).routineDao().reminderSlots();
        } catch (Exception e) {
            android.util.Log.w("RoutineAlarm", "could not read the routine", e);
            return;
        }
        if (slots == null || slots.isEmpty()) return;

        long now = System.currentTimeMillis();
        String todayKey = TimeUtils.today();
        long horizon = now + LOOKAHEAD_MS;

        RoutineSlotEntity best = null;
        long bestAt = Long.MAX_VALUE;

        for (RoutineSlotEntity slot : slots) {
            if (slot == null || !slot.enabled) continue;
            // A slot already announced today must not fire twice, so the worker's
            // once-per-day flag is respected here too.
            if (todayKey.equals(slot.notifiedDayKey)) continue;
            if (!dayMaskIncludesToday(slot.daysMask)) continue;

            int lead = Math.max(0, slot.remindBeforeMin);
            long fireAt = TimeUtils.millisForMinute(todayKey, slot.minuteOfDay)
                    - lead * TimeUtils.MINUTE_MS;

            if (fireAt < now) {
                // The slot's lead time has already passed today. If the slot itself
                // is still in the future, remind immediately; otherwise skip to
                // tomorrow rather than announcing something that already happened.
                long slotStart = TimeUtils.millisForMinute(todayKey, slot.minuteOfDay);
                if (slotStart < now) continue;
                fireAt = now + 5_000L;
            }
            if (fireAt > horizon) continue;

            if (fireAt < bestAt) {
                bestAt = fireAt;
                best = slot;
            }
        }

        if (best == null) return;

        // Carry the slot id so the receiver can mark exactly this slot notified,
        // and the title so the notification is correct even if the slot is edited
        // between arming and firing.
        android.content.Intent intent = new android.content.Intent(context, RoutineAlarmReceiver.class);
        intent.setAction(RoutineAlarmReceiver.ACTION_ROUTINE_DUE);
        intent.putExtra(RoutineAlarmReceiver.EXTRA_SLOT_ID, best.id);
        pending = PendingIntent.getBroadcast(context, REQUEST_CODE, intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        try {
            if (canScheduleExact(context)) {
                // setExactAndAllowWhileIdle rather than setWindow: a routine
                // reminder that drifts by a minute is still useful, but one that
                // drifts by fifteen defeats the purpose of the lead time.
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, bestAt, pending);
            } else {
                // Inexact fallback. Delivery is still guaranteed by the polling
                // ReminderWorker; this just improves the odds of being on time.
                am.setWindow(AlarmManager.RTC_WAKEUP, bestAt, 10 * TimeUtils.MINUTE_MS, pending);
            }
            Prefs.setNextRoutineAlarmAt(context, bestAt);
        } catch (SecurityException e) {
            // Revoked between the check and the call. Degrade to inexact rather
            // than dropping the reminder entirely.
            android.util.Log.w("RoutineAlarm", "exact alarm denied; using an inexact window", e);
            am.setWindow(AlarmManager.RTC_WAKEUP, bestAt, 10 * TimeUtils.MINUTE_MS, pending);
        }
    }

    /** Cancels any pending alarm, for example when notifications are turned off. */
    public static void cancel(@NonNull Context context) {
        AlarmManager am = alarmManager(context);
        if (am == null) return;
        am.cancel(pendingIntent(context));
        Prefs.setNextRoutineAlarmAt(context, 0L);
    }

    @NonNull
    private static PendingIntent pendingIntent(@NonNull Context context) {
        android.content.Intent intent = new android.content.Intent(context, RoutineAlarmReceiver.class);
        intent.setAction(RoutineAlarmReceiver.ACTION_ROUTINE_DUE);
        return PendingIntent.getBroadcast(context, REQUEST_CODE, intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    @Nullable
    private static AlarmManager alarmManager(@NonNull Context context) {
        return (AlarmManager) context.getApplicationContext()
                .getSystemService(Context.ALARM_SERVICE);
    }

    /**
     * Whether a slot's day mask covers today.
     *
     * <p>{@link BlockScheduleEntity}'s bit for {@link Calendar} day-of-week
     * {@code d} is {@code 1 << (d - 1)}, which lines up with {@code Calendar}'s
     * 1-based Sunday-first numbering.</p>
     */
    static boolean dayMaskIncludesToday(int daysMask) {
        if (daysMask <= 0) return true;
        Calendar cal = Calendar.getInstance();
        int bit = 1 << (cal.get(Calendar.DAY_OF_WEEK) - 1);
        return (daysMask & bit) != 0;
    }
}
