package com.focuslife.app.data.db.entity;

import androidx.annotation.NonNull;
import androidx.room.ColumnInfo;
import androidx.room.Entity;
import androidx.room.PrimaryKey;

/**
 * One editable slot of the daily routine.
 *
 * <p>The thirteen default slots from the brief (04:30 wake up … 20:30 sleep
 * preparation) are seeded on first launch and every field is editable.</p>
 */
@Entity(tableName = "routine_slots")
public class RoutineSlotEntity {

    @PrimaryKey(autoGenerate = true)
    public long id;

    /** Minute of day, 0-1439. Stored as a number so slots sort correctly. */
    @ColumnInfo(name = "minute_of_day")
    public int minuteOfDay;

    @ColumnInfo(name = "title")
    @NonNull
    public String title = "";

    /** String resource id for built-in slots so they translate; 0 if custom. */
    @ColumnInfo(name = "title_res")
    public int titleRes;

    @ColumnInfo(name = "category")
    @NonNull
    public String category = CATEGORY_OTHER;

    @ColumnInfo(name = "enabled")
    public boolean enabled = true;

    /** Fire a notification this many minutes before the slot starts. */
    @ColumnInfo(name = "remind_before_min")
    public int remindBeforeMin = 5;

    /** Repeat on these days (bitfield of {@link BlockScheduleEntity#SUN} etc.). */
    @ColumnInfo(name = "days_mask")
    public int daysMask = BlockScheduleEntity.ALL_DAYS;

    /** true once the reminder for today has been delivered. */
    @ColumnInfo(name = "notified_day_key")
    @NonNull
    public String notifiedDayKey = "";

    @ColumnInfo(name = "sort_order")
    public int sortOrder;

    public static final String CATEGORY_WAKE    = "wake";
    public static final String CATEGORY_FITNESS = "fitness";
    public static final String CATEGORY_MEALS   = "meals";
    public static final String CATEGORY_STUDY   = "study";
    public static final String CATEGORY_REST    = "rest";
    public static final String CATEGORY_SLEEP   = "sleep";
    public static final String CATEGORY_OTHER   = "other";

    public RoutineSlotEntity() {
    }

    public RoutineSlotEntity(int minuteOfDay, @NonNull String title, int titleRes,
                             @NonNull String category, int sortOrder) {
        this.minuteOfDay = minuteOfDay;
        this.title = title;
        this.titleRes = titleRes;
        this.category = category;
        this.sortOrder = sortOrder;
    }

    public int hour()   { return minuteOfDay / 60; }
    public int minute() { return minuteOfDay % 60; }
}
