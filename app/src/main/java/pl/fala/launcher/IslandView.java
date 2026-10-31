package pl.fala.launcher;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Outline;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.view.animation.OvershootInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextClock;
import android.widget.TextView;

/**
 * "Dynamic Island" – czarna pigułka u góry ekranu, która rozwija się
 * z animacją sprężyny i pokazuje muzykę, baterię, ładowanie i skróty.
 */
public class IslandView extends FrameLayout {

    public interface Listener {
        void onPlayPause();
        void onNext();
        void onPrev();
        void onTorch();
        void onSettings();
        void onNotifications();
        void onEnableMusic();
        void onOpenMusic();
        void onExpandChanged(boolean expanded);
    }

    private static final int S_IDLE = 0, S_MUSIC = 1, S_BANNER = 2, S_EXPANDED = 3;

    private final float density;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final GradientDrawable bg = new GradientDrawable();
    private Listener listener;
    private int state = S_IDLE;
    private ValueAnimator sizeAnim;

    // stan danych
    private String title, artist;
    private Bitmap art;
    private Drawable appIcon;
    private boolean playing = false;
    private boolean musicAccess = false;
    private int battery = -1;
    private boolean charging = false;
    private boolean torchOn = false;
    private int accent = 0xFFFFFFFF;

    // widoki
    private LinearLayout compact;
    private ImageView compactIcon;
    private TextView compactText;
    private TextView compactRight;
    private EqualizerView compactEq;

    private LinearLayout infoPanel;
    private RingView infoRing;
    private TextView infoBattery;
    private ImageView torchBtn;
    private View musicBtn;

    private LinearLayout musicPanel;
    private ImageView musicArt;
    private TextView musicTitle;
    private TextView musicArtist;
    private ImageView playBtn;
    private EqualizerView musicEq;

    private final Runnable bannerEnd = () -> {
        if (state == S_BANNER) {
            state = S_IDLE;
            updateCompact();
        }
    };

    public IslandView(Context c) {
        super(c);
        density = c.getResources().getDisplayMetrics().density;
        bg.setColor(Color.BLACK);
        bg.setCornerRadius(dp(18));
        setBackground(bg);
        setClipToOutline(true);
        setOutlineProvider(ViewOutlineProvider.BACKGROUND);
        setElevation(dp(4));

        setOnClickListener(v -> {
            if (state == S_EXPANDED) collapse();
            else expand(title != null);
        });
        setOnLongClickListener(v -> {
            if (state == S_EXPANDED) {
                boolean showingMusic = musicPanel.getVisibility() == VISIBLE;
                showPanel(!showingMusic && title != null);
            } else {
                expand(false);
            }
            return true;
        });

        int expW = expandedWidth();

        // ---- kompaktowa zawartość ----
        compact = new LinearLayout(c);
        compact.setOrientation(LinearLayout.HORIZONTAL);
        compact.setGravity(Gravity.CENTER_VERTICAL);
        compact.setPadding(dp(7), 0, dp(12), 0);
        compactIcon = new ImageView(c);
        compactIcon.setScaleType(ImageView.ScaleType.CENTER_CROP);
        roundClip(compactIcon, dp(11));
        compact.addView(compactIcon, new LinearLayout.LayoutParams(dp(22), dp(22)));
        compactText = new TextView(c);
        compactText.setTextColor(Color.WHITE);
        compactText.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        compactText.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        compactText.setPadding(dp(8), 0, 0, 0);
        compact.addView(compactText, new LinearLayout.LayoutParams(-2, -2));
        compact.addView(new View(c), new LinearLayout.LayoutParams(0, 1, 1f));
        compactRight = new TextView(c);
        compactRight.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        compactRight.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        compact.addView(compactRight, new LinearLayout.LayoutParams(-2, -2));
        compactEq = new EqualizerView(c);
        compact.addView(compactEq, new LinearLayout.LayoutParams(dp(20), dp(14)));
        addView(compact, new LayoutParams(-1, -1));

        // ---- panel informacji ----
        infoPanel = new LinearLayout(c);
        infoPanel.setOrientation(LinearLayout.VERTICAL);
        infoPanel.setPadding(dp(24), dp(20), dp(24), dp(18));
        LinearLayout top = new LinearLayout(c);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout col = new LinearLayout(c);
        col.setOrientation(LinearLayout.VERTICAL);
        TextClock time = new TextClock(c);
        time.setFormat24Hour("HH:mm");
        time.setFormat12Hour("h:mm");
        time.setTextColor(Color.WHITE);
        time.setTextSize(TypedValue.COMPLEX_UNIT_SP, 34);
        time.setTypeface(Typeface.create("sans-serif-light", Typeface.NORMAL));
        time.setIncludeFontPadding(false);
        col.addView(time);
        TextClock date = new TextClock(c);
        date.setFormat24Hour("EEEE, d MMMM");
        date.setFormat12Hour("EEEE, d MMMM");
        date.setTextColor(0x99FFFFFF);
        date.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        col.addView(date);
        top.addView(col, new LinearLayout.LayoutParams(0, -2, 1f));
        LinearLayout batCol = new LinearLayout(c);
        batCol.setOrientation(LinearLayout.VERTICAL);
        batCol.setGravity(Gravity.CENTER_HORIZONTAL);
        infoRing = new RingView(c);
        batCol.addView(infoRing, new LinearLayout.LayoutParams(dp(46), dp(46)));
        infoBattery = new TextView(c);
        infoBattery.setTextColor(0x99FFFFFF);
        infoBattery.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        infoBattery.setText("Bateria");
        infoBattery.setPadding(0, dp(4), 0, 0);
        batCol.addView(infoBattery);
        top.addView(batCol);
        infoPanel.addView(top);

        LinearLayout btns = new LinearLayout(c);
        btns.setOrientation(LinearLayout.HORIZONTAL);
        btns.setGravity(Gravity.CENTER_VERTICAL);
        btns.setPadding(0, dp(16), 0, 0);
        torchBtn = circleButton(R.drawable.ic_flash, () -> { if (listener != null) listener.onTorch(); });
        btns.addView(torchBtn, btnLp());
        btns.addView(circleButton(R.drawable.ic_bell, () -> { if (listener != null) listener.onNotifications(); }), btnLp());
        btns.addView(circleButton(R.drawable.ic_tune, () -> { if (listener != null) listener.onSettings(); }), btnLp());
        musicBtn = circleButton(R.drawable.ic_music, () -> { if (listener != null) listener.onEnableMusic(); });
        btns.addView(musicBtn, btnLp());
        infoPanel.addView(btns);
        infoPanel.setVisibility(GONE);
        addView(infoPanel, new LayoutParams(expW, -2, Gravity.TOP | Gravity.CENTER_HORIZONTAL));

        // ---- panel muzyki ----
        musicPanel = new LinearLayout(c);
        musicPanel.setOrientation(LinearLayout.VERTICAL);
        musicPanel.setPadding(dp(20), dp(18), dp(20), dp(14));
        LinearLayout mTop = new LinearLayout(c);
        mTop.setOrientation(LinearLayout.HORIZONTAL);
        mTop.setGravity(Gravity.CENTER_VERTICAL);
        musicArt = new ImageView(c);
        musicArt.setScaleType(ImageView.ScaleType.CENTER_CROP);
        roundClip(musicArt, dp(14));
        musicArt.setOnClickListener(v -> { if (listener != null) listener.onOpenMusic(); });
        mTop.addView(musicArt, new LinearLayout.LayoutParams(dp(56), dp(56)));
        LinearLayout mCol = new LinearLayout(c);
        mCol.setOrientation(LinearLayout.VERTICAL);
        mCol.setPadding(dp(14), 0, dp(8), 0);
        musicTitle = new TextView(c);
        musicTitle.setTextColor(Color.WHITE);
        musicTitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        musicTitle.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        musicTitle.setSingleLine(true);
        musicTitle.setEllipsize(TextUtils.TruncateAt.END);
        mCol.addView(musicTitle);
        musicArtist = new TextView(c);
        musicArtist.setTextColor(0x99FFFFFF);
        musicArtist.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        musicArtist.setSingleLine(true);
        musicArtist.setEllipsize(TextUtils.TruncateAt.END);
        mCol.addView(musicArtist);
        mTop.addView(mCol, new LinearLayout.LayoutParams(0, -2, 1f));
        musicEq = new EqualizerView(c);
        mTop.addView(musicEq, new LinearLayout.LayoutParams(dp(26), dp(20)));
        musicPanel.addView(mTop);

        LinearLayout ctl = new LinearLayout(c);
        ctl.setOrientation(LinearLayout.HORIZONTAL);
        ctl.setGravity(Gravity.CENTER);
        ctl.setPadding(0, dp(12), 0, 0);
        ctl.addView(iconButton(R.drawable.ic_prev, dp(30), () -> { if (listener != null) listener.onPrev(); }));
        playBtn = iconButton(R.drawable.ic_pause, dp(40), () -> { if (listener != null) listener.onPlayPause(); });
        ctl.addView(playBtn);
        ctl.addView(iconButton(R.drawable.ic_next, dp(30), () -> { if (listener != null) listener.onNext(); }));
        musicPanel.addView(ctl);
        musicPanel.setVisibility(GONE);
        addView(musicPanel, new LayoutParams(expW, -2, Gravity.TOP | Gravity.CENTER_HORIZONTAL));

        updateCompact();
    }

    // ---------- API ----------

    public void setListener(Listener l) {
        listener = l;
    }

    public int idleW() { return dp(120); }

    public int idleH() { return dp(35); }

    public boolean isExpanded() {
        return state == S_EXPANDED;
    }

    public void setMusicAccess(boolean access) {
        musicAccess = access;
        musicBtn.setVisibility(access ? GONE : VISIBLE);
    }

    public void setMusic(String title, String artist, Bitmap art, Drawable appIcon, boolean playing) {
        boolean changedArt = art != this.art;
        this.title = title;
        this.artist = artist;
        this.art = art;
        this.appIcon = appIcon;
        this.playing = playing;
        if (changedArt) accent = art != null ? accentOf(art) : 0xFFFFFFFF;
        musicTitle.setText(title == null ? "" : title);
        musicArtist.setText(artist == null ? "" : artist);
        if (art != null) musicArt.setImageBitmap(art);
        else musicArt.setImageDrawable(appIcon);
        playBtn.setImageResource(playing ? R.drawable.ic_pause : R.drawable.ic_play);
        musicEq.setColor(accent);
        musicEq.setPlaying(playing);
        if (state == S_EXPANDED && title == null && musicPanel.getVisibility() == VISIBLE) {
            showPanel(false);
        }
        updateCompact();
    }

    public void setBattery(int level, boolean charging) {
        this.battery = level;
        this.charging = charging;
        infoRing.setBattery(level, charging);
        infoBattery.setText(charging ? "Ładowanie" : "Bateria");
        if (state == S_BANNER) compactRight.setText(level + "%");
    }

    public void setTorch(boolean on) {
        torchOn = on;
        GradientDrawable g = (GradientDrawable) torchBtn.getBackground();
        g.setColor(on ? 0xFFFFD60A : 0xFF2C2C2E);
        torchBtn.setColorFilter(on ? Color.BLACK : Color.WHITE);
    }

    public void showChargingBanner() {
        if (state == S_EXPANDED) return;
        state = S_BANNER;
        compactIcon.setVisibility(VISIBLE);
        compactIcon.setImageResource(R.drawable.ic_flash);
        compactIcon.setColorFilter(0xFF30D158);
        compactIcon.setScaleType(ImageView.ScaleType.FIT_CENTER);
        compactText.setVisibility(VISIBLE);
        compactText.setText("Ładowanie");
        compactRight.setVisibility(VISIBLE);
        compactRight.setTextColor(0xFF30D158);
        compactRight.setText(battery >= 0 ? battery + "%" : "");
        compactEq.setVisibility(GONE);
        compact.animate().alpha(1f).setDuration(200).start();
        int w = Math.min(getResources().getDisplayMetrics().widthPixels - dp(48), dp(330));
        animateSize(w, dp(38));
        handler.removeCallbacks(bannerEnd);
        handler.postDelayed(bannerEnd, 2800);
    }

    public void expand(boolean music) {
        handler.removeCallbacks(bannerEnd);
        boolean was = state == S_EXPANDED;
        state = S_EXPANDED;
        compact.animate().alpha(0f).setDuration(90).start();
        showPanel(music);
        if (!was && listener != null) listener.onExpandChanged(true);
    }

    public void collapse() {
        if (state != S_EXPANDED) return;
        state = S_IDLE;
        infoPanel.animate().alpha(0f).setDuration(90).withEndAction(() -> {
            if (state != S_EXPANDED) infoPanel.setVisibility(GONE);
        }).start();
        musicPanel.animate().alpha(0f).setDuration(90).withEndAction(() -> {
            if (state != S_EXPANDED) musicPanel.setVisibility(GONE);
        }).start();
        updateCompact();
        if (listener != null) listener.onExpandChanged(false);
    }

    // ---------- wewnętrzne ----------

    private void showPanel(boolean music) {
        View show = music ? musicPanel : infoPanel;
        View hide = music ? infoPanel : musicPanel;
        hide.animate().cancel();
        hide.setVisibility(GONE);
        show.setVisibility(VISIBLE);
        show.setAlpha(0f);
        show.animate().alpha(1f).setStartDelay(120).setDuration(220).start();
        show.measure(MeasureSpec.makeMeasureSpec(expandedWidth(), MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED));
        animateSize(expandedWidth(), Math.max(dp(120), show.getMeasuredHeight()));
    }

    private void updateCompact() {
        if (state == S_EXPANDED || state == S_BANNER) return;
        compactIcon.setColorFilter(null);
        compactIcon.setScaleType(ImageView.ScaleType.CENTER_CROP);
        compactText.setVisibility(GONE);
        compactRight.setVisibility(GONE);
        if (title != null && playing) {
            state = S_MUSIC;
            compactIcon.setVisibility(VISIBLE);
            if (art != null) compactIcon.setImageBitmap(art);
            else compactIcon.setImageDrawable(appIcon);
            compactEq.setVisibility(VISIBLE);
            compactEq.setColor(accent);
            compactEq.setPlaying(true);
            compact.animate().alpha(1f).setStartDelay(150).setDuration(200).start();
            animateSize(dp(200), idleH());
        } else {
            state = S_IDLE;
            compactIcon.setVisibility(GONE);
            compactEq.setVisibility(GONE);
            compactEq.setPlaying(false);
            animateSize(idleW(), idleH());
        }
    }

    private int expandedWidth() {
        return Math.min(getResources().getDisplayMetrics().widthPixels - dp(16), dp(420));
    }

    private void animateSize(int w, int h) {
        final ViewGroup.LayoutParams lp = getLayoutParams();
        if (lp == null) {
            post(() -> animateSize(w, h));
            return;
        }
        if (sizeAnim != null) sizeAnim.cancel();
        final int sw = lp.width > 0 ? lp.width : getWidth();
        final int sh = lp.height > 0 ? lp.height : getHeight();
        if (sw == w && sh == h) return;
        sizeAnim = ValueAnimator.ofFloat(0f, 1f);
        sizeAnim.setDuration(460);
        sizeAnim.setInterpolator(new OvershootInterpolator(1.1f));
        sizeAnim.addUpdateListener(a -> {
            float t = (float) a.getAnimatedValue();
            lp.width = Math.max(dp(20), Math.round(sw + (w - sw) * t));
            lp.height = Math.max(dp(20), Math.round(sh + (h - sh) * t));
            bg.setCornerRadius(Math.min(lp.height / 2f, dp(42)));
            setLayoutParams(lp);
        });
        sizeAnim.start();
    }

    private LinearLayout.LayoutParams btnLp() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(50), dp(50));
        lp.rightMargin = dp(14);
        return lp;
    }

    private ImageView circleButton(int icon, Runnable r) {
        ImageView b = new ImageView(getContext());
        b.setImageResource(icon);
        b.setScaleType(ImageView.ScaleType.CENTER);
        b.setColorFilter(Color.WHITE);
        GradientDrawable g = new GradientDrawable();
        g.setShape(GradientDrawable.OVAL);
        g.setColor(0xFF2C2C2E);
        b.setBackground(g);
        b.setOnClickListener(v -> r.run());
        return b;
    }

    private ImageView iconButton(int icon, int size, Runnable r) {
        ImageView b = new ImageView(getContext());
        b.setImageResource(icon);
        b.setScaleType(ImageView.ScaleType.FIT_CENTER);
        b.setColorFilter(Color.WHITE);
        int pad = dp(10);
        b.setPadding(pad, pad, pad, pad);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(size + pad * 2, size + pad * 2);
        lp.leftMargin = dp(10);
        lp.rightMargin = dp(10);
        b.setLayoutParams(lp);
        b.setOnClickListener(v -> r.run());
        return b;
    }

    private static void roundClip(View v, final float radius) {
        v.setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View view, Outline outline) {
                outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), radius);
            }
        });
        v.setClipToOutline(true);
    }

    private static int accentOf(Bitmap b) {
        try {
            Bitmap one = Bitmap.createScaledBitmap(b, 1, 1, true);
            int c = one.getPixel(0, 0);
            float[] hsv = new float[3];
            Color.colorToHSV(c, hsv);
            hsv[1] = Math.min(1f, hsv[1] * 1.3f);
            hsv[2] = Math.max(0.85f, hsv[2]);
            return Color.HSVToColor(hsv);
        } catch (Exception e) {
            return 0xFFFFFFFF;
        }
    }

    private int dp(float v) {
        return Math.round(v * density);
    }

    /** Animowane słupki "equalizera". */
    static class EqualizerView extends View {
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF r = new RectF();
        private final float[] speed = {5.1f, 7.3f, 6.2f, 8.4f};
        private final float[] phase = {0f, 1.3f, 2.6f, 0.7f};
        private boolean playing = false;

        EqualizerView(Context c) {
            super(c);
            p.setColor(Color.WHITE);
        }

        void setColor(int c) {
            p.setColor(c);
            invalidate();
        }

        void setPlaying(boolean pl) {
            playing = pl;
            invalidate();
        }

        @Override
        protected void onDraw(Canvas c) {
            int n = 4;
            float w = getWidth(), h = getHeight();
            float bw = w / (n * 2f - 1f);
            double t = System.nanoTime() / 1e9;
            for (int i = 0; i < n; i++) {
                float f = playing ? (float) (0.25 + 0.75 * Math.abs(Math.sin(t * speed[i] / 2.0 + phase[i]))) : 0.22f;
                float bh = h * f;
                float x = i * bw * 2f;
                r.set(x, (h - bh) / 2f, x + bw, (h + bh) / 2f);
                c.drawRoundRect(r, bw / 2f, bw / 2f, p);
            }
            if (playing && isShown()) postInvalidateOnAnimation();
        }
    }
}
