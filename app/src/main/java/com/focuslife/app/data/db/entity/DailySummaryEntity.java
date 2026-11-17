package com.focuslife.app.data.db.entity;

import androidx.annotation.NonNull;
import androidx.room.ColumnInfo;
import androidx.room.Entity;
import androidx.room.PrimaryKey;

/**
 * The end-of-day report, materialised once per day so that the weekly report
 * and the streak history can be computed with a single small query instead of
 * re-scanning every table.
 */
@Entity(tableName = "daily_summaries")
public class DailySummaryEntity {

    /** {@code yyyy-MM-dd} — one row per day. */
    @PrimaryKey
    @ColumnInfo(name = "day_key")
    @NonNull
    public String dayKey = "";

    @ColumnInfo(name = "focus_score")
    public int focusScore;

    @ColumnInfo(name = "study_minutes")
    public int studyMinutes;

    @ColumnInfo(name = "focus_minutes")
    public int focusMinutes;

    @ColumnInfo(name = "focus_sessions")
    public int focusSessions;

    @ColumnInfo(name = "screen_minutes")
    public int screenMinutes;

    @ColumnInfo(name = "tasks_total")
    public int tasksTotal;

    @ColumnInfo(name = "tasks_done")
    public int tasksDone;

    @ColumnInfo(name = "habits_total")
    public int habitsTotal;

    @ColumnInfo(name = "habits_done")
    public int habitsDone;

    @ColumnInfo(name = "run_distance_m")
    public int runDistanceM;

    @ColumnInfo(name = "run_minutes")
    public int runMinutes;

    @ColumnInfo(name = "run_done")
    public boolean runDone;

    @ColumnInfo(name = "workouts_done")
    public int workoutsDone;

    @ColumnInfo(name = "block_hits")
    public int blockHits;

    @ColumnInfo(name = "distraction_minutes_lost")
    public int distractionMinutesLost;

    @ColumnInfo(name = "streak")
    public int streak;

    /** Human-readable "areas requiring improvement", joined by \n. */
    @ColumnInfo(name = "improvement_notes")
    @NonNull
    public String improvementNotes = "";

    @ColumnInfo(name = "generated_at")
    public long generatedAt;

    /** true once the notification for this report has been shown. */
    @ColumnInfo(name = "notified")
    public boolean notified;

    public DailySummaryEntity() {
    }
}
