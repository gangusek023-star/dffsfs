package pl.fala.bench;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.view.View;

import java.util.ArrayList;
import java.util.List;

/** Wykres testu obciążeniowego: wydajność (%) i temperatura baterii. */
public class ChartView extends View {

    final List<Float> perf = new ArrayList<>();
    final List<Float> temp = new ArrayList<>();
    int slots = 18;
    private final float d;
    private final Paint grid = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint line = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint tline = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint label = new Paint(Paint.ANTI_ALIAS_FLAG);

    public ChartView(Context c) {
        super(c);
        d = c.getResources().getDisplayMetrics().density;
        grid.setColor(0x1FFFFFFF);
        grid.setStrokeWidth(d);
        line.setStyle(Paint.Style.STROKE);
        line.setStrokeWidth(3 * d);
        line.setStrokeJoin(Paint.Join.ROUND);
        line.setColor(0xFF7C9CFF);
        tline.setStyle(Paint.Style.STROKE);
        tline.setStrokeWidth(2 * d);
        tline.setColor(0xFFFF8A4C);
        tline.setPathEffect(new android.graphics.DashPathEffect(new float[]{6 * d, 5 * d}, 0));
        label.setColor(0x99FFFFFF);
        label.setTextSize(11 * d);
        label.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
    }

    void add(float perfPct, float tempC) {
        perf.add(perfPct);
        temp.add(tempC);
        invalidate();
    }

    void clear() {
        perf.clear();
        temp.clear();
        invalidate();
    }

    @Override
    protected void onDraw(Canvas c) {
        float l = 34 * d, r = getWidth() - 34 * d, t = 12 * d, b = getHeight() - 22 * d;
        for (int i = 0; i <= 4; i++) {
            float y = t + (b - t) * i / 4f;
            c.drawLine(l, y, r, y, grid);
            label.setTextAlign(Paint.Align.RIGHT);
            c.drawText((100 - i * 25) + "%", l - 6 * d, y + 4 * d, label);
        }
        label.setTextAlign(Paint.Align.LEFT);
        label.setColor(0xFFFF8A4C);
        c.drawText("°C", r + 6 * d, t + 4 * d, label);
        label.setColor(0x99FFFFFF);
        int n = perf.size();
        if (n == 0) return;
        float step = (r - l) / Math.max(1, slots - 1);
        Path p = new Path(), f = new Path();
        for (int i = 0; i < n; i++) {
            float x = l + i * step;
            float y = b - (b - t) * Math.max(0, Math.min(1.05f, perf.get(i) / 100f));
            if (i == 0) { p.moveTo(x, y); f.moveTo(x, b); f.lineTo(x, y); }
            else { p.lineTo(x, y); f.lineTo(x, y); }
            if (i == n - 1) f.lineTo(x, b);
        }
        f.close();
        fill.setShader(new LinearGradient(0, t, 0, b, 0x667C9CFF, 0x007C9CFF, Shader.TileMode.CLAMP));
        c.drawPath(f, fill);
        c.drawPath(p, line);
        // temperatura: skala 25–55 °C
        Path tp = new Path();
        for (int i = 0; i < n; i++) {
            float x = l + i * step;
            float y = b - (b - t) * Math.max(0, Math.min(1, (temp.get(i) - 25f) / 30f));
            if (i == 0) tp.moveTo(x, y);
            else tp.lineTo(x, y);
        }
        c.drawPath(tp, tline);
        label.setTextAlign(Paint.Align.LEFT);
        c.drawText("0", l, getHeight() - 6 * d, label);
        label.setTextAlign(Paint.Align.RIGHT);
        c.drawText("czas →", r, getHeight() - 6 * d, label);
    }
}
