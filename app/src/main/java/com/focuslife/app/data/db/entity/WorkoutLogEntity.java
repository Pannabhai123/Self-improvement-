package com.focuslife.app.data.db.entity;

import androidx.annotation.NonNull;
import androidx.room.ColumnInfo;
import androidx.room.Entity;
import androidx.room.ForeignKey;
import androidx.room.Index;

/**
 * One completed checklist item on one day, with the count actually achieved.
 */
@Entity(tableName = "workout_logs",
        primaryKeys = {"workout_id", "day_key"},
        foreignKeys = @ForeignKey(
                entity = WorkoutEntity.class,
                parentColumns = "id",
                childColumns = "workout_id",
                onDelete = ForeignKey.CASCADE),
        indices = {@Index("workout_id"), @Index("day_key")})
public class WorkoutLogEntity {

    @ColumnInfo(name = "workout_id")
    public long workoutId;

    @ColumnInfo(name = "day_key")
    @NonNull
    public String dayKey = "";

    @ColumnInfo(name = "reps_done")
    public int repsDone;

    @ColumnInfo(name = "sec_done")
    public int secDone;

    @ColumnInfo(name = "completed_at")
    public long completedAt;

    /** Personal-best flag: set when this rep count beat the previous best. */
    @ColumnInfo(name = "is_personal_best")
    public boolean isPersonalBest;

    public WorkoutLogEntity() {
    }

    public WorkoutLogEntity(long workoutId, @NonNull String dayKey, int repsDone, long completedAt) {
        this.workoutId = workoutId;
        this.dayKey = dayKey;
        this.repsDone = repsDone;
        this.completedAt = completedAt;
    }
}
