package pl.fala.launcher;

import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.LinearGradient;
import android.graphics.Outline;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.RadialGradient;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;

/**
 * Efekt "liquid glass": półprzezroczyste szkło z jasną krawędzią,
 * połyskiem w lewym górnym rogu i wewnętrznym odblaskiem u góry.
 */
public class GlassDrawable extends Drawable {

    private final float radius;
    private final float density;
    private final float strength;
    private final Paint base = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint shine = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint inner = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint rim = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF r = new RectF();
    private final RectF ri = new RectF();

    public GlassDrawable(float density, float radiusPx, float strength) {
        this.density = density;
        this.radius = radiusPx;
        this.strength = strength;
        base.setColor(((int) (0x22 * strength) << 24));
        rim.setStyle(Paint.Style.STROKE);
        rim.setStrokeWidth(1.2f * density);
        inner.setStyle(Paint.Style.STROKE);
        inner.setStrokeWidth(1f * density);
    }

    private int white(int alpha) {
        int a = Math.max(0, Math.min(255, (int) (alpha * strength)));
        return (a << 24) | 0xFFFFFF;
    }

    @Override
    protected void onBoundsChange(Rect b) {
        float w = b.width();
        float h = b.height();
        fill.setShader(new LinearGradient(0, b.top, 0, b.bottom,
                new int[]{white(0x38), white(0x16), white(0x24)},
                new float[]{0f, 0.55f, 1f}, Shader.TileMode.CLAMP));
        rim.setShader(new LinearGradient(b.left, b.top, b.right, b.bottom,
                new int[]{0xBFFFFFFF, 0x2EFFFFFF, 0x14FFFFFF, 0x73FFFFFF},
                new float[]{0f, 0.35f, 0.65f, 1f}, Shader.TileMode.CLAMP));
        shine.setShader(new RadialGradient(b.left + w * 0.22f, b.top, Math.max(1f, Math.max(w, h) * 0.75f),
                new int[]{white(0x30), 0x00FFFFFF}, null, Shader.TileMode.CLAMP));
        inner.setShader(new LinearGradient(0, b.top, 0, b.top + Math.min(h, 36 * density),
                new int[]{0x66FFFFFF, 0x00FFFFFF}, null, Shader.TileMode.CLAMP));
        float hs = rim.getStrokeWidth() / 2f;
        r.set(b.left + hs, b.top + hs, b.right - hs, b.bottom - hs);
        float o = 1.6f * density;
        ri.set(r.left + o, r.top + o, r.right - o, r.bottom - o);
    }

    @Override
    public void draw(Canvas c) {
        float rr = Math.min(radius, r.height() / 2f);
        float o = 1.6f * density;
        c.drawRoundRect(r, rr, rr, base);
        c.drawRoundRect(r, rr, rr, fill);
        c.drawRoundRect(r, rr, rr, shine);
        c.drawRoundRect(ri, Math.max(0, rr - o), Math.max(0, rr - o), inner);
        c.drawRoundRect(r, rr, rr, rim);
    }

    @Override
    public void getOutline(Outline outline) {
        outline.setRoundRect(getBounds(), Math.min(radius, getBounds().height() / 2f));
        outline.setAlpha(0.25f);
    }

    @Override
    public void setAlpha(int alpha) {
    }

    @Override
    public void setColorFilter(ColorFilter colorFilter) {
    }

    @Override
    public int getOpacity() {
        return PixelFormat.TRANSLUCENT;
    }
}
