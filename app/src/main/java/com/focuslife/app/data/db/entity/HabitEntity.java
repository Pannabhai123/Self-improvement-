package com.focuslife.app.data.db.entity;

import androidx.annotation.NonNull;
import androidx.room.ColumnInfo;
import androidx.room.Entity;
import androidx.room.PrimaryKey;

/**
 * A habit definition. The ten default habits from the project brief are seeded
 * on first launch (see {@code DatabaseSeeder}).
 *
 * <p>Deliberately no "failure" language anywhere in this model: a missed day is
 * simply the absence of a {@link HabitLogEntity} row, and the streak counter is
 * forgiving (see {@code StreakCalculator}).</p>
 */
@Entity(tableName = "habits")
public class HabitEntity {

    @PrimaryKey(autoGenerate = true)
    public long id;

    @ColumnInfo(name = "name")
    @NonNull
    public String name = "";

    /** String resource id of the name, so built-in habits follow the locale.
     *  0 for user-created habits. */
    @ColumnInfo(name = "name_res")
    public int nameRes;

    /** Emoji-free icon key resolved in code; avoids shipping extra drawables. */
    @ColumnInfo(name = "icon_key")
    @NonNull
    public String iconKey = "star";

    @ColumnInfo(name = "archived")
    public boolean archived;

    /** Position in the checklist. */
    @ColumnInfo(name = "sort_order")
    public int sortOrder;

    /** Hour of day (0-23) for the optional reminder; -1 disables it. */
    @ColumnInfo(name = "reminder_hour")
    public int reminderHour = -1;

    @ColumnInfo(name = "reminder_minute")
    public int reminderMinute;

    @ColumnInfo(name = "created_at")
    public long createdAt;

    public HabitEntity() {
    }

    public HabitEntity(@NonNull String name, int nameRes, @NonNull String iconKey, int sortOrder) {
        this.name = name;
        this.nameRes = nameRes;
        this.iconKey = iconKey;
        this.sortOrder = sortOrder;
    }

    // Default habit icon keys
    public static final String ICON_WAKE    = "alarm";
    public static final String ICON_EXERCISE = "exercise";
    public static final String ICON_RUN     = "run";
    public static final String ICON_MATHS   = "maths";
    public static final String ICON_BIO     = "biology";
    public static final String ICON_REASON  = "reasoning";
    public static final String ICON_PHONE   = "phone";
    public static final String ICON_GAME    = "game";
    public static final String ICON_BOOK    = "book";
    public static final String ICON_SLEEP   = "sleep";
    public static final String ICON_STAR    = "star";
}
