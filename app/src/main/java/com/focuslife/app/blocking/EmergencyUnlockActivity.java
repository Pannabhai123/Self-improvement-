package com.focuslife.app.blocking;

import android.content.Intent;
import android.os.Bundle;
import android.os.CountDownTimer;
import android.text.TextUtils;
import android.util.Log;
import android.view.View;
import android.view.WindowManager;
import android.widget.RadioGroup;

import androidx.activity.OnBackPressedCallback;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import com.focuslife.app.R;
import com.focuslife.app.data.db.AppDatabase;
import com.focuslife.app.data.db.entity.DistractionLogEntity;
import com.focuslife.app.data.prefs.Prefs;
import com.focuslife.app.databinding.ActivityEmergencyUnlockBinding;
import com.focuslife.app.util.TimeUtils;

/**
 * Emergency unlock: a deliberate, recorded, time-boxed bypass.
 *
 * <h2>Why this exists</h2>
 * A blocking system with no escape valve gets disabled entirely the first time
 * it obstructs something genuinely important — and then it protects nothing. So
 * the bypass is provided, but made <em>costly in attention rather than in
 * effort</em>: the user must state a reason, wait out a cooldown, and hold for
 * five seconds. Every use is written to the distraction diary.
 *
 * <h2>What it never becomes</h2>
 * The unlock is scoped to a single package and expires automatically. It cannot
 * be used to disable blocking globally, and it does not survive a restart of the
 * app because the unlock record is stored in a SharedPreferences file excluded
 * from backup.
 */
public class EmergencyUnlockActivity extends AppCompatActivity {

    private static final String TAG = "EmergencyUnlock";

    public static final String EXTRA_PACKAGE = "extra_package";
    public static final String EXTRA_LABEL = "extra_label";

    /** How long the bypass lasts, in minutes. */
    private static final int UNLOCK_MINUTES = 10;

    /** Press-and-hold duration before the unlock is granted. */
    private static final long HOLD_MILLIS = 5_000L;

    private ActivityEmergencyUnlockBinding binding;

    @Nullable
    private String targetPackage;

    @Nullable
    private String targetLabel;

    @Nullable
    private CountDownTimer cooldownTimer;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        binding = ActivityEmergencyUnlockBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        Intent intent = getIntent();
        targetPackage = intent == null ? null : intent.getStringExtra(EXTRA_PACKAGE);
        targetLabel = intent == null ? null : intent.getStringExtra(EXTRA_LABEL);

        if (TextUtils.isEmpty(targetPackage)) {
            Log.w(TAG, "started without a target package; closing");
            finish();
            return;
        }

        String label = TextUtils.isEmpty(targetLabel) ? targetPackage : targetLabel;
        binding.unlockAppLabel.setText(label);
        binding.unlockMessage.setText(getString(R.string.block_message, label));
        binding.unlockMinutes.setText(getString(R.string.emergency_unlocked, UNLOCK_MINUTES));
        binding.unlockUsedToday.setText(getString(
                R.string.emergency_used_today, Prefs.unlockCountToday(this)));

        binding.radioReasons.setOnCheckedChangeListener((RadioGroup group, int checkedId) -> {
            binding.customReasonField.setVisibility(
                    checkedId == R.id.radio_other ? View.VISIBLE : View.GONE);
        });

        wireHold();
        binding.btnCancel.setOnClickListener(v -> {
            // Cancelling is itself a good outcome and is recorded as a resisted
            // urge, so the diary reflects wins and not just lapses.
            recordOutcome(true, null);
            finish();
        });

        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                recordOutcome(true, null);
                finish();
            }
        });

        applyCooldown();
    }

    /** Blocks the hold gesture while the cooldown between unlocks is running. */
    private void applyCooldown() {
        long remaining = ModeController.emergencyCooldownRemaining(this);
        if (remaining <= 0) {
            binding.cooldownNotice.setVisibility(View.GONE);
            binding.holdUnlock.setEnabled(true);
            return;
        }

        binding.cooldownNotice.setVisibility(View.VISIBLE);
        binding.holdUnlock.setEnabled(false);
        binding.cooldownNotice.setText(getString(
                R.string.emergency_cooldown, TimeUtils.formatDuration(remaining)));

        cooldownTimer = new CountDownTimer(remaining, 500L) {
            @Override
            public void onTick(long millisUntilFinished) {
                binding.cooldownNotice.setText(getString(
                        R.string.emergency_cooldown, TimeUtils.formatDuration(millisUntilFinished)));
            }

            @Override
            public void onFinish() {
                binding.cooldownNotice.setVisibility(View.GONE);
                binding.holdUnlock.setEnabled(true);
            }
        };
        cooldownTimer.start();
    }

    private void wireHold() {
        binding.holdHint.setText(R.string.emergency_hold_to_unlock);

        ModeController.attachHoldToComplete(
                binding.holdUnlock,
                HOLD_MILLIS,
                binding.holdCountdown,
                getString(R.string.emergency_hold_to_unlock),
                this::grantUnlock);
    }

    private void grantUnlock() {
        if (TextUtils.isEmpty(targetPackage)) {
            finish();
            return;
        }

        String reason = selectedReason();
        ModeController.grantEmergencyUnlock(this, targetPackage, UNLOCK_MINUTES);
        recordOutcome(false, reason);

        // Take the user straight into the app they unlocked. Making them find it
        // again would be friction without purpose at this point.
        launchTarget();
        finish();
    }

    @NonNull
    private String selectedReason() {
        int id = binding.radioReasons.getCheckedRadioButtonId();
        if (id == R.id.radio_custom) {
            String typed = binding.customReasonInput.getText() == null
                    ? "" : binding.customReasonInput.getText().toString().trim();
            return typed.isEmpty() ? getString(R.string.emergency_reason_other) : typed;
        }
        if (id == R.id.radio_work) return getString(R.string.emergency_reason_work);
        if (id == R.id.radio_family) return getString(R.string.emergency_reason_family);
        if (id == R.id.radio_other) return getString(R.string.emergency_reason_other);
        // Nothing selected defaults to the genuine-emergency option, which is
        // the honest reading of someone who just held for five seconds.
        return getString(R.string.emergency_reason_genuine);
    }

    /**
     * Writes the outcome to the distraction diary.
     *
     * @param resisted true when the user backed out instead of unlocking
     * @param reason   the stated reason, or null when they backed out
     */
    private void recordOutcome(final boolean resisted, @Nullable final String reason) {
        if (TextUtils.isEmpty(targetPackage)) return;

        final String pkg = targetPackage;
        final String label = TextUtils.isEmpty(targetLabel) ? pkg : targetLabel;

        AppDatabase.io().execute(() -> {
            try {
                DistractionLogEntity log = new DistractionLogEntity();
                log.dayKey = TimeUtils.today();
                log.loggedAt = System.currentTimeMillis();
                log.trigger = DistractionLogEntity.TRIGGER_OTHER;
                log.packageName = pkg;
                log.intendedTask = reason == null ? label : reason;
                log.source = "emergency";
                log.resisted = resisted;
                log.minutesLost = resisted ? 0 : UNLOCK_MINUTES;
                log.alternativeUsed = resisted
                        ? getString(R.string.emergency_cancelled) : "";
                AppDatabase.get(this).controlDao().insertDistraction(log);
            } catch (Exception e) {
                Log.w(TAG, "could not record the unlock outcome", e);
            }
        });
    }

    private void launchTarget() {
        try {
            Intent launch = getPackageManager().getLaunchIntentForPackage(targetPackage);
            if (launch == null) {
                launch = getPackageManager().getLeanbackLaunchIntentForPackage(targetPackage);
            }
            if (launch != null) {
                launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(launch);
            }
        } catch (Exception e) {
            Log.w(TAG, "could not launch the unlocked app", e);
        }
    }

    @Override
    protected void onDestroy() {
        if (cooldownTimer != null) {
            cooldownTimer.cancel();
            cooldownTimer = null;
        }
        super.onDestroy();
    }
}
