package com.focuslife.app.data.db.entity;

import androidx.annotation.NonNull;
import androidx.room.ColumnInfo;
import androidx.room.Entity;
import androidx.room.PrimaryKey;

/**
 * A daily task shown on the dashboard's "Completed Tasks" counter.
 * Distinct from {@link StudyTopicEntity}, which is syllabus-scoped.
 */
@Entity(tableName = "tasks")
public class TaskEntity {

    @PrimaryKey(autoGenerate = true)
    public long id;

    @ColumnInfo(name = "title")
    @NonNull
    public String title = "";

    @ColumnInfo(name = "notes")
    @NonNull
    public String notes = "";

    /** {@code yyyy-MM-dd} — the day this task belongs to. */
    @ColumnInfo(name = "day_key")
    @NonNull
    public String dayKey = "";

    @ColumnInfo(name = "done")
    public boolean done;

    /** Epoch millis when the task was ticked off; 0 if not done. */
    @ColumnInfo(name = "completed_at")
    public long completedAt;

    /** Optional estimated minutes, used for the "remaining daily goals" card. */
    @ColumnInfo(name = "estimate_min")
    public int estimateMin;

    /** 0 = low, 1 = normal, 2 = high. Drives list ordering. */
    @ColumnInfo(name = "priority")
    public int priority;

    @ColumnInfo(name = "created_at")
    public long createdAt;

    public TaskEntity() {
    }
}
