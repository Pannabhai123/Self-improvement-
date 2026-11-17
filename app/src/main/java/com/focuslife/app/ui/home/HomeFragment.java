package com.focuslife.app.ui.home;

import android.content.Intent;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.focuslife.app.R;
import com.focuslife.app.blocking.BlockDecisionEngine;
import com.focuslife.app.blocking.ModeController;
import com.focuslife.app.data.db.AppDatabase;
import com.focuslife.app.data.repo.Repository;
import com.focuslife.app.databinding.FragmentHomeBinding;
import com.focuslife.app.databinding.ItemMetricCardBinding;
import com.focuslife.app.databinding.ItemQuickActionBinding;
import com.focuslife.app.notify.NotifHelper;
import com.focuslife.app.ui.MainActivity;
import com.focuslife.app.ui.blocker.AppPickerActivity;
import com.focuslife.app.ui.blocker.AppLimitActivity;
import com.focuslife.app.ui.control.ControlYourselfActivity;
import com.focuslife.app.ui.fitness.FitnessActivity;
import com.focuslife.app.ui.focus.FocusTimerActivity;
import com.focuslife.app.ui.motivation.MotivationActivity;
import com.focuslife.app.ui.onboarding.OnboardingActivity;
import com.focuslife.app.ui.report.ReportActivity;
import com.focuslife.app.util.PermissionManager;
import com.focuslife.app.util.TimeUtils;
import com.google.android.material.chip.Chip;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.snackbar.Snackbar;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * The dashboard.
 *
 * <h2>Loading strategy</h2>
 * {@link Repository#loadDashboard()} reads eleven tables plus the usage API, so
 * it can take a few hundred milliseconds on a Helio G85. It therefore runs on the
 * shared IO executor and posts the result back to the main thread. The previous
 * render stays visible during the load, which means switching tabs never flashes
 * an empty screen.
 *
 * <h2>Lifecycle safety</h2>
 * Every background result is applied through {@link #isAdded()} and a null check
 * on the binding, because a fragment can be detached while a query is in flight —
 * the most common source of crashes in this pattern.
 */
public class HomeFragment extends Fragment implements MainActivity.Refreshable,
        MainActivity.ScrollToTop {

    @Nullable
    private FragmentHomeBinding binding;

    @Nullable
    private Repository repository;

    /** Guards against two overlapping loads painting out of order. */
    private int loadGeneration;

    @Nullable
    private Repository.DashboardData lastData;

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        repository = Repository.get(requireContext().getApplicationContext());
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        binding = FragmentHomeBinding.inflate(inflater, container, false);
        return binding.getRoot();
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        FragmentHomeBinding b = binding;
        if (b == null) return;

        configureQuickActions(b);
        configureModeChips(b);

        b.btnReport.setOnClickListener(v -> startActivity(new Intent(requireContext(), ReportActivity.class)));
        b.cardNext.setOnClickListener(v -> {
            MainActivity host = hostActivity();
            if (host != null) host.goToTab(MainActivity.TAB_HOME);
        });
        b.btnLogRun.setOnClickListener(v ->
                startActivity(new Intent(requireContext(), FitnessActivity.class)));
        b.btnMoreMotivation.setOnClickListener(v ->
                startActivity(new Intent(requireContext(), MotivationActivity.class)));
        b.cardMotivation.setOnClickListener(v ->
                startActivity(new Intent(requireContext(), MotivationActivity.class)));
        b.streakPill.setOnClickListener(v -> {
            MainActivity host = hostActivity();
            if (host != null) host.goToTab(MainActivity.TAB_HABITS);
        });

        // A long press on the ring opens the report, which is the natural
        // "tell me more about this number" gesture.
        b.ringProgress.setOnLongClickListener(v -> {
            startActivity(new Intent(requireContext(), ReportActivity.class));
            return true;
        });
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
        FragmentHomeBinding b = binding;
        Repository repo = repository;
        if (b == null || repo == null) return;

        final int generation = ++loadGeneration;
        AppDatabase.io().execute(() -> {
            Repository.DashboardData data;
            try {
                data = repo.loadDashboard();
            } catch (Exception e) {
                android.util.Log.e("HomeFragment", "dashboard load failed", e);
                return;
            }
            if (!isAdded()) return;
            requireActivity().runOnUiThread(() -> {
                // A newer load started while this one was in flight; drop it.
                if (generation != loadGeneration || binding == null) return;
                render(data);
            });
        });
    }

    private void render(@NonNull Repository.DashboardData d) {
        FragmentHomeBinding b = binding;
        if (b == null) return;
        lastData = d;

        renderHeader(b, d);
        renderRing(b, d);
        renderMode(b, d);
        renderMetrics(b, d);
        renderGoals(b, d);
        renderFitness(b, d);
        renderNextActivity(b, d);
        renderChart(b, d);
        renderQuote(b, d);
    }

    // ------------------------------------------------------------------
    //  Header
    // ------------------------------------------------------------------

    private void renderHeader(@NonNull FragmentHomeBinding b, @NonNull Repository.DashboardData d) {
        int greetingRes;
        switch (TimeUtils.dayPart()) {
            case TimeUtils.DAY_PART_MORNING:   greetingRes = R.string.dashboard_greeting_morning; break;
            case TimeUtils.DAY_PART_AFTERNOON: greetingRes = R.string.dashboard_greeting_afternoon; break;
            case TimeUtils.DAY_PART_EVENING:   greetingRes = R.string.dashboard_greeting_evening; break;
            default:                           greetingRes = R.string.dashboard_greeting_night; break;
        }
        b.tvGreeting.setText(greetingRes);
        b.tvDate.setText(new SimpleDateFormat("EEE, d MMM", Locale.getDefault())
                .format(new Date()));
    }

    // ------------------------------------------------------------------
    //  Ring, score and streak
    // ------------------------------------------------------------------

    private void renderRing(@NonNull FragmentHomeBinding b, @NonNull Repository.DashboardData d) {
        int score = d.progressPercent;
        b.ringProgress.setTexts(String.valueOf(score), getString(R.string.dashboard_focus_score));
        b.ringProgress.setProgress(score, true);

        // Colour encodes the verdict, but the verdict is also written out below,
        // so the information is never colour-alone.
        int colorRes;
        if (score >= 85) colorRes = R.color.success_500;
        else if (score >= 60) colorRes = R.color.accent_500;
        else if (score >= 30) colorRes = R.color.warning_500;
        else colorRes = R.color.text_tertiary;
        b.ringProgress.setRingColor(requireContext().getColor(colorRes));

        b.tvVerdict.setText(verdictFor(score));

        if (d.streak > 0) {
            b.tvStreak.setText(d.streak == 1
                    ? getString(R.string.streak_days_one)
                    : getString(R.string.streak_days, d.streak));
            b.streakPill.setVisibility(View.VISIBLE);
        } else {
            // Rather than hiding the pill (which would make the concept
            // undiscoverable) or showing "0 days" (which reads as a failure),
            // show the invitation to start.
            b.tvStreak.setText(R.string.streak_zero_hint);
            b.streakPill.setVisibility(View.VISIBLE);
        }
    }

    private int verdictFor(int score) {
        if (score >= 85) return R.string.score_verdict_great;
        if (score >= 60) return R.string.score_verdict_good;
        if (score >= 30) return R.string.score_verdict_mid;
        if (score > 0) return R.string.score_verdict_low;
        return R.string.score_verdict_start;
    }

    // ------------------------------------------------------------------
    //  Mode card
    // ------------------------------------------------------------------

    private void renderMode(@NonNull FragmentHomeBinding b, @NonNull Repository.DashboardData d) {
        boolean active = d.activeMode != null;

        b.ivModeIcon.setImageResource(active
                ? NotifHelper.modeIcon(d.activeMode)
                : R.drawable.ic_shield);
        b.tvModeTitle.setText(active
                ? ModeController.modeName(requireContext(), d.activeMode)
                : getString(R.string.mode_none));
        b.tvModeElapsed.setText(d.activeModeElapsed == null ? "" : d.activeModeElapsed);
        b.tvModeElapsed.setVisibility(active ? View.VISIBLE : View.GONE);

        syncChip(b.chipStudy, BlockDecisionEngine.MODE_STUDY, d.activeMode);
        syncChip(b.chipRun, BlockDecisionEngine.MODE_RUN, d.activeMode);
        syncChip(b.chipSleep, BlockDecisionEngine.MODE_SLEEP, d.activeMode);
        syncChip(b.chipFocusMode, BlockDecisionEngine.MODE_FOCUS, d.activeMode);

        // Warn when enforcement will not actually happen. Showing a mode as
        // "active" while nothing is enforced is the single most misleading thing
        // this screen could do.
        int enforcement = d.enforcementLevel;
        if (enforcement == ModeController.ENFORCEMENT_NONE) {
            b.tvModeWarning.setText(R.string.perm_denied_accessibility_desc);
            b.tvModeWarning.setVisibility(View.VISIBLE);
            b.tvModeWarning.setOnClickListener(v ->
                    startActivity(new Intent(requireContext(), OnboardingActivity.class)));
        } else if (enforcement == ModeController.ENFORCEMENT_NOTIFICATION) {
            b.tvModeWarning.setText(R.string.perm_denied_overlay_desc);
            b.tvModeWarning.setVisibility(View.VISIBLE);
            b.tvModeWarning.setOnClickListener(v ->
                    startActivity(PermissionManager.overlayIntent(requireContext())));
        } else {
            b.tvModeWarning.setVisibility(View.GONE);
            b.tvModeWarning.setOnClickListener(null);
        }
    }

    private void syncChip(@NonNull Chip chip, @NonNull String mode, @Nullable String activeMode) {
        chip.setChecked(mode.equals(activeMode));
    }

    private void configureModeChips(@NonNull FragmentHomeBinding b) {
        b.chipStudy.setOnClickListener(v -> onModeChip(BlockDecisionEngine.MODE_STUDY));
        b.chipRun.setOnClickListener(v -> onModeChip(BlockDecisionEngine.MODE_RUN));
        b.chipSleep.setOnClickListener(v -> onModeChip(BlockDecisionEngine.MODE_SLEEP));
        b.chipFocusMode.setOnClickListener(v -> onModeChip(BlockDecisionEngine.MODE_FOCUS));
    }

    /**
     * Tapping an active chip asks for confirmation before ending it; tapping an
     * inactive chip starts it immediately.
     *
     * <p>The asymmetry is deliberate. Starting a mode is a commitment and should
     * be frictionless; ending one mid-session is the exact decision the user
     * made the mode to protect them from, so it earns a confirmation.</p>
     */
    private void onModeChip(@NonNull String mode) {
        if (ModeController.isActive(requireContext(), mode)) {
            confirmStopMode(mode);
            return;
        }
        startMode(mode);
    }

    private void startMode(@NonNull String mode) {
        ModeController.StartResult result = ModeController.start(requireContext(), mode);
        if (!result.started) {
            showMessage(getString(R.string.error_generic));
            return;
        }

        if (result.enforcement == ModeController.ENFORCEMENT_NONE) {
            showMessage(getString(R.string.perm_denied_accessibility_desc));
        } else if (result.blockCount == 0) {
            // A mode that blocks nothing is confusing; point at the app picker.
            showMessage(getString(R.string.blocker_no_apps));
            startActivity(new Intent(requireContext(), AppPickerActivity.class));
        } else {
            showMessage(getString(R.string.mode_started));
        }
        refreshData();
    }

    private void confirmStopMode(@NonNull String mode) {
        String modeName = ModeController.modeName(requireContext(), mode);
        String elapsed = ModeController.elapsedLabel(requireContext());

        new MaterialAlertDialogBuilder(requireContext())
                .setTitle(getString(R.string.mode_stop_confirm_title, modeName))
                .setMessage(getString(R.string.mode_stop_confirm_message,
                        elapsed.isEmpty() ? getString(R.string.label_today) : elapsed))
                .setNegativeButton(R.string.action_cancel, null)
                .setPositiveButton(R.string.notif_action_stop, (dialog, which) -> {
                    ModeController.stop(requireContext());
                    showMessage(getString(R.string.mode_stopped, modeName));
                    refreshData();
                })
                .show();
    }

    // ------------------------------------------------------------------
    //  Metric cards
    // ------------------------------------------------------------------

    private void renderMetrics(@NonNull FragmentHomeBinding b, @NonNull Repository.DashboardData d) {
        // Study time
        bindMetric(b.metricStudy, R.drawable.ic_book,
                getString(R.string.dashboard_study_time),
                TimeUtils.formatMinutes(d.studyMinutes),
                getString(R.string.study_daily_target_set,
                        TimeUtils.formatMinutes(d.studyTargetMinutes)),
                percent(d.studyMinutes, d.studyTargetMinutes),
                R.color.accent_500);

        // Focus time
        bindMetric(b.metricFocus, R.drawable.ic_timer,
                getString(R.string.dashboard_focus_time),
                TimeUtils.formatMinutes(d.focusMinutes),
                getString(R.string.focus_today_summary, d.focusSessionsDone,
                        TimeUtils.formatMinutes(d.focusMinutes)),
                percent(d.focusSessionsDone, d.focusSessionTarget),
                R.color.info_500);

        // Mobile usage — the one metric where a high bar is bad, so the colour
        // inverts against the limit.
        String screenValue = d.screenMinutes < 0
                ? getString(R.string.label_none)
                : TimeUtils.formatMinutes(d.screenMinutes);
        String screenSub;
        int screenColor;
        if (d.screenMinutes < 0) {
            screenSub = getString(R.string.usage_no_permission);
            screenColor = R.color.text_tertiary;
        } else if (d.screenTargetMinutes > 0 && d.screenMinutes > d.screenTargetMinutes) {
            screenSub = getString(R.string.usage_over_limit,
                    TimeUtils.formatMinutes(d.screenMinutes - d.screenTargetMinutes));
            screenColor = R.color.danger_500;
        } else {
            screenSub = getString(R.string.usage_limit_set,
                    TimeUtils.formatMinutes(d.screenTargetMinutes));
            screenColor = R.color.success_500;
        }
        bindMetric(b.metricScreen, R.drawable.ic_phone_android,
                getString(R.string.dashboard_mobile_usage),
                screenValue, screenSub,
                percent(d.screenMinutes < 0 ? 0 : d.screenMinutes, d.screenTargetMinutes),
                screenColor);

        // Tasks
        bindMetric(b.metricTasks, R.drawable.ic_check_circle,
                getString(R.string.dashboard_completed_tasks),
                d.tasksDone + " / " + d.tasksTotal,
                d.tasksTotal == 0
                        ? getString(R.string.empty_generic)
                        : getString(R.string.tasks_done_of_total, d.tasksDone, d.tasksTotal),
                percent(d.tasksDone, d.tasksTotal),
                R.color.success_500);

        // Tapping the usage card explains how to reduce it.
        b.metricScreen.getRoot().setOnClickListener(v ->
                startActivity(new Intent(requireContext(), ReportActivity.class)));
    }

    private void bindMetric(@NonNull ItemMetricCardBinding card, int iconRes,
                            @NonNull String label, @NonNull String value, @NonNull String sub,
                            int percent, int colorRes) {
        card.ivIcon.setImageResource(iconRes);
        card.ivIcon.setImageTintList(
                android.content.res.ColorStateList.valueOf(requireContext().getColor(colorRes)));
        card.tvLabel.setText(label);
        card.tvValue.setText(value);
        card.tvSub.setText(sub);
        card.pbMetric.setProgress(Math.max(0, Math.min(100, percent)));
        card.pbMetric.getProgressDrawable().setTint(requireContext().getColor(colorRes));
    }

    private static int percent(int value, int target) {
        if (target <= 0) return 0;
        return Math.max(0, Math.min(100, Math.round(value * 100f / target)));
    }

    // ------------------------------------------------------------------
    //  Remaining goals
    // ------------------------------------------------------------------

    private void renderGoals(@NonNull FragmentHomeBinding b, @NonNull Repository.DashboardData d) {
        if (d.tasksTotal == 0) {
            b.tvRemainingGoals.setText(R.string.empty_generic);
            b.tvGoalsDetail.setText(R.string.dashboard_remaining_goals);
            b.pbGoals.setProgress(0);
            return;
        }

        if (d.tasksRemaining == 0) {
            b.tvRemainingGoals.setText(R.string.remaining_goals_none);
            b.tvGoalsDetail.setText(getString(R.string.tasks_done_of_total, d.tasksDone, d.tasksTotal));
            b.pbGoals.setProgress(100);
            return;
        }

        b.tvRemainingGoals.setText(d.tasksRemaining == 1
                ? getString(R.string.remaining_goals_count, d.tasksRemaining)
                : getString(R.string.remaining_goals_count_plural, d.tasksRemaining));
        b.pbGoals.setProgress(percent(d.tasksDone, d.tasksTotal));
        b.tvGoalsDetail.setText(d.remainingMinutes > 0
                ? getString(R.string.remaining_minutes_estimate,
                        TimeUtils.formatMinutes(d.remainingMinutes))
                : getString(R.string.tasks_done_of_total, d.tasksDone, d.tasksTotal));
    }

    // ------------------------------------------------------------------
    //  Fitness
    // ------------------------------------------------------------------

    private void renderFitness(@NonNull FragmentHomeBinding b, @NonNull Repository.DashboardData d) {
        b.tvRunDistance.setText(TimeUtils.formatDistance(d.runDistanceM));
        b.tvRunTarget.setText(d.runTargetM > 0
                ? getString(R.string.run_target_progress,
                        TimeUtils.formatDistance(d.runDistanceM),
                        TimeUtils.formatDistance(d.runTargetM))
                : getString(R.string.fitness_daily_target));
        b.tvWorkouts.setText(d.workoutsDone + " / " + Math.max(1, d.workoutTarget));
        b.pbRun.setProgress(percent(d.runDistanceM, d.runTargetM));

        if (d.restDay) {
            b.tvRestDay.setVisibility(View.VISIBLE);
            b.pbRun.setProgress(100);
        } else {
            b.tvRestDay.setVisibility(View.GONE);
        }
    }

    // ------------------------------------------------------------------
    //  Next activity
    // ------------------------------------------------------------------

    private void renderNextActivity(@NonNull FragmentHomeBinding b, @NonNull Repository.DashboardData d) {
        if (d.nextRoutineTitle == null || d.nextRoutineMinute < 0) {
            b.cardNext.setVisibility(View.GONE);
            return;
        }
        b.cardNext.setVisibility(View.VISIBLE);
        String clock = com.focuslife.app.data.prefs.Prefs.use24HourClock(requireContext())
                ? TimeUtils.formatClock24(d.nextRoutineMinute)
                : TimeUtils.formatClock12(d.nextRoutineMinute);
        b.tvNextActivity.setText(getString(R.string.next_activity_at, d.nextRoutineTitle, clock));
    }

    // ------------------------------------------------------------------
    //  Chart and quote
    // ------------------------------------------------------------------

    private void renderChart(@NonNull FragmentHomeBinding b, @NonNull Repository.DashboardData d) {
        b.chartStudy.setWeekData(d.weekStudyMinutes, d.weekKeys);
        if (d.studyTargetMinutes > 0) {
            // Express the daily target as a fraction of the tallest bar so the
            // reference line is meaningful rather than decorative.
            int max = 1;
            for (int v : d.weekStudyMinutes) {
                if (v > max) max = v;
            }
            max = Math.max(max, d.studyTargetMinutes);
            b.chartStudy.setTargetPercent(
                    Math.max(1, Math.min(100, Math.round(d.studyTargetMinutes * 100f / max))));
        }

        int weekTotal = 0;
        for (int v : d.weekStudyMinutes) weekTotal += Math.max(0, v);
        b.tvWeekSummary.setText(getString(R.string.study_minutes_week,
                TimeUtils.formatMinutes(weekTotal)));
    }

    private void renderQuote(@NonNull FragmentHomeBinding b, @NonNull Repository.DashboardData d) {
        try {
            int resId = Repository.QUOTE_RES[d.quoteIndex % Repository.QUOTE_RES.length];
            b.tvQuote.setText(resId);
        } catch (Exception e) {
            b.tvQuote.setText(R.string.quote_4);
        }
    }

    // ------------------------------------------------------------------
    //  Quick actions
    // ------------------------------------------------------------------

    private void configureQuickActions(@NonNull FragmentHomeBinding b) {
        bindQuickAction(b.quickFocus, R.drawable.ic_timer, R.string.quick_deep_focus,
                R.color.accent_500, v -> startActivity(
                        new Intent(requireContext(), FocusTimerActivity.class)));

        bindQuickAction(b.quickControl, R.drawable.ic_self_improvement, R.string.quick_control,
                R.color.info_500, v -> startActivity(
                        new Intent(requireContext(), ControlYourselfActivity.class)));

        bindQuickAction(b.quickBlocker, R.drawable.ic_block, R.string.quick_blocker,
                R.color.warning_500, v -> startActivity(
                        new Intent(requireContext(), AppLimitActivity.class)));

        bindQuickAction(b.quickFitness, R.drawable.ic_directions_run, R.string.title_fitness,
                R.color.success_500, v -> startActivity(
                        new Intent(requireContext(), FitnessActivity.class)));
    }

    private void bindQuickAction(@NonNull ItemQuickActionBinding tile, int iconRes, int labelRes,
                                 int colorRes, @NonNull View.OnClickListener listener) {
        tile.ivIcon.setImageResource(iconRes);
        tile.ivIcon.setImageTintList(
                android.content.res.ColorStateList.valueOf(requireContext().getColor(colorRes)));
        tile.tvLabel.setText(labelRes);
        tile.getRoot().setOnClickListener(listener);
    }

    // ------------------------------------------------------------------
    //  Helpers
    // ------------------------------------------------------------------

    @Override
    public void scrollToTop() {
        FragmentHomeBinding b = binding;
        if (b != null) {
            b.homeScroll.smoothScrollTo(0, 0);
        }
    }

    @Nullable
    private MainActivity hostActivity() {
        if (getActivity() instanceof MainActivity) return (MainActivity) getActivity();
        return null;
    }

    private void showMessage(@NonNull String message) {
        FragmentHomeBinding b = binding;
        if (b == null) return;
        Snackbar.make(b.getRoot(), message, Snackbar.LENGTH_LONG).show();
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        // Drop the view references so a detached fragment cannot leak the whole
        // inflated hierarchy through the binding.
        binding = null;
    }
}
