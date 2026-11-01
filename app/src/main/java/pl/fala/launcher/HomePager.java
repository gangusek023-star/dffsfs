package pl.fala.launcher;

import android.content.Context;
import android.view.MotionEvent;
import android.view.VelocityTracker;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.widget.OverScroller;

/** Przewijane w poziomie strony ekranu głównego z „przyciąganiem” do strony. */
public class HomePager extends ViewGroup {

    interface Listener {
        void onPage(int page, float offset);
    }

    /** Gesty na pustym miejscu strony. */
    interface EmptyListener {
        void onLongPress();

        void onDoubleTap();

        void onTap();
    }

    private EmptyListener empty;
    private final android.view.GestureDetector gd;

    private final OverScroller scroller;
    private final int slop, minFling;
    private VelocityTracker vt;
    private float downX, downY, lastX;
    private boolean dragging = false;
    private int page = 0;
    private Listener listener;

    public HomePager(Context c) {
        super(c);
        scroller = new OverScroller(c, new android.view.animation.DecelerateInterpolator(2f));
        ViewConfiguration vc = ViewConfiguration.get(c);
        slop = vc.getScaledTouchSlop();
        minFling = vc.getScaledMinimumFlingVelocity() * 4;
        setClipChildren(false);
        gd = new android.view.GestureDetector(c, new android.view.GestureDetector.SimpleOnGestureListener() {
            @Override
            public void onLongPress(MotionEvent e) {
                if (!dragging && empty != null) empty.onLongPress();
            }

            @Override
            public boolean onDoubleTap(MotionEvent e) {
                if (empty != null) empty.onDoubleTap();
                return true;
            }

            @Override
            public boolean onSingleTapConfirmed(MotionEvent e) {
                if (empty != null) empty.onTap();
                return true;
            }
        });
    }

    void setEmptyListener(EmptyListener l) {
        empty = l;
    }

    void setListener(Listener l) {
        listener = l;
    }

    int page() {
        return page;
    }

    int count() {
        return getChildCount();
    }

    @Override
    protected void onMeasure(int wSpec, int hSpec) {
        int w = MeasureSpec.getSize(wSpec), h = MeasureSpec.getSize(hSpec);
        setMeasuredDimension(w, h);
        for (int i = 0; i < getChildCount(); i++) {
            getChildAt(i).measure(MeasureSpec.makeMeasureSpec(w, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(h, MeasureSpec.EXACTLY));
        }
    }

    @Override
    protected void onLayout(boolean changed, int l, int t, int r, int b) {
        int w = r - l, h = b - t;
        for (int i = 0; i < getChildCount(); i++) getChildAt(i).layout(i * w, 0, (i + 1) * w, h);
        if (changed) scrollTo(page * w, 0);
    }

    void setPage(int p, boolean animate) {
        p = Math.max(0, Math.min(getChildCount() - 1, p));
        page = p;
        int target = p * getWidth();
        if (animate) {
            scroller.forceFinished(true);
            scroller.startScroll(getScrollX(), 0, target - getScrollX(), 0, 340);
            postInvalidateOnAnimation();
        } else {
            scrollTo(target, 0);
            report();
        }
    }

    @Override
    protected void onScrollChanged(int l, int t, int oldl, int oldt) {
        super.onScrollChanged(l, t, oldl, oldt);
        depth();
    }

    /** Strony lekko się oddalają i przygasają, gdy odjeżdżają na bok. */
    private void depth() {
        int w = getWidth();
        if (w == 0) return;
        float pos = getScrollX() / (float) w;
        for (int i = 0; i < getChildCount(); i++) {
            View c = getChildAt(i);
            float off = Math.min(1f, Math.abs(i - pos));
            float s = 1f - 0.08f * off;
            c.setScaleX(s);
            c.setScaleY(s);
            c.setAlpha(1f - 0.55f * off);
        }
    }

    @Override
    public void computeScroll() {
        if (scroller.computeScrollOffset()) {
            scrollTo(scroller.getCurrX(), 0);
            report();
            postInvalidateOnAnimation();
        }
    }

    private void report() {
        if (listener == null || getWidth() == 0) return;
        float f = getScrollX() / (float) getWidth();
        listener.onPage(Math.round(f), f);
    }

    @Override
    public boolean onInterceptTouchEvent(MotionEvent e) {
        track(e);
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downX = lastX = e.getX();
                downY = e.getY();
                dragging = !scroller.isFinished();
                if (dragging) scroller.forceFinished(true);
                return dragging;
            case MotionEvent.ACTION_MOVE: {
                float dx = e.getX() - downX, dy = e.getY() - downY;
                if (getChildCount() > 1 && Math.abs(dx) > slop && Math.abs(dx) > Math.abs(dy) * 1.2f) {
                    dragging = true;
                    lastX = e.getX();
                    if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(true);
                    return true;
                }
                return false;
            }
            default:
                return false;
        }
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        track(e);
        gd.onTouchEvent(e);
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                return true;
            case MotionEvent.ACTION_MOVE: {
                float x = e.getX();
                float dx = lastX - x;
                lastX = x;
                int max = (getChildCount() - 1) * getWidth();
                int nx = (int) (getScrollX() + dx);
                if (nx < 0 || nx > max) nx = (int) (getScrollX() + dx * 0.35f); // opór na krańcach
                scrollTo(nx, 0);
                report();
                return true;
            }
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL: {
                float vx = 0;
                if (vt != null) {
                    vt.computeCurrentVelocity(1000);
                    vx = vt.getXVelocity();
                }
                int w = Math.max(1, getWidth());
                int target = Math.round(getScrollX() / (float) w);
                if (vx < -minFling) target = (int) Math.floor(getScrollX() / (float) w) + 1;
                else if (vx > minFling) target = (int) Math.ceil(getScrollX() / (float) w) - 1;
                setPage(target, true);
                dragging = false;
                if (vt != null) {
                    vt.recycle();
                    vt = null;
                }
                return true;
            }
        }
        return true;
    }

    private void track(MotionEvent e) {
        if (vt == null) vt = VelocityTracker.obtain();
        vt.addMovement(e);
    }

    /** Kropki stron w szklanej pigułce. */
    static class Dots extends View {
        private final android.graphics.Paint p = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
        private int count = 1;
        private float pos = 0;
        private final float d;

        Dots(Context c) {
            super(c);
            d = c.getResources().getDisplayMetrics().density;
        }

        void set(int count, float pos) {
            this.count = Math.max(1, count);
            this.pos = pos;
            requestLayout();
            invalidate();
        }

        @Override
        protected void onMeasure(int w, int h) {
            setMeasuredDimension(Math.round((count * 16 + 24) * d), Math.round(30 * d));
        }

        @Override
        protected void onDraw(android.graphics.Canvas c) {
            float cy = getHeight() / 2f, start = 12 * d + 8 * d;
            for (int i = 0; i < count; i++) {
                float f = Math.max(0, 1 - Math.abs(pos - i));
                p.setColor(android.graphics.Color.argb((int) (100 + 155 * f), 255, 255, 255));
                c.drawCircle(start + i * 16 * d, cy, (3.2f + 0.8f * f) * d, p);
            }
        }
    }
}
