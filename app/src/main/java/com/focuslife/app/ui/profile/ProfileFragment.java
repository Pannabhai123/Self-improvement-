package com.focuslife.app.ui.profile;

import android.content.Context;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.StringRes;
import androidx.fragment.app.Fragment;

import com.focuslife.app.BuildConfig;
import com.focuslife.app.R;
import com.focuslife.app.blocking.ModeController;
import com.focuslife.app.data.db.AppDatabase;
import com.focuslife.app.data.prefs.Prefs;
import com.focuslife.app.data.repo.Repository;
import com.focuslife.app.databinding.DialogNumberBinding;
import com.focuslife.app.databinding.DialogTypedConfirmBinding;
import com.focuslife.app.databinding.FragmentProfileBinding;
import com.focuslife.app.databinding.ItemMetricCardBinding;
import com.focuslife.app.databinding.ItemSettingRowBinding;
import com.focuslife.app.ui.MainActivity;
import com.focuslife.app.ui.onboarding.OnboardingActivity;
import com.focuslife.app.ui.report.ReportActivity;
import com.focuslife.app.util.PermissionManager;
import com.focuslife.app.util.TimeUtils;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.snackbar.Snackbar;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Profile — the fifth bottom-nav tab: lifetime progress, permissions, daily
 * targets, language, data export and the two resets.
 *
 * <h2>Targets are honest about their reach</h2>
 * The five numbers here drive the Focus Score's components and the comparisons on
 * the dashboard, report and usage screens. They are <em>not</em> enforcement:
 * blocking is configured per app in the blocker, and this screen never implies
 * otherwise. Setting a target to zero switches that component off entirely, which
 * {@link com.focuslife.app.stats.FocusScoreCalculator} handles by renormalising the
 * remaining weights rather than by awarding free points.
 *
 * <h2>Destructive actions</h2>
 * Both resets are last in the scroll and visually separated. Erasing everything
 * requires typing {@code RESET}, because the database is local-only — there is no
 * server copy to recover from, so a single mis-tap would be unrecoverable.
 *
 * <h2>Language</h2>
 * Changing the override recreates the activity. That is not a shortcut: the app
 * wraps its base context per-activity, so already-inflated views would otherwise
 * keep their old strings until each screen happened to be rebuilt.
 */
public class ProfileFragment extends Fragment implements MainActivity.Refreshable,
        MainActivity.ScrollToTop {

    private static final String TAG = "Profile";

    /** Word the user must type to confirm erasing all data. */
    private static final String ERASE_CONFIRM_WORD = "RESET";

    /** Number of permissions {@link PermissionManager#grantedCount} reports on. */
    private static final int COUNTED_PERMISSIONS = 4;

    private static final SimpleDateFormat EXPORT_STAMP =
            new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US);

    @Nullable
    private FragmentProfileBinding binding;

    @Nullable
    private Repository repository;

    /** Latest lifetime totals, kept so exporting does not re-query. */
    @Nullable
    private Repository.LifetimeStats lifetime;

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Context context = getContext();
        if (context != null) {
            repository = Repository.get(context.getApplicationContext());
        }
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        binding = FragmentProfileBinding.inflate(inflater, container, false);
        return binding.getRoot();
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        FragmentProfileBinding b = binding;
        if (b == null) return;

        b.cardPermissions.setOnClickListener(v ->
                startActivity(new Intent(requireContext(), OnboardingActivity.class)));
        b.cardExport.setOnClickListener(v -> exportData());
        b.cardResetDay.setOnClickListener(v -> confirmResetDay());
        b.cardEraseAll.setOnClickListener(v -> confirmEraseAll());
        b.btnViewReport.setOnClickListener(v ->
                startActivity(ReportActivity.intent(requireContext())));

        bindLanguageControls(b);
        bindClockSwitch(b);

        b.tvVersion.setText(getString(R.string.profile_version) + "  "
                + BuildConfig.VERSION_NAME);
    }

    // ------------------------------------------------------------------
    //  Loading
    // ------------------------------------------------------------------

    @Override
    public void refreshData() {
        load();
    }

    @Override
    public void scrollToTop() {
        FragmentProfileBinding b = binding;
        if (b != null) b.profileScroll.smoothScrollTo(0, 0);
    }

    @Override
    public void onResume() {
        super.onResume();
        // Permissions and the enforcement level change in system settings, outside
        // this app, so they are re-read every time the tab becomes visible.
        renderPermissionStatus();
        load();
    }

    private void load() {
        FragmentProfileBinding b = binding;
        Repository repo = repository;
        if (b == null || repo == null) return;

        AppDatabase.io().execute(() -> {
            Repository.LifetimeStats stats;
            try {
                stats = repo.loadLifetimeStats();
            } catch (Exception e) {
                android.util.Log.e(TAG, "could not load lifetime stats", e);
                return;
            }
            long dbSize = databaseSize();
            if (!isAdded()) return;
            requireActivity().runOnUiThread(() -> {
                FragmentProfileBinding current = binding;
                if (current == null) return;
                lifetime = stats;
                renderSummary(current, stats);
                renderTargets(current);
                renderStorage(current, dbSize);
            });
        });
    }

    private void renderSummary(@NonNull FragmentProfileBinding b,
                               @NonNull Repository.LifetimeStats s) {
        b.summaryContainer.removeAllViews();
        // Reached from a background callback, so the fragment may have detached
        // between the query and this line.
        Context context = getContext();
        if (context == null) return;
        LayoutInflater inflater = LayoutInflater.from(context);

        addSummaryMetric(inflater, b, R.drawable.ic_book, R.string.profile_total_study,
                TimeUtils.formatMinutes(s.totalStudyMinutes), R.color.accent_500);
        addSummaryMetric(inflater, b, R.drawable.ic_timer, R.string.profile_total_focus,
                TimeUtils.formatMinutes(s.totalFocusMinutes), R.color.chart_3);
        addSummaryMetric(inflater, b, R.drawable.ic_insights, R.string.profile_total_sessions,
                String.valueOf(s.totalSessions), R.color.chart_5);
        addSummaryMetric(inflater, b, R.drawable.ic_directions_run, R.string.profile_total_distance,
                TimeUtils.formatDistance(s.totalDistanceM), R.color.success_500);
        addSummaryMetric(inflater, b, R.drawable.ic_local_fire, R.string.profile_current_streak,
                String.valueOf(s.currentStreak), R.color.warning_500);
        addSummaryMetric(inflater, b, R.drawable.ic_star, R.string.profile_best_streak,
                String.valueOf(s.bestStreak), R.color.chart_4);
        addSummaryMetricFormatted(inflater, b, R.drawable.ic_school,
                getString(R.string.motivation_lifetime_topics, s.completedTopics, s.totalTopics),
                s.completedTopics + " / " + s.totalTopics, R.color.info_500);
    }

    /**
     * Adds a summary row whose label is already formatted, for labels that carry
     * their own numbers rather than being a plain string resource.
     */
    private void addSummaryMetricFormatted(@NonNull LayoutInflater inflater,
                                           @NonNull FragmentProfileBinding b,
                                           int iconRes, @NonNull String label,
                                           @NonNull String value, int colorRes) {
        ItemMetricCardBinding row = ItemMetricCardBinding
                .inflate(inflater, b.summaryContainer, false);
        row.ivIcon.setImageResource(iconRes);
        row.ivIcon.setImageTintList(ColorStateList.valueOf(
                requireContext().getColor(colorRes)));
        row.tvLabel.setText(label);
        row.tvValue.setText(value);
        row.tvValue.setTextColor(requireContext().getColor(R.color.text_primary));
        row.tvSub.setVisibility(View.GONE);
        row.pbMetric.setVisibility(View.GONE);
        b.summaryContainer.addView(row.getRoot());
    }

    private void addSummaryMetric(@NonNull LayoutInflater inflater,
                                  @NonNull FragmentProfileBinding b,
                                  int iconRes, @StringRes int labelRes,
                                  @NonNull String value, int colorRes) {
        ItemMetricCardBinding row = ItemMetricCardBinding
                .inflate(inflater, b.summaryContainer, false);
        row.ivIcon.setImageResource(iconRes);
        row.ivIcon.setImageTintList(ColorStateList.valueOf(
                requireContext().getColor(colorRes)));
        row.tvLabel.setText(labelRes);
        row.tvValue.setText(value);
        row.tvValue.setTextColor(requireContext().getColor(R.color.text_primary));
        row.tvSub.setVisibility(View.GONE);
        row.pbMetric.setVisibility(View.GONE);
        b.summaryContainer.addView(row.getRoot());
    }

    private void renderPermissionStatus() {
        FragmentProfileBinding b = binding;
        Context context = getContext();
        if (b == null || context == null) return;

        int granted = PermissionManager.grantedCount(context);
        b.tvPermissionStatus.setText(getString(R.string.profile_permission_status,
                granted, COUNTED_PERMISSIONS));

        int level = ModeController.enforcementLevel(context);
        String state = getString(level == ModeController.ENFORCEMENT_NONE
                ? R.string.profile_status_off : R.string.profile_status_on);
        b.tvBlockingStatus.setText(getString(R.string.profile_blocking_status, state));
        b.tvBlockingStatus.setTextColor(requireContext().getColor(
                level == ModeController.ENFORCEMENT_NONE
                        ? R.color.warning_500 : R.color.success_500));
    }

    // ------------------------------------------------------------------
    //  Daily targets
    // ------------------------------------------------------------------

    private void renderTargets(@NonNull FragmentProfileBinding b) {
        b.targetContainer.removeAllViews();
        Context context = getContext();
        if (context == null) return;
        LayoutInflater inflater = LayoutInflater.from(context);

        addTargetRow(inflater, b, R.string.profile_target_study,
                TimeUtils.formatMinutes(Prefs.targetStudyMinutes(context)),
                Prefs.targetStudyMinutes(context), 0, 1440,
                value -> Prefs.setTargetStudyMinutes(context, value));

        addTargetRow(inflater, b, R.string.profile_target_focus_sessions,
                String.valueOf(Prefs.targetFocusSessions(context)),
                Prefs.targetFocusSessions(context), 0, 40,
                value -> Prefs.setTargetFocusSessions(context, value));

        addTargetRow(inflater, b, R.string.profile_target_run,
                TimeUtils.formatDistance(Prefs.targetRunMetres(context)),
                Prefs.targetRunMetres(context), 0, 100_000,
                value -> Prefs.setTargetRunMetres(context, value));

        addTargetRow(inflater, b, R.string.profile_target_screen,
                Prefs.targetScreenMinutes(context) == 0
                        ? getString(R.string.label_none)
                        : TimeUtils.formatMinutes(Prefs.targetScreenMinutes(context)),
                Prefs.targetScreenMinutes(context), 0, 1440,
                value -> Prefs.setTargetScreenMinutes(context, value));

        addTargetRow(inflater, b, R.string.profile_target_workouts,
                String.valueOf(Prefs.targetWorkouts(context)),
                Prefs.targetWorkouts(context), 0, 30,
                value -> Prefs.setTargetWorkouts(context, value));
    }

    /** Writes a validated target back to preferences. */
    private interface TargetWriter {
        void write(int value);
    }

    private void addTargetRow(@NonNull LayoutInflater inflater, @NonNull FragmentProfileBinding b,
                              @StringRes int labelRes, @NonNull String displayValue,
                              int currentValue, int min, int max,
                              @NonNull TargetWriter writer) {
        ItemSettingRowBinding row = ItemSettingRowBinding
                .inflate(inflater, b.targetContainer, false);

        row.tvSettingLabel.setText(labelRes);
        row.tvSettingValue.setText(displayValue);
        row.getRoot().setOnClickListener(v ->
                showNumberEditor(labelRes, currentValue, min, max, writer));

        b.targetContainer.addView(row.getRoot());
    }

    private void showNumberEditor(@StringRes int titleRes, int currentValue,
                                  int min, int max, @NonNull TargetWriter writer) {
        DialogNumberBinding d =
                DialogNumberBinding.inflate(LayoutInflater.from(requireContext()));
        d.fieldNumber.setHint(titleRes);
        d.etNumber.setText(String.valueOf(currentValue));
        d.tvNumberHint.setText(getString(R.string.profile_target_range, min, max));

        new MaterialAlertDialogBuilder(requireContext())
                .setTitle(titleRes)
                .setView(d.getRoot())
                .setNegativeButton(R.string.action_cancel, null)
                .setPositiveButton(R.string.action_save, (dialog, which) -> {
                    String raw = d.etNumber.getText() == null
                            ? "" : d.etNumber.getText().toString().trim();
                    int value;
                    try {
                        value = Integer.parseInt(raw);
                    } catch (NumberFormatException e) {
                        showMessage(getString(R.string.validation_number));
                        return;
                    }
                    if (value < min || value > max) {
                        showMessage(getString(R.string.profile_target_range, min, max));
                        return;
                    }
                    writer.write(value);
                    // Targets feed the Focus Score, so today's summary is now
                    // describing a different target than the one it was scored
                    // against. Regenerating keeps the dashboard and the report
                    // consistent with the change immediately.
                    Repository repo = repository;
                    if (repo != null) repo.scheduleSummaryRefresh(TimeUtils.today());
                    FragmentProfileBinding current = binding;
                    if (current != null) renderTargets(current);
                    showMessage(getString(R.string.profile_target_saved));
                })
                .show();
    }

    // ------------------------------------------------------------------
    //  Language and clock
    // ------------------------------------------------------------------

    private void bindLanguageControls(@NonNull FragmentProfileBinding b) {
        Context context = getContext();
        if (context == null) return;

        String current = Prefs.languageOverride(context);
        if (Prefs.LANG_EN.equals(current)) {
            b.radioLangEn.setChecked(true);
        } else if (Prefs.LANG_HI.equals(current)) {
            b.radioLangHi.setChecked(true);
        } else {
            b.radioLangSystem.setChecked(true);
        }

        b.groupLanguage.setOnCheckedChangeListener((group, checkedId) -> {
            String chosen;
            if (checkedId == R.id.radio_lang_en) {
                chosen = Prefs.LANG_EN;
            } else if (checkedId == R.id.radio_lang_hi) {
                chosen = Prefs.LANG_HI;
            } else {
                chosen = Prefs.LANG_SYSTEM;
            }
            Context ctx = getContext();
            if (ctx == null) return;
            if (chosen.equals(Prefs.languageOverride(ctx))) return;

            Prefs.setLanguageOverride(ctx, chosen);
            // Recreate so every already-inflated screen picks up the new locale
            // instead of a mix of old and new strings.
            if (isAdded() && getActivity() != null) {
                getActivity().recreate();
            }
        });
    }

    private void bindClockSwitch(@NonNull FragmentProfileBinding b) {
        Context context = getContext();
        if (context == null) return;

        b.switchClock24.setChecked(Prefs.use24HourClock(context));
        b.switchClock24.setOnCheckedChangeListener((view, checked) -> {
            Context ctx = getContext();
            if (ctx == null) return;
            Prefs.setUse24HourClock(ctx, checked);
        });
    }

    // ------------------------------------------------------------------
    //  Export
    // ------------------------------------------------------------------

    /**
     * Writes a plain-text copy of everything measurable to app-specific external
     * storage.
     *
     * <p>App-specific external storage needs no runtime permission on Android 10+,
     * and the file is removed by the system if the app is uninstalled — so the
     * export never leaves behind data the user cannot find or clear. The text is
     * built from aggregates and the user's own entries, never from other apps'
     * content.</p>
     */
    private void exportData() {
        Repository repo = repository;
        if (repo == null) return;

        AppDatabase.io().execute(() -> {
            File target = null;
            try {
                File dir = requireContext().getExternalFilesDir(null);
                if (dir == null) dir = requireContext().getFilesDir();
                target = new File(dir, "focuslife-export-"
                        + TimeUtils.today() + ".txt");

                String text = buildExportText(repo);
                try (OutputStreamWriter writer = new OutputStreamWriter(
                        new FileOutputStream(target), StandardCharsets.UTF_8)) {
                    writer.write(text);
                }
            } catch (Exception e) {
                android.util.Log.e(TAG, "could not export data", e);
                target = null;
            }

            final File written = target;
            if (!isAdded()) return;
            requireActivity().runOnUiThread(() -> {
                if (written == null) {
                    showMessage(getString(R.string.profile_export_failed));
                } else {
                    showMessage(getString(R.string.profile_export_done,
                            written.getAbsolutePath()));
                }
            });
        });
    }

    @NonNull
    private String buildExportText(@NonNull Repository repo) {
        StringBuilder sb = new StringBuilder();
        sb.append("Focus Life — ").append(getString(R.string.profile_export)).append('\n');
        sb.append(getString(R.string.report_generated_at,
                EXPORT_STAMP.format(new Date()))).append("\n\n");

        Repository.LifetimeStats s = lifetime != null ? lifetime : repo.loadLifetimeStats();
        appendExportLine(sb, R.string.profile_total_study,
                TimeUtils.formatMinutes(s.totalStudyMinutes));
        appendExportLine(sb, R.string.profile_total_focus,
                TimeUtils.formatMinutes(s.totalFocusMinutes));
        appendExportLine(sb, R.string.profile_total_sessions, String.valueOf(s.totalSessions));
        appendExportLine(sb, R.string.profile_total_distance,
                TimeUtils.formatDistance(s.totalDistanceM));
        appendExportLine(sb, R.string.profile_current_streak, String.valueOf(s.currentStreak));
        appendExportLine(sb, R.string.profile_best_streak, String.valueOf(s.bestStreak));
        appendExportLine(sb, R.string.report_distractions_blocked,
                String.valueOf(s.totalBlockHits));
        appendExportLine(sb, R.string.report_goals_achieved, String.valueOf(s.achievedGoals));

        sb.append('\n').append(getString(R.string.profile_targets)).append('\n');
        Context context = getContext();
        if (context != null) {
            appendExportLine(sb, R.string.profile_target_study,
                    TimeUtils.formatMinutes(Prefs.targetStudyMinutes(context)));
            appendExportLine(sb, R.string.profile_target_focus_sessions,
                    String.valueOf(Prefs.targetFocusSessions(context)));
            appendExportLine(sb, R.string.profile_target_run,
                    TimeUtils.formatDistance(Prefs.targetRunMetres(context)));
            appendExportLine(sb, R.string.profile_target_screen,
                    TimeUtils.formatMinutes(Prefs.targetScreenMinutes(context)));
            appendExportLine(sb, R.string.profile_target_workouts,
                    String.valueOf(Prefs.targetWorkouts(context)));
        }

        sb.append('\n').append(getString(R.string.report_weekly_trend)).append('\n');
        Repository.WeekData w = repo.loadWeek();
        appendExportLine(sb, R.string.report_weekly_avg_score, String.valueOf(w.avgScore));
        appendExportLine(sb, R.string.report_weekly_study_total,
                TimeUtils.formatMinutes(w.studyMinutes));
        appendExportLine(sb, R.string.report_weekly_screen_total,
                TimeUtils.formatMinutes(w.screenMinutes));

        sb.append('\n').append(getString(R.string.profile_offline)).append('\n');
        return sb.toString();
    }

    private void appendExportLine(@NonNull StringBuilder sb, @StringRes int labelRes,
                                  @NonNull String value) {
        sb.append(getString(labelRes)).append(": ").append(value).append('\n');
    }

    // ------------------------------------------------------------------
    //  Resets
    // ------------------------------------------------------------------

    private void confirmResetDay() {
        new MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.profile_reset_day)
                .setMessage(R.string.profile_reset_day_confirm)
                .setNegativeButton(R.string.action_cancel, null)
                .setPositiveButton(R.string.action_reset, (dialog, which) -> {
                    Repository repo = repository;
                    if (repo == null) return;
                    AppDatabase.io().execute(() -> {
                        try {
                            repo.resetToday();
                            repo.generateDailySummary(TimeUtils.today());
                        } catch (Exception e) {
                            android.util.Log.e(TAG, "could not reset today", e);
                        }
                        if (!isAdded()) return;
                        requireActivity().runOnUiThread(() -> {
                            showMessage(getString(R.string.profile_day_reset_done));
                            load();
                        });
                    });
                })
                .show();
    }

    private void confirmEraseAll() {
        DialogTypedConfirmBinding d = DialogTypedConfirmBinding
                .inflate(LayoutInflater.from(requireContext()));
        d.tvConfirmMessage.setText(R.string.profile_reset_all_confirm);
        d.fieldConfirm.setHint(ERASE_CONFIRM_WORD);

        new MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.profile_reset_all)
                .setView(d.getRoot())
                .setNegativeButton(R.string.action_cancel, null)
                .setPositiveButton(R.string.action_reset, (dialog, which) -> {
                    String typed = d.etConfirm.getText() == null
                            ? "" : d.etConfirm.getText().toString().trim();
                    if (!ERASE_CONFIRM_WORD.equalsIgnoreCase(typed)) {
                        showMessage(getString(R.string.profile_reset_all_confirm));
                        return;
                    }
                    Repository repo = repository;
                    if (repo == null) return;
                    AppDatabase.io().execute(() -> {
                        try {
                            repo.eraseAll();
                        } catch (Exception e) {
                            android.util.Log.e(TAG, "could not erase data", e);
                        }
                        if (!isAdded()) return;
                        requireActivity().runOnUiThread(() -> {
                            showMessage(getString(R.string.profile_reset_done));
                            lifetime = null;
                            load();
                        });
                    });
                })
                .show();
    }

    // ------------------------------------------------------------------
    //  Helpers
    // ------------------------------------------------------------------

    /** Size of the database file plus its journal, in bytes; 0 if unreadable. */
    private long databaseSize() {
        Context context = getContext();
        if (context == null) return 0L;
        try {
            File db = context.getDatabasePath(AppDatabase.NAME);
            long total = 0L;
            if (db != null && db.exists()) total += db.length();
            for (String suffix : new String[]{"-journal", "-wal", "-shm"}) {
                File side = new File(db.getParentFile(), db.getName() + suffix);
                if (side.exists()) total += side.length();
            }
            return total;
        } catch (Exception e) {
            return 0L;
        }
    }

    private void renderStorage(@NonNull FragmentProfileBinding b, long bytes) {
        if (bytes <= 0L) {
            b.tvStorageUsed.setVisibility(View.GONE);
            return;
        }
        b.tvStorageUsed.setVisibility(View.VISIBLE);
        b.tvStorageUsed.setText(getString(R.string.profile_storage_used) + "  "
                + getString(R.string.separator_dot) + "  " + formatBytes(bytes));
    }

    @NonNull
    private static String formatBytes(long bytes) {
        if (bytes < 1024L) return bytes + " B";
        double kb = bytes / 1024.0;
        if (kb < 1024.0) return String.format(Locale.US, "%.0f KB", kb);
        return String.format(Locale.US, "%.1f MB", kb / 1024.0);
    }

    private void showMessage(@NonNull String message) {
        FragmentProfileBinding b = binding;
        if (b == null || TextUtils.isEmpty(message)) return;
        Snackbar.make(b.getRoot(), message, Snackbar.LENGTH_LONG).show();
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        binding = null;
    }
}
