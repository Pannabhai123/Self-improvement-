package com.focuslife.app.data.db.dao;

import androidx.lifecycle.LiveData;
import androidx.room.Dao;
import androidx.room.Delete;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;
import androidx.room.Transaction;
import androidx.room.Update;

import com.focuslife.app.data.db.entity.RunLogEntity;
import com.focuslife.app.data.db.entity.WorkoutEntity;
import com.focuslife.app.data.db.entity.WorkoutLogEntity;

import java.util.List;

@Dao
public interface FitnessDao {

    // ---------------------------------------------------------------
    //  Runs
    // ---------------------------------------------------------------

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    long insertRun(RunLogEntity run);

    @Update
    int updateRun(RunLogEntity run);

    @Delete
    int deleteRun(RunLogEntity run);

    @Query("SELECT * FROM run_logs ORDER BY logged_at DESC LIMIT :limit")
    LiveData<List<RunLogEntity>> observeRecentRuns(int limit);

    @Query("SELECT * FROM run_logs WHERE day_key = :dayKey ORDER BY logged_at DESC")
    LiveData<List<RunLogEntity>> observeRunsOnDay(String dayKey);

    @Query("SELECT * FROM run_logs WHERE day_key = :dayKey ORDER BY logged_at DESC")
    List<RunLogEntity> runsOnDay(String dayKey);

    @Query("SELECT * FROM run_logs WHERE day_key BETWEEN :from AND :to ORDER BY logged_at ASC")
    List<RunLogEntity> runsBetween(String from, String to);

    @Query("SELECT COALESCE(SUM(distance_m), 0) FROM run_logs WHERE day_key = :dayKey AND kind = 'run'")
    int distanceOnDay(String dayKey);

    @Query("SELECT COALESCE(SUM(duration_sec), 0) FROM run_logs WHERE day_key = :dayKey AND kind = 'run'")
    int runSecondsOnDay(String dayKey);

    @Query("SELECT COALESCE(SUM(distance_m), 0) FROM run_logs WHERE day_key BETWEEN :from AND :to AND kind = 'run'")
    int distanceBetween(String from, String to);

    /** Weekly fitness chart: metres per day. */
    @Query("SELECT day_key AS label, COALESCE(SUM(distance_m), 0) / 100 AS value "
            + "FROM run_logs WHERE day_key BETWEEN :from AND :to AND kind = 'run' "
            + "GROUP BY day_key ORDER BY day_key ASC")
    List<FocusSessionDao.ChartPoint> hundredsOfMetresPerDay(String from, String to);

    /** Best ever 1600 m time in seconds; 0 when never attempted. */
    @Query("SELECT COALESCE(MIN(best_1600_sec), 0) FROM run_logs "
            + "WHERE counted_1600 = 1 AND best_1600_sec > 0")
    int best1600Sec();

    @Query("SELECT COALESCE(MAX(distance_m), 0) FROM run_logs")
    int longestRunMetres();

    @Query("SELECT COUNT(*) FROM run_logs WHERE day_key BETWEEN :from AND :to AND kind = 'run'")
    int runDaysBetween(String from, String to);

    /** Distinct days with a run recorded — the "weekly fitness progress" count. */
    @Query("SELECT COUNT(DISTINCT day_key) FROM run_logs WHERE day_key BETWEEN :from AND :to AND kind = 'run'")
    int distinctRunDaysBetween(String from, String to);

    // ---------------------------------------------------------------
    //  Workout checklist
    // ---------------------------------------------------------------

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    long insertWorkout(WorkoutEntity workout);

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    List<Long> insertWorkouts(List<WorkoutEntity> workouts);

    @Update
    int updateWorkout(WorkoutEntity workout);

    /**
     * Persists an edited workout log in place.
     *
     * <p>{@code workout_logs} has a composite primary key of workout id plus day
     * key, so an update replaces exactly the one row. Recording a timed set after
     * the checklist toggle used to delete and re-insert the row, which worked but
     * needlessly exercised the cascade-delete foreign key on every edit.</p>
     */
    @Update
    int updateWorkoutLog(WorkoutLogEntity log);

    @Delete
    int deleteWorkout(WorkoutEntity workout);

    @Query("SELECT * FROM workouts WHERE archived = 0 ORDER BY sort_order ASC, id ASC")
    LiveData<List<WorkoutEntity>> observeWorkouts();

    @Query("SELECT * FROM workouts WHERE archived = 0 ORDER BY sort_order ASC, id ASC")
    List<WorkoutEntity> workouts();

    @Query("SELECT COUNT(*) FROM workouts")
    int workoutCount();

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    void insertWorkoutLog(WorkoutLogEntity log);

    @Query("DELETE FROM workout_logs WHERE workout_id = :workoutId AND day_key = :dayKey")
    int deleteWorkoutLog(long workoutId, String dayKey);

    @Query("SELECT * FROM workout_logs WHERE workout_id = :workoutId AND day_key = :dayKey LIMIT 1")
    WorkoutLogEntity findWorkoutLog(long workoutId, String dayKey);

    @Query("SELECT * FROM workout_logs WHERE day_key = :dayKey")
    List<WorkoutLogEntity> workoutLogsForDay(String dayKey);

    @Query("SELECT * FROM workout_logs WHERE day_key = :dayKey")
    LiveData<List<WorkoutLogEntity>> observeWorkoutLogsForDay(String dayKey);

    @Query("SELECT COUNT(*) FROM workout_logs WHERE day_key = :dayKey")
    int workoutsDoneOnDay(String dayKey);

    @Query("SELECT COUNT(*) FROM workout_logs WHERE day_key BETWEEN :from AND :to")
    int workoutsDoneBetween(String from, String to);

    /** Personal best reps for a workout across all time. */
    @Query("SELECT COALESCE(MAX(reps_done), 0) FROM workout_logs WHERE workout_id = :workoutId")
    int personalBestReps(long workoutId);

    /** Pull-up tracking specifically: total pull-ups logged in a range. */
    @Query("SELECT COALESCE(SUM(wl.reps_done), 0) FROM workout_logs wl "
            + "INNER JOIN workouts w ON w.id = wl.workout_id "
            + "WHERE wl.day_key BETWEEN :from AND :to AND w.name_res = :nameRes")
    int totalRepsForWorkoutRes(String from, String to, int nameRes);

    @Transaction
    default boolean toggleWorkout(long workoutId, String dayKey, int reps, long now) {
        WorkoutLogEntity existing = findWorkoutLog(workoutId, dayKey);
        if (existing != null) {
            deleteWorkoutLog(workoutId, dayKey);
            return false;
        }
        WorkoutLogEntity log = new WorkoutLogEntity(workoutId, dayKey, reps, now);
        log.isPersonalBest = reps > personalBestReps(workoutId);
        insertWorkoutLog(log);
        return true;
    }

    @Query("DELETE FROM run_logs")
    int deleteAllRuns();

    @Query("DELETE FROM workout_logs")
    int deleteAllWorkoutLogs();

    @Query("DELETE FROM workouts")
    int deleteAllWorkouts();
}
