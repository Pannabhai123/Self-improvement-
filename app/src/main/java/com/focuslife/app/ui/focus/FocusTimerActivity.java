package com.focuslife.app.ui.focus;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.CountDownTimer;
import android.text.TextUtils;
import android.util.Log;
import android.view.View;
import android.view.WindowManager;

import androidx.activity.OnBackPressedCallback;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;

import com.focuslife.app.R;
import com.focuslife.app.blocking.BlockDecisionEngine;
import com.focuslife.app.blocking.BlockForegroundService;
import com.focuslife.app.blocking.ModeController;
import com.focuslife.app.data.db.AppDatabase;
import com.focuslife.app.data.db.entity.FocusSessionEntity;
import com.focuslife.app.data.prefs.Prefs;
import com.focuslife.app.data.repo.Repository;
import com.focuslife.app.databinding.ActivityFocusTimerBinding;
import com.focuslife.app.notify.NotifHelper;
import com.focuslife.app.util.PermissionManager;
import com.focuslife.app.util.TimeUtils;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

/**
 * The full-screen Deep Focus timer.
 *
 * <h2>Timing model</h2>
 * The countdown is driven by wall-clock deadlines rather than by accumulated
 * ticks. {@link CountDownTimer} drifts, and more importantly it pauses when the
 * process is throttled — which on an aggressive OEM scheduler would silently
 * extend a session. Each phase therefore stores {@code endsAtMillis} and the
 * displayed remaining time is always {@code endsAt - now}. Pausing stores the
 * remaining duration and recomputes {@code endsAt} on resume, so the total is
 * preserved exactly.
 *
 * <h2>Process death</h2>
 * The session row is written to Room with {@code active = 1} at start and updated
 * on every phase change. If the process is killed, {@code onCreate} finds that row
 * and offers to restore, computing the true elapsed time from its start
 * timestamp. Time is never silently lost and never silently invented.
 *
 * <h2>Accidental-exit protection</h2>
 * <ul>
 *   <li>System bars are hidden, and a swipe reveals them only transiently.</li>
 *   <li>The back gesture is intercepted and requires confirmation.</li>
 *   <li>There is no navigation affordance on screen.</li>
 * </ul>
 * The user is never trapped: the confirmation dialog always offers a way out,
 * because a timer that cannot be exited is a bug, not a feature.
 */
public class FocusTimerActivity extends AppCompatActivity {

    private static final String TAG = "FocusTimer";

    public static final String EXTRA_FOCUS_MIN = "focus_min";
    public static final String EXTRA_BREAK_MIN = "break_min";
    public static final String EXTRA_ROUNDS = "rounds";
    public static final String EXTRA_TASK = "task";
    public static final String EXTRA_DEEP_FOCUS = "deep_focus";

    private static final String STATE_PHASE = "phase";
    private static final String STATE_ROUND = "round";
    private static final String STATE_REMAINING = "remaining";
    private static final String STATE_PAUSED = "paused";
    private static final String STATE_SESSION_ID = "session_id";

    private static final int PHASE_FOCUS = 0;
    private static final int PHASE_BREAK = 1;

    /** Ring resolution: thousandths, so the sweep is smooth without float churn. */
    private static final int RING_MAX = 1000;

    private ActivityFocusTimerBinding binding;
    private Repository repository;

    @Nullable
    private CountDownTimer timer;

    private int phase = PHASE_FOCUS;
    private int round = 1;
    private int rounds = 4;
    private int focusMin = 25;
    private int breakMin = 5;

    /** Total duration of the current phase, in millis. */
    private long phaseDurationMs;

    /** Milliseconds left in the current phase. */
    private long remainingMs;

    private boolean paused;

    private long sessionId = -1L;

    /** Seconds actually focused, accumulated across pauses and rounds. */
    private int focusedSecondsTotal;

    /** Block attempts observed during this session. */
    private int blockHitsThisSession;

    @Nullable
    private String taskLabel = "";

    private boolean deepFocus = true;

    /** Timestamp used to convert wall-clock into focused seconds while running. */
    private long phaseStartedAt;

    /** Whether the immersive mode has been applied (avoids re-applying per frame). */
    private boolean immersiveApplied;

    /**
     * Opens the timer with explicit configuration.
     */
    @NonNull
    public static Intent intent(@NonNull Context context, int focusMin, int breakMin, int rounds,
                                @Nullable String task, boolean deepFocus) {
        return new Intent(context, FocusTimerActivity.class)
                .putExtra(EXTRA_FOCUS_MIN, focusMin)
                .putExtra(EXTRA_BREAK_MIN, breakMin)
                .putExtra(EXTRA_ROUNDS, rounds)
                .putExtra(EXTRA_TASK, task)
                .putExtra(EXTRA_DEEP_FOCUS, deepFocus);
    }

    @NonNull
    public static Intent intent(@NonNull Context context) {
        return new Intent(context, FocusTimerActivity.class);
    }

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        binding = ActivityFocusTimerBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        // Prevent the screen from sleeping mid-session. This is the one place a
        // KEEP_SCREEN_ON flag is justified: a focus timer that lets the phone
        // lock has failed at its only job.
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        repository = Repository.get(getApplicationContext());

        readConfiguration(savedInstanceState);
        applyImmersiveMode();
        wireControls();

        if (savedInstanceState != null) {
            restoreState(savedInstanceState);
        } else if (!tryRestorePersistedSession()) {
            startPhase(PHASE_FOCUS, focusMin * TimeUtils.MINUTE_MS, true);
        }

        // Back is intercepted: leaving mid-session must be a decision, not a
        // reflex, but it must always be possible.
        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                confirmExit();
            }
        });
    }

    // ------------------------------------------------------------------
    //  Configuration
    // ------------------------------------------------------------------

    private void readConfiguration(@Nullable Bundle savedInstanceState) {
        Intent intent = getIntent();

        focusMin = intent == null ? 0 : intent.getIntExtra(EXTRA_FOCUS_MIN, 0);
        breakMin = intent == null ? 0 : intent.getIntExtra(EXTRA_BREAK_MIN, 0);
        rounds = intent == null ? 0 : intent.getIntExtra(EXTRA_ROUNDS, 0);
        taskLabel = intent == null ? "" : intent.getStringExtra(EXTRA_TASK);
        deepFocus = intent == null
                ? Prefs.deepFocusByDefault(this)
                : intent.getBooleanExtra(EXTRA_DEEP_FOCUS, Prefs.deepFocusByDefault(this));

        // Fall back to the user's saved defaults, then to the brief's 25/5.
        if (focusMin <= 0) focusMin = Prefs.defaultFocusMinutes(this);
        if (breakMin < 0) breakMin = Prefs.defaultBreakMinutes(this);
        if (rounds <= 0) rounds = Prefs.defaultRounds(this);
        if (breakMin == 0 && intent != null && !intent.hasExtra(EXTRA_BREAK_MIN)) {
            breakMin = Prefs.defaultBreakMinutes(this);
        }

        focusMin = clamp(focusMin, 1, 240);
        breakMin = clamp(breakMin, 0, 60);
        rounds = clamp(rounds, 1, 12);

        if (taskLabel == null) taskLabel = "";
    }

    // ------------------------------------------------------------------
    //  Immersive mode
    // ------------------------------------------------------------------

    /**
     * Hides both system bars and keeps them hidden.
     *
     * <p>{@code BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE} means a swipe reveals the
     * bars briefly without leaving the session. We deliberately do NOT use
     * {@code setSystemBarsBehavior(BEHAVIOR_DEFAULT)} with sticky immersive,
     * because that variant can trap the user if the activity misbehaves.</p>
     */
    private void applyImmersiveMode() {
        try {
            WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
            WindowInsetsControllerCompat controller =
                    WindowCompat.getInsetsController(getWindow(), binding.getRoot());
            controller.hide(WindowInsetsCompat.Type.systemBars());
            controller.setSystemBarsBehavior(
                    WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
            immersiveApplied = true;
        } catch (Exception e) {
            // Immersive mode is a nicety; failing to apply it must not stop the
            // timer from working.
            Log.w(TAG, "could not apply immersive mode", e);
        }
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        // The system returns the bars after a notification-shade pull or a
        // transient swipe, so re-hide them whenever focus comes back. Re-applying
        // is idempotent and costs nothing measurable.
        if (hasFocus) {
            applyImmersiveMode();
        }
    }

    // ------------------------------------------------------------------
    //  Phase control
    // ------------------------------------------------------------------

    /**
     * Begins a phase.
     *
     * @param newPhase     {@link #PHASE_FOCUS} or {@link #PHASE_BREAK}
     * @param durationMs   length of the phase
     * @param persist      whether to write the session row to Room
     */
    private void startPhase(int newPhase, long durationMs, boolean persist) {
        cancelTimer();

        phase = newPhase;
        phaseDurationMs = Math.max(1000L, durationMs);
        remainingMs = phaseDurationMs;
        paused = false;
        phaseStartedAt = System.currentTimeMillis();

        if (newPhase == PHASE_FOCUS && persist) {
            persistSessionStart();
            if (deepFocus) {
                applyDeepFocusMode(true);
            }
        }

        render();
        runTimer();
        updateNotification();
    }

    private void runTimer() {
        cancelTimer();

        timer = new CountDownTimer(remainingMs, 200L) {
            @Override
            public void onTick(long millisUntilFinished) {
                // Recompute from the deadline rather than trusting the tick
                // value, so throttled delivery cannot make the clock drift.
                long left = paused ? remainingMs : (phaseStartedAt + phaseDurationMs
                        - System.currentTimeMillis());
                if (left < 0) left = 0;
                remainingMs = left;
                renderTimeOnly();
                if (!paused && left % 30_000L < 250L) {
                    // Refresh the notification roughly every 30 s, not every tick:
                    // a per-second notification update is a measurable battery cost.
                    updateNotification();
                }
            }

            @Override
            public void onFinish() {
                remainingMs = 0;
                renderTimeOnly();
                onPhaseComplete();
            }
        };
        timer.start();
    }

    /** Called when a phase's time runs out. */
    private void onPhaseComplete() {
        cancelTimer();

        if (phase == PHASE_FOCUS) {
            focusedSecondsTotal += (int) (phaseDurationMs / 1000L);
            persistSessionProgress(true);

            if (breakMin > 0 && round < rounds) {
                startPhase(PHASE_BREAK, breakMin * TimeUtils.MINUTE_MS, false);
            } else {
                completeSession(true);
            }
            return;
        }

        // Break finished: advance to the next focus round.
        round++;
        startPhase(PHASE_FOCUS, focusMin * TimeUtils.MINUTE_MS, false);
    }

    private void completeSession(boolean completed) {
        cancelTimer();
        persistSessionEnd(completed);
        if (deepFocus) {
            applyDeepFocusMode(false);
        }
        NotifHelper.cancel(this, NotifHelper.NOTIF_ID_FOCUS);

        render();
        showCompletionDialog(completed);
    }

    // ------------------------------------------------------------------
    //  Pause / resume
    // ------------------------------------------------------------------

    private void togglePause() {
        if (paused) {
            resume();
        } else {
            pause();
        }
    }

    private void pause() {
        if (paused) return;
        cancelTimer();

        // Bank the focus time earned so far in this phase.
        if (phase == PHASE_FOCUS) {
            long elapsedInPhase = phaseDurationMs - remainingMs;
            if (elapsedInPhase > 0) {
                focusedSecondsTotal += (int) (elapsedInPhase / 1000L);
            }
        }

        paused = true;
        persistSessionProgress(false);
        render();
        updateNotification();
    }

    private void resume() {
        if (!paused) return;
        paused = false;

        // Rebuild the deadline from the stored remaining time, so total elapsed
        // does not include the paused interval.
        phaseDurationMs = remainingMs;
        phaseStartedAt = System.currentTimeMillis();

        render();
        runTimer();
        updateNotification();
    }

    // ------------------------------------------------------------------
    //  Persistence
    // ------------------------------------------------------------------

    private void persistSessionStart() {
        final String task = taskLabel == null ? "" : taskLabel;
        final int durationSec = (int) (focusMin * 60L);
        AppDatabase.io().execute(() -> {
            try {
                sessionId = repository.startFocusSession(
                        task, durationSec, deepFocus, BlockDecisionEngine.MODE_FOCUS);
            } catch (Exception e) {
                Log.e(TAG, "could not persist the session start", e);
            }
        });
    }

    private void persistSessionProgress(final boolean phaseCompleted) {
        final long id = sessionId;
        if (id < 0) return;
        final int focused = focusedSecondsTotal;
        final int hits = blockHitsThisSession;

        AppDatabase.io().execute(() -> {
            try {
                FocusSessionEntity s = repository.db().focusSessionDao().findById(id);
                if (s == null || !s.active) return;
                s.focusedSec = focused;
                s.blockHits = hits;
                repository.db().focusSessionDao().update(s);
            } catch (Exception e) {
                Log.w(TAG, "could not persist session progress", e);
            }
        });
    }

    private void persistSessionEnd(final boolean completed) {
        final long id = sessionId;
        final int focused = focusedSecondsTotal;
        if (id < 0) return;

        AppDatabase.io().execute(() -> {
            try {
                repository.finishFocusSession(id, focused, completed);
            } catch (Exception e) {
                Log.e(TAG, "could not persist the session end", e);
            }
        });
        sessionId = -1L;
    }

    /**
     * Offers to resume a session that was interrupted by process death.
     *
     * @return true when a session was restored and this activity should not start
     *         a fresh one.
     */
    private boolean tryRestorePersistedSession() {
        // Read synchronously is not possible here (main thread), so we look at
        // the saved prefs mirror instead and let the database catch up.
        long savedId = getPreferences(Context.MODE_PRIVATE).getLong("active_session_id", -1L);
        long savedEndsAt = getPreferences(Context.MODE_PRIVATE).getLong("active_phase_ends_at", 0L);
        int savedPhase = getPreferences(Context.MODE_PRIVATE).getInt("active_phase", PHASE_FOCUS);
        int savedRound = getPreferences(Context.MODE_PRIVATE).getInt("active_round", 1);
        int savedFocused = getPreferences(Context.MODE_PRIVATE).getInt("active_focused_sec", 0);

        if (savedId < 0 || savedEndsAt <= 0) return false;

        long now = System.currentTimeMillis();
        if (savedEndsAt <= now) {
            // The session expired while the process was dead. Close it out
            // honestly as incomplete rather than pretending it finished.
            final long id = savedId;
            final int focused = savedFocused;
            AppDatabase.io().execute(() -> repository.finishFocusSession(id, focused, false));
            clearRestoreState();
            return false;
        }

        sessionId = savedId;
        phase = savedPhase;
        round = savedRound;
        focusedSecondsTotal = savedFocused;
        remainingMs = savedEndsAt - now;
        phaseDurationMs = savedPhase == PHASE_FOCUS
                ? focusMin * TimeUtils.MINUTE_MS
                : Math.max(1L, breakMin * TimeUtils.MINUTE_MS);
        paused = true;
        phaseStartedAt = now;

        if (deepFocus) applyDeepFocusMode(true);

        render();
        updateNotification();

        // Restore paused, and tell the user why, so the session does not appear
        // to have silently lost time.
        binding.tvPhase.setText(getString(R.string.focus_restored,
                TimeUtils.formatDuration(remainingMs)));
        return true;
    }

    /** Mirrors the running session into this activity's private prefs. */
    private void saveRestoreState() {
        getPreferences(Context.MODE_PRIVATE).edit()
                .putLong("active_session_id", sessionId)
                .putLong("active_phase_ends_at", paused ? 0L : phaseStartedAt + phaseDurationMs)
                .putInt("active_phase", phase)
                .putInt("active_round", round)
                .putInt("active_focused_sec", focusedSecondsTotal)
                .putLong("active_remaining_ms", remainingMs)
                .apply();
    }

    private void clearRestoreState() {
        getPreferences(Context.MODE_PRIVATE).edit().clear().apply();
    }

    // ------------------------------------------------------------------
    //  Mode integration
    // ------------------------------------------------------------------

    /**
     * Starts or stops Deep Focus Mode for the lifetime of the session.
     *
     * <p>Only takes over the mode when the user asked for deep focus. If a Study
     * Mode is already running we escalate to Focus (which is stricter) and
     * restore nothing on exit, because reverting to a weaker mode automatically
     * would quietly reduce protection the user did not ask to reduce.</p>
     */
    private void applyDeepFocusMode(boolean start) {
        try {
            if (start) {
                ModeController.start(this, BlockDecisionEngine.MODE_FOCUS);
            } else if (ModeController.isActive(this, BlockDecisionEngine.MODE_FOCUS)) {
                ModeController.stop(this);
            }
        } catch (Exception e) {
            Log.w(TAG, "could not change mode for deep focus", e);
        }
    }

    /** Called by the accessibility guard path via a broadcast-free direct hook. */
    public void onDistractionAttempted() {
        blockHitsThisSession++;
        persistSessionProgress(false);
    }

    // ------------------------------------------------------------------
    //  Rendering
    // ------------------------------------------------------------------

    private void render() {
        binding.tvTask.setText(TextUtils.isEmpty(taskLabel)
                ? getString(R.string.quick_deep_focus)
                : taskLabel);

        binding.tvPhase.setText(phase == PHASE_BREAK
                ? getString(R.string.focus_break_ends_in, TimeUtils.formatDuration(remainingMs))
                : getString(R.string.focus_session_of, round, rounds));

        binding.btnPause.setText(paused ? R.string.action_resume : R.string.action_pause);
        binding.btnPause.setIconResource(paused ? R.drawable.ic_play : R.drawable.ic_pause);

        renderTimeOnly();
        renderBlockStatus();
    }

    /** Updates only the ring and the time text — called on every tick. */
    private void renderTimeOnly() {
        if (binding == null) return;

        binding.ringTimer.setTexts(TimeUtils.formatDuration(remainingMs),
                getString(R.string.focus_remaining));

        int thousandths = phaseDurationMs <= 0 ? 0
                : (int) (RING_MAX * remainingMs / phaseDurationMs);
        binding.ringTimer.setProgress(clamp(thousandths, 0, RING_MAX), false);

        // Breaks are visually distinct so a glance tells you which phase you are
        // in without reading any text.
        int colorRes = phase == PHASE_BREAK ? R.color.success_500
                : (paused ? R.color.warning_500 : R.color.accent_500);
        binding.ringTimer.setRingColor(getColor(colorRes));
    }

    private void renderBlockStatus() {
        int enforcement = ModeController.enforcementLevel(this);
        boolean blockingOn = deepFocus && enforcement == ModeController.ENFORCEMENT_FULL;

        binding.ivBlockStatus.setImageResource(blockingOn ? R.drawable.ic_shield : R.drawable.ic_warning);
        binding.ivBlockStatus.setImageTintList(android.content.res.ColorStateList.valueOf(
                getColor(blockingOn ? R.color.success_500 : R.color.warning_500)));
        binding.tvBlockStatus.setText(blockingOn
                ? R.string.focus_deep_enabled
                : (deepFocus ? R.string.perm_denied_accessibility_desc : R.string.focus_deep_disabled));

        binding.blockStatus.setOnClickListener(v -> {
            if (!blockingOn) {
                startActivity(new Intent(this,
                        com.focuslife.app.ui.onboarding.OnboardingActivity.class));
            }
        });
    }

    // ------------------------------------------------------------------
    //  Notification
    // ------------------------------------------------------------------

    private void updateNotification() {
        if (!PermissionManager.canPostNotifications(this)) return;

        try {
            android.app.PendingIntent content = android.app.PendingIntent.getActivity(
                    this, 0,
                    new Intent(this, FocusTimerActivity.class)
                            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
                    android.app.PendingIntent.FLAG_UPDATE_CURRENT
                            | android.app.PendingIntent.FLAG_IMMUTABLE);

            android.app.Notification n = NotifHelper.buildFocusNotification(
                    this,
                    TimeUtils.formatDuration(remainingMs),
                    taskLabel == null ? "" : taskLabel,
                    phase == PHASE_BREAK,
                    content);
            NotifHelper.updateFocusNotification(this, n);
        } catch (Exception e) {
            Log.w(TAG, "notification update failed", e);
        }
    }

    // ------------------------------------------------------------------
    //  Controls
    // ------------------------------------------------------------------

    private void wireControls() {
        binding.btnPause.setOnClickListener(v -> togglePause());

        binding.btnGiveUp.setOnClickListener(v -> confirmExit());

        // Tapping the ring also toggles pause. A large, obvious target beats a
        // small button when the phone is on a desk and the user is mid-thought.
        binding.ringContainer.setOnClickListener(v -> togglePause());
    }

    /**
     * Confirms before abandoning a session.
     *
     * <p>The message reports what has already been earned, which is both honest
     * and the most effective argument for staying: the time is not wasted either
     * way, but the completed session is worth more.</p>
     */
    private void confirmExit() {
        int banked = focusedSecondsTotal;
        if (phase == PHASE_FOCUS && !paused) {
            long elapsedInPhase = phaseDurationMs - remainingMs;
            if (elapsedInPhase > 0) banked += (int) (elapsedInPhase / 1000L);
        }

        if (banked <= 0) {
            // Nothing to lose; do not add friction to an immediate cancel.
            abandonSession();
            return;
        }

        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.focus_give_up_title)
                .setMessage(getString(R.string.focus_give_up_message, TimeUtils.formatSeconds(banked)))
                .setNegativeButton(R.string.focus_give_up_confirm, (d, w) -> abandonSession())
                .setPositiveButton(R.string.focus_keep_going, null)
                .show();
    }

    private void abandonSession() {
        cancelTimer();
        // Bank whatever was focused before leaving.
        if (phase == PHASE_FOCUS && !paused) {
            long elapsedInPhase = phaseDurationMs - remainingMs;
            if (elapsedInPhase > 0) focusedSecondsTotal += (int) (elapsedInPhase / 1000L);
        }
        completeSession(false);
        finish();
    }

    private void showCompletionDialog(boolean completed) {
        String message = completed
                ? getString(R.string.focus_complete_message,
                        TimeUtils.formatMinutes(focusedSecondsTotal / 60))
                : getString(R.string.focus_abandoned,
                        TimeUtils.formatMinutes(focusedSecondsTotal / 60));

        new MaterialAlertDialogBuilder(this)
                .setTitle(completed ? R.string.focus_complete_title : R.string.focus_give_up_title)
                .setMessage(message)
                .setCancelable(false)
                .setPositiveButton(R.string.action_close, (d, w) -> finish())
                .setNegativeButton(R.string.focus_start_deep, (d, w) -> {
                    round = 1;
                    focusedSecondsTotal = 0;
                    blockHitsThisSession = 0;
                    startPhase(PHASE_FOCUS, focusMin * TimeUtils.MINUTE_MS, true);
                })
                .show();
    }

    // ------------------------------------------------------------------
    //  Lifecycle
    // ------------------------------------------------------------------

    @Override
    protected void onSaveInstanceState(@NonNull Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putInt(STATE_PHASE, phase);
        outState.putInt(STATE_ROUND, round);
        outState.putLong(STATE_REMAINING, remainingMs);
        outState.putBoolean(STATE_PAUSED, paused);
        outState.putLong(STATE_SESSION_ID, sessionId);
        outState.putInt("focused_total", focusedSecondsTotal);
        outState.putInt("block_hits", blockHitsThisSession);
        outState.putLong("phase_duration", phaseDurationMs);
        outState.putString("task", taskLabel);
    }

    private void restoreState(@NonNull Bundle state) {
        phase = state.getInt(STATE_PHASE, PHASE_FOCUS);
        round = state.getInt(STATE_ROUND, 1);
        remainingMs = state.getLong(STATE_REMAINING, focusMin * TimeUtils.MINUTE_MS);
        paused = state.getBoolean(STATE_PAUSED, false);
        sessionId = state.getLong(STATE_SESSION_ID, -1L);
        focusedSecondsTotal = state.getInt("focused_total", 0);
        blockHitsThisSession = state.getInt("block_hits", 0);
        phaseDurationMs = state.getLong("phase_duration", focusMin * TimeUtils.MINUTE_MS);
        taskLabel = state.getString("task", taskLabel);
        phaseStartedAt = System.currentTimeMillis();

        render();
        if (!paused) {
            runTimer();
        }
        if (deepFocus) applyDeepFocusMode(true);
        updateNotification();
    }

    @Override
    protected void onPause() {
        super.onPause();
        saveRestoreState();
    }

    @Override
    protected void onDestroy() {
        cancelTimer();
        // Leaving the activity for real (not a rotation) must not leave Deep
        // Focus Mode running with nothing on screen to show for it.
        if (isFinishing() && deepFocus
                && ModeController.isActive(this, BlockDecisionEngine.MODE_FOCUS)) {
            applyDeepFocusMode(false);
        }
        NotifHelper.cancel(this, NotifHelper.NOTIF_ID_FOCUS);
        super.onDestroy();
    }

    private void cancelTimer() {
        if (timer != null) {
            timer.cancel();
            timer = null;
        }
    }

    private static int clamp(int v, int lo, int hi) {
        return v < lo ? lo : Math.min(v, hi);
    }
}
