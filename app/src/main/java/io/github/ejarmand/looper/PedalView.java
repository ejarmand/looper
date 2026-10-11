package io.github.ejarmand.looper;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.View;

/** The big round pedal. The ring shows loop progress; the label says what a tap will do. */
public final class PedalView extends View {
    private final Paint track = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint arc = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint face = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint title = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint subtitle = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF bounds = new RectF();

    private float progress;
    private String titleText = "";
    private String subtitleText = "";

    public PedalView(Context context, AttributeSet attrs) {
        super(context, attrs);
        track.setStyle(Paint.Style.STROKE);
        track.setColor(0xFF2A2A2A);
        arc.setStyle(Paint.Style.STROKE);
        arc.setStrokeCap(Paint.Cap.ROUND);
        title.setColor(0xFFFFFFFF);
        title.setTextAlign(Paint.Align.CENTER);
        title.setFakeBoldText(true);
        subtitle.setColor(0xCCFFFFFF);
        subtitle.setTextAlign(Paint.Align.CENTER);
        setClickable(true);
        setFocusable(true);
    }

    void show(int color, float progress, String titleText, String subtitleText) {
        arc.setColor(color);
        face.setColor((color & 0x00FFFFFF) | 0x55000000);
        this.progress = progress;
        this.titleText = titleText;
        this.subtitleText = subtitleText;
        setContentDescription(titleText + ". " + subtitleText);
        invalidate();
    }

    @Override
    protected void onMeasure(int widthSpec, int heightSpec) {
        int w = MeasureSpec.getSize(widthSpec);
        int size = Math.min(w, (int) (getResources().getDisplayMetrics().density * 320));
        setMeasuredDimension(w, size);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        float size = Math.min(getWidth(), getHeight());
        float stroke = size * 0.06f;
        float cx = getWidth() / 2f;
        float cy = getHeight() / 2f;
        float r = size / 2f - stroke;
        track.setStrokeWidth(stroke);
        arc.setStrokeWidth(stroke);
        bounds.set(cx - r, cy - r, cx + r, cy + r);

        canvas.drawCircle(cx, cy, r - stroke, face);
        canvas.drawCircle(cx, cy, r, track);
        if (progress > 0f) canvas.drawArc(bounds, -90f, 360f * progress, false, arc);

        title.setTextSize(size * 0.12f);
        subtitle.setTextSize(size * 0.055f);
        canvas.drawText(titleText, cx, cy, title);
        canvas.drawText(subtitleText, cx, cy + size * 0.1f, subtitle);
    }
}
