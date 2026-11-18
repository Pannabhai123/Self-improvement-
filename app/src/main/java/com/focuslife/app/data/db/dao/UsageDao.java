package com.focuslife.app.data.db.dao;

import androidx.lifecycle.LiveData;
import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;

import com.focuslife.app.data.db.entity.AppUsageSnapshotEntity;
import com.focuslife.app.data.db.entity.DailySummaryEntity;

import java.util.List;

@Dao
public interface UsageDao {

    // ---------------------------------------------------------------
    //  App usage snapshots
    // ---------------------------------------------------------------

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    void insertSnapshot(AppUsageSnapshotEntity snapshot);

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    void insertSnapshots(List<AppUsageSnapshotEntity> snapshots);

    @Query("SELECT * FROM app_usage_snapshots WHERE day_key = :dayKey ORDER BY minutes DESC")
    List<AppUsageSnapshotEntity> snapshotsForDay(String dayKey);

    @Query("SELECT * FROM app_usage_snapshots WHERE day_key = :dayKey ORDER BY minutes DESC")
    LiveData<List<AppUsageSnapshotEntity>> observeSnapshotsForDay(String dayKey);

    @Query("SELECT * FROM app_usage_snapshots WHERE day_key = :dayKey ORDER BY minutes DESC LIMIT :limit")
    LiveData<List<AppUsageSnapshotEntity>> observeTopApps(String dayKey, int limit);

    @Query("SELECT * FROM app_usage_snapshots WHERE day_key = :dayKey ORDER BY minutes DESC LIMIT :limit")
    List<AppUsageSnapshotEntity> topApps(String dayKey, int limit);

    @Query("SELECT COALESCE(SUM(minutes), 0) FROM app_usage_snapshots WHERE day_key = :dayKey")
    int totalMinutesOnDay(String dayKey);

    @Query("SELECT COALESCE(SUM(minutes), 0) FROM app_usage_snapshots WHERE day_key = :dayKey")
    LiveData<Integer> observeTotalMinutesOnDay(String dayKey);

    @Query("SELECT COALESCE(SUM(minutes), 0) FROM app_usage_snapshots WHERE day_key BETWEEN :from AND :to")
    int totalMinutesBetween(String from, String to);

    @Query("SELECT COALESCE(SUM(launch_count), 0) FROM app_usage_snapshots WHERE day_key = :dayKey")
    int totalLaunchesOnDay(String dayKey);

    @Query("SELECT COALESCE(SUM(launch_count), 0) FROM app_usage_snapshots "
            + "WHERE day_key = :dayKey AND package_name = :pkg")
    int launchesForApp(String dayKey, String pkg);

    @Query("SELECT COALESCE(SUM(minutes), 0) FROM app_usage_snapshots "
            + "WHERE day_key = :dayKey AND package_name = :pkg")
    int minutesForApp(String dayKey, String pkg);

    /** Weekly screen-time comparison chart. */
    @Query("SELECT day_key AS label, COALESCE(SUM(minutes), 0) AS value "
            + "FROM app_usage_snapshots WHERE day_key BETWEEN :from AND :to "
            + "GROUP BY day_key ORDER BY day_key ASC")
    List<FocusSessionDao.ChartPoint> minutesPerDay(String from, String to);

    /**
     * Aggregate usage per app across a range — the weekly "most-used apps"
     * list, which is more stable than any single day.
     */
    @Query("SELECT package_name AS pkg, app_label AS label, "
            + "COALESCE(SUM(minutes), 0) AS minutes "
            + "FROM app_usage_snapshots WHERE day_key BETWEEN :from AND :to "
            + "GROUP BY package_name ORDER BY minutes DESC LIMIT :limit")
    List<AppUsageTotal> topAppsBetween(String from, String to, int limit);

    @Query("DELETE FROM app_usage_snapshots WHERE day_key = :dayKey")
    int deleteDay(String dayKey);

    @Query("DELETE FROM app_usage_snapshots WHERE day_key < :before")
    int deleteOlderThan(String before);

    @Query("DELETE FROM app_usage_snapshots")
    int deleteAll();

    // ---------------------------------------------------------------
    //  Daily summaries (the materialised report)
    // ---------------------------------------------------------------

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    void insertSummary(DailySummaryEntity summary);

    @Query("SELECT * FROM daily_summaries WHERE day_key = :dayKey LIMIT 1")
    DailySummaryEntity findSummary(String dayKey);

    @Query("SELECT * FROM daily_summaries WHERE day_key = :dayKey LIMIT 1")
    LiveData<DailySummaryEntity> observeSummary(String dayKey);

    @Query("SELECT * FROM daily_summaries WHERE day_key BETWEEN :from AND :to ORDER BY day_key ASC")
    List<DailySummaryEntity> summariesBetween(String from, String to);

    @Query("SELECT * FROM daily_summaries ORDER BY day_key DESC LIMIT :limit")
    LiveData<List<DailySummaryEntity>> observeRecentSummaries(int limit);

    @Query("SELECT * FROM daily_summaries ORDER BY day_key DESC LIMIT :limit")
    List<DailySummaryEntity> recentSummaries(int limit);

    /**
     * Streak input: the most recent days on which a summary exists with a
     * focus score at or above the threshold, descending.
     */
    @Query("SELECT day_key FROM daily_summaries WHERE focus_score >= :minScore "
            + "ORDER BY day_key DESC")
    List<String> daysWithScoreAtLeast(int minScore);

    @Query("UPDATE daily_summaries SET notified = 1 WHERE day_key = :dayKey")
    int markSummaryNotified(String dayKey);

    @Query("DELETE FROM daily_summaries WHERE day_key = :dayKey")
    int deleteSummary(String dayKey);

    @Query("SELECT * FROM daily_summaries WHERE notified = 0 AND day_key < :today "
            + "ORDER BY day_key DESC LIMIT 1")
    DailySummaryEntity unnotifiedSummary(String today);

    @Query("DELETE FROM daily_summaries WHERE day_key < :before")
    int deleteSummariesOlderThan(String before);

    @Query("DELETE FROM daily_summaries")
    int deleteAllSummaries();

    /** Row shape for {@link #topAppsBetween}. */
    class AppUsageTotal {
        public String pkg;
        public String label;
        public int minutes;
    }
}
