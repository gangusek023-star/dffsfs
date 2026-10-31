package pl.fala.launcher;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.view.animation.DecelerateInterpolator;

import java.util.ArrayList;
import java.util.List;

/**
 * Pionowy pasek liter z efektem "fali" - litery w pobliżu palca
 * wychylają się w lewo i powiększają (styl Niagara).
 */
public class AlphabetView extends View {

    public interface Listener {
        void onLetter(String letter, float y);
        void onRelease();
    }

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final float density;
    private List<String> letters = new ArrayList<>();
    private Listener listener;
    private float touchY = -10000f;
    private float strength = 0f;
    private int selected = -1;
    private boolean touching = false;
    private ValueAnimator anim;

    public AlphabetView(Context c) {
        super(c);
        density = c.getResources().getDisplayMetrics().density;
        paint.setTextAlign(Paint.Align.CENTER);
        paint.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        paint.setColor(Color.WHITE);
    }

    private float dp(float v) {
        return v * density;
    }

    public void setListener(Listener l) {
        listener = l;
    }

    public void setLetters(List<String> l) {
        letters = l;
        selected = -1;
        invalidate();
    }

    private float itemH() {
        int n = Math.max(1, letters.size());
        float avail = getHeight() - getPaddingTop() - getPaddingBottom();
        return Math.min(dp(22), avail / n);
    }

    private float startY() {
        float avail = getHeight() - getPaddingTop() - getPaddingBottom();
        return getPaddingTop() + (avail - itemH() * letters.size()) / 2f;
    }

    private float baseX() {
        return getWidth() - dp(22);
    }

    @Override
    protected void onDraw(Canvas c) {
        int n = letters.size();
        if (n == 0) return;
        float ih = itemH();
        float sy = startY();
        float bx = baseX();
        float sigma = dp(44);
        for (int i = 0; i < n; i++) {
            float cy = sy + ih * (i + 0.5f);
            float d = cy - touchY;
            float f = (float) Math.exp(-(d * d) / (2f * sigma * sigma)) * strength;
            paint.setTextSize(dp(11) * (1f + f * 1.2f));
            boolean sel = touching && i == selected;
            int a = sel ? 255 : (int) (255 * (0.45f + 0.5f * f));
            paint.setAlpha(a);
            paint.setFakeBoldText(sel);
            float x = bx - f * dp(56);
            c.drawText(letters.get(i), x, cy - (paint.descent() + paint.ascent()) / 2f, paint);
        }
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN: {
                if (letters.isEmpty() || e.getX() < getWidth() - dp(48)) return false;
                float sy = startY();
                float end = sy + itemH() * letters.size();
                if (e.getY() < sy - dp(24) || e.getY() > end + dp(24)) return false;
                touching = true;
                selected = -1;
                if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(true);
                animateTo(1f);
                update(e.getY());
                return true;
            }
            case MotionEvent.ACTION_MOVE:
                if (touching) update(e.getY());
                return touching;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                if (touching) {
                    touching = false;
                    animateTo(0f);
                    if (listener != null) listener.onRelease();
                }
                return true;
            default:
                return touching;
        }
    }

    private void update(float y) {
        touchY = y;
        float ih = itemH();
        int i = (int) ((y - startY()) / ih);
        i = Math.max(0, Math.min(letters.size() - 1, i));
        if (i != selected) {
            selected = i;
            performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
            if (listener != null) listener.onLetter(letters.get(i), startY() + ih * (i + 0.5f));
        }
        invalidate();
    }

    private void animateTo(float t) {
        if (anim != null) anim.cancel();
        anim = ValueAnimator.ofFloat(strength, t);
        anim.setDuration(t > 0 ? 180 : 280);
        anim.setInterpolator(new DecelerateInterpolator());
        anim.addUpdateListener(a -> {
            strength = (float) a.getAnimatedValue();
            invalidate();
        });
        anim.start();
    }
}
