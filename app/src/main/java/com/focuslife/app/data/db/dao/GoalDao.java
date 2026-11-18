package com.focuslife.app.data.db.dao;

import androidx.lifecycle.LiveData;
import androidx.room.Dao;
import androidx.room.Delete;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;
import androidx.room.Update;

import com.focuslife.app.data.db.entity.GoalEntity;

import java.util.List;

@Dao
public interface GoalDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    long insert(GoalEntity goal);

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    List<Long> insertAll(List<GoalEntity> goals);

    @Update
    int update(GoalEntity goal);

    @Delete
    int delete(GoalEntity goal);

    @Query("SELECT * FROM goals WHERE kind = :kind ORDER BY sort_order ASC, id ASC")
    LiveData<List<GoalEntity>> observeByKind(String kind);

    @Query("SELECT * FROM goals ORDER BY sort_order ASC, id ASC")
    LiveData<List<GoalEntity>> observeAll();

    @Query("SELECT * FROM goals ORDER BY sort_order ASC, id ASC")
    List<GoalEntity> all();

    @Query("SELECT * FROM goals WHERE id = :id")
    GoalEntity findById(long id);

    @Query("SELECT COUNT(*) FROM goals WHERE achieved = 0")
    int pendingCount();

    @Query("SELECT COUNT(*) FROM goals WHERE achieved = 1")
    int achievedCount();

    /** Milestones reached, for the progress-milestones card. */
    @Query("SELECT * FROM goals WHERE achieved = 1 ORDER BY id DESC LIMIT :limit")
    LiveData<List<GoalEntity>> observeAchieved(int limit);

    @Query("UPDATE goals SET achieved = 1, progress = 100 WHERE id = :id")
    int markAchieved(long id);

    @Query("UPDATE goals SET progress = :progress WHERE id = :id")
    int setProgress(long id, int progress);

    @Query("DELETE FROM goals")
    int deleteAll();
}
