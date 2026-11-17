package com.focuslife.app.data.db;

import android.content.Context;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.VisibleForTesting;

import com.focuslife.app.R;
import com.focuslife.app.data.db.entity.BlockScheduleEntity;
import com.focuslife.app.data.db.entity.GoalEntity;
import com.focuslife.app.data.db.entity.HabitEntity;
import com.focuslife.app.data.db.entity.ReasonEntity;
import com.focuslife.app.data.db.entity.RoutineSlotEntity;
import com.focuslife.app.data.db.entity.StudyTopicEntity;
import com.focuslife.app.data.db.entity.WorkoutEntity;

import java.util.ArrayList;
import java.util.List;

/**
 * Populates the default content on first launch.
 *
 * <p>Seeding stores a string resource id alongside the resolved text. The UI
 * resolves the id first and falls back to the stored text, so the built-in
 * habits, routine slots and exercises follow the device language while
 * user-created rows (id 0) keep exactly what was typed.</p>
 *
 * <p>Every seed method is idempotent: it checks the current row count before
 * inserting, so a re-run after a partial failure cannot produce duplicates.</p>
 */
public final class DatabaseSeeder {

    private static final String TAG = "DatabaseSeeder";

    private DatabaseSeeder() {
    }

    /**
     * Seeds everything on a fresh install. Safe to call more than once.
     * Must be invoked off the main thread.
     */
    public static void seedFirstLaunch(@NonNull Context context) {
        try {
            AppDatabase db = AppDatabase.get(context);
            seedHabits(context, db);
            seedRoutine(context, db);
            seedWorkouts(context, db);
            seedReasons(context, db);
            seedGoals(context, db);
            seedStudyStarter(context, db);
            seedSchedules(context, db);
        } catch (Exception e) {
            // Seeding is a convenience, never a reason to crash on first launch.
            // The app is fully usable with empty lists and an "add" affordance.
            Log.e(TAG, "Seeding failed; continuing with empty defaults", e);
        }
    }

    /** Re-seeds only the routine, used by the "restore default schedule" action. */
    public static void resetRoutine(@NonNull Context context, @NonNull AppDatabase db) {
        db.routineDao().clearForReset();
        seedRoutine(context, db);
    }

    // ------------------------------------------------------------------
    //  Habits — the ten defaults from the brief
    // ------------------------------------------------------------------

    @VisibleForTesting
    public static void seedHabits(@NonNull Context context, @NonNull AppDatabase db) {
        if (db.habitDao().habitCount() > 0) return;

        int[] nameRes = {
                R.string.habit_wake_up,
                R.string.habit_morning_exercise,
                R.string.habit_running,
                R.string.habit_study_maths,
                R.string.habit_study_biology,
                R.string.habit_study_reasoning,
                R.string.habit_avoid_mobile,
                R.string.habit_avoid_gaming,
                R.string.habit_read_books,
                R.string.habit_sleep_on_time
        };
        String[] icons = {
                HabitEntity.ICON_WAKE,
                HabitEntity.ICON_EXERCISE,
                HabitEntity.ICON_RUN,
                HabitEntity.ICON_MATHS,
                HabitEntity.ICON_BIO,
                HabitEntity.ICON_REASON,
                HabitEntity.ICON_PHONE,
                HabitEntity.ICON_GAME,
                HabitEntity.ICON_BOOK,
                HabitEntity.ICON_SLEEP
        };
        // Reminder hours chosen to sit just before the matching routine slot.
        // -1 means "no reminder" so the app never nags ten times a day.
        int[] reminderHours = {4, 5, 5, 8, 9, 10, -1, -1, 15, 20};

        long now = System.currentTimeMillis();
        List<HabitEntity> habits = new ArrayList<>(nameRes.length);
        for (int i = 0; i < nameRes.length; i++) {
            HabitEntity h = new HabitEntity(context.getString(nameRes[i]), nameRes[i], icons[i], i);
            h.reminderHour = reminderHours[i];
            h.reminderMinute = reminderHours[i] < 0 ? 0 : 25;
            h.createdAt = now;
            habits.add(h);
        }
        db.habitDao().insertHabits(habits);
    }

    // ------------------------------------------------------------------
    //  Routine — the thirteen default slots from the brief
    // ------------------------------------------------------------------

    @VisibleForTesting
    public static void seedRoutine(@NonNull Context context, @NonNull AppDatabase db) {
        if (db.routineDao().count() > 0) return;

        int[][] slots = {
                // {hour, minute, stringRes, categoryIndex, remindBefore}
                {4, 30, R.string.routine_wake_up, 0, 5},
                {5, 0, R.string.routine_running, 1, 10},
                {6, 30, R.string.routine_freshen_breakfast, 2, 5},
                {8, 0, R.string.routine_mathematics, 3, 10},
                {9, 0, R.string.routine_biology, 3, 10},
                {10, 0, R.string.routine_reasoning, 3, 10},
                {11, 0, R.string.routine_revision, 3, 5},
                {12, 0, R.string.routine_lunch_rest, 4, 5},
                {15, 0, R.string.routine_general_knowledge, 3, 10},
                {17, 0, R.string.routine_exercise, 1, 10},
                {19, 0, R.string.routine_evening_revision, 3, 5},
                {20, 0, R.string.routine_plan_tomorrow, 5, 5},
                {20, 30, R.string.routine_sleep_prep, 5, 5}
        };
        String[] categories = {
                RoutineSlotEntity.CATEGORY_WAKE,
                RoutineSlotEntity.CATEGORY_FITNESS,
                RoutineSlotEntity.CATEGORY_MEALS,
                RoutineSlotEntity.CATEGORY_STUDY,
                RoutineSlotEntity.CATEGORY_REST,
                RoutineSlotEntity.CATEGORY_SLEEP
        };

        List<RoutineSlotEntity> list = new ArrayList<>(slots.length);
        for (int i = 0; i < slots.length; i++) {
            int[] s = slots[i];
            RoutineSlotEntity slot = new RoutineSlotEntity(
                    s[0] * 60 + s[1],
                    context.getString(s[2]),
                    s[2],
                    categories[s[3]],
                    i);
            slot.remindBeforeMin = s[4];
            slot.enabled = true;
            slot.daysMask = BlockScheduleEntity.ALL_DAYS;
            list.add(slot);
        }
        db.routineDao().insertAll(list);
    }

    // ------------------------------------------------------------------
    //  Exercise checklist
    // ------------------------------------------------------------------

    @VisibleForTesting
    public static void seedWorkouts(@NonNull Context context, @NonNull AppDatabase db) {
        if (db.fitnessDao().workoutCount() > 0) return;

        int[] nameRes = {
                R.string.fitness_warmup,
                R.string.fitness_pushups,
                R.string.fitness_pullups,
                R.string.fitness_squats,
                R.string.fitness_situps,
                R.string.fitness_plank,
                R.string.fitness_stretching
        };
        // Targets are deliberately modest: a checklist that cannot be finished
        // on a bad day gets abandoned entirely.
        int[] targetReps = {0, 20, 5, 25, 20, 0, 0};
        int[] targetSec = {180, 0, 0, 0, 0, 60, 300};

        List<WorkoutEntity> list = new ArrayList<>(nameRes.length);
        for (int i = 0; i < nameRes.length; i++) {
            WorkoutEntity w = new WorkoutEntity(context.getString(nameRes[i]), nameRes[i], targetReps[i], i);
            w.targetSec = targetSec[i];
            list.add(w);
        }
        db.fitnessDao().insertWorkouts(list);
    }

    // ------------------------------------------------------------------
    //  Personal reasons — seeded with prompts, editable and deletable
    // ------------------------------------------------------------------

    @VisibleForTesting
    public static void seedReasons(@NonNull Context context, @NonNull AppDatabase db) {
        if (db.controlDao().reasonCount() > 0) return;

        long now = System.currentTimeMillis();
        List<ReasonEntity> list = new ArrayList<>();
        list.add(new ReasonEntity(context.getString(R.string.reason_seed_exam), 0));
        list.add(new ReasonEntity(context.getString(R.string.reason_seed_family), 1));
        list.add(new ReasonEntity(context.getString(R.string.reason_seed_future), 2));
        for (int i = 0; i < list.size(); i++) {
            list.get(i).createdAt = now + i;
        }
        db.controlDao().insertReasons(list);
    }

    // ------------------------------------------------------------------
    //  Goals and vision board
    // ------------------------------------------------------------------

    @VisibleForTesting
    public static void seedGoals(@NonNull Context context, @NonNull AppDatabase db) {
        if (db.goalDao().all().size() > 0) return;

        long now = System.currentTimeMillis();
        List<GoalEntity> list = new ArrayList<>();

        GoalEntity exam = new GoalEntity(
                context.getString(R.string.goal_seed_exam_title),
                GoalEntity.KIND_GOAL, 0);
        exam.detail = context.getString(R.string.goal_seed_exam_detail);
        exam.createdAt = now;
        list.add(exam);

        GoalEntity discipline = new GoalEntity(
                context.getString(R.string.goal_seed_discipline_title),
                GoalEntity.KIND_GOAL, 1);
        discipline.detail = context.getString(R.string.goal_seed_discipline_detail);
        discipline.createdAt = now + 1;
        list.add(discipline);

        GoalEntity fitness = new GoalEntity(
                context.getString(R.string.goal_seed_fitness_title),
                GoalEntity.KIND_GOAL, 2);
        fitness.detail = context.getString(R.string.goal_seed_fitness_detail);
        fitness.createdAt = now + 2;
        list.add(fitness);

        GoalEntity vision = new GoalEntity(
                context.getString(R.string.vision_seed_title),
                GoalEntity.KIND_VISION, 0);
        vision.detail = context.getString(R.string.vision_seed_detail);
        vision.createdAt = now + 3;
        list.add(vision);

        db.goalDao().insertAll(list);
    }

    // ------------------------------------------------------------------
    //  A small starter syllabus so the Study tab is not an empty room
    // ------------------------------------------------------------------

    @VisibleForTesting
    public static void seedStudyStarter(@NonNull Context context, @NonNull AppDatabase db) {
        if (db.studyDao().topicCount() > 0) return;

        long now = System.currentTimeMillis();
        List<StudyTopicEntity> list = new ArrayList<>();

        // Railway Group D carries Biology, which is why it is the seeded exam.
        addTopic(list, context, StudyTopicEntity.EXAM_RAILWAY_GROUP_D,
                StudyTopicEntity.SUBJECT_MATHS, R.string.topic_seed_number_system, 120, now);
        addTopic(list, context, StudyTopicEntity.EXAM_RAILWAY_GROUP_D,
                StudyTopicEntity.SUBJECT_MATHS, R.string.topic_seed_simplification, 90, now);
        addTopic(list, context, StudyTopicEntity.EXAM_RAILWAY_GROUP_D,
                StudyTopicEntity.SUBJECT_REASONING, R.string.topic_seed_series, 90, now);
        addTopic(list, context, StudyTopicEntity.EXAM_RAILWAY_GROUP_D,
                StudyTopicEntity.SUBJECT_REASONING, R.string.topic_seed_coding_decoding, 60, now);
        addTopic(list, context, StudyTopicEntity.EXAM_RAILWAY_GROUP_D,
                StudyTopicEntity.SUBJECT_BIOLOGY, R.string.topic_seed_cell, 90, now);
        addTopic(list, context, StudyTopicEntity.EXAM_RAILWAY_GROUP_D,
                StudyTopicEntity.SUBJECT_BIOLOGY, R.string.topic_seed_human_body, 120, now);
        addTopic(list, context, StudyTopicEntity.EXAM_RAILWAY_GROUP_D,
                StudyTopicEntity.SUBJECT_PHYSICS, R.string.topic_seed_motion, 90, now);
        addTopic(list, context, StudyTopicEntity.EXAM_RAILWAY_GROUP_D,
                StudyTopicEntity.SUBJECT_CHEMISTRY, R.string.topic_seed_matter, 60, now);
        addTopic(list, context, StudyTopicEntity.EXAM_RAILWAY_GROUP_D,
                StudyTopicEntity.SUBJECT_GK, R.string.topic_seed_current_affairs, 45, now);

        db.studyDao().insertTopics(list);
    }

    private static void addTopic(List<StudyTopicEntity> list, Context context, String exam,
                                 String subject, int chapterRes, int targetMinutes, long now) {
        StudyTopicEntity t = new StudyTopicEntity();
        t.exam = exam;
        t.subject = subject;
        t.chapter = context.getString(chapterRes);
        t.targetMinutes = targetMinutes;
        t.createdAt = now + list.size();
        t.dayKey = com.focuslife.app.util.TimeUtils.today();
        list.add(t);
    }

    // ------------------------------------------------------------------
    //  One sensible default blocking window: Sleep Mode overnight
    // ------------------------------------------------------------------

    @VisibleForTesting
    public static void seedSchedules(@NonNull Context context, @NonNull AppDatabase db) {
        if (!db.blockerDao().schedules().isEmpty()) return;

        BlockScheduleEntity sleep = new BlockScheduleEntity();
        sleep.label = context.getString(R.string.schedule_seed_sleep);
        sleep.startMinute = 21 * 60;      // 21:00
        sleep.endMinute = 4 * 60 + 30;    // 04:30 next morning
        sleep.daysMask = BlockScheduleEntity.ALL_DAYS;
        sleep.mode = "sleep";
        sleep.enabled = false;            // Off until the user opts in.
        sleep.createdAt = System.currentTimeMillis();
        db.blockerDao().insertSchedule(sleep);
    }
}
