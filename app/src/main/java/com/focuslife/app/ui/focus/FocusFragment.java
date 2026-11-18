package com.focuslife.app.ui.focus;

import android.content.Intent;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;

import com.focuslife.app.R;
import com.focuslife.app.blocking.ModeController;
import com.focuslife.app.data.db.AppDatabase;
import com.focuslife.app.data.db.entity.FocusSessionEntity;
import com.focuslife.app.data.prefs.Prefs;
import com.focuslife.app.data.repo.Repository;
import com.focuslife.app.databinding.FragmentFocusBinding;
import com.focuslife.app.databinding.ItemFocusPresetBinding;
import com.focuslife.app.ui.MainActivity;
import com.focuslife.app.ui.onboarding.OnboardingActivity;
import com.focuslife.app.util.TimeUtils;
import com.google.android.material.snackbar.Snackbar;

import java.util.ArrayList;
import java.util.List;

/**
 * The Focus tab: preset selection, custom timer configuration and the session
 * history.
 *
 * <p>This screen configures; {@link FocusTimerActivity} runs. Keeping them
 * separate means the running timer gets a genuinely full-screen, empty
 * environment, which is the whole point of the feature.</p>
 */
public class FocusFragment extends Fragment implements MainActivity.Refreshable,
        MainActivity.ScrollToTop {

    @Nullable
    private FragmentFocusBinding binding;

    @Nullable
    private FocusSessionAdapter adapter;

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
        binding = FragmentFocusBinding.inflate(inflater, container, false);
        return binding.getRoot();
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        FragmentFocusBinding b = binding;
        if (b == null) return;

        setupHistoryList(b);
        setupPresets(b);
        setupCustom(b);
        restoreLastTask(b);
    }

    // ------------------------------------------------------------------
    //  Presets
    // ------------------------------------------------------------------

    private void setupPresets(@NonNull FragmentFocusBinding b) {
        // 25/5 — the classic Pomodoro, and the brief's default.
        bindPreset(b.preset255, "25", getString(R.string.focus_break_5),
                () -> launch(25, 5, Prefs.defaultRounds(requireContext())));

        // 50/10 — for longer syllabus blocks where a 25-minute cycle breaks flow.
        bindPreset(b.preset5010, "50", getString(R.string.focus_break_10),
                () -> launch(50, 10, Math.max(2, Prefs.defaultRounds(requireContext()) / 2)));

        // Standalone breaks: useful after a long unprompted stretch of work, and
        // they make the break duration a first-class choice rather than an
        // afterthought of the focus length.
        bindPreset(b.presetBreak5, "5", getString(R.string.focus_break_5),
                () -> launchBreak(5));
        bindPreset(b.presetBreak10, "10", getString(R.string.focus_break_10),
                () -> launchBreak(10));
    }

    private void bindPreset(@NonNull ItemFocusPresetBinding tile, @NonNull String value,
                            @NonNull String label, @NonNull Runnable action) {
        tile.tvPresetValue.setText(value);
        tile.tvPresetLabel.setText(label);
        tile.getRoot().setOnClickListener(v -> action.run());
    }

    /** A break-only session: one round, no focus phase. */
    private void launchBreak(int breakMinutes) {
        startActivity(FocusTimerActivity.intent(requireContext(),
                breakMinutes, 0, 1, getString(R.string.focus_break_title), false));
    }

    // ------------------------------------------------------------------
    //  Custom configuration
    // ------------------------------------------------------------------

    private void setupCustom(@NonNull FragmentFocusBinding b) {
        b.etFocusMin.setText(String.valueOf(Prefs.defaultFocusMinutes(requireContext())));
        b.etBreakMin.setText(String.valueOf(Prefs.defaultBreakMinutes(requireContext())));
        b.sliderRounds.setValue(Prefs.defaultRounds(requireContext()));
        b.switchDeep.setChecked(Prefs.deepFocusByDefault(requireContext()));

        updateCustomLabel(b);
        updateDeepStatus(b);

        TextWatcher watcher = new SimpleTextWatcher(() -> updateCustomLabel(b));
        b.etFocusMin.addTextChangedListener(watcher);
        b.etBreakMin.addTextChangedListener(watcher);

        b.sliderRounds.addOnChangeListener((slider, value, fromUser) -> updateCustomLabel(b));

        b.switchDeep.setOnCheckedChangeListener((view, checked) -> {
            Prefs.setDeepFocusByDefault(requireContext(), checked);
            updateDeepStatus(b);
            // Deep Focus is only meaningful if the guard can actually intercept,
            // so point at the setup screen when it cannot.
            if (checked && ModeController.enforcementLevel(requireContext())
                    == ModeController.ENFORCEMENT_NONE) {
                startActivity(new Intent(requireContext(), OnboardingActivity.class));
            }
        });

        b.btnStartCustom.setOnClickListener(v -> {
            int focus = parseMinutes(b.etFocusMin.getText(), Prefs.defaultFocusMinutes(requireContext()));
            int brk = parseMinutes(b.etBreakMin.getText(), Prefs.defaultBreakMinutes(requireContext()));
            int rounds = (int) b.sliderRounds.getValue();

            if (focus < 1 || focus > 240) {
                showMessage(getString(R.string.focus_invalid_duration));
                return;
            }

            // Persist the chosen configuration so the next visit starts from it.
            Prefs.setDefaultFocusMinutes(requireContext(), focus);
            Prefs.setDefaultBreakMinutes(requireContext(), brk);
            Prefs.setDefaultRounds(requireContext(), rounds);
            rememberTask(b);

            launch(focus, brk, rounds);
        });
    }

    private void updateCustomLabel(@NonNull FragmentFocusBinding b) {
        int focus = parseMinutes(b.etFocusMin.getText(), Prefs.defaultFocusMinutes(requireContext()));
        int brk = parseMinutes(b.etBreakMin.getText(), Prefs.defaultBreakMinutes(requireContext()));
        int rounds = (int) b.sliderRounds.getValue();
        b.tvCustomLabel.setText(buildCustomSummary(focus, brk, rounds));
    }

    /**
     * Reads the configuration back to the user in one line, e.g.
     * "25m focus · 5m break · 4 rounds".
     */
    @NonNull
    private String buildCustomSummary(int focusMin, int breakMin, int rounds) {
        String focusPart = TimeUtils.formatMinutes(focusMin) + " "
                + getString(R.string.focus_summary_focus_word);
        String breakPart = breakMin > 0
                ? TimeUtils.formatMinutes(breakMin) + " " + getString(R.string.focus_summary_break_word)
                : getString(R.string.focus_summary_no_break);
        return getString(R.string.focus_custom_summary, focusPart, breakPart, rounds);
    }

    private void updateDeepStatus(@NonNull FragmentFocusBinding b) {
        boolean on = b.switchDeep.isChecked();
        int enforcement = ModeController.enforcementLevel(requireContext());

        if (!on) {
            b.tvDeepStatus.setText(R.string.focus_deep_disabled);
            return;
        }
        if (enforcement == ModeController.ENFORCEMENT_FULL) {
            b.tvDeepStatus.setText(R.string.focus_deep_enabled);
            b.tvDeepStatus.setTextColor(requireContext().getColor(R.color.success_500));
            return;
        }
        b.tvDeepStatus.setText(enforcement == ModeController.ENFORCEMENT_NOTIFICATION
                ? R.string.perm_denied_overlay_desc
                : R.string.perm_denied_accessibility_desc);
        b.tvDeepStatus.setTextColor(requireContext().getColor(R.color.warning_500));
    }

    private void launch(int focusMin, int breakMin, int rounds) {
        rememberTask(binding == null ? null : binding);
        String task = binding == null ? "" : currentTask(binding);
        boolean deep = binding != null && binding.switchDeep.isChecked();
        startActivity(FocusTimerActivity.intent(requireContext(), focusMin, breakMin, rounds, task, deep));
    }

    @NonNull
    private String currentTask(@NonNull FragmentFocusBinding b) {
        Editable text = b.etTask.getText();
        return text == null ? "" : text.toString().trim();
    }

    private void rememberTask(@Nullable FragmentFocusBinding b) {
        if (b == null) return;
        String task = currentTask(b);
        if (!task.isEmpty()) {
            requireContext().getSharedPreferences("focuslife_state", android.content.Context.MODE_PRIVATE)
                    .edit().putString("last_focus_task", task).apply();
        }
    }

    private void restoreLastTask(@NonNull FragmentFocusBinding b) {
        String last = requireContext()
                .getSharedPreferences("focuslife_state", android.content.Context.MODE_PRIVATE)
                .getString("last_focus_task", "");
        if (!TextUtils.isEmpty(last)) {
            b.etTask.setText(last);
        }
    }

    private int parseMinutes(@Nullable Editable text, int fallback) {
        if (text == null) return fallback;
        try {
            int v = Integer.parseInt(text.toString().trim());
            return v < 0 ? 0 : v;
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    // ------------------------------------------------------------------
    //  History
    // ------------------------------------------------------------------

    private void setupHistoryList(@NonNull FragmentFocusBinding b) {
        adapter = new FocusSessionAdapter();
        b.rvHistory.setLayoutManager(new LinearLayoutManager(requireContext()));
        b.rvHistory.setAdapter(adapter);
        // The list lives inside a NestedScrollView and is not itself scrollable,
        // so it must not fight the parent for touch events.
        b.rvHistory.setNestedScrollingEnabled(false);
    }

    @Override
    public void onResume() {
        super.onResume();
        refreshData();
    }

    @Override
    public void refreshData() {
        FragmentFocusBinding b = binding;
        Repository repo = repository;
        if (b == null || repo == null) return;

        updateDeepStatus(b);

        AppDatabase.io().execute(() -> {
            List<FocusSessionEntity> sessions;
            int todaySessions;
            int todayMinutes;
            try {
                sessions = repo.db().focusSessionDao().recent(12);
                todaySessions = repo.db().focusSessionDao()
                        .completedCountOnDay(TimeUtils.today());
                todayMinutes = repo.db().focusSessionDao()
                        .focusedSecOnDay(TimeUtils.today()) / 60;
            } catch (Exception e) {
                android.util.Log.e("FocusFragment", "history load failed", e);
                return;
            }

            if (!isAdded()) return;
            requireActivity().runOnUiThread(() -> {
                FragmentFocusBinding current = binding;
                if (current == null || adapter == null) return;

                List<FocusSessionEntity> list = sessions == null
                        ? new ArrayList<>() : new ArrayList<>(sessions);
                adapter.submitList(list);

                current.tvHistoryEmpty.setVisibility(list.isEmpty() ? View.VISIBLE : View.GONE);
                current.rvHistory.setVisibility(list.isEmpty() ? View.GONE : View.VISIBLE);
                current.tvTodaySummary.setText(getString(R.string.focus_today_summary,
                        todaySessions, TimeUtils.formatMinutes(todayMinutes)));
            });
        });
    }

    @Override
    public void scrollToTop() {
        FragmentFocusBinding b = binding;
        if (b != null) b.focusScroll.smoothScrollTo(0, 0);
    }

    private void showMessage(@NonNull String message) {
        FragmentFocusBinding b = binding;
        if (b == null) return;
        Snackbar.make(b.getRoot(), message, Snackbar.LENGTH_LONG).show();
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        if (binding != null) {
            binding.rvHistory.setAdapter(null);
        }
        adapter = null;
        binding = null;
    }

    /** Minimal TextWatcher so three fields can share one implementation. */
    private static class SimpleTextWatcher implements TextWatcher {
        private final Runnable onChange;

        SimpleTextWatcher(@NonNull Runnable onChange) {
            this.onChange = onChange;
        }

        @Override
        public void beforeTextChanged(CharSequence s, int start, int count, int after) {
        }

        @Override
        public void onTextChanged(CharSequence s, int start, int before, int count) {
            onChange.run();
        }

        @Override
        public void afterTextChanged(Editable s) {
        }
    }
}
