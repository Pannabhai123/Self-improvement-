package com.focuslife.app.data.db.dao;

import androidx.lifecycle.LiveData;
import androidx.room.Dao;
import androidx.room.Delete;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;
import androidx.room.Update;

import com.focuslife.app.data.db.entity.RoutineSlotEntity;

import java.util.List;

@Dao
public interface RoutineDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    long insert(RoutineSlotEntity slot);

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    List<Long> insertAll(List<RoutineSlotEntity> slots);

    @Update
    int update(RoutineSlotEntity slot);

    @Delete
    int delete(RoutineSlotEntity slot);

    @Query("SELECT * FROM routine_slots ORDER BY minute_of_day ASC, id ASC")
    LiveData<List<RoutineSlotEntity>> observeAll();

    @Query("SELECT * FROM routine_slots ORDER BY minute_of_day ASC, id ASC")
    List<RoutineSlotEntity> all();

    @Query("SELECT * FROM routine_slots WHERE id = :id")
    RoutineSlotEntity findById(long id);

    @Query("SELECT COUNT(*) FROM routine_slots")
    int count();

    @Query("SELECT * FROM routine_slots WHERE enabled = 1 AND remind_before_min >= 0 "
            + "ORDER BY minute_of_day ASC")
    List<RoutineSlotEntity> reminderSlots();

    /**
     * The slot that is currently in progress: the latest slot whose start time
     * is at or before {@code minuteOfDay}.
     */
    @Query("SELECT * FROM routine_slots WHERE enabled = 1 AND minute_of_day <= :minuteOfDay "
            + "ORDER BY minute_of_day DESC LIMIT 1")
    RoutineSlotEntity currentSlot(int minuteOfDay);

    /** The next slot coming up, for the dashboard card and the reminder text. */
    @Query("SELECT * FROM routine_slots WHERE enabled = 1 AND minute_of_day > :minuteOfDay "
            + "ORDER BY minute_of_day ASC LIMIT 1")
    RoutineSlotEntity nextSlot(int minuteOfDay);

    @Query("SELECT * FROM routine_slots WHERE enabled = 1 AND minute_of_day > :minuteOfDay "
            + "ORDER BY minute_of_day ASC LIMIT 1")
    LiveData<RoutineSlotEntity> observeNextSlot(int minuteOfDay);

    @Query("UPDATE routine_slots SET notified_day_key = :dayKey WHERE id = :id")
    int markNotified(long id, String dayKey);

    @Query("DELETE FROM routine_slots")
    int deleteAll();

    /** Restore the thirteen-slot default schedule. */
    @Query("DELETE FROM routine_slots")
    void clearForReset();
}
