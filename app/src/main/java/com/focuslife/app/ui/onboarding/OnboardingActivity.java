package com.focuslife.app.ui.onboarding;

import android.Manifest;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import com.focuslife.app.R;
import com.focuslife.app.data.prefs.Prefs;
import com.focuslife.app.databinding.ActivityOnboardingBinding;
import com.focuslife.app.databinding.ItemPermissionBinding;
import com.focuslife.app.notify.RoutineAlarmScheduler;
import com.focuslife.app.util.PermissionManager;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.snackbar.Snackbar;

import java.util.ArrayList;
import java.util.List;

/**
 * Permission setup, shown on first launch and reopenable from Profile.
 *
 * <h2>What this screen is for</h2>
 * The brief requires transparent permission requests and graceful handling of
 * denial, and those are the same requirement: a user can only make an informed
 * choice if they know what each permission unlocks <em>and what stops working
 * without it</em>. Every row therefore carries three pieces of text — what it is,
 * what it enables, and the consequence of declining.
 *
 * <h2>Denial is a supported outcome</h2>
 * Nothing here is mandatory. Skipping leaves the app fully usable with reduced
 * features, and each declined permission degrades exactly one thing:
 * <ul>
 *   <li>Notifications off → reminders are silent, the routine still displays.</li>
 *   <li>Usage access off → screen time reads "not available" rather than zero,
 *       and the score renormalises instead of penalising.</li>
 *   <li>Accessibility off → modes warn instead of blocking.</li>
 *   <li>Overlay off → the block warning cannot appear over other apps.</li>
 *   <li>Battery optimisation on → background work may be delayed.</li>
 * </ul>
 *
 * <h2>Re-asking</h2>
 * Android permanently denies a runtime permission after two refusals. This screen
 * therefore routes a permanently-denied notification request to system Settings
 * instead of calling {@code requestPermissions} again and silently getting
 * nothing, which is the failure mode that makes permission screens look broken.
 */
public class OnboardingActivity extends AppCompatActivity {

    private static final String TAG = "Onboarding";

    /** Permission indices, matching {@link PermissionManager#statusSnapshot}. */
    private static final int PERM_NOTIFICATIONS = 0;
    private static final int PERM_USAGE = 1;
    private static final int PERM_ACCESSIBILITY = 2;
    private static final int PERM_OVERLAY = 3;
    private static final int PERM_BATTERY = 4;

    private static final int PERMISSION_COUNT = 5;

    /** How many of the five unlock a real feature; battery is advisory. */
    private static final int CORE_PERMISSION_COUNT = 4;

    @Nullable
    private ActivityOnboardingBinding binding;

    private final List<ItemPermissionBinding> rows = new ArrayList<>();

    @Nullable
    private ActivityResultLauncher<String> notificationPermissionLauncher;

    @Nullable
    private ActivityResultLauncher<Intent> settingsLauncher;

    /** Which permission the settings screen was opened for, so the result can refresh it. */
    private int pendingSettingsPermission = -1;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivityOnboardingBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        registerLaunchers();

        ActivityOnboardingBinding b = binding;
        if (b == null) return;

        buildPermissionRows(b);

        b.btnFinish.setOnClickListener(v -> finishSetup());
        b.btnSkip.setOnClickListener(v -> finishSetup());
    }

    private void registerLaunchers() {
        notificationPermissionLauncher = registerForActivityResult(
                new ActivityResultContracts.RequestPermission(),
                granted -> {
                    refreshRow(PERM_NOTIFICATIONS);
                    if (!granted) {
                        // Read *after* the denial: false now means "don't ask
                        // again", so the system dialog will never appear again and
                        // Settings is the only way back. Saying nothing would leave
                        // a button that looks broken. This callback only runs on
                        // Android 13+, because requestNotificationPermission sends
                        // older builds straight to Settings without launching the
                        // runtime request - and on those builds the rationale flag
                        // is always false, which would misread as "don't ask again".
                        boolean dontAskAgain = !PermissionManager
                                .shouldShowNotificationRationale(this);
                        explainDenial(PERM_NOTIFICATIONS, dontAskAgain);
                    }
                    updateSummary();
                });

        // Returning from a system settings screen reports no result code, so the
        // row is re-read rather than trusting the callback payload.
        settingsLauncher = registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(),
                result -> {
                    if (pendingSettingsPermission >= 0) {
                        refreshRow(pendingSettingsPermission);
                        pendingSettingsPermission = -1;
                    }
                    refreshAll();
                    updateSummary();
                });
    }

    // ------------------------------------------------------------------
    //  Rows
    // ------------------------------------------------------------------

    private void buildPermissionRows(@NonNull ActivityOnboardingBinding b) {
        b.permissionContainer.removeAllViews();
        rows.clear();

        LayoutInflater inflater = LayoutInflater.from(this);
        for (int index = 0; index < PERMISSION_COUNT; index++) {
            ItemPermissionBinding row = ItemPermissionBinding
                    .inflate(inflater, b.permissionContainer, false);
            final int permissionIndex = index;

            row.ivPermIcon.setImageResource(iconFor(index));
            row.tvPermTitle.setText(PermissionManager.labelFor(this, index));
            row.tvPermDesc.setText(descriptionFor(index));
            row.btnPerm.setOnClickListener(v -> requestPermission(permissionIndex));

            b.permissionContainer.addView(row.getRoot());
            rows.add(row);
        }
        refreshAll();
        updateSummary();
    }

    private static int iconFor(int index) {
        switch (index) {
            case PERM_USAGE:         return R.drawable.ic_assessment;
            case PERM_ACCESSIBILITY: return R.drawable.ic_shield;
            case PERM_OVERLAY:       return R.drawable.ic_visibility;
            case PERM_BATTERY:       return R.drawable.ic_bolt;
            default:                 return R.drawable.ic_notifications;
        }
    }

    private int descriptionFor(int index) {
        switch (index) {
            case PERM_USAGE:         return R.string.onboarding_step_usage_desc;
            case PERM_ACCESSIBILITY: return R.string.onboarding_step_accessibility_desc;
            case PERM_OVERLAY:       return R.string.onboarding_step_overlay_desc;
            case PERM_BATTERY:       return R.string.onboarding_step_battery_desc;
            default:                 return R.string.onboarding_step_notifications_desc;
        }
    }

    private int consequenceFor(int index) {
        switch (index) {
            case PERM_USAGE:         return R.string.onboarding_consequence_usage;
            case PERM_ACCESSIBILITY: return R.string.onboarding_consequence_accessibility;
            case PERM_OVERLAY:       return R.string.onboarding_consequence_overlay;
            case PERM_BATTERY:       return R.string.onboarding_consequence_battery;
            default:                 return R.string.onboarding_consequence_notifications;
        }
    }

    private void refreshAll() {
        for (int index = 0; index < rows.size(); index++) {
            refreshRow(index);
        }
    }

    private void refreshRow(int index) {
        if (index < 0 || index >= rows.size()) return;
        ItemPermissionBinding row = rows.get(index);

        boolean granted = isGranted(index);
        row.ivPermState.setImageResource(granted
                ? R.drawable.ic_check_circle_filled
                : R.drawable.ic_radio_unchecked);
        row.ivPermState.setImageTintList(android.content.res.ColorStateList.valueOf(
                getColor(granted ? R.color.success_500 : R.color.text_tertiary)));

        row.btnPerm.setText(granted ? R.string.onboarding_granted : R.string.perm_grant);
        row.btnPerm.setEnabled(!granted);
        row.btnPerm.setIconResource(granted ? R.drawable.ic_check_white : 0);

        // The consequence line only matters while the permission is missing; once
        // granted it is noise.
        row.tvPermConsequence.setVisibility(granted ? View.GONE : View.VISIBLE);
        if (!granted) {
            row.tvPermConsequence.setText(consequenceFor(index));
        }
    }

    private boolean isGranted(int index) {
        boolean[] snapshot = PermissionManager.statusSnapshot(this);
        return index >= 0 && index < snapshot.length && snapshot[index];
    }

    private void updateSummary() {
        ActivityOnboardingBinding b = binding;
        if (b == null) return;

        int granted = PermissionManager.grantedCount(this);
        b.tvPermissionSummary.setText(getString(R.string.onboarding_summary,
                granted, CORE_PERMISSION_COUNT));

        // Naming the exact gaps is more useful than a bare count, and it is the
        // only place the user can see what they are still missing.
        if (granted < CORE_PERMISSION_COUNT) {
            List<String> missing = new ArrayList<>();
            for (int i = 0; i < CORE_PERMISSION_COUNT; i++) {
                if (!isGranted(i)) missing.add(PermissionManager.labelFor(this, i));
            }
            b.tvPermissionSummary.setText(getString(R.string.onboarding_summary_missing,
                    granted, CORE_PERMISSION_COUNT, joinLabels(missing)));
        }

        boolean canSchedule = RoutineAlarmScheduler.canScheduleExact(this);
        b.btnFinish.setText(canSchedule
                ? R.string.onboarding_finish
                : R.string.onboarding_finish_approximate);
    }

    /** Comma-joins labels for the summary line. */
    @NonNull
    private static String joinLabels(@NonNull List<String> parts) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < parts.size(); i++) {
            if (i > 0) sb.append(", ");
            sb.append(parts.get(i));
        }
        return sb.toString();
    }

    // ------------------------------------------------------------------
    //  Requests
    // ------------------------------------------------------------------

    private void requestPermission(int index) {
        if (isGranted(index)) return;

        if (index == PERM_NOTIFICATIONS) {
            requestNotificationPermission();
            return;
        }

        Intent intent = PermissionManager.intentFor(this, index);
        if (intent == null) {
            showMessage(getString(R.string.perm_not_available));
            return;
        }

        // Accessibility is the one permission with real power here, so it gets an
        // explicit explanation before the system screen opens. The brief asks for
        // transparency precisely where a permission is most sensitive.
        if (index == PERM_ACCESSIBILITY) {
            new MaterialAlertDialogBuilder(this)
                    .setTitle(R.string.perm_accessibility_warning)
                    .setMessage(R.string.onboarding_step_accessibility_desc)
                    .setNegativeButton(R.string.action_cancel, null)
                    .setPositiveButton(R.string.perm_grant, (dialog, which) ->
                            launchSettings(intent, index))
                    .show();
            return;
        }

        launchSettings(intent, index);
    }

    private void requestNotificationPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            // Below Android 13 notifications are allowed by default; the only way
            // to change that is the system settings screen.
            Intent intent = PermissionManager.notificationSettingsIntent(this);
            launchSettings(intent, PERM_NOTIFICATIONS);
            return;
        }

        ActivityResultLauncher<String> launcher = notificationPermissionLauncher;
        if (launcher == null) return;

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                == PackageManager.PERMISSION_GRANTED) {
            refreshRow(PERM_NOTIFICATIONS);
            return;
        }

        // Ask through the system dialog, always. This used to branch on
        // shouldShowRequestPermissionRationale and only launch the dialog when it
        // returned true - but that method is false both before the first request
        // and after a permanent denial, so on a fresh install the very first tap
        // skipped Android's prompt entirely and dropped the user into the
        // notification settings screen. The dialog itself is what distinguishes
        // the two cases, so it has to come first; the permanent-denial route is
        // handled in the result callback instead.
        launcher.launch(Manifest.permission.POST_NOTIFICATIONS);
    }

    private void launchSettings(@Nullable Intent intent, int index) {
        if (intent == null) return;
        ActivityResultLauncher<Intent> launcher = settingsLauncher;
        if (launcher == null) return;

        try {
            pendingSettingsPermission = index;
            launcher.launch(intent);
        } catch (ActivityNotFoundException | SecurityException e) {
            // ColorOS on the OPPO A18 hides some settings screens, so fall back to
            // the generic entry point rather than failing outright.
            android.util.Log.w(TAG, "settings screen unavailable for permission " + index, e);
            pendingSettingsPermission = -1;
            try {
                launcher.launch(new Intent(android.provider.Settings.ACTION_SETTINGS));
            } catch (ActivityNotFoundException ignored) {
                showMessage(getString(R.string.perm_not_available));
            }
        }
    }

    /**
     * Explains what was lost, once, without nagging.
     *
     * <p>A denial is a legitimate answer. This states the consequence and moves
     * on; it does not re-prompt or guilt the user.</p>
     */
    /**
     * @param settingsIsTheOnlyRoute true when the system will no longer show a
     *                               dialog for this permission, so the button
     *                               should lead to Settings instead of implying
     *                               that tapping the row again will help
     */
    private void explainDenial(int index, boolean settingsIsTheOnlyRoute) {
        MaterialAlertDialogBuilder builder = new MaterialAlertDialogBuilder(this)
                .setTitle(PermissionManager.deniedTitleRes(index))
                .setMessage(PermissionManager.deniedBodyRes(index))
                .setPositiveButton(R.string.onboarding_continue_anyway, null);

        if (settingsIsTheOnlyRoute) {
            Intent settings = PermissionManager.notificationSettingsIntent(this);
            if (PermissionManager.isResolvable(this, settings)) {
                builder.setNegativeButton(R.string.action_open_settings,
                        (dialog, which) -> launchSettings(settings, index));
            }
        }
        builder.show();
    }

    // ------------------------------------------------------------------

    @Override
    protected void onResume() {
        super.onResume();
        // The user may have granted something in system Settings and come back.
        refreshAll();
        updateSummary();
    }

    private void finishSetup() {
        Prefs.setOnboardingComplete(this, true);

        // Reminders can only be armed once notifications exist, so this is the
        // first moment it is worth doing.
        RoutineAlarmScheduler.scheduleNext(this);

        Intent intent = new Intent(this, com.focuslife.app.ui.MainActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        startActivity(intent);
        finish();
    }

    private void showMessage(@NonNull String message) {
        ActivityOnboardingBinding b = binding;
        if (b == null) return;
        Snackbar.make(b.getRoot(), message, Snackbar.LENGTH_LONG).show();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        rows.clear();
        binding = null;
    }
}
