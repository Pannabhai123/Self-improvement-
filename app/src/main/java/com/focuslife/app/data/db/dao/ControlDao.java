package com.focuslife.app.data.db.dao;

import androidx.lifecycle.LiveData;
import androidx.room.Dao;
import androidx.room.Delete;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;
import androidx.room.Update;

import com.focuslife.app.data.db.entity.DistractionLogEntity;
import com.focuslife.app.data.db.entity.ReasonEntity;

import java.util.List;

/**
 * Backs the "CONTROL YOURSELF" screen: the distraction diary and the personal
 * reasons list.
 */
@Dao
public interface ControlDao {

    // ---------------------------------------------------------------
    //  Distraction diary
    // ---------------------------------------------------------------

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    long insertDistraction(DistractionLogEntity log);

    @Update
    int updateDistraction(DistractionLogEntity log);

    @Delete
    int deleteDistraction(DistractionLogEntity log);

    @Query("SELECT * FROM distraction_logs ORDER BY logged_at DESC LIMIT :limit")
    LiveData<List<DistractionLogEntity>> observeRecent(int limit);

    @Query("SELECT * FROM distraction_logs WHERE day_key = :dayKey ORDER BY logged_at DESC")
    LiveData<List<DistractionLogEntity>> observeByDay(String dayKey);

    @Query("SELECT * FROM distraction_logs WHERE day_key BETWEEN :from AND :to ORDER BY logged_at DESC")
    List<DistractionLogEntity> between(String from, String to);

    /**
     * Common distraction triggers: how often each trigger appeared in a range,
     * most frequent first. This is the core "identify your triggers" query.
     */
    @Query("SELECT `trigger` AS label, COUNT(*) AS value FROM distraction_logs "
            + "WHERE day_key BETWEEN :from AND :to AND `trigger` != '' "
            + "GROUP BY `trigger` ORDER BY value DESC")
    List<FocusSessionDao.ChartPoint> triggerCounts(String from, String to);

    /** Minutes lost per trigger over a range. */
    @Query("SELECT `trigger` AS label, COALESCE(SUM(minutes_lost), 0) AS value "
            + "FROM distraction_logs WHERE day_key BETWEEN :from AND :to AND `trigger` != '' "
            + "GROUP BY `trigger` ORDER BY value DESC")
    List<FocusSessionDao.ChartPoint> minutesLostPerTrigger(String from, String to);

    /** Which apps caused the most self-reported distractions. */
    @Query("SELECT package_name AS label, COUNT(*) AS value FROM distraction_logs "
            + "WHERE day_key BETWEEN :from AND :to AND package_name != '' "
            + "GROUP BY package_name ORDER BY value DESC LIMIT :limit")
    List<FocusSessionDao.ChartPoint> worstApps(String from, String to, int limit);

    @Query("SELECT COALESCE(SUM(minutes_lost), 0) FROM distraction_logs WHERE day_key = :dayKey")
    int minutesLostOnDay(String dayKey);

    @Query("SELECT COUNT(*) FROM distraction_logs WHERE day_key = :dayKey")
    int countOnDay(String dayKey);

    /**
     * Success rate: share of logged urges that were resisted. Shown as
     * encouragement, never as criticism.
     */
    @Query("SELECT COUNT(*) FROM distraction_logs WHERE day_key BETWEEN :from AND :to AND resisted = 1")
    int resistedCount(String from, String to);

    @Query("SELECT COUNT(*) FROM distraction_logs WHERE day_key BETWEEN :from AND :to")
    int totalUrgeCount(String from, String to);

    /** Which alternative activities the user actually reaches for. */
    @Query("SELECT alternative_used AS label, COUNT(*) AS value FROM distraction_logs "
            + "WHERE day_key BETWEEN :from AND :to AND alternative_used != '' "
            + "GROUP BY alternative_used ORDER BY value DESC")
    List<FocusSessionDao.ChartPoint> alternativesUsed(String from, String to);

    // ---------------------------------------------------------------
    //  Personal reasons
    // ---------------------------------------------------------------

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    long insertReason(ReasonEntity reason);

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    List<Long> insertReasons(List<ReasonEntity> reasons);

    @Update
    int updateReason(ReasonEntity reason);

    @Delete
    int deleteReason(ReasonEntity reason);

    @Query("SELECT * FROM reasons ORDER BY sort_order ASC, id ASC")
    LiveData<List<ReasonEntity>> observeReasons();

    @Query("SELECT * FROM reasons ORDER BY sort_order ASC, id ASC")
    List<ReasonEntity> reasons();

    @Query("SELECT COUNT(*) FROM reasons")
    int reasonCount();

    /** Rotate through reasons so the warning screen does not repeat itself. */
    @Query("SELECT * FROM reasons ORDER BY times_shown ASC, id ASC LIMIT 1")
    ReasonEntity leastShownReason();

    @Query("UPDATE reasons SET times_shown = times_shown + 1 WHERE id = :id")
    int incrementShown(long id);

    @Query("DELETE FROM distraction_logs")
    int deleteAllDistractions();

    @Query("DELETE FROM reasons")
    int deleteAllReasons();
}
