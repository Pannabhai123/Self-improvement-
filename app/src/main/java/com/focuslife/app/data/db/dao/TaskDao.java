package com.focuslife.app.data.db.dao;

import androidx.lifecycle.LiveData;
import androidx.room.Dao;
import androidx.room.Delete;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;
import androidx.room.Update;

import com.focuslife.app.data.db.entity.TaskEntity;

import java.util.List;

@Dao
public interface TaskDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    long insert(TaskEntity task);

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    List<Long> insertAll(List<TaskEntity> tasks);

    @Update
    int update(TaskEntity task);

    @Delete
    int delete(TaskEntity task);

    @Query("SELECT * FROM tasks WHERE id = :id")
    TaskEntity findById(long id);

    /** Undone first, then by priority, so the next thing to do is on top. */
    @Query("SELECT * FROM tasks WHERE day_key = :dayKey "
            + "ORDER BY done ASC, priority DESC, id ASC")
    LiveData<List<TaskEntity>> observeByDay(String dayKey);

    @Query("SELECT * FROM tasks WHERE day_key = :dayKey ORDER BY done ASC, priority DESC, id ASC")
    List<TaskEntity> byDay(String dayKey);

    @Query("SELECT * FROM tasks WHERE done = 0 ORDER BY day_key ASC, priority DESC")
    LiveData<List<TaskEntity>> observeAllPending();

    @Query("SELECT COUNT(*) FROM tasks WHERE day_key = :dayKey")
    int totalOnDay(String dayKey);

    @Query("SELECT COUNT(*) FROM tasks WHERE day_key = :dayKey AND done = 1")
    int doneOnDay(String dayKey);

    @Query("SELECT COUNT(*) FROM tasks WHERE day_key = :dayKey AND done = 1")
    LiveData<Integer> observeDoneOnDay(String dayKey);

    @Query("SELECT COUNT(*) FROM tasks WHERE day_key = :dayKey AND done = 0")
    LiveData<Integer> observeRemainingOnDay(String dayKey);

    @Query("SELECT COALESCE(SUM(estimate_min), 0) FROM tasks WHERE day_key = :dayKey AND done = 0")
    int remainingMinutes(String dayKey);

    @Query("UPDATE tasks SET done = :done, completed_at = :at WHERE id = :id")
    int setDone(long id, boolean done, long at);

    /** Carry yesterday's undone tasks forward — the "roll over" action. */
    @Query("SELECT * FROM tasks WHERE day_key = :fromDay AND done = 0")
    List<TaskEntity> undoneOn(String fromDay);

    @Query("DELETE FROM tasks WHERE day_key = :dayKey")
    int deleteDay(String dayKey);

    @Query("DELETE FROM tasks")
    int deleteAll();
}
