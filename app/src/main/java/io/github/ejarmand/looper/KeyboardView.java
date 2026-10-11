package io.github.ejarmand.looper;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.util.AttributeSet;
import android.view.View;

/**
 * A two-octave keyboard used as a picture, not an instrument: the middle C stands for the
 * note you sang, and the highlighted key is where the pitched copy lands.
 */
public final class KeyboardView extends View {
    private static final int RANGE = Interval.MAX_STEPS;
    private static final int WHITE_KEYS = 2 * 7 + 1;
    // Index of each pitch class among the 7 white keys, or -1 for black keys.
    private static final int[] WHITE_INDEX = {0, -1, 1, -1, 2, 3, -1, 4, -1, 5, -1, 6};
    private static final int SOURCE_COLOR = 0xFF9E9E9E;
    private static final int TARGET_COLOR = 0xFFFB8C00;

    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint border = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
    private int target;

    public KeyboardView(Context context, AttributeSet attrs) {
        super(context, attrs);
        border.setStyle(Paint.Style.STROKE);
        border.setColor(0xFF424242);
        text.setTextAlign(Paint.Align.CENTER);
        text.setFakeBoldText(true);
    }

    /** Semitones from the middle C, from -12 to +12. */
    void setTarget(int semitones) {
        target = Math.max(-RANGE, Math.min(RANGE, semitones));
        setContentDescription("From C to " + Interval.noteFromC(target));
        invalidate();
    }

    @Override
    protected void onMeasure(int widthSpec, int heightSpec) {
        int w = MeasureSpec.getSize(widthSpec);
        setMeasuredDimension(w, Math.round(w * 0.3f));
    }

    @Override
    protected void onDraw(Canvas canvas) {
        float keyW = (float) getWidth() / WHITE_KEYS;
        float h = getHeight();
        float blackW = keyW * 0.6f;
        border.setStrokeWidth(Math.max(1f, keyW * 0.04f));
        text.setTextSize(keyW * 0.42f);

        for (int s = -RANGE; s <= RANGE; s++) {
            int pc = Math.floorMod(s, 12);
            if (WHITE_INDEX[pc] < 0) continue;
            float x = whiteIndex(s) * keyW;
            fill.setColor(keyColor(s, 0xFFF5F5F5));
            canvas.drawRect(x, 0, x + keyW, h, fill);
            canvas.drawRect(x, 0, x + keyW, h, border);
            if (s == 0 || s == target) {
                text.setColor(0xFF212121);
                canvas.drawText(Interval.noteFromC(s), x + keyW / 2, h - keyW * 0.3f, text);
            }
        }
        for (int s = -RANGE; s <= RANGE; s++) {
            int pc = Math.floorMod(s, 12);
            if (WHITE_INDEX[pc] >= 0) continue;
            // A black key sits on the line after the white key just below it.
            float x = (whiteIndex(s - 1) + 1) * keyW - blackW / 2;
            fill.setColor(keyColor(s, 0xFF212121));
            canvas.drawRect(x, 0, x + blackW, h * 0.6f, fill);
            if (s == target && s != 0) {
                text.setColor(0xFFFFFFFF);
                canvas.drawText(Interval.noteFromC(s), x + blackW / 2, h * 0.6f - keyW * 0.2f, text);
            }
        }
    }

    private int keyColor(int s, int normal) {
        if (s == target && s != 0) return TARGET_COLOR;
        if (s == 0) return SOURCE_COLOR;
        return normal;
    }

    private static int whiteIndex(int s) {
        int octave = Math.floorDiv(s, 12) + 1;
        return octave * 7 + WHITE_INDEX[Math.floorMod(s, 12)];
    }
}
