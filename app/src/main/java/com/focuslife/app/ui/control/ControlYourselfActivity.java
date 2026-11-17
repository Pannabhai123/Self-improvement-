package com.focuslife.app.ui.control;

import android.os.Bundle;
import android.text.Editable;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;

import com.focuslife.app.R;
import com.focuslife.app.data.db.AppDatabase;
import com.focuslife.app.data.db.dao.FocusSessionDao;
import com.focuslife.app.data.db.entity.DistractionLogEntity;
import com.focuslife.app.data.db.entity.ReasonEntity;
import com.focuslife.app.data.repo.Repository;
import com.focuslife.app.databinding.ActivityControlBinding;
import com.focuslife.app.databinding.DialogDistractionEntryBinding;
import com.focuslife.app.databinding.ItemReasonBinding;
import com.focuslife.app.ui.focus.FocusTimerActivity;
import com.focuslife.app.util.TimeUtils;
import com.google.android.material.chip.Chip;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.snackbar.Snackbar;

import java.util.ArrayList;
import java.util.List;

/**
 * CONTROL YOURSELF — brief section 8.
 *
 * <h2>Order of content</h2>
 * The four-step routine and the emergency focus button come first, before any
 * statistics, because this screen is opened during an urge. At that moment the
 * useful thing is an action to take, not a chart to read. The diary and trigger
 * analysis follow, for the calmer moment when the pattern is worth studying.
 *
 * <h2>What the diary records</h2>
 * Trigger, intended task, minutes lost, urge strength, whether it was resisted
 * and what was done instead. A resisted urge counts as a success and is written
 * by the block screen automatically, so the diary fills itself up with wins
 * rather than only lapses.
 */
public class ControlYourselfActivity extends AppCompatActivity {

    private static final String TAG = "ControlYourself";

    /** The ten five-minute alternatives from the brief, in the order given. */
    private static final int[] ALTERNATIVE_RES = {
            R.string.alt_water, R.string.alt_walk, R.string.alt_pushups,
            R.string.alt_breathe, R.string.alt_stretch, R.string.alt_eyes,
            R.string.alt_write, R.string.alt_revise, R.string.alt_tidy,
            R.string.alt_sunlight
    };

    @Nullable
    private ActivityControlBinding binding;

    @Nullable
    private DistractionDiaryAdapter adapter;

    @Nullable
    private Repository repository;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivityControlBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        repository = Repository.get(getApplicationContext());

        ActivityControlBinding b = binding;
        if (b == null) return;

        b.toolbar.setNavigationOnClickListener(v -> finish());

        bindSteps(b);
        bindAlternatives(b);

        adapter = new DistractionDiaryAdapter();
        adapter.setListener(this::confirmDeleteEntry);
        b.rvDiary.setLayoutManager(new LinearLayoutManager(this));
        b.rvDiary.setAdapter(adapter);
        b.rvDiary.setNestedScrollingEnabled(false);

        b.btnEmergencyFocus.setOnClickListener(v -> startEmergencyFocus());
        b.btnAddEntry.setOnClickListener(v -> showEntryEditor());
        b.btnAddReason.setOnClickListener(v -> showReasonEditor(null));

        bindMetrics(b);
    }

    @Override
    protected void onResume() {
        super.onResume();
        load();
    }

    // ------------------------------------------------------------------
    //  Static content
    // ------------------------------------------------------------------

    /** The four-step distraction message from the brief, verbatim. */
    private void bindSteps(@NonNull ActivityControlBinding b) {
        bindStep(b.stepRow1, "1", R.string.control_step_1);
        bindStep(b.stepRow2, "2", R.string.control_step_2);
        bindStep(b.stepRow3, "3", R.string.control_step_3);
        bindStep(b.stepRow4, "4", R.string.control_step_4);
    }

    private void bindStep(@NonNull com.focuslife.app.databinding.ItemResetStepBinding row,
                          @NonNull String number, int textRes) {
        row.tvStepNumber.setText(number);
        row.tvStepText.setText(textRes);
    }

    private void bindAlternatives(@NonNull ActivityControlBinding b) {
        b.chipGroupAlternatives.removeAllViews();
        for (int res : ALTERNATIVE_RES) {
            final String label = getString(res);
            Chip chip = new Chip(this);
            chip.setText(label);
            chip.setCheckable(false);
            chip.setChipMinHeight(getResources().getDimension(R.dimen.touch_target));
            chip.setId(View.generateViewId());
            chip.setOnClickListener(v -> logAlternative(label));
            b.chipGroupAlternatives.addView(chip);
        }
    }

    private void bindMetrics(@NonNull ActivityControlBinding b) {
        b.metricResisted.ivIcon.setImageResource(R.drawable.ic_shield);
        b.metricResisted.ivIcon.setImageTintList(tint(R.color.success_500));
        b.metricResisted.tvLabel.setText(R.string.control_resist_label);
        b.metricResisted.pbMetric.setVisibility(View.GONE);

        b.metricMinutesLost.ivIcon.setImageResource(R.drawable.ic_timer);
        b.metricMinutesLost.ivIcon.setImageTintList(tint(R.color.warning_500));
        b.metricMinutesLost.tvLabel.setText(R.string.control_minutes_label);
        b.metricMinutesLost.pbMetric.setVisibility(View.GONE);
    }

    @NonNull
    private android.content.res.ColorStateList tint(int colorRes) {
        return android.content.res.ColorStateList.valueOf(getColor(colorRes));
    }

    // ------------------------------------------------------------------
    //  Data
    // ------------------------------------------------------------------

    private void load() {
        ActivityControlBinding b = binding;
        Repository repo = repository;
        if (b == null || repo == null) return;

        AppDatabase.io().execute(() -> {
            Payload p;
            try {
                p = collect(repo);
            } catch (Exception e) {
                android.util.Log.e(TAG, "could not load the diary", e);
                return;
            }
            runOnUiThread(() -> {
                ActivityControlBinding current = binding;
                if (current == null || adapter == null) return;
                render(p);
            });
        });
    }

    private static final class Payload {
        List<DistractionLogEntity> entries = new ArrayList<>();
        List<FocusSessionDao.ChartPoint> triggers = new ArrayList<>();
        List<ReasonEntity> reasons = new ArrayList<>();
        int resisted;
        int totalUrges;
        int minutesLost;
    }

    @NonNull
    private Payload collect(@NonNull Repository repo) {
        Payload p = new Payload();
        String[] week = TimeUtils.currentWeekRange();

        p.entries = repo.db().controlDao().between(week[0], week[1]);
        if (p.entries == null) p.entries = new ArrayList<>();
        // Newest first: the diary is read top-down as a timeline.
        java.util.Collections.sort(p.entries,
                (x, y) -> Long.compare(y.loggedAt, x.loggedAt));

        p.triggers = repo.db().controlDao().triggerCounts(week[0], week[1]);
        p.reasons = repo.db().controlDao().reasons();
        p.resisted = repo.db().controlDao().resistedCount(week[0], week[1]);
        p.totalUrges = repo.db().controlDao().totalUrgeCount(week[0], week[1]);
        p.minutesLost = repo.db().controlDao().minutesLostPerTrigger(week[0], week[1])
                .stream().mapToInt(point -> point.value).sum();
        return p;
    }

    private void render(@NonNull Payload p) {
        ActivityControlBinding b = binding;
        if (b == null || adapter == null) return;

        adapter.submitList(new ArrayList<>(p.entries));
        b.tvDiaryEmpty.setVisibility(p.entries.isEmpty() ? View.VISIBLE : View.GONE);
        b.rvDiary.setVisibility(p.entries.isEmpty() ? View.GONE : View.VISIBLE);

        b.metricResisted.tvValue.setText(p.totalUrges == 0
                ? getString(R.string.label_empty_no_data)
                : p.resisted + "/" + p.totalUrges);
        b.metricResisted.tvSub.setText(getString(R.string.control_resist_summary,
                p.resisted, p.totalUrges));

        b.metricMinutesLost.tvValue.setText(TimeUtils.formatMinutes(p.minutesLost));
        b.metricMinutesLost.tvSub.setText(getString(R.string.control_minutes_lost_summary,
                TimeUtils.formatMinutes(p.minutesLost)));

        renderTriggers(b, p);
        renderReasons(b, p);
    }

    private void renderTriggers(@NonNull ActivityControlBinding b, @NonNull Payload p) {
        boolean empty = p.triggers == null || p.triggers.isEmpty();
        b.chartTriggers.setVisibility(empty ? View.GONE : View.VISIBLE);
        b.tvTriggersEmpty.setVisibility(empty ? View.VISIBLE : View.GONE);

        if (empty) {
            b.tvTopTrigger.setText(R.string.control_top_trigger_none);
            b.tvTriggersEmpty.setText(R.string.control_triggers_empty);
            return;
        }

        int[] values = new int[p.triggers.size()];
        String[] labels = new String[p.triggers.size()];
        for (int i = 0; i < p.triggers.size(); i++) {
            FocusSessionDao.ChartPoint point = p.triggers.get(i);
            values[i] = point.value;
            labels[i] = DistractionDiaryAdapter.triggerLabel(this, point.label);
        }
        b.chartTriggers.setData(values, labels);
        b.chartTriggers.setValueSuffix("");

        // The trigger query orders by count descending, so the first row is the
        // pattern most worth acting on.
        FocusSessionDao.ChartPoint top = p.triggers.get(0);
        b.tvTopTrigger.setText(getString(R.string.control_top_trigger,
                DistractionDiaryAdapter.triggerLabel(this, top.label), top.value));
    }

    private void renderReasons(@NonNull ActivityControlBinding b, @NonNull Payload p) {
        b.reasonContainer.removeAllViews();
        boolean empty = p.reasons == null || p.reasons.isEmpty();
        b.tvReasonEmpty.setVisibility(empty ? View.VISIBLE : View.GONE);
        if (empty) return;

        LayoutInflater inflater = LayoutInflater.from(this);
        for (ReasonEntity reason : p.reasons) {
            if (reason == null || TextUtils.isEmpty(reason.text)) continue;

            ItemReasonBinding row = ItemReasonBinding
                    .inflate(inflater, b.reasonContainer, false);
            row.tvReasonText.setText(reason.text);
            row.tvReasonShown.setText(reason.timesShown == 0
                    ? getString(R.string.control_reason_never_shown)
                    : (reason.timesShown == 1
                            ? getString(R.string.control_reason_shown_once)
                            : getString(R.string.control_reason_shown, reason.timesShown)));

            row.getRoot().setOnClickListener(v -> showReasonEditor(reason));
            row.btnReasonDelete.setOnClickListener(v -> confirmDeleteReason(reason));

            b.reasonContainer.addView(row.getRoot());
        }
    }

    // ------------------------------------------------------------------
    //  Actions
    // ------------------------------------------------------------------

    /**
     * The emergency focus button: starts a short Deep Focus session immediately.
     *
     * <p>Five minutes rather than twenty-five. At the moment of an urge the
     * commitment has to be small enough to accept, and a completed five-minute
     * session is worth more than an abandoned twenty-five-minute one.</p>
     */
    private void startEmergencyFocus() {
        showMessage(getString(R.string.control_emergency_started));
        startActivity(FocusTimerActivity.intent(this, 5, 0, 1,
                getString(R.string.control_emergency_focus), true));
    }

    /**
     * Logs a five-minute alternative as a resisted urge.
     *
     * <p>Tapping the chip is the whole interaction: no dialog, no fields. The
     * alternative activities exist to replace the urge in the moment, so anything
     * that makes recording one slower than doing it defeats the purpose.</p>
     */
    private void logAlternative(@NonNull String alternative) {
        Repository repo = repository;
        if (repo == null) return;

        AppDatabase.io().execute(() -> {
            try {
                DistractionLogEntity entry = new DistractionLogEntity();
                entry.dayKey = TimeUtils.today();
                entry.loggedAt = System.currentTimeMillis();
                entry.trigger = DistractionLogEntity.TRIGGER_OTHER;
                entry.intendedTask = "";
                entry.minutesLost = 0;
                // A five-minute alternative costs five minutes by definition.
                entry.urgeLevel = 0;
                entry.resisted = true;
                entry.alternativeUsed = alternative;
                entry.source = "alternative";
                repo.db().controlDao().insertDistraction(entry);
            } catch (Exception e) {
                android.util.Log.e(TAG, "could not log the alternative", e);
            }
            runOnUiThread(() -> {
                showMessage(getString(R.string.control_alternative_done, alternative));
                load();
            });
        });
    }

    private void showEntryEditor() {
        DialogDistractionEntryBinding d = DialogDistractionEntryBinding
                .inflate(LayoutInflater.from(this));

        final String[] trigger = {DistractionLogEntity.TRIGGER_BOREDOM};
        final String[] alternative = {""};

        for (String value : DistractionLogEntity.PRESET_TRIGGERS) {
            Chip chip = new Chip(this);
            chip.setText(DistractionDiaryAdapter.triggerLabel(this, value));
            chip.setCheckable(true);
            chip.setChecked(value.equals(trigger[0]));
            chip.setId(View.generateViewId());
            chip.setChipMinHeight(getResources().getDimension(R.dimen.touch_target));
            chip.setOnClickListener(v -> trigger[0] = value);
            d.chipGroupTrigger.addView(chip);
        }

        // "None" first and pre-selected: most entries are logged at the moment of
        // an urge, when naming an alternative has not happened yet.
        Chip none = new Chip(this);
        none.setText(R.string.label_none);
        none.setCheckable(true);
        none.setChecked(true);
        none.setId(View.generateViewId());
        none.setChipMinHeight(getResources().getDimension(R.dimen.touch_target));
        none.setOnClickListener(v -> alternative[0] = "");
        d.chipGroupAlternative.addView(none);

        for (int res : ALTERNATIVE_RES) {
            final String label = getString(res);
            Chip chip = new Chip(this);
            chip.setText(label);
            chip.setCheckable(true);
            chip.setId(View.generateViewId());
            chip.setChipMinHeight(getResources().getDimension(R.dimen.touch_target));
            chip.setOnClickListener(v -> alternative[0] = label);
            d.chipGroupAlternative.addView(chip);
        }

        d.sliderUrge.addOnChangeListener((slider, value, fromUser) ->
                d.tvUrgeLabel.setText(getString(R.string.control_urge_level_short, (int) value)));
        d.tvUrgeLabel.setText(getString(R.string.control_urge_level_short, 3));

        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.control_add_entry)
                .setView(d.getRoot())
                .setNegativeButton(R.string.action_cancel, null)
                .setPositiveButton(R.string.action_save, (dialog, which) -> {
                    int minutes = parseMinutes(d.etMinutesLost.getText());
                    String task = d.etIntendedTask.getText() == null
                            ? "" : d.etIntendedTask.getText().toString().trim();
                    saveEntry(trigger[0], task, (int) d.sliderUrge.getValue(), minutes,
                            d.switchResisted.isChecked(), alternative[0]);
                })
                .show();
    }

    private static int parseMinutes(@Nullable Editable text) {
        if (text == null) return 0;
        try {
            int v = Integer.parseInt(text.toString().trim());
            return v < 0 ? 0 : Math.min(v, 960);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private void saveEntry(@NonNull String trigger, @NonNull String intendedTask,
                           int urgeLevel, int minutesLost, boolean resisted,
                           @NonNull String alternative) {
        Repository repo = repository;
        if (repo == null) return;

        AppDatabase.io().execute(() -> {
            try {
                DistractionLogEntity entry = new DistractionLogEntity();
                entry.dayKey = TimeUtils.today();
                entry.loggedAt = System.currentTimeMillis();
                entry.trigger = trigger;
                entry.intendedTask = intendedTask;
                entry.urgeLevel = urgeLevel;
                entry.minutesLost = minutesLost;
                entry.resisted = resisted;
                entry.alternativeUsed = alternative;
                entry.source = "self";
                repo.db().controlDao().insertDistraction(entry);
            } catch (Exception e) {
                android.util.Log.e(TAG, "could not save the entry", e);
            }
            runOnUiThread(() -> {
                showMessage(getString(resisted
                        ? R.string.control_urge_resisted : R.string.control_urge_logged));
                load();
            });
        });
    }

    private void confirmDeleteEntry(@NonNull DistractionLogEntity entry) {
        Repository repo = repository;
        if (repo == null) return;

        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.action_delete)
                .setMessage(R.string.control_delete_confirm)
                .setNegativeButton(R.string.action_cancel, null)
                .setPositiveButton(R.string.action_delete, (dialog, which) ->
                        AppDatabase.io().execute(() -> {
                            try {
                                repo.db().controlDao().deleteDistraction(entry);
                            } catch (Exception e) {
                                android.util.Log.e(TAG, "could not delete the entry", e);
                            }
                            runOnUiThread(() -> {
                                showMessage(getString(R.string.control_entry_deleted));
                                load();
                            });
                        }))
                .show();
    }

    private void showReasonEditor(@Nullable ReasonEntity existing) {
        android.widget.EditText input = new android.widget.EditText(this);
        input.setHint(R.string.control_reason_hint);
        input.setMinLines(2);
        input.setMaxLines(5);
        if (existing != null) input.setText(existing.text);

        int pad = getResources().getDimensionPixelSize(R.dimen.space_xxl);
        FrameLayout container = new FrameLayout(this);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(pad, pad, pad, 0);
        input.setLayoutParams(lp);
        container.addView(input);

        new MaterialAlertDialogBuilder(this)
                .setTitle(existing == null ? R.string.control_add_reason : R.string.action_edit)
                .setView(container)
                .setNegativeButton(R.string.action_cancel, null)
                .setPositiveButton(R.string.action_save, (dialog, which) -> {
                    String text = input.getText() == null ? "" : input.getText().toString().trim();
                    if (TextUtils.isEmpty(text)) return;
                    saveReason(existing, text);
                })
                .show();
    }

    private void saveReason(@Nullable ReasonEntity existing, @NonNull String text) {
        Repository repo = repository;
        if (repo == null) return;

        AppDatabase.io().execute(() -> {
            try {
                if (existing == null) {
                    ReasonEntity reason = new ReasonEntity(text, repo.db().controlDao().reasonCount());
                    reason.createdAt = System.currentTimeMillis();
                    repo.db().controlDao().insertReason(reason);
                } else {
                    existing.text = text;
                    repo.db().controlDao().updateReason(existing);
                }
            } catch (Exception e) {
                android.util.Log.e(TAG, "could not save the reason", e);
            }
            // The block screen reads a fresh reason each time, but the cached
            // snapshot may hold the old list.
            com.focuslife.app.blocking.BlockState.get().refreshAsync(this);
            runOnUiThread(() -> {
                showMessage(getString(R.string.control_reason_saved));
                load();
            });
        });
    }

    private void confirmDeleteReason(@NonNull ReasonEntity reason) {
        Repository repo = repository;
        if (repo == null) return;

        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.action_delete)
                .setMessage(reason.text)
                .setNegativeButton(R.string.action_cancel, null)
                .setPositiveButton(R.string.action_delete, (dialog, which) ->
                        AppDatabase.io().execute(() -> {
                            try {
                                repo.db().controlDao().deleteReason(reason);
                            } catch (Exception e) {
                                android.util.Log.e(TAG, "could not delete the reason", e);
                            }
                            runOnUiThread(() -> {
                                showMessage(getString(R.string.control_reason_deleted));
                                load();
                            });
                        }))
                .show();
    }

    private void showMessage(@NonNull String message) {
        ActivityControlBinding b = binding;
        if (b == null) return;
        Snackbar.make(b.getRoot(), message, Snackbar.LENGTH_LONG).show();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (binding != null) binding.rvDiary.setAdapter(null);
        adapter = null;
        binding = null;
    }
}
