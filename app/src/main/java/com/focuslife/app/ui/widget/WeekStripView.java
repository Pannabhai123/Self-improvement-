package com.focuslife.app.ui.widget;

import android.content.Context;
import android.content.res.TypedArray;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;

import com.focuslife.app.R;
import com.focuslife.app.util.TimeUtils;

import java.util.List;

/**
 * The weekly habit calendar: seven rounded cells, one per day, filled according
 * to how much of that day was completed.
 *
 * <p>Uses a three-step fill rather than a continuous gradient because a partial
 * day and a complete day need to be distinguishable at a glance, in peripheral
 * vision, on a 720p panel. Continuous colour would require the user to compare
 * shades against each other to know whether yesterday was "done".</p>
 *
 * <ul>
 *   <li>0% → empty outline</li>
 *   <li>1-99% → half-filled in the muted colour (partial progress is real progress)</li>
 *   <li>100% → solid in the accent colour</li>
 *   <li>Today → accent outline, so "where am I" is always obvious</li>
 * </ul>
 *
 * <p>A future day is drawn dimmer than a past missed day, because a day that has
 * not arrived is not a failure and must not look like one.</p>
 */
public class WeekStripView extends View {

    private final Paint fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint strokePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint labelPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    private final RectF cellRect = new RectF();

    private int doneColor;
    private int partialColor;
    private int emptyColor;
    private int todayColor;
    private float cellSizePx;

    @NonNull
    private int[] percents = new int[0];

    @NonNull
    private List<String> dayKeys = TimeUtils.lastSevenDayKeys();

    public WeekStripView(@NonNull Context context) {
        this(context, null);
    }

    public WeekStripView(@NonNull Context context, @Nullable AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public WeekStripView(@NonNull Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init(context, attrs);
    }

    private void init(@NonNull Context context, @Nullable AttributeSet attrs) {
        doneColor = ContextCompat.getColor(context, R.color.accent_500);
        partialColor = ContextCompat.getColor(context, R.color.accent_700);
        emptyColor = ContextCompat.getColor(context, R.color.navy_700);
        todayColor = ContextCompat.getColor(context, R.color.accent_400);
        int labelColor = ContextCompat.getColor(context, R.color.text_tertiary);
        cellSizePx = getResources().getDimension(R.dimen.ring_small);

        if (attrs != null) {
            TypedArray a = context.obtainStyledAttributes(attrs, R.styleable.WeekStripView);
            try {
                doneColor = a.getColor(R.styleable.WeekStripView_wsv_doneColor, doneColor);
                partialColor = a.getColor(R.styleable.WeekStripView_wsv_partialColor, partialColor);
                emptyColor = a.getColor(R.styleable.WeekStripView_wsv_emptyColor, emptyColor);
                todayColor = a.getColor(R.styleable.WeekStripView_wsv_todayColor, todayColor);
                labelColor = a.getColor(R.styleable.WeekStripView_wsv_labelColor, labelColor);
                cellSizePx = a.getDimension(R.styleable.WeekStripView_wsv_cellSize, cellSizePx);
            } finally {
                a.recycle();
            }
        }

        strokePaint.setStyle(Paint.Style.STROKE);
        strokePaint.setStrokeWidth(2f * getResources().getDisplayMetrics().density);
        fillPaint.setStyle(Paint.Style.FILL);
        labelPaint.setColor(labelColor);
        labelPaint.setTextSize(getResources().getDimension(R.dimen.text_caption));
        labelPaint.setTextAlign(Paint.Align.CENTER);
    }

    /**
     * @param percents completion percentage per day, aligned with {@code dayKeys}
     * @param dayKeys  day keys, oldest first
     */
    public void setData(@Nullable int[] percents, @Nullable List<String> dayKeys) {
        this.percents = percents == null ? new int[0] : percents.clone();
        if (dayKeys != null && !dayKeys.isEmpty()) {
            this.dayKeys = dayKeys;
        }
        invalidate();
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        float density = getResources().getDisplayMetrics().density;
        int count = Math.max(1, dayKeys.size());
        // Cells plus a 6dp gap between them, plus room for the weekday initial.
        int desiredWidth = (int) (count * cellSizePx + (count - 1) * 6 * density);
        int desiredHeight = (int) (cellSizePx + labelPaint.getTextSize() * 2.2f);
        setMeasuredDimension(resolveSize(desiredWidth, widthMeasureSpec),
                resolveSize(desiredHeight, heightMeasureSpec));
    }

    @Override
    protected void onDraw(@NonNull Canvas canvas) {
        super.onDraw(canvas);

        int w = getWidth();
        int h = getHeight();
        if (w <= 0 || h <= 0) return;

        float density = getResources().getDisplayMetrics().density;
        float labelHeight = labelPaint.getTextSize() * 1.8f;
        float cell = Math.min(cellSizePx, (w - getPaddingLeft() - getPaddingRight()
                - (dayKeys.size() - 1) * 6 * density) / Math.max(1, dayKeys.size()));
        if (cell <= 0f) return;

        float gap = (w - getPaddingLeft() - getPaddingRight() - cell * dayKeys.size())
                / Math.max(1, dayKeys.size() - 1);
        if (gap < 0) gap = 0;

        String today = TimeUtils.today();
        float radius = cell * 0.28f;
        float top = getPaddingTop();

        for (int i = 0; i < dayKeys.size(); i++) {
            String key = dayKeys.get(i);
            float left = getPaddingLeft() + i * (cell + gap);
            cellRect.set(left, top, left + cell, top + cell);

            int percent = i < percents.length ? clamp(percents[i]) : 0;
            boolean isToday = today.equals(key);
            boolean isFuture = key.compareTo(today) > 0;

            // Fill
            if (percent >= 100) {
                fillPaint.setColor(doneColor);
                canvas.drawRoundRect(cellRect, radius, radius, fillPaint);
            } else if (percent > 0) {
                // Partial: draw the cell bottom-up to the achieved fraction, over
                // an empty base. This reads as "filled to here" rather than as a
                // shade of grey, which is the distinction that matters.
                fillPaint.setColor(isFuture ? dim(emptyColor) : emptyColor);
                canvas.drawRoundRect(cellRect, radius, radius, fillPaint);

                float fillHeight = cell * percent / 100f;
                canvas.save();
                canvas.clipRect(cellRect.left, cellRect.bottom - fillHeight,
                        cellRect.right, cellRect.bottom);
                fillPaint.setColor(partialColor);
                canvas.drawRoundRect(cellRect, radius, radius, fillPaint);
                canvas.restore();
            } else {
                fillPaint.setColor(isFuture ? dim(emptyColor) : emptyColor);
                canvas.drawRoundRect(cellRect, radius, radius, fillPaint);
            }

            // Today's outline
            if (isToday) {
                strokePaint.setColor(todayColor);
                canvas.drawRoundRect(cellRect, radius, radius, strokePaint);
            }

            // Weekday initial
            String initial = TimeUtils.weekdayInitial(key);
            labelPaint.setColor(isToday
                    ? ContextCompat.getColor(getContext(), R.color.text_primary)
                    : ContextCompat.getColor(getContext(), R.color.text_tertiary));
            canvas.drawText(initial, cellRect.centerX(), top + cell + labelHeight * 0.62f, labelPaint);
        }
    }

    private static int clamp(int v) {
        return v < 0 ? 0 : Math.min(v, 100);
    }

    /** Reduces a colour's alpha so future days recede visually. */
    private static int dim(int color) {
        return android.graphics.Color.argb(
                android.graphics.Color.alpha(color) / 2,
                android.graphics.Color.red(color),
                android.graphics.Color.green(color),
                android.graphics.Color.blue(color));
    }
}
