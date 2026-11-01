package pl.fala.bench;

import android.animation.ValueAnimator;
import android.app.Activity;
import android.app.ActivityManager;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.SweepGradient;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.hardware.display.DisplayManager;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.StatFs;
import android.text.TextUtils;
import android.util.DisplayMetrics;
import android.util.TypedValue;
import android.view.Display;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.view.animation.DecelerateInterpolator;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.BufferedReader;
import java.io.FileReader;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

public class MainActivity extends Activity {

    private static final Locale PL = new Locale("pl", "PL");
    private static final int[] CAT_COLORS = {0xFF7C9CFF, 0xFFB57CFF, 0xFF4CD6C1, 0xFFFFC14C, 0xFFFF6B8A};
    private static final String[] CAT_NAMES = {"Procesor – 1 rdzeń", "Procesor – wszystkie rdzenie", "Pamięć", "Dysk", "Grafika"};

    private final Handler main = new Handler(Looper.getMainLooper());
    private SharedPreferences prefs;
    private FrameLayout root;
    private volatile boolean cancelled = false;
    private Thread worker;

    // wyniki
    private double rInt, rFloat, rSha, rSort, rMulti, rBw, rLat, rWrite, rIops, rGpu;
    private float tempStart, tempEnd;
    private int battStart;

    // ekran testu
    private RingView ring;
    private TextView stageName, stageLive;
    private LinearLayout stageList;
    private final List<TextView> stageRows = new ArrayList<>();

    private int dp(float v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = getSharedPreferences("bench", MODE_PRIVATE);
        getWindow().setStatusBarColor(0xFF07070D);
        getWindow().setNavigationBarColor(0xFF07070D);
        root = new FrameLayout(this);
        root.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{0xFF141230, 0xFF0B0B18, 0xFF07070D}));
        setContentView(root);
        showHome();
    }

    @Override
    public void onBackPressed() {
        if (worker != null) {
            cancelled = true;
            return;
        }
        if (root.getTag() != null && !"home".equals(root.getTag())) {
            showHome();
            return;
        }
        super.onBackPressed();
    }

    @Override
    protected void onDestroy() {
        cancelled = true;
        super.onDestroy();
    }

    private void keepOn(boolean on) {
        if (on) getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        else getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    }

    // ================= UI: pomocnicze =================

    private TextView tv(String s, float sp, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        t.setTypeface(Typeface.create(bold ? "sans-serif-medium" : "sans-serif", Typeface.NORMAL));
        return t;
    }

    private GradientDrawable card() {
        GradientDrawable g = new GradientDrawable(GradientDrawable.Orientation.TL_BR, new int[]{0x26FFFFFF, 0x0DFFFFFF});
        g.setCornerRadius(dp(24));
        g.setStroke(dp(1), 0x26FFFFFF);
        return g;
    }

    private TextView pill(String s, boolean primary) {
        TextView b = tv(s, 16, primary ? 0xFF0B0B18 : Color.WHITE, true);
        b.setGravity(Gravity.CENTER);
        b.setPadding(dp(18), dp(14), dp(18), dp(14));
        GradientDrawable g = primary
                ? new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, new int[]{0xFF9DB4FF, 0xFFD1A6FF})
                : new GradientDrawable();
        if (!primary) {
            g.setColor(0x1FFFFFFF);
            g.setStroke(dp(1), 0x33FFFFFF);
        }
        g.setCornerRadius(dp(22));
        b.setBackground(g);
        return b;
    }

    private ScrollView page(LinearLayout content) {
        ScrollView sv = new ScrollView(this);
        sv.setFillViewport(true);
        sv.setVerticalScrollBarEnabled(false);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(20), dp(28), dp(20), dp(28));
        sv.addView(content);
        return sv;
    }

    private void setPage(View v, String tag) {
        root.removeAllViews();
        root.setTag(tag);
        root.addView(v, new FrameLayout.LayoutParams(-1, -1));
        v.setAlpha(0f);
        v.setTranslationY(dp(16));
        v.animate().alpha(1f).translationY(0).setDuration(300).setInterpolator(new DecelerateInterpolator(2f)).start();
    }

    // ================= INFORMACJE O TELEFONIE =================

    static String socName() {
        String soc = "";
        if (Build.VERSION.SDK_INT >= 31) {
            soc = (Build.SOC_MANUFACTURER + " " + Build.SOC_MODEL).trim();
        }
        if (TextUtils.isEmpty(soc) || soc.contains("unknown")) soc = Build.HARDWARE;
        String up = soc.toUpperCase(Locale.ROOT);
        if (up.contains("MT6769Z")) return "MediaTek Helio G85 (MT6769Z)";
        if (up.contains("MT6769H")) return "MediaTek Helio G88 (MT6769H)";
        if (up.contains("MT6769T")) return "MediaTek Helio G80 (MT6769T)";
        if (up.contains("MT6769V")) return "MediaTek Helio G70 (MT6769V)";
        if (up.contains("MT6789")) return "MediaTek Helio G99 (MT6789)";
        return soc;
    }

    static String cpuClusters() {
        int n = Runtime.getRuntime().availableProcessors();
        TreeMap<Long, Integer> m = new TreeMap<>();
        for (int i = 0; i < n; i++) {
            try (BufferedReader br = new BufferedReader(new FileReader("/sys/devices/system/cpu/cpu" + i + "/cpufreq/cpuinfo_max_freq"))) {
                long khz = Long.parseLong(br.readLine().trim());
                Integer c = m.get(khz);
                m.put(khz, c == null ? 1 : c + 1);
            } catch (Exception ignored) {
            }
        }
        if (m.isEmpty()) return n + " rdzeni";
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<Long, Integer> e : m.descendingMap().entrySet()) {
            if (sb.length() > 0) sb.append(" + ");
            sb.append(e.getValue()).append("× ").append(String.format(PL, "%.2f GHz", e.getKey() / 1e6));
        }
        return sb.toString();
    }

    private String ramText() {
        ActivityManager am = (ActivityManager) getSystemService(Context.ACTIVITY_SERVICE);
        ActivityManager.MemoryInfo mi = new ActivityManager.MemoryInfo();
        am.getMemoryInfo(mi);
        return String.format(PL, "%.1f GB (wolne %.1f GB)", mi.totalMem / 1073741824.0, mi.availMem / 1073741824.0);
    }

    private String storageText() {
        StatFs s = new StatFs(getFilesDir().getAbsolutePath());
        return String.format(PL, "%.0f GB (wolne %.0f GB)", s.getTotalBytes() / 1e9, s.getAvailableBytes() / 1e9);
    }

    private String displayText() {
        DisplayMetrics dm = new DisplayMetrics();
        //noinspection deprecation
        getWindowManager().getDefaultDisplay().getRealMetrics(dm);
        float hz = 60;
        try {
            Display d = ((DisplayManager) getSystemService(Context.DISPLAY_SERVICE)).getDisplay(Display.DEFAULT_DISPLAY);
            hz = d.getRefreshRate();
        } catch (Exception ignored) {
        }
        return Math.min(dm.widthPixels, dm.heightPixels) + " × " + Math.max(dm.widthPixels, dm.heightPixels) + ", " + Math.round(hz) + " Hz";
    }

    private float batteryTemp() {
        Intent b = registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        return b != null ? b.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) / 10f : 0;
    }

    private int batteryLevel() {
        Intent b = registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        if (b == null) return -1;
        return b.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) * 100 / Math.max(1, b.getIntExtra(BatteryManager.EXTRA_SCALE, 100));
    }

    private View infoRow(String k, String v) {
        LinearLayout r = new LinearLayout(this);
        r.setPadding(0, dp(7), 0, dp(7));
        TextView a = tv(k, 14, 0x99FFFFFF, false);
        r.addView(a, new LinearLayout.LayoutParams(dp(110), -2));
        TextView b = tv(v, 14, Color.WHITE, true);
        r.addView(b, new LinearLayout.LayoutParams(0, -2, 1f));
        return r;
    }

    // ================= EKRAN GŁÓWNY =================

    private void showHome() {
        keepOn(false);
        LinearLayout c = new LinearLayout(this);
        ScrollView sv = page(c);

        TextView title = tv("Fala Bench", 34, Color.WHITE, true);
        title.getPaint().setShader(new LinearGradient(0, 0, dp(220), 0, 0xFF9DB4FF, 0xFFE0A6FF, Shader.TileMode.CLAMP));
        c.addView(title);
        c.addView(tv("Test wydajności telefonu", 15, 0x99FFFFFF, false));

        LinearLayout info = new LinearLayout(this);
        info.setOrientation(LinearLayout.VERTICAL);
        info.setBackground(card());
        info.setPadding(dp(18), dp(12), dp(18), dp(12));
        info.addView(infoRow("Telefon", Build.MANUFACTURER.substring(0, 1).toUpperCase(Locale.ROOT) + Build.MANUFACTURER.substring(1) + " " + Build.MODEL));
        info.addView(infoRow("Procesor", socName()));
        info.addView(infoRow("Rdzenie", cpuClusters()));
        String gpu = prefs.getString("gpu", "");
        if (!gpu.isEmpty()) info.addView(infoRow("Grafika", gpu));
        info.addView(infoRow("RAM", ramText()));
        info.addView(infoRow("Pamięć", storageText()));
        info.addView(infoRow("Ekran", displayText()));
        info.addView(infoRow("Android", Build.VERSION.RELEASE + " (API " + Build.VERSION.SDK_INT + ")"));
        info.addView(infoRow("Bateria", batteryLevel() + "%, " + String.format(PL, "%.1f °C", batteryTemp())));
        LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(-1, -2);
        ilp.topMargin = dp(20);
        c.addView(info, ilp);

        // przycisk START
        StartButton start = new StartButton(this);
        start.setOnClickListener(v -> runFull());
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(dp(200), dp(200));
        slp.gravity = Gravity.CENTER_HORIZONTAL;
        slp.topMargin = dp(26);
        slp.bottomMargin = dp(10);
        c.addView(start, slp);
        TextView hint = tv("Pełny test trwa około minuty. Nie używaj telefonu w trakcie.", 13, 0x80FFFFFF, false);
        hint.setGravity(Gravity.CENTER);
        c.addView(hint, new LinearLayout.LayoutParams(-1, -2));

        // test obciążeniowy
        TextView stressT = tv("Test obciążeniowy", 18, Color.WHITE, true);
        stressT.setPadding(0, dp(28), 0, dp(4));
        c.addView(stressT);
        c.addView(tv("Wszystkie rdzenie pracują na 100% – zobaczysz, ile wydajności telefon traci, gdy się nagrzeje.", 13, 0x99FFFFFF, false));
        LinearLayout sr = new LinearLayout(this);
        sr.setPadding(0, dp(12), 0, 0);
        TextView s1 = pill("🔥 1 minuta", false), s3 = pill("🔥 3 minuty", false);
        s1.setOnClickListener(v -> runStress(60));
        s3.setOnClickListener(v -> runStress(180));
        sr.addView(s1, new LinearLayout.LayoutParams(0, -2, 1f));
        LinearLayout.LayoutParams s3lp = new LinearLayout.LayoutParams(0, -2, 1f);
        s3lp.leftMargin = dp(10);
        sr.addView(s3, s3lp);
        c.addView(sr);

        // historia
        List<String[]> hist = history();
        if (!hist.isEmpty()) {
            TextView ht = tv("Historia wyników", 18, Color.WHITE, true);
            ht.setPadding(0, dp(28), 0, dp(8));
            c.addView(ht);
            int max = 1;
            for (String[] h : hist) max = Math.max(max, Integer.parseInt(h[1]));
            for (String[] h : hist) {
                LinearLayout row = new LinearLayout(this);
                row.setOrientation(LinearLayout.VERTICAL);
                row.setPadding(dp(16), dp(12), dp(16), dp(12));
                row.setBackground(card());
                LinearLayout top = new LinearLayout(this);
                top.addView(tv(new SimpleDateFormat("d MMM, HH:mm", PL).format(new Date(Long.parseLong(h[0]))), 13, 0x99FFFFFF, false),
                        new LinearLayout.LayoutParams(0, -2, 1f));
                top.addView(tv(h[1] + " pkt", 16, Color.WHITE, true));
                row.addView(top);
                Bar bar = new Bar(this, Integer.parseInt(h[1]) / (float) max, 0xFF9DB4FF);
                LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(-1, dp(6));
                blp.topMargin = dp(8);
                row.addView(bar, blp);
                LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(-1, -2);
                rlp.bottomMargin = dp(8);
                c.addView(row, rlp);
            }
        }
        setPage(sv, "home");
    }

    // ================= PEŁNY TEST =================

    private static final String[] STAGES = {
            "Procesor: liczby całkowite", "Procesor: liczby zmiennoprzecinkowe", "Procesor: szyfrowanie SHA-256",
            "Procesor: sortowanie", "Procesor: wszystkie rdzenie", "Pamięć: przepustowość", "Pamięć: opóźnienie",
            "Dysk: zapis", "Grafika: OpenGL"};

    private void showRunning(String title) {
        keepOn(true);
        LinearLayout c = new LinearLayout(this);
        ScrollView sv = page(c);
        TextView t = tv(title, 22, Color.WHITE, true);
        t.setGravity(Gravity.CENTER);
        c.addView(t, new LinearLayout.LayoutParams(-1, -2));
        ring = new RingView(this);
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(dp(220), dp(220));
        rlp.gravity = Gravity.CENTER_HORIZONTAL;
        rlp.topMargin = dp(20);
        c.addView(ring, rlp);
        stageName = tv("", 17, Color.WHITE, true);
        stageName.setGravity(Gravity.CENTER);
        stageName.setPadding(0, dp(16), 0, 0);
        c.addView(stageName, new LinearLayout.LayoutParams(-1, -2));
        stageLive = tv("", 15, 0xFF9DB4FF, false);
        stageLive.setGravity(Gravity.CENTER);
        c.addView(stageLive, new LinearLayout.LayoutParams(-1, -2));
        stageList = new LinearLayout(this);
        stageList.setOrientation(LinearLayout.VERTICAL);
        stageList.setPadding(dp(18), dp(14), dp(18), dp(14));
        stageList.setBackground(card());
        stageRows.clear();
        for (String s : STAGES) {
            TextView r = tv("○  " + s, 14, 0x66FFFFFF, false);
            r.setPadding(0, dp(5), 0, dp(5));
            stageRows.add(r);
            stageList.addView(r);
        }
        LinearLayout.LayoutParams llp = new LinearLayout.LayoutParams(-1, -2);
        llp.topMargin = dp(20);
        c.addView(stageList, llp);
        TextView cancel = pill("Przerwij", false);
        cancel.setOnClickListener(v -> cancelled = true);
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(-1, -2);
        clp.topMargin = dp(16);
        c.addView(cancel, clp);
        setPage(sv, "run");
    }

    private void stage(int i, float overall) {
        main.post(() -> {
            for (int k = 0; k < stageRows.size(); k++) {
                TextView r = stageRows.get(k);
                if (k < i) { r.setText("✓  " + STAGES[k]); r.setTextColor(0xFF4CD6C1); }
                else if (k == i) { r.setText("●  " + STAGES[k]); r.setTextColor(Color.WHITE); }
            }
            stageName.setText(STAGES[i]);
            stageLive.setText("");
            ring.setProgress(overall);
        });
    }

    private Bench.Live live(int i) {
        final long[] last = {0};
        return (f, v) -> {
            long now = System.nanoTime();
            if (now - last[0] < 120_000_000L) return;
            last[0] = now;
            if (cancelled) throw new RuntimeException("cancel");
            final float overall = (i + f) / STAGES.length;
            main.post(() -> {
                stageLive.setText(v);
                ring.setProgress(overall);
            });
        };
    }

    private void runFull() {
        cancelled = false;
        showRunning("Trwa test…");
        tempStart = batteryTemp();
        battStart = batteryLevel();
        final int cores = Runtime.getRuntime().availableProcessors();
        final java.io.File dir = getFilesDir();
        worker = new Thread(() -> {
            try {
                Thread.sleep(600);
                stage(0, 0f); rInt = Bench.intSingle(2500, live(0));
                stage(1, 1f / 9); rFloat = Bench.floatSingle(2500, live(1));
                stage(2, 2f / 9); rSha = Bench.sha(2000, live(2));
                stage(3, 3f / 9); rSort = Bench.sort(2000, live(3));
                stage(4, 4f / 9); rMulti = Bench.multi(5000, cores, live(4));
                stage(5, 5f / 9); rBw = Bench.bandwidth(2500, live(5));
                stage(6, 6f / 9); rLat = Bench.latency(2500, live(6));
                System.gc();
                stage(7, 7f / 9); double[] st = Bench.storage(dir, live(7)); rWrite = st[0]; rIops = st[1];
                stage(8, 8f / 9);
                main.post(this::runGpu);
            } catch (Throwable e) {
                main.post(() -> { worker = null; showHome(); });
            }
        });
        worker.setPriority(Thread.MAX_PRIORITY);
        worker.start();
    }

    private void runGpu() {
        if (cancelled) { worker = null; showHome(); return; }
        FrameLayout overlay = new FrameLayout(this);
        overlay.setTag("gpu");
        TextView label = tv("Grafika: OpenGL ES", 16, Color.WHITE, true);
        label.setShadowLayer(dp(4), 0, 0, Color.BLACK);
        TextView val = tv("", 15, 0xFF9DB4FF, true);
        val.setShadowLayer(dp(4), 0, 0, Color.BLACK);
        Bar bar = new Bar(this, 0f, 0xFFFF6B8A);
        GpuView view = new GpuView(this, new GpuView.Listener() {
            @Override
            public void onProgress(float fraction, double pps) {
                val.setText(String.format(PL, "%.1f przebiegów/s  ·  %.0f kl./s", pps, pps / GpuView.PASSES));
                bar.set(fraction);
                ring.setProgress((8 + fraction) / 9f);
            }

            @Override
            public void onDone(double pps, String renderer) {
                rGpu = pps;
                if (renderer != null && !renderer.isEmpty()) prefs.edit().putString("gpu", renderer).apply();
                root.removeView(overlay);
                worker = null;
                finishFull();
            }
        });
        overlay.addView(view, new FrameLayout.LayoutParams(-1, -1));
        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.VERTICAL);
        top.setPadding(dp(20), dp(36), dp(20), dp(16));
        top.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, new int[]{0xCC000000, 0x00000000}));
        top.addView(label);
        top.addView(val);
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(-1, dp(5));
        blp.topMargin = dp(10);
        top.addView(bar, blp);
        overlay.addView(top, new FrameLayout.LayoutParams(-1, -2, Gravity.TOP));
        root.addView(overlay, new FrameLayout.LayoutParams(-1, -1));
    }

    // ================= WYNIKI =================

    private int[] scores() {
        int cpu1 = (Bench.score(rInt, Bench.REF_INT) + Bench.score(rFloat, Bench.REF_FLOAT)
                + Bench.score(rSha, Bench.REF_SHA) + Bench.score(rSort, Bench.REF_SORT)) / 4;
        int cpuN = Bench.score(rMulti, Bench.REF_MULTI);
        int mem = (Bench.score(rBw, Bench.REF_BW) + (rLat > 0 ? (int) Math.round(Bench.REF_LAT / rLat * 1000) : 0)) / 2;
        int disk = (Bench.score(rWrite, Bench.REF_WRITE) + Bench.score(rIops, Bench.REF_IOPS)) / 2;
        int gpu = Bench.score(rGpu, Bench.REF_GPU);
        return new int[]{cpu1, cpuN, mem, disk, gpu};
    }

    private void finishFull() {
        tempEnd = batteryTemp();
        int[] s = scores();
        int total = 0;
        for (int v : s) total += v;
        saveHistory(total, s);
        showResults(total, s);
    }

    private String[] details() {
        return new String[]{
                String.format(PL, "Całkowite %s op/s · zmiennoprz. %s it/s · SHA-256 %.0f MB/s · sortowanie %s el/s",
                        Bench.fmt(rInt), Bench.fmt(rFloat), rSha, Bench.fmt(rSort)),
                String.format(PL, "%.0f jednostek pracy/s na %d wątkach", rMulti, Runtime.getRuntime().availableProcessors()),
                String.format(PL, "Przepustowość %.1f GB/s · opóźnienie %.0f ns", rBw, rLat),
                String.format(PL, "Zapis sekwencyjny %.0f MB/s · losowy 4 KB %.0f IOPS", rWrite, rIops),
                String.format(PL, "%.1f przebiegów fraktala 512×512 na sekundę", rGpu)};
    }

    private void showResults(int total, int[] s) {
        keepOn(false);
        LinearLayout c = new LinearLayout(this);
        ScrollView sv = page(c);
        TextView head = tv("Twój wynik", 16, 0x99FFFFFF, true);
        head.setGravity(Gravity.CENTER);
        c.addView(head, new LinearLayout.LayoutParams(-1, -2));
        TextView big = tv("0", 64, Color.WHITE, true);
        big.setGravity(Gravity.CENTER);
        big.getPaint().setShader(new LinearGradient(0, 0, dp(300), 0, 0xFF9DB4FF, 0xFFE0A6FF, Shader.TileMode.CLAMP));
        c.addView(big, new LinearLayout.LayoutParams(-1, -2));
        ValueAnimator va = ValueAnimator.ofInt(0, total);
        va.setDuration(1400);
        va.setInterpolator(new DecelerateInterpolator(2f));
        va.addUpdateListener(a -> big.setText(String.valueOf(a.getAnimatedValue())));
        va.start();
        TextView sub = tv(Build.MODEL + " · " + socName(), 14, 0x99FFFFFF, false);
        sub.setGravity(Gravity.CENTER);
        c.addView(sub, new LinearLayout.LayoutParams(-1, -2));

        String[] det = details();
        int max = 1000;
        for (int v : s) max = Math.max(max, v);
        for (int i = 0; i < 5; i++) {
            LinearLayout cardV = new LinearLayout(this);
            cardV.setOrientation(LinearLayout.VERTICAL);
            cardV.setBackground(card());
            cardV.setPadding(dp(18), dp(14), dp(18), dp(14));
            LinearLayout top = new LinearLayout(this);
            top.setGravity(Gravity.CENTER_VERTICAL);
            View dot = new View(this);
            GradientDrawable g = new GradientDrawable();
            g.setShape(GradientDrawable.OVAL);
            g.setColor(CAT_COLORS[i]);
            dot.setBackground(g);
            top.addView(dot, new LinearLayout.LayoutParams(dp(10), dp(10)));
            TextView n = tv(CAT_NAMES[i], 15, Color.WHITE, true);
            n.setPadding(dp(10), 0, 0, 0);
            top.addView(n, new LinearLayout.LayoutParams(0, -2, 1f));
            top.addView(tv(String.valueOf(s[i]), 20, CAT_COLORS[i], true));
            cardV.addView(top);
            Bar bar = new Bar(this, 0f, CAT_COLORS[i]);
            LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(-1, dp(8));
            blp.topMargin = dp(10);
            cardV.addView(bar, blp);
            final float target = s[i] / (float) max;
            bar.postDelayed(() -> bar.animateTo(target), 300 + i * 120L);
            TextView d = tv(det[i], 12, 0x99FFFFFF, false);
            d.setPadding(0, dp(8), 0, 0);
            cardV.addView(d);
            LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(-1, -2);
            clp.topMargin = dp(12);
            c.addView(cardV, clp);
        }
        int battNow = batteryLevel();
        TextView th = tv(String.format(PL, "Temperatura baterii: %.1f → %.1f °C%s", tempStart, tempEnd,
                battStart >= 0 && battNow >= 0 ? String.format(PL, " · bateria %d → %d%%", battStart, battNow) : ""), 13, 0x99FFFFFF, false);
        th.setPadding(dp(4), dp(16), dp(4), 0);
        c.addView(th);
        TextView note = tv("Skala Fala Bench: 1000 pkt w kategorii to poziom telefonu z procesorem klasy Helio G85. "
                + "Wynik zależy od temperatury i naładowania – porównuj testy robione w podobnych warunkach.", 12, 0x66FFFFFF, false);
        note.setPadding(dp(4), dp(8), dp(4), 0);
        c.addView(note);

        LinearLayout btns = new LinearLayout(this);
        btns.setPadding(0, dp(20), 0, 0);
        TextView share = pill("Udostępnij", true), back = pill("Gotowe", false);
        final int tot = total;
        share.setOnClickListener(v -> share(tot, s));
        back.setOnClickListener(v -> showHome());
        btns.addView(share, new LinearLayout.LayoutParams(0, -2, 1f));
        LinearLayout.LayoutParams bl = new LinearLayout.LayoutParams(0, -2, 1f);
        bl.leftMargin = dp(10);
        btns.addView(back, bl);
        c.addView(btns);
        setPage(sv, "result");
    }

    private void share(int total, int[] s) {
        StringBuilder sb = new StringBuilder();
        sb.append("Fala Bench: ").append(total).append(" pkt\n");
        sb.append(Build.MANUFACTURER).append(' ').append(Build.MODEL).append(" · ").append(socName()).append('\n');
        for (int i = 0; i < 5; i++) sb.append(CAT_NAMES[i]).append(": ").append(s[i]).append('\n');
        Intent i = new Intent(Intent.ACTION_SEND);
        i.setType("text/plain");
        i.putExtra(Intent.EXTRA_TEXT, sb.toString());
        try {
            startActivity(Intent.createChooser(i, "Udostępnij wynik"));
        } catch (Exception ignored) {
        }
    }

    private void saveHistory(int total, int[] s) {
        StringBuilder line = new StringBuilder();
        line.append(System.currentTimeMillis()).append(';').append(total);
        for (int v : s) line.append(';').append(v);
        String old = prefs.getString("hist", "");
        String[] parts = old.isEmpty() ? new String[0] : old.split("\n");
        StringBuilder sb = new StringBuilder(line);
        for (int i = 0; i < parts.length && i < 7; i++) sb.append('\n').append(parts[i]);
        prefs.edit().putString("hist", sb.toString()).apply();
    }

    private List<String[]> history() {
        List<String[]> out = new ArrayList<>();
        String h = prefs.getString("hist", "");
        if (h.isEmpty()) return out;
        for (String l : h.split("\n")) {
            String[] p = l.split(";");
            if (p.length >= 2) out.add(p);
        }
        return out;
    }

    // ================= TEST OBCIĄŻENIOWY =================

    private void runStress(int seconds) {
        cancelled = false;
        keepOn(true);
        LinearLayout c = new LinearLayout(this);
        ScrollView sv = page(c);
        c.addView(tv("Test obciążeniowy", 24, Color.WHITE, true));
        TextView sub = tv("Wszystkie rdzenie na 100% przez " + (seconds / 60) + " min", 14, 0x99FFFFFF, false);
        c.addView(sub);
        ChartView chart = new ChartView(this);
        chart.slots = seconds / 5 + 1;
        LinearLayout.LayoutParams chlp = new LinearLayout.LayoutParams(-1, dp(230));
        chlp.topMargin = dp(18);
        chart.setBackground(card());
        c.addView(chart, chlp);
        LinearLayout legend = new LinearLayout(this);
        legend.setPadding(dp(4), dp(8), 0, 0);
        legend.addView(tv("━ wydajność   ", 13, 0xFF7C9CFF, true));
        legend.addView(tv("┅ temperatura", 13, 0xFFFF8A4C, true));
        c.addView(legend);

        LinearLayout tiles = new LinearLayout(this);
        tiles.setPadding(0, dp(16), 0, 0);
        TextView[] vals = new TextView[3];
        String[] names = {"Teraz", "Stabilność", "Temperatura"};
        for (int i = 0; i < 3; i++) {
            LinearLayout t = new LinearLayout(this);
            t.setOrientation(LinearLayout.VERTICAL);
            t.setBackground(card());
            t.setPadding(dp(14), dp(12), dp(14), dp(12));
            t.addView(tv(names[i], 12, 0x99FFFFFF, true));
            vals[i] = tv("—", 20, Color.WHITE, true);
            t.addView(vals[i]);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -2, 1f);
            if (i > 0) lp.leftMargin = dp(8);
            tiles.addView(t, lp);
        }
        c.addView(tiles);
        TextView status = tv("Rozgrzewanie…", 15, 0xFF9DB4FF, true);
        status.setPadding(0, dp(16), 0, 0);
        c.addView(status);
        TextView stop = pill("Zatrzymaj", false);
        stop.setOnClickListener(v -> cancelled = true);
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(-1, -2);
        slp.topMargin = dp(16);
        c.addView(stop, slp);
        setPage(sv, "stress");

        final int threads = Runtime.getRuntime().availableProcessors();
        final float t0 = batteryTemp();
        worker = new Thread(() -> {
            AtomicLong units = new AtomicLong();
            AtomicBoolean stopFlag = new AtomicBoolean(false);
            Thread[] ts = new Thread[threads];
            for (int t = 0; t < threads; t++) {
                final int id = t;
                ts[t] = new Thread(() -> {
                    double cx = -0.75 + id * 0.01;
                    int k = 0;
                    while (!stopFlag.get()) {
                        Bench.sink += Bench.intWork(id * 31 + k++, 100_000);
                        Bench.floatWork(cx, 512);
                        units.incrementAndGet();
                    }
                });
                ts[t].start();
            }
            double first = -1, maxV = 0, minV = Double.MAX_VALUE;
            long start = System.nanoTime();
            int windows = seconds / 5;
            for (int w = 0; w < windows && !cancelled; w++) {
                long u0 = units.get(), s0 = System.nanoTime();
                while (System.nanoTime() - s0 < 5_000_000_000L && !cancelled) {
                    try { Thread.sleep(100); } catch (InterruptedException ignored) { }
                }
                double rate = (units.get() - u0) / ((System.nanoTime() - s0) / 1e9);
                if (first < 0) first = rate;
                maxV = Math.max(maxV, rate);
                minV = Math.min(minV, rate);
                final float pct = (float) (rate / maxV * 100);
                final float pctFirst = (float) (rate / Math.max(1, first) * 100);
                final float temp = batteryTemp();
                final float stab = (float) (minV / maxV * 100);
                final int left = (int) (seconds - (System.nanoTime() - start) / 1e9);
                main.post(() -> {
                    chart.add(Math.min(100f, pctFirst), temp);
                    vals[0].setText(String.format(PL, "%.0f%%", pctFirst));
                    vals[1].setText(String.format(PL, "%.0f%%", stab));
                    vals[2].setText(String.format(PL, "%.1f°", temp));
                    vals[2].setTextColor(temp >= 43 ? 0xFFFF6B6B : temp >= 38 ? 0xFFFFC14C : Color.WHITE);
                    status.setText("Pozostało " + Math.max(0, left) + " s");
                });
            }
            stopFlag.set(true);
            for (Thread t : ts) {
                try { t.join(); } catch (InterruptedException ignored) { }
            }
            final double stab = maxV > 0 ? minV / maxV * 100 : 0;
            final float t1 = batteryTemp();
            final boolean wasCancelled = cancelled;
            main.post(() -> {
                worker = null;
                keepOn(false);
                if (wasCancelled && chart.perf.size() < 2) { showHome(); return; }
                String verdict = stab >= 90 ? "Świetnie – telefon prawie nie zwalnia."
                        : stab >= 75 ? "Dobrze – lekki spadek po nagrzaniu."
                        : stab >= 60 ? "Średnio – telefon wyraźnie zwalnia, gdy jest ciepły."
                        : "Słabo – mocny spadek wydajności po nagrzaniu.";
                status.setText(String.format(PL, "Stabilność %.0f%%. %s\nTemperatura %.1f → %.1f °C", stab, verdict, t0, t1));
                status.setTextColor(Color.WHITE);
                stop.setText("Gotowe");
                stop.setOnClickListener(v -> showHome());
            });
        });
        worker.start();
    }

    // ================= WIDOKI =================

    /** Pierścień postępu. */
    static class RingView extends View {
        private final Paint track = new Paint(Paint.ANTI_ALIAS_FLAG), arc = new Paint(Paint.ANTI_ALIAS_FLAG), txt = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF r = new RectF();
        private float progress = 0, shown = 0;
        private final float d;

        RingView(Context c) {
            super(c);
            d = c.getResources().getDisplayMetrics().density;
            track.setStyle(Paint.Style.STROKE);
            track.setStrokeWidth(14 * d);
            track.setColor(0x1AFFFFFF);
            arc.setStyle(Paint.Style.STROKE);
            arc.setStrokeWidth(14 * d);
            arc.setStrokeCap(Paint.Cap.ROUND);
            txt.setColor(Color.WHITE);
            txt.setTextAlign(Paint.Align.CENTER);
            txt.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        }

        void setProgress(float p) {
            progress = Math.max(0, Math.min(1, p));
            invalidate();
        }

        @Override
        protected void onDraw(Canvas c) {
            shown += (progress - shown) * 0.15f;
            float s = Math.min(getWidth(), getHeight()) - 16 * d;
            float l = (getWidth() - s) / 2, t = (getHeight() - s) / 2;
            r.set(l, t, l + s, t + s);
            c.drawOval(r, track);
            arc.setShader(new SweepGradient(getWidth() / 2f, getHeight() / 2f,
                    new int[]{0xFF9DB4FF, 0xFFE0A6FF, 0xFF4CD6C1, 0xFF9DB4FF}, null));
            c.save();
            c.rotate(-90, getWidth() / 2f, getHeight() / 2f);
            c.drawArc(r, 0, 360 * shown, false, arc);
            c.restore();
            txt.setTextSize(44 * d);
            c.drawText(Math.round(shown * 100) + "%", getWidth() / 2f, getHeight() / 2f + 16 * d, txt);
            if (Math.abs(progress - shown) > 0.001f) postInvalidateOnAnimation();
        }
    }

    /** Pasek wyniku. */
    static class Bar extends View {
        private final Paint bg = new Paint(Paint.ANTI_ALIAS_FLAG), fg = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF r = new RectF();
        private float value;
        private final int color;

        Bar(Context c, float v, int color) {
            super(c);
            value = v;
            this.color = color;
            bg.setColor(0x1AFFFFFF);
        }

        void set(float v) {
            value = v;
            invalidate();
        }

        void animateTo(float v) {
            ValueAnimator a = ValueAnimator.ofFloat(value, v);
            a.setDuration(900);
            a.setInterpolator(new DecelerateInterpolator(2f));
            a.addUpdateListener(x -> set((float) x.getAnimatedValue()));
            a.start();
        }

        @Override
        protected void onDraw(Canvas c) {
            float h = getHeight();
            r.set(0, 0, getWidth(), h);
            c.drawRoundRect(r, h / 2, h / 2, bg);
            r.set(0, 0, Math.max(h, getWidth() * Math.min(1, value)), h);
            fg.setShader(new LinearGradient(0, 0, getWidth(), 0, color, (color & 0x00FFFFFF) | 0x99000000, Shader.TileMode.CLAMP));
            if (value > 0) c.drawRoundRect(r, h / 2, h / 2, fg);
        }
    }

    /** Duży okrągły przycisk START z pulsującą poświatą. */
    static class StartButton extends View {
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG), glow = new Paint(Paint.ANTI_ALIAS_FLAG), t = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final float d;
        private final long born = System.nanoTime();

        StartButton(Context c) {
            super(c);
            d = c.getResources().getDisplayMetrics().density;
            t.setColor(0xFF0B0B18);
            t.setTextAlign(Paint.Align.CENTER);
            t.setTypeface(Typeface.create("sans-serif-black", Typeface.NORMAL));
            setClickable(true);
        }

        @Override
        protected void onDraw(Canvas c) {
            float cx = getWidth() / 2f, cy = getHeight() / 2f, r = Math.min(cx, cy) * 0.72f;
            double s = (System.nanoTime() - born) / 1e9;
            float pulse = (float) (0.5 + 0.5 * Math.sin(s * 2.2));
            glow.setShader(new android.graphics.RadialGradient(cx, cy, r * 1.38f,
                    new int[]{0x669DB4FF, 0x22B57CFF, 0x00000000}, new float[]{0.6f, 0.82f, 1f}, Shader.TileMode.CLAMP));
            c.drawCircle(cx, cy, r * (1.3f + pulse * 0.08f), glow);
            p.setShader(new LinearGradient(cx - r, cy - r, cx + r, cy + r, 0xFF9DB4FF, 0xFFE0A6FF, Shader.TileMode.CLAMP));
            float sc = isPressed() ? 0.94f : 1f;
            c.drawCircle(cx, cy, r * sc, p);
            t.setTextSize(30 * d * sc);
            c.drawText("START", cx, cy + 11 * d, t);
            postInvalidateOnAnimation();
        }
    }
}
