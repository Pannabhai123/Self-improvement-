package com.focuslife.app.data.db.entity;

import androidx.annotation.NonNull;
import androidx.room.ColumnInfo;
import androidx.room.Entity;
import androidx.room.Index;
import androidx.room.PrimaryKey;

/**
 * A chapter / topic inside an exam syllabus.
 *
 * <p>Models the four target exams (Railway ALP, Railway Group D, SSC CGL,
 * SSC GD) and their six subjects. {@code targetMinutes} is the chapter-wise
 * goal; {@code studiedMinutes} accumulates from {@link StudyLogEntity}.</p>
 */
@Entity(tableName = "study_topics",
        indices = {@Index("exam"), @Index("subject"), @Index("day_key")})
public class StudyTopicEntity {

    @PrimaryKey(autoGenerate = true)
    public long id;

    /** One of the {@code Exam} constants below. */
    @ColumnInfo(name = "exam")
    @NonNull
    public String exam = EXAM_RAILWAY_ALP;

    /** One of the {@code Subject} constants below. */
    @ColumnInfo(name = "subject")
    @NonNull
    public String subject = SUBJECT_MATHS;

    @ColumnInfo(name = "chapter")
    @NonNull
    public String chapter = "";

    /** Chapter-wise goal in minutes. */
    @ColumnInfo(name = "target_minutes")
    public int targetMinutes;

    /** Minutes actually studied against this topic. */
    @ColumnInfo(name = "studied_minutes")
    public int studiedMinutes;

    /** Revision passes completed. Drives the revision reminder. */
    @ColumnInfo(name = "revisions_done")
    public int revisionsDone;

    /** Epoch millis of the next scheduled revision; 0 if none. */
    @ColumnInfo(name = "next_revision_at")
    public long nextRevisionAt;

    @ColumnInfo(name = "completed")
    public boolean completed;

    /** {@code yyyy-MM-dd} this topic was assigned to. */
    @ColumnInfo(name = "day_key")
    @NonNull
    public String dayKey = "";

    /** 0-5 self rating captured after finishing, used in weekly statistics. */
    @ColumnInfo(name = "confidence")
    public int confidence;

    @ColumnInfo(name = "created_at")
    public long createdAt;

    public StudyTopicEntity() {
    }

    // ------------------------------------------------------------------
    //  Exams
    // ------------------------------------------------------------------
    public static final String EXAM_RAILWAY_ALP   = "Railway ALP";
    public static final String EXAM_RAILWAY_GROUP_D = "Railway Group D";
    public static final String EXAM_SSC_CGL       = "SSC CGL";
    public static final String EXAM_SSC_GD        = "SSC GD";

    public static final String[] ALL_EXAMS = {
            EXAM_RAILWAY_ALP, EXAM_RAILWAY_GROUP_D, EXAM_SSC_CGL, EXAM_SSC_GD
    };

    // ------------------------------------------------------------------
    //  Subjects
    // ------------------------------------------------------------------
    public static final String SUBJECT_MATHS     = "Mathematics";
    public static final String SUBJECT_REASONING = "Reasoning";
    public static final String SUBJECT_BIOLOGY   = "Biology";
    public static final String SUBJECT_PHYSICS   = "Physics";
    public static final String SUBJECT_CHEMISTRY = "Chemistry";
    public static final String SUBJECT_GK        = "General Knowledge";

    public static final String[] ALL_SUBJECTS = {
            SUBJECT_MATHS, SUBJECT_REASONING, SUBJECT_BIOLOGY,
            SUBJECT_PHYSICS, SUBJECT_CHEMISTRY, SUBJECT_GK
    };
}
