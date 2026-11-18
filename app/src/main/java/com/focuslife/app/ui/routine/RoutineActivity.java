package com.focuslife.app.ui.routine;

import android.app.TimePickerDialog;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.LayoutInflater;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;

import com.focuslife.app.R;
import com.focuslife.app.data.db.AppDatabase;
import com.focuslife.app.data.db.entity.BlockScheduleEntity;
import com.focuslife.app.data.db.entity.RoutineSlotEntity;
import com.focuslife.app.data.repo.Repository;
import com.focuslife.app.databinding.ActivityRoutineBinding;
import com.focuslife.app.databinding.DialogRoutineSlotBinding;
import com.focuslife.app.util.PermissionManager;
import com.focuslife.app.util.TimeUtils;
import com.google.android.material.chip.Chip;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.snackbar.Snackbar;

import java.util.ArrayList;
import java.util.List;

/**
 * The daily routine editor.
 *
 * <p>Ships with the thirteen slots from the brief, 04:30 to 20:30, every one of
 * them editable. Reminders are delivered by the polling {@code ReminderWorker}
 * rather than by thirteen separate alarms: the routine is user-editable down to
 * the minute and each slot has its own lead time, so a flag-guarded poll is both
 * simpler and more robust than a wall of alarms that must be re-armed on every
 * edit.</p>
 */
public class RoutineActivity extends AppCompatActivity {

    private static final String TAG = "RoutineActivity";

    @Nullable
    private ActivityRoutineBinding binding;

    @Nullable
    private RoutineSlotAdapter adapter;

    @Nullable
    private Repository repository;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivityRoutineBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        repository = Repository.get(getApplicationContext());

        ActivityRoutineBinding b = binding;
        if (b == null) return;

        b.toolbar.setNavigationOnClickListener(v -> finish());

        adapter = new RoutineSlotAdapter();
        adapter.setListener(new RoutineSlotAdapter.OnSlotAction() {
            @Override
            public void onOpen(@NonNull RoutineSlotEntity slot) {
                showSlotEditor(slot);
            }

            @Override
            public void onToggleEnabled(@NonNull RoutineSlotEntity slot, boolean enabled) {
                setEnabled(slot, enabled);
            }

            @Override
            public void onDelete(@NonNull RoutineSlotEntity slot) {
                confirmDelete(slot);
            }
        });

        b.rvSlots.setLayoutManager(new LinearLayoutManager(this));
        b.rvSlots.setAdapter(adapter);
        b.rvSlots.setNestedScrollingEnabled(false);

        b.fabAddSlot.setOnClickListener(v -> showSlotEditor(null));
        b.btnResetRoutine.setOnClickListener(v -> confirmReset());
        b.btnEnableNotifications.setOnClickListener(v ->
                startActivity(PermissionManager.notificationSettingsIntent(this)));
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
        ActivityRoutineBinding b = binding;
        Repository repo = repository;
        if (b == null || repo == null) return;

        final boolean notificationsOn = PermissionManager.canPostNotifications(this);

        AppDatabase.io().execute(() -> {
            List<RoutineSlotEntity> slots;
            RoutineSlotEntity current;
            RoutineSlotEntity next;
            try {
                slots = repo.db().routineDao().all();
                int minuteNow = TimeUtils.minuteOfDayNow();
                current = repo.db().routineDao().currentSlot(minuteNow);
                next = repo.db().routineDao().nextSlot(minuteNow);
            } catch (Exception e) {
                android.util.Log.e(TAG, "could not load the routine", e);
                return;
            }

            List<RoutineSlotEntity> list = slots == null ? new ArrayList<>() : slots;
            runOnUiThread(() -> {
                ActivityRoutineBinding current2 = binding;
                if (current2 == null || adapter == null) return;
                render(list, current, next, notificationsOn);
            });
        });
    }

    private void render(@NonNull List<RoutineSlotEntity> slots,
                        @Nullable RoutineSlotEntity current,
                        @Nullable RoutineSlotEntity next,
                        boolean notificationsOn) {
        ActivityRoutineBinding b = binding;
        if (b == null || adapter == null) return;

        adapter.setNotificationsOn(notificationsOn);
        adapter.setCurrentSlotId(current == null
                ? RoutineSlotAdapter.NO_CURRENT_SLOT : current.id);
        adapter.submitList(new ArrayList<>(slots));

        b.tvRoutineEmpty.setVisibility(slots.isEmpty() ? android.view.View.VISIBLE
                : android.view.View.GONE);
        b.cardNotificationsOff.setVisibility(notificationsOn ? android.view.View.GONE
                : android.view.View.VISIBLE);

        // "Now" card: shows the slot in progress and what follows it, which is
        // the two facts that make a routine usable during the day.
        if (current == null) {
            b.cardNow.setVisibility(android.view.View.GONE);
            return;
        }
        b.cardNow.setVisibility(android.view.View.VISIBLE);
        Repository repo = repository;
        String title = repo == null ? current.title
                : repo.resolveString(current.titleRes, current.title);
        b.tvNowTitle.setText(title);
        b.tvNowTime.setText(getString(R.string.routine_now_since,
                TimeUtils.formatClock24(current.minuteOfDay),
                next == null ? getString(R.string.routine_last_slot)
                        : (repo == null ? next.title
                        : repo.resolveString(next.titleRes, next.title))));
    }

    // ------------------------------------------------------------------
    //  Editing
    // ------------------------------------------------------------------

    private void showSlotEditor(@Nullable RoutineSlotEntity existing) {
        DialogRoutineSlotBinding d = DialogRoutineSlotBinding.inflate(LayoutInflater.from(this));

        final int[] minute = {existing == null ? TimeUtils.toMinuteOfDay(6, 0) : existing.minuteOfDay};
        final String[] category = {existing == null
                ? RoutineSlotEntity.CATEGORY_STUDY : existing.category};
        final int[] remind = {existing == null ? 5 : existing.remindBeforeMin};
        final int[] daysMask = {existing == null
                ? BlockScheduleEntity.ALL_DAYS : existing.daysMask};

        d.btnTime.setText(formatMinute(minute[0]));
        d.btnTime.setOnClickListener(v -> pickTime(minute[0], picked -> {
            minute[0] = picked;
            d.btnTime.setText(formatMinute(picked));
        }));

        buildCategoryChips(d, category[0], value -> category[0] = value);
        buildDayToggles(d, daysMask, value -> daysMask[0] = value);

        int remindButtonId = remindButtonFor(remind[0]);
        d.toggleRemind.check(remindButtonId);
        d.toggleRemind.addOnButtonCheckedListener((group, checkedId, isChecked) -> {
            if (!isChecked) return;
            remind[0] = remindMinutesFor(checkedId);
        });

        if (existing != null) {
            Repository repo = repository;
            d.etTitle.setText(repo == null ? existing.title
                    : repo.resolveString(existing.titleRes, existing.title));
            d.switchEnabled.setChecked(existing.enabled);
        } else {
            d.switchEnabled.setChecked(true);
        }

        boolean isEdit = existing != null;
        AlertDialog alert = new MaterialAlertDialogBuilder(this)
                .setTitle(isEdit ? R.string.routine_edit_slot : R.string.routine_add_slot)
                .setView(d.getRoot())
                .setNegativeButton(R.string.action_cancel, null)
                .setPositiveButton(R.string.action_save, (dialog, which) -> {
                    String title = d.etTitle.getText() == null
                            ? "" : d.etTitle.getText().toString().trim();
                    if (TextUtils.isEmpty(title)) {
                        // Re-showing the dialog is cheaper than trapping the user
                        // in a validation loop, and the typed values are kept.
                        Snackbar.make(binding == null ? d.getRoot() : binding.getRoot(),
                                R.string.validation_required, Snackbar.LENGTH_LONG).show();
                        showSlotEditor(buildDraft(existing, title, minute[0], category[0],
                                remind[0], daysMask[0], d.switchEnabled.isChecked()));
                        return;
                    }
                    saveSlot(existing, title, minute[0], category[0], remind[0], daysMask[0],
                            d.switchEnabled.isChecked());
                })
                .create();
        alert.show();
    }

    /** Reconstructs a slot from the current dialog state so validation can retry. */
    @Nullable
    private RoutineSlotEntity buildDraft(@Nullable RoutineSlotEntity existing,
                                         @NonNull String title, int minute,
                                         @NonNull String category, int remind,
                                         int daysMask, boolean enabled) {
        RoutineSlotEntity draft = existing != null ? existing : new RoutineSlotEntity();
        draft.title = title;
        draft.minuteOfDay = minute;
        draft.category = category;
        draft.remindBeforeMin = remind;
        draft.daysMask = daysMask;
        draft.enabled = enabled;
        return draft;
    }

    private void buildCategoryChips(@NonNull DialogRoutineSlotBinding d,
                                    @NonNull String selected,
                                    @NonNull java.util.function.Consumer<String> onSelected) {
        d.chipGroupCategory.removeAllViews();
        String[] categories = {
                RoutineSlotEntity.CATEGORY_WAKE,
                RoutineSlotEntity.CATEGORY_FITNESS,
                RoutineSlotEntity.CATEGORY_MEALS,
                RoutineSlotEntity.CATEGORY_STUDY,
                RoutineSlotEntity.CATEGORY_REST,
                RoutineSlotEntity.CATEGORY_SLEEP,
                RoutineSlotEntity.CATEGORY_OTHER,
        };
        for (String category : categories) {
            Chip chip = new Chip(this);
            chip.setText(categoryLabel(category));
            chip.setCheckable(true);
            chip.setChecked(category.equals(selected));
            chip.setId(android.view.View.generateViewId());
            chip.setChipMinHeight(getResources().getDimension(R.dimen.touch_target));
            chip.setOnClickListener(v -> onSelected.accept(category));
            d.chipGroupCategory.addView(chip);
        }
    }

    /**
     * Builds the seven day toggles and keeps a mask in sync with them.
     *
     * <p>{@link BlockScheduleEntity}'s bit for a {@link java.util.Calendar} day
     * of week {@code d} is {@code 1 << (d - 1)}, which is why the button array is
     * ordered Sunday first.</p>
     */
    private void buildDayToggles(@NonNull DialogRoutineSlotBinding d, int[] maskHolder,
                                 @NonNull java.util.function.Consumer<Integer> onChanged) {
        int[] buttonIds = {R.id.day_sun, R.id.day_mon, R.id.day_tue, R.id.day_wed,
                R.id.day_thu, R.id.day_fri, R.id.day_sat};
        int[] bits = {BlockScheduleEntity.SUN, BlockScheduleEntity.MON, BlockScheduleEntity.TUE,
                BlockScheduleEntity.WED, BlockScheduleEntity.THU, BlockScheduleEntity.FRI,
                BlockScheduleEntity.SAT};

        for (int i = 0; i < buttonIds.length; i++) {
            if ((maskHolder[0] & bits[i]) != 0) {
                d.toggleDays.check(buttonIds[i]);
            }
        }
        d.toggleDays.addOnButtonCheckedListener((group, checkedId, isChecked) -> {
            for (int i = 0; i < buttonIds.length; i++) {
                if (buttonIds[i] != checkedId) continue;
                if (isChecked) maskHolder[0] |= bits[i];
                else maskHolder[0] &= ~bits[i];
            }
            onChanged.accept(maskHolder[0]);
        });
    }

    @NonNull
    private String categoryLabel(@NonNull String category) {
        switch (category) {
            case RoutineSlotEntity.CATEGORY_WAKE:    return getString(R.string.category_wake);
            case RoutineSlotEntity.CATEGORY_FITNESS: return getString(R.string.category_fitness);
            case RoutineSlotEntity.CATEGORY_MEALS:   return getString(R.string.category_meals);
            case RoutineSlotEntity.CATEGORY_STUDY:   return getString(R.string.category_study);
            case RoutineSlotEntity.CATEGORY_REST:    return getString(R.string.category_rest);
            case RoutineSlotEntity.CATEGORY_SLEEP:   return getString(R.string.category_sleep);
            default:                                 return getString(R.string.category_other);
        }
    }

    private interface OnTimePicked {
        void onPicked(int minuteOfDay);
    }

    private void pickTime(int currentMinute, @NonNull OnTimePicked onPicked) {
        int hour = currentMinute / 60;
        int minute = currentMinute % 60;
        new TimePickerDialog(this, R.style.Theme_FocusLife,
                (view, pickedHour, pickedMinute) ->
                        onPicked.onPicked(TimeUtils.toMinuteOfDay(pickedHour, pickedMinute)),
                hour, minute, com.focuslife.app.data.prefs.Prefs.use24HourClock(this))
                .show();
    }

    @NonNull
    private String formatMinute(int minuteOfDay) {
        return com.focuslife.app.data.prefs.Prefs.use24HourClock(this)
                ? TimeUtils.formatClock24(minuteOfDay)
                : TimeUtils.formatClock12(minuteOfDay);
    }

    private static int remindButtonFor(int minutes) {
        switch (minutes) {
            case 0:  return R.id.btn_remind_0;
            case 5:  return R.id.btn_remind_5;
            case 10: return R.id.btn_remind_10;
            case 15: return R.id.btn_remind_15;
            case 30: return R.id.btn_remind_30;
            default: return R.id.btn_remind_5;
        }
    }

    private static int remindMinutesFor(int buttonId) {
        if (buttonId == R.id.btn_remind_0) return 0;
        if (buttonId == R.id.btn_remind_10) return 10;
        if (buttonId == R.id.btn_remind_15) return 15;
        if (buttonId == R.id.btn_remind_30) return 30;
        return 5;
    }

    // ------------------------------------------------------------------
    //  Writes
    // ------------------------------------------------------------------

    private void saveSlot(@Nullable RoutineSlotEntity existing, @NonNull String title,
                          int minute, @NonNull String category, int remind,
                          int daysMask, boolean enabled) {
        Repository repo = repository;
        if (repo == null) return;

        AppDatabase.io().execute(() -> {
            try {
                RoutineSlotEntity slot = existing != null ? existing : new RoutineSlotEntity();
                slot.title = title;
                // Typing a title converts a built-in slot to a user string, so the
                // translation can no longer override what the user wrote.
                slot.titleRes = 0;
                slot.minuteOfDay = minute;
                slot.category = category;
                slot.remindBeforeMin = remind;
                slot.daysMask = daysMask;
                slot.enabled = enabled;
                slot.sortOrder = minute;
                // Clearing the notified flag lets the worker remind again today if
                // the slot was moved into the future.
                slot.notifiedDayKey = "";

                if (existing == null) {
                    repo.db().routineDao().insert(slot);
                } else {
                    repo.db().routineDao().update(slot);
                }
            } catch (Exception e) {
                android.util.Log.e(TAG, "could not save the slot", e);
            }
            // Re-arm immediately: waiting for the next poll would mean an edit
            // to a slot's time silently keeps the old alarm.
            com.focuslife.app.notify.RoutineAlarmScheduler.scheduleNext(this);
            runOnUiThread(() -> {
                showMessage(getString(R.string.routine_slot_saved));
                load();
            });
        });
    }

    private void setEnabled(@NonNull RoutineSlotEntity slot, boolean enabled) {
        Repository repo = repository;
        if (repo == null) return;

        AppDatabase.io().execute(() -> {
            try {
                slot.enabled = enabled;
                slot.notifiedDayKey = "";
                repo.db().routineDao().update(slot);
            } catch (Exception e) {
                android.util.Log.e(TAG, "could not update the slot", e);
            }
            com.focuslife.app.notify.RoutineAlarmScheduler.scheduleNext(this);
            runOnUiThread(this::load);
        });
    }

    private void confirmDelete(@NonNull RoutineSlotEntity slot) {
        Repository repo = repository;
        if (repo == null) return;

        String title = repo.resolveString(slot.titleRes, slot.title);
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.action_delete)
                .setMessage(getString(R.string.routine_delete_confirm, title))
                .setNegativeButton(R.string.action_cancel, null)
                .setPositiveButton(R.string.action_delete, (dialog, which) ->
                        AppDatabase.io().execute(() -> {
                            try {
                                repo.db().routineDao().delete(slot);
                            } catch (Exception e) {
                                android.util.Log.e(TAG, "could not delete the slot", e);
                            }
                            com.focuslife.app.notify.RoutineAlarmScheduler
                                    .scheduleNext(RoutineActivity.this);
                            runOnUiThread(() -> {
                                showMessage(getString(R.string.routine_slot_deleted));
                                load();
                            });
                        }))
                .show();
    }

    /** Restores the thirteen shipped slots, discarding edits. */
    private void confirmReset() {
        Repository repo = repository;
        if (repo == null) return;

        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.routine_reset)
                .setMessage(R.string.routine_reset_confirm)
                .setNegativeButton(R.string.action_cancel, null)
                .setPositiveButton(R.string.routine_reset, (dialog, which) ->
                        AppDatabase.io().execute(() -> {
                            try {
                                com.focuslife.app.data.db.DatabaseSeeder
                                        .resetRoutine(this, AppDatabase.get(this));
                            } catch (Exception e) {
                                android.util.Log.e(TAG, "could not reset the routine", e);
                            }
                            com.focuslife.app.notify.RoutineAlarmScheduler
                                    .scheduleNext(RoutineActivity.this);
                            runOnUiThread(() -> {
                                showMessage(getString(R.string.routine_reset_done));
                                load();
                            });
                        }))
                .show();
    }

    private void showMessage(@NonNull String message) {
        ActivityRoutineBinding b = binding;
        if (b == null) return;
        Snackbar.make(b.getRoot(), message, Snackbar.LENGTH_SHORT).show();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (binding != null) binding.rvSlots.setAdapter(null);
        adapter = null;
        binding = null;
    }
}
