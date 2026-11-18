package com.focuslife.app.data.db.dao;

import androidx.lifecycle.LiveData;
import androidx.room.Dao;
import androidx.room.Delete;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;
import androidx.room.Transaction;
import androidx.room.Update;

import com.focuslife.app.data.db.entity.HabitEntity;
import com.focuslife.app.data.db.entity.HabitLogEntity;

import java.util.List;

@Dao
public interface HabitDao {

    // ---------------------------------------------------------------
    //  Habit definitions
    // ---------------------------------------------------------------

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    long insertHabit(HabitEntity habit);

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    List<Long> insertHabits(List<HabitEntity> habits);

    @Update
    int updateHabit(HabitEntity habit);

    @Delete
    int deleteHabit(HabitEntity habit);

    @Query("SELECT * FROM habits WHERE archived = 0 ORDER BY sort_order ASC, id ASC")
    LiveData<List<HabitEntity>> observeActiveHabits();

    @Query("SELECT * FROM habits WHERE archived = 0 ORDER BY sort_order ASC, id ASC")
    List<HabitEntity> activeHabits();

    @Query("SELECT * FROM habits ORDER BY sort_order ASC, id ASC")
    List<HabitEntity> allHabits();

    @Query("SELECT COUNT(*) FROM habits")
    int habitCount();

    @Query("SELECT * FROM habits WHERE name_res = :nameRes LIMIT 1")
    HabitEntity findByRes(int nameRes);

    @Query("UPDATE habits SET archived = 1 WHERE id = :id")
    int archive(long id);

    // ---------------------------------------------------------------
    //  Habit logs
    // ---------------------------------------------------------------

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    void insertLog(HabitLogEntity log);

    @Query("DELETE FROM habit_logs WHERE habit_id = :habitId AND day_key = :dayKey")
    int deleteLog(long habitId, String dayKey);

    /** Toggles a habit for a day; returns the new state (true = now done). */
    @Transaction
    default boolean toggle(long habitId, String dayKey, long now) {
        HabitLogEntity existing = findLog(habitId, dayKey);
        if (existing != null) {
            deleteLog(habitId, dayKey);
            return false;
        }
        insertLog(new HabitLogEntity(habitId, dayKey, now));
        return true;
    }

    @Query("SELECT * FROM habit_logs WHERE habit_id = :habitId AND day_key = :dayKey LIMIT 1")
    HabitLogEntity findLog(long habitId, String dayKey);

    @Query("SELECT * FROM habit_logs WHERE day_key = :dayKey")
    List<HabitLogEntity> logsForDay(String dayKey);

    @Query("SELECT * FROM habit_logs WHERE day_key = :dayKey")
    LiveData<List<HabitLogEntity>> observeLogsForDay(String dayKey);

    @Query("SELECT habit_id FROM habit_logs WHERE day_key = :dayKey")
    List<Long> habitIdsDoneOnDay(String dayKey);

    @Query("SELECT * FROM habit_logs WHERE day_key BETWEEN :from AND :to")
    List<HabitLogEntity> logsBetween(String from, String to);

    @Query("SELECT COUNT(*) FROM habit_logs WHERE day_key = :dayKey")
    int doneCountOnDay(String dayKey);

    @Query("SELECT COUNT(*) FROM habit_logs WHERE day_key = :dayKey")
    LiveData<Integer> observeDoneCountOnDay(String dayKey);

    /**
     * Per-day completion counts over a range. Joined against the active habit
     * count in code to produce a percentage.
     */
    @Query("SELECT day_key AS label, COUNT(*) AS value FROM habit_logs "
            + "WHERE day_key BETWEEN :from AND :to GROUP BY day_key ORDER BY day_key ASC")
    List<FocusSessionDao.ChartPoint> completionsPerDay(String from, String to);

    /** Total completions for one habit (drives its own streak and best run). */
    @Query("SELECT COUNT(*) FROM habit_logs WHERE habit_id = :habitId")
    int totalCompletionsForHabit(long habitId);

    /**
     * All distinct days on which the habit was completed, descending.
     * The streak calculator walks this list.
     */
    @Query("SELECT DISTINCT day_key FROM habit_logs WHERE habit_id = :habitId "
            + "ORDER BY day_key DESC")
    List<String> completionDaysForHabit(long habitId);

    /**
     * Missed-habit analysis: days in range minus days completed, per habit.
     * Returns the completed days so the caller can subtract from the range.
     */
    @Query("SELECT habit_id AS label, COUNT(*) AS value FROM habit_logs "
            + "WHERE day_key BETWEEN :from AND :to GROUP BY habit_id ORDER BY value ASC")
    List<ChartPointLong> completionsPerHabit(String from, String to);

    @Query("DELETE FROM habit_logs")
    int deleteAllLogs();

    @Query("DELETE FROM habits")
    int deleteAllHabits();

    /** Chart point whose label is a numeric id. */
    class ChartPointLong {
        public long label;
        public int value;
    }
}
