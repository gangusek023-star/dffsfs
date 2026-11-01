package pl.fala.launcher;

import android.animation.ValueAnimator;
import android.app.Activity;
import android.app.ActivityOptions;
import android.app.AlertDialog;
import android.app.SearchManager;
import android.content.ActivityNotFoundException;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.LauncherActivityInfo;
import android.content.pm.LauncherApps;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.AdaptiveIconDrawable;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraManager;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.net.Uri;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.UserHandle;
import android.os.UserManager;
import android.provider.AlarmClock;
import android.provider.MediaStore;
import android.provider.Settings;
import android.text.Editable;
import android.text.InputType;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.util.TypedValue;
import android.view.GestureDetector;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.VelocityTracker;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.animation.DecelerateInterpolator;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.AbsListView;
import android.widget.BaseAdapter;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextClock;
import android.widget.TextView;
import android.widget.Toast;

import java.text.Collator;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity {

    // ---------- model ----------

    static class App {
        String label;
        String norm;
        String section;
        String key;
        ComponentName component;
        UserHandle user;
        Bitmap icon;
    }

    static final int T_SECTION = 0, T_APP = 1, T_LABEL = 2, T_HINT = 3;

    static class Item {
        final int type;
        final App app;
        final String text;

        Item(int type, App app, String text) {
            this.type = type;
            this.app = app;
            this.text = text;
        }
    }

    static class Holder {
        ImageView icon;
        TextView label;
    }

    // ---------- stan ----------

    private LauncherApps launcherApps;
    private SharedPreferences prefs;
    private final List<App> apps = new ArrayList<>();
    private final Map<String, App> byKey = new HashMap<>();
    private final List<Item> items = new ArrayList<>();
    private final Map<String, Integer> sectionPos = new HashMap<>();
    private final ExecutorService exec = Executors.newSingleThreadExecutor();
    private final ExecutorService iconPool = Executors.newFixedThreadPool(
            Math.max(2, Math.min(4, Runtime.getRuntime().availableProcessors() - 1)));
    private final Map<String, Bitmap> iconCache = new ConcurrentHashMap<>();
    private final Runnable reloadRunnable = this::loadApps;
    private SharedPreferences usage;
    private View suggestView;
    private TextView widgetGreeting;
    private TextView widgetAlarm;
    private int lastBlur = -1;
    private boolean layersOn = false;
    private boolean thresholdBuzzed = false;
    private final android.view.animation.Interpolator smooth =
            new android.view.animation.PathInterpolator(0.2f, 0f, 0f, 1f);
    private final Handler main = new Handler(Looper.getMainLooper());

    private FrameLayout root;
    private HomeFrame homeLayer;
    private LinearLayout homeContent;
    private LinearLayout widget;
    private RingView widgetRing;
    private TextView widgetBattery;
    private LinearLayout grid;
    private LinearLayout searchPill;
    private LinearLayout dock;

    private DrawerFrame drawer;
    private ListView list;
    private AlphabetView alphabet;
    private TextView bubble;
    private LinearLayout searchBar;
    private EditText searchField;
    private Adapter adapter;

    private IslandView island;
    private View islandShield;

    private String query = "";
    private int insetTop = 0, insetBottom = 0;
    private float drawerP = 0f;
    private boolean drawerOpen = false;
    private ValueAnimator drawerAnim;
    private LauncherApps.Callback callback;

    private int batteryLevel = -1;
    private boolean charging = false;
    private BroadcastReceiver batteryReceiver;

    private IslandController islandCtl;
    private IconFactory icons;
    private android.view.DisplayCutout cutout;
    private static final int REQ_PICK_ICON = 41;
    private String pendingIconKey = null;

    // ---------- cykl życia ----------

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                        | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);

        prefs = getSharedPreferences("fala", MODE_PRIVATE);
        usage = getSharedPreferences("fala_usage", MODE_PRIVATE);
        launcherApps = (LauncherApps) getSystemService(Context.LAUNCHER_APPS_SERVICE);
        icons = new IconFactory(this, prefs);

        readWallpaperTint();
        buildUi();
        if (Build.VERSION.SDK_INT >= 27) {
            try {
                android.app.WallpaperManager.getInstance(this).addOnColorsChangedListener((colors, which) -> {
                    readWallpaperTint();
                    invalidateTree(root);
                    // ikony w stylu "Tapeta"/"Szklane" przebarwiają się same po zmianie tapety
                    if (icons != null && icons.dependsOnWallpaper()) reloadIcons();
                }, main);
            } catch (Exception ignored) {
            }
        }
        setContentView(root);

        root.setOnApplyWindowInsetsListener((v, ins) -> {
            insetTop = ins.getSystemWindowInsetTop();
            insetBottom = ins.getSystemWindowInsetBottom();
            if (Build.VERSION.SDK_INT >= 28) cutout = ins.getDisplayCutout();
            applyInsets();
            return ins;
        });

        callback = new LauncherApps.Callback() {
            @Override public void onPackageRemoved(String p, UserHandle u) { invalidatePkg(p); scheduleReload(); }
            @Override public void onPackageAdded(String p, UserHandle u) { invalidatePkg(p); scheduleReload(); }
            @Override public void onPackageChanged(String p, UserHandle u) { invalidatePkg(p); scheduleReload(); }
            @Override public void onPackagesAvailable(String[] p, UserHandle u, boolean r) { for (String s : p) invalidatePkg(s); scheduleReload(); }
            @Override public void onPackagesUnavailable(String[] p, UserHandle u, boolean r) { scheduleReload(); }
        };
        launcherApps.registerCallback(callback, main);

        registerBattery();
        LockActivity.ensureScreenReceiver(getApplicationContext());
        islandCtl.start();
        loadApps();
    }

    @Override
    protected void onResume() {
        super.onResume();
        islandCtl.refresh();
        updateGlobalIsland();
        updateWidgetTexts();
        if (!drawerOpen) playEntrance();
    }

    @Override
    protected void onStop() {
        super.onStop();
        setLayers(false);
        if (drawerOpen || drawerP > 0) {
            if (drawerAnim != null) drawerAnim.cancel();
            drawerOpen = false;
            setDrawerProgress(0f);
            resetSearch();
        }
        island.collapse();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        if (island.isExpanded()) island.collapse();
        if (drawerOpen) animateDrawer(false);
    }

    @Override
    public void onBackPressed() {
        if (island.isExpanded()) {
            island.collapse();
        } else if (drawerOpen) {
            if (!query.isEmpty()) searchField.setText("");
            else animateDrawer(false);
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        try { launcherApps.unregisterCallback(callback); } catch (Exception ignored) { }
        try { if (batteryReceiver != null) unregisterReceiver(batteryReceiver); } catch (Exception ignored) { }
        islandCtl.stop();
        exec.shutdownNow();
        iconPool.shutdownNow();
    }

    // ---------- pomocnicze ----------

    /** Bierze główny kolor tapety i przyciemnia go – szkło dopasowuje się do tapety. */
    private void readWallpaperTint() {
        int tint = 0xFF000000;
        if (Build.VERSION.SDK_INT >= 27) {
            try {
                android.app.WallpaperColors wc = android.app.WallpaperManager.getInstance(this)
                        .getWallpaperColors(android.app.WallpaperManager.FLAG_SYSTEM);
                if (wc != null) {
                    IconFactory.wallPrimary = wc.getPrimaryColor().toArgb();
                    IconFactory.wallSecondary = wc.getSecondaryColor() != null ? wc.getSecondaryColor().toArgb() : 0;
                    float[] hsv = new float[3];
                    Color.colorToHSV(wc.getPrimaryColor().toArgb(), hsv);
                    hsv[2] = Math.min(hsv[2], 0.35f);
                    tint = Color.HSVToColor(hsv);
                }
            } catch (Exception ignored) {
            }
        }
        GlassDrawable.tint = tint;
    }

    private static void invalidateTree(View v) {
        v.invalidate();
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) invalidateTree(g.getChildAt(i));
        }
    }

    /** Warstwy sprzętowe tylko na czas animacji – płynne przesuwanie bez przerysowywania. */
    private void setLayers(boolean on) {
        if (layersOn == on) return;
        layersOn = on;
        int t = on ? View.LAYER_TYPE_HARDWARE : View.LAYER_TYPE_NONE;
        homeLayer.setLayerType(t, null);
        drawer.setLayerType(t, null);
    }

    private int dp(float v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private GlassDrawable glass(float radiusDp, float strength) {
        return new GlassDrawable(getResources().getDisplayMetrics().density, dp(radiusDp), strength);
    }

    private int screenH() {
        int h = root != null ? root.getHeight() : 0;
        return h > 0 ? h : getResources().getDisplayMetrics().heightPixels;
    }

    private void pressEffect(View v) {
        v.setOnTouchListener((vv, e) -> {
            int a = e.getActionMasked();
            if (a == MotionEvent.ACTION_DOWN) {
                vv.animate().setStartDelay(0).scaleX(0.86f).scaleY(0.86f).setDuration(110).start();
            } else if (a == MotionEvent.ACTION_UP || a == MotionEvent.ACTION_CANCEL) {
                vv.animate().setStartDelay(0).scaleX(1f).scaleY(1f).setDuration(320)
                        .setInterpolator(new android.view.animation.OvershootInterpolator(2.2f)).start();
            }
            return false;
        });
    }

    // ---------- budowa UI ----------

    private void buildUi() {
        root = new FrameLayout(this);

        View scrim = new View(this);
        scrim.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{0x4D000000, 0x00000000, 0x00000000, 0x4D000000}));
        root.addView(scrim, new FrameLayout.LayoutParams(-1, -1));

        // ===== ekran główny =====
        homeLayer = new HomeFrame(this);
        root.addView(homeLayer, new FrameLayout.LayoutParams(-1, -1));

        homeContent = new LinearLayout(this);
        homeContent.setOrientation(LinearLayout.VERTICAL);
        homeLayer.addView(homeContent, new FrameLayout.LayoutParams(-1, -1));

        widget = buildWidget();
        LinearLayout.LayoutParams wlp = new LinearLayout.LayoutParams(-1, -2);
        wlp.topMargin = dp(40);
        wlp.leftMargin = dp(4);
        wlp.rightMargin = dp(4);
        homeContent.addView(widget, wlp);

        grid = new LinearLayout(this);
        grid.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams glp = new LinearLayout.LayoutParams(-1, -2);
        glp.topMargin = dp(28);
        homeContent.addView(grid, glp);

        homeContent.addView(new View(this), new LinearLayout.LayoutParams(1, 0, 1f));

        searchPill = new LinearLayout(this);
        searchPill.setOrientation(LinearLayout.HORIZONTAL);
        searchPill.setGravity(Gravity.CENTER);
        searchPill.setBackground(glass(18, 1f));
        searchPill.setPadding(dp(16), dp(8), dp(18), dp(8));
        ImageView si = new ImageView(this);
        si.setImageResource(R.drawable.ic_search);
        searchPill.addView(si, new LinearLayout.LayoutParams(dp(16), dp(16)));
        TextView st = new TextView(this);
        st.setText("Szukaj");
        st.setTextColor(Color.WHITE);
        st.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        st.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        st.setPadding(dp(8), 0, 0, 0);
        searchPill.addView(st);
        searchPill.setOnClickListener(v -> openDrawerWithSearch());
        pressEffect(searchPill);
        LinearLayout.LayoutParams plp = new LinearLayout.LayoutParams(-2, -2);
        plp.gravity = Gravity.CENTER_HORIZONTAL;
        plp.bottomMargin = dp(16);
        homeContent.addView(searchPill, plp);

        dock = new LinearLayout(this);
        dock.setOrientation(LinearLayout.HORIZONTAL);
        dock.setGravity(Gravity.CENTER_VERTICAL);
        dock.setBackground(glass(34, 1.1f));
        dock.setPadding(dp(10), dp(13), dp(10), dp(13));
        homeContent.addView(dock, new LinearLayout.LayoutParams(-1, -2));

        // ===== szuflada aplikacji =====
        drawer = new DrawerFrame(this);
        int tc = GlassDrawable.tint & 0xFFFFFF;
        boolean blur = false;
        if (Build.VERSION.SDK_INT >= 31) {
            try {
                blur = ((android.view.WindowManager) getSystemService(Context.WINDOW_SERVICE)).isCrossWindowBlurEnabled();
            } catch (Exception ignored) {
            }
        }
        drawer.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, blur
                ? new int[]{0xB3000000 | tc, 0x99000000 | tc, 0xD9000000 | tc}
                : new int[]{0xE6000000 | tc, 0xD9000000 | tc, 0xF2000000 | tc}));
        drawer.setVisibility(View.INVISIBLE);
        root.addView(drawer, new FrameLayout.LayoutParams(-1, -1));

        list = new ListView(this);
        list.setDivider(null);
        list.setDividerHeight(0);
        list.setSelector(new ColorDrawable(Color.TRANSPARENT));
        list.setVerticalScrollBarEnabled(false);
        list.setClipToPadding(false);
        list.setOverScrollMode(View.OVER_SCROLL_NEVER);
        list.setCacheColorHint(Color.TRANSPARENT);
        adapter = new Adapter();
        suggestView = buildSuggestHeader();
        list.addHeaderView(suggestView, null, false);
        list.setAdapter(adapter);
        list.setOnItemClickListener((p, v, position, id) -> {
            int pos = position - list.getHeaderViewsCount();
            if (pos < 0 || pos >= items.size()) return;
            Item it = items.get(pos);
            if (it.app != null) launch(it.app, v);
        });
        list.setOnItemLongClickListener((p, v, position, id) -> {
            int pos = position - list.getHeaderViewsCount();
            if (pos < 0 || pos >= items.size()) return false;
            Item it = items.get(pos);
            if (it.app != null) {
                showAppMenu(it.app);
                return true;
            }
            return false;
        });
        list.setOnScrollListener(new AbsListView.OnScrollListener() {
            @Override
            public void onScrollStateChanged(AbsListView view, int state) {
                if (state == SCROLL_STATE_TOUCH_SCROLL) hideKeyboard();
            }

            @Override
            public void onScroll(AbsListView view, int first, int visible, int total) {
            }
        });
        drawer.addView(list, new FrameLayout.LayoutParams(-1, -1));

        alphabet = new AlphabetView(this);
        drawer.addView(alphabet, new FrameLayout.LayoutParams(dp(120), -1, Gravity.END));

        bubble = new TextView(this);
        bubble.setTextColor(Color.WHITE);
        bubble.setTextSize(TypedValue.COMPLEX_UNIT_SP, 34);
        bubble.setTypeface(Typeface.create("sans-serif-light", Typeface.NORMAL));
        bubble.setGravity(Gravity.CENTER);
        bubble.setBackground(glass(34, 1.2f));
        bubble.setAlpha(0f);
        FrameLayout.LayoutParams blp = new FrameLayout.LayoutParams(dp(68), dp(68), Gravity.TOP | Gravity.END);
        blp.rightMargin = dp(132);
        drawer.addView(bubble, blp);

        alphabet.setListener(new AlphabetView.Listener() {
            @Override
            public void onLetter(String letter, float y) {
                Integer pos = sectionPos.get(letter);
                if (pos != null) list.setSelectionFromTop(pos + list.getHeaderViewsCount(), list.getPaddingTop());
                bubble.setText(letter);
                bubble.setTranslationY(y - dp(34));
                bubble.animate().cancel();
                bubble.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(120).start();
            }

            @Override
            public void onRelease() {
                bubble.animate().alpha(0f).scaleX(0.7f).scaleY(0.7f).setDuration(220).start();
            }
        });

        searchBar = new LinearLayout(this);
        searchBar.setOrientation(LinearLayout.HORIZONTAL);
        searchBar.setGravity(Gravity.CENTER_VERTICAL);
        searchBar.setBackground(glass(24, 1f));
        searchBar.setPadding(dp(16), 0, dp(16), 0);
        ImageView sIcon = new ImageView(this);
        sIcon.setImageResource(R.drawable.ic_search);
        sIcon.setAlpha(0.75f);
        searchBar.addView(sIcon, new LinearLayout.LayoutParams(dp(20), dp(20)));
        searchField = new EditText(this);
        searchField.setBackground(null);
        searchField.setHint("Szukaj aplikacji");
        searchField.setHintTextColor(0x99FFFFFF);
        searchField.setTextColor(Color.WHITE);
        searchField.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        searchField.setSingleLine(true);
        searchField.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        searchField.setImeOptions(EditorInfo.IME_ACTION_GO);
        searchField.setPadding(dp(10), 0, 0, 0);
        searchField.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) { }
            @Override
            public void afterTextChanged(Editable s) {
                query = s.toString().trim();
                alphabet.setVisibility(query.isEmpty() ? View.VISIBLE : View.GONE);
                rebuildDrawer();
                list.setSelection(0);
            }
        });
        searchField.setOnEditorActionListener((v, actionId, event) -> {
            boolean go = actionId == EditorInfo.IME_ACTION_GO
                    || (event != null && event.getKeyCode() == KeyEvent.KEYCODE_ENTER
                    && event.getAction() == KeyEvent.ACTION_DOWN);
            if (!go) return false;
            if (!query.isEmpty()) {
                for (Item it : items) {
                    if (it.app != null) {
                        launch(it.app, null);
                        return true;
                    }
                }
                webSearch(query);
            }
            return true;
        });
        searchBar.addView(searchField, new LinearLayout.LayoutParams(0, -1, 1f));
        FrameLayout.LayoutParams sblp = new FrameLayout.LayoutParams(-1, dp(48), Gravity.TOP);
        sblp.leftMargin = dp(18);
        sblp.rightMargin = dp(18);
        drawer.addView(searchBar, sblp);

        // ===== wyspa =====
        islandShield = new View(this);
        islandShield.setBackgroundColor(0x33000000);
        islandShield.setVisibility(View.GONE);
        islandShield.setOnClickListener(v -> island.collapse());
        root.addView(islandShield, new FrameLayout.LayoutParams(-1, -1));

        island = new IslandView(this);
        islandCtl = new IslandController(this, island, new IslandController.Host() {
            @Override
            public void onExpandChanged(boolean expanded) {
                if (expanded) {
                    islandShield.setVisibility(View.VISIBLE);
                    islandShield.setAlpha(0f);
                    islandShield.animate().alpha(1f).setDuration(200).start();
                } else {
                    islandShield.animate().alpha(0f).setDuration(200)
                            .withEndAction(() -> islandShield.setVisibility(View.GONE)).start();
                }
            }

            @Override
            public void onIslandSize(int w, int h, boolean starting) {
            }
        });
        root.addView(island, new FrameLayout.LayoutParams(island.containerW(), island.containerH(),
                Gravity.TOP | Gravity.CENTER_HORIZONTAL));
    }

    private LinearLayout buildWidget() {
        LinearLayout w = new LinearLayout(this);
        w.setOrientation(LinearLayout.HORIZONTAL);
        w.setGravity(Gravity.CENTER_VERTICAL);
        w.setBackground(glass(30, 1f));
        w.setPadding(dp(22), dp(18), dp(20), dp(18));

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        widgetGreeting = new TextView(this);
        widgetGreeting.setTextColor(0xCCFFFFFF);
        widgetGreeting.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        widgetGreeting.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        widgetGreeting.setLetterSpacing(0.04f);
        widgetGreeting.setPadding(dp(3), 0, 0, dp(2));
        col.addView(widgetGreeting);
        TextClock time = new TextClock(this);
        time.setFormat24Hour("HH:mm");
        time.setFormat12Hour("h:mm");
        time.setTextColor(Color.WHITE);
        time.setTextSize(TypedValue.COMPLEX_UNIT_SP, 56);
        time.setTypeface(Typeface.create("sans-serif-thin", Typeface.NORMAL));
        time.setIncludeFontPadding(false);
        time.setShadowLayer(dp(6), 0, dp(1), 0x40000000);
        col.addView(time);
        TextClock date = new TextClock(this);
        date.setFormat24Hour("EEEE, d MMMM");
        date.setFormat12Hour("EEEE, d MMMM");
        date.setTextColor(0xE6FFFFFF);
        date.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        date.setTypeface(Typeface.create("sans-serif", Typeface.NORMAL));
        date.setPadding(dp(3), dp(4), 0, 0);
        date.setShadowLayer(dp(4), 0, dp(1), 0x40000000);
        col.addView(date);
        widgetAlarm = new TextView(this);
        widgetAlarm.setTextColor(0xB3FFFFFF);
        widgetAlarm.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        widgetAlarm.setPadding(dp(3), dp(6), 0, 0);
        widgetAlarm.setVisibility(View.GONE);
        col.addView(widgetAlarm);
        w.addView(col, new LinearLayout.LayoutParams(0, -2, 1f));

        LinearLayout bat = new LinearLayout(this);
        bat.setOrientation(LinearLayout.VERTICAL);
        bat.setGravity(Gravity.CENTER_HORIZONTAL);
        widgetRing = new RingView(this);
        bat.addView(widgetRing, new LinearLayout.LayoutParams(dp(62), dp(62)));
        widgetBattery = new TextView(this);
        widgetBattery.setText("Bateria");
        widgetBattery.setTextColor(0xB3FFFFFF);
        widgetBattery.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        widgetBattery.setPadding(0, dp(5), 0, 0);
        bat.addView(widgetBattery);
        w.addView(bat);

        w.setOnClickListener(v -> openClock());
        w.setOnLongClickListener(v -> {
            showSettings();
            return true;
        });
        pressEffectSoft(w);
        return w;
    }

    private void pressEffectSoft(View v) {
        v.setOnTouchListener((vv, e) -> {
            int a = e.getActionMasked();
            if (a == MotionEvent.ACTION_DOWN) {
                vv.animate().setStartDelay(0).scaleX(0.97f).scaleY(0.97f).setDuration(120).start();
            } else if (a == MotionEvent.ACTION_UP || a == MotionEvent.ACTION_CANCEL) {
                vv.animate().setStartDelay(0).scaleX(1f).scaleY(1f).setDuration(220).start();
            }
            return false;
        });
    }

    private void applyInsets() {
        homeContent.setPadding(dp(16), insetTop + dp(6), dp(16), insetBottom + dp(12));
        list.setPadding(dp(22), insetTop + dp(116), dp(60), insetBottom + dp(24));
        alphabet.setPadding(0, insetTop + dp(116), 0, insetBottom + dp(24));

        FrameLayout.LayoutParams sblp = (FrameLayout.LayoutParams) searchBar.getLayoutParams();
        sblp.topMargin = insetTop + dp(52);
        searchBar.setLayoutParams(sblp);

        FrameLayout.LayoutParams ilp = (FrameLayout.LayoutParams) island.getLayoutParams();
        ilp.topMargin = IslandView.topFor(this, cutout, insetTop, island.idleH());
        island.setLayoutParams(ilp);
        island.setCoverTop(insetTop - ilp.topMargin);

        adapter.notifyDataSetChanged();
    }

    // ---------- dane ----------

    private static String normalize(String s) {
        String n = Normalizer.normalize(s, Normalizer.Form.NFD).replaceAll("\\p{Mn}+", "");
        return n.replace('ł', 'l').replace('Ł', 'L');
    }

    private static String sectionOf(String norm) {
        for (int i = 0; i < norm.length(); i++) {
            char c = norm.charAt(i);
            if (Character.isWhitespace(c)) continue;
            char u = Character.toUpperCase(c);
            if (u >= 'A' && u <= 'Z') return String.valueOf(u);
            return "#";
        }
        return "#";
    }

    /** Ikona w kształcie zaokrąglonego kwadratu (jak w iOS). */
    private Bitmap makeIcon(LauncherActivityInfo info, UserHandle user, int px) {
        Bitmap b = Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b);
        Path clip = new Path();
        float rad = px * 0.235f;
        clip.addRoundRect(new RectF(0, 0, px, px), rad, rad, Path.Direction.CW);
        Drawable d = null;
        try {
            d = info.getIcon(0);
        } catch (Exception ignored) {
        }
        if (d instanceof AdaptiveIconDrawable) {
            AdaptiveIconDrawable a = (AdaptiveIconDrawable) d;
            a.setBounds(0, 0, px, px);
            c.save();
            c.clipPath(clip);
            Drawable bg = a.getBackground();
            Drawable fg = a.getForeground();
            if (bg != null) bg.draw(c);
            else c.drawColor(Color.WHITE);
            if (fg != null) fg.draw(c);
            c.restore();
        } else {
            Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
            p.setColor(Color.WHITE);
            c.drawPath(clip, p);
            if (d != null) {
                int in = Math.round(px * 0.14f);
                d.setBounds(in, in, px - in, px - in);
                d.draw(c);
            }
        }
        if (!user.equals(android.os.Process.myUserHandle())) {
            try {
                Drawable badged = getPackageManager().getUserBadgedIcon(new BitmapDrawable(getResources(), b), user);
                Bitmap b2 = Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888);
                Canvas c2 = new Canvas(b2);
                badged.setBounds(0, 0, px, px);
                badged.draw(c2);
                return b2;
            } catch (Exception ignored) {
            }
        }
        return b;
    }

    private void invalidatePkg(String pkg) {
        String prefix = pkg + "/";
        for (String k : iconCache.keySet()) if (k.startsWith(prefix)) iconCache.remove(k);
    }

    /** Grupuje serię zdarzeń instalacji w jedno przeładowanie. */
    private void scheduleReload() {
        main.removeCallbacks(reloadRunnable);
        main.postDelayed(reloadRunnable, 400);
    }

    private void loadApps() {
        if (exec.isShutdown()) return;
        exec.execute(() -> {
            List<App> out = new ArrayList<>();
            final int px = dp(64);
            UserManager um = (UserManager) getSystemService(Context.USER_SERVICE);
            List<Callable<Void>> jobs = new ArrayList<>();
            for (UserHandle u : um.getUserProfiles()) {
                List<LauncherActivityInfo> infos;
                try {
                    infos = launcherApps.getActivityList(null, u);
                } catch (Exception e) {
                    continue;
                }
                for (LauncherActivityInfo i : infos) {
                    ComponentName cn = i.getComponentName();
                    if (cn.getPackageName().equals(getPackageName())) continue;
                    final App a = new App();
                    CharSequence l = i.getLabel();
                    a.label = l == null ? cn.getPackageName() : l.toString().trim();
                    String customLabel = prefs.getString("label_" + cn.flattenToString() + "#" + u.hashCode(), null);
                    if (customLabel != null && !customLabel.trim().isEmpty()) a.label = customLabel.trim();
                    a.norm = normalize(a.label).toLowerCase(Locale.ROOT);
                    a.section = sectionOf(a.norm);
                    a.component = cn;
                    a.user = u;
                    a.key = cn.flattenToString() + "#" + u.hashCode();
                    a.icon = iconCache.get(a.key);
                    if (a.icon == null) {
                        jobs.add(() -> {
                            try {
                                a.icon = icons.make(i, u, a.key, px);
                                if (a.icon != null) iconCache.put(a.key, a.icon);
                            } catch (Exception ignored) {
                            }
                            return null;
                        });
                    }
                    out.add(a);
                }
            }
            // Ikony renderowane równolegle; z pamięci podręcznej przy kolejnych przeładowaniach.
            try {
                if (!jobs.isEmpty()) iconPool.invokeAll(jobs);
            } catch (InterruptedException e) {
                return;
            }
            final Collator col = Collator.getInstance(new Locale("pl", "PL"));
            Collections.sort(out, (x, y) -> {
                int s = x.section.compareTo(y.section);
                return s != 0 ? s : col.compare(x.label, y.label);
            });
            if (!prefs.contains("dock") || !prefs.contains("home")) pickDefaults(out);
            main.post(() -> {
                apps.clear();
                apps.addAll(out);
                byKey.clear();
                for (App a : apps) byKey.put(a.key, a);
                rebuildHome();
                rebuildDrawer();
            });
        });
    }

    private void pickDefaults(List<App> all) {
        List<String> dockKeys = new ArrayList<>();
        if (!prefs.contains("dock")) {
            PackageManager pm = getPackageManager();
            Intent[] intents = new Intent[]{
                    new Intent(Intent.ACTION_DIAL),
                    new Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:")),
                    new Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com")),
                    new Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA),
            };
            for (Intent in : intents) {
                try {
                    ResolveInfo ri = pm.resolveActivity(in, PackageManager.MATCH_DEFAULT_ONLY);
                    if (ri == null || ri.activityInfo == null) continue;
                    String pkg = ri.activityInfo.packageName;
                    for (App a : all) {
                        if (a.component.getPackageName().equals(pkg) && !dockKeys.contains(a.key)) {
                            dockKeys.add(a.key);
                            break;
                        }
                    }
                } catch (Exception ignored) {
                }
            }
            saveList("dock", dockKeys);
        } else {
            dockKeys = getList("dock");
        }
        if (!prefs.contains("home")) {
            String[] pkgs = {
                    "com.google.android.gm", "com.google.android.apps.photos", "com.sec.android.gallery3d",
                    "com.miui.gallery", "com.google.android.youtube", "com.google.android.apps.maps",
                    "com.android.vending", "com.whatsapp", "com.instagram.android", "com.facebook.katana",
                    "com.facebook.orca", "com.spotify.music", "com.zhiliaoapp.musically", "com.snapchat.android",
                    "com.google.android.calendar", "com.samsung.android.calendar", "com.google.android.deskclock",
                    "com.sec.android.app.clockpackage", "com.google.android.keep", "com.google.android.calculator",
                    "com.sec.android.app.popupcalculator", "com.android.settings", "com.discord", "pl.allegro",
                    "com.google.android.apps.youtube.music", "com.netflix.mediaclient", "com.android.chrome",
            };
            List<String> home = new ArrayList<>();
            for (String p : pkgs) {
                if (home.size() >= 12) break;
                for (App a : all) {
                    if (a.component.getPackageName().equals(p) && !dockKeys.contains(a.key) && !home.contains(a.key)) {
                        home.add(a.key);
                        break;
                    }
                }
            }
            saveList("home", home);
        }
    }

    private List<String> getList(String name) {
        String s = prefs.getString(name, "");
        List<String> out = new ArrayList<>();
        for (String k : s.split("\n")) if (!k.isEmpty()) out.add(k);
        return out;
    }

    private void saveList(String name, List<String> keys) {
        prefs.edit().putString(name, TextUtils.join("\n", keys)).apply();
    }

    // ---------- ekran główny ----------

    private void rebuildHome() {
        grid.removeAllViews();
        List<App> home = new ArrayList<>();
        for (String k : getList("home")) {
            App a = byKey.get(k);
            if (a != null) home.add(a);
        }
        if (home.isEmpty()) {
            TextView hint = new TextView(this);
            hint.setText("Przesuń w górę, aby zobaczyć wszystkie aplikacje.\nPrzytrzymaj aplikację, aby dodać ją tutaj.");
            hint.setTextColor(0xE6FFFFFF);
            hint.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
            hint.setGravity(Gravity.CENTER);
            hint.setLineSpacing(dp(3), 1f);
            hint.setBackground(glass(24, 0.9f));
            hint.setPadding(dp(18), dp(16), dp(18), dp(16));
            grid.addView(hint, new LinearLayout.LayoutParams(-1, -2));
        } else {
            int cols = 4;
            LinearLayout row = null;
            for (int i = 0; i < home.size() && i < 24; i++) {
                if (i % cols == 0) {
                    row = new LinearLayout(this);
                    row.setOrientation(LinearLayout.HORIZONTAL);
                    LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(-1, -2);
                    rlp.bottomMargin = dp(18);
                    grid.addView(row, rlp);
                }
                row.addView(homeCell(home.get(i)), new LinearLayout.LayoutParams(0, -2, 1f));
            }
            int rest = home.size() % cols;
            if (rest != 0 && row != null) {
                for (int i = rest; i < cols; i++) row.addView(new View(this), new LinearLayout.LayoutParams(0, 1, 1f));
            }
        }

        dock.removeAllViews();
        List<String> dockKeys = getList("dock");
        int added = 0;
        for (String k : dockKeys) {
            App a = byKey.get(k);
            if (a == null) continue;
            ImageView iv = iconView(a);
            LinearLayout cell = new LinearLayout(this);
            cell.setGravity(Gravity.CENTER);
            int ds = dp(icons.homeDp() - 2);
            cell.addView(iv, new LinearLayout.LayoutParams(ds, ds));
            dock.addView(cell, new LinearLayout.LayoutParams(0, -2, 1f));
            added++;
        }
        dock.setVisibility(added > 0 ? View.VISIBLE : View.GONE);
    }

    private ImageView iconView(App a) {
        ImageView iv = new ImageView(this);
        if (a.icon != null) iv.setImageBitmap(a.icon);
        iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
        iv.setElevation(dp(3));
        iv.setOutlineProvider(new android.view.ViewOutlineProvider() {
            @Override
            public void getOutline(View view, android.graphics.Outline outline) {
                outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), icons.outlineRadius(view.getWidth()));
            }
        });
        iv.setOnClickListener(v -> launch(a, v));
        iv.setOnLongClickListener(v -> {
            showAppMenu(a);
            return true;
        });
        iv.setContentDescription(a.label);
        pressEffect(iv);
        return iv;
    }

    private View homeCell(App a) {
        LinearLayout cell = new LinearLayout(this);
        cell.setOrientation(LinearLayout.VERTICAL);
        cell.setGravity(Gravity.CENTER_HORIZONTAL);
        int hs = dp(icons.homeDp());
        cell.addView(iconView(a), new LinearLayout.LayoutParams(hs, hs));
        TextView t = new TextView(this);
        t.setText(a.label);
        t.setTextColor(Color.WHITE);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        t.setSingleLine(true);
        t.setEllipsize(TextUtils.TruncateAt.END);
        t.setGravity(Gravity.CENTER);
        t.setShadowLayer(dp(3), 0, dp(1), 0x99000000);
        t.setPadding(dp(2), dp(6), dp(2), 0);
        t.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        if (!icons.labels()) t.setVisibility(View.GONE);
        cell.addView(t, new LinearLayout.LayoutParams(-1, -2));
        return cell;
    }

    // ---------- widget i animacje wejścia ----------

    private void updateWidgetTexts() {
        int h = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY);
        String g;
        if (h < 5) g = "Dobranoc";
        else if (h < 12) g = "Dzień dobry";
        else if (h < 18) g = "Miłego popołudnia";
        else g = "Dobry wieczór";
        widgetGreeting.setText(g);

        try {
            android.app.AlarmManager am = (android.app.AlarmManager) getSystemService(Context.ALARM_SERVICE);
            android.app.AlarmManager.AlarmClockInfo info = am.getNextAlarmClock();
            if (info != null) {
                long t = info.getTriggerTime();
                java.util.Calendar now = java.util.Calendar.getInstance();
                java.util.Calendar at = java.util.Calendar.getInstance();
                at.setTimeInMillis(t);
                String time = android.text.format.DateFormat.getTimeFormat(this).format(new java.util.Date(t));
                String day;
                int diff = at.get(java.util.Calendar.DAY_OF_YEAR) - now.get(java.util.Calendar.DAY_OF_YEAR);
                if (at.get(java.util.Calendar.YEAR) != now.get(java.util.Calendar.YEAR)) diff = 7;
                if (diff == 0) day = "dziś";
                else if (diff == 1) day = "jutro";
                else day = new java.text.SimpleDateFormat("EEE", new Locale("pl", "PL")).format(new java.util.Date(t));
                widgetAlarm.setText("⏰  Budzik " + day + " " + time);
                widgetAlarm.setVisibility(View.VISIBLE);
            } else {
                widgetAlarm.setVisibility(View.GONE);
            }
        } catch (Exception e) {
            widgetAlarm.setVisibility(View.GONE);
        }
    }

    /** Kaskadowe wejście elementów (jak w iOS po powrocie na ekran główny). */
    private void playEntrance() {
        List<View> views = new ArrayList<>();
        views.add(widget);
        for (int i = 0; i < grid.getChildCount(); i++) views.add(grid.getChildAt(i));
        views.add(searchPill);
        views.add(dock);
        int d = 0;
        for (View v : views) {
            v.animate().cancel();
            v.setAlpha(0f);
            v.setTranslationY(dp(22));
            v.setScaleX(0.96f);
            v.setScaleY(0.96f);
            v.animate().alpha(1f).translationY(0f).scaleX(1f).scaleY(1f)
                    .setStartDelay(d).setDuration(420).setInterpolator(smooth)
                    .withLayer().start();
            d += 28;
        }
    }

    // ---------- propozycje ----------

    private LinearLayout suggestCard;
    private LinearLayout suggestRow;

    private View buildSuggestHeader() {
        FrameLayout wrap = new FrameLayout(this);
        wrap.setPadding(0, 0, 0, dp(6));
        suggestCard = new LinearLayout(this);
        suggestCard.setOrientation(LinearLayout.VERTICAL);
        suggestCard.setBackground(glass(26, 0.9f));
        suggestCard.setPadding(dp(14), dp(12), dp(14), dp(14));
        TextView label = new TextView(this);
        label.setText("PROPOZYCJE");
        label.setTextColor(0x99FFFFFF);
        label.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        label.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        label.setLetterSpacing(0.12f);
        label.setPadding(dp(4), 0, 0, dp(10));
        suggestCard.addView(label);
        suggestRow = new LinearLayout(this);
        suggestRow.setOrientation(LinearLayout.HORIZONTAL);
        suggestCard.addView(suggestRow, new LinearLayout.LayoutParams(-1, -2));
        suggestCard.setVisibility(View.GONE);
        wrap.addView(suggestCard, new FrameLayout.LayoutParams(-1, -2));
        return wrap;
    }

    private void rebuildSuggestions() {
        if (suggestCard == null) return;
        List<App> top = new ArrayList<>();
        if (query.isEmpty()) {
            Map<String, ?> all = usage.getAll();
            List<App> used = new ArrayList<>();
            for (App a : apps) {
                Object c = all.get(a.key);
                if (c instanceof Integer && (Integer) c > 0) used.add(a);
            }
            Collections.sort(used, (x, y) -> Integer.compare(
                    usage.getInt(y.key, 0), usage.getInt(x.key, 0)));
            for (int i = 0; i < used.size() && i < 4; i++) top.add(used.get(i));
        }
        suggestRow.removeAllViews();
        if (top.isEmpty()) {
            suggestCard.setVisibility(View.GONE);
            return;
        }
        for (App a : top) suggestRow.addView(homeCell(a), new LinearLayout.LayoutParams(0, -2, 1f));
        for (int i = top.size(); i < 4; i++) suggestRow.addView(new View(this), new LinearLayout.LayoutParams(0, 1, 1f));
        suggestCard.setVisibility(View.VISIBLE);
    }

    // ---------- szuflada ----------

    private void rebuildDrawer() {
        items.clear();
        sectionPos.clear();
        LinkedHashSet<String> letters = new LinkedHashSet<>();

        if (query.isEmpty()) {
            String last = null;
            for (App a : apps) {
                if (!a.section.equals(last)) {
                    last = a.section;
                    sectionPos.put(last, items.size());
                    letters.add(last);
                    items.add(new Item(T_SECTION, null, last));
                }
                items.add(new Item(T_APP, a, null));
            }
        } else {
            String q = normalize(query).toLowerCase(Locale.ROOT);
            List<App> starts = new ArrayList<>();
            List<App> wordStarts = new ArrayList<>();
            List<App> contains = new ArrayList<>();
            for (App a : apps) {
                if (a.norm.startsWith(q)) starts.add(a);
                else if (a.norm.contains(" " + q)) wordStarts.add(a);
                else if (a.norm.contains(q)) contains.add(a);
            }
            items.add(new Item(T_LABEL, null, "WYNIKI"));
            for (App a : starts) items.add(new Item(T_APP, a, null));
            for (App a : wordStarts) items.add(new Item(T_APP, a, null));
            for (App a : contains) items.add(new Item(T_APP, a, null));
            if (starts.isEmpty() && wordStarts.isEmpty() && contains.isEmpty()) {
                items.add(new Item(T_HINT, null, "Brak aplikacji. Naciśnij Enter, aby wyszukać „" + query + "” w internecie."));
            }
        }
        adapter.notifyDataSetChanged();
        alphabet.setLetters(new ArrayList<>(letters));
        rebuildSuggestions();
    }

    private void setDrawerProgress(float p) {
        p = Math.max(0f, Math.min(1f, p));
        drawerP = p;
        int h = screenH();
        drawer.setVisibility(p > 0f ? View.VISIBLE : View.INVISIBLE);
        drawer.setTranslationY((1f - p) * h * 0.45f);
        drawer.setAlpha(Math.min(1f, p * 1.5f));
        homeLayer.setAlpha(1f - p);
        homeLayer.setScaleX(1f - 0.07f * p);
        homeLayer.setScaleY(1f - 0.07f * p);
        homeLayer.setVisibility(p >= 1f ? View.INVISIBLE : View.VISIBLE);
        if (Build.VERSION.SDK_INT >= 31) {
            int blur = Math.round(p * dp(40));
            // rozmycie zmieniamy tylko przy wyraźnej różnicy – mniej pracy dla kompozytora
            if (lastBlur < 0 || Math.abs(blur - lastBlur) >= dp(2) || blur == 0) {
                if (blur != lastBlur) {
                    try {
                        getWindow().setBackgroundBlurRadius(blur);
                    } catch (Exception ignored) {
                    }
                    lastBlur = blur;
                }
            }
        }
    }

    private void animateDrawer(boolean open) {
        if (drawerAnim != null) drawerAnim.cancel();
        setLayers(true);
        drawerAnim = ValueAnimator.ofFloat(drawerP, open ? 1f : 0f);
        drawerAnim.setDuration(open ? 380 : 300);
        drawerAnim.setInterpolator(smooth);
        drawerAnim.addUpdateListener(a -> setDrawerProgress((float) a.getAnimatedValue()));
        drawerAnim.addListener(new android.animation.AnimatorListenerAdapter() {
            boolean cancelled = false;

            @Override
            public void onAnimationCancel(android.animation.Animator animation) {
                cancelled = true;
            }

            @Override
            public void onAnimationEnd(android.animation.Animator animation) {
                if (!cancelled) setLayers(false);
            }
        });
        drawerOpen = open;
        if (!open) resetSearch();
        else rebuildSuggestions();
        drawerAnim.start();
    }

    /** Lekka wibracja w momencie, gdy puszczenie palca otworzy/zamknie szufladę. */
    private void dragFeedback(boolean pastThreshold) {
        if (pastThreshold && !thresholdBuzzed) {
            root.performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK);
        }
        thresholdBuzzed = pastThreshold;
    }

    private void openDrawerWithSearch() {
        animateDrawer(true);
        searchField.requestFocus();
        InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
        searchField.postDelayed(() -> imm.showSoftInput(searchField, InputMethodManager.SHOW_IMPLICIT), 200);
    }

    private void resetSearch() {
        hideKeyboard();
        searchField.clearFocus();
        if (!query.isEmpty()) searchField.setText("");
        list.setSelection(0);
    }

    private void hideKeyboard() {
        InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
        imm.hideSoftInputFromWindow(searchField.getWindowToken(), 0);
    }

    private boolean listAtTop() {
        if (list.getFirstVisiblePosition() != 0) return false;
        if (list.getChildCount() == 0) return true;
        return list.getChildAt(0).getTop() >= list.getPaddingTop() - dp(2);
    }

    // ---------- akcje ----------

    private void launch(App a, View v) {
        try {
            Rect r = null;
            Bundle opts = null;
            if (v != null && v.getWidth() > 0) {
                int[] loc = new int[2];
                v.getLocationOnScreen(loc);
                r = new Rect(loc[0], loc[1], loc[0] + v.getWidth(), loc[1] + v.getHeight());
                opts = ActivityOptions.makeScaleUpAnimation(v, 0, 0, v.getWidth(), v.getHeight()).toBundle();
            }
            launcherApps.startMainActivity(a.component, a.user, r, opts);
            usage.edit().putInt(a.key, usage.getInt(a.key, 0) + 1).apply();
        } catch (Exception e) {
            Toast.makeText(this, "Nie można uruchomić: " + a.label, Toast.LENGTH_SHORT).show();
        }
    }

    private void safeStart(Intent i) {
        try {
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(i);
        } catch (ActivityNotFoundException | SecurityException e) {
            Toast.makeText(this, "Brak aplikacji do tej akcji", Toast.LENGTH_SHORT).show();
        }
    }

    private void openClock() {
        safeStart(new Intent(AlarmClock.ACTION_SHOW_ALARMS));
    }

    private void webSearch(String q) {
        Intent i = new Intent(Intent.ACTION_WEB_SEARCH);
        i.putExtra(SearchManager.QUERY, q);
        try {
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(i);
        } catch (Exception e) {
            safeStart(new Intent(Intent.ACTION_VIEW,
                    Uri.parse("https://www.google.com/search?q=" + Uri.encode(q))));
        }
    }

    private void expandNotifications() {
        IslandController.expandNotifications(this);
    }

    private void showAppMenu(App a) {
        List<String> labels = new ArrayList<>();
        List<Runnable> actions = new ArrayList<>();
        final List<String> home = getList("home");
        final List<String> dockKeys = getList("dock");
        final int hi = home.indexOf(a.key);
        final int di = dockKeys.indexOf(a.key);

        if (hi < 0) {
            labels.add("＋  Dodaj do ekranu głównego");
            actions.add(() -> {
                home.add(a.key);
                saveList("home", home);
                rebuildHome();
            });
        } else {
            labels.add("－  Usuń z ekranu głównego");
            actions.add(() -> {
                home.remove(a.key);
                saveList("home", home);
                rebuildHome();
            });
            if (hi > 0) {
                labels.add("←  Przesuń wcześniej");
                actions.add(() -> {
                    Collections.swap(home, hi, hi - 1);
                    saveList("home", home);
                    rebuildHome();
                });
            }
            if (hi < home.size() - 1) {
                labels.add("→  Przesuń dalej");
                actions.add(() -> {
                    Collections.swap(home, hi, hi + 1);
                    saveList("home", home);
                    rebuildHome();
                });
            }
        }
        if (di < 0) {
            if (dockKeys.size() < 5) {
                labels.add("▁  Dodaj do docka");
                actions.add(() -> {
                    dockKeys.add(a.key);
                    saveList("dock", dockKeys);
                    rebuildHome();
                });
            }
        } else {
            labels.add("▁  Usuń z docka");
            actions.add(() -> {
                dockKeys.remove(a.key);
                saveList("dock", dockKeys);
                rebuildHome();
            });
        }
        labels.add("✎  Zmień nazwę");
        actions.add(() -> renameApp(a));
        labels.add("🖼  Zmień ikonę (z galerii)");
        actions.add(() -> pickIcon(a));
        if (icons.hasCustom(a.key) || prefs.contains("label_" + a.key)) {
            labels.add("↺  Przywróć oryginał");
            actions.add(() -> {
                icons.removeCustom(a.key);
                prefs.edit().remove("label_" + a.key).apply();
                iconCache.remove(a.key);
                loadApps();
            });
        }
        final String pkgName = a.component.getPackageName();
        if (GameMode.isGame(this, pkgName)) {
            labels.add("🎮  To nie jest gra");
            actions.add(() -> GameMode.markGame(this, pkgName, false));
        } else {
            labels.add("🎮  To jest gra (Tryb Ultra Gry)");
            actions.add(() -> {
                GameMode.markGame(this, pkgName, true);
                Toast.makeText(this, "Dodano do gier – tryb włączy się po jej uruchomieniu", Toast.LENGTH_SHORT).show();
            });
        }
        labels.add("ⓘ  Informacje o aplikacji");
        actions.add(() -> {
            try {
                launcherApps.startAppDetailsActivity(a.component, a.user, null, null);
            } catch (Exception e) {
                safeStart(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.parse("package:" + a.component.getPackageName())));
            }
        });
        labels.add("✕  Odinstaluj");
        actions.add(() -> safeStart(new Intent(Intent.ACTION_DELETE,
                Uri.parse("package:" + a.component.getPackageName()))));

        showMenu(a.label, labels, actions);
    }

    private void openAssistant() {
        try {
            Intent i = new Intent(this, AssistantActivity.class);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_NO_ANIMATION);
            startActivity(i);
        } catch (Exception ignored) {
        }
    }

    private boolean globalIslandPref() {
        return prefs.getBoolean("island_global", true);
    }

    private void toggleGlobalIsland() {
        boolean on = !globalIslandPref();
        prefs.edit().putBoolean("island_global", on).apply();
        IslandService s0 = IslandService.instance;
        if (s0 != null) s0.refreshVisibility();
        if (on && !Settings.canDrawOverlays(this)) {
            askOverlay();
        }
        updateGlobalIsland();
    }

    private void askOverlay() {
        Toast.makeText(this, "Zezwól Fali na wyświetlanie nad innymi aplikacjami", Toast.LENGTH_LONG).show();
        safeStart(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:" + getPackageName())));
    }

    /** Wyspa nad wszystkimi aplikacjami (usługa) albo tylko na ekranie głównym. */
    private void updateGlobalIsland() {
        boolean want = IslandService.wanted(this);
        boolean can = Settings.canDrawOverlays(this);
        if (want && can) {
            IslandService.start(this);
            IslandService s = IslandService.instance;
            if (s != null) s.refreshVisibility();
            // własna wyspa launchera zostaje, dopóki nakładka faktycznie się nie pokaże
            island.setVisibility(IslandService.isShowing() ? View.GONE : View.VISIBLE);
            main.postDelayed(() -> island.setVisibility(IslandService.isShowing() ? View.GONE : View.VISIBLE), 900);
        } else {
            IslandService.stop(this);
            island.setVisibility(View.VISIBLE);
        }
        if (!prefs.getBoolean("setup_shown", false)) {
            prefs.edit().putBoolean("setup_shown", true).apply();
            main.postDelayed(this::showSetup, 1000);
        }
        if (setupDlg != null && setupDlg.isShowing()) {
            setupDlg.dismiss();
            showSetup();
        }
        if (gameDlg != null && gameDlg.isShowing()) {
            gameDlg.dismiss();
            showGameSettings();
        }
    }

    private AlertDialog setupDlg;
    private AlertDialog gameDlg;

    /** Ustawienia Trybu Ultra Gry. */
    private void showGameSettings() {
        LinearLayout box = dialogBox("🎮  Tryb Ultra Gry");
        android.widget.ScrollView sv = new android.widget.ScrollView(this);
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        sv.addView(col);

        TextView intro = new TextView(this);
        intro.setText("Gdy uruchomisz grę, przy prawej krawędzi pojawi się kolorowy uchwyt. Dotknij go lub przesuń w lewo, aby otworzyć panel gry.");
        intro.setTextColor(0xB3FFFFFF);
        intro.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        intro.setLineSpacing(dp(2), 1f);
        intro.setPadding(dp(4), 0, dp(4), dp(8));
        col.addView(intro);

        boolean on = GameMode.enabled(this);
        col.addView(setupRow(on, on ? "Tryb Ultra Gry: włączony" : "Tryb Ultra Gry: wyłączony",
                "Dotknij, aby włączyć", () -> {
                    prefs.edit().putBoolean("game_mode", !GameMode.enabled(this)).apply();
                    updateGlobalIsland();
                    gameDlg.dismiss();
                    showGameSettings();
                }));
        col.addView(setupRow(GameMode.hasUsageAccess(this), "Dostęp do użycia aplikacji",
                "Potrzebny, żeby wykryć, że włączasz grę – znajdź Falę i zezwól",
                () -> safeStart(new Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))));
        col.addView(setupRow(Settings.canDrawOverlays(this), "Wyświetlanie nad aplikacjami",
                "Potrzebne dla panelu i licznika w grze", this::askOverlay));
        col.addView(setupRow(Settings.System.canWrite(this), "Zmiana jasności",
                "Potrzebne do chłodzenia (przyciemnianie ekranu)",
                () -> safeStart(new Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, Uri.parse("package:" + getPackageName())))));
        col.addView(setupRow(IslandController.hasNotificationAccess(this), "Dostęp do powiadomień",
                "Potrzebny dla „Nie przeszkadzać” w grze",
                () -> safeStart(new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))));

        col.addView(sectionLabel("PO WEJŚCIU DO GRY"));
        col.addView(sectionLabel("Licznik na ekranie"));
        col.addView(chips(new String[]{"Pokaż", "Ukryj"}, prefs.getBoolean("game_hud", true) ? 0 : 1,
                i -> prefs.edit().putBoolean("game_hud", i == 0).apply()));
        col.addView(sectionLabel("Nie przeszkadzać"));
        col.addView(chips(new String[]{"Automatycznie", "Ręcznie"}, prefs.getBoolean("game_auto_dnd", false) ? 0 : 1,
                i -> prefs.edit().putBoolean("game_auto_dnd", i == 0).apply()));
        col.addView(sectionLabel("Chłodzenie"));
        col.addView(chips(new String[]{"Automatycznie", "Ręcznie"}, prefs.getBoolean("game_auto_cool", false) ? 0 : 1,
                i -> prefs.edit().putBoolean("game_auto_cool", i == 0).apply()));
        col.addView(sectionLabel("Banery wyspy w grze"));
        col.addView(chips(new String[]{"Blokuj", "Pokazuj"}, prefs.getBoolean("game_block_banners", true) ? 0 : 1,
                i -> prefs.edit().putBoolean("game_block_banners", i == 0).apply()));

        col.addView(sectionLabel("WYKRYTE GRY"));
        StringBuilder sb = new StringBuilder();
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (App a : apps) {
            String pkg = a.component.getPackageName();
            if (seen.add(pkg) && GameMode.isGame(this, pkg)) {
                if (sb.length() > 0) sb.append(", ");
                sb.append(a.label);
            }
        }
        TextView games = new TextView(this);
        games.setText(sb.length() > 0 ? sb.toString() : "Nie wykryto gier.");
        games.setTextColor(Color.WHITE);
        games.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        games.setPadding(dp(4), 0, dp(4), dp(6));
        col.addView(games);
        TextView hint = new TextView(this);
        hint.setText("Brakuje gry albo coś nie jest grą? Przytrzymaj aplikację w szufladzie i wybierz „To jest gra” / „To nie jest gra”.\n\nUwaga: FPS samej gry nie da się odczytać bez roota – licznik pokazuje odświeżanie ekranu (Hz). Ping mierzony jest do serwera 1.1.1.1, nie do serwera gry.");
        hint.setTextColor(0x99FFFFFF);
        hint.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        hint.setLineSpacing(dp(2), 1f);
        hint.setPadding(dp(4), dp(6), dp(4), 0);
        col.addView(hint);

        box.addView(sv, new LinearLayout.LayoutParams(-1, Math.round(getResources().getDisplayMetrics().heightPixels * 0.62f)));
        AlertDialog dlg = new AlertDialog.Builder(this).setView(box).create();
        LinearLayout btns = new LinearLayout(this);
        btns.setGravity(Gravity.END);
        btns.setPadding(0, dp(10), 0, 0);
        TextView done = dialogButton("Gotowe");
        done.setOnClickListener(v -> dlg.dismiss());
        btns.addView(done);
        box.addView(btns);
        gameDlg = dlg;
        showGlassDialog(dlg);
    }

    private boolean isDefaultHome() {
        try {
            Intent h = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME);
            ResolveInfo ri = getPackageManager().resolveActivity(h, PackageManager.MATCH_DEFAULT_ONLY);
            return ri != null && ri.activityInfo != null && getPackageName().equals(ri.activityInfo.packageName);
        } catch (Exception e) {
            return false;
        }
    }

    private boolean batteryUnrestricted() {
        android.os.PowerManager pm = (android.os.PowerManager) getSystemService(Context.POWER_SERVICE);
        return pm != null && pm.isIgnoringBatteryOptimizations(getPackageName());
    }

    private static boolean isXiaomi() {
        String m = (Build.MANUFACTURER + " " + Build.BRAND).toLowerCase(Locale.ROOT);
        return m.contains("xiaomi") || m.contains("redmi") || m.contains("poco");
    }

    private void openXiaomiPermissions() {
        try {
            Intent i = new Intent("miui.intent.action.APP_PERM_EDITOR");
            i.setClassName("com.miui.securitycenter", "com.miui.permcenter.permissions.PermissionsEditorActivity");
            i.putExtra("extra_pkgname", getPackageName());
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(i);
        } catch (Exception e) {
            safeStart(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + getPackageName())));
        }
    }

    private View setupRow(boolean ok, String title, String desc, Runnable fix) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(12), dp(11), dp(12), dp(11));
        row.setBackground(ripple());
        TextView mark = new TextView(this);
        mark.setText(ok ? "✓" : "!");
        mark.setGravity(Gravity.CENTER);
        mark.setTextColor(ok ? 0xFF111111 : Color.WHITE);
        mark.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        mark.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        GradientDrawable g = new GradientDrawable();
        g.setShape(GradientDrawable.OVAL);
        g.setColor(ok ? 0xFF30D158 : 0xFFFF9F0A);
        mark.setBackground(g);
        row.addView(mark, new LinearLayout.LayoutParams(dp(26), dp(26)));
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(dp(14), 0, 0, 0);
        TextView t = new TextView(this);
        t.setText(title);
        t.setTextColor(Color.WHITE);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        t.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        col.addView(t);
        TextView d = new TextView(this);
        d.setText(ok ? "Gotowe" : desc);
        d.setTextColor(ok ? 0x8030D158 | 0xFF000000 : 0xB3FFFFFF);
        d.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        col.addView(d);
        row.addView(col, new LinearLayout.LayoutParams(0, -2, 1f));
        if (!ok && fix != null) row.setOnClickListener(v -> fix.run());
        else if (fix != null) row.setOnClickListener(v -> fix.run());
        return row;
    }

    /** Konfiguracja: co jest włączone, czego brakuje – jedno dotknięcie naprawia. */
    private void showSetup() {
        LinearLayout box = dialogBox("Konfiguracja Fali");
        android.widget.ScrollView sv = new android.widget.ScrollView(this);
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        sv.addView(col);

        col.addView(setupRow(isDefaultHome(), "Fala jako ekran główny",
                "Dotknij i wybierz Falę", () -> safeStart(new Intent(Settings.ACTION_HOME_SETTINGS))));
        col.addView(setupRow(Settings.canDrawOverlays(this), "Wyświetlanie nad aplikacjami",
                "Potrzebne dla wyspy wszędzie i ekranu blokady", this::askOverlay));
        col.addView(setupRow(IslandService.isShowing() || !globalIslandPref(),
                globalIslandPref() ? "Wyspa w każdej aplikacji" : "Wyspa w każdej aplikacji (wyłączona)",
                Settings.canDrawOverlays(this) ? "Dotknij, aby uruchomić ponownie" : "Najpierw zezwól na wyświetlanie nad aplikacjami",
                () -> {
                    if (!globalIslandPref()) prefs.edit().putBoolean("island_global", true).apply();
                    IslandService.stop(this);
                    main.postDelayed(this::updateGlobalIsland, 300);
                    main.postDelayed(() -> { if (setupDlg != null && setupDlg.isShowing()) { setupDlg.dismiss(); showSetup(); } }, 1400);
                }));
        col.addView(setupRow(lockEnabled(), "Ekran blokady iPhone",
                "Dotknij, aby włączyć", () -> {
                    if (!lockEnabled()) toggleLock();
                    if (setupDlg != null) { setupDlg.dismiss(); showSetup(); }
                }));
        boolean notif = IslandController.hasNotificationAccess(this);
        if (!notif && Build.VERSION.SDK_INT >= 33) {
            col.addView(setupRow(false, "Krok 1: Ustawienia z ograniczeniami",
                    "Android 13+ blokuje to dla aplikacji spoza Sklepu Play. Dotknij → menu ⋮ w prawym górnym rogu → „Zezwól na ustawienia z ograniczeniami”",
                    () -> safeStart(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                            Uri.parse("package:" + getPackageName())))));
        }
        col.addView(setupRow(notif, notif || Build.VERSION.SDK_INT < 33 ? "Powiadomienia i muzyka" : "Krok 2: Powiadomienia i muzyka",
                "Pokazuje powiadomienia na wyspie i blokadzie – włącz „Fala”",
                () -> safeStart(new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))));
        col.addView(setupRow(batteryUnrestricted(), "Bez ograniczeń baterii",
                "Żeby system nie wyłączał wyspy i blokady", () -> {
                    try {
                        Intent i = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                                Uri.parse("package:" + getPackageName()));
                        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                        startActivity(i);
                    } catch (Exception e) {
                        safeStart(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS));
                    }
                }));
        col.addView(setupRow(checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED,
                "Mikrofon dla asystenta", "Dotknij, aby zezwolić",
                () -> requestPermissions(new String[]{android.Manifest.permission.RECORD_AUDIO}, 5)));
        if (isXiaomi()) {
            col.addView(setupRow(false, "Xiaomi / Redmi / POCO",
                    "Włącz: „Wyświetlaj wyskakujące okna w tle”, „Pokaż na ekranie blokady” i „Autostart”",
                    this::openXiaomiPermissions));
        } else {
            col.addView(setupRow(true, "Ustawienia aplikacji Fala",
                    "", () -> safeStart(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                            Uri.parse("package:" + getPackageName())))));
        }
        TextView note = new TextView(this);
        note.setText("Po zmianach wróć tutaj – lista sama się odświeży. Ekran blokady pojawi się po wyłączeniu i włączeniu ekranu.");
        note.setTextColor(0x99FFFFFF);
        note.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        note.setPadding(dp(4), dp(12), dp(4), 0);
        col.addView(note);

        box.addView(sv, new LinearLayout.LayoutParams(-1, Math.round(getResources().getDisplayMetrics().heightPixels * 0.6f)));
        AlertDialog dlg = new AlertDialog.Builder(this).setView(box).create();
        LinearLayout btns = new LinearLayout(this);
        btns.setGravity(Gravity.END);
        btns.setPadding(0, dp(10), 0, 0);
        TextView done = dialogButton("Zamknij");
        done.setOnClickListener(v -> dlg.dismiss());
        btns.addView(done);
        box.addView(btns);
        setupDlg = dlg;
        showGlassDialog(dlg);
    }

    private boolean lockEnabled() {
        return prefs.getBoolean("lock", false);
    }

    private void toggleLock() {
        boolean on = !lockEnabled();
        prefs.edit().putBoolean("lock", on).apply();
        if (on && !Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "Zezwól Fali na wyświetlanie nad innymi aplikacjami – wtedy ekran blokady pojawi się zawsze",
                    Toast.LENGTH_LONG).show();
            safeStart(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:" + getPackageName())));
        } else {
            Toast.makeText(this, on ? "Ekran blokady włączony – wyłącz i włącz ekran" : "Ekran blokady wyłączony",
                    Toast.LENGTH_SHORT).show();
        }
        if (on) updateGlobalIsland();
    }

    private void showApiKeyDialog() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(22), dp(22), dp(22), dp(16));
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(28));
        bg.setColor(0xF01C1C22);
        bg.setStroke(dp(1), 0x33FFFFFF);
        box.setBackground(bg);

        TextView t = new TextView(this);
        t.setText("Asystent Fala");
        t.setTextColor(Color.WHITE);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20);
        t.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        box.addView(t);
        TextView d = new TextView(this);
        d.setText("Fala odpowiada na pytania dzięki Claude. Wklej klucz API z console.anthropic.com (zaczyna się od sk-ant-). Rozmowy są płatne według cennika Anthropic. Bez klucza działają komendy: otwórz aplikację, latarka, budzik, minutnik, godzina, bateria.");
        d.setTextColor(0xB3FFFFFF);
        d.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        d.setLineSpacing(dp(2), 1f);
        d.setPadding(0, dp(8), 0, dp(14));
        box.addView(d);
        EditText key = new EditText(this);
        key.setHint("sk-ant-…");
        key.setHintTextColor(0x66FFFFFF);
        key.setTextColor(Color.WHITE);
        key.setSingleLine(true);
        key.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        key.setText(prefs.getString("api_key", ""));
        box.addView(key, new LinearLayout.LayoutParams(-1, -2));

        LinearLayout btns = new LinearLayout(this);
        btns.setGravity(Gravity.END);
        btns.setPadding(0, dp(12), 0, 0);
        AlertDialog dlg = new AlertDialog.Builder(this).setView(box).create();
        TextView voice = dialogButton(prefs.getBoolean("assistant_voice", true) ? "Głos: wł." : "Głos: wył.");
        voice.setOnClickListener(v -> {
            boolean on = !prefs.getBoolean("assistant_voice", true);
            prefs.edit().putBoolean("assistant_voice", on).apply();
            voice.setText(on ? "Głos: wł." : "Głos: wył.");
        });
        btns.addView(voice);
        TextView save = dialogButton("Zapisz");
        save.setOnClickListener(v -> {
            prefs.edit().putString("api_key", key.getText().toString().trim()).apply();
            dlg.dismiss();
            Toast.makeText(this, "Zapisano. Stuknij dwa razy w pusty ekran, aby zawołać Falę.", Toast.LENGTH_LONG).show();
        });
        btns.addView(save);
        box.addView(btns);
        dlg.show();
        if (dlg.getWindow() != null) dlg.getWindow().setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
    }

    private TextView dialogButton(String text) {
        TextView b = new TextView(this);
        b.setText(text);
        b.setTextColor(0xFF64B5FF);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        b.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        b.setPadding(dp(16), dp(10), dp(16), dp(10));
        b.setBackground(ripple());
        return b;
    }

    // ---------- dostosowywanie ikon ----------

    private void reloadIcons() {
        iconCache.clear();
        loadApps();
    }

    private void renameApp(App a) {
        LinearLayout box = dialogBox("Zmień nazwę");
        EditText name = new EditText(this);
        name.setText(a.label);
        name.setTextColor(Color.WHITE);
        name.setSingleLine(true);
        name.setSelectAllOnFocus(true);
        box.addView(name, new LinearLayout.LayoutParams(-1, -2));
        AlertDialog dlg = new AlertDialog.Builder(this).setView(box).create();
        LinearLayout btns = new LinearLayout(this);
        btns.setGravity(Gravity.END);
        btns.setPadding(0, dp(12), 0, 0);
        TextView save = dialogButton("Zapisz");
        save.setOnClickListener(v -> {
            String n = name.getText().toString().trim();
            if (n.isEmpty()) prefs.edit().remove("label_" + a.key).apply();
            else prefs.edit().putString("label_" + a.key, n).apply();
            dlg.dismiss();
            loadApps();
        });
        btns.addView(save);
        box.addView(btns);
        showGlassDialog(dlg);
        name.requestFocus();
    }

    private void pickIcon(App a) {
        pendingIconKey = a.key;
        Intent i = new Intent(Intent.ACTION_GET_CONTENT);
        i.setType("image/*");
        i.addCategory(Intent.CATEGORY_OPENABLE);
        try {
            startActivityForResult(Intent.createChooser(i, "Wybierz obrazek ikony"), REQ_PICK_ICON);
        } catch (Exception e) {
            Toast.makeText(this, "Brak galerii", Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQ_PICK_ICON || resultCode != RESULT_OK || data == null || data.getData() == null
                || pendingIconKey == null) return;
        final Uri uri = data.getData();
        final String key = pendingIconKey;
        pendingIconKey = null;
        exec.execute(() -> {
            try {
                android.graphics.BitmapFactory.Options o = new android.graphics.BitmapFactory.Options();
                o.inJustDecodeBounds = true;
                try (java.io.InputStream is = getContentResolver().openInputStream(uri)) {
                    android.graphics.BitmapFactory.decodeStream(is, null, o);
                }
                int sample = 1;
                while (Math.min(o.outWidth, o.outHeight) / (sample * 2) >= 256) sample *= 2;
                o = new android.graphics.BitmapFactory.Options();
                o.inSampleSize = sample;
                Bitmap bm;
                try (java.io.InputStream is = getContentResolver().openInputStream(uri)) {
                    bm = android.graphics.BitmapFactory.decodeStream(is, null, o);
                }
                if (bm == null) throw new Exception("decode");
                icons.saveCustom(key, bm);
                iconCache.remove(key);
                main.post(() -> {
                    Toast.makeText(this, "Ikona zmieniona", Toast.LENGTH_SHORT).show();
                    loadApps();
                });
            } catch (Exception e) {
                main.post(() -> Toast.makeText(this, "Nie udało się wczytać obrazka", Toast.LENGTH_SHORT).show());
            }
        });
    }

    private LinearLayout dialogBox(String title) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(22), dp(22), dp(22), dp(16));
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(28));
        bg.setColor(0xF01C1C22);
        bg.setStroke(dp(1), 0x33FFFFFF);
        box.setBackground(bg);
        TextView t = new TextView(this);
        t.setText(title);
        t.setTextColor(Color.WHITE);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20);
        t.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        t.setPadding(0, 0, 0, dp(12));
        box.addView(t);
        return box;
    }

    private void showGlassDialog(AlertDialog dlg) {
        dlg.show();
        if (dlg.getWindow() != null) dlg.getWindow().setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
    }

    private TextView sectionLabel(String text) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextColor(0x99FFFFFF);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        t.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        t.setLetterSpacing(0.1f);
        t.setPadding(dp(2), dp(14), 0, dp(8));
        return t;
    }

    /** Rząd przycisków-wyboru; zaznaczony jest podświetlony. */
    private LinearLayout chips(String[] options, int selected, java.util.function.IntConsumer onPick) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        List<TextView> all = new ArrayList<>();
        for (int i = 0; i < options.length; i++) {
            final int idx = i;
            TextView c = new TextView(this);
            c.setText(options[i]);
            c.setGravity(Gravity.CENTER);
            c.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
            c.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
            c.setPadding(dp(6), dp(10), dp(6), dp(10));
            c.setSingleLine(true);
            all.add(c);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -2, 1f);
            if (i > 0) lp.leftMargin = dp(6);
            row.addView(c, lp);
            c.setOnClickListener(v -> {
                for (int k = 0; k < all.size(); k++) styleChip(all.get(k), k == idx);
                onPick.accept(idx);
            });
        }
        for (int k = 0; k < all.size(); k++) styleChip(all.get(k), k == selected);
        return row;
    }

    private void styleChip(TextView c, boolean on) {
        GradientDrawable g = new GradientDrawable();
        g.setCornerRadius(dp(14));
        g.setColor(on ? 0xFFFFFFFF : 0x1FFFFFFF);
        g.setStroke(dp(1), on ? 0xFFFFFFFF : 0x26FFFFFF);
        c.setBackground(g);
        c.setTextColor(on ? 0xFF111111 : 0xE6FFFFFF);
    }

    private void showIconSettings() {
        LinearLayout box = dialogBox("Wygląd ikon");
        android.widget.ScrollView sv = new android.widget.ScrollView(this);
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        sv.addView(col);

        // podgląd
        LinearLayout preview = new LinearLayout(this);
        preview.setGravity(Gravity.CENTER);
        preview.setPadding(0, dp(4), 0, dp(4));
        col.addView(preview);
        Runnable refreshPreview = () -> {
            preview.removeAllViews();
            int n = 0;
            for (String k : getList("dock")) {
                App a = byKey.get(k);
                if (a == null || n >= 4) continue;
                n++;
                ImageView iv = new ImageView(this);
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(icons.homeDp() - 6), dp(icons.homeDp() - 6));
                lp.leftMargin = dp(6);
                lp.rightMargin = dp(6);
                preview.addView(iv, lp);
                final ImageView target = iv;
                exec.execute(() -> {
                    try {
                        for (LauncherActivityInfo li : launcherApps.getActivityList(a.component.getPackageName(), a.user)) {
                            if (li.getComponentName().equals(a.component)) {
                                Bitmap bm = icons.make(li, a.user, a.key, dp(64));
                                main.post(() -> target.setImageBitmap(bm));
                                break;
                            }
                        }
                    } catch (Exception ignored) {
                    }
                });
            }
        };
        refreshPreview.run();

        col.addView(sectionLabel("KSZTAŁT"));
        col.addView(chips(IconFactory.SHAPES, icons.shape(), i -> {
            prefs.edit().putInt("icon_shape", i).apply();
            refreshPreview.run();
        }));
        col.addView(sectionLabel("STYL"));
        col.addView(chips(IconFactory.STYLES, icons.style(), i -> {
            prefs.edit().putInt("icon_style", i).apply();
            refreshPreview.run();
        }));
        col.addView(sectionLabel("TON (STYL „TAPETA”)"));
        col.addView(chips(IconFactory.TONES, icons.tone(), i -> {
            prefs.edit().putInt("icon_tone", i).apply();
            if (icons.style() != 3) prefs.edit().putInt("icon_style", 3).apply();
            refreshPreview.run();
        }));
        col.addView(sectionLabel("ROZMIAR"));
        col.addView(chips(IconFactory.SIZES, icons.size(), i -> {
            prefs.edit().putInt("icon_size", i).apply();
            refreshPreview.run();
        }));
        col.addView(sectionLabel("PODPISY POD IKONAMI"));
        col.addView(chips(new String[]{"Pokaż", "Ukryj"}, icons.labels() ? 0 : 1, i ->
                prefs.edit().putBoolean("icon_labels", i == 0).apply()));

        col.addView(sectionLabel("PACZKA IKON"));
        List<String[]> packs = IconFactory.packChoices(this);
        String cur = prefs.getString("icon_pack", "");
        int sel = 0;
        String[] names = new String[packs.size()];
        for (int i = 0; i < packs.size(); i++) {
            names[i] = packs.get(i)[1];
            if (packs.get(i)[0].equals(cur)) sel = i;
        }
        LinearLayout packList = new LinearLayout(this);
        packList.setOrientation(LinearLayout.VERTICAL);
        List<TextView> packViews = new ArrayList<>();
        for (int i = 0; i < packs.size(); i++) {
            final int idx = i;
            TextView t = new TextView(this);
            t.setText(names[i]);
            t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
            t.setPadding(dp(14), dp(11), dp(14), dp(11));
            packViews.add(t);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
            lp.bottomMargin = dp(6);
            packList.addView(t, lp);
            t.setOnClickListener(v -> {
                for (int k = 0; k < packViews.size(); k++) styleChip(packViews.get(k), k == idx);
                prefs.edit().putString("icon_pack", packs.get(idx)[0]).apply();
                refreshPreview.run();
            });
        }
        for (int k = 0; k < packViews.size(); k++) styleChip(packViews.get(k), k == sel);
        col.addView(packList);
        if (packs.size() == 1) {
            TextView hint = new TextView(this);
            hint.setText("Zainstaluj paczkę ikon ze Sklepu Play (np. zgodną z Nova Launcher), a pojawi się tutaj.");
            hint.setTextColor(0x99FFFFFF);
            hint.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
            col.addView(hint);
        }
        TextView tip = new TextView(this);
        tip.setText("Pojedynczą ikonę lub nazwę zmienisz, przytrzymując aplikację.");
        tip.setTextColor(0x99FFFFFF);
        tip.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        tip.setPadding(0, dp(12), 0, 0);
        col.addView(tip);

        box.addView(sv, new LinearLayout.LayoutParams(-1, Math.round(getResources().getDisplayMetrics().heightPixels * 0.58f)));
        AlertDialog dlg = new AlertDialog.Builder(this).setView(box).create();
        LinearLayout btns = new LinearLayout(this);
        btns.setGravity(Gravity.END);
        btns.setPadding(0, dp(10), 0, 0);
        TextView done = dialogButton("Gotowe");
        done.setOnClickListener(v -> dlg.dismiss());
        btns.addView(done);
        box.addView(btns);
        dlg.setOnDismissListener(d -> reloadIcons());
        showGlassDialog(dlg);
    }

    private void showSettings() {
        List<String> labels = new ArrayList<>();
        List<Runnable> actions = new ArrayList<>();
        labels.add("⚙  Konfiguracja i uprawnienia");
        actions.add(this::showSetup);
        labels.add("🎮  Tryb Ultra Gry");
        actions.add(this::showGameSettings);
        labels.add("✦  Zapytaj Falę (asystent)");
        actions.add(this::openAssistant);
        labels.add("✦  Asystent: klucz API i głos");
        actions.add(this::showApiKeyDialog);
        labels.add(lockEnabled() ? "🔒  Ekran blokady iPhone: włączony" : "🔒  Ekran blokady iPhone: wyłączony");
        actions.add(this::toggleLock);
        labels.add(globalIslandPref() ? "⬬  Wyspa w każdej aplikacji: włączona" : "⬬  Wyspa w każdej aplikacji: wyłączona");
        actions.add(this::toggleGlobalIsland);
        labels.add("🎨  Wygląd ikon");
        actions.add(this::showIconSettings);
        labels.add("Zmień tapetę");
        actions.add(() -> safeStart(Intent.createChooser(new Intent(Intent.ACTION_SET_WALLPAPER), "Tapeta")));
        labels.add(IslandController.hasNotificationAccess(this) ? "Powiadomienia i muzyka na wyspie: włączone" : "Włącz powiadomienia i muzykę na wyspie");
        actions.add(() -> safeStart(new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)));
        labels.add("Ustaw Falę jako ekran główny");
        actions.add(() -> safeStart(new Intent(Settings.ACTION_HOME_SETTINGS)));
        labels.add("Ustawienia telefonu");
        actions.add(() -> safeStart(new Intent(Settings.ACTION_SETTINGS)));
        showMenu("Fala", labels, actions);
    }

    private void showMenu(String title, List<String> labels, List<Runnable> actions) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(8), dp(20), dp(8), dp(12));
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(28));
        bg.setColor(0xF01C1C22);
        bg.setStroke(dp(1), 0x33FFFFFF);
        box.setBackground(bg);

        TextView t = new TextView(this);
        t.setText(title);
        t.setTextColor(Color.WHITE);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20);
        t.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        t.setPadding(dp(20), 0, dp(20), dp(12));
        box.addView(t);

        AlertDialog dlg = new AlertDialog.Builder(this).setView(box).create();
        for (int i = 0; i < labels.size(); i++) {
            final Runnable r = actions.get(i);
            TextView row = new TextView(this);
            row.setText(labels.get(i));
            row.setTextColor(0xE6FFFFFF);
            row.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
            row.setPadding(dp(20), dp(14), dp(20), dp(14));
            row.setBackground(ripple());
            row.setOnClickListener(v -> {
                dlg.dismiss();
                r.run();
            });
            box.addView(row);
        }
        dlg.show();
        if (dlg.getWindow() != null) {
            dlg.getWindow().setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        }
    }

    private Drawable ripple() {
        GradientDrawable mask = new GradientDrawable();
        mask.setCornerRadius(dp(14));
        mask.setColor(Color.WHITE);
        return new RippleDrawable(ColorStateList.valueOf(0x26FFFFFF), null, mask);
    }

    // ---------- bateria ----------

    private void registerBattery() {
        batteryReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context ctx, Intent i) {
                String act = i.getAction();
                if (Intent.ACTION_BATTERY_CHANGED.equals(act)) {
                    int lvl = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
                    int scale = i.getIntExtra(BatteryManager.EXTRA_SCALE, 100);
                    int status = i.getIntExtra(BatteryManager.EXTRA_STATUS, -1);
                    batteryLevel = (lvl >= 0 && scale > 0) ? lvl * 100 / scale : -1;
                    charging = status == BatteryManager.BATTERY_STATUS_CHARGING
                            || status == BatteryManager.BATTERY_STATUS_FULL;
                    widgetRing.setBattery(batteryLevel, charging);
                    widgetBattery.setText(charging ? "Ładowanie" : "Bateria");
                }
            }
        };
        IntentFilter f = new IntentFilter();
        f.addAction(Intent.ACTION_BATTERY_CHANGED);
        f.addAction(Intent.ACTION_POWER_CONNECTED);
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(batteryReceiver, f, Context.RECEIVER_EXPORTED);
        } else {
            registerReceiver(batteryReceiver, f);
        }
    }

    // ---------- gesty ----------

    /** Ekran główny: przesunięcie w górę = szuflada, w dół = powiadomienia. */
    private class HomeFrame extends FrameLayout {
        private final int slop;
        private float downX, downY;
        private boolean dragging = false;
        private int mode = 0; // 1 = szuflada, 2 = powiadomienia
        private VelocityTracker vt;
        private final GestureDetector gd;

        HomeFrame(Context c) {
            super(c);
            slop = ViewConfiguration.get(c).getScaledTouchSlop();
            gd = new GestureDetector(c, new GestureDetector.SimpleOnGestureListener() {
                @Override
                public boolean onDoubleTap(MotionEvent e) {
                    if (!dragging) {
                        performHapticFeedback(android.view.HapticFeedbackConstants.CONTEXT_CLICK);
                        openAssistant();
                    }
                    return true;
                }

                @Override
                public void onLongPress(MotionEvent e) {
                    if (!dragging) {
                        performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS);
                        showSettings();
                    }
                }
            });
        }

        private void track(MotionEvent e) {
            if (vt == null) vt = VelocityTracker.obtain();
            vt.addMovement(e);
        }

        private boolean checkStart(MotionEvent e) {
            float dx = e.getX() - downX;
            float dy = e.getY() - downY;
            if (Math.abs(dy) > slop * 1.5f && Math.abs(dy) > Math.abs(dx) * 1.3f) {
                dragging = true;
                mode = dy < 0 ? 1 : 2;
                if (mode == 1) {
                    if (drawerAnim != null) drawerAnim.cancel();
                    hideKeyboard();
                    setLayers(true);
                    thresholdBuzzed = false;
                    rebuildSuggestions();
                }
                return true;
            }
            return false;
        }

        @Override
        public boolean onInterceptTouchEvent(MotionEvent e) {
            track(e);
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    downX = e.getX();
                    downY = e.getY();
                    dragging = false;
                    mode = 0;
                    return false;
                case MotionEvent.ACTION_MOVE:
                    return !dragging && checkStart(e);
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
                    downX = e.getX();
                    downY = e.getY();
                    dragging = false;
                    mode = 0;
                    return true;
                case MotionEvent.ACTION_MOVE:
                    if (!dragging) checkStart(e);
                    if (dragging && mode == 1) {
                        float dy = e.getY() - downY;
                        setDrawerProgress(-dy / (screenH() * 0.5f));
                        dragFeedback(drawerP > 0.35f);
                    }
                    return true;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    if (dragging) {
                        float vy = 0;
                        if (vt != null) {
                            vt.computeCurrentVelocity(1000);
                            vy = vt.getYVelocity();
                        }
                        if (mode == 1) {
                            animateDrawer(vy < -700 || drawerP > 0.35f);
                        } else if (mode == 2 && e.getY() - downY > dp(50)) {
                            expandNotifications();
                        }
                    }
                    dragging = false;
                    if (vt != null) {
                        vt.recycle();
                        vt = null;
                    }
                    return true;
            }
            return true;
        }
    }

    /** Szuflada: przeciągnięcie w dół, gdy lista jest na górze = zamknięcie. */
    private class DrawerFrame extends FrameLayout {
        private final int slop;
        private float downX, downY;
        private boolean dragging = false;
        private VelocityTracker vt;

        DrawerFrame(Context c) {
            super(c);
            slop = ViewConfiguration.get(c).getScaledTouchSlop();
        }

        private void track(MotionEvent e) {
            if (vt == null) vt = VelocityTracker.obtain();
            vt.addMovement(e);
        }

        @Override
        public boolean onInterceptTouchEvent(MotionEvent e) {
            track(e);
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    downX = e.getX();
                    downY = e.getY();
                    dragging = false;
                    return false;
                case MotionEvent.ACTION_MOVE: {
                    float dx = e.getX() - downX;
                    float dy = e.getY() - downY;
                    if (!dragging && dy > slop * 1.5f && dy > Math.abs(dx) * 1.3f && listAtTop()) {
                        dragging = true;
                        if (drawerAnim != null) drawerAnim.cancel();
                        hideKeyboard();
                        setLayers(true);
                        thresholdBuzzed = false;
                        return true;
                    }
                    return false;
                }
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    if (vt != null) {
                        vt.recycle();
                        vt = null;
                    }
                    return false;
                default:
                    return false;
            }
        }

        @Override
        public boolean onTouchEvent(MotionEvent e) {
            track(e);
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    downX = e.getX();
                    downY = e.getY();
                    return true;
                case MotionEvent.ACTION_MOVE:
                    if (dragging) {
                        float dy = e.getY() - downY;
                        setDrawerProgress(1f - dy / (screenH() * 0.5f));
                        dragFeedback(drawerP < 0.65f);
                    }
                    return true;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    if (dragging) {
                        float vy = 0;
                        if (vt != null) {
                            vt.computeCurrentVelocity(1000);
                            vy = vt.getYVelocity();
                        }
                        animateDrawer(!(vy > 700 || drawerP < 0.65f));
                    }
                    dragging = false;
                    if (vt != null) {
                        vt.recycle();
                        vt = null;
                    }
                    return true;
            }
            return true;
        }
    }

    // ---------- adapter listy ----------

    private class Adapter extends BaseAdapter {
        @Override public int getCount() { return items.size(); }
        @Override public Object getItem(int p) { return items.get(p); }
        @Override public long getItemId(int p) { return p; }
        @Override public int getViewTypeCount() { return 4; }
        @Override public int getItemViewType(int p) { return items.get(p).type; }
        @Override public boolean areAllItemsEnabled() { return false; }

        @Override
        public boolean isEnabled(int p) {
            return items.get(p).type == T_APP;
        }

        @Override
        public View getView(int pos, View cv, ViewGroup parent) {
            Item it = items.get(pos);
            switch (it.type) {
                case T_APP: return appRow(cv, it.app);
                case T_SECTION: return textRow(cv, it.text, 13, 0x99FFFFFF, "sans-serif-medium", dp(18), dp(6));
                case T_LABEL: return textRow(cv, it.text, 11, 0x80FFFFFF, "sans-serif-medium", dp(6), dp(4));
                default: return textRow(cv, it.text, 14, 0x99FFFFFF, "sans-serif", dp(14), dp(14));
            }
        }
    }

    private View appRow(View cv, App a) {
        LinearLayout row;
        Holder h;
        if (cv instanceof LinearLayout && cv.getTag() instanceof Holder) {
            row = (LinearLayout) cv;
            h = (Holder) cv.getTag();
        } else {
            row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setBackground(ripple());
            row.setPadding(dp(4), dp(7), dp(4), dp(7));
            h = new Holder();
            h.icon = new ImageView(this);
            LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(dp(42), dp(42));
            ilp.rightMargin = dp(16);
            row.addView(h.icon, ilp);
            h.label = new TextView(this);
            h.label.setTextColor(Color.WHITE);
            h.label.setSingleLine(true);
            h.label.setEllipsize(TextUtils.TruncateAt.END);
            h.label.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
            h.label.setTypeface(Typeface.create("sans-serif", Typeface.NORMAL));
            row.addView(h.label, new LinearLayout.LayoutParams(0, -2, 1f));
            row.setTag(h);
        }
        h.label.setText(a.label);
        if (a.icon != null) h.icon.setImageBitmap(a.icon);
        else h.icon.setImageDrawable(null);
        return row;
    }

    private View textRow(View cv, String text, int sp, int color, String font, int padTop, int padBottom) {
        TextView tv = cv instanceof TextView ? (TextView) cv : new TextView(this);
        tv.setText(text);
        tv.setTextColor(color);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        tv.setTypeface(Typeface.create(font, Typeface.NORMAL));
        tv.setLetterSpacing(sp <= 13 ? 0.12f : 0f);
        tv.setPadding(dp(4), padTop, dp(4), padBottom);
        return tv;
    }
}
