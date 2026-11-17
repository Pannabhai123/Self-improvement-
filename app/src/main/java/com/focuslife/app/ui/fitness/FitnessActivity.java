package com.focuslife.app.ui.fitness;

import android.os.Bundle;
import android.text.Editable;
import android.view.LayoutInflater;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;

import com.focuslife.app.R;
import com.focuslife.app.data.db.AppDatabase;
import com.focuslife.app.data.db.entity.RunLogEntity;
import com.focuslife.app.data.db.entity.WorkoutEntity;
import com.focuslife.app.data.db.entity.WorkoutLogEntity;
import com.focuslife.app.data.prefs.Prefs;
import com.focuslife.app.data.repo.Repository;
import com.focuslife.app.databinding.ActivityFitnessBinding;
import com.focuslife.app.databinding.DialogLogRunBinding;
import com.focuslife.app.databinding.ItemRunLogBinding;
import com.focuslife.app.stats.FocusScoreCalculator;
import com.focuslife.app.util.TimeUtils;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.snackbar.Snackbar;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Running and fitness — brief section 5.
 *
 * <h2>Rest days</h2>
 * Marking a rest day fills the fitness component of the Focus Score rather than
 * zeroing it. That is implemented in {@link FocusScoreCalculator}, not here, and
 * it is the difference between a rest day being a legitimate part of training and
 * a rest day being a hole in the record.
 *
 * <h2>The 1600 m goal</h2>
 * Optional and additive. A run without a timed 1600 counts in full, and a timed
 * 1600 is recorded separately so a personal best can be tracked without making
 * the stopwatch a condition of logging anything.
 */
public class FitnessActivity extends AppCompatActivity {

    private static final String TAG = "Fitness";

    private static final SimpleDateFormat DAY_FORMAT =
            new SimpleDateFormat("EEE d MMM", Locale.getDefault());

    @Nullable
    private ActivityFitnessBinding binding;

    @Nullable
    private WorkoutAdapter adapter;

    @Nullable
    private Repository repository;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivityFitnessBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        repository = Repository.get(getApplicationContext());

        ActivityFitnessBinding b = binding;
        if (b == null) return;

        b.toolbar.setNavigationOnClickListener(v -> finish());

        adapter = new WorkoutAdapter();
        adapter.setListener(new WorkoutAdapter.OnWorkoutAction() {
            @Override
            public void onQuickDone(@NonNull WorkoutAdapter.WorkoutRow row) {
                recordWorkout(row.workout, row.workout.targetReps, row.workout.targetSec);
            }

            @Override
            public void onCustomAmount(@NonNull WorkoutAdapter.WorkoutRow row) {
                promptWorkoutAmount(row.workout);
            }

            @Override
            public void onUndo(@NonNull WorkoutAdapter.WorkoutRow row) {
                undoWorkout(row.workout);
            }
        });

        b.rvWorkouts.setLayoutManager(new LinearLayoutManager(this));
        b.rvWorkouts.setAdapter(adapter);
        b.rvWorkouts.setNestedScrollingEnabled(false);

        b.btnLogRun.setOnClickListener(v -> showRunDialog());
        b.btnEditTarget.setOnClickListener(v -> showTargetDialog());

        b.switchRestDay.setChecked(Prefs.isRestDayToday(this));
        b.switchRestDay.setOnCheckedChangeListener((view, checked) -> setRestDay(checked));
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
        ActivityFitnessBinding b = binding;
        Repository repo = repository;
        if (b == null || repo == null) return;

        AppDatabase.io().execute(() -> {
            Payload p;
            try {
                p = collect(repo);
            } catch (Exception e) {
                android.util.Log.e(TAG, "could not load fitness data", e);
                return;
            }
            runOnUiThread(() -> {
                ActivityFitnessBinding current = binding;
                if (current == null || adapter == null) return;
                render(p);
            });
        });
    }

    private static final class Payload {
        int distanceToday;
        int secondsToday;
        int targetMetres;
        int workoutTarget;
        int workoutsDone;
        int best1600Sec;
        int longestRun;
        int weekDays;
        int weekDistance;
        int[] weekHundreds = new int[0];
        List<String> weekKeys = new ArrayList<>();
        List<WorkoutAdapter.WorkoutRow> workouts = new ArrayList<>();
        List<RunLogEntity> runs = new ArrayList<>();
        boolean restDay;
    }

    @NonNull
    private Payload collect(@NonNull Repository repo) {
        Payload p = new Payload();
        String today = TimeUtils.today();
        String[] week = TimeUtils.currentWeekRange();

        p.distanceToday = repo.db().fitnessDao().distanceOnDay(today);
        p.secondsToday = repo.db().fitnessDao().runSecondsOnDay(today);
        p.targetMetres = Prefs.targetRunMetres(this);
        p.workoutTarget = Prefs.targetWorkouts(this);
        p.workoutsDone = repo.db().fitnessDao().workoutsDoneOnDay(today);
        p.best1600Sec = repo.db().fitnessDao().best1600Sec();
        p.longestRun = repo.db().fitnessDao().longestRunMetres();
        p.weekDays = repo.db().fitnessDao().distinctRunDaysBetween(week[0], week[1]);
        p.weekDistance = repo.db().fitnessDao().distanceBetween(week[0], week[1]);
        p.restDay = Prefs.isRestDayToday(this);

        p.weekKeys = TimeUtils.lastSevenDayKeys();
        // The DAO returns hundreds of metres so the bar chart's integer axis stays
        // readable; a daily distance in metres would make every bar a four-digit
        // number and the chart unreadable at 160dp tall.
        List<com.focuslife.app.data.db.dao.FocusSessionDao.ChartPoint> points =
                repo.db().fitnessDao().hundredsOfMetresPerDay(week[0], week[1]);
        p.weekHundreds = new int[p.weekKeys.size()];
        if (points != null) {
            for (com.focuslife.app.data.db.dao.FocusSessionDao.ChartPoint point : points) {
                if (point == null || point.label == null) continue;
                int index = p.weekKeys.indexOf(point.label);
                if (index >= 0) p.weekHundreds[index] = point.value;
            }
        }

        // Workouts: definition plus today's log, joined in memory. The checklist
        // is at most a dozen rows, so a join query would cost more than it saves.
        List<WorkoutEntity> definitions = repo.db().fitnessDao().workouts();
        List<WorkoutLogEntity> logs = repo.db().fitnessDao().workoutLogsForDay(today);
        if (definitions != null) {
            for (WorkoutEntity workout : definitions) {
                if (workout == null || workout.archived) continue;
                WorkoutLogEntity match = null;
                if (logs != null) {
                    for (WorkoutLogEntity log : logs) {
                        if (log != null && log.workoutId == workout.id) {
                            match = log;
                            break;
                        }
                    }
                }
                int best = 0;
                try {
                    best = repo.db().fitnessDao().personalBestReps(workout.id);
                } catch (Exception ignored) {
                    // A personal best is decorative; a failure to read it must not
                    // cost the whole checklist.
                }
                p.workouts.add(WorkoutAdapter.row(workout, match, best));
            }
        }

        List<RunLogEntity> runs = repo.db().fitnessDao().runsBetween(week[0], today);
        if (runs != null) {
            p.runs = new ArrayList<>(runs);
            java.util.Collections.sort(p.runs, (x, y) -> Long.compare(y.loggedAt, x.loggedAt));
            if (p.runs.size() > 10) {
                p.runs = new ArrayList<>(p.runs.subList(0, 10));
            }
        }
        return p;
    }

    private void render(@NonNull Payload p) {
        ActivityFitnessBinding b = binding;
        if (b == null || adapter == null) return;

        b.tvDistanceToday.setText(TimeUtils.formatDistance(p.distanceToday));

        int percent = p.targetMetres <= 0 ? 0
                : FocusScoreCalculator.clamp(
                        Math.round(p.distanceToday * 100f / p.targetMetres), 0, 100);
        b.pbDistance.setProgress(percent);
        b.pbDistance.getProgressDrawable().setTint(getColor(
                percent >= 100 ? R.color.success_500 : R.color.accent_500));

        b.tvTargetDistance.setText(getString(R.string.fitness_target_summary,
                TimeUtils.formatDistance(p.targetMetres),
                TimeUtils.formatMinutes(p.secondsToday / 60)));

        b.tvDurationToday.setText(TimeUtils.formatMinutes(p.secondsToday / 60));

        // Pace is only meaningful with both a distance and a duration; showing a
        // divide-by-zero result as "0:00 /km" would be a lie about the run.
        if (p.distanceToday > 0 && p.secondsToday > 0) {
            int secPerKm = (int) (p.secondsToday * 1000.0 / p.distanceToday);
            b.tvPaceToday.setText(TimeUtils.formatPace(secPerKm));
        } else {
            b.tvPaceToday.setText(R.string.label_empty_no_data);
        }

        // Rest days state their effect on the score, so the switch is understood
        // as training rather than as skipping.
        if (p.restDay) {
            b.tvDistanceToday.setText(getString(R.string.fitness_rest_day_on));
            b.tvDistanceToday.setTextSize(
                    getResources().getDimension(R.dimen.text_subtitle)
                            / getResources().getDisplayMetrics().scaledDensity);
        } else {
            b.tvDistanceToday.setTextSize(
                    getResources().getDimension(R.dimen.text_display_small)
                            / getResources().getDisplayMetrics().scaledDensity);
        }

        b.tvBest1600.setText(p.best1600Sec > 0
                ? TimeUtils.formatSeconds(p.best1600Sec)
                : getString(R.string.fitness_best_1600_none));
        b.tvLongestRun.setText(p.longestRun > 0
                ? TimeUtils.formatDistance(p.longestRun)
                : getString(R.string.label_empty_no_data));

        b.chartWeek.setData(p.weekHundreds, weekLabels(p.weekKeys));
        b.tvWeekSummary.setText(getString(R.string.fitness_week_summary,
                p.weekDays, TimeUtils.formatDistance(p.weekDistance)));

        b.tvWorkoutCount.setText(getString(R.string.fitness_workouts_done,
                p.workoutsDone, Math.max(p.workouts.size(), p.workoutTarget)));

        adapter.submitList(new ArrayList<>(p.workouts));
        renderRuns(b, p.runs);

        b.btnEditTarget.setText(getString(R.string.fitness_target_title,
                TimeUtils.formatDistance(p.targetMetres), p.workoutTarget));
    }

    @NonNull
    private String[] weekLabels(@NonNull List<String> dayKeys) {
        String[] labels = new String[dayKeys.size()];
        for (int i = 0; i < dayKeys.size(); i++) {
            labels[i] = TimeUtils.weekdayInitial(dayKeys.get(i));
        }
        return labels;
    }

    private void renderRuns(@NonNull ActivityFitnessBinding b, @NonNull List<RunLogEntity> runs) {
        android.widget.LinearLayout runContainer = b.runContainer;
        runContainer.removeAllViews();
        b.tvNoRuns.setVisibility(runs.isEmpty() ? View.VISIBLE : View.GONE);
        runContainer.setVisibility(runs.isEmpty() ? View.GONE : View.VISIBLE);
        if (runs.isEmpty()) return;

        // Ten rows at most, so inflating into a LinearLayout is cheaper than a
        // RecyclerView nested inside a NestedScrollView that cannot scroll anyway.
        LayoutInflater inflater = LayoutInflater.from(this);
        for (RunLogEntity run : runs) {
            if (run == null) continue;
            ItemRunLogBinding runRow = ItemRunLogBinding.inflate(inflater, runContainer, false);

            runRow.tvRunSummary.setText(getString(R.string.fitness_run_summary,
                    TimeUtils.formatDistance(run.distanceM),
                    TimeUtils.formatMinutes(run.durationSec / 60)));

            String day = DAY_FORMAT.format(new Date(run.loggedAt));
            String pace = run.distanceM > 0 && run.durationSec > 0
                    ? TimeUtils.formatPace((int) (run.durationSec * 1000.0 / run.distanceM))
                    : getString(R.string.label_empty_no_data);

            runRow.tvRunMeta.setText(run.counted1600 && run.best1600Sec > 0
                    ? getString(R.string.fitness_run_meta_timed, day, pace,
                            TimeUtils.formatSeconds(run.best1600Sec))
                    : getString(R.string.fitness_run_meta, day, pace));

            runRow.btnRunDelete.setOnClickListener(v -> confirmDeleteRun(run));
            runContainer.addView(runRow.getRoot());
        }
    }

    // ------------------------------------------------------------------
    //  Actions
    // ------------------------------------------------------------------

    private void showRunDialog() {
        DialogLogRunBinding d = DialogLogRunBinding.inflate(LayoutInflater.from(this));

        // Pre-filling the target distance makes the common case — "I ran my
        // usual 1600" — a single confirmation instead of two typed numbers.
        d.etDistance.setText(String.valueOf(Prefs.targetRunMetres(this)));

        d.switchTimed1600.setOnCheckedChangeListener((view, checked) ->
                d.fieldTimed1600.setVisibility(checked ? View.VISIBLE : View.GONE));

        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.fitness_log_run)
                .setView(d.getRoot())
                .setNegativeButton(R.string.action_cancel, null)
                .setPositiveButton(R.string.action_save, (dialog, which) -> {
                    int distance = parseIntOr(d.etDistance.getText(), 0);
                    int minutes = parseIntOr(d.etMinutes.getText(), 0);
                    if (distance <= 0 || minutes <= 0) {
                        showMessage(getString(R.string.fitness_invalid_run));
                        return;
                    }

                    int timed1600Sec = 0;
                    if (d.switchTimed1600.isChecked()) {
                        int m = parseIntOr(d.et1600Minutes.getText(), 0);
                        int s = parseIntOr(d.et1600Seconds.getText(), 0);
                        timed1600Sec = m * 60 + s;
                        if (timed1600Sec < 60) {
                            showMessage(getString(R.string.fitness_invalid_1600));
                            return;
                        }
                    }

                    String note = d.etNote.getText() == null
                            ? "" : d.etNote.getText().toString().trim();
                    saveRun(distance, minutes * 60, timed1600Sec, note);
                })
                .show();
    }

    private void saveRun(int distanceM, int durationSec, int timed1600Sec, @NonNull String note) {
        Repository repo = repository;
        if (repo == null) return;

        final int target = Prefs.targetRunMetres(this);
        AppDatabase.io().execute(() -> {
            boolean personalBest = false;
            try {
                RunLogEntity run = new RunLogEntity();
                run.kind = RunLogEntity.KIND_RUN;
                run.distanceM = distanceM;
                run.durationSec = durationSec;
                run.dayKey = TimeUtils.today();
                run.loggedAt = System.currentTimeMillis();
                run.note = note;

                // A timed 1600 counts only when the run actually covered it, so a
                // 400 m warm-up cannot set a 1600 m record.
                boolean covers1600 = timed1600Sec > 0 && distanceM >= RunLogEntity.TARGET_1600_M;
                run.counted1600 = covers1600;
                if (covers1600) {
                    int previousBest = repo.db().fitnessDao().best1600Sec();
                    run.best1600Sec = timed1600Sec;
                    personalBest = previousBest <= 0 || timed1600Sec < previousBest;
                }
                repo.db().fitnessDao().insertRun(run);

                // Reaching the daily distance target should also count as a
                // workout, so the checklist reflects the run rather than
                // demanding a second entry for the same effort.
                if (distanceM >= target && target > 0) {
                    repo.db().fitnessDao().insertRun(workoutEntryForRun(run));
                }
            } catch (Exception e) {
                android.util.Log.e(TAG, "could not save the run", e);
            }

            final boolean isPersonalBest = personalBest;
            runOnUiThread(() -> {
                showMessage(getString(isPersonalBest
                        ? R.string.fitness_new_personal_best : R.string.fitness_run_saved));
                load();
            });
        });
    }

    /** A run that hit the daily target, recorded as a workout entry too. */
    @NonNull
    private RunLogEntity workoutEntryForRun(@NonNull RunLogEntity run) {
        RunLogEntity entry = new RunLogEntity();
        entry.kind = RunLogEntity.KIND_WORKOUT;
        entry.distanceM = 0;
        entry.durationSec = run.durationSec;
        entry.dayKey = run.dayKey;
        entry.loggedAt = run.loggedAt;
        entry.counted1600 = false;
        entry.note = getString(R.string.fitness_running);
        return entry;
    }

    private void confirmDeleteRun(@NonNull RunLogEntity run) {
        Repository repo = repository;
        if (repo == null) return;

        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.action_delete)
                .setMessage(R.string.fitness_delete_confirm)
                .setNegativeButton(R.string.action_cancel, null)
                .setPositiveButton(R.string.action_delete, (dialog, which) ->
                        AppDatabase.io().execute(() -> {
                            try {
                                repo.db().fitnessDao().deleteRun(run);
                            } catch (Exception e) {
                                android.util.Log.e(TAG, "could not delete the run", e);
                            }
                            runOnUiThread(() -> {
                                showMessage(getString(R.string.fitness_deleted));
                                load();
                            });
                        }))
                .show();
    }

    // ------------------------------------------------------------------
    //  Workouts
    // ------------------------------------------------------------------

    private void recordWorkout(@NonNull WorkoutEntity workout, int reps, int seconds) {
        Repository repo = repository;
        if (repo == null) return;

        String today = TimeUtils.today();
        AppDatabase.io().execute(() -> {
            try {
                // toggleWorkout inserts or deletes in one transaction, so the
                // checklist and its count cannot disagree.
                repo.db().fitnessDao().toggleWorkout(workout.id, today, reps,
                        System.currentTimeMillis());
                if (seconds > 0) {
                    WorkoutLogEntity log = repo.db().fitnessDao()
                            .findWorkoutLog(workout.id, today);
                    if (log != null) {
                        log.secDone = seconds;
                        repo.db().fitnessDao().updateWorkoutLog(log);
                    }
                }
            } catch (Exception e) {
                android.util.Log.e(TAG, "could not record the workout", e);
            }
            runOnUiThread(() -> {
                showMessage(getString(R.string.fitness_workout_done,
                        repo.resolveString(workout.nameRes, workout.name)));
                load();
            });
        });
    }

    private void undoWorkout(@NonNull WorkoutEntity workout) {
        Repository repo = repository;
        if (repo == null) return;

        AppDatabase.io().execute(() -> {
            try {
                repo.db().fitnessDao().deleteWorkoutLog(workout.id, TimeUtils.today());
            } catch (Exception e) {
                android.util.Log.e(TAG, "could not undo the workout", e);
            }
            runOnUiThread(this::load);
        });
    }

    /** Long-press path: ask for a number that differs from the target. */
    private void promptWorkoutAmount(@NonNull WorkoutEntity workout) {
        boolean timed = workout.targetSec > 0 && workout.targetReps <= 0;
        final android.widget.EditText input = new android.widget.EditText(this);
        input.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
        input.setHint(timed ? R.string.fitness_workout_sec_prompt : R.string.fitness_workout_reps_prompt);
        if (timed && workout.targetSec > 0) input.setText(String.valueOf(workout.targetSec));
        else if (!timed && workout.targetReps > 0) input.setText(String.valueOf(workout.targetReps));

        int pad = getResources().getDimensionPixelSize(R.dimen.space_xxl);
        android.widget.FrameLayout container = new android.widget.FrameLayout(this);
        android.widget.FrameLayout.LayoutParams lp = new android.widget.FrameLayout.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(pad, pad, pad, 0);
        input.setLayoutParams(lp);
        container.addView(input);

        new MaterialAlertDialogBuilder(this)
                .setTitle(repository == null ? workout.name
                        : repository.resolveString(workout.nameRes, workout.name))
                .setView(container)
                .setNegativeButton(R.string.action_cancel, null)
                .setPositiveButton(R.string.action_save, (dialog, which) -> {
                    int amount = parseIntOr(input.getText(), 0);
                    if (amount <= 0) return;
                    recordWorkout(workout, timed ? 0 : amount, timed ? amount : 0);
                })
                .show();
    }

    // ------------------------------------------------------------------
    //  Targets and rest days
    // ------------------------------------------------------------------

    private void showTargetDialog() {
        final String[] distances = {"800", "1200", "1600", "2400", "3200", "5000"};
        String[] labels = new String[distances.length];
        for (int i = 0; i < distances.length; i++) {
            labels[i] = TimeUtils.formatDistance(Integer.parseInt(distances[i]));
        }
        int current = Prefs.targetRunMetres(this);
        int selected = indexOf(distances, String.valueOf(current));

        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.fitness_daily_target)
                .setSingleChoiceItems(labels, selected >= 0 ? selected : 2,
                        (dialog, which) -> {
                            Prefs.setTargetRunMetres(this, Integer.parseInt(distances[which]));
                            dialog.dismiss();
                            load();
                        })
                .setNegativeButton(R.string.action_cancel, null)
                .show();
    }

    private void setRestDay(boolean restDay) {
        Prefs.setRestDayToday(this, restDay);
        // The dashboard's fitness component reads this, so it has to refresh too.
        Repository repo = repository;
        if (repo != null) repo.scheduleSummaryRefresh(TimeUtils.today());
        load();
    }

    private static int indexOf(@NonNull String[] values, @NonNull String target) {
        for (int i = 0; i < values.length; i++) {
            if (values[i].equals(target)) return i;
        }
        return -1;
    }

    private static int parseIntOr(@Nullable Editable text, int fallback) {
        if (text == null) return fallback;
        try {
            int v = Integer.parseInt(text.toString().trim());
            return v < 0 ? 0 : v;
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private void showMessage(@NonNull String message) {
        ActivityFitnessBinding b = binding;
        if (b == null) return;
        Snackbar.make(b.getRoot(), message, Snackbar.LENGTH_LONG).show();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (binding != null) binding.rvWorkouts.setAdapter(null);
        adapter = null;
        binding = null;
    }
}
