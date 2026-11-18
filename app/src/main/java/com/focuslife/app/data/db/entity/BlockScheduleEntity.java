package com.focuslife.app.data.db.entity;

import androidx.annotation.NonNull;
import androidx.room.ColumnInfo;
import androidx.room.Entity;
import androidx.room.PrimaryKey;

/**
 * A scheduled blocking window, e.g. "block games every day 21:00-23:00".
 *
 * <p>{@code daysMask} is a bitfield over {@link #SUN}..{@link #SAT}.</p>
 */
@Entity(tableName = "block_schedules")
public class BlockScheduleEntity {

    @PrimaryKey(autoGenerate = true)
    public long id;

    @ColumnInfo(name = "label")
    @NonNull
    public String label = "";

    /** Minute of day (0-1439) the window opens. */
    @ColumnInfo(name = "start_minute")
    public int startMinute;

    /** Minute of day (0-1439) the window closes. May be &lt; start for
     *  windows crossing midnight, e.g. 22:00 → 05:00. */
    @ColumnInfo(name = "end_minute")
    public int endMinute;

    @ColumnInfo(name = "days_mask")
    public int daysMask = ALL_DAYS;

    @ColumnInfo(name = "enabled")
    public boolean enabled = true;

    /** Mode to activate while the window is open. */
    @ColumnInfo(name = "mode")
    @NonNull
    public String mode = "study";

    @ColumnInfo(name = "created_at")
    public long createdAt;

    public static final int SUN = 1;
    public static final int MON = 1 << 1;
    public static final int TUE = 1 << 2;
    public static final int WED = 1 << 3;
    public static final int THU = 1 << 4;
    public static final int FRI = 1 << 5;
    public static final int SAT = 1 << 6;
    public static final int ALL_DAYS = SUN | MON | TUE | WED | THU | FRI | SAT;

    /**
     * Whether the window is open right now.
     *
     * @param dayOfWeek   {@link java.util.Calendar#SUNDAY}..{@link java.util.Calendar#SATURDAY}
     * @param minuteOfDay 0-1439
     */
    public boolean isOpenAt(int dayOfWeek, int minuteOfDay) {
        if (!enabled) return false;
        int bit = 1 << (dayOfWeek - 1); // Calendar.SUNDAY == 1

        if (startMinute <= endMinute) {
            // A window that starts and ends on the same calendar day: only the
            // selected day matters.
            if ((daysMask & bit) == 0) return false;
            return minuteOfDay >= startMinute && minuteOfDay < endMinute;
        }

        // The window crosses midnight, so there are two independent ways to be
        // open, and each is governed by a different day of the week:
        //   - late this evening, which belongs to today
        //   - early this morning, which is the tail of yesterday's window
        // Testing today's bit first and returning early used to make the second
        // case unreachable whenever today was not itself selected, so a
        // Saturday-night-only window silently stopped blocking at midnight
        // instead of running to its configured 04:30 end.
        if (minuteOfDay >= startMinute) {
            return (daysMask & bit) != 0;
        }
        if (minuteOfDay < endMinute) {
            int yesterdayBit = 1 << (((dayOfWeek - 1) + 6) % 7);
            return (daysMask & yesterdayBit) != 0;
        }
        return false;
    }

    public BlockScheduleEntity() {
    }
}
