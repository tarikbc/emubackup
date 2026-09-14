package com.tarikbc.emubackup;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.View;

/**
 * A stacked proportional bar: saves, states, keys, and what was skipped.
 *
 * <p>It is a sanity check as much as a chart. A registry mistake shows up here before it shows
 * up in a backup — if this bar ever reports gigabytes, a glob has stopped excluding ROMs or
 * BIOS images. See {@code DESIGN.md} section 6.
 */
public final class SizeBarView extends View {

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();

    private long saves, states, keys, skipped;
    private int cSaves, cStates, cKeys, cSkipped, cTrack;

    public SizeBarView(Context c) { this(c, null); }

    public SizeBarView(Context c, AttributeSet a) {
        super(c, a);
        cSaves = getResources().getColor(R.color.accent, null);
        cStates = getResources().getColor(R.color.text_tertiary, null);
        cKeys = getResources().getColor(R.color.warn, null);
        cSkipped = getResources().getColor(R.color.hairline, null);
        cTrack = getResources().getColor(R.color.surface_low, null);
    }

    public void setBytes(long saves, long states, long keys, long skipped) {
        this.saves = Math.max(0, saves);
        this.states = Math.max(0, states);
        this.keys = Math.max(0, keys);
        this.skipped = Math.max(0, skipped);
        invalidate();
    }

    public long total() {
        return saves + states + keys + skipped;
    }

    @Override protected void onDraw(Canvas canvas) {
        float w = getWidth(), h = getHeight();
        float r = h / 2f;

        rect.set(0, 0, w, h);
        paint.setColor(cTrack);
        canvas.drawRoundRect(rect, r, r, paint);

        long total = total();
        if (total <= 0) return;

        // Clip the whole bar to the rounded track once, then draw plain rectangles inside it.
        // Rounding each segment separately would leave visible notches between them.
        canvas.save();
        canvas.clipRect(0, 0, w, h);
        float x = 0;
        x = segment(canvas, x, w, h, saves, total, cSaves);
        x = segment(canvas, x, w, h, states, total, cStates);
        x = segment(canvas, x, w, h, keys, total, cKeys);
        segment(canvas, x, w, h, skipped, total, cSkipped);
        canvas.restore();
    }

    private float segment(Canvas c, float x, float w, float h, long v, long total, int color) {
        if (v <= 0) return x;
        // A segment that is a rounding error still gets a hairline, so a category is never
        // invisible just because it is small. Keys are kilobytes next to hundreds of megabytes.
        float width = Math.max((float) v / total * w, v > 0 ? 2f : 0f);
        paint.setColor(color);
        rect.set(x, 0, Math.min(x + width, w), h);
        c.drawRect(rect, paint);
        return x + width;
    }

    @Override protected void onMeasure(int widthSpec, int heightSpec) {
        int h = (int) (getResources().getDisplayMetrics().density * 10);
        setMeasuredDimension(resolveSize(getSuggestedMinimumWidth(), widthSpec),
                resolveSize(h, heightSpec));
    }
}
