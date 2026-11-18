package com.focuslife.app.data.db.entity;

import androidx.annotation.NonNull;
import androidx.room.ColumnInfo;
import androidx.room.Entity;
import androidx.room.Index;

/**
 * A daily snapshot of per-app foreground time, copied out of
 * {@code UsageStatsManager}.
 *
 * <p>The system only retains detailed usage for a limited window and can drop
 * it, so we mirror each day locally. This is what makes the weekly screen-time
 * comparison and the offline-first daily report possible.</p>
 *
 * <p>Privacy: we store the package name and the aggregate minutes in
 * foreground. Nothing else — no content, no notifications, no keystrokes.</p>
 */
@Entity(tableName = "app_usage_snapshots",
        primaryKeys = {"day_key", "package_name"},
        indices = {@Index("day_key"), @Index("package_name")})
public class AppUsageSnapshotEntity {

    @ColumnInfo(name = "day_key")
    @NonNull
    public String dayKey = "";

    @ColumnInfo(name = "package_name")
    @NonNull
    public String packageName = "";

    @ColumnInfo(name = "app_label")
    @NonNull
    public String appLabel = "";

    /** Total foreground time in minutes. */
    @ColumnInfo(name = "minutes")
    public int minutes;

    /**
     * Number of times the app came to the foreground. Derived from
     * {@code UsageEvents} where the platform supports it, otherwise 0.
     */
    @ColumnInfo(name = "launch_count")
    public int launchCount;

    @ColumnInfo(name = "last_used")
    public long lastUsed;

    /** true if this package is on the user's block list. */
    @ColumnInfo(name = "is_blocked_app")
    public boolean isBlockedApp;

    public AppUsageSnapshotEntity() {
    }

    public AppUsageSnapshotEntity(@NonNull String dayKey, @NonNull String packageName,
                                  @NonNull String appLabel, int minutes) {
        this.dayKey = dayKey;
        this.packageName = packageName;
        this.appLabel = appLabel;
        this.minutes = minutes;
    }
}
