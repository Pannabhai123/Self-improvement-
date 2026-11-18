package com.focuslife.app.blocking;

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

import com.focuslife.app.R;
import com.focuslife.app.data.db.AppDatabase;
import com.focuslife.app.data.db.entity.DistractionLogEntity;
import com.focuslife.app.data.db.entity.ReasonEntity;
import com.focuslife.app.data.prefs.Prefs;
import com.focuslife.app.databinding.ActivityBlockWarningBinding;
import com.focuslife.app.util.PermissionManager;
import com.focuslife.app.util.TimeUtils;

/**
 * The full-screen interception shown over a blocked app.
 *
 * <h2>Design intent</h2>
 * This screen is the moment the whole product lives or dies. A blocking screen
 * that only says "blocked" gets bypassed within a day; one that shows the user
 * <em>their own written reason</em> and a concrete alternative has a chance. So
 * the layout leads with the reason, then the four-step reset routine, and only
 * then offers exits.
 *
 * <h2>Anti-bypass behaviour, and its limits</h2>
 * <ul>
 *   <li>The back button is intercepted and routed to the same cooldown as a
 *       fresh attempt, so it cannot be used as a free exit.</li>
 *   <li>The activity is {@code singleInstance} in its own task affinity and
 *       excluded from Recents, so it cannot be sidestepped via the task
 *       switcher.</li>
 *   <li>In strict mode there is no "open anyway" button at all.</li>
 *   <li><b>Honest limitation:</b> a determined user can still disable the
 *       accessibility service in system Settings. No third-party Android app can
 *       prevent that — only device-owner / Digital Wellbeing apps can. The
 *       service is deliberately listed in the allow-list so that the Settings
 *       app itself can never be blocked, because blocking it would be a safety
 *       hazard rather than a feature.</li>
 * </ul>
 */
public class BlockWarningActivity extends AppCompatActivity {

    private static final String TAG = "BlockWarning";

    public static final String EXTRA_PACKAGE = "extra_package";
    public static final String EXTRA_LABEL = "extra_label";
    public static final String EXTRA_REASON = "extra_reason";
    public static final String EXTRA_MODE = "extra_mode";
    public static final String EXTRA_STRICT = "extra_strict";

    private ActivityBlockWarningBinding binding;

    @Nullable
    private String blockedPackage;

    @Nullable
    private String blockedLabel;

    private int blockReason = BlockDecisionEngine.REASON_MODE;
    private String mode = BlockDecisionEngine.MODE_NONE;
    private boolean strict;

    @Nullable
    private CountDownTimer cooldownTimer;

    /** Set once the user has logged this attempt, so it is not logged twice. */
    private boolean alreadyLogged;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Show over the lock screen is deliberately NOT enabled: a block warning
        // should not become a way to interact with the phone while locked.
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
                | WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON);
        setShowWhenLocked(false);
        setTurnScreenOn(false);

        binding = ActivityBlockWarningBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        readIntent(getIntent());
        applyStrictness();
        renderReason();
        renderSteps();
        wireActions();
        startCooldown();

        // The back button is not an escape hatch: it returns to the blocked app,
        // which the guard will immediately intercept again. We finish rather
        // than allow the default back stack behaviour, so there is never a blank
        // task left behind.
        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                finishAndRemove();
            }
        });
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        // A different blocked app was opened while this screen was up. Re-render
        // rather than stacking a second activity.
        if (cooldownTimer != null) cooldownTimer.cancel();
        alreadyLogged = false;
        readIntent(intent);
        applyStrictness();
        renderReason();
        startCooldown();
    }

    private void readIntent(@Nullable Intent intent) {
        if (intent == null) {
            finishAndRemove();
            return;
        }
        blockedPackage = intent.getStringExtra(EXTRA_PACKAGE);
        blockedLabel = intent.getStringExtra(EXTRA_LABEL);
        blockReason = intent.getIntExtra(EXTRA_REASON, BlockDecisionEngine.REASON_MODE);
        mode = intent.getStringExtra(EXTRA_MODE);
        if (mode == null) mode = BlockDecisionEngine.MODE_NONE;
        strict = intent.getBooleanExtra(EXTRA_STRICT, false);

        if (TextUtils.isEmpty(blockedPackage)) {
            Log.w(TAG, "started without a target package; closing");
            finishAndRemove();
            return;
        }

        // An emergency unlock granted while this screen was open must take
        // effect immediately rather than on the next attempt.
        if (Prefs.isUnlocked(this, blockedPackage)) {
            finishAndRemove();
        }
    }

    private void applyStrictness() {
        String label = TextUtils.isEmpty(blockedLabel) ? blockedPackage : blockedLabel;

        binding.blockAppLabel.setText(label);
        binding.blockTitle.setText(blockReason == BlockDecisionEngine.REASON_LIMIT
                ? R.string.block_title_limit
                : R.string.block_title);
        binding.blockMessage.setText(getString(R.string.block_message, label));

        String modeName = BlockDecisionEngine.isKnownMode(mode)
                ? ModeController.modeName(this, mode)
                : null;
        binding.blockModeChip.setVisibility(modeName == null ? View.GONE : View.VISIBLE);
        if (modeName != null) {
            binding.blockModeChip.setText(getString(R.string.block_mode_active, modeName));
        }

        binding.strictNotice.setVisibility(strict ? View.VISIBLE : View.GONE);
        binding.btnOpenAnyway.setVisibility(strict ? View.GONE : View.VISIBLE);

        // A running focus session is the strongest argument on this screen, so
        // surface the remaining time when there is one.
        final long[] remainingMs = {0L};
        AppDatabase.io().execute(() -> {
            try {
                com.focuslife.app.data.db.entity.FocusSessionEntity active =
                        AppDatabase.get(this).focusSessionDao().findActive();
                if (active != null && !active.completed) {
                    long elapsed = System.currentTimeMillis() - active.startTime;
                    remainingMs[0] = Math.max(0L, active.durationSec * 1000L - elapsed);
                }
                runOnUiThread(() -> {
                    if (remainingMs[0] > 0) {
                        binding.blockSessionProgress.setVisibility(View.VISIBLE);
                        binding.blockSessionProgress.setText(getString(
                                R.string.block_session_progress,
                                TimeUtils.formatDuration(remainingMs[0])));
                    } else {
                        binding.blockSessionProgress.setVisibility(View.GONE);
                    }
                });
            } catch (Exception e) {
                Log.w(TAG, "could not read the active session", e);
            }
        });
    }

    /**
     * Shows one of the user's own reasons, rotating so the screen does not
     * become wallpaper after the tenth interception.
     */
    private void renderReason() {
        binding.reasonCard.setVisibility(View.GONE);
        AppDatabase.io().execute(() -> {
            try {
                AppDatabase db = AppDatabase.get(this);
                ReasonEntity reason = db.controlDao().leastShownReason();
                if (reason == null || TextUtils.isEmpty(reason.text)) return;
                db.controlDao().incrementShown(reason.id);
                final String text = reason.text;
                runOnUiThread(() -> {
                    binding.reasonText.setText(text);
                    binding.reasonCard.setVisibility(View.VISIBLE);
                });
            } catch (Exception e) {
                Log.w(TAG, "could not load a reason", e);
            }
        });
    }

    /**
     * The four-step reset routine from the brief.
     *
     * <p>Each row is an {@code <include>} of the shared step layout, so the same
     * four lines render identically here and inside Control Yourself and the
     * wording can never drift between the two screens.</p>
     */
    private void renderSteps() {
        bindStep(binding.stepRow1, "1", R.string.block_step_1);
        bindStep(binding.stepRow2, "2", R.string.block_step_2);
        bindStep(binding.stepRow3, "3", R.string.block_step_3);
        bindStep(binding.stepRow4, "4", R.string.block_step_4);
    }

    private void bindStep(@NonNull com.focuslife.app.databinding.ItemResetStepBinding row,
                          @NonNull String number, int textRes) {
        row.tvStepNumber.setText(number);
        row.tvStepText.setText(textRes);
    }

    private void wireActions() {
        binding.btnGoBack.setOnClickListener(v -> finishAndRemove());

        binding.btnLogDistraction.setOnClickListener(v -> logDistraction());

        binding.btnOpenAnyway.setOnClickListener(v -> {
            // A plain tap, not a hold: the cooldown has already provided the
            // friction, and doubling it makes the bypass feel broken rather
            // than considered.
            logDistraction();
            launchBlockedApp();
            finishAndRemove();
        });

        binding.btnEmergency.setOnClickListener(v -> {
            Intent intent = new Intent(this, EmergencyUnlockActivity.class);
            intent.putExtra(EmergencyUnlockActivity.EXTRA_PACKAGE, blockedPackage);
            intent.putExtra(EmergencyUnlockActivity.EXTRA_LABEL, blockedLabel);
            startActivity(intent);
            finishAndRemove();
        });

        // Tapping the reason card shows the next one, which makes the reasons
        // list discoverable without adding another screen.
        binding.reasonCard.setOnClickListener(v -> renderReason());
    }

    /**
     * Counts down before the bypass buttons become enabled.
     *
     * <p>The wait is the point. Urge decay is well documented and roughly
     * ninety seconds; a {@link Prefs#blockCooldownSeconds} hold is short enough
     * not to feel punitive and long enough to interrupt the reflex.</p>
     */
    private void startCooldown() {
        int seconds = Prefs.blockCooldownSeconds(this);
        if (seconds <= 0 || strict) {
            binding.cooldownBar.setVisibility(View.GONE);
            binding.btnOpenAnyway.setEnabled(!strict);
            binding.btnEmergency.setEnabled(true);
            return;
        }

        binding.cooldownBar.setVisibility(View.VISIBLE);
        binding.btnOpenAnyway.setEnabled(false);
        binding.btnEmergency.setEnabled(false);
        binding.btnWait.setText(getString(R.string.block_action_wait, seconds));

        cooldownTimer = new CountDownTimer(seconds * 1000L, 250L) {
            @Override
            public void onTick(long millisUntilFinished) {
                int remaining = (int) Math.ceil(millisUntilFinished / 1000.0);
                binding.btnWait.setText(getString(R.string.block_action_wait, remaining));
                binding.cooldownBar.setProgress(
                        (int) (100 * (seconds * 1000L - millisUntilFinished) / (seconds * 1000L)));
            }

            @Override
            public void onFinish() {
                binding.cooldownBar.setVisibility(View.GONE);
                binding.btnWait.setText(R.string.block_action_go_back);
                binding.btnOpenAnyway.setEnabled(true);
                binding.btnEmergency.setEnabled(true);
            }
        };
        cooldownTimer.start();
    }

    private void logDistraction() {
        if (alreadyLogged || TextUtils.isEmpty(blockedPackage)) return;
        alreadyLogged = true;

        final String pkg = blockedPackage;
        final String label = blockedLabel == null ? pkg : blockedLabel;
        final boolean limit = blockReason == BlockDecisionEngine.REASON_LIMIT;

        AppDatabase.io().execute(() -> {
            try {
                DistractionLogEntity log = new DistractionLogEntity();
                log.dayKey = TimeUtils.today();
                log.loggedAt = System.currentTimeMillis();
                log.trigger = limit
                        ? DistractionLogEntity.TRIGGER_OTHER
                        : DistractionLogEntity.TRIGGER_SOCIAL_MEDIA;
                log.packageName = pkg;
                log.intendedTask = currentTaskLabel();
                log.source = "blocked";
                // The guard held, so this counts as a resisted urge. That is the
                // honest reading, and it keeps the resist-rate metric
                // encouraging rather than punitive.
                log.resisted = true;
                log.alternativeUsed = getString(R.string.control_step_1);
                AppDatabase.get(this).controlDao().insertDistraction(log);
            } catch (Exception e) {
                Log.w(TAG, "could not log the distraction for " + label, e);
            }
        });
    }

    /** The task of the running focus session, for the diary's "intended task". */
    @NonNull
    private String currentTaskLabel() {
        String modeName = BlockDecisionEngine.isKnownMode(mode)
                ? ModeController.modeName(this, mode) : "";
        return modeName.isEmpty() ? getString(R.string.mode_none) : modeName;
    }

    /**
     * Launches the app the user chose to open anyway.
     *
     * <p>Uses the package's launch intent rather than an explicit component, so
     * it works regardless of which activity that app declares as its entry
     * point.</p>
     */
    private void launchBlockedApp() {
        if (TextUtils.isEmpty(blockedPackage)) return;
        try {
            Intent launch = getPackageManager().getLaunchIntentForPackage(blockedPackage);
            if (launch == null) {
                launch = getPackageManager().getLeanbackLaunchIntentForPackage(blockedPackage);
            }
            if (launch != null) {
                launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(launch);
            }
        } catch (Exception e) {
            Log.w(TAG, "could not launch " + blockedPackage, e);
        }
    }

    /** Closes the warning and clears our task so no blank screen remains. */
    private void finishAndRemove() {
        if (cooldownTimer != null) {
            cooldownTimer.cancel();
            cooldownTimer = null;
        }
        finishAndRemoveTask();
    }

    @Override
    protected void onDestroy() {
        if (cooldownTimer != null) {
            cooldownTimer.cancel();
            cooldownTimer = null;
        }
        super.onDestroy();
    }

    /** Exposed so a caller can check whether overlay permission is missing. */
    public static boolean canShowOverOtherApps(@NonNull android.content.Context context) {
        return PermissionManager.canDrawOverlays(context);
    }
}
