package com.focuslife.app.ui.widget;

import android.content.Context;
import android.content.res.TypedArray;
import android.graphics.Canvas;
import android.graphics.DashPathEffect;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;

import com.focuslife.app.R;

/**
 * A seven-bar chart for the weekly views.
 *
 * <p>Drawn by hand rather than pulled from a charting library. The whole of
 * Focus Life needs exactly one chart shape — a small number of labelled vertical
 * bars — and MPAndroidChart alone would add roughly a megabyte to an APK that is
 * meant to stay light on a 4 GB device.</p>
 *
 * <h2>Deliberate design choices</h2>
 * <ul>
 *   <li><b>No gridlines.</b> With seven bars and a labelled axis, gridlines add
 *       ink without adding information.</li>
 *   <li><b>A single optional target line.</b> One reference line ("your daily
 *       goal") is the comparison the user actually wants; a full grid is not.</li>
 *   <li><b>The last bar is highlighted.</b> Today is the bar the user cares
 *       about, so it uses the accent colour while the rest stay muted.</li>
 *   <li><b>Zero is drawn as a stub, not as nothing.</b> A day with no data still
 *       gets a 2dp nub, so a week of inactivity reads as "seven empty days"
 *       rather than as a broken chart.</li>
 * </ul>
 */
public class BarChartView extends View {

    private final Paint barPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint trackPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint labelPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint valuePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint targetPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    private final RectF barRect = new RectF();

    @NonNull
    private int[] values = new int[0];

    @NonNull
    private String[] labels = new String[0];

    private int barColor;
    private int highlightColor;
    private int trackColor;
    private float barRadiusPx;
    private boolean showValues = true;
    private boolean showTrack = true;
    private boolean highlightLast = true;
    private int targetPercent = -1;
    @NonNull
    private String valueSuffix = "";

    /** A bar shorter than this is still drawn, so empty days stay visible. */
    private float minBarPx;

    public BarChartView(@NonNull Context context) {
        this(context, null);
    }

    public BarChartView(@NonNull Context context, @Nullable AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public BarChartView(@NonNull Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init(context, attrs);
    }

    private void init(@NonNull Context context, @Nullable AttributeSet attrs) {
        float density = getResources().getDisplayMetrics().density;

        barColor = ContextCompat.getColor(context, R.color.navy_600);
        highlightColor = ContextCompat.getColor(context, R.color.accent_500);
        trackColor = ContextCompat.getColor(context, R.color.accent_track);
        int labelColor = ContextCompat.getColor(context, R.color.text_tertiary);
        int valueColor = ContextCompat.getColor(context, R.color.text_secondary);
        int targetColor = ContextCompat.getColor(context, R.color.warning_500);
        barRadiusPx = getResources().getDimension(R.dimen.chart_bar_radius);
        float labelSize = getResources().getDimension(R.dimen.text_caption);
        float valueSize = getResources().getDimension(R.dimen.text_caption);

        if (attrs != null) {
            TypedArray a = context.obtainStyledAttributes(attrs, R.styleable.BarChartView);
            try {
                barColor = a.getColor(R.styleable.BarChartView_bcv_barColor, barColor);
                highlightColor = a.getColor(R.styleable.BarChartView_bcv_barColorSecondary, highlightColor);
                trackColor = a.getColor(R.styleable.BarChartView_bcv_trackColor, trackColor);
                labelColor = a.getColor(R.styleable.BarChartView_bcv_labelColor, labelColor);
                valueColor = a.getColor(R.styleable.BarChartView_bcv_valueColor, valueColor);
                barRadiusPx = a.getDimension(R.styleable.BarChartView_bcv_barRadius, barRadiusPx);
                labelSize = a.getDimension(R.styleable.BarChartView_bcv_labelTextSize, labelSize);
                valueSize = a.getDimension(R.styleable.BarChartView_bcv_valueTextSize, valueSize);
                showValues = a.getBoolean(R.styleable.BarChartView_bcv_showValues, showValues);
                showTrack = a.getBoolean(R.styleable.BarChartView_bcv_showTrack, showTrack);
                highlightLast = a.getBoolean(R.styleable.BarChartView_bcv_highlightLast, highlightLast);
                targetPercent = a.getInt(R.styleable.BarChartView_bcv_targetPercent, targetPercent);
                targetColor = a.getColor(R.styleable.BarChartView_bcv_targetColor, targetColor);
                String suffix = a.getString(R.styleable.BarChartView_bcv_valueSuffix);
                if (suffix != null) valueSuffix = suffix;
            } finally {
                a.recycle();
            }
        }

        minBarPx = 2f * density;

        barPaint.setStyle(Paint.Style.FILL);
        trackPaint.setStyle(Paint.Style.FILL);
        trackPaint.setColor(trackColor);

        labelPaint.setColor(labelColor);
        labelPaint.setTextSize(labelSize);
        labelPaint.setTextAlign(Paint.Align.CENTER);

        valuePaint.setColor(valueColor);
        valuePaint.setTextSize(valueSize);
        valuePaint.setTextAlign(Paint.Align.CENTER);

        targetPaint.setColor(targetColor);
        targetPaint.setStyle(Paint.Style.STROKE);
        targetPaint.setStrokeWidth(1.5f * density);
        targetPaint.setPathEffect(new DashPathEffect(new float[]{6f * density, 5f * density}, 0f));
    }

    // ------------------------------------------------------------------
    //  Data
    // ------------------------------------------------------------------

    /**
     * Sets the chart data.
     *
     * @param values one per bar; negative entries are clamped to zero
     * @param labels axis labels, one per bar. May be shorter than {@code values},
     *               in which case the extra bars are drawn unlabelled.
     */
    public void setData(@Nullable int[] values, @Nullable String[] labels) {
        this.values = values == null ? new int[0] : values.clone();
        this.labels = labels == null ? new String[0] : labels.clone();
        requestLayout();
        invalidate();
    }

    /** Convenience for the common "seven days ending today" case. */
    public void setWeekData(@Nullable int[] values, @NonNull java.util.List<String> dayKeys) {
        String[] out = new String[dayKeys.size()];
        for (int i = 0; i < dayKeys.size(); i++) {
            out[i] = com.focuslife.app.util.TimeUtils.shortWeekday(dayKeys.get(i));
        }
        setData(values, out);
    }

    public void setValueSuffix(@NonNull String suffix) {
        this.valueSuffix = suffix;
        invalidate();
    }

    public void setTargetPercent(int percent) {
        this.targetPercent = percent;
        invalidate();
    }

    public void setHighlightLast(boolean highlight) {
        this.highlightLast = highlight;
        invalidate();
    }

    public void setBarColors(int muted, int highlighted) {
        this.barColor = muted;
        this.highlightColor = highlighted;
        invalidate();
    }

    public void setShowValues(boolean show) {
        this.showValues = show;
        invalidate();
    }

    // ------------------------------------------------------------------
    //  Drawing
    // ------------------------------------------------------------------

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int desiredHeight = (int) getResources().getDimension(R.dimen.chart_height);
        int desiredWidth = getSuggestedMinimumWidth();
        setMeasuredDimension(
                resolveSize(Math.max(desiredWidth, (int) (300 * getResources().getDisplayMetrics().density)),
                        widthMeasureSpec),
                resolveSize(desiredHeight, heightMeasureSpec));
    }

    @Override
    protected void onDraw(@NonNull Canvas canvas) {
        super.onDraw(canvas);

        int w = getWidth();
        int h = getHeight();
        if (w <= 0 || h <= 0 || values.length == 0) return;

        float paddingLeft = getPaddingLeft();
        float paddingRight = getPaddingRight();
        float paddingTop = getPaddingTop();
        float paddingBottom = getPaddingBottom();

        float labelHeight = labelPaint.getTextSize() * 1.6f;
        float valueHeight = showValues ? valuePaint.getTextSize() * 1.7f : 0f;

        float chartTop = paddingTop + valueHeight;
        float chartBottom = h - paddingBottom - labelHeight;
        float chartHeight = chartBottom - chartTop;
        if (chartHeight <= minBarPx) return;

        float chartWidth = w - paddingLeft - paddingRight;
        int n = values.length;
        // Bars take 60% of each slot; the gap keeps them visually separate
        // without making the chart feel striped.
        float slot = chartWidth / n;
        float barWidth = Math.min(slot * 0.6f, 34f * getResources().getDisplayMetrics().density);
        if (barWidth < 4f) barWidth = Math.max(4f, slot * 0.8f);

        int max = 0;
        for (int v : values) {
            if (v > max) max = v;
        }
        // A target line above the data must still be visible, so it participates
        // in the scale; otherwise the line would be drawn off the top.
        if (targetPercent > 0 && max > 0) {
            max = Math.max(max, (int) Math.ceil(max * targetPercent / 100.0));
        }
        if (max <= 0) max = 1;

        // Track behind each bar.
        if (showTrack) {
            for (int i = 0; i < n; i++) {
                float cx = paddingLeft + slot * i + slot / 2f;
                barRect.set(cx - barWidth / 2f, chartTop, cx + barWidth / 2f, chartBottom);
                canvas.drawRoundRect(barRect, barRadiusPx, barRadiusPx, trackPaint);
            }
        }

        // Target reference line.
        if (targetPercent > 0) {
            float y = chartBottom - chartHeight * targetPercent / 100f;
            canvas.drawLine(paddingLeft, y, w - paddingRight, y, targetPaint);
        }

        // Bars.
        for (int i = 0; i < n; i++) {
            int value = Math.max(0, values[i]);
            float barHeight = chartHeight * value / max;
            if (value > 0 && barHeight < minBarPx) barHeight = minBarPx;

            float cx = paddingLeft + slot * i + slot / 2f;
            float top = chartBottom - barHeight;

            boolean isToday = highlightLast && i == n - 1;
            barPaint.setColor(isToday ? highlightColor : barColor);

            if (barHeight > 0f) {
                barRect.set(cx - barWidth / 2f, top, cx + barWidth / 2f, chartBottom);
                canvas.drawRoundRect(barRect, barRadiusPx, barRadiusPx, barPaint);
            }

            // Value above the bar.
            if (showValues && value > 0) {
                String text = value + valueSuffix;
                float textY = top - valuePaint.getTextSize() * 0.45f;
                if (textY < paddingTop + valuePaint.getTextSize()) {
                    textY = paddingTop + valuePaint.getTextSize();
                }
                valuePaint.setColor(isToday ? highlightColor : ContextCompat.getColor(
                        getContext(), R.color.text_secondary));
                canvas.drawText(text, cx, textY, valuePaint);
            }

            // Axis label.
            if (i < labels.length && labels[i] != null) {
                float textY = chartBottom + labelHeight * 0.72f;
                labelPaint.setColor(isToday
                        ? ContextCompat.getColor(getContext(), R.color.text_primary)
                        : ContextCompat.getColor(getContext(), R.color.text_tertiary));
                canvas.drawText(labels[i], cx, textY, labelPaint);
            }
        }
    }
}
