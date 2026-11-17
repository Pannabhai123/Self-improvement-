package com.focuslife.app.data.db.entity;

import androidx.annotation.NonNull;
import androidx.room.ColumnInfo;
import androidx.room.Entity;
import androidx.room.PrimaryKey;

/**
 * A personal "why" — the reason the user is avoiding distractions. Shown on the
 * block warning screen, which is the moment it has the most force.
 */
@Entity(tableName = "reasons")
public class ReasonEntity {

    @PrimaryKey(autoGenerate = true)
    public long id;

    @ColumnInfo(name = "text")
    @NonNull
    public String text = "";

    @ColumnInfo(name = "sort_order")
    public int sortOrder;

    @ColumnInfo(name = "times_shown")
    public int timesShown;

    @ColumnInfo(name = "created_at")
    public long createdAt;

    public ReasonEntity() {
    }

    public ReasonEntity(@NonNull String text, int sortOrder) {
        this.text = text;
        this.sortOrder = sortOrder;
    }
}
