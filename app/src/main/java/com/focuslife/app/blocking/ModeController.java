package com.focuslife.app.blocking;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.view.MotionEvent;
import android.view.View;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.focuslife.app.R;
import com.focuslife.app.data.prefs.Prefs;
import com.focuslife.app.notify.NotifHelper;
import com.focuslife.app.util.PermissionManager;
import com.focuslife.app.util.TimeUtils;

/**
 * The single entry point for starting, stopping and inspecting modes.
 *
 * <p>Keeping this in one class means the UI never has to remember that starting
 * a mode involves four coordinated steps: writing the mode to prefs, starting
 * the foreground service, refreshing the block snapshot, and telling the user
 * whether enforcement will actually work given the permissions they granted.</p>
 */
public final class ModeController {

    private static final String TAG = "ModeController";

    private ModeController() {
    }

    // ------------------------------------------------------------------
    //  Queries
    // ------------------------------------------------------------------

    @Nullable
    public static String activeMode(@NonNull Context context) {
        String mode = Prefs.activeMode(context);
        return BlockDecisionEngine.isKnownMode(mode) ? mode : null;
    }

    public static boolean isActive(@NonNull Context context) {
        return activeMode(context) != null;
    }

    public static boolean isActive(@NonNull Context context, @NonNull String mode) {
        return mode.equals(activeMode(context));
    }

    @NonNull
    public static String elapsedLabel(@NonNull Context context) {
        return BlockForegroundService.elapsedLabel(context);
    }

    /** Localised display name for a mode key. */
    @NonNull
    public static String modeName(@NonNull Context context, @NonNull String mode) {
        return context.getString(NotifHelper.modeNameRes(mode));
    }

    /** The explanatory line shown under each mode card. */
    @NonNull
    public static String modeDescription(@NonNull Context context, @NonNull String mode) {
        int res;
        switch (mode) {
            case BlockDecisionEngine.MODE_STUDY: res = R.string.mode_study_desc; break;
            case BlockDecisionEngine.MODE_RUN:   res = R.string.mode_run_desc; break;
            case BlockDecisionEngine.MODE_SLEEP: res = R.string.mode_sleep_desc; break;
            case BlockDecisionEngine.MODE_FOCUS: res = R.string.mode_deep_focus_desc; break;
            default: return context.getString(R.string.mode_none);
        }
        return context.getString(res);
    }

    /**
     * How many apps the given mode would actually block right now.
     *
     * <p>Used to warn the user before starting a mode that would block nothing,
     * which is the most common reason a new user concludes the app is broken.</p>
     */
    public static int wouldBlockCount(@NonNull Context context, @NonNull String mode) {
        BlockDecisionEngine.Snapshot s = BlockState.get().snapshot();
        int n = 0;
        for (com.focuslife.app.data.db.entity.BlockedAppEntity app : s.blockedSnapshotApps()) {
            if (app == null || !app.enabled) continue;
            if (coversMode(app, mode) || app.blockAlways) n++;
        }
        return n;
    }

    private static boolean coversMode(@NonNull com.focuslife.app.data.db.entity.BlockedAppEntity app,
                                      @NonNull String mode) {
        switch (mode) {
            case BlockDecisionEngine.MODE_FOCUS: return app.blockInFocus;
            case BlockDecisionEngine.MODE_STUDY: return app.blockInStudy;
            case BlockDecisionEngine.MODE_RUN:   return app.blockInRun;
            case BlockDecisionEngine.MODE_SLEEP: return app.blockInSleep;
            default: return false;
        }
    }

    /**
     * Whether interception can actually happen, given the granted permissions.
     *
     * @return {@link #ENFORCEMENT_FULL} when the accessibility guard and the
     *         overlay are both available, {@link #ENFORCEMENT_NOTIFICATION} when
     *         only a notification can be shown, or {@link #ENFORCEMENT_NONE}
     *         when neither permission is present.
     */
    public static int enforcementLevel(@NonNull Context context) {
        boolean guard = PermissionManager.isAccessibilityEnabled(context);
        boolean overlay = PermissionManager.canDrawOverlays(context);
        if (guard && overlay) return ENFORCEMENT_FULL;
        if (guard) return ENFORCEMENT_NOTIFICATION;
        return ENFORCEMENT_NONE;
    }

    public static final int ENFORCEMENT_NONE = 0;
    public static final int ENFORCEMENT_NOTIFICATION = 1;
    public static final int ENFORCEMENT_FULL = 2;

    // ------------------------------------------------------------------
    //  Control
    // ------------------------------------------------------------------

    /** Result of a start attempt, so the caller can explain what happened. */
    public static final class StartResult {
        public final boolean started;
        public final boolean alreadyActive;
        public final int enforcement;
        /** Apps this mode will block. */
        public final int blockCount;

        StartResult(boolean started, boolean alreadyActive, int enforcement, int blockCount) {
            this.started = started;
            this.alreadyActive = alreadyActive;
            this.enforcement = enforcement;
            this.blockCount = blockCount;
        }
    }

    /**
     * Starts a mode. Replaces any mode already running rather than stacking,
     * because two simultaneous modes would make the enforcement rules
     * ambiguous.
     */
    @NonNull
    public static StartResult start(@NonNull Context context, @NonNull String mode) {
        if (!BlockDecisionEngine.isKnownMode(mode)) {
            Log.w(TAG, "refusing to start unknown mode: " + mode);
            return new StartResult(false, false, ENFORCEMENT_NONE, 0);
        }

        boolean already = isActive(context, mode);
        int enforcement = enforcementLevel(context);
        int blockCount = wouldBlockCount(context, mode);

        try {
            // Record the mode first. Even if the foreground service is refused
            // (Android 12+ background-start restrictions), the accessibility
            // guard still enforces, because it reads this value.
            Prefs.setActiveMode(context, mode);
            BlockState.get().refreshMode(context);
            BlockForegroundService.startMode(context, mode);
        } catch (Exception e) {
            Log.e(TAG, "failed to start mode " + mode, e);
            return new StartResult(false, already, enforcement, blockCount);
        }

        return new StartResult(true, already, enforcement, blockCount);
    }

    /** Stops the current mode, if any. */
    public static void stop(@NonNull Context context) {
        try {
            Prefs.setActiveMode(context, null);
            Prefs.revokeUnlock(context);
            BlockState.get().refreshMode(context);
            BlockForegroundService.stopMode(context);
        } catch (Exception e) {
            Log.e(TAG, "failed to stop mode", e);
        }
    }

    /**
     * Switches to a different mode, preserving the elapsed clock so that a user
     * who moves from Study to Deep Focus mid-session is not shown a reset timer.
     */
    public static void switchTo(@NonNull Context context, @NonNull String mode) {
        if (isActive(context, mode)) return;
        start(context, mode);
    }

    // ------------------------------------------------------------------
    //  Emergency unlock
    // ------------------------------------------------------------------

    /** Grants a temporary bypass for one package. */
    public static void grantEmergencyUnlock(@NonNull Context context, @NonNull String pkg,
                                            int minutes) {
        long until = System.currentTimeMillis() + minutes * TimeUtils.MINUTE_MS;
        Prefs.grantUnlock(context, pkg, until);
        BlockState.get().refreshMode(context);
    }

    public static void revokeEmergencyUnlock(@NonNull Context context) {
        Prefs.revokeUnlock(context);
        BlockState.get().refreshMode(context);
    }

    /** Milliseconds remaining on the current emergency unlock, 0 if none. */
    public static long unlockRemaining(@NonNull Context context) {
        return Prefs.unlockRemainingMillis(context);
    }

    /**
     * Minimum gap between two emergency unlocks.
     *
     * <p>Without a cooldown the unlock becomes a one-tap bypass and the whole
     * blocking system is decorative. Fifteen minutes is long enough to be a real
     * friction, short enough to be legitimate in an actual emergency.</p>
     */
    public static final long EMERGENCY_COOLDOWN_MS = 15 * TimeUtils.MINUTE_MS;

    /** Milliseconds the user must wait before the next emergency unlock. */
    public static long emergencyCooldownRemaining(@NonNull Context context) {
        long last = Prefs.lastUnlockAt(context);
        if (last <= 0) return 0L;
        long elapsed = System.currentTimeMillis() - last;
        return Math.max(0L, EMERGENCY_COOLDOWN_MS - elapsed);
    }

    public static boolean canUseEmergencyUnlock(@NonNull Context context) {
        return emergencyCooldownRemaining(context) == 0L;
    }

    // ------------------------------------------------------------------
    //  Press-and-hold helper
    // ------------------------------------------------------------------

    /**
     * Wires a press-and-hold gesture to a view.
     *
     * <p>Used for "end mode" and "emergency unlock". A hold is deliberate in a
     * way a tap is not, which is exactly the friction these two actions need:
     * both of them undo a commitment the user made while they were in a clearer
     * state of mind.</p>
     *
     * @param holdMillis duration the press must be sustained
     * @param progressLabel updated with the remaining seconds while holding
     * @param onComplete    invoked once on the main thread when the hold succeeds
     */
    public static void attachHoldToComplete(@NonNull View view,
                                            final long holdMillis,
                                            @Nullable final TextView progressLabel,
                                            @Nullable final String holdHint,
                                            @NonNull final Runnable onComplete) {
        final Handler handler = new Handler(Looper.getMainLooper());
        final long startTime = SystemClock.elapsedRealtime();
        final boolean[] fired = {false};

        final Runnable ticker = new Runnable() {
            @Override
            public void run() {
                long elapsed = SystemClock.elapsedRealtime() - startTime;
                long remaining = holdMillis - elapsed;
                if (remaining <= 0) {
                    if (!fired[0]) {
                        fired[0] = true;
                        if (progressLabel != null && holdHint != null) {
                            progressLabel.setText(holdHint);
                        }
                        onComplete.run();
                    }
                    return;
                }
                if (progressLabel != null) {
                    int secs = (int) Math.ceil(remaining / 1000.0);
                    progressLabel.setText(String.valueOf(secs));
                }
                handler.postDelayed(this, 100L);
            }
        };

        view.setOnTouchListener(new View.OnTouchListener() {
            @Override
            public boolean onTouch(View v, MotionEvent event) {
                switch (event.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        fired[0] = false;
                        handler.removeCallbacks(ticker);
                        ticker.run();
                        v.setPressed(true);
                        return true;

                    case MotionEvent.ACTION_UP:
                    case MotionEvent.ACTION_CANCEL:
                        handler.removeCallbacks(ticker);
                        v.setPressed(false);
                        if (!fired[0] && progressLabel != null && holdHint != null) {
                            // Restore the hint; releasing early must not leave a
                            // stray countdown number on screen.
                            progressLabel.setText(holdHint);
                        }
                        return true;

                    default:
                        return false;
                }
            }
        });

        // Cancel the hold if the view detaches mid-gesture, otherwise the ticker
        // keeps a reference to a dead view until it fires.
        view.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
            @Override
            public void onViewAttachedToWindow(@NonNull View v) {
            }

            @Override
            public void onViewDetachedFromWindow(@NonNull View v) {
                handler.removeCallbacks(ticker);
            }
        });
    }
}
