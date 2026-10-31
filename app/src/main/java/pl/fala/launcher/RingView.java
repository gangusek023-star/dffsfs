package pl.fala.launcher;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.view.View;

/** Okrągły wskaźnik baterii. */
public class RingView extends View {

    private final Paint track = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint arc = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();
    private int level = -1;
    private boolean charging = false;

    public RingView(Context c) {
        super(c);
        float d = c.getResources().getDisplayMetrics().density;
        track.setStyle(Paint.Style.STROKE);
        track.setStrokeWidth(4.5f * d);
        track.setColor(0x33FFFFFF);
        arc.setStyle(Paint.Style.STROKE);
        arc.setStrokeWidth(4.5f * d);
        arc.setStrokeCap(Paint.Cap.ROUND);
        text.setColor(0xFFFFFFFF);
        text.setTextAlign(Paint.Align.CENTER);
        text.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
    }

    public void setBattery(int level, boolean charging) {
        this.level = level;
        this.charging = charging;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas c) {
        float sw = track.getStrokeWidth();
        float s = Math.min(getWidth(), getHeight());
        float l = (getWidth() - s) / 2f + sw / 2f;
        float t = (getHeight() - s) / 2f + sw / 2f;
        rect.set(l, t, l + s - sw, t + s - sw);
        c.drawOval(rect, track);
        if (level < 0) return;
        int color = charging ? 0xFF30D158 : (level <= 20 ? 0xFFFF453A : 0xFFFFFFFF);
        arc.setColor(color);
        c.drawArc(rect, -90f, 360f * level / 100f, false, arc);
        text.setTextSize(s * 0.27f);
        String label = level + "%";
        c.drawText(label, getWidth() / 2f, getHeight() / 2f - (text.descent() + text.ascent()) / 2f, text);
    }
}
