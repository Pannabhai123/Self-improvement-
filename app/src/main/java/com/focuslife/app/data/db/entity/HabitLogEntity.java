package com.focuslife.app.data.db.entity;

import androidx.annotation.NonNull;
import androidx.room.ColumnInfo;
import androidx.room.Entity;
import androidx.room.ForeignKey;
import androidx.room.Index;

/**
 * One habit ticked on one day. Absence of a row means "not done" — there is no
 * explicit failure record, so nothing in the database stigmatises a missed day.
 */
@Entity(tableName = "habit_logs",
        primaryKeys = {"habit_id", "day_key"},
        foreignKeys = @ForeignKey(
                entity = HabitEntity.class,
                parentColumns = "id",
                childColumns = "habit_id",
                onDelete = ForeignKey.CASCADE),
        indices = {@Index("habit_id"), @Index("day_key")})
public class HabitLogEntity {

    @ColumnInfo(name = "habit_id")
    public long habitId;

    /** {@code yyyy-MM-dd}. */
    @ColumnInfo(name = "day_key")
    @NonNull
    public String dayKey = "";

    /** Epoch millis of the tick. */
    @ColumnInfo(name = "completed_at")
    public long completedAt;

    public HabitLogEntity() {
    }

    public HabitLogEntity(long habitId, @NonNull String dayKey, long completedAt) {
        this.habitId = habitId;
        this.dayKey = dayKey;
        this.completedAt = completedAt;
    }
}
