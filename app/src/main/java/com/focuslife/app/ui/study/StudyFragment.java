package com.focuslife.app.ui.study;

import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;

import com.focuslife.app.R;
import com.focuslife.app.data.db.AppDatabase;
import com.focuslife.app.data.db.dao.FocusSessionDao;
import com.focuslife.app.data.db.entity.StudyLogEntity;
import com.focuslife.app.data.db.entity.StudyTopicEntity;
import com.focuslife.app.data.prefs.Prefs;
import com.focuslife.app.data.repo.Repository;
import com.focuslife.app.databinding.DialogStudyManualBinding;
import com.focuslife.app.databinding.DialogTopicEditBinding;
import com.focuslife.app.databinding.FragmentStudyBinding;
import com.focuslife.app.stats.FocusScoreCalculator;
import com.focuslife.app.ui.MainActivity;
import com.focuslife.app.ui.focus.FocusTimerActivity;
import com.focuslife.app.util.RevisionSchedule;
import com.focuslife.app.util.TimeUtils;
import com.google.android.material.chip.Chip;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.snackbar.Snackbar;

import java.util.ArrayList;
import java.util.List;

/**
 * The study planner: daily target, timed study, chapter-wise topics, revision
 * scheduling and weekly statistics.
 *
 * <h2>Why the timer lives here and not in {@link FocusTimerActivity}</h2>
 * The focus timer measures a bounded session with breaks. Study time is
 * open-ended — a student sits down for "Mathematics" and stops when the chapter
 * is done. Running that on a Pomodoro clock would cut real work short, so the
 * study timer is a simple stopwatch that credits a subject and optionally a
 * topic. Both write to their own tables and both count toward the Focus Score.
 *
 * <h2>Revision</h2>
 * Intervals are the expanding 1/3/7/15/30-day schedule in
 * {@link RevisionSchedule}. Marking a topic complete schedules the first pass;
 * each revision moves it further out. Nothing is ever removed from the schedule,
 * because a topic that leaves the cycle is a topic that gets forgotten.
 */
public class StudyFragment extends Fragment implements MainActivity.Refreshable,
        MainActivity.ScrollToTop {

    private static final int TIMER_TICK_MS = 1_000;

    @Nullable
    private FragmentStudyBinding binding;

    @Nullable
    private StudyTopicAdapter adapter;

    @Nullable
    private Repository repository;

    /** Currently selected filters; null means "all". */
    @Nullable
    private String filterExam;

    @Nullable
    private String filterSubject;

    @Nullable
    private Handler timerHandler;

    @Nullable
    private Runnable timerTick;

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        repository = Repository.get(requireContext().getApplicationContext());
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        binding = FragmentStudyBinding.inflate(inflater, container, false);
        return binding.getRoot();
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        FragmentStudyBinding b = binding;
        if (b == null) return;

        adapter = new StudyTopicAdapter();
        adapter.setListener(new StudyTopicAdapter.OnTopicAction() {
            @Override
            public void onOpen(@NonNull StudyTopicEntity topic) {
                showTopicEditor(topic);
            }

            @Override
            public void onEdit(@NonNull StudyTopicEntity topic) {
                showTopicEditor(topic);
            }

            @Override
            public void onToggleComplete(@NonNull StudyTopicEntity topic) {
                toggleTopicComplete(topic);
            }

            @Override
            public void onRevise(@NonNull StudyTopicEntity topic) {
                markRevised(topic);
            }
        });

        b.rvTopics.setLayoutManager(new LinearLayoutManager(requireContext()));
        b.rvTopics.setAdapter(adapter);
        b.rvTopics.setNestedScrollingEnabled(false);

        buildFilterChips(b);

        b.btnStudyTimer.setOnClickListener(v -> onTimerButton());
        b.btnAddManual.setOnClickListener(v -> showManualLog());
        b.btnEditTarget.setOnClickListener(v -> showTargetEditor());
        b.btnStopStudyTimer.setOnClickListener(v -> stopStudyTimer());
        b.fabAddTopic.setOnClickListener(v -> showTopicEditor(null));

        timerHandler = new Handler(Looper.getMainLooper());
        timerTick = this::updateTimerLabel;
    }

    // ------------------------------------------------------------------
    //  Filters
    // ------------------------------------------------------------------

    private void buildFilterChips(@NonNull FragmentStudyBinding b) {
        b.chipGroupExam.removeAllViews();
        b.chipGroupSubject.removeAllViews();

        addFilterChip(b.chipGroupExam, getString(R.string.label_all), null, true,
                exam -> {
                    filterExam = exam;
                    refreshData();
                });
        for (String exam : StudyTopicEntity.ALL_EXAMS) {
            addFilterChip(b.chipGroupExam, examDisplayName(exam), exam, false,
                    value -> {
                        filterExam = value;
                        refreshData();
                    });
        }

        addFilterChip(b.chipGroupSubject, getString(R.string.label_all), null, true,
                subject -> {
                    filterSubject = subject;
                    refreshData();
                });
        for (String subject : StudyTopicEntity.ALL_SUBJECTS) {
            addFilterChip(b.chipGroupSubject, subjectDisplayName(subject), subject, false,
                    value -> {
                        filterSubject = value;
                        refreshData();
                    });
        }
    }

    private interface OnFilterSelected {
        void onSelected(@Nullable String value);
    }

    private void addFilterChip(@NonNull com.google.android.material.chip.ChipGroup group,
                               @NonNull String label, @Nullable String value, boolean checked,
                               @NonNull OnFilterSelected onSelected) {
        Chip chip = new Chip(requireContext());
        chip.setText(label);
        chip.setCheckable(true);
        chip.setChecked(checked);
        chip.setChipMinHeight(getResources().getDimension(R.dimen.touch_target));
        chip.setId(View.generateViewId());
        chip.setOnClickListener(v -> onSelected.onSelected(value));
        group.addView(chip);
    }

    /** Exam constants are stored in English so data survives a language switch. */
    @NonNull
    private String examDisplayName(@NonNull String exam) {
        switch (exam) {
            case StudyTopicEntity.EXAM_RAILWAY_ALP:
                return getString(R.string.study_exam_railway_alp);
            case StudyTopicEntity.EXAM_RAILWAY_GROUP_D:
                return getString(R.string.study_exam_railway_group_d);
            case StudyTopicEntity.EXAM_SSC_CGL:
                return getString(R.string.study_exam_ssc_cgl);
            case StudyTopicEntity.EXAM_SSC_GD:
                return getString(R.string.study_exam_ssc_gd);
            default:
                return exam;
        }
    }

    @NonNull
    private String subjectDisplayName(@NonNull String subject) {
        switch (subject) {
            case StudyTopicEntity.SUBJECT_MATHS:
                return getString(R.string.study_subject_mathematics);
            case StudyTopicEntity.SUBJECT_REASONING:
                return getString(R.string.study_subject_reasoning);
            case StudyTopicEntity.SUBJECT_BIOLOGY:
                return getString(R.string.study_subject_biology);
            case StudyTopicEntity.SUBJECT_PHYSICS:
                return getString(R.string.study_subject_physics);
            case StudyTopicEntity.SUBJECT_CHEMISTRY:
                return getString(R.string.study_subject_chemistry);
            case StudyTopicEntity.SUBJECT_GK:
                return getString(R.string.study_subject_gk);
            default:
                return subject;
        }
    }

    // ------------------------------------------------------------------
    //  Data
    // ------------------------------------------------------------------

    @Override
    public void onResume() {
        super.onResume();
        refreshData();
        restartTimerPolling();
    }

    @Override
    public void onPause() {
        super.onPause();
        stopTimerPolling();
    }

    @Override
    public void refreshData() {
        FragmentStudyBinding b = binding;
        Repository repo = repository;
        if (b == null || repo == null) return;

        AppDatabase.io().execute(() -> {
            Payload p;
            try {
                p = load(repo);
            } catch (Exception e) {
                android.util.Log.e("StudyFragment", "load failed", e);
                return;
            }
            if (!isAdded()) return;
            requireActivity().runOnUiThread(() -> {
                if (binding == null || adapter == null) return;
                render(p);
                updateTimerLabel();
            });
        });
    }

    private static final class Payload {
        int todayMinutes;
        int targetMinutes;
        int weekMinutes;
        int topicCount;
        int completedCount;
        int revisionDue;
        List<StudyTopicEntity> topics = new ArrayList<>();
        List<StudyTopicEntity> dueTopics = new ArrayList<>();
        List<FocusSessionDao.ChartPoint> perSubject = new ArrayList<>();
    }

    @NonNull
    private Payload load(@NonNull Repository repo) {
        Payload p = new Payload();
        String today = TimeUtils.today();
        String[] week = TimeUtils.currentWeekRange();
        long now = System.currentTimeMillis();

        p.todayMinutes = repo.db().studyDao().minutesOnDay(today);
        p.targetMinutes = Prefs.targetStudyMinutes(requireContext());
        p.weekMinutes = repo.db().studyDao().minutesBetween(week[0], week[1]);
        p.topicCount = repo.db().studyDao().topicCount();
        p.completedCount = repo.db().studyDao().completedTopicCount();
        p.revisionDue = repo.db().studyDao().revisionsDueCount(now);
        p.dueTopics = repo.db().studyDao().dueTopics(now, 6);
        p.perSubject = repo.db().studyDao().minutesPerSubject(week[0], week[1]);

        // The list query is filtered in memory rather than with four more DAO
        // methods: the topic table is at most a few hundred rows for a whole
        // syllabus, and the filters change on every chip tap.
        List<StudyTopicEntity> all = repo.db().studyDao().allTopics();
        for (StudyTopicEntity t : all) {
            if (t == null) continue;
            if (filterExam != null && !filterExam.equals(t.exam)) continue;
            if (filterSubject != null && !filterSubject.equals(t.subject)) continue;
            p.topics.add(t);
        }
        // Due revisions first, then incomplete, then complete: the top of the
        // list is always the thing that most needs attention.
        java.util.Collections.sort(p.topics, (x, y) -> {
            int dueCompare = Boolean.compare(
                    !RevisionSchedule.isDue(x.nextRevisionAt, now),
                    !RevisionSchedule.isDue(y.nextRevisionAt, now));
            if (dueCompare != 0) return dueCompare;
            int completeCompare = Boolean.compare(x.completed, y.completed);
            if (completeCompare != 0) return completeCompare;
            return x.subject.compareTo(y.subject);
        });

        return p;
    }

    private void render(@NonNull Payload p) {
        FragmentStudyBinding b = binding;
        if (b == null || adapter == null) return;

        int percent = p.targetMinutes <= 0 ? 0
                : FocusScoreCalculator.clamp(
                        Math.round(p.todayMinutes * 100f / p.targetMinutes), 0, 100);

        b.tvStudyToday.setText(TimeUtils.formatMinutes(p.todayMinutes));
        b.tvStudyTarget.setText(getString(R.string.study_daily_target_set,
                TimeUtils.formatMinutes(p.targetMinutes)));
        b.pbStudy.setProgress(percent);
        b.pbStudy.getProgressDrawable().setTint(requireContext().getColor(
                percent >= 100 ? R.color.success_500 : R.color.accent_500));

        b.tvTopicProgress.setText(getString(R.string.study_completed_of,
                p.completedCount, p.topicCount));
        b.tvTopicsEmpty.setVisibility(p.topics.isEmpty() ? View.VISIBLE : View.GONE);
        b.rvTopics.setVisibility(p.topics.isEmpty() ? View.GONE : View.VISIBLE);
        adapter.submitList(new ArrayList<>(p.topics));

        renderRevision(b, p);
        renderWeekly(b, p);
    }

    private void renderRevision(@NonNull FragmentStudyBinding b, @NonNull Payload p) {
        b.cardRevision.setVisibility(p.revisionDue > 0 ? View.VISIBLE : View.GONE);
        b.tvRevisionCount.setText(String.valueOf(p.revisionDue));
        b.revisionContainer.removeAllViews();

        LayoutInflater inflater = LayoutInflater.from(requireContext());
        for (StudyTopicEntity topic : p.dueTopics) {
            View row = inflater.inflate(R.layout.item_revision_row, b.revisionContainer, false);
            android.widget.TextView chapter = row.findViewById(R.id.tv_revision_chapter);
            android.widget.TextView subject = row.findViewById(R.id.tv_revision_subject);
            chapter.setText(topic.chapter);
            subject.setText(subjectDisplayName(topic.subject));

            int days = RevisionSchedule.daysUntil(topic.nextRevisionAt, System.currentTimeMillis());
            // study_revision_due_count takes a count, not a chapter name, so the
            // row describes itself with the text it actually shows.
            row.setContentDescription(topic.chapter + ", " + subjectDisplayName(topic.subject));
            if (days < 0) {
                // Overdue revisions are amber rather than red: being late on a
                // revision is a scheduling fact, not a failure.
                subject.setTextColor(requireContext().getColor(R.color.warning_500));
            }

            row.setOnClickListener(v -> markRevised(topic));
            b.revisionContainer.addView(row);
        }
    }

    private void renderWeekly(@NonNull FragmentStudyBinding b, @NonNull Payload p) {
        if (p.perSubject.isEmpty()) {
            b.chartSubjects.setData(new int[0], new String[0]);
            b.tvWeekTotal.setText(getString(R.string.study_minutes_week,
                    TimeUtils.formatMinutes(p.weekMinutes)));
            return;
        }

        int[] values = new int[p.perSubject.size()];
        String[] labels = new String[p.perSubject.size()];
        for (int i = 0; i < p.perSubject.size(); i++) {
            FocusSessionDao.ChartPoint point = p.perSubject.get(i);
            values[i] = point.value;
            // Subject names are long ("General Knowledge"); the chart draws them
            // vertically, so only the first word is needed to disambiguate.
            labels[i] = point.label == null ? "" : firstWord(subjectDisplayName(point.label));
        }
        b.chartSubjects.setData(values, labels);
        b.chartSubjects.setValueSuffix("");
        b.tvWeekTotal.setText(getString(R.string.study_minutes_week,
                TimeUtils.formatMinutes(p.weekMinutes)));
    }

    @NonNull
    private static String firstWord(@NonNull String text) {
        int space = text.indexOf(' ');
        return space > 0 ? text.substring(0, space) : text;
    }

    // ------------------------------------------------------------------
    //  Study timer
    // ------------------------------------------------------------------

    private void onTimerButton() {
        if (Prefs.isStudyTimerRunning(requireContext())) {
            stopStudyTimer();
        } else {
            showTimerSubjectPicker();
        }
    }

    /**
     * Asks what is being studied before the clock starts.
     *
     * <p>One question, not three: the subject drives weekly statistics and the
     * topic is optional, so a student who has not planned chapters can still
     * time their work.</p>
     */
    private void showTimerSubjectPicker() {
        final String[] subjects = StudyTopicEntity.ALL_SUBJECTS;
        String[] labels = new String[subjects.length];
        for (int i = 0; i < subjects.length; i++) {
            labels[i] = subjectDisplayName(subjects[i]);
        }

        final int[] selected = {0};
        new MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.study_timer_start)
                .setSingleChoiceItems(labels, 0, (dialog, which) -> selected[0] = which)
                .setNegativeButton(R.string.action_cancel, null)
                .setPositiveButton(R.string.study_timer_start, (dialog, which) ->
                        startStudyTimer(subjects[selected[0]]))
                .show();
    }

    private void startStudyTimer(@NonNull String subject) {
        String exam = filterExam != null ? filterExam : StudyTopicEntity.EXAM_RAILWAY_GROUP_D;
        Prefs.setStudyTimer(requireContext(), System.currentTimeMillis(), subject, exam, 0L);
        restartTimerPolling();
        refreshData();
    }

    private void restartTimerPolling() {
        Handler handler = timerHandler;
        Runnable tick = timerTick;
        if (handler == null || tick == null) return;
        handler.removeCallbacks(tick);
        if (Prefs.isStudyTimerRunning(requireContext())) {
            handler.postDelayed(tick, TIMER_TICK_MS);
        }
    }

    private void stopTimerPolling() {
        if (timerHandler != null && timerTick != null) {
            timerHandler.removeCallbacks(timerTick);
        }
    }

    /**
     * Repaints the running-timer row.
     *
     * <p>Elapsed time is always derived from the stored start instant rather than
     * accumulated per tick, so a paused or throttled handler cannot silently
     * under-count study minutes.</p>
     */
    private void updateTimerLabel() {
        FragmentStudyBinding b = binding;
        if (b == null) return;

        long startedAt = Prefs.studyTimerStartedAt(requireContext());
        boolean running = startedAt > 0L;

        b.studyTimerRunning.setVisibility(running ? View.VISIBLE : View.GONE);
        // study_timer_running is a format string; setting it as a plain resource
        // would print "%1$s" on the button, so the button carries its own label.
        b.btnStudyTimer.setText(running
                ? R.string.study_timer_stop
                : R.string.study_timer_start);

        if (!running) {
            if (timerHandler != null && timerTick != null) {
                timerHandler.removeCallbacks(timerTick);
            }
            return;
        }

        long elapsedMs = Math.max(0L, System.currentTimeMillis() - startedAt);
        String subject = Prefs.studyTimerSubject(requireContext());
        b.tvStudyTimer.setText(getString(R.string.study_timer_elapsed,
                subjectDisplayName(subject.isEmpty()
                        ? StudyTopicEntity.SUBJECT_MATHS : subject),
                TimeUtils.formatDuration(elapsedMs)));

        if (timerHandler != null && timerTick != null) {
            timerHandler.removeCallbacks(timerTick);
            timerHandler.postDelayed(timerTick, TIMER_TICK_MS);
        }
    }

    private void stopStudyTimer() {
        long startedAt = Prefs.studyTimerStartedAt(requireContext());
        if (startedAt <= 0L) return;

        final String subject = Prefs.studyTimerSubject(requireContext());
        final String exam = Prefs.studyTimerExam(requireContext());
        final long topicId = Prefs.studyTimerTopicId(requireContext());
        final int minutes = (int) ((System.currentTimeMillis() - startedAt) / TimeUtils.MINUTE_MS);

        // Clear immediately so a second tap cannot log the same block twice.
        Prefs.clearStudyTimer(requireContext());
        stopTimerPolling();

        if (minutes < 1) {
            showMessage(getString(R.string.study_timer_too_short));
            updateTimerLabel();
            return;
        }

        Repository repo = repository;
        if (repo == null) {
            updateTimerLabel();
            return;
        }

        AppDatabase.io().execute(() -> {
            try {
                StudyLogEntity log = new StudyLogEntity();
                log.subject = subject.isEmpty() ? StudyTopicEntity.SUBJECT_MATHS : subject;
                log.exam = exam.isEmpty() ? StudyTopicEntity.EXAM_RAILWAY_GROUP_D : exam;
                log.topicId = topicId;
                log.minutes = minutes;
                log.startTime = startedAt;
                log.dayKey = TimeUtils.dayKey(startedAt);
                log.source = "timer";
                // recordStudy credits the topic in the same transaction, so the
                // log and the chapter's progress cannot drift apart.
                repo.db().studyDao().recordStudy(log);
            } catch (Exception e) {
                android.util.Log.e("StudyFragment", "could not save the study block", e);
            }
            if (!isAdded()) return;
            requireActivity().runOnUiThread(() -> {
                showMessage(getString(R.string.study_logged_minutes, minutes));
                updateTimerLabel();
                refreshData();
            });
        });
    }

    // ------------------------------------------------------------------
    //  Manual log
    // ------------------------------------------------------------------

    private void showManualLog() {
        DialogStudyManualBinding d = DialogStudyManualBinding
                .inflate(LayoutInflater.from(requireContext()));

        final String[] subjects = StudyTopicEntity.ALL_SUBJECTS;
        String[] labels = new String[subjects.length];
        for (int i = 0; i < subjects.length; i++) {
            labels[i] = subjectDisplayName(subjects[i]);
        }
        d.dropdownSubject.setAdapter(new ArrayAdapter<>(requireContext(),
                R.layout.item_dropdown_text, labels));

        final int[] selected = {0};
        if (filterSubject != null) {
            for (int i = 0; i < subjects.length; i++) {
                if (subjects[i].equals(filterSubject)) {
                    selected[0] = i;
                    break;
                }
            }
        }
        d.dropdownSubject.setText(labels[selected[0]], false);
        d.dropdownSubject.setOnItemClickListener((parent, view, position, id) -> selected[0] = position);

        AlertDialog alert = new MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.study_log_manual)
                .setView(d.getRoot())
                .setNegativeButton(R.string.action_cancel, null)
                .setPositiveButton(R.string.action_save, null)
                .create();
        alert.show();

        alert.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            Editable text = d.etMinutes.getText();
            int minutes = 0;
            if (text != null) {
                try {
                    minutes = Integer.parseInt(text.toString().trim());
                } catch (NumberFormatException ignored) {
                    // Handled by the range check below.
                }
            }
            if (minutes < 1 || minutes > 24 * 60) {
                d.etMinutes.setError(getString(R.string.study_invalid_minutes));
                return;
            }
            saveManualLog(minutes, subjects[selected[0]]);
            alert.dismiss();
        });
    }

    private void saveManualLog(int minutes, @NonNull String subject) {
        Repository repo = repository;
        if (repo == null) return;

        final String exam = filterExam != null ? filterExam : StudyTopicEntity.EXAM_RAILWAY_GROUP_D;
        AppDatabase.io().execute(() -> {
            try {
                StudyLogEntity log = new StudyLogEntity();
                log.subject = subject;
                log.exam = exam;
                log.topicId = 0L;
                log.minutes = minutes;
                log.startTime = System.currentTimeMillis();
                log.dayKey = TimeUtils.today();
                log.source = "manual";
                repo.db().studyDao().insertLog(log);
            } catch (Exception e) {
                android.util.Log.e("StudyFragment", "manual log failed", e);
            }
            if (!isAdded()) return;
            requireActivity().runOnUiThread(() -> {
                showMessage(getString(R.string.study_logged_minutes, minutes));
                refreshData();
            });
        });
    }

    // ------------------------------------------------------------------
    //  Topics
    // ------------------------------------------------------------------

    private void showTopicEditor(@Nullable StudyTopicEntity existing) {
        DialogTopicEditBinding d = DialogTopicEditBinding
                .inflate(LayoutInflater.from(requireContext()));

        final String[] exams = StudyTopicEntity.ALL_EXAMS;
        final String[] subjects = StudyTopicEntity.ALL_SUBJECTS;

        String[] examLabels = new String[exams.length];
        for (int i = 0; i < exams.length; i++) examLabels[i] = examDisplayName(exams[i]);
        String[] subjectLabels = new String[subjects.length];
        for (int i = 0; i < subjects.length; i++) subjectLabels[i] = subjectDisplayName(subjects[i]);

        d.dropdownExam.setAdapter(new ArrayAdapter<>(requireContext(),
                R.layout.item_dropdown_text, examLabels));
        d.dropdownSubject.setAdapter(new ArrayAdapter<>(requireContext(),
                R.layout.item_dropdown_text, subjectLabels));

        int examIndex = indexOf(exams, existing == null
                ? (filterExam != null ? filterExam : StudyTopicEntity.EXAM_RAILWAY_GROUP_D)
                : existing.exam);
        int subjectIndex = indexOf(subjects, existing == null
                ? (filterSubject != null ? filterSubject : StudyTopicEntity.SUBJECT_MATHS)
                : existing.subject);

        d.dropdownExam.setText(examLabels[examIndex], false);
        d.dropdownSubject.setText(subjectLabels[subjectIndex], false);

        final int[] chosen = {examIndex, subjectIndex};
        d.dropdownExam.setOnItemClickListener((parent, view, position, id) -> chosen[0] = position);
        d.dropdownSubject.setOnItemClickListener((parent, view, position, id) -> chosen[1] = position);

        if (existing != null) {
            d.etChapter.setText(existing.chapter);
            d.etTargetMinutes.setText(String.valueOf(existing.targetMinutes));
            d.etStudiedMinutes.setText(String.valueOf(existing.studiedMinutes));
            d.switchCompleted.setChecked(existing.completed);
        } else {
            d.etTargetMinutes.setText("60");
            d.etStudiedMinutes.setText("0");
        }

        boolean isEdit = existing != null;
        MaterialAlertDialogBuilder builder = new MaterialAlertDialogBuilder(requireContext())
                .setTitle(isEdit ? R.string.study_edit_topic : R.string.study_add_topic)
                .setView(d.getRoot())
                .setNegativeButton(R.string.action_cancel, null)
                .setPositiveButton(R.string.action_save, null);
        if (isEdit) builder.setNeutralButton(R.string.action_delete, null);

        AlertDialog alert = builder.create();
        alert.show();

        alert.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            String chapter = d.etChapter.getText() == null
                    ? "" : d.etChapter.getText().toString().trim();
            if (TextUtils.isEmpty(chapter)) {
                d.etChapter.setError(getString(R.string.validation_required));
                return;
            }
            saveTopic(existing, chapter, exams[chosen[0]], subjects[chosen[1]],
                    parseIntOr(d.etTargetMinutes.getText(), 0),
                    parseIntOr(d.etStudiedMinutes.getText(), 0),
                    d.switchCompleted.isChecked());
            alert.dismiss();
        });

        if (isEdit) {
            android.widget.Button neutral = alert.getButton(AlertDialog.BUTTON_NEUTRAL);
            if (neutral != null) {
                neutral.setOnClickListener(v -> {
                    deleteTopic(existing);
                    alert.dismiss();
                });
            }
        }
    }

    private static int indexOf(@NonNull String[] values, @NonNull String target) {
        for (int i = 0; i < values.length; i++) {
            if (values[i].equals(target)) return i;
        }
        return 0;
    }

    private static int parseIntOr(@Nullable Editable text, int fallback) {
        if (text == null) return fallback;
        try {
            return Integer.parseInt(text.toString().trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private void saveTopic(@Nullable StudyTopicEntity existing, @NonNull String chapter,
                           @NonNull String exam, @NonNull String subject,
                           int targetMinutes, int studiedMinutes, boolean completed) {
        Repository repo = repository;
        if (repo == null) return;

        AppDatabase.io().execute(() -> {
            try {
                StudyTopicEntity topic = existing != null ? existing : new StudyTopicEntity();
                boolean newlyCompleted = completed && (existing == null || !existing.completed);

                topic.chapter = chapter;
                topic.exam = exam;
                topic.subject = subject;
                topic.targetMinutes = Math.max(0, targetMinutes);
                topic.studiedMinutes = Math.max(0, studiedMinutes);
                topic.completed = completed;
                if (TextUtils.isEmpty(topic.dayKey)) {
                    topic.dayKey = TimeUtils.today();
                }
                if (existing == null) {
                    topic.createdAt = System.currentTimeMillis();
                }

                // Completing a chapter schedules its first revision. Un-completing
                // it clears the schedule, so a reverted chapter does not keep
                // nagging.
                if (newlyCompleted) {
                    topic.revisionsDone = 0;
                    topic.nextRevisionAt = RevisionSchedule.nextRevisionAt(
                            0, System.currentTimeMillis());
                } else if (!completed) {
                    topic.nextRevisionAt = 0L;
                }

                if (existing == null) {
                    repo.db().studyDao().insertTopic(topic);
                } else {
                    repo.db().studyDao().updateTopic(topic);
                }
            } catch (Exception e) {
                android.util.Log.e("StudyFragment", "save topic failed", e);
            }
            if (!isAdded()) return;
            requireActivity().runOnUiThread(this::refreshData);
        });
    }

    private void toggleTopicComplete(@NonNull StudyTopicEntity topic) {
        saveTopic(topic, topic.chapter, topic.exam, topic.subject,
                topic.targetMinutes, topic.studiedMinutes, !topic.completed);
    }

    private void deleteTopic(@NonNull StudyTopicEntity topic) {
        Repository repo = repository;
        if (repo == null) return;

        AppDatabase.io().execute(() -> {
            try {
                repo.db().studyDao().deleteTopic(topic);
            } catch (Exception e) {
                android.util.Log.e("StudyFragment", "delete topic failed", e);
            }
            if (!isAdded()) return;
            requireActivity().runOnUiThread(() -> {
                showMessage(getString(R.string.study_topic_deleted));
                refreshData();
            });
        });
    }

    /**
     * Records a revision pass and pushes the next one further out.
     *
     * <p>Optionally starts a timed session for the same topic, which is what
     * most people mean when they tap "revise" — they are about to sit down and
     * do it.</p>
     */
    private void markRevised(@NonNull StudyTopicEntity topic) {
        Repository repo = repository;
        if (repo == null) return;

        final long topicId = topic.id;
        final int nextPasses = topic.revisionsDone + 1;
        final String chapter = topic.chapter;
        final String subject = topic.subject;
        final String exam = topic.exam;

        AppDatabase.io().execute(() -> {
            try {
                long next = RevisionSchedule.nextRevisionAt(nextPasses, System.currentTimeMillis());
                repo.db().studyDao().markRevised(topicId, next);

                // A revision is study time too, and it belongs to the same
                // chapter, so credit it rather than losing it from the stats.
                StudyLogEntity log = new StudyLogEntity();
                log.subject = subject;
                log.exam = exam;
                log.topicId = topicId;
                log.minutes = 10;
                log.startTime = System.currentTimeMillis();
                log.dayKey = TimeUtils.today();
                log.source = "revision";
                repo.db().studyDao().insertLog(log);
            } catch (Exception e) {
                android.util.Log.e("StudyFragment", "revision failed", e);
            }
            if (!isAdded()) return;
            requireActivity().runOnUiThread(() -> {
                showMessage(getString(R.string.study_revision_done, chapter,
                        RevisionSchedule.intervalDaysFor(nextPasses)));
                refreshData();
            });
        });
    }

    // ------------------------------------------------------------------
    //  Daily target
    // ------------------------------------------------------------------

    private void showTargetEditor() {
        final int current = Prefs.targetStudyMinutes(requireContext());
        final String[] options = {"120", "180", "240", "300", "360", "420", "480"};
        String[] labels = new String[options.length];
        for (int i = 0; i < options.length; i++) {
            labels[i] = TimeUtils.formatMinutes(Integer.parseInt(options[i]));
        }
        int selectedIndex = indexOf(options, String.valueOf(current));

        new MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.study_daily_target_set)
                .setSingleChoiceItems(labels, selectedIndex >= 0 ? selectedIndex : 4,
                        (dialog, which) -> {
                            Prefs.setTargetStudyMinutes(requireContext(),
                                    Integer.parseInt(options[which]));
                            dialog.dismiss();
                            refreshData();
                        })
                .setNegativeButton(R.string.action_cancel, null)
                .show();
    }

    // ------------------------------------------------------------------

    @Override
    public void scrollToTop() {
        FragmentStudyBinding b = binding;
        if (b != null) b.studyScroll.smoothScrollTo(0, 0);
    }

    private void showMessage(@NonNull String message) {
        FragmentStudyBinding b = binding;
        if (b == null) return;
        Snackbar.make(b.getRoot(), message, Snackbar.LENGTH_LONG).show();
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        stopTimerPolling();
        if (binding != null) binding.rvTopics.setAdapter(null);
        adapter = null;
        timerHandler = null;
        timerTick = null;
        binding = null;
    }
}
