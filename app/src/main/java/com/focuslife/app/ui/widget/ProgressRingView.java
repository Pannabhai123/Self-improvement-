package com.focuslife.app.ui.widget;

import android.animation.ValueAnimator;
import android.content.Context;
import android.content.res.TypedArray;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.SweepGradient;
import android.util.AttributeSet;
import android.view.View;
import android.view.animation.DecelerateInterpolator;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;

import com.focuslife.app.R;

/**
 * The 0-100% daily progress circle.
 *
 * <p>Drawn directly rather than using a {@code CircularProgressDrawable} or a
 * library, for three reasons:</p>
 * <ol>
 *   <li><b>Size.</b> No dependency, and the whole view is ~200 lines.</li>
 *   <li><b>Control.</b> The ring needs a sweep gradient, a rounded cap and a
 *       centred multi-line label, which stock drawables do not compose well.</li>
 *   <li><b>Performance.</b> One {@code drawArc} per frame on a hardware layer is
 *       cheap enough for a Helio G85 even while the dashboard scrolls.</li>
 * </ol>
 *
 * <h2>Accessibility</h2>
 * A progress ring conveys a number purely visually, so the view publishes a
 * content description that includes the percentage and exposes
 * {@code className=ProgressBar} to TalkBack. The parent screen also renders the
 * number as real text, so the information is never colour-only or graphic-only.
 */
public class ProgressRingView extends View {

    private final Paint trackPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint progressPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint valuePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint labelPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint glowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    private final RectF arcRect = new RectF();

    private int progress;          // animated, 0..max
    private int targetProgress;    // requested, 0..max
    private int max = 100;
    private float strokePx;
    private float trackStrokePx;
    private boolean showGlow = true;
    private boolean animateChanges = true;

    @Nullable
    private CharSequence valueText;
    @Nullable
    private CharSequence labelText;

    @Nullable
    private ValueAnimator animator;

    /** Start angle: 12 o'clock, sweeping clockwise. */
    private static final float START_ANGLE = -90f;

    public ProgressRingView(@NonNull Context context) {
        this(context, null);
    }

    public ProgressRingView(@NonNull Context context, @Nullable AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public ProgressRingView(@NonNull Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init(context, attrs);
    }

    private void init(@NonNull Context context, @Nullable AttributeSet attrs) {
        float density = getResources().getDisplayMetrics().density;
        strokePx = getResources().getDimension(R.dimen.ring_stroke);
        trackStrokePx = strokePx;

        int trackColor = ContextCompat.getColor(context, R.color.accent_track);
        int progressColor = ContextCompat.getColor(context, R.color.accent_500);
        int valueColor = ContextCompat.getColor(context, R.color.text_primary);
        int labelColor = ContextCompat.getColor(context, R.color.text_secondary);
        float valueSize = getResources().getDimension(R.dimen.text_headline);
        float labelSize = getResources().getDimension(R.dimen.text_caption);

        if (attrs != null) {
            TypedArray a = context.obtainStyledAttributes(attrs, R.styleable.ProgressRingView);
            try {
                max = a.getInt(R.styleable.ProgressRingView_prv_max, max);
                targetProgress = a.getInt(R.styleable.ProgressRingView_prv_progress, 0);
                trackColor = a.getColor(R.styleable.ProgressRingView_prv_trackColor, trackColor);
                progressColor = a.getColor(R.styleable.ProgressRingView_prv_progressColor, progressColor);
                valueColor = a.getColor(R.styleable.ProgressRingView_prv_valueColor, valueColor);
                labelColor = a.getColor(R.styleable.ProgressRingView_prv_labelColor, labelColor);
                strokePx = a.getDimension(R.styleable.ProgressRingView_prv_strokeWidth, strokePx);
                trackStrokePx = a.getDimension(R.styleable.ProgressRingView_prv_trackStrokeWidth, strokePx);
                valueSize = a.getDimension(R.styleable.ProgressRingView_prv_valueTextSize, valueSize);
                labelSize = a.getDimension(R.styleable.ProgressRingView_prv_labelTextSize, labelSize);
                showGlow = a.getBoolean(R.styleable.ProgressRingView_prv_showGlow, showGlow);
                animateChanges = a.getBoolean(R.styleable.ProgressRingView_prv_animate, animateChanges);
                valueText = a.getText(R.styleable.ProgressRingView_prv_valueText);
                labelText = a.getText(R.styleable.ProgressRingView_prv_labelText);
            } finally {
                a.recycle();
            }
        }

        if (max <= 0) max = 100;
        targetProgress = clampProgress(targetProgress);

        trackPaint.setStyle(Paint.Style.STROKE);
        trackPaint.setStrokeWidth(trackStrokePx);
        trackPaint.setColor(trackColor);
        trackPaint.setStrokeCap(Paint.Cap.ROUND);

        progressPaint.setStyle(Paint.Style.STROKE);
        progressPaint.setStrokeWidth(strokePx);
        progressPaint.setColor(progressColor);
        progressPaint.setStrokeCap(Paint.Cap.ROUND);

        glowPaint.setStyle(Paint.Style.STROKE);
        glowPaint.setStrokeWidth(strokePx * 2.6f);
        glowPaint.setColor(progressColor);
        // A very low alpha wide stroke reads as a soft bloom without the cost of
        // a blur mask filter, which is expensive on this class of GPU.
        glowPaint.setAlpha(38);
        glowPaint.setStrokeCap(Paint.Cap.ROUND);

        valuePaint.setColor(valueColor);
        valuePaint.setTextSize(valueSize);
        valuePaint.setTextAlign(Paint.Align.CENTER);
        valuePaint.setFakeBoldText(true);

        labelPaint.setColor(labelColor);
        labelPaint.setTextSize(labelSize);
        labelPaint.setTextAlign(Paint.Align.CENTER);

        // Text should not be scaled by the user's font setting inside a fixed
        // ring, or it would overflow the circle; the surrounding screen provides
        // a properly scalable duplicate of the same number.
        valuePaint.setTextScaleX(1f);

        setLayerType(LAYER_TYPE_SOFTWARE, null);
        updateContentDescription();
    }

    // ------------------------------------------------------------------
    //  Public API
    // ------------------------------------------------------------------

    /**
     * Sets the displayed progress.
     *
     * @param value    target value
     * @param animate  whether to tween from the current value
     */
    public void setProgress(int value, boolean animate) {
        value = clampProgress(value);
        if (value == targetProgress && animator == null) {
            invalidate();
            return;
        }
        targetProgress = value;
        updateContentDescription();

        if (!animate || !animateChanges || !isAttachedToWindow()) {
            cancelAnimator();
            progress = value;
            invalidate();
            return;
        }

        cancelAnimator();
        animator = ValueAnimator.ofInt(progress, value);
        // 420 ms with deceleration: perceptible as motion, never as delay.
        animator.setDuration(420L);
        animator.setInterpolator(new DecelerateInterpolator());
        animator.addUpdateListener(a -> {
            progress = (Integer) a.getAnimatedValue();
            invalidate();
        });
        animator.start();
    }

    public void setProgress(int value) {
        setProgress(value, true);
    }

    public int getProgress() {
        return targetProgress;
    }

    public void setMax(int max) {
        this.max = max <= 0 ? 100 : max;
        this.targetProgress = clampProgress(this.targetProgress);
        this.progress = clampProgress(this.progress);
        updateContentDescription();
        invalidate();
    }

    public int getMax() {
        return max;
    }

    /** Sets both the large centred value and the small caption beneath it. */
    public void setTexts(@Nullable CharSequence value, @Nullable CharSequence label) {
        this.valueText = value;
        this.labelText = label;
        invalidate();
    }

    public void setValueText(@Nullable CharSequence value) {
        this.valueText = value;
        invalidate();
    }

    public void setLabelText(@Nullable CharSequence label) {
        this.labelText = label;
        invalidate();
    }

    public void setRingColor(int color) {
        progressPaint.setColor(color);
        glowPaint.setColor(color);
        glowPaint.setAlpha(38);
        invalidate();
    }

    public void setShowGlow(boolean show) {
        this.showGlow = show;
        invalidate();
    }

    // ------------------------------------------------------------------
    //  Drawing
    // ------------------------------------------------------------------

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        // A ring is square by definition. Take the smaller of the two proposed
        // dimensions so a ring inside a horizontal LinearLayout cannot be
        // stretched into an ellipse.
        int w = MeasureSpec.getSize(widthMeasureSpec);
        int h = MeasureSpec.getSize(heightMeasureSpec);
        int desired = Math.min(w, h);

        int defaultSize = (int) (getResources().getDimension(R.dimen.ring_small) + 0.5f);
        if (desired <= 0) desired = defaultSize;

        setMeasuredDimension(resolveSize(desired, widthMeasureSpec),
                resolveSize(desired, heightMeasureSpec));
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        rebuildGradient(w, h);
    }

    /**
     * Applies a sweep gradient so the leading edge of the arc is brighter than
     * the trailing edge. This is what makes the ring read as "progress" rather
     * than as a static circle.
     */
    private void rebuildGradient(int w, int h) {
        if (w <= 0 || h <= 0) return;
        try {
            int base = progressPaint.getColor();
            int lighter = lighten(base, 0.35f);
            float cx = w / 2f;
            float cy = h / 2f;
            float radius = Math.max(1f, Math.min(cx, cy));
            SweepGradient gradient = new SweepGradient(cx, cy,
                    new int[]{base, lighter, base},
                    new float[]{0f, 0.5f, 1f});
            android.graphics.Matrix matrix = new android.graphics.Matrix();
            matrix.postRotate(START_ANGLE, cx, cy);
            gradient.setLocalMatrix(matrix);
            progressPaint.setShader(gradient);
        } catch (Exception e) {
            // A shader failure must not break the ring; fall back to flat colour.
            progressPaint.setShader(null);
        }
    }

    @Override
    protected void onDraw(@NonNull Canvas canvas) {
        super.onDraw(canvas);

        int w = getWidth();
        int h = getHeight();
        if (w <= 0 || h <= 0) return;

        float cx = w / 2f;
        float cy = h / 2f;
        float maxStroke = Math.max(strokePx, trackStrokePx);
        // Inset by half the widest stroke (plus a hair for the glow) so the arc
        // is never clipped by the view bounds.
        float inset = maxStroke / 2f + (showGlow ? strokePx * 0.9f : 1f);
        float radius = Math.min(cx, cy) - inset;
        if (radius <= 0f) return;

        arcRect.set(cx - radius, cy - radius, cx + radius, cy + radius);

        // Track: full circle.
        canvas.drawArc(arcRect, 0f, 360f, false, trackPaint);

        float sweep = max <= 0 ? 0f : (progress * 360f / max);
        if (sweep > 0.1f) {
            if (showGlow) {
                canvas.drawArc(arcRect, START_ANGLE, sweep, false, glowPaint);
            }
            canvas.drawArc(arcRect, START_ANGLE, sweep, false, progressPaint);
        }

        drawCenterText(canvas, cx, cy);
    }

    private void drawCenterText(@NonNull Canvas canvas, float cx, float cy) {
        boolean hasValue = valueText != null && valueText.length() > 0;
        boolean hasLabel = labelText != null && labelText.length() > 0;
        if (!hasValue && !hasLabel) return;

        Paint.FontMetrics valueFm = valuePaint.getFontMetrics();
        Paint.FontMetrics labelFm = labelPaint.getFontMetrics();

        float valueHeight = hasValue ? (valueFm.descent - valueFm.ascent) : 0f;
        float labelHeight = hasLabel ? (labelFm.descent - labelFm.ascent) : 0f;
        float gap = hasValue && hasLabel ? valueHeight * 0.12f : 0f;
        float totalHeight = valueHeight + gap + labelHeight;

        // Vertically centre the combined block.
        float top = cy - totalHeight / 2f;

        if (hasValue) {
            float baseline = top - valueFm.ascent;
            canvas.drawText(valueText.toString(), cx, baseline, valuePaint);
            top += valueHeight + gap;
        }
        if (hasLabel) {
            float baseline = top - labelFm.ascent;
            canvas.drawText(labelText.toString(), cx, baseline, labelPaint);
        }
    }

    // ------------------------------------------------------------------
    //  Helpers
    // ------------------------------------------------------------------

    private int clampProgress(int value) {
        if (value < 0) return 0;
        return Math.min(value, max);
    }

    /** Lightens a colour toward white by {@code amount} (0..1). */
    private static int lighten(int color, float amount) {
        float a = Math.max(0f, Math.min(1f, amount));
        int r = android.graphics.Color.red(color);
        int g = android.graphics.Color.green(color);
        int b = android.graphics.Color.blue(color);
        int alpha = android.graphics.Color.alpha(color);
        r = (int) (r + (255 - r) * a);
        g = (int) (g + (255 - g) * a);
        b = (int) (b + (255 - b) * a);
        return android.graphics.Color.argb(alpha, r, g, b);
    }

    private void updateContentDescription() {
        int percent = max <= 0 ? 0 : Math.round(targetProgress * 100f / max);
        setContentDescription(getContext().getString(R.string.cd_progress_ring, percent));
    }

    /**
     * Announced as a progress bar rather than a generic view, so TalkBack reads
     * the percentage as a determinate indicator. View exposes no setter for this;
     * overriding the getter is the supported route, and it must stay public
     * because View declares it public.
     */
    @NonNull
    @Override
    public CharSequence getAccessibilityClassName() {
        return android.widget.ProgressBar.class.getName();
    }

    private void cancelAnimator() {
        if (animator != null) {
            animator.cancel();
            animator = null;
        }
    }

    @Override
    protected void onDetachedFromWindow() {
        cancelAnimator();
        super.onDetachedFromWindow();
    }
}
