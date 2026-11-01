package pl.fala.launcher;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.LauncherActivityInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.res.Resources;
import android.content.res.XmlResourceParser;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.drawable.AdaptiveIconDrawable;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.os.UserHandle;

import org.xmlpull.v1.XmlPullParser;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Tworzy ikony według ustawień: kształt, styl (kolorowe / szklane / ciemne),
 * paczka ikon, własne obrazki z galerii.
 */
public class IconFactory {

    public static final String[] SHAPES = {"iOS", "Koło", "Kropla", "Kwadrat"};
    public static final String[] STYLES = {"Kolorowe", "Szklane", "Ciemne", "Tapeta"};
    public static final String[] TONES = {"Auto", "Jasne", "Ciemne"};

    /** Kolory z aktualnej tapety (ustawiane przez ekran główny). */
    static volatile int wallPrimary = 0xFF6750A4;
    static volatile int wallSecondary = 0;
    public static final String[] SIZES = {"Małe", "Średnie", "Duże"};

    private final Context ctx;
    private final SharedPreferences prefs;
    private final int density;

    // paczka ikon: komponent -> nazwa drawable
    private String packName = null;
    private Map<String, String> packMap = null;
    private Resources packRes = null;

    public IconFactory(Context ctx, SharedPreferences prefs) {
        this.ctx = ctx;
        this.prefs = prefs;
        this.density = ctx.getResources().getDisplayMetrics().densityDpi;
    }

    public int shape() { return prefs.getInt("icon_shape", 0); }

    public int style() { return prefs.getInt("icon_style", 0); }

    public int size() { return prefs.getInt("icon_size", 1); }

    public int tone() { return prefs.getInt("icon_tone", 0); }

    /** Czy wygląd ikon zależy od tapety (trzeba je przerysować po jej zmianie). */
    public boolean dependsOnWallpaper() {
        int st = style();
        return st == 1 || st == 3;
    }

    /**
     * Paleta "Tapeta": {góra tła, dół tła, kolor symbolu, jasna?}.
     * Jasne tło z ciemnym symbolem albo ciemne tło z jasnym – w odcieniu tapety.
     */
    private int[] wallPalette() {
        float[] hsv = new float[3];
        Color.colorToHSV(wallPrimary, hsv);
        float h = hsv[0], s = hsv[1];
        float[] hsv2 = new float[3];
        int sec = wallSecondary;
        float gh = h;
        if (sec != 0) {
            Color.colorToHSV(sec, hsv2);
            if (hsv2[1] > 0.12f) gh = hsv2[0];
        }
        boolean gray = s < 0.1f;
        int t = tone();
        boolean light = t == 1 || (t == 0 && Color.luminance(wallPrimary) > 0.42f);
        int top, bottom, glyph;
        if (light) {
            top = Color.HSVToColor(new float[]{h, gray ? 0.02f : 0.16f, 0.98f});
            bottom = Color.HSVToColor(new float[]{h, gray ? 0.03f : 0.26f, 0.90f});
            glyph = Color.HSVToColor(new float[]{gh, gray ? 0.05f : Math.max(0.55f, s), 0.36f});
        } else {
            top = Color.HSVToColor(new float[]{h, gray ? 0.04f : 0.45f, 0.27f});
            bottom = Color.HSVToColor(new float[]{h, gray ? 0.05f : 0.55f, 0.14f});
            glyph = Color.HSVToColor(new float[]{gh, gray ? 0.05f : 0.33f, 0.96f});
        }
        return new int[]{top, bottom, glyph, light ? 1 : 0};
    }

    /** Zamienia kolory ikony na odcienie jednego koloru (dla ikon bez symbolu). */
    private static ColorMatrixColorFilter tintTo(int color) {
        float r = Color.red(color) / 255f, g = Color.green(color) / 255f, b = Color.blue(color) / 255f;
        float k = 1.35f;
        return new ColorMatrixColorFilter(new ColorMatrix(new float[]{
                0.30f * r * k, 0.59f * r * k, 0.11f * r * k, 0, 0,
                0.30f * g * k, 0.59f * g * k, 0.11f * g * k, 0, 0,
                0.30f * b * k, 0.59f * b * k, 0.11f * b * k, 0, 0,
                0, 0, 0, 1, 0}));
    }

    public boolean labels() { return prefs.getBoolean("icon_labels", true); }

    /** Rozmiar ikony na ekranie głównym w dp. */
    public int homeDp() {
        int s = size();
        return s == 0 ? 52 : s == 2 ? 68 : 60;
    }

    // ---------- kształty ----------

    public Path shapePath(int px) {
        Path p = new Path();
        RectF r = new RectF(0, 0, px, px);
        switch (shape()) {
            case 1:
                p.addCircle(px / 2f, px / 2f, px / 2f, Path.Direction.CW);
                break;
            case 2: {
                float big = px * 0.5f, small = px * 0.12f;
                p.addRoundRect(r, new float[]{big, big, big, big, small, small, big, big}, Path.Direction.CW);
                break;
            }
            case 3:
                p.addRoundRect(r, px * 0.1f, px * 0.1f, Path.Direction.CW);
                break;
            default:
                p.addRoundRect(r, px * 0.235f, px * 0.235f, Path.Direction.CW);
        }
        return p;
    }

    public float outlineRadius(int px) {
        switch (shape()) {
            case 1: return px / 2f;
            case 2: return px * 0.35f;
            case 3: return px * 0.1f;
            default: return px * 0.235f;
        }
    }

    // ---------- paczki ikon ----------

    /** Zainstalowane paczki ikon (zgodne z Nova / ADW). */
    public static LinkedHashMap<String, String> installedPacks(Context c) {
        LinkedHashMap<String, String> out = new LinkedHashMap<>();
        PackageManager pm = c.getPackageManager();
        String[] actions = {"org.adw.launcher.THEMES", "com.novalauncher.THEME", "com.gau.go.launcherex.theme",
                "com.teslacoilsw.launcher.THEME"};
        for (String a : actions) {
            try {
                for (ResolveInfo ri : pm.queryIntentActivities(new Intent(a), 0)) {
                    String pkg = ri.activityInfo.packageName;
                    if (!out.containsKey(pkg)) out.put(pkg, ri.loadLabel(pm).toString());
                }
            } catch (Exception ignored) {
            }
        }
        return out;
    }

    private synchronized void ensurePack() {
        String want = prefs.getString("icon_pack", "");
        if (want.equals(packName == null ? "" : packName) && (want.isEmpty() || packMap != null)) return;
        packName = want.isEmpty() ? null : want;
        packMap = null;
        packRes = null;
        if (packName == null) return;
        try {
            packRes = ctx.getPackageManager().getResourcesForApplication(packName);
            Map<String, String> map = new HashMap<>();
            int id = packRes.getIdentifier("appfilter", "xml", packName);
            XmlPullParser xp = null;
            InputStream is = null;
            if (id != 0) {
                xp = packRes.getXml(id);
            } else {
                try {
                    is = packRes.getAssets().open("appfilter.xml");
                    xp = android.util.Xml.newPullParser();
                    xp.setInput(is, "utf-8");
                } catch (Exception ignored) {
                }
            }
            if (xp != null) {
                int ev = xp.getEventType();
                while (ev != XmlPullParser.END_DOCUMENT) {
                    if (ev == XmlPullParser.START_TAG && "item".equals(xp.getName())) {
                        String comp = xp.getAttributeValue(null, "component");
                        String draw = xp.getAttributeValue(null, "drawable");
                        if (comp != null && draw != null && comp.startsWith("ComponentInfo{")) {
                            String c = comp.substring(14, comp.length() - (comp.endsWith("}") ? 1 : 0));
                            map.put(c, draw);
                            String pkg = c.contains("/") ? c.substring(0, c.indexOf('/')) : c;
                            if (!map.containsKey(pkg)) map.put(pkg, draw);
                        }
                    }
                    ev = xp.next();
                }
                if (xp instanceof XmlResourceParser) ((XmlResourceParser) xp).close();
            }
            if (is != null) is.close();
            packMap = map;
        } catch (Exception e) {
            packMap = new HashMap<>();
        }
    }

    private Drawable packIcon(LauncherActivityInfo info) {
        ensurePack();
        if (packMap == null || packRes == null || packName == null) return null;
        String full = info.getComponentName().flattenToString();
        String name = packMap.get(full);
        if (name == null) {
            String pkg = info.getComponentName().getPackageName();
            String cls = info.getComponentName().getClassName();
            String shortCls = cls.startsWith(pkg) ? cls.substring(pkg.length()) : cls;
            name = packMap.get(pkg + "/" + shortCls);
            if (name == null) name = packMap.get(pkg);
        }
        if (name == null) return null;
        try {
            int id = packRes.getIdentifier(name, "drawable", packName);
            if (id == 0) return null;
            return packRes.getDrawableForDensity(id, density, null);
        } catch (Exception e) {
            return null;
        }
    }

    // ---------- własne ikony ----------

    private static String fileFor(String key) {
        return "icon_" + Integer.toHexString(key.hashCode()) + ".png";
    }

    public boolean hasCustom(String key) {
        return new File(new File(ctx.getFilesDir(), "icons"), fileFor(key)).exists();
    }

    public void saveCustom(String key, Bitmap src) throws Exception {
        int s = Math.min(src.getWidth(), src.getHeight());
        Bitmap sq = Bitmap.createBitmap(src, (src.getWidth() - s) / 2, (src.getHeight() - s) / 2, s, s);
        Bitmap out = Bitmap.createScaledBitmap(sq, 256, 256, true);
        File dir = new File(ctx.getFilesDir(), "icons");
        if (!dir.exists()) dir.mkdirs();
        try (FileOutputStream fo = new FileOutputStream(new File(dir, fileFor(key)))) {
            out.compress(Bitmap.CompressFormat.PNG, 100, fo);
        }
    }

    public void removeCustom(String key) {
        File f = new File(new File(ctx.getFilesDir(), "icons"), fileFor(key));
        if (f.exists()) f.delete();
    }

    private Bitmap loadCustom(String key) {
        File f = new File(new File(ctx.getFilesDir(), "icons"), fileFor(key));
        if (!f.exists()) return null;
        return BitmapFactory.decodeFile(f.getAbsolutePath());
    }

    // ---------- renderowanie ----------

    public Bitmap make(LauncherActivityInfo info, UserHandle user, String key, int px) {
        Bitmap b = Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b);
        Path clip = shapePath(px);
        int st = style();

        Bitmap custom = loadCustom(key);
        if (custom != null) {
            c.save();
            c.clipPath(clip);
            Paint p = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG);
            c.drawBitmap(custom, null, new RectF(0, 0, px, px), p);
            c.restore();
            return badge(b, user, px);
        }

        Drawable packed = packIcon(info);
        if (packed != null) {
            // ikony z paczki mają już swój kształt
            packed.setBounds(0, 0, px, px);
            if (st == 1) packed.setColorFilter(grayscale());
            packed.draw(c);
            return badge(b, user, px);
        }

        Drawable d = null;
        try {
            d = info.getIcon(0);
        } catch (Exception ignored) {
        }

        if (st == 1 || st == 2 || st == 3) {
            int[] pal = st == 3 ? wallPalette() : null;
            if (pal != null) {
                Paint bgp = new Paint(Paint.ANTI_ALIAS_FLAG);
                bgp.setShader(new LinearGradient(0, 0, 0, px, pal[0], pal[1], Shader.TileMode.CLAMP));
                c.drawPath(clip, bgp);
            } else {
                drawTintedBase(c, clip, px, st == 2);
            }
            int glyphColor = pal != null ? pal[2] : (st == 2 ? 0xFF8AB4FF : Color.WHITE);
            Drawable mono = null;
            if (Build.VERSION.SDK_INT >= 33 && d instanceof AdaptiveIconDrawable) {
                mono = ((AdaptiveIconDrawable) d).getMonochrome();
            }
            if (mono != null) {
                // jednokolorowy symbol aplikacji (Android 13+)
                AdaptiveIconDrawable a = (AdaptiveIconDrawable) d;
                a.setBounds(0, 0, px, px);
                Drawable m = mono.mutate();
                m.setBounds(a.getForeground().getBounds());
                m.setColorFilter(new PorterDuffColorFilter(glyphColor, PorterDuff.Mode.SRC_IN));
                c.save();
                c.clipPath(clip);
                m.draw(c);
                c.restore();
            } else {
                // brak symbolu – zwykła ikona, wyszarzona (szklane) lub przyciemniona (ciemne)
                Bitmap normal = renderNormal(d, px, clip);
                Paint p = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
                p.setColorFilter(pal != null ? tintTo(glyphColor) : (st == 1 ? grayscale() : dim()));
                int in = Math.round(px * 0.16f);
                c.save();
                c.clipPath(clip);
                c.drawBitmap(normal, null, new RectF(in, in, px - in, px - in), p);
                c.restore();
            }
            if (pal == null || pal[3] == 0) drawRim(c, clip, px);
            else drawLightRim(c, clip, px);
            return badge(b, user, px);
        }

        Bitmap normal = renderNormal(d, px, clip);
        c.drawBitmap(normal, 0, 0, null);
        return badge(b, user, px);
    }

    private Bitmap renderNormal(Drawable d, int px, Path clip) {
        Bitmap b = Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b);
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
        return b;
    }

    private void drawTintedBase(Canvas c, Path clip, int px, boolean dark) {
        int t = GlassDrawable.tint;
        float[] hsv = new float[3];
        Color.colorToHSV(t, hsv);
        int top, bottom;
        if (dark) {
            top = 0xFF22232A;
            bottom = 0xFF0E0F13;
        } else {
            hsv[2] = Math.min(1f, hsv[2] + 0.28f);
            hsv[1] = Math.min(1f, hsv[1] * 0.8f);
            top = Color.HSVToColor(0xF0, hsv);
            hsv[2] = Math.max(0f, hsv[2] - 0.22f);
            bottom = Color.HSVToColor(0xF0, hsv);
        }
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setShader(new LinearGradient(0, 0, 0, px, top, bottom, Shader.TileMode.CLAMP));
        c.drawPath(clip, p);
    }

    private void drawRim(Canvas c, Path clip, int px) {
        Paint rim = new Paint(Paint.ANTI_ALIAS_FLAG);
        rim.setStyle(Paint.Style.STROKE);
        rim.setStrokeWidth(Math.max(1f, px * 0.022f));
        rim.setShader(new LinearGradient(0, 0, px, px, new int[]{0x99FFFFFF, 0x14FFFFFF, 0x40FFFFFF},
                null, Shader.TileMode.CLAMP));
        c.save();
        c.clipPath(clip);
        c.drawPath(clip, rim);
        c.restore();
    }

    private void drawLightRim(Canvas c, Path clip, int px) {
        Paint rim = new Paint(Paint.ANTI_ALIAS_FLAG);
        rim.setStyle(Paint.Style.STROKE);
        rim.setStrokeWidth(Math.max(1f, px * 0.02f));
        rim.setShader(new LinearGradient(0, 0, 0, px, 0xCCFFFFFF, 0x1F000000, Shader.TileMode.CLAMP));
        c.save();
        c.clipPath(clip);
        c.drawPath(clip, rim);
        c.restore();
    }

    private static ColorMatrixColorFilter grayscale() {
        ColorMatrix m = new ColorMatrix();
        m.setSaturation(0f);
        ColorMatrix bright = new ColorMatrix(new float[]{
                1.15f, 0, 0, 0, 20,
                0, 1.15f, 0, 0, 20,
                0, 0, 1.15f, 0, 20,
                0, 0, 0, 1, 0});
        m.postConcat(bright);
        return new ColorMatrixColorFilter(m);
    }

    private static ColorMatrixColorFilter dim() {
        ColorMatrix m = new ColorMatrix();
        m.setSaturation(0.75f);
        ColorMatrix d = new ColorMatrix(new float[]{
                0.8f, 0, 0, 0, 0,
                0, 0.8f, 0, 0, 0,
                0, 0, 0.8f, 0, 0,
                0, 0, 0, 1, 0});
        m.postConcat(d);
        return new ColorMatrixColorFilter(m);
    }

    private Bitmap badge(Bitmap b, UserHandle user, int px) {
        if (user.equals(android.os.Process.myUserHandle())) return b;
        try {
            Drawable badged = ctx.getPackageManager().getUserBadgedIcon(new BitmapDrawable(ctx.getResources(), b), user);
            Bitmap b2 = Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888);
            Canvas c2 = new Canvas(b2);
            badged.setBounds(0, 0, px, px);
            badged.draw(c2);
            return b2;
        } catch (Exception e) {
            return b;
        }
    }

    /** Lista do wyboru w ustawieniach. */
    public static List<String[]> packChoices(Context c) {
        List<String[]> out = new ArrayList<>();
        out.add(new String[]{"", "Brak (ikony systemowe)"});
        for (Map.Entry<String, String> e : installedPacks(c).entrySet()) {
            out.add(new String[]{e.getKey(), e.getValue()});
        }
        return out;
    }
}
