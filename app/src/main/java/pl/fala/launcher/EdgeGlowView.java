package pl.fala.launcher;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.SweepGradient;
import android.view.View;
import android.view.animation.DecelerateInterpolator;

/** Kolorowa poświata wzdłuż krawędzi ekranu, gdy asystent słucha. */
public class EdgeGlowView extends View {

    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Matrix m = new Matrix();
    private final RectF r = new RectF();
    private final float density;
    private SweepGradient shader;
    private float intensity = 0f;
    private float level = 0f, target = 0f;
    private double phase = 0;
    private long last = 0;
    private ValueAnimator anim;

    public EdgeGlowView(Context c) {
        super(c);
        density = c.getResources().getDisplayMetrics().density;
        p.setStyle(Paint.Style.STROKE);
        setLayerType(LAYER_TYPE_HARDWARE, null);
    }

    public void setLevel(float l) {
        target = Math.max(0f, Math.min(1f, l));
    }

    public void show(boolean on) {
        if (anim != null) anim.cancel();
        anim = ValueAnimator.ofFloat(intensity, on ? 1f : 0f);
        anim.setDuration(on ? 500 : 400);
        anim.setInterpolator(new DecelerateInterpolator());
        anim.addUpdateListener(a -> {
            intensity = (float) a.getAnimatedValue();
            invalidate();
        });
        anim.start();
    }

    @Override
    protected void onSizeChanged(int w, int h, int ow, int oh) {
        shader = new SweepGradient(w / 2f, h / 2f,
                new int[]{0xFFFF2D95, 0xFFFF9F0A, 0xFF8E5CFF, 0xFF2D9CFF, 0xFF2DE1C2, 0xFFFF2D95}, null);
        p.setShader(shader);
    }

    @Override
    protected void onDraw(Canvas c) {
        long now = System.nanoTime();
        float dt = last == 0 ? 0.016f : Math.min(0.05f, (now - last) / 1e9f);
        last = now;
        level += (target - level) * Math.min(1f, dt * 8f);
        phase += dt * (0.6 + level * 1.5);
        if (intensity <= 0.001f || shader == null) {
            if (intensity > 0f) postInvalidateOnAnimation();
            return;
        }
        m.setRotate((float) (phase * 57.3), getWidth() / 2f, getHeight() / 2f);
        shader.setLocalMatrix(m);
        float corner = 44 * density;
        int layers = 7;
        for (int k = layers - 1; k >= 0; k--) {
            float w = (3f + k * 5.5f * (0.7f + level * 0.8f)) * density;
            p.setStrokeWidth(w);
            p.setAlpha((int) (255 * intensity * (1f - k / (float) layers) * 0.6f));
            r.set(0, 0, getWidth(), getHeight());
            c.drawRoundRect(r, corner, corner, p);
        }
        postInvalidateOnAnimation();
    }
}
