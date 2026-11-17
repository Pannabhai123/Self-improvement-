package com.focuslife.app.data.db.entity;

import androidx.annotation.NonNull;
import androidx.room.ColumnInfo;
import androidx.room.Entity;
import androidx.room.PrimaryKey;

/**
 * A long-term personal goal or vision-board entry shown in the Motivation
 * section and used for progress milestones.
 */
@Entity(tableName = "goals")
public class GoalEntity {

    @PrimaryKey(autoGenerate = true)
    public long id;

    @ColumnInfo(name = "title")
    @NonNull
    public String title = "";

    @ColumnInfo(name = "detail")
    @NonNull
    public String detail = "";

    /** "vision" for the future vision board, "milestone" for progress markers. */
    @ColumnInfo(name = "kind")
    @NonNull
    public String kind = KIND_GOAL;

    /** Target date as epoch millis; 0 when open-ended. */
    @ColumnInfo(name = "target_date")
    public long targetDate;

    /** 0-100 self-reported progress. */
    @ColumnInfo(name = "progress")
    public int progress;

    @ColumnInfo(name = "achieved")
    public boolean achieved;

    @ColumnInfo(name = "sort_order")
    public int sortOrder;

    @ColumnInfo(name = "created_at")
    public long createdAt;

    public static final String KIND_GOAL      = "goal";
    public static final String KIND_VISION    = "vision";
    public static final String KIND_MILESTONE = "milestone";

    public GoalEntity() {
    }

    public GoalEntity(@NonNull String title, @NonNull String kind, int sortOrder) {
        this.title = title;
        this.kind = kind;
        this.sortOrder = sortOrder;
    }
}
