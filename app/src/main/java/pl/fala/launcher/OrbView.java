package pl.fala.launcher;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.view.View;

/**
 * Świecąca kula asystenta (w stylu Siri): kolorowe plamy krążą wewnątrz
 * szklanej kuli i pulsują w rytm głosu.
 */
public class OrbView extends View {

    private static final int[] COLORS = {0xFFFF2D95, 0xFF8E5CFF, 0xFF2D9CFF, 0xFF2DE1C2};

    private final Paint[] blobs = new Paint[COLORS.length];
    private final Paint base = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint glow = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint shine = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint rim = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path clip = new Path();
    private float R = 1f;
    private float level = 0f, target = 0f;
    private float speed = 1f, targetSpeed = 1f;
    private double phase = 0;
    private long lastFrame = 0;

    public OrbView(Context c) {
        super(c);
        for (int i = 0; i < blobs.length; i++) {
            blobs[i] = new Paint(Paint.ANTI_ALIAS_FLAG);
            blobs[i].setXfermode(new PorterDuffXfermode(PorterDuff.Mode.SCREEN));
        }
        rim.setStyle(Paint.Style.STROKE);
        rim.setColor(0x55FFFFFF);
        rim.setStrokeWidth(1.5f * c.getResources().getDisplayMetrics().density);
        setLayerType(LAYER_TYPE_HARDWARE, null);
    }

    /** Poziom głosu 0..1. */
    public void setLevel(float l) {
        target = Math.max(0f, Math.min(1f, l));
    }

    /** Szybsze wirowanie, gdy asystent "myśli". */
    public void setThinking(boolean t) {
        targetSpeed = t ? 3.2f : 1f;
    }

    @Override
    protected void onSizeChanged(int w, int h, int ow, int oh) {
        R = Math.min(w, h) / 2f / 1.35f;
        for (int i = 0; i < blobs.length; i++) {
            int col = COLORS[i];
            blobs[i].setShader(new RadialGradient(0, 0, R * 0.8f,
                    new int[]{col, (col & 0x00FFFFFF) | 0x66000000, col & 0x00FFFFFF},
                    new float[]{0f, 0.5f, 1f}, Shader.TileMode.CLAMP));
        }
        base.setShader(new RadialGradient(w / 2f, h / 2f, R,
                new int[]{0xFF1A1440, 0xFF07060F}, null, Shader.TileMode.CLAMP));
        shine.setShader(new RadialGradient(w / 2f - R * 0.35f, h / 2f - R * 0.45f, R * 0.7f,
                new int[]{0x66FFFFFF, 0x00FFFFFF}, null, Shader.TileMode.CLAMP));
        glow.setShader(new RadialGradient(w / 2f, h / 2f, R * 1.35f,
                new int[]{0x998E5CFF, 0x442D9CFF, 0x002D9CFF}, new float[]{0.55f, 0.75f, 1f},
                Shader.TileMode.CLAMP));
    }

    @Override
    protected void onDraw(Canvas c) {
        long now = System.nanoTime();
        float dt = lastFrame == 0 ? 0.016f : Math.min(0.05f, (now - lastFrame) / 1e9f);
        lastFrame = now;
        level += (target - level) * Math.min(1f, dt * 10f);
        speed += (targetSpeed - speed) * Math.min(1f, dt * 4f);
        phase += dt * speed;

        float cx = getWidth() / 2f, cy = getHeight() / 2f;
        float breathe = (float) Math.sin(phase * 2.0) * 0.025f;
        float r = R * (0.86f + 0.16f * level + breathe);

        // poświata wokół kuli
        glow.setAlpha((int) (120 + 135 * level));
        c.save();
        float gs = 0.9f + 0.3f * level;
        c.scale(gs, gs, cx, cy);
        c.drawCircle(cx, cy, R * 1.35f, glow);
        c.restore();

        clip.reset();
        clip.addCircle(cx, cy, r, Path.Direction.CW);
        c.save();
        c.clipPath(clip);
        c.drawCircle(cx, cy, r, base);
        for (int i = 0; i < blobs.length; i++) {
            double a = phase * (0.7 + 0.28 * i) + i * Math.PI / 2;
            float dist = r * (0.30f + 0.15f * level);
            float x = cx + (float) Math.cos(a) * dist;
            float y = cy + (float) Math.sin(a * 1.27) * dist;
            float s = (r / R) * (0.85f + 0.35f * level + 0.08f * (float) Math.sin(phase * 3 + i));
            c.save();
            c.translate(x, y);
            c.scale(s, s);
            c.drawCircle(0, 0, R * 0.8f, blobs[i]);
            c.restore();
        }
        c.drawCircle(cx, cy, r, shine);
        c.restore();
        c.drawCircle(cx, cy, r, rim);

        if (isShown()) postInvalidateOnAnimation();
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        lastFrame = 0;
        postInvalidateOnAnimation();
    }
}
