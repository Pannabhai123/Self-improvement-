package com.focuslife.app.data.db.dao;

import androidx.lifecycle.LiveData;
import androidx.room.Dao;
import androidx.room.Delete;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;
import androidx.room.Transaction;
import androidx.room.Update;

import com.focuslife.app.data.db.entity.StudyLogEntity;
import com.focuslife.app.data.db.entity.StudyTopicEntity;

import java.util.List;

/**
 * Combined DAO for the study planner: chapter-wise topics plus the timed log.
 */
@Dao
public interface StudyDao {

    // ---------------------------------------------------------------
    //  Topics
    // ---------------------------------------------------------------

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    long insertTopic(StudyTopicEntity topic);

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    List<Long> insertTopics(List<StudyTopicEntity> topics);

    @Update
    int updateTopic(StudyTopicEntity topic);

    @Delete
    int deleteTopic(StudyTopicEntity topic);

    @Query("SELECT * FROM study_topics WHERE id = :id")
    StudyTopicEntity findTopic(long id);

    @Query("SELECT * FROM study_topics ORDER BY exam ASC, subject ASC, id ASC")
    LiveData<List<StudyTopicEntity>> observeAllTopics();

    /**
     * Synchronous read of every topic.
     *
     * <p>The planner filters by exam and subject in memory, because the filters
     * change on every chip tap and a syllabus is a few hundred rows at most —
     * four extra filtered queries per tap costs more than one full read.</p>
     */
    @Query("SELECT * FROM study_topics ORDER BY exam ASC, subject ASC, id ASC")
    List<StudyTopicEntity> allTopics();

    @Query("SELECT * FROM study_topics WHERE exam = :exam ORDER BY subject ASC, id ASC")
    LiveData<List<StudyTopicEntity>> observeTopicsForExam(String exam);

    @Query("SELECT * FROM study_topics WHERE subject = :subject ORDER BY exam ASC, id ASC")
    LiveData<List<StudyTopicEntity>> observeTopicsForSubject(String subject);

    @Query("SELECT * FROM study_topics WHERE day_key = :dayKey ORDER BY subject ASC, id ASC")
    LiveData<List<StudyTopicEntity>> observeTopicsForDay(String dayKey);

    /** Topics whose revision is due (next_revision_at in the past, not finished). */
    @Query("SELECT * FROM study_topics WHERE next_revision_at > 0 AND next_revision_at <= :now "
            + "ORDER BY next_revision_at ASC")
    LiveData<List<StudyTopicEntity>> observeRevisionsDue(long now);

    /**
     * Synchronous variant, limited to {@code limit} rows. Workers cannot observe
     * LiveData, and a reminder only needs the first due topic for its text.
     */
    @Query("SELECT * FROM study_topics WHERE next_revision_at > 0 AND next_revision_at <= :now "
            + "ORDER BY next_revision_at ASC LIMIT :limit")
    List<StudyTopicEntity> dueTopics(long now, int limit);

    @Query("SELECT COUNT(*) FROM study_topics WHERE next_revision_at > 0 AND next_revision_at <= :now")
    int revisionsDueCount(long now);

    @Query("SELECT COUNT(*) FROM study_topics WHERE completed = 1")
    int completedTopicCount();

    @Query("SELECT COUNT(*) FROM study_topics")
    int topicCount();

    @Query("UPDATE study_topics SET studied_minutes = studied_minutes + :minutes WHERE id = :topicId")
    int addMinutesToTopic(long topicId, int minutes);

    @Query("UPDATE study_topics SET revisions_done = revisions_done + 1, next_revision_at = :next WHERE id = :topicId")
    int markRevised(long topicId, long next);

    // ---------------------------------------------------------------
    //  Study log
    // ---------------------------------------------------------------

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    long insertLog(StudyLogEntity log);

    @Delete
    int deleteLog(StudyLogEntity log);

    @Query("SELECT * FROM study_logs WHERE day_key = :dayKey ORDER BY start_time DESC")
    LiveData<List<StudyLogEntity>> observeLogsForDay(String dayKey);

    @Query("SELECT * FROM study_logs ORDER BY start_time DESC LIMIT :limit")
    LiveData<List<StudyLogEntity>> observeRecentLogs(int limit);

    @Query("SELECT COALESCE(SUM(minutes), 0) FROM study_logs WHERE day_key = :dayKey")
    int minutesOnDay(String dayKey);

    @Query("SELECT COALESCE(SUM(minutes), 0) FROM study_logs WHERE day_key = :dayKey")
    LiveData<Integer> observeMinutesOnDay(String dayKey);

    @Query("SELECT COALESCE(SUM(minutes), 0) FROM study_logs WHERE day_key BETWEEN :from AND :to")
    int minutesBetween(String from, String to);

    /** Weekly statistics: minutes per subject over a date range. */
    @Query("SELECT subject AS label, COALESCE(SUM(minutes), 0) AS value "
            + "FROM study_logs WHERE day_key BETWEEN :from AND :to "
            + "GROUP BY subject ORDER BY value DESC")
    List<FocusSessionDao.ChartPoint> minutesPerSubject(String from, String to);

    /** Daily progress chart data. */
    @Query("SELECT day_key AS label, COALESCE(SUM(minutes), 0) AS value "
            + "FROM study_logs WHERE day_key BETWEEN :from AND :to "
            + "GROUP BY day_key ORDER BY day_key ASC")
    List<FocusSessionDao.ChartPoint> minutesPerDay(String from, String to);

    /** Minutes logged for one subject on one day. */
    @Query("SELECT COALESCE(SUM(minutes), 0) FROM study_logs WHERE day_key = :dayKey AND subject = :subject")
    int minutesForSubjectOnDay(String dayKey, String subject);

    /**
     * Record a study block and, when it belongs to a topic, credit that topic in
     * one transaction so the two can never drift apart.
     */
    @Transaction
    default void recordStudy(StudyLogEntity log) {
        insertLog(log);
        if (log.topicId > 0 && log.minutes > 0) {
            addMinutesToTopic(log.topicId, log.minutes);
        }
    }

    @Query("DELETE FROM study_logs")
    int deleteAllLogs();

    @Query("DELETE FROM study_topics")
    int deleteAllTopics();
}
