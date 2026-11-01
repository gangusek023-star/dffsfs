package pl.fala.launcher;

import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;

import java.util.HashMap;
import java.util.Map;

/**
 * Jedna strona ekranu głównego: siatka 4×6. W trybie edycji elementy drgają,
 * mają przycisk „−”, można je przeciągać.
 */
public class HomeGrid extends ViewGroup {

    interface Listener {
        void onRemove(HomeModel.Item item);

        void onTapInEdit(HomeModel.Item item);

        /** Upuszczenie: komórka docelowa (lewy górny róg elementu). */
        void onDrop(HomeModel.Item item, int x, int y);

        /** Element trzymany przy krawędzi ekranu: -1 w lewo, 1 w prawo. */
        void onEdgeHold(HomeModel.Item item, int dir);

        void onDragState(boolean dragging);
    }

    private final Map<View, HomeModel.Item> items = new HashMap<>();
    private final float d;
    private final int slop;
    private Listener listener;
    private boolean editing = false;
    private final Paint badge = new Paint(Paint.ANTI_ALIAS_FLAG), minus = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint ghost = new Paint(Paint.ANTI_ALIAS_FLAG);

    // przeciąganie
    private View dragView;
    private float downX, downY, startTx, startTy;
    private boolean dragging = false;
    private View downChild;
    private int ghostX = -1, ghostY = -1;
    private Runnable edgeRunnable;
    private int edgeDir = 0;

    public HomeGrid(Context c) {
        super(c);
        d = c.getResources().getDisplayMetrics().density;
        slop = ViewConfiguration.get(c).getScaledTouchSlop();
        badge.setColor(0xE6303036);
        minus.setColor(Color.WHITE);
        minus.setStrokeWidth(2.4f * d);
        minus.setStrokeCap(Paint.Cap.ROUND);
        ghost.setColor(0x33FFFFFF);
        setWillNotDraw(false);
        setClipChildren(false);
    }

    void setListener(Listener l) {
        listener = l;
    }

    void add(View v, HomeModel.Item it) {
        items.put(v, it);
        addView(v);
        if (editing) jiggle(v, true);
    }

    void clear() {
        items.clear();
        removeAllViews();
    }

    float cellW() {
        return getWidth() / (float) HomeModel.COLS;
    }

    float cellH() {
        return getHeight() / (float) HomeModel.ROWS;
    }

    @Override
    protected void onMeasure(int wSpec, int hSpec) {
        int w = MeasureSpec.getSize(wSpec), h = MeasureSpec.getSize(hSpec);
        setMeasuredDimension(w, h);
        float cw = w / (float) HomeModel.COLS, ch = h / (float) HomeModel.ROWS;
        for (int i = 0; i < getChildCount(); i++) {
            View v = getChildAt(i);
            HomeModel.Item it = items.get(v);
            if (it == null) continue;
            v.measure(MeasureSpec.makeMeasureSpec(Math.round(cw * it.w), MeasureSpec.EXACTLY),
                    MeasureSpec.makeMeasureSpec(Math.round(ch * it.h), MeasureSpec.EXACTLY));
        }
    }

    @Override
    protected void onLayout(boolean changed, int l, int t, int r, int b) {
        float cw = cellW(), ch = cellH();
        for (int i = 0; i < getChildCount(); i++) {
            View v = getChildAt(i);
            HomeModel.Item it = items.get(v);
            if (it == null) continue;
            int x = Math.round(it.x * cw), y = Math.round(it.y * ch);
            v.layout(x, y, x + v.getMeasuredWidth(), y + v.getMeasuredHeight());
        }
    }

    // ---------- tryb edycji ----------

    void setEditing(boolean e) {
        editing = e;
        for (int i = 0; i < getChildCount(); i++) jiggle(getChildAt(i), e);
        invalidate();
    }

    private void jiggle(View v, boolean on) {
        Object prev = v.getTag(R.id.jiggle);
        if (prev instanceof ObjectAnimator) ((ObjectAnimator) prev).cancel();
        v.setRotation(0);
        if (!on) return;
        float amp = 1.6f;
        ObjectAnimator a = ObjectAnimator.ofFloat(v, "rotation", -amp, amp);
        a.setDuration(120 + (int) (Math.random() * 40));
        a.setRepeatCount(ValueAnimator.INFINITE);
        a.setRepeatMode(ValueAnimator.REVERSE);
        a.setStartDelay((long) (Math.random() * 120));
        a.start();
        v.setTag(R.id.jiggle, a);
    }

    private float badgeR() {
        return 11 * d;
    }

    /** Środek przycisku „−” przy lewym górnym rogu treści elementu. */
    private float[] badgeAt(View v) {
        HomeModel.Item it = items.get(v);
        float inset = it != null && it.w == 1 ? (v.getWidth() - Math.min(v.getWidth(), 62 * d)) / 2f : 10 * d;
        return new float[]{v.getLeft() + inset + 2 * d, v.getTop() + 6 * d};
    }

    @Override
    protected void dispatchDraw(Canvas c) {
        if (editing && dragging && ghostX >= 0 && dragView != null) {
            HomeModel.Item it = items.get(dragView);
            if (it != null) {
                float cw = cellW(), ch = cellH();
                c.drawRoundRect(ghostX * cw + 6 * d, ghostY * ch + 6 * d, (ghostX + it.w) * cw - 6 * d,
                        (ghostY + it.h) * ch - 6 * d, 20 * d, 20 * d, ghost);
            }
        }
        super.dispatchDraw(c);
        if (!editing) return;
        for (int i = 0; i < getChildCount(); i++) {
            View v = getChildAt(i);
            if (v == dragView && dragging) continue;
            float[] b = badgeAt(v);
            c.drawCircle(b[0], b[1], badgeR(), badge);
            c.drawLine(b[0] - 5 * d, b[1], b[0] + 5 * d, b[1], minus);
        }
    }

    private View childAt(float x, float y) {
        for (int i = getChildCount() - 1; i >= 0; i--) {
            View v = getChildAt(i);
            if (x >= v.getLeft() && x < v.getRight() && y >= v.getTop() && y < v.getBottom()) return v;
        }
        return null;
    }

    @Override
    public boolean onInterceptTouchEvent(MotionEvent e) {
        if (!editing) return false;
        if (e.getActionMasked() == MotionEvent.ACTION_DOWN) {
            downX = e.getX();
            downY = e.getY();
            // dotknięcie „−”
            for (int i = 0; i < getChildCount(); i++) {
                View v = getChildAt(i);
                float[] b = badgeAt(v);
                if (Math.hypot(downX - b[0], downY - b[1]) < badgeR() * 1.8f) {
                    HomeModel.Item it = items.get(v);
                    if (it != null && listener != null) listener.onRemove(it);
                    return true;
                }
            }
            downChild = childAt(downX, downY);
            // element pod palcem: przewijanie stron i szuflada nie mogą przejąć gestu
            if (downChild != null && getParent() != null) getParent().requestDisallowInterceptTouchEvent(true);
            return downChild != null; // w trybie edycji całą obsługę przejmuje siatka
        }
        return false;
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        if (!editing) return false;
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                return downChild != null;
            case MotionEvent.ACTION_MOVE: {
                if (downChild == null) return false;
                float dx = e.getX() - downX, dy = e.getY() - downY;
                if (!dragging && Math.hypot(dx, dy) > slop) {
                    dragging = true;
                    dragView = downChild;
                    startTx = 0;
                    startTy = 0;
                    dragView.bringToFront();
                    dragView.animate().scaleX(1.12f).scaleY(1.12f).alpha(0.9f).setDuration(120).start();
                    jiggle(dragView, false);
                    performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
                    if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(true);
                    if (listener != null) listener.onDragState(true);
                }
                if (dragging) {
                    dragView.setTranslationX(startTx + dx);
                    dragView.setTranslationY(startTy + dy);
                    updateGhost();
                    checkEdge(e.getX());
                }
                return true;
            }
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL: {
                cancelEdge();
                if (dragging && dragView != null) {
                    HomeModel.Item it = items.get(dragView);
                    View v = dragView;
                    int gx = ghostX, gy = ghostY;
                    dragging = false;
                    dragView = null;
                    ghostX = ghostY = -1;
                    v.animate().scaleX(1f).scaleY(1f).alpha(1f).translationX(0).translationY(0).setDuration(160).start();
                    if (listener != null) {
                        listener.onDragState(false);
                        if (it != null && gx >= 0) listener.onDrop(it, gx, gy);
                    }
                    invalidate();
                } else if (downChild != null && e.getActionMasked() == MotionEvent.ACTION_UP) {
                    HomeModel.Item it = items.get(downChild);
                    if (it != null && listener != null) listener.onTapInEdit(it);
                }
                downChild = null;
                return true;
            }
        }
        return true;
    }

    private void updateGhost() {
        HomeModel.Item it = items.get(dragView);
        if (it == null) return;
        float cw = cellW(), ch = cellH();
        float cx = dragView.getLeft() + dragView.getTranslationX();
        float cy = dragView.getTop() + dragView.getTranslationY();
        int gx = Math.round(cx / cw), gy = Math.round(cy / ch);
        gx = Math.max(0, Math.min(HomeModel.COLS - it.w, gx));
        gy = Math.max(0, Math.min(HomeModel.ROWS - it.h, gy));
        if (gx != ghostX || gy != ghostY) {
            ghostX = gx;
            ghostY = gy;
            invalidate();
        }
    }

    private void checkEdge(float x) {
        int dir = x < 18 * d ? -1 : x > getWidth() - 18 * d ? 1 : 0;
        if (dir == edgeDir) return;
        cancelEdge();
        edgeDir = dir;
        if (dir == 0) return;
        edgeRunnable = () -> {
            if (!dragging || dragView == null) return;
            HomeModel.Item it = items.get(dragView);
            View v = dragView;
            dragging = false;
            dragView = null;
            ghostX = ghostY = -1;
            v.setTranslationX(0);
            v.setTranslationY(0);
            v.setScaleX(1f);
            v.setScaleY(1f);
            v.setAlpha(1f);
            if (listener != null) {
                listener.onDragState(false);
                if (it != null) listener.onEdgeHold(it, edgeDir);
            }
            edgeDir = 0;
        };
        postDelayed(edgeRunnable, 650);
    }

    private void cancelEdge() {
        if (edgeRunnable != null) removeCallbacks(edgeRunnable);
        edgeRunnable = null;
        edgeDir = 0;
    }
}
