package com.focuslife.app.notify;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationChannelGroup;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;

import com.focuslife.app.R;
import com.focuslife.app.blocking.BlockDecisionEngine;
import com.focuslife.app.ui.MainActivity;
import com.focuslife.app.ui.report.ReportActivity;
import com.focuslife.app.util.PermissionManager;
import com.focuslife.app.util.TimeUtils;

/**
 * All notification construction and channel management.
 *
 * <h2>Channel design</h2>
 * Four channels, separated by how much the user should be able to tune them:
 * <ul>
 *   <li>{@code modes} — LOW importance. Persistent, silent, no sound or peek.
 *       It is a status indicator, not an interruption.</li>
 *   <li>{@code reminders} — DEFAULT. Routine, revision, workout and habit
 *       reminders. These are the ones the user actually wants to hear.</li>
 *   <li>{@code reports} — LOW. The daily summary; useful but not urgent.</li>
 *   <li>{@code limits} — HIGH. A daily ceiling was crossed, which is the moment
 *       the app has something genuinely important to say.</li>
 * </ul>
 *
 * <p>Every notification carries an explicit small icon and colour so the tray
 * does not fall back to a grey square, and every {@code PendingIntent} is
 * {@code FLAG_IMMUTABLE} as required on Android 12+.</p>
 */
public final class NotifHelper {

    public static final String CHANNEL_MODES = "fl_modes";
    public static final String CHANNEL_REMINDERS = "fl_reminders";
    public static final String CHANNEL_REPORTS = "fl_reports";
    public static final String CHANNEL_LIMITS = "fl_limits";

    private static final String GROUP_MAIN = "fl_group_main";

    /** Stable IDs so an updated notification replaces rather than stacks. */
    public static final int NOTIF_ID_MODE = 1001;
    public static final int NOTIF_ID_FOCUS = 1002;
    public static final int NOTIF_ID_DAILY_REPORT = 1003;

    /** Reminder ids are derived from the row id, offset into a distinct range. */
    private static final int ROUTINE_ID_BASE = 2000;
    private static final int REVISION_ID_BASE = 3000;
    private static final int WORKOUT_ID_BASE = 4000;
    private static final int HABIT_ID_BASE = 5000;
    private static final int LIMIT_ID_BASE = 6000;

    private NotifHelper() {
    }

    // ------------------------------------------------------------------
    //  Channels
    // ------------------------------------------------------------------

    /** Creates every channel. Idempotent; safe to call on each app start. */
    public static void createChannels(@NonNull Context context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;

        NotificationManager nm = context.getSystemService(NotificationManager.class);
        if (nm == null) return;

        NotificationChannelGroup group = new NotificationChannelGroup(
                GROUP_MAIN, context.getString(R.string.app_name));
        group.setDescription(context.getString(R.string.app_tagline));
        try {
            nm.createNotificationChannelGroup(group);
        } catch (Exception ignored) {
            // Some OEM builds reject groups; channels still work without one.
        }

        NotificationChannel modes = new NotificationChannel(
                CHANNEL_MODES,
                context.getString(R.string.notif_channel_modes_name),
                NotificationManager.IMPORTANCE_LOW);
        modes.setDescription(context.getString(R.string.notif_channel_modes_desc));
        modes.setShowBadge(false);
        modes.enableLights(false);
        modes.enableVibration(false);
        modes.setGroup(GROUP_MAIN);
        // A mode notification must not be dismissible-by-swipe-confusion, but
        // the platform requires ongoing notifications to be clearable on some
        // builds, so we leave lockscreen visibility private instead.
        modes.setLockscreenVisibility(Notification.VISIBILITY_SECRET);

        NotificationChannel reminders = new NotificationChannel(
                CHANNEL_REMINDERS,
                context.getString(R.string.notif_channel_reminders_name),
                NotificationManager.IMPORTANCE_DEFAULT);
        reminders.setDescription(context.getString(R.string.notif_channel_reminders_desc));
        reminders.setShowBadge(true);
        reminders.enableVibration(true);
        reminders.setGroup(GROUP_MAIN);
        reminders.setLockscreenVisibility(Notification.VISIBILITY_PRIVATE);

        NotificationChannel reports = new NotificationChannel(
                CHANNEL_REPORTS,
                context.getString(R.string.notif_channel_reports_name),
                NotificationManager.IMPORTANCE_LOW);
        reports.setDescription(context.getString(R.string.notif_channel_reports_desc));
        reports.setShowBadge(false);
        reports.setGroup(GROUP_MAIN);
        reports.setLockscreenVisibility(Notification.VISIBILITY_PRIVATE);

        NotificationChannel limits = new NotificationChannel(
                CHANNEL_LIMITS,
                context.getString(R.string.notif_channel_limits_name),
                NotificationManager.IMPORTANCE_HIGH);
        limits.setDescription(context.getString(R.string.notif_channel_limits_desc));
        limits.setShowBadge(true);
        limits.enableVibration(true);
        limits.setGroup(GROUP_MAIN);
        limits.setLockscreenVisibility(Notification.VISIBILITY_PRIVATE);

        nm.createNotificationChannels(java.util.Arrays.asList(modes, reminders, reports, limits));
    }

    // ------------------------------------------------------------------
    //  Mode notification
    // ------------------------------------------------------------------

    @NonNull
    public static Notification buildModeNotification(@NonNull Context context,
                                                     @NonNull String mode,
                                                     @NonNull String elapsed,
                                                     @NonNull PendingIntent content,
                                                     @Nullable PendingIntent stop) {
        int modeNameRes = modeNameRes(mode);
        int modeIcon = modeIcon(mode);

        String title = context.getString(R.string.mode_running_notification,
                context.getString(modeNameRes));
        String body = elapsed.isEmpty()
                ? context.getString(R.string.mode_stop_hold_hint)
                : context.getString(R.string.mode_active_since, elapsed);

        NotificationCompat.Builder b = base(context, CHANNEL_MODES)
                .setContentTitle(title)
                .setContentText(body)
                .setSmallIcon(modeIcon)
                .setContentIntent(content)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setSilent(true)
                .setCategory(NotificationCompat.CATEGORY_SERVICE)
                .setVisibility(NotificationCompat.VISIBILITY_SECRET)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setTimeoutAfter(0);

        if (stop != null) {
            b.addAction(R.drawable.ic_stop, context.getString(R.string.notif_action_stop), stop);
        }
        // Expanding the notification spells out how to end the mode, which is
        // the one thing a user staring at a persistent notification needs.
        b.setStyle(new NotificationCompat.BigTextStyle()
                .bigText(body + "\n\n" + context.getString(R.string.mode_stop_hold_hint)));
        return b.build();
    }

    public static void updateModeNotification(@NonNull Context context, @NonNull Notification n) {
        notify(context, NOTIF_ID_MODE, n);
    }

    /** Localised name for a mode key. */
    public static int modeNameRes(@NonNull String mode) {
        switch (mode) {
            case BlockDecisionEngine.MODE_STUDY: return R.string.mode_study;
            case BlockDecisionEngine.MODE_RUN:   return R.string.mode_run;
            case BlockDecisionEngine.MODE_SLEEP: return R.string.mode_sleep;
            case BlockDecisionEngine.MODE_FOCUS: return R.string.mode_deep_focus;
            default:                             return R.string.mode_none;
        }
    }

    public static int modeIcon(@NonNull String mode) {
        switch (mode) {
            case BlockDecisionEngine.MODE_STUDY: return R.drawable.ic_book;
            case BlockDecisionEngine.MODE_RUN:   return R.drawable.ic_directions_run;
            case BlockDecisionEngine.MODE_SLEEP: return R.drawable.ic_night;
            case BlockDecisionEngine.MODE_FOCUS: return R.drawable.ic_target;
            default:                             return R.drawable.ic_shield;
        }
    }

    // ------------------------------------------------------------------
    //  Focus timer notification
    // ------------------------------------------------------------------

    @NonNull
    public static Notification buildFocusNotification(@NonNull Context context,
                                                      @NonNull String remainingText,
                                                      @NonNull String taskLabel,
                                                      boolean isBreak,
                                                      @NonNull PendingIntent content) {
        String title = isBreak
                ? context.getString(R.string.focus_break_title)
                : context.getString(R.string.focus_current_task);
        String body = isBreak
                ? context.getString(R.string.notif_focus_break, remainingText)
                : context.getString(R.string.notif_focus_running, remainingText,
                        taskLabel.isEmpty() ? context.getString(R.string.quick_deep_focus) : taskLabel);

        return base(context, CHANNEL_MODES)
                .setContentTitle(title)
                .setContentText(body)
                .setSmallIcon(isBreak ? R.drawable.ic_self_improvement : R.drawable.ic_timer)
                .setContentIntent(content)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setSilent(true)
                .setCategory(NotificationCompat.CATEGORY_PROGRESS)
                .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setStyle(new NotificationCompat.BigTextStyle().bigText(body))
                .build();
    }

    public static void updateFocusNotification(@NonNull Context context, @NonNull Notification n) {
        notify(context, NOTIF_ID_FOCUS, n);
    }

    // ------------------------------------------------------------------
    //  Reminders
    // ------------------------------------------------------------------

    public static void notifyRoutine(@NonNull Context context, long slotId,
                                     @NonNull String title, @NonNull String whenText,
                                     @NonNull String bodyText) {
        PendingIntent content = mainIntent(context, MainActivity.TAB_HOME);
        Notification n = base(context, CHANNEL_REMINDERS)
                .setContentTitle(whenText.isEmpty()
                        ? context.getString(R.string.notif_routine_title_now, title)
                        : context.getString(R.string.notif_routine_title, title, whenText))
                .setContentText(bodyText)
                .setSmallIcon(R.drawable.ic_alarm)
                .setContentIntent(content)
                .setAutoCancel(true)
                .setCategory(NotificationCompat.CATEGORY_REMINDER)
                .setStyle(new NotificationCompat.BigTextStyle().bigText(bodyText))
                .build();
        notify(context, ROUTINE_ID_BASE + (int) (slotId % 900), n);
    }

    public static void notifyRevisionDue(@NonNull Context context, int dueCount,
                                         @NonNull String firstTopic) {
        Notification n = base(context, CHANNEL_REMINDERS)
                .setContentTitle(context.getString(R.string.notif_revision_title))
                .setContentText(context.getString(R.string.notif_revision_body, dueCount, firstTopic))
                .setSmallIcon(R.drawable.ic_undo)
                .setContentIntent(mainIntent(context, MainActivity.TAB_STUDY))
                .setAutoCancel(true)
                .setCategory(NotificationCompat.CATEGORY_REMINDER)
                .build();
        notify(context, REVISION_ID_BASE, n);
    }

    public static void notifyWorkout(@NonNull Context context) {
        Notification n = base(context, CHANNEL_REMINDERS)
                .setContentTitle(context.getString(R.string.notif_workout_title))
                .setContentText(context.getString(R.string.notif_workout_body))
                .setSmallIcon(R.drawable.ic_fitness_center)
                .setContentIntent(mainIntent(context, MainActivity.TAB_HOME))
                .setAutoCancel(true)
                .setCategory(NotificationCompat.CATEGORY_REMINDER)
                .build();
        notify(context, WORKOUT_ID_BASE, n);
    }

    public static void notifyHabitsRemaining(@NonNull Context context, int done, int total) {
        Notification n = base(context, CHANNEL_REMINDERS)
                .setContentTitle(context.getString(R.string.notif_habit_title))
                .setContentText(context.getString(R.string.notif_habit_body, done, total))
                .setSmallIcon(R.drawable.ic_check_circle)
                .setContentIntent(mainIntent(context, MainActivity.TAB_HABITS))
                .setAutoCancel(true)
                .setCategory(NotificationCompat.CATEGORY_REMINDER)
                .build();
        notify(context, HABIT_ID_BASE, n);
    }

    public static void notifyLimitReached(@NonNull Context context, @NonNull String appLabel,
                                          int usedMinutes, int limitMinutes) {
        Notification n = base(context, CHANNEL_LIMITS)
                .setContentTitle(context.getString(R.string.notif_limit_title, appLabel))
                .setContentText(context.getString(R.string.notif_limit_body,
                        TimeUtils.formatMinutes(usedMinutes), TimeUtils.formatMinutes(limitMinutes)))
                .setSmallIcon(R.drawable.ic_warning)
                .setContentIntent(mainIntent(context, MainActivity.TAB_PROFILE))
                .setAutoCancel(true)
                .setCategory(NotificationCompat.CATEGORY_REMINDER)
                .setColor(context.getColor(R.color.warning_500))
                .build();
        // Hash the label so different apps get different notification ids and
        // therefore do not overwrite each other.
        notify(context, LIMIT_ID_BASE + (Math.abs(appLabel.hashCode()) % 900), n);
    }

    /** Fallback when the warning activity could not be shown over a blocked app. */
    public static void notifyBlockAttempt(@NonNull Context context, @NonNull String appLabel) {
        Notification n = base(context, CHANNEL_LIMITS)
                .setContentTitle(context.getString(R.string.notif_block_title, appLabel))
                .setContentText(context.getString(R.string.notif_block_body, appLabel))
                .setSmallIcon(R.drawable.ic_block)
                .setContentIntent(mainIntent(context, MainActivity.TAB_HOME))
                .setAutoCancel(true)
                .setCategory(NotificationCompat.CATEGORY_REMINDER)
                .build();
        notify(context, LIMIT_ID_BASE + (Math.abs(appLabel.hashCode()) % 900), n);
    }

    public static void notifyDailyReport(@NonNull Context context, int score,
                                         @NonNull String studyText, @NonNull String screenText,
                                         @NonNull String dayKey) {
        PendingIntent content = PendingIntent.getActivity(
                context, 0,
                ReportActivity.forDay(context, dayKey)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Notification n = base(context, CHANNEL_REPORTS)
                .setContentTitle(context.getString(R.string.notif_report_title))
                .setContentText(context.getString(R.string.notif_report_body, score, studyText, screenText))
                .setSmallIcon(R.drawable.ic_assessment)
                .setContentIntent(content)
                .setAutoCancel(true)
                .setCategory(NotificationCompat.CATEGORY_REMINDER)
                .setStyle(new NotificationCompat.BigTextStyle()
                        .bigText(context.getString(R.string.notif_report_body, score, studyText, screenText)))
                .build();
        notify(context, NOTIF_ID_DAILY_REPORT, n);
    }

    // ------------------------------------------------------------------
    //  Internals
    // ------------------------------------------------------------------

    @NonNull
    private static NotificationCompat.Builder base(@NonNull Context context, @NonNull String channelId) {
        return new NotificationCompat.Builder(context, channelId)
                .setColor(context.getColor(R.color.accent_500))
                .setShowWhen(true)
                .setLocalOnly(true);
    }

    @NonNull
    private static PendingIntent mainIntent(@NonNull Context context, int tab) {
        return PendingIntent.getActivity(
                context, tab,
                new Intent(context, MainActivity.class)
                        .putExtra(MainActivity.EXTRA_OPEN_TAB, tab)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    /**
     * Posts a notification, silently doing nothing when the permission is
     * missing. {@code SecurityException} here would crash the app for a feature
     * the user deliberately declined.
     */
    private static void notify(@NonNull Context context, int id, @NonNull Notification n) {
        if (!PermissionManager.canPostNotifications(context)) return;
        try {
            NotificationManagerCompat.from(context).notify(id, n);
        } catch (SecurityException e) {
            // Race between the permission check and the post.
        } catch (Exception e) {
            android.util.Log.w("NotifHelper", "notify failed for id " + id, e);
        }
    }

    public static void cancel(@NonNull Context context, int id) {
        try {
            NotificationManagerCompat.from(context).cancel(id);
        } catch (Exception ignored) {
        }
    }

    public static void cancelAll(@NonNull Context context) {
        try {
            NotificationManagerCompat.from(context).cancelAll();
        } catch (Exception ignored) {
        }
    }
}
