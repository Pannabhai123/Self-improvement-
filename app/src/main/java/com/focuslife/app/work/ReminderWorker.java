package com.focuslife.app.work;

import android.content.Context;
import android.text.TextUtils;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

import com.focuslife.app.R;
import com.focuslife.app.data.db.AppDatabase;
import com.focuslife.app.data.db.entity.RoutineSlotEntity;
import com.focuslife.app.data.db.entity.StudyTopicEntity;
import com.focuslife.app.data.prefs.Prefs;
import com.focuslife.app.notify.NotifHelper;
import com.focuslife.app.util.PermissionManager;
import com.focuslife.app.util.TimeUtils;

import java.util.List;

/**
 * Fires routine, revision, workout and habit reminders.
 *
 * <h2>Why polling rather than AlarmManager</h2>
 * The routine is fully user-editable, and each slot has a configurable
 * lead time. With thirteen default slots that is up to thirteen exact alarms to
 * maintain, and every edit, timezone change or app update would have to
 * reschedule all of them. A 30-minute periodic worker with a "have I already
 * notified for this slot today" flag is dramatically simpler, survives process
 * death and reboots without a {@code BootReceiver} dance, and its worst-case
 * lateness is bounded by the lead time the user chose.
 *
 * <p>{@code BootReceiver} additionally re-enqueues the workers after a restart,
 * so reminders resume without the user opening the app.</p>
 *
 * <h2>Cost</h2>
 * Each run performs a handful of indexed queries and, in the common case, posts
 * nothing. WorkManager applies its own Doze-aware batching, so this does not
 * wake the device on a fixed cadence during deep sleep.
 */
public class ReminderWorker extends Worker {

    private static final String TAG = "ReminderWorker";

    public static final String UNIQUE_NAME = "focuslife_reminders";

    /** Evening hour at which the "habits still open" nudge is allowed. */
    private static final int HABIT_NUDGE_HOUR = 21;

    public ReminderWorker(@NonNull Context context, @NonNull WorkerParameters params) {
        super(context, params);
    }

    @NonNull
    @Override
    public Result doWork() {
        Context context = getApplicationContext();

        if (!PermissionManager.canPostNotifications(context)) {
            // The user turned notifications off. Continuing would be a no-op
            // that still costs queries, so return early.
            return Result.success();
        }

        try {
            AppDatabase db = AppDatabase.get(context);
            long now = System.currentTimeMillis();
            int minuteNow = TimeUtils.minuteOfDay(now);
            String today = TimeUtils.today();

            int posted = 0;
            posted += postRoutineReminders(context, db, minuteNow, today);
            posted += postRevisionReminder(context, db, now);
            posted += postWorkoutReminder(context, db, minuteNow, today);
            posted += postHabitNudge(context, db, minuteNow, today);

            if (posted > 0) {
                Log.i(TAG, "posted " + posted + " reminder(s)");
            }

            // Keep the precise alarm pointed at the next slot. This is what makes
            // the poll and the alarm cooperate: the poll guarantees delivery, the
            // alarm guarantees punctuality, and each pass re-syncs them.
            com.focuslife.app.notify.RoutineAlarmScheduler.scheduleNext(context);

            return Result.success();
        } catch (Exception e) {
            Log.e(TAG, "reminder pass failed", e);
            return Result.retry();
        }
    }

    // ------------------------------------------------------------------
    //  Routine
    // ------------------------------------------------------------------

    /**
     * Notifies for each enabled slot whose reminder time has arrived and which
     * has not already been notified today.
     *
     * <p>A slot is due when {@code minuteNow >= slotMinute - leadTime} and
     * {@code minuteNow < slotMinute + CATCHUP_WINDOW}. The window matters: a
     * phone that was in Doze at 04:25 should still say "running at 05:00" when
     * it wakes at 04:40, but must not announce a 05:00 slot at 19:00.</p>
     */
    private int postRoutineReminders(@NonNull Context context, @NonNull AppDatabase db,
                                     int minuteNow, @NonNull String today) {
        List<RoutineSlotEntity> slots = db.routineDao().reminderSlots();
        if (slots == null || slots.isEmpty()) return 0;

        int posted = 0;
        for (RoutineSlotEntity slot : slots) {
            if (slot == null || !slot.enabled) continue;
            if (!isScheduledForToday(slot)) continue;

            int lead = Math.max(0, slot.remindBeforeMin);
            int dueMinute = slot.minuteOfDay - lead;
            // Wrap past midnight so a 00:15 slot with a 30-minute lead is due at
            // 23:45 the previous evening rather than never.
            if (dueMinute < 0) dueMinute += 1440;

            int delta = minuteNow - dueMinute;
            if (delta < 0) delta += 1440;
            if (delta > CATCHUP_WINDOW_MIN) continue;

            if (today.equals(slot.notifiedDayKey)) continue;

            String title = resolveString(context, slot.titleRes, slot.title);
            if (TextUtils.isEmpty(title)) continue;

            int minutesUntil = slot.minuteOfDay - minuteNow;
            if (minutesUntil < -720) minutesUntil += 1440;
            if (minutesUntil > 720) minutesUntil -= 1440;

            String whenText = minutesUntil <= 0
                    ? ""
                    : TimeUtils.formatMinutes(minutesUntil);

            RoutineSlotEntity next = db.routineDao().nextSlot(minuteNow);
            String body = next == null
                    ? title
                    : context.getString(R.string.notif_routine_body,
                            resolveString(context, next.titleRes, next.title),
                            formatClock(context, next.minuteOfDay));

            NotifHelper.notifyRoutine(context, slot.id, title, whenText, body);

            db.routineDao().markNotified(slot.id, today);
            posted++;
        }
        return posted;
    }

    /** How long after its due minute a reminder is still worth posting. */
    private static final int CATCHUP_WINDOW_MIN = 30;

    /** Respects the per-slot day mask so a weekday-only slot stays quiet at weekends. */
    private boolean isScheduledForToday(@NonNull RoutineSlotEntity slot) {
        java.util.Calendar c = java.util.Calendar.getInstance();
        int bit = 1 << (c.get(java.util.Calendar.DAY_OF_WEEK) - 1);
        return (slot.daysMask & bit) != 0;
    }

    // ------------------------------------------------------------------
    //  Revision
    // ------------------------------------------------------------------

    /**
     * Notifies once per day when spaced-repetition revisions are due.
     *
     * <p>Capped at one notification rather than one per topic: a revision system
     * that nags six times gets switched off, and the study screen lists every
     * due topic anyway.</p>
     */
    private int postRevisionReminder(@NonNull Context context, @NonNull AppDatabase db, long now) {
        int due = db.studyDao().revisionsDueCount(now);
        if (due <= 0) return 0;

        String today = TimeUtils.today();
        if (alreadyNotifiedToday(context, PREF_KEY_REVISION_DAY, today)) return 0;

        String firstTopic = firstDueTopic(db, now);
        NotifHelper.notifyRevisionDue(context, due, firstTopic);
        markNotifiedToday(context, PREF_KEY_REVISION_DAY, today);
        return 1;
    }

    /** Reads the first due topic synchronously (LiveData cannot be used here). */
    private String firstDueTopic(@NonNull AppDatabase db, long now) {
        List<StudyTopicEntity> topics = db.studyDao().dueTopics(now, 1);
        if (topics == null || topics.isEmpty()) return "";
        StudyTopicEntity t = topics.get(0);
        return t == null || t.chapter == null ? "" : t.chapter;
    }

    // ------------------------------------------------------------------
    //  Workout
    // ------------------------------------------------------------------

    /** Fires once a day at the exercise slot, if the user has one. */
    private int postWorkoutReminder(@NonNull Context context, @NonNull AppDatabase db,
                                    int minuteNow, @NonNull String today) {
        // Find the routine slot categorised as fitness in the evening, which is
        // the default exercise time, and use its minute as the trigger.
        List<RoutineSlotEntity> slots = db.routineDao().reminderSlots();
        if (slots == null) return 0;

        RoutineSlotEntity exerciseSlot = null;
        for (RoutineSlotEntity s : slots) {
            if (s != null && RoutineSlotEntity.CATEGORY_FITNESS.equals(s.category)
                    && s.minuteOfDay >= 12 * 60) {
                exerciseSlot = s;
                break;
            }
        }
        if (exerciseSlot == null) return 0;

        int delta = minuteNow - exerciseSlot.minuteOfDay;
        if (delta < 0) delta += 1440;
        if (delta > CATCHUP_WINDOW_MIN) return 0;

        if (alreadyNotifiedToday(context, PREF_KEY_WORKOUT_DAY, today)) return 0;

        // Do not nag when the checklist is already done.
        int done = db.fitnessDao().workoutsDoneOnDay(today);
        int target = Prefs.targetWorkouts(context);
        if (target > 0 && done >= target) return 0;

        NotifHelper.notifyWorkout(context);
        markNotifiedToday(context, PREF_KEY_WORKOUT_DAY, today);
        return 1;
    }

    // ------------------------------------------------------------------
    //  Habit nudge
    // ------------------------------------------------------------------

    /**
     * A single evening nudge listing how many habits are still open.
     *
     * <p>Wording is deliberately non-shaming: it names what is left and points at
     * the easiest next action. It also does not fire at all when everything is
     * already done, or when there are no habits configured.</p>
     */
    private int postHabitNudge(@NonNull Context context, @NonNull AppDatabase db,
                               int minuteNow, @NonNull String today) {
        if (TimeUtils.hourOfDayNow() < HABIT_NUDGE_HOUR) return 0;
        if (minuteNow < HABIT_NUDGE_HOUR * 60) return 0;
        if (alreadyNotifiedToday(context, PREF_KEY_HABIT_DAY, today)) return 0;

        int total = db.habitDao().activeHabits().size();
        if (total <= 0) return 0;
        int done = db.habitDao().doneCountOnDay(today);
        if (done >= total) return 0;

        NotifHelper.notifyHabitsRemaining(context, done, total);
        markNotifiedToday(context, PREF_KEY_HABIT_DAY, today);
        return 1;
    }

    // ------------------------------------------------------------------
    //  Once-per-day flags
    // ------------------------------------------------------------------

    private static final String PREF_KEY_REVISION_DAY = "notified_revision_day";
    private static final String PREF_KEY_WORKOUT_DAY = "notified_workout_day";
    private static final String PREF_KEY_HABIT_DAY = "notified_habit_day";
    private static final String PREF_FILE = "focuslife_state";

    private static boolean alreadyNotifiedToday(@NonNull Context context,
                                                @NonNull String key, @NonNull String today) {
        return today.equals(context.getSharedPreferences(PREF_FILE, Context.MODE_PRIVATE)
                .getString(key, ""));
    }

    private static void markNotifiedToday(@NonNull Context context,
                                          @NonNull String key, @NonNull String today) {
        context.getSharedPreferences(PREF_FILE, Context.MODE_PRIVATE)
                .edit().putString(key, today).apply();
    }

    @NonNull
    private static String resolveString(@NonNull Context context, int resId, @Nullable String fallback) {
        if (resId != 0) {
            try {
                return context.getString(resId);
            } catch (Exception ignored) {
                // Fall through to the stored text.
            }
        }
        return fallback == null ? "" : fallback;
    }

    @NonNull
    private static String formatClock(@NonNull Context context, int minuteOfDay) {
        return Prefs.use24HourClock(context)
                ? TimeUtils.formatClock24(minuteOfDay)
                : TimeUtils.formatClock12(minuteOfDay);
    }
}
