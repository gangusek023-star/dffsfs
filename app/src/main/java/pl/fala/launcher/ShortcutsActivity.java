package pl.fala.launcher;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.LauncherActivityInfo;
import android.content.pm.LauncherApps;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.UserHandle;
import android.os.UserManager;
import android.text.InputType;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.GridLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.FileOutputStream;
import java.io.InputStream;
import java.text.Collator;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/** Aplikacja „Skróty”: lista skrótów i edytor akcji. */
public class ShortcutsActivity extends Activity {

    private static final int REQ_ICON = 61;
    private FrameLayout root;
    private List<Shortcuts.Shortcut> list;
    private Shortcuts.Shortcut editing;
    private boolean isNew;

    private int dp(float v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private TextView tv(CharSequence s, float sp, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        t.setTypeface(Typeface.create(bold ? "sans-serif-medium" : "sans-serif", Typeface.NORMAL));
        return t;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setStatusBarColor(0xFF000000);
        getWindow().setNavigationBarColor(0xFF000000);
        root = new FrameLayout(this);
        root.setBackgroundColor(0xFF000000);
        setContentView(root);
        showList();
    }

    @Override
    public void onBackPressed() {
        if (editing != null) {
            editing = null;
            showList();
        } else super.onBackPressed();
    }

    // ---------- ikona skrótu ----------

    /** Ikona: własne zdjęcie albo kolorowy kafelek z symbolem, w kształcie z „Wyglądu ikon”. */
    static Bitmap icon(Context c, Shortcuts.Shortcut s, int px) {
        SharedPreferences p = c.getSharedPreferences("fala", Context.MODE_PRIVATE);
        Path clip = new IconFactory(c, p).shapePath(px);
        Bitmap out = Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888);
        Canvas cv = new Canvas(out);
        java.io.File f = Shortcuts.iconFile(c, s.id);
        if (f.exists()) {
            Bitmap b = BitmapFactory.decodeFile(f.getAbsolutePath());
            if (b != null) {
                cv.save();
                cv.clipPath(clip);
                cv.drawBitmap(b, null, new android.graphics.RectF(0, 0, px, px), new Paint(Paint.FILTER_BITMAP_FLAG));
                cv.restore();
                return out;
            }
        }
        Paint bg = new Paint(Paint.ANTI_ALIAS_FLAG);
        float[] hsv = new float[3];
        Color.colorToHSV(s.color, hsv);
        hsv[2] = Math.min(1f, hsv[2] * 1.2f);
        hsv[1] *= 0.85f;
        bg.setShader(new LinearGradient(0, 0, px, px, Color.HSVToColor(hsv), s.color, Shader.TileMode.CLAMP));
        cv.drawPath(clip, bg);
        Paint t = new Paint(Paint.ANTI_ALIAS_FLAG);
        t.setColor(Color.WHITE);
        t.setTextAlign(Paint.Align.CENTER);
        t.setTextSize(px * 0.46f);
        String g = TextUtils.isEmpty(s.glyph) ? "✦" : s.glyph;
        cv.drawText(g, px / 2f, px / 2f - (t.descent() + t.ascent()) / 2f, t);
        return out;
    }

    // ---------- lista ----------

    private void showList() {
        list = Shortcuts.load(this);
        root.removeAllViews();
        ScrollView sv = new ScrollView(this);
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(dp(16), dp(24), dp(16), dp(30));
        sv.addView(col);
        LinearLayout head = new LinearLayout(this);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.addView(tv("Skróty", 34, Color.WHITE, true), new LinearLayout.LayoutParams(0, -2, 1f));
        TextView add = tv("+", 30, 0xFF4A9DFF, false);
        add.setPadding(dp(14), 0, dp(6), 0);
        add.setOnClickListener(v -> edit(null));
        head.addView(add);
        col.addView(head);
        col.addView(tv("Dotknij, aby uruchomić · ⋯ aby edytować", 13, 0x80FFFFFF, false));

        GridLayout grid = new GridLayout(this);
        grid.setColumnCount(2);
        LinearLayout.LayoutParams glp = new LinearLayout.LayoutParams(-1, -2);
        glp.topMargin = dp(16);
        col.addView(grid, glp);
        int w = (getResources().getDisplayMetrics().widthPixels - dp(32) - dp(12)) / 2;
        for (Shortcuts.Shortcut s : list) grid.addView(card(s), cardLp(w));
        // kafelek „nowy skrót”
        LinearLayout plus = new LinearLayout(this);
        plus.setGravity(Gravity.CENTER);
        GradientDrawable pg = new GradientDrawable();
        pg.setCornerRadius(dp(22));
        pg.setStroke(dp(2), 0x40FFFFFF, dp(8), dp(6));
        plus.setBackground(pg);
        plus.addView(tv("＋  Nowy skrót", 16, 0xB3FFFFFF, true));
        plus.setOnClickListener(v -> edit(null));
        grid.addView(plus, cardLp(w));

        if (list.isEmpty()) {
            TextView hint = tv("Skrót to przycisk, który wykonuje kilka czynności naraz – np. „Dobranoc”: "
                    + "wycisz muzykę, ustaw budzik na 7:00 i powiedz „Dobranoc”. Możesz dać mu własną ikonę "
                    + "i położyć go na ekranie głównym.", 14, 0x99FFFFFF, false);
            hint.setLineSpacing(dp(3), 1f);
            hint.setPadding(dp(4), dp(20), dp(4), 0);
            col.addView(hint);
            TextView ex = tv("Dodaj przykładowe skróty", 15, 0xFF4A9DFF, true);
            ex.setPadding(dp(4), dp(14), dp(4), dp(4));
            ex.setOnClickListener(v -> {
                addExamples();
                showList();
            });
            col.addView(ex);
        }
        root.addView(sv, new FrameLayout.LayoutParams(-1, -1));
    }

    private GridLayout.LayoutParams cardLp(int w) {
        GridLayout.LayoutParams lp = new GridLayout.LayoutParams();
        lp.width = w;
        lp.height = dp(116);
        lp.setMargins(0, 0, dp(12), dp(12));
        return lp;
    }

    private View card(Shortcuts.Shortcut s) {
        FrameLayout f = new FrameLayout(this);
        float[] hsv = new float[3];
        Color.colorToHSV(s.color, hsv);
        hsv[2] = Math.min(1f, hsv[2] * 1.2f);
        GradientDrawable g = new GradientDrawable(GradientDrawable.Orientation.TL_BR, new int[]{Color.HSVToColor(hsv), s.color});
        g.setCornerRadius(dp(22));
        f.setBackground(g);
        f.setForeground(new android.graphics.drawable.RippleDrawable(
                android.content.res.ColorStateList.valueOf(0x33FFFFFF), null, null));
        ImageView ic = new ImageView(this);
        java.io.File file = Shortcuts.iconFile(this, s.id);
        if (file.exists()) ic.setImageBitmap(icon(this, s, dp(36)));
        else {
            TextView gl = tv(TextUtils.isEmpty(s.glyph) ? "✦" : s.glyph, 24, Color.WHITE, false);
            FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.START);
            lp.setMargins(dp(14), dp(10), 0, 0);
            f.addView(gl, lp);
        }
        if (file.exists()) {
            FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(dp(36), dp(36), Gravity.TOP | Gravity.START);
            lp.setMargins(dp(14), dp(14), 0, 0);
            f.addView(ic, lp);
        }
        TextView more = tv("⋯", 22, Color.WHITE, true);
        more.setGravity(Gravity.CENTER);
        more.setBackground(circle(0x33000000));
        more.setOnClickListener(v -> edit(s));
        FrameLayout.LayoutParams mlp = new FrameLayout.LayoutParams(dp(32), dp(32), Gravity.TOP | Gravity.END);
        mlp.setMargins(0, dp(10), dp(10), 0);
        f.addView(more, mlp);
        TextView name = tv(s.name, 16, Color.WHITE, true);
        name.setMaxLines(2);
        name.setEllipsize(TextUtils.TruncateAt.END);
        FrameLayout.LayoutParams nlp = new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM);
        nlp.setMargins(dp(14), 0, dp(10), dp(12));
        f.addView(name, nlp);
        f.setOnClickListener(v -> {
            v.animate().scaleX(0.95f).scaleY(0.95f).setDuration(80).withEndAction(() ->
                    v.animate().scaleX(1f).scaleY(1f).setDuration(140).start()).start();
            Shortcuts.run(this, s);
        });
        return f;
    }

    private GradientDrawable circle(int c) {
        GradientDrawable g = new GradientDrawable();
        g.setShape(GradientDrawable.OVAL);
        g.setColor(c);
        return g;
    }

    private void addExamples() {
        Shortcuts.Shortcut a = new Shortcuts.Shortcut();
        a.name = "Dobranoc";
        a.color = Shortcuts.COLORS[6];
        a.glyph = "🌙";
        a.actions.add(act("media", "", ""));
        a.actions.add(act("alarm", "7:00", ""));
        a.actions.add(act("say", "Dobranoc! Budzik ustawiony na siódmą.", ""));
        Shortcuts.Shortcut b = new Shortcuts.Shortcut();
        b.name = "Latarka";
        b.color = Shortcuts.COLORS[2];
        b.glyph = "🔦";
        b.actions.add(act("torch", "toggle", ""));
        Shortcuts.Shortcut c = new Shortcuts.Shortcut();
        c.name = "Herbata";
        c.color = Shortcuts.COLORS[3];
        c.glyph = "🍵";
        c.actions.add(act("timer", "4", ""));
        c.actions.add(act("toast", "Minutnik: 4 minuty ☕", ""));
        list.add(a);
        list.add(b);
        list.add(c);
        Shortcuts.save(this, list);
    }

    private static Shortcuts.Action act(String t, String a, String b) {
        Shortcuts.Action x = new Shortcuts.Action();
        x.type = t;
        x.a = a;
        x.b = b;
        return x;
    }

    // ---------- edytor ----------

    private void edit(Shortcuts.Shortcut s) {
        isNew = s == null;
        if (s == null) {
            s = new Shortcuts.Shortcut();
            s.color = Shortcuts.COLORS[(int) (Math.random() * Shortcuts.COLORS.length)];
        }
        editing = s;
        renderEditor();
    }

    private void renderEditor() {
        Shortcuts.Shortcut s = editing;
        root.removeAllViews();
        ScrollView sv = new ScrollView(this);
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(dp(16), dp(18), dp(16), dp(40));
        sv.addView(col);

        LinearLayout bar = new LinearLayout(this);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        TextView cancel = tv("Anuluj", 17, 0xFF4A9DFF, false);
        cancel.setOnClickListener(v -> {
            editing = null;
            showList();
        });
        bar.addView(cancel, new LinearLayout.LayoutParams(0, -2, 1f));
        TextView done = tv("Gotowe", 17, 0xFF4A9DFF, true);
        done.setOnClickListener(v -> saveEditing());
        bar.addView(done);
        col.addView(bar);

        // podgląd ikony
        ImageView preview = new ImageView(this);
        preview.setImageBitmap(icon(this, s, dp(96)));
        LinearLayout.LayoutParams plp = new LinearLayout.LayoutParams(dp(96), dp(96));
        plp.gravity = Gravity.CENTER_HORIZONTAL;
        plp.topMargin = dp(18);
        col.addView(preview, plp);

        EditText name = new EditText(this);
        name.setText(s.name);
        name.setTextColor(Color.WHITE);
        name.setTextSize(TypedValue.COMPLEX_UNIT_SP, 22);
        name.setGravity(Gravity.CENTER);
        name.setSingleLine(true);
        name.setBackground(null);
        name.setOnFocusChangeListener((v, f) -> {
            if (!f) s.name = name.getText().toString().trim().isEmpty() ? "Skrót" : name.getText().toString().trim();
        });
        name.addTextChangedListener(new android.text.TextWatcher() {
            @Override public void beforeTextChanged(CharSequence x, int a, int b, int c) { }
            @Override public void onTextChanged(CharSequence x, int a, int b, int c) { }
            @Override public void afterTextChanged(android.text.Editable e) { s.name = e.toString().trim().isEmpty() ? "Skrót" : e.toString().trim(); }
        });
        col.addView(name, new LinearLayout.LayoutParams(-1, -2));

        // kolory
        LinearLayout colors = new LinearLayout(this);
        colors.setGravity(Gravity.CENTER);
        for (int c : Shortcuts.COLORS) {
            View dot = new View(this);
            GradientDrawable g = circle(c);
            if (c == s.color) g.setStroke(dp(3), Color.WHITE);
            dot.setBackground(g);
            dot.setOnClickListener(v -> {
                s.color = c;
                renderEditor();
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(26), dp(26));
            lp.setMargins(dp(4), dp(8), dp(4), dp(8));
            colors.addView(dot, lp);
        }
        col.addView(colors);

        LinearLayout iconRow = new LinearLayout(this);
        iconRow.setGravity(Gravity.CENTER);
        TextView glyph = chip("Symbol: " + s.glyph);
        glyph.setOnClickListener(v -> askText("Symbol (emoji lub znak)", s.glyph, InputType.TYPE_CLASS_TEXT, val -> {
            s.glyph = val.isEmpty() ? "✦" : val.substring(0, Math.min(val.length(), 4));
            renderEditor();
        }));
        iconRow.addView(glyph);
        TextView photo = chip(Shortcuts.iconFile(this, s.id).exists() ? "Usuń zdjęcie" : "Zdjęcie jako ikona");
        photo.setOnClickListener(v -> {
            java.io.File f = Shortcuts.iconFile(this, s.id);
            if (f.exists()) {
                //noinspection ResultOfMethodCallIgnored
                f.delete();
                renderEditor();
            } else {
                Intent i = new Intent(Intent.ACTION_GET_CONTENT).setType("image/*").addCategory(Intent.CATEGORY_OPENABLE);
                try {
                    startActivityForResult(i, REQ_ICON);
                } catch (Exception ignored) {
                }
            }
        });
        LinearLayout.LayoutParams phl = new LinearLayout.LayoutParams(-2, -2);
        phl.leftMargin = dp(8);
        iconRow.addView(photo, phl);
        col.addView(iconRow);

        TextView at = tv("AKCJE", 12, 0x80FFFFFF, true);
        at.setLetterSpacing(0.12f);
        at.setPadding(dp(4), dp(22), 0, dp(8));
        col.addView(at);
        for (int i = 0; i < s.actions.size(); i++) col.addView(actionRow(s, i));
        if (s.actions.isEmpty()) {
            TextView e = tv("Brak akcji – dodaj pierwszą.", 14, 0x80FFFFFF, false);
            e.setPadding(dp(4), 0, 0, dp(6));
            col.addView(e);
        }
        TextView addA = tv("＋  Dodaj akcję", 16, 0xFF4A9DFF, true);
        addA.setGravity(Gravity.CENTER);
        addA.setPadding(0, dp(14), 0, dp(14));
        addA.setBackground(round(0xFF1C1C1E, dp(16)));
        addA.setOnClickListener(v -> pickAction());
        LinearLayout.LayoutParams alp = new LinearLayout.LayoutParams(-1, -2);
        alp.topMargin = dp(4);
        col.addView(addA, alp);

        TextView run = big("▶  Uruchom", 0xFF2ED47A);
        run.setOnClickListener(v -> Shortcuts.run(this, s));
        col.addView(run, bigLp(dp(24)));
        TextView home = big("🏠  Dodaj do ekranu głównego", 0xFF4A7DFF);
        home.setOnClickListener(v -> {
            saveEditing(false);
            SharedPreferences p = getSharedPreferences("fala", MODE_PRIVATE);
            HomeModel m = new HomeModel(p);
            m.add(HomeModel.SHORTCUT, s.id, null, 1, 1, 0);
            m.save();
            Toast.makeText(this, "Dodano „" + s.name + "” na ekran główny", Toast.LENGTH_SHORT).show();
        });
        col.addView(home, bigLp(dp(10)));
        if (!isNew) {
            TextView del = big("Usuń skrót", 0xFF3A1C1C);
            del.setTextColor(0xFFFF6B6B);
            del.setOnClickListener(v -> new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                    .setTitle("Usunąć „" + s.name + "”?")
                    .setPositiveButton("Usuń", (d, w) -> {
                        List<Shortcuts.Shortcut> all = Shortcuts.load(this);
                        for (int i = all.size() - 1; i >= 0; i--) if (all.get(i).id.equals(s.id)) all.remove(i);
                        Shortcuts.save(this, all);
                        //noinspection ResultOfMethodCallIgnored
                        Shortcuts.iconFile(this, s.id).delete();
                        editing = null;
                        showList();
                    }).setNegativeButton("Anuluj", null).show());
            col.addView(del, bigLp(dp(10)));
        }
        root.addView(sv, new FrameLayout.LayoutParams(-1, -1));
    }

    private LinearLayout.LayoutParams bigLp(int top) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.topMargin = top;
        return lp;
    }

    private TextView big(String s, int bg) {
        TextView t = tv(s, 16, Color.WHITE, true);
        t.setGravity(Gravity.CENTER);
        t.setPadding(0, dp(15), 0, dp(15));
        t.setBackground(round(bg, dp(18)));
        return t;
    }

    private TextView chip(String s) {
        TextView t = tv(s, 14, Color.WHITE, true);
        t.setPadding(dp(14), dp(8), dp(14), dp(8));
        t.setBackground(round(0xFF1C1C1E, dp(16)));
        return t;
    }

    private GradientDrawable round(int c, float r) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(c);
        g.setCornerRadius(r);
        return g;
    }

    private View actionRow(Shortcuts.Shortcut s, int i) {
        Shortcuts.Action a = s.actions.get(i);
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(14), dp(12), dp(8), dp(12));
        row.setBackground(round(0xFF1C1C1E, dp(16)));
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.addView(tv(Shortcuts.label(a.type), 16, Color.WHITE, true));
        String sum = summary(a);
        if (!sum.isEmpty()) {
            TextView st = tv(sum, 14, 0xFF4A9DFF, false);
            st.setPadding(0, dp(2), 0, 0);
            c.addView(st);
        }
        row.addView(c, new LinearLayout.LayoutParams(0, -2, 1f));
        if (i > 0) {
            TextView up = tv("↑", 18, 0x99FFFFFF, true);
            up.setPadding(dp(10), dp(4), dp(10), dp(4));
            up.setOnClickListener(v -> {
                Collections.swap(s.actions, i, i - 1);
                renderEditor();
            });
            row.addView(up);
        }
        TextView rm = tv("✕", 16, 0x99FFFFFF, true);
        rm.setPadding(dp(10), dp(4), dp(10), dp(4));
        rm.setOnClickListener(v -> {
            s.actions.remove(i);
            renderEditor();
        });
        row.addView(rm);
        row.setOnClickListener(v -> configure(a, false));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.bottomMargin = dp(8);
        row.setLayoutParams(lp);
        return row;
    }

    private String summary(Shortcuts.Action a) {
        switch (a.type) {
            case "app": return appLabel(a.a);
            case "sms": return a.a + (a.b.isEmpty() ? "" : " · „" + a.b + "”");
            case "torch": return "on".equals(a.a) ? "włącz" : "off".equals(a.a) ? "wyłącz" : "przełącz";
            case "timer": return a.a + " min";
            case "volume": return a.a + "%";
            case "wait": return a.a + " s";
            case "settings":
                for (String[] st : Shortcuts.SETTINGS) if (st[0].equals(a.a)) return st[1];
                return "";
            default: return a.a;
        }
    }

    private String appLabel(String key) {
        for (String[] e : apps()) if (e[1].equals(key)) return e[0];
        return "wybierz aplikację";
    }

    private List<String[]> appsCache;

    /** [nazwa, klucz] wszystkich aplikacji. */
    private List<String[]> apps() {
        if (appsCache != null) return appsCache;
        List<String[]> out = new ArrayList<>();
        LauncherApps la = (LauncherApps) getSystemService(Context.LAUNCHER_APPS_SERVICE);
        UserManager um = (UserManager) getSystemService(Context.USER_SERVICE);
        for (UserHandle u : um.getUserProfiles()) {
            for (LauncherActivityInfo i : la.getActivityList(null, u)) {
                out.add(new String[]{i.getLabel().toString(), i.getComponentName().flattenToString() + "#" + u.hashCode()});
            }
        }
        Collator col = Collator.getInstance(new Locale("pl", "PL"));
        out.sort((x, y) -> col.compare(x[0], y[0]));
        appsCache = out;
        return out;
    }

    private void pickAction() {
        String[] names = new String[Shortcuts.ACTIONS.length];
        for (int i = 0; i < names.length; i++) names[i] = Shortcuts.ACTIONS[i][2] + "   " + Shortcuts.ACTIONS[i][1];
        new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle("Dodaj akcję")
                .setItems(names, (d, w) -> {
                    Shortcuts.Action a = act(Shortcuts.ACTIONS[w][0], "", "");
                    configure(a, true);
                }).show();
    }

    interface Val {
        void on(String v);
    }

    private void askText(String title, String value, int type, Val cb) {
        EditText e = new EditText(this);
        e.setText(value);
        e.setInputType(type);
        e.setSelectAllOnFocus(true);
        FrameLayout f = new FrameLayout(this);
        f.setPadding(dp(20), dp(8), dp(20), 0);
        f.addView(e);
        new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle(title).setView(f)
                .setPositiveButton("OK", (d, w) -> cb.on(e.getText().toString().trim()))
                .setNegativeButton("Anuluj", null).show();
        e.requestFocus();
    }

    /** Ustawienia akcji; dla nowej akcji – po zatwierdzeniu dodaje ją do skrótu. */
    private void configure(Shortcuts.Action a, boolean add) {
        Runnable commit = () -> {
            if (add) editing.actions.add(a);
            renderEditor();
        };
        switch (a.type) {
            case "app": {
                List<String[]> l = apps();
                String[] names = new String[l.size()];
                for (int i = 0; i < names.length; i++) names[i] = l.get(i)[0];
                new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                        .setTitle("Którą aplikację?")
                        .setItems(names, (d, w) -> {
                            a.a = l.get(w)[1];
                            commit.run();
                        }).show();
                break;
            }
            case "url": askText("Adres strony", a.a, InputType.TYPE_TEXT_VARIATION_URI, v -> { a.a = v; commit.run(); }); break;
            case "call": askText("Numer telefonu", a.a, InputType.TYPE_CLASS_PHONE, v -> { a.a = v; commit.run(); }); break;
            case "sms":
                askText("Numer telefonu", a.a, InputType.TYPE_CLASS_PHONE, v -> {
                    a.a = v;
                    askText("Treść (opcjonalnie)", a.b, InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES, t -> {
                        a.b = t;
                        commit.run();
                    });
                });
                break;
            case "torch":
                new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                        .setTitle("Latarka").setItems(new String[]{"Przełącz", "Włącz", "Wyłącz"}, (d, w) -> {
                            a.a = w == 1 ? "on" : w == 2 ? "off" : "toggle";
                            commit.run();
                        }).show();
                break;
            case "timer": askText("Ile minut?", a.a.isEmpty() ? "5" : a.a, InputType.TYPE_CLASS_NUMBER, v -> { a.a = v; commit.run(); }); break;
            case "alarm": askText("Godzina budzika (np. 7:30)", a.a.isEmpty() ? "7:00" : a.a, InputType.TYPE_CLASS_DATETIME | InputType.TYPE_DATETIME_VARIATION_TIME, v -> { a.a = v; commit.run(); }); break;
            case "say": askText("Co powiedzieć?", a.a, InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES, v -> { a.a = v; commit.run(); }); break;
            case "toast": askText("Treść komunikatu", a.a, InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES, v -> { a.a = v; commit.run(); }); break;
            case "volume": askText("Głośność muzyki (0–100%)", a.a.isEmpty() ? "50" : a.a, InputType.TYPE_CLASS_NUMBER, v -> { a.a = v; commit.run(); }); break;
            case "wait": askText("Ile sekund czekać?", a.a.isEmpty() ? "3" : a.a, InputType.TYPE_CLASS_NUMBER, v -> { a.a = v; commit.run(); }); break;
            case "maps": askText("Dokąd?", a.a, InputType.TYPE_CLASS_TEXT, v -> { a.a = v; commit.run(); }); break;
            case "settings": {
                String[] names = new String[Shortcuts.SETTINGS.length];
                for (int i = 0; i < names.length; i++) names[i] = Shortcuts.SETTINGS[i][1];
                new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                        .setTitle("Które ustawienia?").setItems(names, (d, w) -> {
                            a.a = Shortcuts.SETTINGS[w][0];
                            commit.run();
                        }).show();
                break;
            }
            default:
                commit.run();
        }
    }

    private void saveEditing() {
        saveEditing(true);
    }

    private void saveEditing(boolean close) {
        if (editing == null) return;
        List<Shortcuts.Shortcut> all = Shortcuts.load(this);
        boolean found = false;
        for (int i = 0; i < all.size(); i++) if (all.get(i).id.equals(editing.id)) {
            all.set(i, editing);
            found = true;
        }
        if (!found) all.add(editing);
        Shortcuts.save(this, all);
        isNew = false;
        if (close) {
            editing = null;
            showList();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQ_ICON || resultCode != RESULT_OK || data == null || data.getData() == null || editing == null) return;
        try {
            Uri u = data.getData();
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            try (InputStream is = getContentResolver().openInputStream(u)) {
                BitmapFactory.decodeStream(is, null, o);
            }
            int sample = 1;
            while (Math.min(o.outWidth, o.outHeight) / (sample * 2) >= 256) sample *= 2;
            BitmapFactory.Options o2 = new BitmapFactory.Options();
            o2.inSampleSize = sample;
            Bitmap b;
            try (InputStream is = getContentResolver().openInputStream(u)) {
                b = BitmapFactory.decodeStream(is, null, o2);
            }
            if (b == null) return;
            int s = Math.min(b.getWidth(), b.getHeight());
            Bitmap sq = Bitmap.createScaledBitmap(Bitmap.createBitmap(b, (b.getWidth() - s) / 2, (b.getHeight() - s) / 2, s, s), 256, 256, true);
            try (FileOutputStream fo = new FileOutputStream(Shortcuts.iconFile(this, editing.id))) {
                sq.compress(Bitmap.CompressFormat.PNG, 100, fo);
            }
            renderEditor();
        } catch (Exception e) {
            Toast.makeText(this, "Nie udało się wczytać zdjęcia", Toast.LENGTH_SHORT).show();
        }
    }
}
