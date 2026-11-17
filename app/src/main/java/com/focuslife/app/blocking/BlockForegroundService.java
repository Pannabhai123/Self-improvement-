package com.focuslife.app.blocking;

import android.app.Notification;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.app.ServiceCompat;

import com.focuslife.app.R;
import com.focuslife.app.data.db.AppDatabase;
import com.focuslife.app.data.prefs.Prefs;
import com.focuslife.app.notify.NotifHelper;
import com.focuslife.app.ui.MainActivity;
import com.focuslife.app.util.TimeUtils;

/**
 * Keeps a mode (Study, Running, Sleep, Deep Focus) alive and visible.
 *
 * <h2>Why a foreground service</h2>
 * A mode has to survive the app being swiped from Recents and the screen being
 * off for hours (Sleep Mode). Only a foreground service gets that guarantee on
 * Android 10+. The trade-off is a persistent notification, which is also the
 * honest thing to show: the user should always be able to see that blocking is
 * active.
 *
 * <h2>Battery cost</h2>
 * The service holds no wake lock and runs no timer of its own. Its only
 * periodic work is a mode bookkeeping tick every {@link #TICK_MS} that
 * <ol>
 *   <li>refreshes the notification's elapsed-time text,</li>
 *   <li>re-evaluates scheduled windows so a mode that was started by schedule
 *       stops when the window closes,</li>
 *   <li>flushes the running focus session so a crash cannot lose it.</li>
 * </ol>
 * The tick is a {@link Handler} postDelayed on the main looper, which is
 * suspended automatically during Doze — that is the behaviour we want, because
 * nothing in the tick is time-critical.
 *
 * <p>The actual interception work is done by the accessibility service in
 * response to events, not by polling, so there is no continuous CPU cost.</p>
 */
public class BlockForegroundService extends Service {

    private static final String TAG = "BlockService";

    public static final String ACTION_START_MODE = "com.focuslife.app.START_MODE";
    public static final String ACTION_STOP_MODE = "com.focuslife.app.STOP_MODE";
    public static final String ACTION_REFRESH = "com.focuslife.app.REFRESH_MODE";

    public static final String EXTRA_MODE = "mode";

    /** Bookkeeping interval. Long enough to be irrelevant to battery. */
    private static final long TICK_MS = 60_000L;

    private final Handler handler = new Handler(Looper.getMainLooper());

    @Nullable
    private String currentMode;

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            try {
                onTick();
            } catch (Exception e) {
                Log.w(TAG, "tick failed", e);
            }
            handler.postDelayed(this, TICK_MS);
        }
    };

    // ------------------------------------------------------------------
    //  Public control surface
    // ------------------------------------------------------------------

    /**
     * Starts a mode. Idempotent: starting the mode that is already running just
     * refreshes the notification.
     */
    public static void startMode(@NonNull Context context, @NonNull String mode) {
        Intent intent = new Intent(context, BlockForegroundService.class)
                .setAction(ACTION_START_MODE)
                .putExtra(EXTRA_MODE, mode);
        startCompat(context, intent);
    }

    /** Stops whatever mode is running and removes the notification. */
    public static void stopMode(@NonNull Context context) {
        Intent intent = new Intent(context, BlockForegroundService.class)
                .setAction(ACTION_STOP_MODE);
        startCompat(context, intent);
    }

    /** Re-reads the mode and refreshes the notification. */
    public static void refresh(@NonNull Context context) {
        Intent intent = new Intent(context, BlockForegroundService.class)
                .setAction(ACTION_REFRESH);
        startCompat(context, intent);
    }

    /**
     * {@code startForegroundService} is mandatory on Android 8+ when the app is
     * in the background, and mandatory to pair with a {@code startForeground}
     * call within 5 seconds. We always use it and always call
     * {@code startForeground} in {@link #onStartCommand}.
     */
    private static void startCompat(@NonNull Context context, @NonNull Intent intent) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent);
            } else {
                context.startService(intent);
            }
        } catch (Exception e) {
            // IllegalStateException / ForegroundServiceStartNotAllowedException
            // when Android 12+ refuses a background start. The mode is still
            // recorded in prefs, so blocking continues via the accessibility
            // service; only the persistent notification is missing.
            Log.w(TAG, "could not start the mode service", e);
        }
    }

    /** True when a mode is recorded as active. */
    public static boolean isModeActive(@NonNull Context context) {
        String mode = Prefs.activeMode(context);
        return mode != null && BlockDecisionEngine.isKnownMode(mode);
    }

    @Nullable
    public static String activeMode(@NonNull Context context) {
        return Prefs.activeMode(context);
    }

    /** Human-readable elapsed time for the active mode, e.g. "1h 05m". */
    @NonNull
    public static String elapsedLabel(@NonNull Context context) {
        long since = Prefs.modeStartedAt(context);
        if (since <= 0) return "";
        long elapsed = System.currentTimeMillis() - since;
        if (elapsed < 0) elapsed = 0;
        return TimeUtils.formatDuration(elapsed);
    }

    // ------------------------------------------------------------------
    //  Service lifecycle
    // ------------------------------------------------------------------

    @Override
    public void onCreate() {
        super.onCreate();
        BlockState.get().attach(this);
    }

    @Override
    public int onStartCommand(@Nullable Intent intent, int flags, int startId) {
        String action = intent == null ? null : intent.getAction();
        if (action == null) action = ACTION_REFRESH;

        switch (action) {
            case ACTION_STOP_MODE:
                shutdownMode();
                return START_NOT_STICKY;

            case ACTION_START_MODE: {
                String mode = intent.getStringExtra(EXTRA_MODE);
                if (!BlockDecisionEngine.isKnownMode(mode)) {
                    Log.w(TAG, "ignoring unknown mode: " + mode);
                    shutdownMode();
                    return START_NOT_STICKY;
                }
                applyMode(mode);
                return START_STICKY;
            }

            case ACTION_REFRESH:
            default: {
                String mode = Prefs.activeMode(this);
                if (BlockDecisionEngine.isKnownMode(mode)) {
                    applyMode(mode);
                    return START_STICKY;
                }
                shutdownMode();
                return START_NOT_STICKY;
            }
        }
    }

    /**
     * Records the mode and promotes the service to the foreground.
     *
     * <p>The order matters: {@code startForeground} must happen within five
     * seconds of {@code startForegroundService}, so the notification is built
     * and posted before any database work is scheduled.</p>
     */
    private void applyMode(@NonNull String mode) {
        boolean modeChanged = !mode.equals(currentMode);
        if (modeChanged || Prefs.modeStartedAt(this) <= 0) {
            Prefs.setActiveMode(this, mode);
        }
        currentMode = mode;

        promoteToForeground(mode);

        if (modeChanged) {
            // Rebuild the policy snapshot so the accessibility service starts
            // enforcing the new mode on its very next event.
            BlockState.get().refreshAsync(this);
        }

        handler.removeCallbacks(tick);
        handler.postDelayed(tick, TICK_MS);
    }

    private void promoteToForeground(@NonNull String mode) {
        Notification notification = buildNotification(mode);
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                // Android 14 requires the declared type to be passed explicitly.
                ServiceCompat.startForeground(this, NotifHelper.NOTIF_ID_MODE, notification,
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
            } else {
                ServiceCompat.startForeground(this, NotifHelper.NOTIF_ID_MODE, notification, 0);
            }
        } catch (Exception e) {
            // MissingForegroundServiceTypeException or a denied start. The mode
            // is still recorded, so the accessibility guard keeps working.
            Log.e(TAG, "startForeground failed; mode stays active without a notification", e);
        }
    }

    private Notification buildNotification(@NonNull String mode) {
        PendingIntent content = PendingIntent.getActivity(
                this, 0,
                new Intent(this, MainActivity.class)
                        .putExtra(MainActivity.EXTRA_OPEN_TAB, MainActivity.TAB_HOME)
                        .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        PendingIntent stop = PendingIntent.getService(
                this, 1,
                new Intent(this, BlockForegroundService.class).setAction(ACTION_STOP_MODE),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        return NotifHelper.buildModeNotification(this, mode, elapsedLabel(this), content, stop);
    }

    /** Periodic bookkeeping. No wake lock, no polling of app state. */
    private void onTick() {
        String mode = Prefs.activeMode(this);
        if (!BlockDecisionEngine.isKnownMode(mode)) {
            shutdownMode();
            return;
        }
        currentMode = mode;

        // Refresh so the elapsed label stays accurate and any newly crossed
        // daily limit or opened schedule window is picked up.
        BlockState.get().refreshAsync(this);

        // Keep the running focus session's progress durable against a crash.
        AppDatabase.io().execute(() -> {
            try {
                AppDatabase db = AppDatabase.get(getApplicationContext());
                com.focuslife.app.data.db.entity.FocusSessionEntity active =
                        db.focusSessionDao().findActive();
                if (active != null) {
                    db.focusSessionDao().update(active);
                }
            } catch (Exception e) {
                Log.w(TAG, "session flush failed", e);
            }
        });

        // Rewrite the notification only when the visible text actually changed,
        // which avoids waking the notification shade once a minute for nothing.
        Notification fresh = buildNotification(mode);
        try {
            NotifHelper.updateModeNotification(this, fresh);
        } catch (Exception e) {
            Log.w(TAG, "notification refresh failed", e);
        }
    }

    private void shutdownMode() {
        handler.removeCallbacks(tick);
        currentMode = null;
        Prefs.setActiveMode(this, null);
        BlockState.get().refreshAsync(this);
        try {
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE);
        } catch (Exception e) {
            Log.w(TAG, "stopForeground failed", e);
        }
        stopSelf();
    }

    @Override
    public void onTaskRemoved(Intent rootIntent) {
        // The user swiped Focus Life from Recents. The mode should survive that:
        // it was a deliberate commitment. Re-arm the service so Sleep Mode still
        // guards the night after the app is dismissed.
        String mode = Prefs.activeMode(this);
        if (BlockDecisionEngine.isKnownMode(mode)) {
            try {
                Intent restart = new Intent(getApplicationContext(), BlockForegroundService.class)
                        .setAction(ACTION_REFRESH);
                startCompat(getApplicationContext(), restart);
            } catch (Exception e) {
                Log.w(TAG, "could not re-arm after task removal", e);
            }
        } else {
            stopSelf();
        }
        super.onTaskRemoved(rootIntent);
    }

    @Override
    public void onDestroy() {
        handler.removeCallbacks(tick);
        super.onDestroy();
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        // Not a bound service.
        return null;
    }
}
