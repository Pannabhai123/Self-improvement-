package com.focuslife.app.ui.motivation;

import android.app.DatePickerDialog;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import com.focuslife.app.R;
import com.focuslife.app.data.db.AppDatabase;
import com.focuslife.app.data.db.entity.DailySummaryEntity;
import com.focuslife.app.data.db.entity.GoalEntity;
import com.focuslife.app.data.prefs.Prefs;
import com.focuslife.app.data.repo.Repository;
import com.focuslife.app.databinding.ActivityMotivationBinding;
import com.focuslife.app.databinding.DialogGoalBinding;
import com.focuslife.app.databinding.ItemGoalBinding;
import com.focuslife.app.databinding.ItemMilestoneBinding;
import com.focuslife.app.stats.FocusScoreCalculator;
import com.focuslife.app.util.TimeUtils;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.snackbar.Snackbar;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Motivation — brief section 10: daily quotes, personal goals, vision board,
 * discipline reminders and milestones.
 *
 * <h2>Tone</h2>
 * The discipline line reports what has been kept rather than what has been
 * missed, and there is no wording anywhere in this screen that treats an empty
 * record as a failure. A streak of zero is described as "not started yet", which
 * is accurate and is also the only framing that does not punish a new user on
 * day one.
 *
 * <h2>Quotes</h2>
 * The daily quote is the same deterministic per-day pick the dashboard uses
 * ({@link Repository#quoteIndexForDay}), so the two screens can never show
 * different lines for the same day. "New quote" advances past today's pick
 * without changing what the dashboard shows.
 */
public class MotivationActivity extends AppCompatActivity {

    private static final String TAG = "Motivation";

    private static final SimpleDateFormat DAY_FORMAT =
            new SimpleDateFormat("d MMM", Locale.getDefault());

    /** How many daily summaries to read when evaluating milestones. */
    private static final int MILESTONE_HISTORY_DAYS = 400;

    @Nullable
    private ActivityMotivationBinding binding;

    @Nullable
    private Repository repository;

    /** How far past today's deterministic quote the user has advanced. */
    private int quoteOffset;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivityMotivationBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        repository = Repository.get(getApplicationContext());

        ActivityMotivationBinding b = binding;
        if (b == null) return;

        // Restore an advance made earlier today, so leaving and returning does not
        // snap the quote back to the day's default.
        int saved = Prefs.lastQuoteIndex(this);
        if (saved >= 0) {
            quoteOffset = offsetOf(saved);
        }

        b.toolbar.setNavigationOnClickListener(v -> finish());
        b.btnNewQuote.setOnClickListener(v -> {
            // Cycle through the rotation, skipping the line already on screen so
            // the tap always produces a visibly different quote.
            quoteOffset = (quoteOffset + 1) % Math.max(1, Repository.QUOTE_RES.length);
            Prefs.setLastQuoteIndex(this, currentQuoteIndex());
            renderQuote(b);
        });
        b.btnAddGoal.setOnClickListener(v -> showGoalEditor(null, GoalEntity.KIND_GOAL));
        b.btnAddVision.setOnClickListener(v -> showGoalEditor(null, GoalEntity.KIND_VISION));
    }

    @Override
    protected void onResume() {
        super.onResume();
        load();
    }

    private int currentQuoteIndex() {
        int base = Repository.quoteIndexForToday();
        int count = Repository.QUOTE_RES.length;
        // Advance within the rotation; never index past the end.
        return ((base + quoteOffset) % count + count) % count;
    }

    /** The offset that produces {@code index} from today's base quote. */
    private int offsetOf(int index) {
        int count = Repository.QUOTE_RES.length;
        if (count <= 0) return 0;
        return ((index - Repository.quoteIndexForToday()) % count + count) % count;
    }

    // ------------------------------------------------------------------
    //  Data
    // ------------------------------------------------------------------

    private void load() {
        ActivityMotivationBinding b = binding;
        Repository repo = repository;
        if (b == null || repo == null) return;

        AppDatabase.io().execute(() -> {
            Payload p;
            try {
                p = collect(repo);
            } catch (Exception e) {
                android.util.Log.e(TAG, "could not load motivation data", e);
                return;
            }
            runOnUiThread(() -> {
                ActivityMotivationBinding current = binding;
                if (current == null) return;
                render(p);
            });
        });
    }

    private static final class Payload {
        List<GoalEntity> goals = new ArrayList<>();
        List<GoalEntity> visions = new ArrayList<>();
        List<MilestoneTracker.Milestone> milestones = new ArrayList<>();
        Repository.LifetimeStats lifetime = new Repository.LifetimeStats();
        int todayScore;
        int currentStreak;
    }

    @NonNull
    private Payload collect(@NonNull Repository repo) {
        Payload p = new Payload();

        List<GoalEntity> all = repo.db().goalDao().all();
        if (all != null) {
            for (GoalEntity goal : all) {
                if (goal == null) continue;
                if (GoalEntity.KIND_VISION.equals(goal.kind)) p.visions.add(goal);
                else p.goals.add(goal);
            }
        }

        p.lifetime = repo.loadLifetimeStats();
        p.currentStreak = p.lifetime.currentStreak;

        List<DailySummaryEntity> summaries =
                repo.db().usageDao().recentSummaries(MILESTONE_HISTORY_DAYS);
        p.milestones = MilestoneTracker.evaluate(
                p.lifetime, summaries == null ? new ArrayList<>() : summaries,
                repo.db().fitnessDao().best1600Sec());

        Repository.DashboardData today = repo.loadDashboard();
        p.todayScore = today == null || today.score == null ? 0 : today.score.score;
        return p;
    }

    private void render(@NonNull Payload p) {
        ActivityMotivationBinding b = binding;
        if (b == null) return;

        renderQuote(b);
        renderDiscipline(b, p);
        renderGoals(b, p.visions, true);
        renderGoals(b, p.goals, false);
        renderMilestones(b, p.milestones);
        renderLifetime(b, p.lifetime);
    }

    private void renderQuote(@NonNull ActivityMotivationBinding b) {
        int index = currentQuoteIndex();
        if (index < 0 || index >= Repository.QUOTE_RES.length) index = 0;
        b.tvQuote.setText(Repository.QUOTE_RES[index]);
    }

    /**
     * The discipline reminder.
     *
     * <p>Three cases, all of which describe something true and forward-looking:
     * a streak in progress, a streak not yet started, and today's score. Nothing
     * here compares the user to anyone else or to their own best.</p>
     */
    private void renderDiscipline(@NonNull ActivityMotivationBinding b, @NonNull Payload p) {
        if (p.currentStreak > 0) {
            b.tvDisciplineLine.setText(getString(R.string.motivation_discipline_streak,
                    p.currentStreak));
        } else {
            b.tvDisciplineLine.setText(getString(R.string.motivation_discipline_no_streak)
                    + " " + getString(R.string.motivation_discipline_score, p.todayScore));
        }
    }

    private void renderGoals(@NonNull ActivityMotivationBinding b,
                             @NonNull List<GoalEntity> goals, boolean isVision) {
        ViewGroup container = isVision ? b.visionContainer : b.goalContainer;
        container.removeAllViews();

        if (!isVision) {
            b.tvNoGoals.setVisibility(goals.isEmpty() ? View.VISIBLE : View.GONE);
        }
        if (goals.isEmpty()) return;

        LayoutInflater inflater = LayoutInflater.from(this);
        for (GoalEntity goal : goals) {
            ItemGoalBinding row = ItemGoalBinding.inflate(inflater, container, false);

            row.tvGoalTitle.setText(goal.title);
            row.tvGoalDetail.setText(goal.detail);
            row.tvGoalDetail.setVisibility(TextUtils.isEmpty(goal.detail)
                    ? View.GONE : View.VISIBLE);

            int progress = goal.achieved ? 100
                    : FocusScoreCalculator.clamp(goal.progress, 0, 100);
            row.pbGoal.setProgress(progress);
            row.pbGoal.getProgressDrawable().setTint(getColor(goal.achieved
                    ? R.color.success_500 : R.color.accent_500));

            row.ivGoalState.setImageResource(goal.achieved
                    ? R.drawable.ic_check_circle_filled : R.drawable.ic_target);
            row.ivGoalState.setImageTintList(android.content.res.ColorStateList.valueOf(
                    getColor(goal.achieved ? R.color.success_500 : R.color.accent_500)));

            row.tvGoalMeta.setText(goalMeta(goal, progress));
            row.tvGoalMeta.setVisibility(goal.achieved || isVision ? View.GONE : View.VISIBLE);

            row.getRoot().setOnClickListener(v -> {
                if (goal.achieved) {
                    showGoalEditor(goal, goal.kind);
                } else {
                    // Tapping a pending goal offers to mark it done, which is the
                    // action most likely to be wanted and saves opening the editor.
                    new MaterialAlertDialogBuilder(this)
                            .setTitle(goal.title)
                            .setItems(new String[]{
                                            getString(R.string.motivation_mark_done),
                                            getString(R.string.action_edit)
                                    },
                                    (dialog, which) -> {
                                        if (which == 0) markAchieved(goal);
                                        else showGoalEditor(goal, goal.kind);
                                    })
                            .show();
                }
            });
            row.btnGoalDelete.setOnClickListener(v -> confirmDeleteGoal(goal));

            container.addView(row.getRoot());
        }
    }

    @NonNull
    private String goalMeta(@NonNull GoalEntity goal, int progress) {
        StringBuilder sb = new StringBuilder();
        sb.append(getString(R.string.motivation_progress_percent, progress));
        if (goal.targetDate > 0) {
            long days = TimeUtils.daysBetween(TimeUtils.today(),
                    TimeUtils.dayKey(goal.targetDate));
            sb.append("  ").append(getString(R.string.separator_dot)).append("  ");
            // An overdue goal keeps its encouraging wording on purpose: the
            // string reads "past its date — still worth doing", and there is no
            // version of this screen where a slipped date should be announced as
            // a failure.
            sb.append(days >= 0
                    ? getString(R.string.motivation_days_until, days)
                    : getString(R.string.motivation_overdue));
        }
        return sb.toString();
    }

    private void renderMilestones(@NonNull ActivityMotivationBinding b,
                                  @NonNull List<MilestoneTracker.Milestone> milestones) {
        b.milestoneContainer.removeAllViews();

        // Reached milestones first. The section is a record of what has been
        // done, and burying the achievements under the pending ones would make it
        // read as a list of things not yet achieved.
        List<MilestoneTracker.Milestone> ordered = new ArrayList<>(milestones);
        java.util.Collections.sort(ordered, (x, y) -> Boolean.compare(y.reached, x.reached));

        int reached = MilestoneTracker.reachedCount(milestones);
        b.tvNoMilestones.setVisibility(reached == 0 ? View.VISIBLE : View.GONE);

        LayoutInflater inflater = LayoutInflater.from(this);
        for (MilestoneTracker.Milestone milestone : ordered) {
            ItemMilestoneBinding row = ItemMilestoneBinding
                    .inflate(inflater, b.milestoneContainer, false);

            row.tvMilestoneText.setText(milestone.labelRes);
            row.ivMilestoneState.setImageResource(milestone.reached
                    ? R.drawable.ic_check_circle_filled : R.drawable.ic_radio_unchecked);
            row.ivMilestoneState.setImageTintList(android.content.res.ColorStateList.valueOf(
                    getColor(milestone.reached ? R.color.success_500 : R.color.text_tertiary)));

            row.tvMilestoneText.setTextColor(getColor(milestone.reached
                    ? R.color.text_primary : R.color.text_tertiary));
            row.tvMilestoneDate.setVisibility(View.GONE);

            b.milestoneContainer.addView(row.getRoot());
        }
    }

    private void renderLifetime(@NonNull ActivityMotivationBinding b,
                                @NonNull Repository.LifetimeStats s) {
        b.lifetimeContainer.removeAllViews();

        List<String> lines = new ArrayList<>(8);
        if (s.totalStudyMinutes > 0) {
            lines.add(getString(R.string.motivation_lifetime_study,
                    TimeUtils.formatMinutes(s.totalStudyMinutes)));
        }
        if (s.totalFocusMinutes > 0) {
            lines.add(getString(R.string.motivation_lifetime_focus,
                    TimeUtils.formatMinutes(s.totalFocusMinutes)));
        }
        if (s.totalSessions > 0) {
            lines.add(getString(R.string.motivation_lifetime_sessions, s.totalSessions));
        }
        if (s.totalDistanceM > 0) {
            lines.add(getString(R.string.motivation_lifetime_distance,
                    TimeUtils.formatDistance(s.totalDistanceM)));
        }
        if (s.totalBlockHits > 0) {
            lines.add(getString(R.string.motivation_lifetime_blocks, s.totalBlockHits));
        }
        if (s.totalTopics > 0) {
            lines.add(getString(R.string.motivation_lifetime_topics,
                    s.completedTopics, s.totalTopics));
        }
        if (s.achievedGoals > 0) {
            lines.add(getString(R.string.motivation_lifetime_goals, s.achievedGoals));
        }
        if (s.bestStreak > 0) {
            lines.add(getString(R.string.motivation_lifetime_best_streak, s.bestStreak));
        }

        if (lines.isEmpty()) {
            addLifetimeLine(b, getString(R.string.label_empty_no_data), false);
            return;
        }
        for (String line : lines) {
            addLifetimeLine(b, line, true);
        }
    }

    private void addLifetimeLine(@NonNull ActivityMotivationBinding b,
                                 @NonNull String line, boolean emphasise) {
        android.widget.TextView view = new android.widget.TextView(this);
        view.setText(line);
        view.setTextAppearance(emphasise
                ? R.style.TextAppearance_FocusLife_Body_Secondary
                : R.style.TextAppearance_FocusLife_Caption);
        view.setPadding(0, getResources().getDimensionPixelSize(R.dimen.space_xs), 0,
                getResources().getDimensionPixelSize(R.dimen.space_xs));
        b.lifetimeContainer.addView(view);
    }

    // ------------------------------------------------------------------
    //  Editing
    // ------------------------------------------------------------------

    private void showGoalEditor(@Nullable GoalEntity existing, @NonNull String kind) {
        DialogGoalBinding d = DialogGoalBinding.inflate(LayoutInflater.from(this));

        boolean isVision = GoalEntity.KIND_VISION.equals(kind);
        d.fieldTitle.setHint(isVision
                ? R.string.motivation_vision_hint : R.string.motivation_goal_hint);

        final long[] targetDate = {existing == null ? 0L : existing.targetDate};
        d.sliderProgress.setValue(existing == null ? 0
                : FocusScoreCalculator.clamp(existing.progress, 0, 100));
        updateDateButton(d, targetDate[0]);
        d.btnTargetDate.setOnClickListener(v -> pickDate(targetDate[0], picked -> {
            targetDate[0] = picked;
            updateDateButton(d, picked);
        }));

        if (existing != null) {
            d.etTitle.setText(existing.title);
            d.etDetail.setText(existing.detail);
        }

        new MaterialAlertDialogBuilder(this)
                .setTitle(existing == null
                        ? (isVision ? R.string.motivation_add_vision : R.string.motivation_add_goal)
                        : R.string.action_edit)
                .setView(d.getRoot())
                .setNegativeButton(R.string.action_cancel, null)
                .setPositiveButton(R.string.action_save, (dialog, which) -> {
                    String title = d.etTitle.getText() == null
                            ? "" : d.etTitle.getText().toString().trim();
                    if (TextUtils.isEmpty(title)) {
                        showMessage(getString(R.string.validation_required));
                        return;
                    }
                    String detail = d.etDetail.getText() == null
                            ? "" : d.etDetail.getText().toString().trim();
                    saveGoal(existing, kind, title, detail,
                            (int) d.sliderProgress.getValue(), targetDate[0]);
                })
                .show();
    }

    private void updateDateButton(@NonNull DialogGoalBinding d, long targetDate) {
        d.btnTargetDate.setText(targetDate > 0
                ? DAY_FORMAT.format(new Date(targetDate))
                : getString(R.string.motivation_target_date));
    }

    private interface OnDatePicked {
        void onPicked(long millis);
    }

    private void pickDate(long current, @NonNull OnDatePicked onPicked) {
        Calendar cal = Calendar.getInstance();
        if (current > 0) cal.setTimeInMillis(current);
        new DatePickerDialog(this, R.style.Theme_FocusLife,
                (view, year, month, day) -> {
                    Calendar picked = Calendar.getInstance();
                    picked.set(year, month, day, 23, 59, 59);
                    onPicked.onPicked(picked.getTimeInMillis());
                },
                cal.get(Calendar.YEAR), cal.get(Calendar.MONTH), cal.get(Calendar.DAY_OF_MONTH))
                .show();
    }

    private void saveGoal(@Nullable GoalEntity existing, @NonNull String kind,
                          @NonNull String title, @NonNull String detail,
                          int progress, long targetDate) {
        Repository repo = repository;
        if (repo == null) return;

        AppDatabase.io().execute(() -> {
            try {
                GoalEntity goal = existing != null ? existing : new GoalEntity();
                goal.title = title;
                goal.detail = detail;
                goal.kind = kind;
                goal.progress = FocusScoreCalculator.clamp(progress, 0, 100);
                goal.targetDate = targetDate;
                goal.achieved = goal.progress >= 100;
                if (existing == null) {
                    goal.createdAt = System.currentTimeMillis();
                    goal.sortOrder = repo.db().goalDao().all().size();
                    repo.db().goalDao().insert(goal);
                } else {
                    repo.db().goalDao().update(goal);
                }
            } catch (Exception e) {
                android.util.Log.e(TAG, "could not save the goal", e);
            }
            runOnUiThread(() -> {
                showMessage(getString(R.string.motivation_goal_saved));
                load();
            });
        });
    }

    private void markAchieved(@NonNull GoalEntity goal) {
        Repository repo = repository;
        if (repo == null) return;

        AppDatabase.io().execute(() -> {
            try {
                repo.db().goalDao().markAchieved(goal.id);
            } catch (Exception e) {
                android.util.Log.e(TAG, "could not mark the goal achieved", e);
            }
            runOnUiThread(() -> {
                showMessage(getString(R.string.motivation_goal_achieved_toast, goal.title));
                load();
            });
        });
    }

    private void confirmDeleteGoal(@NonNull GoalEntity goal) {
        Repository repo = repository;
        if (repo == null) return;

        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.action_delete)
                .setMessage(goal.title)
                .setNegativeButton(R.string.action_cancel, null)
                .setPositiveButton(R.string.action_delete, (dialog, which) ->
                        AppDatabase.io().execute(() -> {
                            try {
                                repo.db().goalDao().delete(goal);
                            } catch (Exception e) {
                                android.util.Log.e(TAG, "could not delete the goal", e);
                            }
                            runOnUiThread(() -> {
                                showMessage(getString(R.string.motivation_goal_deleted));
                                load();
                            });
                        }))
                .show();
    }

    private void showMessage(@NonNull String message) {
        ActivityMotivationBinding b = binding;
        if (b == null) return;
        Snackbar.make(b.getRoot(), message, Snackbar.LENGTH_SHORT).show();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        binding = null;
    }
}
