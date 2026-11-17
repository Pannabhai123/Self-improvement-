package com.focuslife.app.ui.blocker;

import android.app.TimePickerDialog;
import android.content.Intent;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;

import com.focuslife.app.R;
import com.focuslife.app.blocking.BlockDecisionEngine;
import com.focuslife.app.blocking.BlockState;
import com.focuslife.app.blocking.ModeController;
import com.focuslife.app.data.db.AppDatabase;
import com.focuslife.app.data.db.entity.BlockScheduleEntity;
import com.focuslife.app.data.db.entity.BlockedAppEntity;
import com.focuslife.app.data.prefs.Prefs;
import com.focuslife.app.data.repo.Repository;
import com.focuslife.app.databinding.ActivityAppLimitBinding;
import com.focuslife.app.databinding.DialogAppLimitBinding;
import com.focuslife.app.databinding.DialogBlockScheduleBinding;
import com.focuslife.app.ui.onboarding.OnboardingActivity;
import com.focuslife.app.util.PermissionManager;
import com.focuslife.app.util.TimeUtils;
import com.google.android.material.chip.Chip;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.snackbar.Snackbar;

import java.util.ArrayList;
import java.util.List;

/**
 * Configures how each blocked app is blocked, plus scheduled windows.
 *
 * <h2>The enforcement banner</h2>
 * This screen can be fully configured and still do nothing if Accessibility has
 * not been granted, so the current enforcement level is stated at the top in
 * plain words, with a button to fix it. Configuring a blocker that cannot block,
 * without saying so, is the failure mode that makes this category of app
 * untrustworthy.
 *
 * <h2>Writes</h2>
 * Every change goes through {@link BlockState#refreshAsync}, which rebuilds the
 * in-memory snapshot the accessibility service reads. Without that, an edit here
 * would not take effect until the next cache refresh, and the user would see a
 * switch move and nothing change.
 */
public class AppLimitActivity extends AppCompatActivity {

    private static final String TAG = "AppLimit";

    @Nullable
    private ActivityAppLimitBinding binding;

    @Nullable
    private BlockedAppAdapter adapter;

    @Nullable
    private Repository repository;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivityAppLimitBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        repository = Repository.get(getApplicationContext());

        ActivityAppLimitBinding b = binding;
        if (b == null) return;

        b.toolbar.setNavigationOnClickListener(v -> finish());

        adapter = new BlockedAppAdapter();
        adapter.setListener(new BlockedAppAdapter.OnAppAction() {
            @Override
            public void onOpen(@NonNull BlockedAppEntity app) {
                showAppEditor(app);
            }

            @Override
            public void onToggle(@NonNull BlockedAppEntity app, boolean enabled) {
                setAppEnabled(app, enabled);
            }

            @Override
            public void onRemove(@NonNull BlockedAppEntity app) {
                confirmRemove(app);
            }
        });

        b.rvBlocked.setLayoutManager(new LinearLayoutManager(this));
        b.rvBlocked.setAdapter(adapter);
        b.rvBlocked.setNestedScrollingEnabled(false);

        b.btnPickApps.setOnClickListener(v ->
                startActivity(new Intent(this, AppPickerActivity.class)));
        b.btnAddSchedule.setOnClickListener(v -> showScheduleEditor(null));
        b.btnFixPermissions.setOnClickListener(v ->
                startActivity(new Intent(this, OnboardingActivity.class)));
    }

    @Override
    protected void onResume() {
        super.onResume();
        load();
    }

    // ------------------------------------------------------------------
    //  Data
    // ------------------------------------------------------------------

    private void load() {
        ActivityAppLimitBinding b = binding;
        Repository repo = repository;
        if (b == null || repo == null) return;

        final boolean usageAvailable = PermissionManager.hasUsageAccess(this);
        final int enforcement = ModeController.enforcementLevel(this);

        AppDatabase.io().execute(() -> {
            List<BlockedAppEntity> apps;
            List<BlockScheduleEntity> schedules;
            int totalBlocks;
            try {
                apps = repo.loadBlockedApps();
                schedules = repo.db().blockerDao().schedules();
                totalBlocks = repo.db().blockerDao().totalBlockCount();
            } catch (Exception e) {
                android.util.Log.e(TAG, "could not load the block list", e);
                return;
            }

            List<BlockedAppEntity> appList = apps == null ? new ArrayList<>() : apps;
            List<BlockScheduleEntity> scheduleList =
                    schedules == null ? new ArrayList<>() : schedules;

            runOnUiThread(() -> {
                ActivityAppLimitBinding current = binding;
                if (current == null || adapter == null) return;

                renderEnforcement(current, enforcement);

                adapter.setUsageAvailable(usageAvailable);
                adapter.submitList(new ArrayList<>(appList));

                current.tvNoBlocked.setVisibility(appList.isEmpty() ? View.VISIBLE : View.GONE);
                current.rvBlocked.setVisibility(appList.isEmpty() ? View.GONE : View.VISIBLE);
                current.tvBlockedCount.setText(appList.size() == 1
                        ? getString(R.string.blocker_blocked_count, appList.size())
                        : getString(R.string.blocker_blocked_count_plural, appList.size()));
                current.tvTotalBlocks.setText(totalBlocks == 1
                        ? getString(R.string.blocker_total_blocks_one)
                        : getString(R.string.blocker_total_blocks, totalBlocks));

                renderSchedules(current, scheduleList);
            });
        });
    }

    private void renderEnforcement(@NonNull ActivityAppLimitBinding b, int enforcement) {
        if (enforcement == ModeController.ENFORCEMENT_FULL) {
            b.ivEnforcementIcon.setImageResource(R.drawable.ic_shield);
            b.ivEnforcementIcon.setImageTintList(tint(R.color.success_500));
            b.tvEnforcementTitle.setText(R.string.blocker_enforcement_full);
            b.tvEnforcementDesc.setText(R.string.blocker_enforcement_full_desc);
            b.btnFixPermissions.setVisibility(View.GONE);
        } else if (enforcement == ModeController.ENFORCEMENT_NOTIFICATION) {
            b.ivEnforcementIcon.setImageResource(R.drawable.ic_warning);
            b.ivEnforcementIcon.setImageTintList(tint(R.color.warning_500));
            b.tvEnforcementTitle.setText(R.string.blocker_enforcement_notification);
            b.tvEnforcementDesc.setText(R.string.blocker_enforcement_notification_desc);
            b.btnFixPermissions.setVisibility(View.VISIBLE);
        } else {
            b.ivEnforcementIcon.setImageResource(R.drawable.ic_block);
            b.ivEnforcementIcon.setImageTintList(tint(R.color.danger_500));
            b.tvEnforcementTitle.setText(R.string.blocker_enforcement_none);
            b.tvEnforcementDesc.setText(R.string.blocker_enforcement_none_desc);
            b.btnFixPermissions.setVisibility(View.VISIBLE);
        }
    }

    @NonNull
    private android.content.res.ColorStateList tint(int colorRes) {
        return android.content.res.ColorStateList.valueOf(getColor(colorRes));
    }

    private void renderSchedules(@NonNull ActivityAppLimitBinding b,
                                 @NonNull List<BlockScheduleEntity> schedules) {
        b.scheduleContainer.removeAllViews();
        b.tvNoSchedules.setVisibility(schedules.isEmpty() ? View.VISIBLE : View.GONE);

        LayoutInflater inflater = LayoutInflater.from(this);
        for (BlockScheduleEntity schedule : schedules) {
            if (schedule == null) continue;
            View row = inflater.inflate(R.layout.item_schedule_row, b.scheduleContainer, false);

            android.widget.TextView title = row.findViewById(R.id.tv_schedule_title);
            android.widget.TextView detail = row.findViewById(R.id.tv_schedule_detail);
            com.google.android.material.materialswitch.MaterialSwitch toggle =
                    row.findViewById(R.id.switch_schedule);

            title.setText(TextUtils.isEmpty(schedule.label)
                    ? ModeController.modeName(this, schedule.mode)
                    : schedule.label);

            detail.setText(formatScheduleRange(schedule.startMinute, schedule.endMinute));

            float alpha = schedule.enabled ? 1f : 0.5f;
            title.setAlpha(alpha);
            detail.setAlpha(alpha);

            toggle.setOnCheckedChangeListener(null);
            toggle.setChecked(schedule.enabled);
            toggle.setOnCheckedChangeListener((view, checked) -> setScheduleEnabled(schedule, checked));

            row.setOnClickListener(v -> showScheduleEditor(schedule));
            row.findViewById(R.id.btn_schedule_delete)
                    .setOnClickListener(v -> confirmDeleteSchedule(schedule));

            b.scheduleContainer.addView(row);
        }
    }

    /**
     * Renders a schedule window as "start – end", honouring the 12/24-hour
     * preference and the localised separator string.
     *
     * <p>Named for what it produces rather than what it consumes: it formats a
     * range, and {@code formatMinute} read like a single-time formatter next to
     * {@code TimeUtils.formatMinutes}, which formats a duration.</p>
     */
    @NonNull
    private String formatScheduleRange(int startMinute, int endMinute) {
        boolean use24 = Prefs.use24HourClock(this);
        String start = use24
                ? TimeUtils.formatClock24(startMinute) : TimeUtils.formatClock12(startMinute);
        String end = use24
                ? TimeUtils.formatClock24(endMinute) : TimeUtils.formatClock12(endMinute);
        return getString(R.string.blocker_schedule_from_to, start, end);
    }

    // ------------------------------------------------------------------
    //  Per-app editor
    // ------------------------------------------------------------------

    private void showAppEditor(@NonNull BlockedAppEntity app) {
        DialogAppLimitBinding d = DialogAppLimitBinding.inflate(LayoutInflater.from(this));

        final int[] limit = {app.dailyLimitMin};

        d.toggleLimit.check(limitButtonFor(limit[0]));
        d.fieldCustomLimit.setVisibility(isPresetLimit(limit[0]) ? View.GONE : View.VISIBLE);
        if (!isPresetLimit(limit[0]) && limit[0] > 0) {
            d.etCustomLimit.setText(String.valueOf(limit[0]));
        }

        d.toggleLimit.addOnButtonCheckedListener((group, checkedId, isChecked) -> {
            if (!isChecked) return;
            if (checkedId == R.id.btn_limit_custom) {
                d.fieldCustomLimit.setVisibility(View.VISIBLE);
                Editable text = d.etCustomLimit.getText();
                limit[0] = parseIntOr(text, limit[0]);
            } else {
                d.fieldCustomLimit.setVisibility(View.GONE);
                limit[0] = limitMinutesFor(checkedId);
            }
        });

        d.switchFocus.setChecked(app.blockInFocus);
        d.switchStudy.setChecked(app.blockInStudy);
        d.switchRun.setChecked(app.blockInRun);
        d.switchSleep.setChecked(app.blockInSleep);
        d.switchAlways.setChecked(app.blockAlways);
        d.switchStrict.setChecked(app.strict);

        d.tvUsageToday.setText(usageLineFor(app));

        new MaterialAlertDialogBuilder(this)
                .setTitle(app.appLabel)
                .setView(d.getRoot())
                .setNegativeButton(R.string.action_cancel, null)
                .setPositiveButton(R.string.action_save, (dialog, which) -> {
                    int custom = parseIntOr(d.etCustomLimit.getText(), 0);
                    int chosen = d.toggleLimit.getCheckedButtonId() == R.id.btn_limit_custom
                            ? custom : limit[0];
                    saveApp(app, chosen,
                            d.switchFocus.isChecked(), d.switchStudy.isChecked(),
                            d.switchRun.isChecked(), d.switchSleep.isChecked(),
                            d.switchAlways.isChecked(), d.switchStrict.isChecked());
                })
                .show();
    }

    @NonNull
    private String usageLineFor(@NonNull BlockedAppEntity app) {
        if (!PermissionManager.hasUsageAccess(this)) {
            return getString(R.string.blocker_limit_usage_unavailable);
        }
        String used = TimeUtils.formatMinutes(app.dayKeyMatches(TimeUtils.today())
                ? app.usedTodayMin : 0);
        return app.dailyLimitMin > 0
                ? getString(R.string.blocker_limit_usage_today, used,
                        TimeUtils.formatMinutes(app.dailyLimitMin))
                : getString(R.string.blocker_limit_usage_no_limit, used);
    }

    private static boolean isPresetLimit(int minutes) {
        return minutes == 0 || minutes == 15 || minutes == 30 || minutes == 60;
    }

    private static int limitButtonFor(int minutes) {
        switch (minutes) {
            case 0:  return R.id.btn_limit_0;
            case 15: return R.id.btn_limit_15;
            case 30: return R.id.btn_limit_30;
            case 60: return R.id.btn_limit_60;
            default: return R.id.btn_limit_custom;
        }
    }

    private static int limitMinutesFor(int buttonId) {
        if (buttonId == R.id.btn_limit_15) return 15;
        if (buttonId == R.id.btn_limit_30) return 30;
        if (buttonId == R.id.btn_limit_60) return 60;
        if (buttonId == R.id.btn_limit_custom) return 0;
        return 0;
    }

    private static int parseIntOr(@Nullable Editable text, int fallback) {
        if (text == null) return fallback;
        try {
            int v = Integer.parseInt(text.toString().trim());
            return v < 0 ? 0 : Math.min(v, 24 * 60);
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private void saveApp(@NonNull BlockedAppEntity app, int dailyLimit,
                         boolean focus, boolean study, boolean run, boolean sleep,
                         boolean always, boolean strict) {
        Repository repo = repository;
        if (repo == null) return;

        AppDatabase.io().execute(() -> {
            try {
                app.dailyLimitMin = Math.max(0, dailyLimit);
                app.blockInFocus = focus;
                app.blockInStudy = study;
                app.blockInRun = run;
                app.blockInSleep = sleep;
                app.blockAlways = always;
                app.strict = strict;
                // A limit only means something if some usage is attributed to it,
                // so reset the day's counter when the limit changes.
                if (!app.dayKeyMatches(TimeUtils.today())) {
                    app.usedTodayMin = 0;
                    app.usageDayKey = TimeUtils.today();
                }
                repo.updateBlockedApp(app);
            } catch (Exception e) {
                android.util.Log.e(TAG, "could not save " + app.packageName, e);
            }
            // The service reads a cached snapshot, so it must be rebuilt before
            // this change has any effect.
            BlockState.get().refreshAsync(AppLimitActivity.this);
            runOnUiThread(() -> {
                showMessage(getString(R.string.blocker_app_updated));
                load();
            });
        });
    }

    private void setAppEnabled(@NonNull BlockedAppEntity app, boolean enabled) {
        Repository repo = repository;
        if (repo == null) return;

        AppDatabase.io().execute(() -> {
            try {
                repo.db().blockerDao().setEnabled(app.packageName, enabled);
            } catch (Exception e) {
                android.util.Log.e(TAG, "could not toggle " + app.packageName, e);
            }
            BlockState.get().refreshAsync(AppLimitActivity.this);
            runOnUiThread(() -> {
                showMessage(getString(enabled ? R.string.blocker_enabled
                        : R.string.blocker_paused));
                load();
            });
        });
    }

    private void confirmRemove(@NonNull BlockedAppEntity app) {
        Repository repo = repository;
        if (repo == null) return;

        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.blocker_remove)
                .setMessage(getString(R.string.blocker_remove_confirm, app.appLabel))
                .setNegativeButton(R.string.action_cancel, null)
                .setPositiveButton(R.string.blocker_remove, (dialog, which) ->
                        AppDatabase.io().execute(() -> {
                            try {
                                repo.removeBlockedApp(app.packageName);
                            } catch (Exception e) {
                                android.util.Log.e(TAG, "could not remove " + app.packageName, e);
                            }
                            BlockState.get().refreshAsync(AppLimitActivity.this);
                            runOnUiThread(() -> {
                                showMessage(getString(R.string.blocker_removed, app.appLabel));
                                load();
                            });
                        }))
                .show();
    }

    // ------------------------------------------------------------------
    //  Schedules
    // ------------------------------------------------------------------

    private void showScheduleEditor(@Nullable BlockScheduleEntity existing) {
        DialogBlockScheduleBinding d = DialogBlockScheduleBinding
                .inflate(LayoutInflater.from(this));

        final int[] start = {existing == null ? TimeUtils.toMinuteOfDay(21, 0) : existing.startMinute};
        final int[] end = {existing == null ? TimeUtils.toMinuteOfDay(4, 30) : existing.endMinute};
        final int[] days = {existing == null ? BlockScheduleEntity.ALL_DAYS : existing.daysMask};
        final String[] mode = {existing == null
                ? BlockDecisionEngine.MODE_SLEEP : existing.mode};

        if (existing != null && !TextUtils.isEmpty(existing.label)) {
            d.etLabel.setText(existing.label);
        }

        d.btnStart.setText(clockFor(start[0]));
        d.btnEnd.setText(clockFor(end[0]));
        d.btnStart.setOnClickListener(v -> pickTime(start[0], picked -> {
            start[0] = picked;
            d.btnStart.setText(clockFor(picked));
            updateMidnightHint(d, start[0], end[0]);
        }));
        d.btnEnd.setOnClickListener(v -> pickTime(end[0], picked -> {
            end[0] = picked;
            d.btnEnd.setText(clockFor(picked));
            updateMidnightHint(d, start[0], end[0]);
        }));
        updateMidnightHint(d, start[0], end[0]);

        buildModeChips(d, mode[0], value -> mode[0] = value);

        int[] buttonIds = {R.id.day_sun, R.id.day_mon, R.id.day_tue, R.id.day_wed,
                R.id.day_thu, R.id.day_fri, R.id.day_sat};
        int[] bits = {BlockScheduleEntity.SUN, BlockScheduleEntity.MON, BlockScheduleEntity.TUE,
                BlockScheduleEntity.WED, BlockScheduleEntity.THU, BlockScheduleEntity.FRI,
                BlockScheduleEntity.SAT};
        for (int i = 0; i < buttonIds.length; i++) {
            if ((days[0] & bits[i]) != 0) d.toggleDays.check(buttonIds[i]);
        }
        d.toggleDays.addOnButtonCheckedListener((group, checkedId, isChecked) -> {
            for (int i = 0; i < buttonIds.length; i++) {
                if (buttonIds[i] != checkedId) continue;
                if (isChecked) days[0] |= bits[i];
                else days[0] &= ~bits[i];
            }
        });

        d.switchEnabled.setChecked(existing == null || existing.enabled);

        boolean isEdit = existing != null;
        AlertDialog alert = new MaterialAlertDialogBuilder(this)
                .setTitle(isEdit ? R.string.blocker_schedule_edit : R.string.blocker_schedule_add)
                .setView(d.getRoot())
                .setNegativeButton(R.string.action_cancel, null)
                .setPositiveButton(R.string.action_save, null)
                .create();
        alert.show();

        alert.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            // A schedule with no days selected can never fire, which reads as a
            // bug rather than a choice, so it is rejected here.
            if (days[0] == 0) {
                showMessage(getString(R.string.blocker_schedule_needs_days));
                return;
            }
            String label = d.etLabel.getText() == null
                    ? "" : d.etLabel.getText().toString().trim();
            saveSchedule(existing, label, start[0], end[0], days[0], mode[0],
                    d.switchEnabled.isChecked());
            alert.dismiss();
        });
    }

    private void buildModeChips(@NonNull DialogBlockScheduleBinding d, @NonNull String selected,
                                @NonNull java.util.function.Consumer<String> onSelected) {
        d.chipGroupMode.removeAllViews();
        String[] modes = {BlockDecisionEngine.MODE_FOCUS, BlockDecisionEngine.MODE_STUDY,
                BlockDecisionEngine.MODE_RUN, BlockDecisionEngine.MODE_SLEEP};
        for (String value : modes) {
            Chip chip = new Chip(this);
            chip.setText(ModeController.modeName(this, value));
            chip.setCheckable(true);
            chip.setChecked(value.equals(selected));
            chip.setId(View.generateViewId());
            chip.setChipMinHeight(getResources().getDimension(R.dimen.touch_target));
            chip.setOnClickListener(v -> onSelected.accept(value));
            d.chipGroupMode.addView(chip);
        }
    }

    /** A window whose end is before its start crosses midnight, which is valid. */
    private void updateMidnightHint(@NonNull DialogBlockScheduleBinding d, int start, int end) {
        d.tvCrossesMidnight.setVisibility(end <= start ? View.VISIBLE : View.GONE);
    }

    private interface OnTimePicked {
        void onPicked(int minuteOfDay);
    }

    private void pickTime(int currentMinute, @NonNull OnTimePicked onPicked) {
        // A full theme rather than a ThemeOverlay: an overlay carries no window
        // attributes, so the picker would fall back to the light platform default.
        new TimePickerDialog(this, R.style.Theme_FocusLife,
                (view, hour, minute) -> onPicked.onPicked(TimeUtils.toMinuteOfDay(hour, minute)),
                currentMinute / 60, currentMinute % 60, Prefs.use24HourClock(this))
                .show();
    }

    @NonNull
    private String clockFor(int minuteOfDay) {
        return Prefs.use24HourClock(this)
                ? TimeUtils.formatClock24(minuteOfDay)
                : TimeUtils.formatClock12(minuteOfDay);
    }

    private void saveSchedule(@Nullable BlockScheduleEntity existing, @NonNull String label,
                              int startMinute, int endMinute, int daysMask,
                              @NonNull String mode, boolean enabled) {
        Repository repo = repository;
        if (repo == null) return;

        AppDatabase.io().execute(() -> {
            try {
                BlockScheduleEntity schedule = existing != null ? existing : new BlockScheduleEntity();
                schedule.label = label;
                schedule.startMinute = startMinute;
                schedule.endMinute = endMinute;
                schedule.daysMask = daysMask;
                schedule.mode = mode;
                schedule.enabled = enabled;
                if (existing == null) {
                    schedule.createdAt = System.currentTimeMillis();
                    repo.db().blockerDao().insertSchedule(schedule);
                } else {
                    repo.db().blockerDao().updateSchedule(schedule);
                }
            } catch (Exception e) {
                android.util.Log.e(TAG, "could not save the schedule", e);
            }
            BlockState.get().refreshAsync(AppLimitActivity.this);
            runOnUiThread(() -> {
                showMessage(getString(R.string.blocker_schedule_saved));
                load();
            });
        });
    }

    private void setScheduleEnabled(@NonNull BlockScheduleEntity schedule, boolean enabled) {
        Repository repo = repository;
        if (repo == null) return;

        AppDatabase.io().execute(() -> {
            try {
                schedule.enabled = enabled;
                repo.db().blockerDao().updateSchedule(schedule);
            } catch (Exception e) {
                android.util.Log.e(TAG, "could not toggle the schedule", e);
            }
            BlockState.get().refreshAsync(AppLimitActivity.this);
            runOnUiThread(this::load);
        });
    }

    private void confirmDeleteSchedule(@NonNull BlockScheduleEntity schedule) {
        Repository repo = repository;
        if (repo == null) return;

        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.action_delete)
                .setMessage(R.string.blocker_schedule_delete_confirm)
                .setNegativeButton(R.string.action_cancel, null)
                .setPositiveButton(R.string.action_delete, (dialog, which) ->
                        AppDatabase.io().execute(() -> {
                            try {
                                repo.db().blockerDao().deleteSchedule(schedule);
                            } catch (Exception e) {
                                android.util.Log.e(TAG, "could not delete the schedule", e);
                            }
                            BlockState.get().refreshAsync(AppLimitActivity.this);
                            runOnUiThread(() -> {
                                showMessage(getString(R.string.blocker_schedule_deleted));
                                load();
                            });
                        }))
                .show();
    }

    private void showMessage(@NonNull String message) {
        ActivityAppLimitBinding b = binding;
        if (b == null) return;
        Snackbar.make(b.getRoot(), message, Snackbar.LENGTH_SHORT).show();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (binding != null) binding.rvBlocked.setAdapter(null);
        adapter = null;
        binding = null;
    }
}
