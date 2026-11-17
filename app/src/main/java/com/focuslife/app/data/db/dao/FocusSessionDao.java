package com.focuslife.app.data.db.dao;

import androidx.lifecycle.LiveData;
import androidx.room.Dao;
import androidx.room.Delete;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;
import androidx.room.Update;

import com.focuslife.app.data.db.entity.FocusSessionEntity;

import java.util.List;

@Dao
public interface FocusSessionDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    long insert(FocusSessionEntity session);

    @Update
    int update(FocusSessionEntity session);

    @Delete
    int delete(FocusSessionEntity session);

    @Query("SELECT * FROM focus_sessions WHERE id = :id")
    FocusSessionEntity findById(long id);

    /** The session that was running when the process died, if any. */
    @Query("SELECT * FROM focus_sessions WHERE active = 1 ORDER BY start_time DESC LIMIT 1")
    FocusSessionEntity findActive();

    @Query("SELECT * FROM focus_sessions WHERE active = 1")
    List<FocusSessionEntity> findAllActive();

    @Query("SELECT * FROM focus_sessions WHERE day_key = :dayKey ORDER BY start_time DESC")
    LiveData<List<FocusSessionEntity>> observeByDay(String dayKey);

    @Query("SELECT * FROM focus_sessions ORDER BY start_time DESC LIMIT :limit")
    LiveData<List<FocusSessionEntity>> observeRecent(int limit);

    @Query("SELECT * FROM focus_sessions ORDER BY start_time DESC LIMIT :limit")
    List<FocusSessionEntity> recent(int limit);

    @Query("SELECT * FROM focus_sessions WHERE day_key BETWEEN :from AND :to ORDER BY start_time DESC")
    List<FocusSessionEntity> between(String from, String to);

    // ---------------------------------------------------------------
    //  Aggregates used by the dashboard and the daily report
    // ---------------------------------------------------------------

    /** Total seconds actually focused on a day. */
    @Query("SELECT COALESCE(SUM(focused_sec), 0) FROM focus_sessions WHERE day_key = :dayKey")
    LiveData<Integer> observeFocusedSecOnDay(String dayKey);

    @Query("SELECT COALESCE(SUM(focused_sec), 0) FROM focus_sessions WHERE day_key = :dayKey")
    int focusedSecOnDay(String dayKey);

    @Query("SELECT COUNT(*) FROM focus_sessions WHERE day_key = :dayKey AND completed = 1")
    int completedCountOnDay(String dayKey);

    @Query("SELECT COUNT(*) FROM focus_sessions WHERE day_key = :dayKey AND completed = 1")
    LiveData<Integer> observeCompletedCountOnDay(String dayKey);

    @Query("SELECT COALESCE(SUM(block_hits), 0) FROM focus_sessions WHERE day_key = :dayKey")
    int blockHitsOnDay(String dayKey);

    /** Per-day focused minutes over a range, oldest first. Drives the bar chart. */
    @Query("SELECT day_key AS label, COALESCE(SUM(focused_sec), 0) / 60 AS value "
            + "FROM focus_sessions WHERE day_key BETWEEN :from AND :to "
            + "GROUP BY day_key ORDER BY day_key ASC")
    List<ChartPoint> focusMinutesPerDay(String from, String to);

    /** Clears the active flag on any orphaned session (crash recovery). */
    @Query("UPDATE focus_sessions SET active = 0 WHERE active = 1")
    int clearActiveFlags();

    @Query("DELETE FROM focus_sessions")
    int deleteAll();

    /** A (label, value) pair for the hand-drawn charts. */
    class ChartPoint {
        public String label;
        public int value;

        public ChartPoint() {
        }

        public ChartPoint(String label, int value) {
            this.label = label;
            this.value = value;
        }
    }
}
