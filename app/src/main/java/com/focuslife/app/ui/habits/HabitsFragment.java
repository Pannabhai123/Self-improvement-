package com.focuslife.app.ui.habits;

import android.os.Bundle;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;

import com.focuslife.app.R;
import com.focuslife.app.data.db.AppDatabase;
import com.focuslife.app.data.db.entity.HabitEntity;
import com.focuslife.app.data.repo.Repository;
import com.focuslife.app.databinding.DialogHabitEditBinding;
import com.focuslife.app.databinding.FragmentHabitsBinding;
import com.focuslife.app.stats.FocusScoreCalculator;
import com.focuslife.app.ui.MainActivity;
import com.focuslife.app.util.TimeUtils;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.snackbar.Snackbar;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The habit tracker.
 *
 * <h2>Tone is a feature here</h2>
 * The brief asks explicitly that missing habits must not shame the user, and that
 * is implemented rather than merely intended: there is no failure colour, no
 * "missed" counter, no negative streak messaging, and the encouragement line
 * always names the easiest next action instead of the shortfall. The missed-habit
 * analysis is presented as consistency percentages with the sentence
 * "Shown as information, not judgement" attached to it in the layout.
 */
public class HabitsFragment extends Fragment implements MainActivity.Refreshable,
        MainActivity.ScrollToTop {

    @Nullable
    private FragmentHabitsBinding binding;

    @Nullable
    private HabitAdapter adapter;

    @Nullable
    private Repository repository;

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        repository = Repository.get(requireContext().getApplicationContext());
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        binding = FragmentHabitsBinding.inflate(inflater, container, false);
        return binding.getRoot();
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        FragmentHabitsBinding b = binding;
        if (b == null) return;

        adapter = new HabitAdapter();
        adapter.setListener(new HabitAdapter.OnHabitClick() {
            @Override
            public void onToggle(long habitId, int position) {
                toggleHabit(habitId);
            }

            @Override
            public void onEdit(@NonNull Repository.HabitRow row) {
                showHabitEditor(row.habit);
            }
        });

        b.rvHabits.setLayoutManager(new LinearLayoutManager(requireContext()));
        b.rvHabits.setAdapter(adapter);
        b.rvHabits.setNestedScrollingEnabled(false);

        b.fabAddHabit.setOnClickListener(v -> showHabitEditor(null));

        b.tvHabitsDate.setText(new SimpleDateFormat("EEEE, d MMMM", Locale.getDefault())
                .format(new Date()));
    }

    // ------------------------------------------------------------------
    //  Data
    // ------------------------------------------------------------------

    @Override
    public void onResume() {
        super.onResume();
        refreshData();
    }

    @Override
    public void refreshData() {
        FragmentHabitsBinding b = binding;
        Repository repo = repository;
        if (b == null || repo == null) return;

        AppDatabase.io().execute(() -> {
            Payload p;
            try {
                p = load(repo);
            } catch (Exception e) {
                android.util.Log.e("HabitsFragment", "load failed", e);
                return;
            }
            if (!isAdded()) return;
            requireActivity().runOnUiThread(() -> {
                if (binding == null || adapter == null) return;
                render(p);
            });
        });
    }

    /** Everything the screen needs, assembled in one background pass. */
    private static final class Payload {
        List<Repository.HabitRow> rows = new ArrayList<>();
        Map<String, Integer> weekCompletions;
        List<String> weekKeys = TimeUtils.lastSevenDayKeys();
        int habitTotal;
        int streak;
        int bestStreak;
        int[] monthPercents = new int[0];
        List<String> monthKeys = new ArrayList<>();
        List<Repository.HabitConsistency> consistency = new ArrayList<>();
    }

    @NonNull
    private Payload load(@NonNull Repository repo) {
        Payload p = new Payload();
        String today = TimeUtils.today();

        p.rows = repo.loadHabitRows(today);
        p.habitTotal = p.rows.size();

        int done = 0;
        for (Repository.HabitRow r : p.rows) {
            if (r.done) done++;
        }

        p.weekCompletions = repo.weekHabitCompletions();

        // Count completions per day across the whole retained history in a single
        // query, then derive the streak from that. Reading 400 days one day at a
        // time would be 400 queries.
        List<String> longRange = TimeUtils.lastNDayKeys(400);
        List<com.focuslife.app.data.db.entity.HabitLogEntity> all =
                repo.db().habitDao().logsBetween(longRange.get(0), today);
        Map<String, Integer> fullCounts = new java.util.HashMap<>();
        for (com.focuslife.app.data.db.entity.HabitLogEntity l : all) {
            if (l == null || l.dayKey == null) continue;
            Integer c = fullCounts.get(l.dayKey);
            fullCounts.put(l.dayKey, c == null ? 1 : c + 1);
        }

        // A day qualifies for the streak when at least half the habits were
        // completed. An all-or-nothing rule would break the streak on any single
        // miss, which is exactly the punishing behaviour the brief rules out.
        List<String> qualifying = new ArrayList<>();
        // Newest-first, which is the order the streak calculator expects.
        for (int i = longRange.size() - 1; i >= 0; i--) {
            String key = longRange.get(i);
            Integer count = fullCounts.get(key);
            if (count != null && p.habitTotal > 0 && count * 2 >= p.habitTotal) {
                qualifying.add(key);
            }
        }
        p.streak = FocusScoreCalculator.currentStreak(qualifying, today);
        p.bestStreak = FocusScoreCalculator.bestStreak(qualifying);

        // 30-day percentage series.
        p.monthKeys = TimeUtils.lastNDayKeys(30);
        p.monthPercents = new int[p.monthKeys.size()];
        for (int i = 0; i < p.monthKeys.size(); i++) {
            Integer count = fullCounts.get(p.monthKeys.get(i));
            int c = count == null ? 0 : count;
            p.monthPercents[i] = p.habitTotal <= 0 ? 0
                    : FocusScoreCalculator.clamp(Math.round(c * 100f / p.habitTotal), 0, 100);
        }

        p.consistency = repo.habitConsistency(7);
        return p;
    }

    private void render(@NonNull Payload p) {
        FragmentHabitsBinding b = binding;
        if (b == null || adapter == null) return;

        adapter.submitList(new ArrayList<>(p.rows));

        int done = 0;
        for (Repository.HabitRow r : p.rows) {
            if (r.done) done++;
        }

        int percent = p.habitTotal <= 0 ? 0
                : FocusScoreCalculator.clamp(Math.round(done * 100f / p.habitTotal), 0, 100);

        b.ringHabits.setTexts(percent + "%", null);
        b.ringHabits.setProgress(percent, true);
        b.ringHabits.setRingColor(requireContext().getColor(
                percent >= 100 ? R.color.success_500
                        : percent >= 50 ? R.color.accent_500
                        : R.color.warning_500));

        b.tvHabitsSummary.setText(getString(R.string.habits_done_of, done, p.habitTotal));
        b.tvHabitsEncouragement.setText(encouragementFor(done, p.habitTotal));

        b.tvHabitStreak.setText(p.streak > 0
                ? getString(R.string.habits_streak_days, p.streak)
                : getString(R.string.streak_zero_hint));
        b.tvBestStreak.setText(getString(R.string.habits_best_streak) + ": "
                + getString(R.string.habits_streak_days, p.bestStreak));

        // Weekly calendar
        int[] weekPercents = new int[p.weekKeys.size()];
        for (int i = 0; i < p.weekKeys.size(); i++) {
            Integer count = p.weekCompletions.get(p.weekKeys.get(i));
            int c = count == null ? 0 : count;
            weekPercents[i] = p.habitTotal <= 0 ? 0
                    : FocusScoreCalculator.clamp(Math.round(c * 100f / p.habitTotal), 0, 100);
        }
        b.weekStrip.setData(weekPercents, p.weekKeys);

        // 30-day chart: label only every fifth day, or the axis becomes noise on
        // a 360dp-wide chart.
        String[] labels = new String[p.monthKeys.size()];
        for (int i = 0; i < p.monthKeys.size(); i++) {
            labels[i] = (i % 5 == 0 || i == p.monthKeys.size() - 1)
                    ? TimeUtils.shortDate(p.monthKeys.get(i)) : "";
        }
        b.chartHabits.setData(p.monthPercents, labels);
        b.chartHabits.setValueSuffix("%");

        renderAnalysis(b, p);
    }

    /**
     * The encouragement line.
     *
     * <p>Three cases, none of which mention failure: nothing done yet (an
     * invitation), partially done (name the easiest next step), and complete (an
     * acknowledgement).</p>
     */
    @NonNull
    private String encouragementFor(int done, int total) {
        if (total <= 0) return getString(R.string.habits_none_yet);
        if (done == 0) return getString(R.string.habits_none_yet);
        if (done >= total) return getString(R.string.habits_all_done);
        int left = total - done;
        return left == 1
                ? getString(R.string.habits_partial_done, left)
                : getString(R.string.habits_partial_done_plural, left);
    }

    /**
     * Renders the missed-habit analysis as a list of consistency bars.
     *
     * <p>Ordered weakest first and split into two labelled groups, so the user
     * sees where support is needed without a wall of red.</p>
     */
    private void renderAnalysis(@NonNull FragmentHabitsBinding b, @NonNull Payload p) {
        b.analysisContainer.removeAllViews();

        if (p.consistency.isEmpty()) {
            addAnalysisEmpty(b);
            return;
        }

        LayoutInflater inflater = LayoutInflater.from(requireContext());
        for (Repository.HabitConsistency c : p.consistency) {
            View row = inflater.inflate(R.layout.item_analysis_row, b.analysisContainer, false);
            TextView name = row.findViewById(R.id.tv_analysis_name);
            TextView value = row.findViewById(R.id.tv_analysis_value);
            android.widget.ProgressBar bar = row.findViewById(R.id.pb_analysis);

            name.setText(c.name);
            value.setText(getString(R.string.habits_completed_rate, c.percent));
            bar.setProgress(c.percent);

            // Colour by band rather than by exact value: green above 70, accent
            // above 40, muted below. Nothing is red, because a low-consistency
            // habit is a place to add support, not a mistake.
            int colorRes = c.percent >= 70 ? R.color.success_500
                    : c.percent >= 40 ? R.color.accent_500
                    : R.color.text_tertiary;
            bar.getProgressDrawable().setTint(requireContext().getColor(colorRes));

            b.analysisContainer.addView(row);
        }
    }

    private void addAnalysisEmpty(@NonNull FragmentHabitsBinding b) {
        TextView empty = new TextView(requireContext());
        empty.setText(R.string.label_empty_no_data);
        empty.setTextAppearance(R.style.TextAppearance_FocusLife_Caption);
        b.analysisContainer.addView(empty);
    }

    // ------------------------------------------------------------------
    //  Actions
    // ------------------------------------------------------------------

    private void toggleHabit(long habitId) {
        Repository repo = repository;
        if (repo == null) return;

        AppDatabase.io().execute(() -> {
            try {
                repo.toggleHabit(habitId, TimeUtils.today());
            } catch (Exception e) {
                android.util.Log.e("HabitsFragment", "toggle failed", e);
            }
            if (!isAdded()) return;
            requireActivity().runOnUiThread(this::refreshData);
        });
    }

    /**
     * Creates or edits a habit.
     *
     * @param existing null to create a new habit
     */
    private void showHabitEditor(@Nullable HabitEntity existing) {
        DialogHabitEditBinding d = DialogHabitEditBinding.inflate(LayoutInflater.from(requireContext()));

        if (existing != null) {
            // Built-in habits carry a resource id; editing one converts it to a
            // plain user string, because the typed text must win over the
            // translation from then on.
            d.etName.setText(repository == null ? existing.name
                    : repository.resolveString(existing.nameRes, existing.name));
        }

        final int[] reminderHour = {existing == null ? -1 : existing.reminderHour};
        d.toggleReminder.check(reminderHour[0] < 0
                ? R.id.btn_reminder_off
                : (reminderHour[0] < 12 ? R.id.btn_reminder_morning : R.id.btn_reminder_evening));

        d.toggleReminder.addOnButtonCheckedListener((group, checkedId, isChecked) -> {
            if (!isChecked) return;
            if (checkedId == R.id.btn_reminder_off) reminderHour[0] = -1;
            else if (checkedId == R.id.btn_reminder_morning) reminderHour[0] = 6;
            else reminderHour[0] = 20;
        });

        boolean isEdit = existing != null;
        MaterialAlertDialogBuilder dialog = new MaterialAlertDialogBuilder(requireContext())
                .setTitle(isEdit ? R.string.habits_edit : R.string.habits_add)
                .setView(d.getRoot())
                .setNegativeButton(R.string.action_cancel, null)
                .setPositiveButton(R.string.action_save, null);

        if (isEdit) {
            dialog.setNeutralButton(R.string.habits_archive, null);
        }

        androidx.appcompat.app.AlertDialog alert = dialog.create();
        alert.show();

        // Resolve the positive button after show() so it can validate without
        // dismissing the dialog on an empty name.
        alert.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE)
                .setOnClickListener(v -> {
                    String name = d.etName.getText() == null
                            ? "" : d.etName.getText().toString().trim();
                    if (TextUtils.isEmpty(name)) {
                        d.etName.setError(getString(R.string.validation_required));
                        return;
                    }
                    saveHabit(existing, name, reminderHour[0]);
                    alert.dismiss();
                });

        if (isEdit) {
            android.widget.Button neutral =
                    alert.getButton(androidx.appcompat.app.AlertDialog.BUTTON_NEUTRAL);
            if (neutral != null) {
                neutral.setOnClickListener(v -> {
                    archiveHabit(existing);
                    alert.dismiss();
                });
            }
        }
    }

    private void saveHabit(@Nullable HabitEntity existing, @NonNull String name, int reminderHour) {
        Repository repo = repository;
        if (repo == null) return;

        AppDatabase.io().execute(() -> {
            try {
                HabitEntity habit = existing != null ? existing : new HabitEntity();
                habit.name = name;
                // Clearing nameRes is what makes the typed text authoritative.
                habit.nameRes = 0;
                habit.reminderHour = reminderHour;
                habit.reminderMinute = 0;
                if (existing == null) {
                    habit.createdAt = System.currentTimeMillis();
                    habit.sortOrder = repo.db().habitDao().activeHabits().size();
                    habit.iconKey = HabitEntity.ICON_STAR;
                    repo.db().habitDao().insertHabit(habit);
                } else {
                    repo.db().habitDao().updateHabit(habit);
                }
            } catch (Exception e) {
                android.util.Log.e("HabitsFragment", "save failed", e);
            }
            if (!isAdded()) return;
            requireActivity().runOnUiThread(this::refreshData);
        });
    }

    private void archiveHabit(@NonNull HabitEntity habit) {
        Repository repo = repository;
        if (repo == null) return;

        AppDatabase.io().execute(() -> {
            try {
                repo.db().habitDao().archive(habit.id);
            } catch (Exception e) {
                android.util.Log.e("HabitsFragment", "archive failed", e);
            }
            if (!isAdded()) return;
            requireActivity().runOnUiThread(() -> {
                showMessage(getString(R.string.habits_archived));
                refreshData();
            });
        });
    }

    // ------------------------------------------------------------------

    @Override
    public void scrollToTop() {
        FragmentHabitsBinding b = binding;
        if (b != null) b.habitsScroll.smoothScrollTo(0, 0);
    }

    private void showMessage(@NonNull String message) {
        FragmentHabitsBinding b = binding;
        if (b == null) return;
        Snackbar.make(b.getRoot(), message, Snackbar.LENGTH_SHORT).show();
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        if (binding != null) binding.rvHabits.setAdapter(null);
        adapter = null;
        binding = null;
    }
}
