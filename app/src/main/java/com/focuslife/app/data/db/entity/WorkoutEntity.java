package com.focuslife.app.data.db.entity;

import androidx.annotation.NonNull;
import androidx.room.ColumnInfo;
import androidx.room.Entity;
import androidx.room.PrimaryKey;

/**
 * An item in the exercise checklist (push-ups, pull-ups, squats, stretching…).
 */
@Entity(tableName = "workouts")
public class WorkoutEntity {

    @PrimaryKey(autoGenerate = true)
    public long id;

    @ColumnInfo(name = "name")
    @NonNull
    public String name = "";

    @ColumnInfo(name = "name_res")
    public int nameRes;

    /** Target repetitions or sets; 0 for time-based items. */
    @ColumnInfo(name = "target_reps")
    public int targetReps;

    /** Target seconds for timed items (planks, stretching). */
    @ColumnInfo(name = "target_sec")
    public int targetSec;

    @ColumnInfo(name = "sort_order")
    public int sortOrder;

    @ColumnInfo(name = "archived")
    public boolean archived;

    public WorkoutEntity() {
    }

    public WorkoutEntity(@NonNull String name, int nameRes, int targetReps, int sortOrder) {
        this.name = name;
        this.nameRes = nameRes;
        this.targetReps = targetReps;
        this.sortOrder = sortOrder;
    }
}
