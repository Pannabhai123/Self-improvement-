package com.focuslife.app.data.db.dao;

import androidx.lifecycle.LiveData;
import androidx.room.Dao;
import androidx.room.Delete;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;
import androidx.room.Update;

import com.focuslife.app.data.db.entity.BlockScheduleEntity;
import com.focuslife.app.data.db.entity.BlockedAppEntity;

import java.util.List;

@Dao
public interface BlockerDao {

    // ---------------------------------------------------------------
    //  Blocked apps
    // ---------------------------------------------------------------

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    void insertApp(BlockedAppEntity app);

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    void insertApps(List<BlockedAppEntity> apps);

    @Update
    int updateApp(BlockedAppEntity app);

    @Delete
    int deleteApp(BlockedAppEntity app);

    @Query("DELETE FROM blocked_apps WHERE package_name = :pkg")
    int removeApp(String pkg);

    @Query("SELECT * FROM blocked_apps ORDER BY app_label ASC")
    LiveData<List<BlockedAppEntity>> observeAll();

    @Query("SELECT * FROM blocked_apps ORDER BY app_label ASC")
    List<BlockedAppEntity> all();

    /**
     * The synchronous hot path used by the accessibility service. It must be
     * fast and must not touch LiveData, so it returns a plain list that the
     * service keeps in memory and refreshes on change.
     */
    @Query("SELECT * FROM blocked_apps WHERE enabled = 1")
    List<BlockedAppEntity> enabledApps();

    @Query("SELECT * FROM blocked_apps WHERE package_name = :pkg LIMIT 1")
    BlockedAppEntity find(String pkg);

    @Query("SELECT package_name FROM blocked_apps WHERE enabled = 1")
    List<String> enabledPackageNames();

    @Query("SELECT COUNT(*) FROM blocked_apps WHERE enabled = 1")
    int enabledCount();

    @Query("SELECT COUNT(*) FROM blocked_apps WHERE enabled = 1")
    LiveData<Integer> observeEnabledCount();

    @Query("UPDATE blocked_apps SET enabled = :enabled WHERE package_name = :pkg")
    int setEnabled(String pkg, boolean enabled);

    @Query("UPDATE blocked_apps SET block_count = block_count + 1 WHERE package_name = :pkg")
    int incrementBlockCount(String pkg);

    @Query("SELECT COALESCE(SUM(block_count), 0) FROM blocked_apps")
    int totalBlockCount();

    /** Apps whose daily ceiling has already been exceeded today. */
    @Query("SELECT * FROM blocked_apps WHERE enabled = 1 AND daily_limit_min > 0 "
            + "AND usage_day_key = :dayKey AND used_today_min >= daily_limit_min")
    List<BlockedAppEntity> overLimitToday(String dayKey);

    @Query("UPDATE blocked_apps SET used_today_min = :minutes, usage_day_key = :dayKey "
            + "WHERE package_name = :pkg")
    int setUsedToday(String pkg, int minutes, String dayKey);

    @Query("UPDATE blocked_apps SET used_today_min = 0 WHERE usage_day_key != :dayKey")
    int resetStaleUsage(String dayKey);

    @Query("SELECT * FROM blocked_apps ORDER BY block_count DESC LIMIT :limit")
    List<BlockedAppEntity> mostBlocked(int limit);

    // ---------------------------------------------------------------
    //  Schedules
    // ---------------------------------------------------------------

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    long insertSchedule(BlockScheduleEntity schedule);

    @Update
    int updateSchedule(BlockScheduleEntity schedule);

    @Delete
    int deleteSchedule(BlockScheduleEntity schedule);

    @Query("SELECT * FROM block_schedules ORDER BY start_minute ASC")
    LiveData<List<BlockScheduleEntity>> observeSchedules();

    @Query("SELECT * FROM block_schedules ORDER BY start_minute ASC")
    List<BlockScheduleEntity> schedules();

    @Query("SELECT * FROM block_schedules WHERE enabled = 1")
    List<BlockScheduleEntity> enabledSchedules();

    @Query("SELECT * FROM block_schedules WHERE id = :id")
    BlockScheduleEntity findSchedule(long id);

    @Query("DELETE FROM block_schedules")
    int deleteAllSchedules();

    @Query("DELETE FROM blocked_apps")
    int deleteAllApps();
}
