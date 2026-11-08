package pl.fala.launcher;

import android.app.ActivityManager;
import android.app.AppOpsManager;
import android.app.usage.UsageEvents;
import android.app.usage.UsageStatsManager;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.hardware.display.DisplayManager;
import android.net.Uri;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.os.Process;
import android.os.SystemClock;
import android.provider.Settings;
import android.service.notification.NotificationListenerService;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Display;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.view.animation.DecelerateInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * Tryb Ultra Gry (w stylu vivo): wykrywa uruchomioną grę, pokazuje boczny uchwyt
 * z panelem (temperatura, ping, RAM, odświeżanie, przyspieszenie, Nie przeszkadzać,
 * chłodzenie) oraz mały licznik nad grą.
 */
public class GameMode {

    private final IslandService svc;
    private final Context ctx;
    private final WindowManager wm;
    private final SharedPreferences prefs;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ScheduledExecutorService bg = Executors.newSingleThreadScheduledExecutor();
    private final Map<String, Boolean> gameCache = new HashMap<>();

    private boolean running = false;
    private String currentGame = null;
    private String lastClass = null;
    private View handle, hud, panel;
    private TextView hudText;
    private ScheduledFuture<?> pingTask;
    private volatile int pingMs = -1;
    private boolean dndByUs = false, coolByUs = false;

    // kafelki panelu
    private TextView tTemp, tTempSub, tPing, tRam, tHz;

    private final Runnable poll = new Runnable() {
        @Override
        public void run() {
            if (!running) return;
            try {
                bg.execute(() -> {
                    try {
                        check();
                    } catch (Exception ignored) {
                    }
                });
            } catch (Exception ignored) {
            }
            main.postDelayed(this, 1500);
        }
    };

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            if (currentGame == null) return;
            updateHud();
            updatePanel();
            main.postDelayed(this, 1000);
        }
    };

    GameMode(IslandService svc, WindowManager wm) {
        this.svc = svc;
        this.ctx = svc;
        this.wm = wm;
        this.prefs = svc.getSharedPreferences("fala", Context.MODE_PRIVATE);
    }

    private int dp(float v) {
        return Math.round(v * ctx.getResources().getDisplayMetrics().density);
    }

    // ---------- ustawienia ----------

    static boolean enabled(Context c) {
        return c.getSharedPreferences("fala", Context.MODE_PRIVATE).getBoolean("game_mode", true);
    }

    static boolean hasUsageAccess(Context c) {
        try {
            AppOpsManager ops = (AppOpsManager) c.getSystemService(Context.APP_OPS_SERVICE);
            int mode = Build.VERSION.SDK_INT >= 29
                    ? ops.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), c.getPackageName())
                    : ops.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), c.getPackageName());
            return mode == AppOpsManager.MODE_ALLOWED;
        } catch (Exception e) {
            return false;
        }
    }

    static Set<String> set(Context c, String name) {
        return new HashSet<>(c.getSharedPreferences("fala", Context.MODE_PRIVATE).getStringSet(name, new HashSet<>()));
    }

    /** Wydawcy i słowa w nazwach pakietów typowe dla gier. */
    private static final String[] GAME_WORDS = {
            "game", "supercell", "com.ea.", "playrix", "com.king.", "rovio", "miniclip", "roblox", "mojang",
            "epicgames", "tencent.ig", "pubg", "garena", "dts.freefire", "mihoyo", "hoyoverse", "activision",
            "netease", "zynga", "gameloft", "nekki", "voodoo", "ketchapp", "outfit7", "halfbrick", "imangi",
            "kiloo", "sybo", "innersloth", "riotgames", "netmarble", "moonactive", "scopely", "playtika",
            "nianticlabs", "ubisoft", "square_enix", "squareenix", "bandainamco", "konami", "sega.", "nintendo",
            "habby", "lilithgames", "funplus", "igg.", "kabam", "jagex", "chess", "puzzle", "solitaire",
            "sudoku", "minecraft", "fortnite", "clashofclans", "candycrush", "subwaysurf", "pvz"};

    /** Biblioteki silników gier (Unity, Unreal, Cocos, Godot, Supercell...). */
    private static final String[] ENGINE_LIBS = {
            "libunity.so", "libil2cpp.so", "libUE4.so", "libUnreal.so", "libcocos2dcpp.so", "libcocos2djs.so",
            "libcocos.so", "libgodot_android.so", "libgdx.so", "libg.so", "libmono.so", "libmonosgen-2.0.so",
            "libgame.so", "libfmod.so", "libGameCore.so", "libyoyo.so", "libdefold.so", "libsdl2.so", "libSDL2.so"};

    /** Czy pakiet to gra: wybór użytkownika > kategoria systemowa > nazwa > silnik gry w APK. */
    static boolean isGame(Context c, String pkg) {
        if (pkg == null || pkg.equals(c.getPackageName())) return false;
        if (set(c, "games_add").contains(pkg)) return true;
        if (set(c, "games_remove").contains(pkg)) return false;
        SharedPreferences p = c.getSharedPreferences("fala", Context.MODE_PRIVATE);
        try {
            PackageManager pm = c.getPackageManager();
            ApplicationInfo ai = pm.getApplicationInfo(pkg, 0);
            if ((ai.flags & ApplicationInfo.FLAG_SYSTEM) != 0 && (ai.flags & ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) == 0) {
                return false;
            }
            if (ai.category == ApplicationInfo.CATEGORY_GAME) return true;
            //noinspection deprecation
            if ((ai.flags & ApplicationInfo.FLAG_IS_GAME) != 0) return true;
            String low = pkg.toLowerCase(Locale.ROOT);
            for (String w : GAME_WORDS) if (low.contains(w)) return true;
            // wynik skanowania APK zapamiętujemy (do aktualizacji aplikacji)
            long upd = pm.getPackageInfo(pkg, 0).lastUpdateTime;
            String key = "gd_" + pkg;
            String cached = p.getString(key, null);
            if (cached != null && cached.startsWith(upd + ":")) return cached.endsWith(":1");
            boolean found = hasEngine(ai);
            p.edit().putString(key, upd + ":" + (found ? "1" : "0")).apply();
            return found;
        } catch (Exception e) {
            return false;
        }
    }

    private static boolean hasEngine(ApplicationInfo ai) {
        java.util.List<String> apks = new java.util.ArrayList<>();
        if (ai.sourceDir != null) apks.add(ai.sourceDir);
        if (ai.splitSourceDirs != null) java.util.Collections.addAll(apks, ai.splitSourceDirs);
        // biblioteki rozpakowane na dysk
        try {
            if (ai.nativeLibraryDir != null) {
                String[] files = new java.io.File(ai.nativeLibraryDir).list();
                if (files != null) for (String f : files) for (String e : ENGINE_LIBS) if (f.equals(e)) return true;
            }
        } catch (Exception ignored) {
        }
        for (String apk : apks) {
            try (java.util.zip.ZipFile z = new java.util.zip.ZipFile(apk)) {
                java.util.Enumeration<? extends java.util.zip.ZipEntry> en = z.entries();
                while (en.hasMoreElements()) {
                    String n = en.nextElement().getName();
                    if (!n.startsWith("lib/")) {
                        if (n.startsWith("assets/bin/Data/") || n.startsWith("assets/aa/")) return true; // Unity
                        continue;
                    }
                    int slash = n.lastIndexOf('/');
                    String base = slash >= 0 ? n.substring(slash + 1) : n;
                    for (String e : ENGINE_LIBS) if (base.equals(e)) return true;
                }
            } catch (Exception ignored) {
            }
        }
        return false;
    }

    static void markGame(Context c, String pkg, boolean game) {
        SharedPreferences p = c.getSharedPreferences("fala", Context.MODE_PRIVATE);
        Set<String> add = set(c, "games_add");
        Set<String> rem = set(c, "games_remove");
        if (game) {
            add.add(pkg);
            rem.remove(pkg);
        } else {
            rem.add(pkg);
            add.remove(pkg);
        }
        p.edit().putStringSet("games_add", add).putStringSet("games_remove", rem).apply();
        IslandService s = IslandService.instance;
        if (s != null && s.game != null) {
            synchronized (s.game.gameCache) {
                s.game.gameCache.clear();
            }
        }
    }

    // ---------- cykl ----------

    void start() {
        if (running) return;
        running = true;
        // po restarcie aplikacji przywróć jasność, jeśli chłodzenie zostało włączone
        if (prefs.getBoolean("cool_active", false)) {
            coolByUs = true;
            setCooling(false);
        }
        main.post(poll);
    }

    void stop() {
        running = false;
        main.removeCallbacks(poll);
        exitGame();
        bg.shutdownNow();
    }

    /** Działa w tle; zmiany interfejsu przekazuje do wątku głównego. */
    private void check() {
        if (!enabled(ctx)) {
            main.post(this::exitGame);
            return;
        }
        if (!hasUsageAccess(ctx)) return; // bez dostępu działa tylko start gry z ekranu głównego Fali
        PowerManager pm = (PowerManager) ctx.getSystemService(Context.POWER_SERVICE);
        if (pm != null && !pm.isInteractive()) return;
        String fg = foreground();
        if (fg == null) return;
        if (fg.equals(ctx.getPackageName())) {
            // nasze okna (asystent, ustawienia) nie przerywają gry – ale ekran główny tak
            String cls = lastClass;
            if (cls != null && cls.endsWith(".MainActivity")) main.post(this::exitGame);
            return;
        }
        Boolean g;
        synchronized (gameCache) {
            g = gameCache.get(fg);
        }
        if (g == null) {
            g = isGame(ctx, fg);
            synchronized (gameCache) {
                gameCache.put(fg, g);
            }
        }
        final boolean game = g;
        final boolean transient_ = isTransient(fg);
        main.post(() -> {
            if (game) {
                notGameStreak = 0;
                if (!fg.equals(currentGame)) {
                    if (currentGame != null) exitGame();
                    enterGame(fg);
                }
            } else if (currentGame != null && !transient_) {
                // pojedynczy odczyt „nie gra” (reklama, okno logowania) nie wyłącza trybu od razu
                if (++notGameStreak >= 3) {
                    notGameStreak = 0;
                    exitGame();
                }
            }
        });
    }

    private int notGameStreak = 0;

    /** Okna systemu, klawiatury i usług Google, które na chwilę wchodzą na wierzch gry. */
    private static boolean isTransient(String pkg) {
        return pkg.equals("android") || pkg.startsWith("com.android.systemui")
                || pkg.contains("inputmethod") || pkg.contains("keyboard")
                || pkg.equals("com.google.android.gms") || pkg.equals("com.google.android.play.games")
                || pkg.equals("com.android.permissioncontroller") || pkg.equals("com.google.android.permissioncontroller")
                || pkg.startsWith("com.motorola.") && pkg.contains("game");
    }

    /** Gra uruchomiona z ekranu głównego Fali – działa nawet bez dostępu do użycia aplikacji. */
    void onLaunched(String pkg) {
        if (!enabled(ctx)) return;
        bg.execute(() -> {
            boolean g = isGame(ctx, pkg);
            synchronized (gameCache) {
                gameCache.put(pkg, g);
            }
            if (g) main.postDelayed(() -> {
                if (!pkg.equals(currentGame)) {
                    if (currentGame != null) exitGame();
                    enterGame(pkg);
                }
            }, 700);
        });
    }

    /** Ekran główny Fali jest na wierzchu – gra na pewno nie. */
    void onHomeVisible() {
        main.post(this::exitGame);
    }

    private String foreground() {
        UsageStatsManager usm = (UsageStatsManager) ctx.getSystemService(Context.USAGE_STATS_SERVICE);
        long now = System.currentTimeMillis();
        UsageEvents ev = usm.queryEvents(now - 60_000, now);
        UsageEvents.Event e = new UsageEvents.Event();
        String last = null, cls = null;
        while (ev.hasNextEvent()) {
            ev.getNextEvent(e);
            //noinspection deprecation
            if (e.getEventType() == UsageEvents.Event.MOVE_TO_FOREGROUND) {
                last = e.getPackageName();
                cls = e.getClassName();
            }
        }
        if (last != null) lastClass = cls;
        return last;
    }

    boolean inGame() {
        return currentGame != null;
    }

    private void enterGame(String pkg) {
        currentGame = pkg;
        svc.setInGame(true);
        showHandle();
        if (prefs.getBoolean("game_hud", true)) showHud();
        if (prefs.getBoolean("game_auto_dnd", false)) setDnd(true);
        if (prefs.getBoolean("game_auto_cool", false)) setCooling(true);
        pingTask = bg.scheduleWithFixedDelay(this::measurePing, 0, 2, TimeUnit.SECONDS);
        main.removeCallbacks(tick);
        main.post(tick);
        Toast.makeText(ctx, "🎮 Tryb Ultra Gry: " + label(pkg), Toast.LENGTH_SHORT).show();
    }

    private void exitGame() {
        if (currentGame == null) return;
        currentGame = null;
        main.removeCallbacks(tick);
        if (pingTask != null) pingTask.cancel(true);
        pingMs = -1;
        removeView(handle);
        removeView(hud);
        removeView(panel);
        handle = hud = panel = null;
        if (dndByUs) setDnd(false);
        if (coolByUs) setCooling(false);
        svc.setInGame(false);
    }

    private void removeView(View v) {
        if (v == null) return;
        try {
            wm.removeView(v);
        } catch (Exception ignored) {
        }
    }

    private WindowManager.LayoutParams params(int w, int h, boolean touchable) {
        int type = Build.VERSION.SDK_INT >= 26
                ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                : WindowManager.LayoutParams.TYPE_PHONE;
        int flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                | WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED;
        if (!touchable) flags |= WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(w, h, type, flags, PixelFormat.TRANSLUCENT);
        if (Build.VERSION.SDK_INT >= 28) {
            lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
        }
        return lp;
    }

    private String label(String pkg) {
        try {
            PackageManager pm = ctx.getPackageManager();
            return pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString();
        } catch (Exception e) {
            return pkg;
        }
    }

    // ---------- uchwyt boczny ----------

    private void showHandle() {
        FrameLayout box = new FrameLayout(ctx);
        View bar = new View(ctx);
        GradientDrawable g = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{0xCCFF2D95, 0xCC8E5CFF, 0xCC2D9CFF});
        g.setCornerRadius(dp(4));
        bar.setBackground(g);
        box.addView(bar, new FrameLayout.LayoutParams(dp(6), dp(70), Gravity.END | Gravity.CENTER_VERTICAL));
        box.setOnClickListener(v -> openPanel());
        box.setOnTouchListener(new View.OnTouchListener() {
            float downX;

            @Override
            public boolean onTouch(View v, MotionEvent e) {
                if (e.getActionMasked() == MotionEvent.ACTION_DOWN) downX = e.getRawX();
                if (e.getActionMasked() == MotionEvent.ACTION_MOVE && downX - e.getRawX() > dp(18)) {
                    openPanel();
                    return true;
                }
                return false;
            }
        });
        WindowManager.LayoutParams lp = params(dp(30), dp(100), true);
        lp.gravity = Gravity.END | Gravity.TOP;
        lp.y = Math.round(ctx.getResources().getDisplayMetrics().heightPixels * 0.28f);
        try {
            wm.addView(box, lp);
            handle = box;
            bar.setAlpha(0f);
            bar.setTranslationX(dp(10));
            bar.animate().alpha(1f).translationX(0).setDuration(400).start();
        } catch (Exception ignored) {
        }
    }

    // ---------- licznik nad grą ----------

    private void showHud() {
        if (hud != null) return;
        hudText = new TextView(ctx);
        hudText.setTextColor(Color.WHITE);
        hudText.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        hudText.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        hudText.setPadding(dp(10), dp(5), dp(10), dp(5));
        GradientDrawable g = new GradientDrawable();
        g.setCornerRadius(dp(12));
        g.setColor(0x99000000);
        g.setStroke(dp(1), 0x33FFFFFF);
        hudText.setBackground(g);
        hudText.setText("…");
        WindowManager.LayoutParams lp = params(WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT, false);
        lp.gravity = Gravity.TOP | Gravity.START;
        lp.x = dp(10);
        lp.y = dp(8);
        try {
            wm.addView(hudText, lp);
            hud = hudText;
        } catch (Exception ignored) {
        }
    }

    private void hideHud() {
        removeView(hud);
        hud = null;
        hudText = null;
    }

    private void updateHud() {
        if (hudText == null) return;
        hudText.setText(String.format(Locale.ROOT, "%.0f°C  ·  %s  ·  %d Hz  ·  RAM %.1f GB",
                batteryTemp(), pingMs < 0 ? "— ms" : pingMs + " ms", refreshHz(), freeRamGb()));
    }

    // ---------- panel ----------

    private void openPanel() {
        if (panel != null || currentGame == null) return;
        FrameLayout root = new FrameLayout(ctx);
        root.setBackgroundColor(0x66000000);
        root.setOnClickListener(v -> closePanel());

        int sw = ctx.getResources().getDisplayMetrics().widthPixels;
        int sh = ctx.getResources().getDisplayMetrics().heightPixels;
        ScrollView sv = new ScrollView(ctx);
        sv.setVerticalScrollBarEnabled(false);
        LinearLayout card = new LinearLayout(ctx);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setClickable(true);
        GradientDrawable bgd = new GradientDrawable();
        bgd.setCornerRadius(dp(28));
        bgd.setColor(0xEE111118);
        bgd.setStroke(dp(1), 0x33FFFFFF);
        card.setBackground(bgd);
        card.setPadding(dp(18), dp(18), dp(18), dp(16));
        sv.addView(card);
        FrameLayout.LayoutParams clp = new FrameLayout.LayoutParams(Math.min(dp(340), Math.round(sw * 0.92f)),
                -2, Gravity.END | Gravity.CENTER_VERTICAL);
        clp.rightMargin = dp(10);
        clp.topMargin = dp(10);
        clp.bottomMargin = dp(10);
        root.addView(sv, clp);

        // nagłówek
        LinearLayout head = new LinearLayout(ctx);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        ImageView icon = new ImageView(ctx);
        try {
            Drawable d = ctx.getPackageManager().getApplicationIcon(currentGame);
            icon.setImageDrawable(d);
        } catch (Exception ignored) {
        }
        head.addView(icon, new LinearLayout.LayoutParams(dp(40), dp(40)));
        LinearLayout ht = new LinearLayout(ctx);
        ht.setOrientation(LinearLayout.VERTICAL);
        ht.setPadding(dp(12), 0, 0, 0);
        TextView mode = text("TRYB ULTRA GRY", 11, 0xFFB98CFF, true);
        mode.setLetterSpacing(0.12f);
        ht.addView(mode);
        TextView name = text(label(currentGame), 18, Color.WHITE, true);
        name.setSingleLine(true);
        name.setEllipsize(TextUtils.TruncateAt.END);
        ht.addView(name);
        head.addView(ht, new LinearLayout.LayoutParams(0, -2, 1f));
        TextView x = text("✕", 20, 0xB3FFFFFF, false);
        x.setGravity(Gravity.CENTER);
        x.setOnClickListener(v -> closePanel());
        head.addView(x, new LinearLayout.LayoutParams(dp(44), dp(44)));
        card.addView(head);

        // kafelki
        LinearLayout r1 = new LinearLayout(ctx);
        r1.setPadding(0, dp(14), 0, 0);
        LinearLayout r2 = new LinearLayout(ctx);
        r2.setPadding(0, dp(8), 0, 0);
        View[] t1 = tile("Temperatura");
        tTemp = (TextView) t1[1];
        tTempSub = (TextView) t1[2];
        View[] t2 = tile("Ping");
        tPing = (TextView) t2[1];
        ((TextView) t2[2]).setText("do 1.1.1.1");
        View[] t3 = tile("Wolny RAM");
        tRam = (TextView) t3[1];
        View[] t4 = tile("Odświeżanie");
        tHz = (TextView) t4[1];
        ((TextView) t4[2]).setText("ekranu");
        r1.addView(t1[0], tileLp(false));
        r1.addView(t2[0], tileLp(true));
        r2.addView(t3[0], tileLp(false));
        r2.addView(t4[0], tileLp(true));
        card.addView(r1);
        card.addView(r2);

        // przyspieszenie
        TextView boost = text("🚀  Przyspiesz grę", 16, 0xFF111111, true);
        boost.setGravity(Gravity.CENTER);
        boost.setPadding(0, dp(14), 0, dp(14));
        GradientDrawable bb = new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,
                new int[]{0xFFFFFFFF, 0xFFE3D4FF});
        bb.setCornerRadius(dp(18));
        boost.setBackground(bb);
        boost.setOnClickListener(v -> boost(boost));
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(-1, -2);
        blp.topMargin = dp(14);
        card.addView(boost, blp);

        // przełączniki
        card.addView(toggleRow("❄  Chłodzenie", "Przyciemnia ekran i zamyka aplikacje w tle",
                () -> coolByUs, on -> setCooling(on)));
        card.addView(toggleRow("🔕  Nie przeszkadzać", "Wycisza powiadomienia i połączenia (oprócz ważnych)",
                () -> dndByUs, on -> setDnd(on)));
        card.addView(toggleRow("📊  Licznik na ekranie", "Temperatura, ping, Hz i RAM nad grą",
                () -> hud != null, on -> {
                    prefs.edit().putBoolean("game_hud", on).apply();
                    if (on) showHud();
                    else hideHud();
                    return true;
                }));
        card.addView(toggleRow("🏝  Blokuj banery wyspy", "Powiadomienia nie zasłaniają gry",
                () -> prefs.getBoolean("game_block_banners", true), on -> {
                    prefs.edit().putBoolean("game_block_banners", on).apply();
                    return true;
                }));

        TextView note = text("FPS samej gry nie da się odczytać bez roota – licznik pokazuje odświeżanie ekranu (Hz). "
                + "Chłodzenie zmniejsza nagrzewanie, ale nie steruje procesorem.", 12, 0x80FFFFFF, false);
        note.setPadding(dp(2), dp(12), dp(2), 0);
        card.addView(note);

        WindowManager.LayoutParams lp = params(-1, -1, true);
        lp.flags &= ~WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL;
        try {
            wm.addView(root, lp);
            panel = root;
            sv.setTranslationX(dp(60));
            sv.setAlpha(0f);
            sv.animate().translationX(0).alpha(1f).setDuration(260)
                    .setInterpolator(new DecelerateInterpolator(2f)).start();
            updatePanel();
        } catch (Exception ignored) {
        }
    }

    private void closePanel() {
        View p = panel;
        panel = null;
        tTemp = tTempSub = tPing = tRam = tHz = null;
        if (p == null) return;
        p.animate().alpha(0f).setDuration(160).withEndAction(() -> removeView(p)).start();
    }

    private LinearLayout.LayoutParams tileLp(boolean right) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -2, 1f);
        if (right) lp.leftMargin = dp(8);
        return lp;
    }

    /** [kafelek, wartość, opis] */
    private View[] tile(String label) {
        LinearLayout t = new LinearLayout(ctx);
        t.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable g = new GradientDrawable();
        g.setCornerRadius(dp(18));
        g.setColor(0x14FFFFFF);
        g.setStroke(dp(1), 0x1FFFFFFF);
        t.setBackground(g);
        t.setPadding(dp(14), dp(12), dp(14), dp(12));
        t.addView(text(label, 12, 0x99FFFFFF, true));
        TextView val = text("…", 22, Color.WHITE, true);
        t.addView(val);
        TextView sub = text("", 11, 0x80FFFFFF, false);
        t.addView(sub);
        return new View[]{t, val, sub};
    }

    interface Getter { boolean get(); }

    interface Setter { boolean set(boolean on); }

    private View toggleRow(String title, String desc, Getter g, Setter s) {
        LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(4), dp(12), dp(4), dp(4));
        LinearLayout col = new LinearLayout(ctx);
        col.setOrientation(LinearLayout.VERTICAL);
        col.addView(text(title, 15, Color.WHITE, true));
        col.addView(text(desc, 12, 0x99FFFFFF, false));
        row.addView(col, new LinearLayout.LayoutParams(0, -2, 1f));
        TextView sw = text("", 13, Color.WHITE, true);
        sw.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(dp(58), dp(34));
        slp.leftMargin = dp(10);
        row.addView(sw, slp);
        Runnable paint = () -> {
            boolean on = g.get();
            GradientDrawable d = new GradientDrawable();
            d.setCornerRadius(dp(17));
            d.setColor(on ? 0xFF30D158 : 0x33FFFFFF);
            sw.setBackground(d);
            sw.setText(on ? "WŁ" : "WYŁ");
            sw.setTextColor(on ? 0xFF0B2A12 : Color.WHITE);
        };
        paint.run();
        row.setOnClickListener(v -> {
            s.set(!g.get());
            main.postDelayed(paint, 150);
        });
        return row;
    }

    private void updatePanel() {
        if (tTemp == null) return;
        float t = batteryTemp();
        tTemp.setText(String.format(Locale.ROOT, "%.1f°C", t));
        String st = thermalText();
        tTempSub.setText(st);
        tTemp.setTextColor(t >= 43 ? 0xFFFF453A : t >= 38 ? 0xFFFFD60A : 0xFF30D158);
        tPing.setText(pingMs < 0 ? "—" : pingMs + " ms");
        tPing.setTextColor(pingMs < 0 ? Color.WHITE : pingMs < 60 ? 0xFF30D158 : pingMs < 120 ? 0xFFFFD60A : 0xFFFF453A);
        tRam.setText(String.format(Locale.ROOT, "%.1f GB", freeRamGb()));
        tHz.setText(refreshHz() + " Hz");
    }

    // ---------- pomiary ----------

    private void measurePing() {
        long t0 = SystemClock.elapsedRealtime();
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress("1.1.1.1", 443), 1500);
            pingMs = (int) (SystemClock.elapsedRealtime() - t0);
        } catch (Exception e) {
            pingMs = -1;
        }
    }

    private float batteryTemp() {
        try {
            Intent b = ctx.registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
            return b != null ? b.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) / 10f : 0f;
        } catch (Exception e) {
            return 0f;
        }
    }

    private String thermalText() {
        if (Build.VERSION.SDK_INT < 29) return "bateria";
        PowerManager pm = (PowerManager) ctx.getSystemService(Context.POWER_SERVICE);
        switch (pm.getCurrentThermalStatus()) {
            case PowerManager.THERMAL_STATUS_NONE: return "chłodny";
            case PowerManager.THERMAL_STATUS_LIGHT: return "lekko ciepły";
            case PowerManager.THERMAL_STATUS_MODERATE: return "ciepły";
            case PowerManager.THERMAL_STATUS_SEVERE: return "gorący – zwalnia";
            default: return "przegrzany!";
        }
    }

    private float freeRamGb() {
        ActivityManager am = (ActivityManager) ctx.getSystemService(Context.ACTIVITY_SERVICE);
        ActivityManager.MemoryInfo mi = new ActivityManager.MemoryInfo();
        am.getMemoryInfo(mi);
        return mi.availMem / 1073741824f;
    }

    private int refreshHz() {
        try {
            DisplayManager dm = (DisplayManager) ctx.getSystemService(Context.DISPLAY_SERVICE);
            Display d = dm.getDisplay(Display.DEFAULT_DISPLAY);
            return Math.round(d.getRefreshRate());
        } catch (Exception e) {
            return 60;
        }
    }

    // ---------- akcje ----------

    /** Zamyka procesy aplikacji w tle (oprócz gry) i pokazuje, ile pamięci zwolniono. */
    private void boost(TextView btn) {
        btn.setText("Optymalizuję…");
        final String game = currentGame;
        bg.execute(() -> {
            ActivityManager am = (ActivityManager) ctx.getSystemService(Context.ACTIVITY_SERVICE);
            ActivityManager.MemoryInfo mi = new ActivityManager.MemoryInfo();
            am.getMemoryInfo(mi);
            long before = mi.availMem;
            int n = 0;
            try {
                List<ApplicationInfo> list = ctx.getPackageManager().getInstalledApplications(0);
                for (ApplicationInfo ai : list) {
                    if ((ai.flags & ApplicationInfo.FLAG_SYSTEM) != 0) continue;
                    if (ai.packageName.equals(game) || ai.packageName.equals(ctx.getPackageName())) continue;
                    am.killBackgroundProcesses(ai.packageName);
                    n++;
                }
            } catch (Exception ignored) {
            }
            try {
                Thread.sleep(900);
            } catch (InterruptedException ignored) {
            }
            am.getMemoryInfo(mi);
            long freed = Math.max(0, mi.availMem - before) / (1024 * 1024);
            final int count = n;
            main.post(() -> {
                btn.setText(freed > 20 ? "✓  Zwolniono " + freed + " MB" : "✓  Zoptymalizowano (" + count + " apl.)");
                main.postDelayed(() -> btn.setText("🚀  Przyspiesz grę"), 3000);
                updatePanel();
            });
        });
    }

    private boolean setDnd(boolean on) {
        MediaListener l = MediaListener.instance;
        if (l == null) {
            Toast.makeText(ctx, "Włącz dostęp do powiadomień w Konfiguracji Fali", Toast.LENGTH_LONG).show();
            return false;
        }
        try {
            l.requestInterruptionFilter(on ? NotificationListenerService.INTERRUPTION_FILTER_PRIORITY
                    : NotificationListenerService.INTERRUPTION_FILTER_ALL);
            dndByUs = on;
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private boolean setCooling(boolean on) {
        if (!Settings.System.canWrite(ctx)) {
            Toast.makeText(ctx, "Zezwól Fali na zmianę ustawień systemu (jasność)", Toast.LENGTH_LONG).show();
            try {
                Intent i = new Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, Uri.parse("package:" + ctx.getPackageName()));
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                ctx.startActivity(i);
            } catch (Exception ignored) {
            }
            closePanel();
            return false;
        }
        try {
            if (on && !coolByUs) {
                int mode = Settings.System.getInt(ctx.getContentResolver(), Settings.System.SCREEN_BRIGHTNESS_MODE, 0);
                int b = Settings.System.getInt(ctx.getContentResolver(), Settings.System.SCREEN_BRIGHTNESS, 128);
                boolean dim = mode == Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL && b > 60;
                prefs.edit().putInt("cool_prev_b", b).putBoolean("cool_dimmed", dim)
                        .putBoolean("cool_active", true).apply();
                // jasność automatyczna zostaje włączona – zmniejszamy tylko ręcznie ustawioną, raz i łagodnie
                if (dim) Settings.System.putInt(ctx.getContentResolver(), Settings.System.SCREEN_BRIGHTNESS,
                        Math.round(b * 0.8f));
                coolByUs = true;
                final String game = currentGame;
                bg.execute(() -> {
                    ActivityManager am = (ActivityManager) ctx.getSystemService(Context.ACTIVITY_SERVICE);
                    try {
                        for (ApplicationInfo ai : ctx.getPackageManager().getInstalledApplications(0)) {
                            if ((ai.flags & ApplicationInfo.FLAG_SYSTEM) != 0) continue;
                            if (ai.packageName.equals(game) || ai.packageName.equals(ctx.getPackageName())) continue;
                            am.killBackgroundProcesses(ai.packageName);
                        }
                    } catch (Exception ignored) {
                    }
                });
                Toast.makeText(ctx, "❄ Chłodzenie włączone", Toast.LENGTH_SHORT).show();
            } else if (!on && coolByUs) {
                if (prefs.getBoolean("cool_dimmed", false)) {
                    Settings.System.putInt(ctx.getContentResolver(), Settings.System.SCREEN_BRIGHTNESS,
                            prefs.getInt("cool_prev_b", 128));
                }
                // starsze wersje Fali przełączały tryb – oddaj jasność automatyczną, jeśli była
                if (prefs.contains("cool_prev_mode")) {
                    Settings.System.putInt(ctx.getContentResolver(), Settings.System.SCREEN_BRIGHTNESS_MODE,
                            prefs.getInt("cool_prev_mode", 0));
                }
                prefs.edit().remove("cool_prev_mode").putBoolean("cool_active", false).putBoolean("cool_dimmed", false).apply();
                coolByUs = false;
            }
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private TextView text(String s, int sp, int color, boolean medium) {
        TextView t = new TextView(ctx);
        t.setText(s);
        t.setTextColor(color);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTypeface(Typeface.create(medium ? "sans-serif-medium" : "sans-serif", Typeface.NORMAL));
        return t;
    }
}
