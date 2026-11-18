package com.focuslife.app.data.db.entity;

import androidx.annotation.NonNull;
import androidx.room.ColumnInfo;
import androidx.room.Entity;
import androidx.room.Index;
import androidx.room.PrimaryKey;

/**
 * The "Distraction diary": what pulled the user away, when, and for how long.
 *
 * <p>Aggregating {@link #trigger} over a week is what powers "identify common
 * distraction triggers" in the CONTROL YOURSELF screen.</p>
 */
@Entity(tableName = "distraction_logs", indices = {@Index("day_key"), @Index("trigger")})
public class DistractionLogEntity {

    @PrimaryKey(autoGenerate = true)
    public long id;

    @ColumnInfo(name = "day_key")
    @NonNull
    public String dayKey = "";

    @ColumnInfo(name = "logged_at")
    public long loggedAt;

    /** Free text, or one of the {@code TRIGGER_} presets. */
    @ColumnInfo(name = "trigger")
    @NonNull
    public String trigger = "";

    /** Package name if a specific app caused it; empty otherwise. */
    @ColumnInfo(name = "package_name")
    @NonNull
    public String packageName = "";

    /** Rough minutes lost. */
    @ColumnInfo(name = "minutes_lost")
    public int minutesLost;

    /** What the user was supposed to be doing. */
    @ColumnInfo(name = "intended_task")
    @NonNull
    public String intendedTask = "";

    /** 1-5: how strong the urge felt. Used for trigger analysis. */
    @ColumnInfo(name = "urge_level")
    public int urgeLevel;

    /** true when the user resisted and returned to the task. */
    @ColumnInfo(name = "resisted")
    public boolean resisted;

    /** Which 5-minute alternative activity was chosen, if any. */
    @ColumnInfo(name = "alternative_used")
    @NonNull
    public String alternativeUsed = "";

    /** "self" when the user logged it, "blocked" when the blocker logged it. */
    @ColumnInfo(name = "source")
    @NonNull
    public String source = "self";

    // Preset trigger keys (resolved to strings at display time so they translate)
    public static final String TRIGGER_SOCIAL_MEDIA = "social_media";
    public static final String TRIGGER_GAMES        = "games";
    public static final String TRIGGER_VIDEO        = "video";
    public static final String TRIGGER_MESSAGES     = "messages";
    public static final String TRIGGER_BOREDOM      = "boredom";
    public static final String TRIGGER_STRESS       = "stress";
    public static final String TRIGGER_HUNGER       = "hunger";
    public static final String TRIGGER_SLEEPINESS   = "sleepiness";
    public static final String TRIGGER_OTHER        = "other";

    public static final String[] PRESET_TRIGGERS = {
            TRIGGER_SOCIAL_MEDIA, TRIGGER_GAMES, TRIGGER_VIDEO, TRIGGER_MESSAGES,
            TRIGGER_BOREDOM, TRIGGER_STRESS, TRIGGER_HUNGER, TRIGGER_SLEEPINESS,
            TRIGGER_OTHER
    };

    public DistractionLogEntity() {
    }
}
