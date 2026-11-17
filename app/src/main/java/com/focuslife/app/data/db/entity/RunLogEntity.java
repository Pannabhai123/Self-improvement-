package com.focuslife.app.data.db.entity;

import androidx.annotation.NonNull;
import androidx.room.ColumnInfo;
import androidx.room.Entity;
import androidx.room.Index;
import androidx.room.PrimaryKey;

/**
 * One recorded run or workout.
 *
 * <p>Distance is stored in metres so pace maths stays integral; the 1600 m
 * goal from the brief is therefore exactly representable. Time for that
 * distance is {@code durationSec}.</p>
 */
@Entity(tableName = "run_logs", indices = {@Index("day_key")})
public class RunLogEntity {

    @PrimaryKey(autoGenerate = true)
    public long id;

    /** "run" or "workout". */
    @ColumnInfo(name = "kind")
    @NonNull
    public String kind = KIND_RUN;

    @ColumnInfo(name = "distance_m")
    public int distanceM;

    @ColumnInfo(name = "duration_sec")
    public int durationSec;

    @ColumnInfo(name = "day_key")
    @NonNull
    public String dayKey = "";

    @ColumnInfo(name = "logged_at")
    public long loggedAt;

    /** True when this run counted toward the 1600 m target. */
    @ColumnInfo(name = "counted_1600")
    public boolean counted1600;

    /** Best lap time in seconds for the 1600 m attempt; 0 if not recorded. */
    @ColumnInfo(name = "best_1600_sec")
    public int best1600Sec;

    @ColumnInfo(name = "note")
    @NonNull
    public String note = "";

    public static final String KIND_RUN = "run";
    public static final String KIND_WORKOUT = "workout";

    /** The optional 1600-metre goal from the project brief. */
    public static final int TARGET_1600_M = 1600;

    public RunLogEntity() {
    }

    /** Pace in seconds per kilometre, or 0 when it cannot be computed. */
    public int paceSecPerKm() {
        if (distanceM <= 0 || durationSec <= 0) return 0;
        return (int) Math.round(durationSec * 1000.0 / distanceM);
    }
}
