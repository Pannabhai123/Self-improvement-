package com.focuslife.app.data.db.entity;

import androidx.annotation.NonNull;
import androidx.room.ColumnInfo;
import androidx.room.Entity;
import androidx.room.Index;
import androidx.room.PrimaryKey;

/**
 * One timed study block. Produced by the study timer, and aggregated into
 * daily and weekly statistics.
 */
@Entity(tableName = "study_logs", indices = {@Index("day_key"), @Index("subject")})
public class StudyLogEntity {

    @PrimaryKey(autoGenerate = true)
    public long id;

    @ColumnInfo(name = "subject")
    @NonNull
    public String subject = StudyTopicEntity.SUBJECT_MATHS;

    @ColumnInfo(name = "exam")
    @NonNull
    public String exam = StudyTopicEntity.EXAM_RAILWAY_ALP;

    /** 0 when the block was not tied to a specific topic row. */
    @ColumnInfo(name = "topic_id")
    public long topicId;

    @ColumnInfo(name = "minutes")
    public int minutes;

    @ColumnInfo(name = "start_time")
    public long startTime;

    @ColumnInfo(name = "day_key")
    @NonNull
    public String dayKey = "";

    /** "timer" when driven by the study timer, "manual" when typed in. */
    @ColumnInfo(name = "source")
    @NonNull
    public String source = "timer";

    public StudyLogEntity() {
    }
}
