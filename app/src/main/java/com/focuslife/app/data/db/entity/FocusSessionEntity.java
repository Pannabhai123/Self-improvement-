package com.focuslife.app.data.db.entity;

import androidx.annotation.NonNull;
import androidx.room.ColumnInfo;
import androidx.room.Entity;
import androidx.room.PrimaryKey;

/**
 * One completed (or abandoned) Pomodoro-style focus session.
 *
 * <p>{@code durationSec} is the planned length, {@code focusedSec} is what the
 * user actually achieved, so the daily Focus Score can distinguish a finished
 * 25-minute block from one that was quit after 4 minutes.</p>
 */
@Entity(tableName = "focus_sessions")
public class FocusSessionEntity {

    @PrimaryKey(autoGenerate = true)
    public long id;

    /** The task the user said they were working on. Free text. */
    @ColumnInfo(name = "task_label")
    @NonNull
    public String taskLabel = "";

    /** Planned session length in seconds (1500, 3000, or custom). */
    @ColumnInfo(name = "duration_sec")
    public int durationSec;

    /** Seconds actually spent focused before completion or abandonment. */
    @ColumnInfo(name = "focused_sec")
    public int focusedSec;

    /** Epoch millis at which the session started. */
    @ColumnInfo(name = "start_time")
    public long startTime;

    /** Epoch millis at which the session ended. 0 while running. */
    @ColumnInfo(name = "end_time")
    public long endTime;

    /**
     * Local calendar day key in {@code yyyy-MM-dd} form. Stored explicitly so
     * that day grouping never depends on the device locale or timezone at
     * query time, and so daily reports stay correct after a timezone change.
     */
    @ColumnInfo(name = "day_key")
    @NonNull
    public String dayKey = "";

    /** true when the user finished the whole session; false if they quit early. */
    @ColumnInfo(name = "completed")
    public boolean completed;

    /** true if this session ran inside Deep Focus Mode with blocking enabled. */
    @ColumnInfo(name = "deep_focus")
    public boolean deepFocus;

    /** How many times the user tried to open a blocked app during this session. */
    @ColumnInfo(name = "block_hits")
    public int blockHits;

    /** true if the session is still running (used to restore after process death). */
    @ColumnInfo(name = "active")
    public boolean active;

    /** Which mode was running: "focus", "study", "run", "sleep", "none". */
    @ColumnInfo(name = "mode")
    @NonNull
    public String mode = "focus";

    public FocusSessionEntity() {
    }

    public FocusSessionEntity(@NonNull String taskLabel, int durationSec, long startTime,
                              @NonNull String dayKey) {
        this.taskLabel = taskLabel;
        this.durationSec = durationSec;
        this.startTime = startTime;
        this.dayKey = dayKey;
    }
}
