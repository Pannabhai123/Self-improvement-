package com.focuslife.app.data.db.entity;

import androidx.annotation.NonNull;
import androidx.room.ColumnInfo;
import androidx.room.Entity;
import androidx.room.PrimaryKey;

/**
 * An app the user has chosen to treat as a distraction.
 *
 * <p>Identification is by package name plus the boolean flags that say in which
 * modes the app should be intercepted. No app content is ever inspected — the
 * accessibility service compares only the foreground package name against this
 * table.</p>
 */
@Entity(tableName = "blocked_apps")
public class BlockedAppEntity {

    @PrimaryKey
    @ColumnInfo(name = "package_name")
    @NonNull
    public String packageName = "";

    @ColumnInfo(name = "app_label")
    @NonNull
    public String appLabel = "";

    /** Hard block: the warning screen offers no "open anyway" button. */
    @ColumnInfo(name = "strict")
    public boolean strict;

    @ColumnInfo(name = "block_in_focus")
    public boolean blockInFocus = true;

    @ColumnInfo(name = "block_in_study")
    public boolean blockInStudy = true;

    @ColumnInfo(name = "block_in_run")
    public boolean blockInRun = true;

    @ColumnInfo(name = "block_in_sleep")
    public boolean blockInSleep = true;

    /** Also intercept whenever no mode is active (always-on guard). */
    @ColumnInfo(name = "block_always")
    public boolean blockAlways;

    /** Daily usage ceiling in minutes; 0 means "no ceiling, just warn". */
    @ColumnInfo(name = "daily_limit_min")
    public int dailyLimitMin;

    /** Minutes already consumed today, refreshed by UsageSnapshotWorker. */
    @ColumnInfo(name = "used_today_min")
    public int usedTodayMin;

    /** {@code yyyy-MM-dd} that {@link #usedTodayMin} refers to. */
    @ColumnInfo(name = "usage_day_key")
    @NonNull
    public String usageDayKey = "";

    @ColumnInfo(name = "enabled")
    public boolean enabled = true;

    @ColumnInfo(name = "added_at")
    public long addedAt;

    /** Count of interception warnings shown for this app (all time). */
    @ColumnInfo(name = "block_count")
    public int blockCount;

    public BlockedAppEntity() {
    }

    public BlockedAppEntity(@NonNull String packageName, @NonNull String appLabel) {
        this.packageName = packageName;
        this.appLabel = appLabel;
    }

    /**
     * Whether {@link #usedTodayMin} refers to the given day.
     *
     * <p>Guarding the limit check with this prevents a stale counter from
     * blocking an app on a fresh day before the usage snapshot has run.</p>
     */
    public boolean dayKeyMatches(@NonNull String dayKey) {
        return usageDayKey != null && usageDayKey.equals(dayKey);
    }

    /** Remaining minutes on the daily ceiling, or -1 when no ceiling is set. */
    public int remainingLimitMinutes() {
        if (dailyLimitMin <= 0) return -1;
        return Math.max(0, dailyLimitMin - usedTodayMin);
    }
}
