package com.focuslife.app.blocking;

import android.accessibilityservice.AccessibilityService;
import android.content.Context;
import android.content.Intent;
import android.text.TextUtils;
import android.util.Log;
import android.view.accessibility.AccessibilityEvent;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.focuslife.app.data.db.AppDatabase;
import com.focuslife.app.data.db.entity.DistractionLogEntity;
import com.focuslife.app.util.TimeUtils;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * The Distraction Guard.
 *
 * <h2>Exactly what it does</h2>
 * Receives {@code TYPE_WINDOW_STATE_CHANGED} events, reads
 * {@link AccessibilityEvent#getPackageName()}, and asks
 * {@link BlockDecisionEngine} whether that package should be intercepted. If so
 * it launches {@link BlockWarningActivity} over it.
 *
 * <h2>Exactly what it does not do</h2>
 * <ul>
 *   <li>It never calls {@code getRootInActiveWindow()}. The service config sets
 *       {@code canRetrieveWindowContent="false"}, so it could not even if it
 *       tried.</li>
 *   <li>It never inspects {@link AccessibilityEvent#getText()}, which is where
 *       screen content would appear.</li>
 *   <li>It never performs a gesture or global action on the user's behalf.</li>
 * </ul>
 *
 * <h2>Robustness notes</h2>
 * <ul>
 *   <li><b>Self-loop guard.</b> Launching our own activity generates window
 *       events for our own package. Those are filtered out, and a second
 *       launch is suppressed for {@link #RELAUNCH_SUPPRESSION_MS} after a
 *       successful interception.</li>
 *   <li><b>Rapid-switch guard.</b> Switching between two blocked apps quickly
 *       must not stack two warning activities. A per-package debounce handles
 *       this.</li>
 *   <li><b>Process death.</b> Android can kill the service; the manifest
 *       registration means the system restarts it when accessibility events
 *       resume. {@link #sRunning} is only ever a hint for the UI, never the
 *       authority — {@code PermissionManager} also reads the system setting.</li>
 * </ul>
 */
public class BlockAccessibilityService extends AccessibilityService {

    private static final String TAG = "BlockGuard";

    /** True while an instance is connected. Used only for UI status display. */
    private static final AtomicBoolean sRunning = new AtomicBoolean(false);

    /** Last package we launched a warning for. */
    @Nullable
    private static volatile String sLastBlockedPackage;

    /** When we last launched a warning. */
    private static volatile long sLastBlockAt;

    /**
     * Ignore repeat events for the same package within this window. Long enough
     * to swallow the burst of events a single app launch produces, short enough
     * that genuinely reopening the app later still warns.
     */
    private static final long RELAUNCH_SUPPRESSION_MS = 2_500L;

    /** Different package, but still suppress to avoid stacking activities. */
    private static final long GLOBAL_SUPPRESSION_MS = 800L;

    public static boolean isRunning() {
        return sRunning.get();
    }

    /** Total interceptions since the process started, for diagnostics. */
    private static volatile int sInterceptionCount;

    public static int interceptionCount() {
        return sInterceptionCount;
    }

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        sRunning.set(true);
        Log.i(TAG, "Distraction Guard connected");

        // Load the policy immediately so the very first event is not evaluated
        // against an empty snapshot. refreshSync is safe here because
        // onServiceConnected runs on the main thread but the database is already
        // warm in the common case; we still route it through the IO executor to
        // guarantee no main-thread disk access.
        BlockState.get().attach(this);
        BlockState.get().refreshAsync(this);
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event == null) return;

        int type = event.getEventType();
        if (type != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
                && type != AccessibilityEvent.TYPE_WINDOWS_CHANGED) {
            return;
        }

        CharSequence pkgCs = event.getPackageName();
        if (pkgCs == null) return;
        String pkg = pkgCs.toString();
        if (TextUtils.isEmpty(pkg)) return;

        // Our own UI must never be intercepted, otherwise the warning screen
        // would immediately trigger another warning and loop.
        if (pkg.equals(getPackageName())) return;

        BlockDecisionEngine.Decision decision;
        try {
            decision = BlockState.get().evaluate(this, pkg);
        } catch (Exception e) {
            // A failure to decide must fail open: never lock the user out of
            // their phone because of an internal error.
            Log.w(TAG, "evaluation failed for " + pkg, e);
            return;
        }

        if (!decision.shouldBlock) return;

        if (isSuppressed(pkg)) return;

        recordInterception(pkg, decision);
        showWarning(pkg, decision);
    }

    /** Debounce so one app launch cannot produce a stack of warning screens. */
    private boolean isSuppressed(@NonNull String pkg) {
        long now = System.currentTimeMillis();
        long sinceLast = now - sLastBlockAt;
        if (sLastBlockedPackage != null && sLastBlockedPackage.equals(pkg)) {
            if (sinceLast < RELAUNCH_SUPPRESSION_MS) return true;
        } else if (sinceLast < GLOBAL_SUPPRESSION_MS) {
            return true;
        }
        return false;
    }

    private void recordInterception(@NonNull String pkg, @NonNull BlockDecisionEngine.Decision decision) {
        sLastBlockedPackage = pkg;
        sLastBlockAt = System.currentTimeMillis();
        sInterceptionCount++;

        final Context app = getApplicationContext();
        final String label = decision.blockedLabel() != null ? decision.blockedLabel() : pkg;
        final boolean limit = decision.isLimitBlock();

        // Persist the interception count and, for limit blocks, a diary entry so
        // the "CONTROL YOURSELF" analysis reflects what actually pulled at the
        // user rather than only what they chose to log manually.
        AppDatabase.io().execute(() -> {
            try {
                AppDatabase db = AppDatabase.get(app);
                db.blockerDao().incrementBlockCount(pkg);

                if (limit) {
                    DistractionLogEntity log = new DistractionLogEntity();
                    log.dayKey = TimeUtils.today();
                    log.loggedAt = System.currentTimeMillis();
                    log.trigger = DistractionLogEntity.TRIGGER_OTHER;
                    log.packageName = pkg;
                    log.intendedTask = label;
                    log.source = "blocked";
                    log.minutesLost = 0;
                    log.resisted = true; // the guard held, so the urge was resisted
                    db.controlDao().insertDistraction(log);
                }

                // A block may have been caused by a mode change or a crossed
                // daily limit; refresh so subsequent decisions are current.
                BlockState.get().refreshSync(app);
            } catch (Exception e) {
                Log.w(TAG, "failed to persist interception", e);
            }
        });
    }

    private void showWarning(@NonNull String pkg, @NonNull BlockDecisionEngine.Decision decision) {
        try {
            Intent intent = new Intent(this, BlockWarningActivity.class);
            intent.putExtra(BlockWarningActivity.EXTRA_PACKAGE, pkg);
            intent.putExtra(BlockWarningActivity.EXTRA_LABEL,
                    decision.blockedLabel() != null ? decision.blockedLabel() : pkg);
            intent.putExtra(BlockWarningActivity.EXTRA_REASON, decision.reason);
            intent.putExtra(BlockWarningActivity.EXTRA_MODE, decision.mode);
            intent.putExtra(BlockWarningActivity.EXTRA_STRICT, decision.isStrict());
            // NEW_TASK is required: the service has no task of its own.
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                    | Intent.FLAG_ACTIVITY_CLEAR_TOP
                    | Intent.FLAG_ACTIVITY_SINGLE_TOP
                    | Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS
                    | Intent.FLAG_ACTIVITY_NO_ANIMATION);
            startActivity(intent);
        } catch (Exception e) {
            Log.e(TAG, "could not show the warning screen; falling back to a notification", e);
            notifyInstead(pkg, decision);
        }
    }

    /**
     * Fallback when the warning activity cannot be launched — most commonly
     * because the app lacks Display-over-other-apps permission while in the
     * background. Blocking silently would be worse than not blocking, so the
     * user is told.
     */
    private void notifyInstead(@NonNull String pkg, @NonNull BlockDecisionEngine.Decision decision) {
        try {
            com.focuslife.app.notify.NotifHelper.notifyBlockAttempt(
                    getApplicationContext(),
                    decision.blockedLabel() != null ? decision.blockedLabel() : pkg);
        } catch (Exception e) {
            Log.w(TAG, "fallback notification failed", e);
        }
    }

    @Override
    public void onInterrupt() {
        // The system asks us to stop producing feedback. We produce none, so
        // there is nothing to tear down; the guard keeps evaluating events.
        Log.i(TAG, "Distraction Guard interrupted by system");
    }

    @Override
    public boolean onUnbind(Intent intent) {
        sRunning.set(false);
        Log.i(TAG, "Distraction Guard disconnected");
        return super.onUnbind(intent);
    }

    @Override
    public void onDestroy() {
        sRunning.set(false);
        super.onDestroy();
    }
}
