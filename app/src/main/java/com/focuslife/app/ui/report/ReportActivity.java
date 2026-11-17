package com.focuslife.app.ui.report;

import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.content.res.ColorStateList;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.StringRes;
import androidx.appcompat.app.AppCompatActivity;

import com.focuslife.app.R;
import com.focuslife.app.data.db.AppDatabase;
import com.focuslife.app.data.db.entity.DailySummaryEntity;
import com.focuslife.app.data.prefs.Prefs;
import com.focuslife.app.data.repo.Repository;
import com.focuslife.app.databinding.ActivityReportBinding;
import com.focuslife.app.databinding.ItemMetricCardBinding;
import com.focuslife.app.util.TimeUtils;
import com.google.android.material.snackbar.Snackbar;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Daily and weekly report — brief section 11.
 *
 * <h2>One source of truth</h2>
 * Every number here comes from the same {@link DailySummaryEntity} row that the
 * dashboard and the 9 PM notification read. The row is regenerated on open, so a
 * report is never a stale copy of the day it describes.
 *
 * <h2>What it refuses to invent</h2>
 * Screen time that could not be measured is reported as not measured, never as
 * zero. A day with no data at all says so. Running is reported as completed,
 * missed, or a rest day — a rest day is a completed plan, not a miss.
 *
 * <h2>Tone of the improvement section</h2>
 * The notes are the same ones {@link Repository} builds for the summary: they
 * name a gap and its size, and stop there. A day with nothing to improve says so
 * plainly, because an always-populated criticism list trains the user to ignore
 * it.
 */
public class ReportActivity extends AppCompatActivity {

    private static final String TAG = "Report";

    private static final SimpleDateFormat CLOCK_FORMAT =
            new SimpleDateFormat("h:mm a", Locale.getDefault());

    private static final String STATE_DAY_KEY = "day_key";
    private static final String STATE_WEEKLY = "weekly";

    @Nullable
    private ActivityReportBinding binding;

    @Nullable
    private Repository repository;

    /** The day being reported on. Never in the future. */
    @NonNull
    private String dayKey = TimeUtils.today();

    private boolean weeklyView;

    /** The last loaded data, retained so sharing does not need a second query. */
    @Nullable
    private Payload lastPayload;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivityReportBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        repository = Repository.get(getApplicationContext());

        ActivityReportBinding b = binding;
        if (b == null) return;

        if (savedInstanceState != null) {
            String restored = savedInstanceState.getString(STATE_DAY_KEY);
            if (!TextUtils.isEmpty(restored)) dayKey = restored;
            weeklyView = savedInstanceState.getBoolean(STATE_WEEKLY, false);
        } else if (getIntent() != null) {
            weeklyView = getIntent().getBooleanExtra(EXTRA_WEEKLY, false);
            // A notification for a specific day opens on that day rather than
            // today, so the report the user was just told about is the one they see.
            String requested = getIntent().getStringExtra(EXTRA_DAY_KEY);
            if (!TextUtils.isEmpty(requested)) dayKey = requested;
        }

        b.toolbar.setNavigationOnClickListener(v -> finish());
        b.btnPrevDay.setOnClickListener(v -> shiftDay(-1));
        b.btnNextDay.setOnClickListener(v -> shiftDay(1));
        b.btnToggleView.setOnClickListener(v -> {
            weeklyView = !weeklyView;
            load();
        });
        b.btnShare.setOnClickListener(v -> share());
    }

    @Override
    protected void onResume() {
        super.onResume();
        load();
    }

    @Override
    protected void onSaveInstanceState(@NonNull Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putString(STATE_DAY_KEY, dayKey);
        outState.putBoolean(STATE_WEEKLY, weeklyView);
    }

    /** Optional extra: open straight into the weekly report. */
    public static final String EXTRA_WEEKLY = "extra_weekly";

    /** Optional extra: open on this day key rather than today. */
    public static final String EXTRA_DAY_KEY = "extra_day_key";

    /** Intent for the daily report. */
    @NonNull
    public static Intent intent(@NonNull Context context) {
        return new Intent(context, ReportActivity.class);
    }

    /** Intent for the report of one specific day. */
    @NonNull
    public static Intent forDay(@NonNull Context context, @NonNull String dayKey) {
        return new Intent(context, ReportActivity.class).putExtra(EXTRA_DAY_KEY, dayKey);
    }

    /** Intent that opens directly on the weekly report. */
    @NonNull
    public static Intent weeklyIntent(@NonNull Context context) {
        return new Intent(context, ReportActivity.class).putExtra(EXTRA_WEEKLY, true);
    }

    private void shiftDay(int delta) {
        String next = TimeUtils.dayOffset(dayKey, delta);
        if (next == null) return;
        // Stepping into the future would report on a day that has not happened.
        if (TimeUtils.daysBetween(TimeUtils.today(), next) > 0) return;
        dayKey = next;
        load();
    }

    // ------------------------------------------------------------------
    //  Data
    // ------------------------------------------------------------------

    private static final class Payload {
        @Nullable
        DailySummaryEntity summary;
        @Nullable
        Repository.WeekData week;
        String dayKey = "";
        int achievedGoals;
        int totalGoals;
        long generatedAt;
    }

    private void load() {
        ActivityReportBinding b = binding;
        Repository repo = repository;
        if (b == null || repo == null) return;

        final String requestedDay = dayKey;
        final boolean requestedWeekly = weeklyView;

        AppDatabase.io().execute(() -> {
            Payload p;
            try {
                p = collect(repo, requestedDay, requestedWeekly);
            } catch (Exception e) {
                android.util.Log.e(TAG, "could not build the report", e);
                return;
            }
            runOnUiThread(() -> {
                ActivityReportBinding current = binding;
                if (current == null) return;
                // Ignore a result for a day the user has already navigated away
                // from; otherwise fast taps would render out of order.
                if (!requestedDay.equals(dayKey) || requestedWeekly != weeklyView) return;
                lastPayload = p;
                render(p);
            });
        });
    }

    @NonNull
    private Payload collect(@NonNull Repository repo, @NonNull String day, boolean weekly) {
        Payload p = new Payload();
        p.dayKey = day;

        // Regenerating is idempotent and preserves the notified flag, so opening
        // the report repeatedly cannot duplicate or re-announce anything.
        p.summary = repo.generateDailySummary(day);
        p.generatedAt = p.summary == null ? 0L : p.summary.generatedAt;

        p.totalGoals = repo.db().goalDao().all().size();
        p.achievedGoals = repo.db().goalDao().achievedCount();

        if (weekly) {
            p.week = repo.loadWeek();
        }
        return p;
    }

    // ------------------------------------------------------------------
    //  Rendering
    // ------------------------------------------------------------------

    private void render(@NonNull Payload p) {
        ActivityReportBinding b = binding;
        if (b == null) return;

        boolean weekly = weeklyView;
        b.toolbar.setTitle(weekly ? R.string.title_weekly_report : R.string.title_daily_report);
        b.btnToggleView.setText(weekly ? R.string.report_view_daily : R.string.report_view_weekly);
        b.dayNav.setVisibility(weekly ? View.GONE : View.VISIBLE);
        b.cardTrend.setVisibility(weekly ? View.VISIBLE : View.GONE);
        b.cardCharts.setVisibility(weekly ? View.VISIBLE : View.GONE);

        if (!weekly) {
            b.tvDayLabel.setText(TimeUtils.shortDate(p.dayKey));
            // The forward arrow is disabled, not hidden, on today: hiding it would
            // make the day label look unclickable rather than at its limit.
            b.btnNextDay.setEnabled(!TimeUtils.isToday(p.dayKey));
            b.btnNextDay.setAlpha(TimeUtils.isToday(p.dayKey) ? 0.35f : 1f);
        }

        if (p.summary == null) {
            renderEmpty(b);
            return;
        }

        renderScore(b, p);
        b.metricContainer.removeAllViews();
        if (weekly && p.week != null) {
            renderWeekly(b, p);
        } else {
            renderDaily(b, p);
        }
        renderImprovement(b, p);
        renderGeneratedAt(b, p);
    }

    private void renderEmpty(@NonNull ActivityReportBinding b) {
        b.ringScore.setProgress(0, false);
        b.ringScore.setTexts("0", getString(R.string.report_score));
        b.tvScoreCaption.setText(R.string.report_no_data);
        b.metricContainer.removeAllViews();
        b.tvImprovement.setText(R.string.report_improvement_none);
        b.tvGeneratedAt.setVisibility(View.GONE);
        b.cardTrend.setVisibility(View.GONE);
        b.cardCharts.setVisibility(View.GONE);
    }

    private void renderScore(@NonNull ActivityReportBinding b, @NonNull Payload p) {
        DailySummaryEntity s = p.summary;
        if (s == null) return;

        int score = clamp(s.focusScore);
        b.ringScore.setMax(100);
        b.ringScore.setProgress(score, true);
        b.ringScore.setTexts(String.valueOf(score), getString(R.string.report_score));
        b.ringScore.setRingColor(getColor(scoreColor(score)));

        if (weeklyView && p.week != null) {
            b.tvScoreCaption.setText(getString(R.string.report_weekly_avg_score)
                    + "  " + getString(R.string.separator_dot) + "  "
                    + p.week.avgScore);
        } else {
            if (s.streak > 1) {
                b.tvScoreCaption.setText(getString(R.string.streak_days, s.streak));
            } else if (s.streak == 1) {
                b.tvScoreCaption.setText(R.string.streak_days_one);
            } else {
                // Zero is not reported as a broken streak, because there is nothing
                // yet to break.
                b.tvScoreCaption.setText(R.string.streak_zero_hint);
            }
        }
    }

    private void renderDaily(@NonNull ActivityReportBinding b, @NonNull Payload p) {
        DailySummaryEntity s = p.summary;
        if (s == null) return;
        LayoutInflater inflater = LayoutInflater.from(this);

        addMetric(inflater, b, R.drawable.ic_book, R.string.report_total_study_time,
                TimeUtils.formatMinutes(s.studyMinutes), null, R.color.accent_500, -1);

        // An unmeasured day is not a zero day. This is the one metric the app may
        // genuinely be unable to produce, and saying so is the honest option.
        if (s.screenMinutes >= 0) {
            addMetric(inflater, b, R.drawable.ic_phone_android,
                    R.string.report_total_mobile_usage,
                    TimeUtils.formatMinutes(s.screenMinutes),
                    getString(R.string.report_screen_time), R.color.chart_2, -1);
        } else {
            addMetric(inflater, b, R.drawable.ic_phone_android,
                    R.string.report_total_mobile_usage,
                    getString(R.string.label_none),
                    getString(R.string.usage_no_permission), R.color.text_tertiary, -1);
        }

        addMetric(inflater, b, R.drawable.ic_timer, R.string.report_focus_sessions,
                String.valueOf(s.focusSessions),
                TimeUtils.formatMinutes(s.focusMinutes), R.color.chart_3, -1);

        addRunningMetric(inflater, b, s);

        addMetric(inflater, b, R.drawable.ic_check_circle, R.string.report_habits_completed,
                s.habitsDone + " / " + s.habitsTotal,
                percentLabel(s.habitsDone, s.habitsTotal), R.color.success_500,
                percentOf(s.habitsDone, s.habitsTotal));

        addMetric(inflater, b, R.drawable.ic_flag, R.string.report_goals_achieved,
                String.valueOf(p.achievedGoals),
                getString(R.string.report_goals_sub, p.achievedGoals, p.totalGoals),
                R.color.chart_4, percentOf(p.achievedGoals, p.totalGoals));

        addMetric(inflater, b, R.drawable.ic_shield, R.string.report_distractions_blocked,
                String.valueOf(s.blockHits),
                s.distractionMinutesLost > 0
                        ? TimeUtils.formatMinutes(s.distractionMinutesLost) : null,
                R.color.info_500, -1);

        addMetric(inflater, b, R.drawable.ic_assessment, R.string.report_summary,
                s.tasksDone + " / " + s.tasksTotal,
                getString(R.string.label_today), R.color.chart_5,
                percentOf(s.tasksDone, s.tasksTotal));
    }

    /**
     * Running, reported as completed, missed, or a rest day.
     *
     * <p>A rest day shows as a completed plan rather than a miss. Treating a
     * deliberate rest as a failure would be the same shaming the habit tracker is
     * built to avoid.</p>
     */
    private void addRunningMetric(@NonNull LayoutInflater inflater,
                                  @NonNull ActivityReportBinding b,
                                  @NonNull DailySummaryEntity s) {
        @StringRes int label;
        @NonNull String sub;
        int color;

        if (s.runDistanceM > 0 || s.runDone) {
            label = R.string.report_running_completed;
            sub = TimeUtils.formatDistance(s.runDistanceM);
            color = R.color.success_500;
        } else if (s.workoutsDone > 0) {
            // Exercise was done even though the run target was not: still a
            // completed day, not a miss.
            label = R.string.report_running_completed;
            sub = getString(R.string.fitness_workouts_done, s.workoutsDone,
                    Prefs.targetWorkouts(this));
            color = R.color.success_500;
        } else if (isRestDay(s)) {
            label = R.string.report_running_rest;
            sub = getString(R.string.fitness_rest_day_desc);
            color = R.color.info_500;
        } else {
            label = R.string.report_running_missed;
            sub = getString(R.string.label_none);
            color = R.color.text_tertiary;
        }

        // report_running_completed carries the distance as an argument, so the
        // value cell takes the formatted string and the label stays plain.
        String value = label == R.string.report_running_completed
                ? getString(R.string.report_running_completed, sub)
                : getString(label);

        addMetric(inflater, b, R.drawable.ic_directions_run, R.string.report_running,
                value, null, color, -1);
    }

    /**
     * Whether this summary describes a planned rest day.
     *
     * <p>{@link Prefs#isRestDayToday} only knows about
     * today, so a historical day is inferred: no run, no workout and no distance
     * recorded, while the fitness score component still awarded full weight. The
     * summary row itself is the record, which is why the inference is not needed
     * for today.</p>
     */
    private boolean isRestDay(@NonNull DailySummaryEntity s) {
        if (TimeUtils.isToday(s.dayKey)) {
            return Prefs.isRestDayToday(this);
        }
        return s.runDistanceM == 0 && s.runMinutes == 0
                && s.workoutsDone == 0 && !s.runDone;
    }

    private void renderWeekly(@NonNull ActivityReportBinding b, @NonNull Payload p) {
        Repository.WeekData w = p.week;
        if (w == null) return;
        LayoutInflater inflater = LayoutInflater.from(this);

        switch (w.trend) {
            case Repository.TREND_IMPROVING:
                b.tvTrend.setText(R.string.report_trend_improving);
                b.tvTrend.setTextColor(getColor(R.color.success_500));
                break;
            case Repository.TREND_DECLINING:
                b.tvTrend.setText(R.string.report_trend_declining);
                b.tvTrend.setTextColor(getColor(R.color.warning_500));
                break;
            default:
                b.tvTrend.setText(R.string.report_trend_flat);
                b.tvTrend.setTextColor(getColor(R.color.text_primary));
                break;
        }

        addMetric(inflater, b, R.drawable.ic_insights, R.string.report_weekly_avg_score,
                String.valueOf(w.avgScore),
                w.bestScore + " " + getString(R.string.report_weekly_best_day),
                R.color.accent_500, clamp(w.avgScore));

        addMetric(inflater, b, R.drawable.ic_star, R.string.report_weekly_best_day,
                w.bestDayKey == null ? getString(R.string.label_none)
                        : TimeUtils.shortDate(w.bestDayKey),
                String.valueOf(w.bestScore), R.color.success_500, clamp(w.bestScore));

        addMetric(inflater, b, R.drawable.ic_local_fire, R.string.report_weekly_worst_day,
                w.worstDayKey == null ? getString(R.string.label_none)
                        : TimeUtils.shortDate(w.worstDayKey),
                String.valueOf(w.worstScore), R.color.chart_4, clamp(w.worstScore));

        addMetric(inflater, b, R.drawable.ic_book, R.string.report_weekly_study_total,
                TimeUtils.formatMinutes(w.studyMinutes),
                TimeUtils.formatMinutes(w.focusMinutes) + " "
                        + getString(R.string.title_focus),
                R.color.chart_3, -1);

        addMetric(inflater, b, R.drawable.ic_phone_android, R.string.report_weekly_screen_total,
                TimeUtils.formatMinutes(w.screenMinutes), null, R.color.chart_2, -1);

        addMetric(inflater, b, R.drawable.ic_directions_run, R.string.report_running,
                TimeUtils.formatDistance(w.runDistanceM),
                w.runDays + " / " + w.dayKeys.size(), R.color.success_500, -1);

        addMetric(inflater, b, R.drawable.ic_check_circle, R.string.report_habits_completed,
                w.habitsDone + " / " + w.habitsPossible,
                percentLabel(w.habitsDone, w.habitsPossible), R.color.success_500,
                percentOf(w.habitsDone, w.habitsPossible));

        addMetric(inflater, b, R.drawable.ic_shield, R.string.report_distractions_blocked,
                String.valueOf(w.blockHits), null, R.color.info_500, -1);

        renderCharts(b, w);
    }

    private void renderCharts(@NonNull ActivityReportBinding b,
                              @NonNull Repository.WeekData w) {
        if (w.dailyScores.length > 0) {
            b.chartScores.setVisibility(View.VISIBLE);
            b.chartScores.setWeekData(w.dailyScores, w.dayKeys);
            b.chartScores.setHighlightLast(true);
            b.chartScores.setTargetPercent(PREFS_STREAK_SCORE);
        } else {
            b.chartScores.setVisibility(View.GONE);
        }

        if (w.dailyStudy.length > 0) {
            b.chartStudy.setVisibility(View.VISIBLE);
            b.chartStudy.setWeekData(w.dailyStudy, w.dayKeys);
            b.chartStudy.setValueSuffix(getString(R.string.label_minutes_short));
            b.chartStudy.setHighlightLast(true);
        } else {
            b.chartStudy.setVisibility(View.GONE);
        }

        int[] habitPercents = habitPercentsFor(w);
        if (habitPercents.length > 0) {
            b.stripHabits.setVisibility(View.VISIBLE);
            b.stripHabits.setData(habitPercents, w.dayKeys);
        } else {
            b.stripHabits.setVisibility(View.GONE);
        }
    }

    /** The streak threshold, used as the chart's target line. */
    private static final int PREFS_STREAK_SCORE = 55;

    @NonNull
    private int[] habitPercentsFor(@NonNull Repository.WeekData w) {
        // The week payload carries totals, not per-day habit counts, so the strip
        // is driven from the summaries the week was built from.
        List<String> keys = w.dayKeys;
        int[] out = new int[keys.size()];
        Repository repo = repository;
        if (repo == null) return out;
        for (int i = 0; i < keys.size(); i++) {
            DailySummaryEntity s = repo.db().usageDao().findSummary(keys.get(i));
            out[i] = s == null ? 0 : percentOf(s.habitsDone, s.habitsTotal);
        }
        return out;
    }

    private void renderImprovement(@NonNull ActivityReportBinding b, @NonNull Payload p) {
        DailySummaryEntity s = p.summary;
        if (s == null) return;

        String notes = s.improvementNotes;
        boolean empty = TextUtils.isEmpty(notes) || notes.trim().isEmpty();
        b.tvImprovement.setText(empty
                ? getString(R.string.report_improvement_none) : notes);
        b.tvImprovement.setTextColor(getColor(empty
                ? R.color.success_500 : R.color.text_secondary));
    }

    private void renderGeneratedAt(@NonNull ActivityReportBinding b, @NonNull Payload p) {
        if (p.generatedAt > 0) {
            b.tvGeneratedAt.setVisibility(View.VISIBLE);
            b.tvGeneratedAt.setText(getString(R.string.report_generated_at,
                    CLOCK_FORMAT.format(new Date(p.generatedAt))));
        } else {
            b.tvGeneratedAt.setVisibility(View.GONE);
        }
    }

    /**
     * Appends one metric row.
     *
     * @param sub     optional caption; hidden when null
     * @param percent 0-100 to draw the thin bar, or -1 to hide it
     */
    private void addMetric(@NonNull LayoutInflater inflater, @NonNull ActivityReportBinding b,
                           int iconRes, @StringRes int labelRes, @NonNull String value,
                           @Nullable String sub, int colorRes, int percent) {
        ItemMetricCardBinding row = ItemMetricCardBinding
                .inflate(inflater, b.metricContainer, false);

        row.ivIcon.setImageResource(iconRes);
        row.ivIcon.setImageTintList(ColorStateList.valueOf(getColor(colorRes)));
        row.tvLabel.setText(labelRes);
        row.tvValue.setText(value);
        row.tvValue.setTextColor(getColor(R.color.text_primary));

        if (TextUtils.isEmpty(sub)) {
            row.tvSub.setVisibility(View.GONE);
        } else {
            row.tvSub.setVisibility(View.VISIBLE);
            row.tvSub.setText(sub);
        }

        if (percent < 0) {
            row.pbMetric.setVisibility(View.GONE);
        } else {
            row.pbMetric.setVisibility(View.VISIBLE);
            row.pbMetric.setProgress(clamp(percent));
            row.pbMetric.getProgressDrawable().setTint(getColor(colorRes));
        }

        b.metricContainer.addView(row.getRoot());
    }

    // ------------------------------------------------------------------
    //  Sharing
    // ------------------------------------------------------------------

    /**
     * Shares the report as plain text.
     *
     * <p>Sharing is the only way any of this data leaves the app, and it is always
     * a deliberate tap that hands the text to another app the user chooses. The
     * project declares no {@code INTERNET} permission, so nothing here can upload
     * anything on its own.</p>
     */
    private void share() {
        Payload p = lastPayload;
        if (p == null || p.summary == null) {
            showMessage(getString(R.string.report_no_data));
            return;
        }

        String text = weeklyView ? buildWeeklyText(p) : buildDailyText(p);
        Intent send = new Intent(Intent.ACTION_SEND)
                .setType("text/plain")
                .putExtra(Intent.EXTRA_SUBJECT, getString(R.string.report_share_subject))
                .putExtra(Intent.EXTRA_TEXT, text);

        try {
            startActivity(Intent.createChooser(send, getString(R.string.action_share)));
            showMessage(getString(R.string.report_shared));
        } catch (ActivityNotFoundException e) {
            showMessage(getString(R.string.report_no_app_to_share));
        }
    }

    @NonNull
    private String buildDailyText(@NonNull Payload p) {
        DailySummaryEntity s = p.summary;
        StringBuilder sb = new StringBuilder();
        sb.append(getString(R.string.title_daily_report)).append(" — ")
                .append(TimeUtils.shortDate(p.dayKey)).append("\n\n");
        if (s == null) return sb.toString();

        appendLine(sb, R.string.report_score, String.valueOf(clamp(s.focusScore)));
        appendLine(sb, R.string.report_total_study_time,
                TimeUtils.formatMinutes(s.studyMinutes));
        appendLine(sb, R.string.report_total_mobile_usage, s.screenMinutes >= 0
                ? TimeUtils.formatMinutes(s.screenMinutes)
                : getString(R.string.label_none));
        appendLine(sb, R.string.report_focus_sessions, String.valueOf(s.focusSessions));
        appendLine(sb, R.string.report_running, TimeUtils.formatDistance(s.runDistanceM));
        appendLine(sb, R.string.report_habits_completed, s.habitsDone + "/" + s.habitsTotal);
        appendLine(sb, R.string.report_goals_achieved, String.valueOf(p.achievedGoals));
        appendLine(sb, R.string.report_distractions_blocked, String.valueOf(s.blockHits));

        if (!TextUtils.isEmpty(s.improvementNotes)) {
            sb.append('\n').append(getString(R.string.report_improvement)).append('\n')
                    .append(s.improvementNotes).append('\n');
        }
        return sb.toString();
    }

    @NonNull
    private String buildWeeklyText(@NonNull Payload p) {
        Repository.WeekData w = p.week;
        StringBuilder sb = new StringBuilder();
        sb.append(getString(R.string.title_weekly_report)).append("\n\n");
        if (w == null) return sb.toString();

        appendLine(sb, R.string.report_weekly_avg_score, String.valueOf(w.avgScore));
        appendLine(sb, R.string.report_weekly_study_total,
                TimeUtils.formatMinutes(w.studyMinutes));
        appendLine(sb, R.string.report_weekly_screen_total,
                TimeUtils.formatMinutes(w.screenMinutes));
        appendLine(sb, R.string.report_running, TimeUtils.formatDistance(w.runDistanceM));
        appendLine(sb, R.string.report_habits_completed,
                w.habitsDone + "/" + w.habitsPossible);
        appendLine(sb, R.string.report_distractions_blocked, String.valueOf(w.blockHits));

        sb.append('\n').append(getString(R.string.report_weekly_trend)).append(": ");
        switch (w.trend) {
            case Repository.TREND_IMPROVING:
                sb.append(getString(R.string.report_trend_improving));
                break;
            case Repository.TREND_DECLINING:
                sb.append(getString(R.string.report_trend_declining));
                break;
            default:
                sb.append(getString(R.string.report_trend_flat));
                break;
        }
        sb.append('\n');
        return sb.toString();
    }

    private void appendLine(@NonNull StringBuilder sb, @StringRes int labelRes,
                            @NonNull String value) {
        sb.append(getString(labelRes)).append(": ").append(value).append('\n');
    }

    // ------------------------------------------------------------------
    //  Small helpers
    // ------------------------------------------------------------------

    @NonNull
    private String percentLabel(int done, int total) {
        return total > 0
                ? getString(R.string.motivation_progress_percent, percentOf(done, total))
                : getString(R.string.label_empty_no_data);
    }

    private static int percentOf(int done, int total) {
        if (total <= 0) return 0;
        return clamp(Math.round(done * 100f / total));
    }

    private static int clamp(int value) {
        return Math.max(0, Math.min(100, value));
    }

    private static int scoreColor(int score) {
        if (score >= 70) return R.color.success_500;
        if (score >= 40) return R.color.accent_500;
        return R.color.chart_4;
    }

    private void showMessage(@NonNull String message) {
        ActivityReportBinding b = binding;
        if (b == null) return;
        Snackbar.make(b.getRoot(), message, Snackbar.LENGTH_SHORT).show();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        binding = null;
    }
}
