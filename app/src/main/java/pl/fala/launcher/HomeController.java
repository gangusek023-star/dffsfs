package pl.fala.launcher;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.LauncherApps;
import android.content.pm.ShortcutInfo;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Outline;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Process;
import android.os.UserHandle;
import android.os.UserManager;
import android.text.InputType;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewOutlineProvider;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.OvershootInterpolator;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.GridLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Ekran główny jak w iOS: strony z siatką, widżety ze zdjęciami, zegar, foldery,
 * skróty i tryb edycji (drganie, przeciąganie, „−”, łączenie w foldery).
 */
public class HomeController implements HomeGrid.Listener {

    interface Host {
        MainActivity.App app(String key);

        void launch(MainActivity.App a, View v);

        void appMenu(MainActivity.App a);

        View clockWidget();

        void pickImage(int requestCode);

        void showSettings();

        void openSearch();

        IconFactory icons();

        void onEditChanged(boolean editing);

        void onEmptyDoubleTap();
    }

    static final int REQ_WIDGET_PHOTO = 71;

    private final Activity act;
    private final Host host;
    private final SharedPreferences prefs;
    private final FrameLayout overlay;
    final HomeModel model;
    private final float d;

    private LinearLayout root;
    private HomePager pager;
    private HomePager.Dots dots;
    private LinearLayout dotsPill;
    private final List<HomeGrid> grids = new ArrayList<>();
    private boolean editing = false;
    private View editBar;
    private View folderView;
    private HomeModel.Item folderItem;
    private HomeModel.Item pendingPhoto;

    HomeController(Activity act, Host host, SharedPreferences prefs, FrameLayout overlay) {
        this.act = act;
        this.host = host;
        this.prefs = prefs;
        this.overlay = overlay;
        this.model = new HomeModel(prefs);
        this.d = act.getResources().getDisplayMetrics().density;
    }

    private int dp(float v) {
        return Math.round(v * d);
    }

    View view() {
        root = new LinearLayout(act);
        root.setOrientation(LinearLayout.VERTICAL);
        pager = new HomePager(act);
        pager.setListener((page, offset) -> dots.set(pager.count(), offset));
        pager.setEmptyListener(new HomePager.EmptyListener() {
            @Override public void onLongPress() { if (!editing) setEditing(true); }
            @Override public void onDoubleTap() { if (!editing) host.onEmptyDoubleTap(); }
            @Override public void onTap() { if (editing) setEditing(false); }
        });
        root.addView(pager, new LinearLayout.LayoutParams(-1, 0, 1f));
        dotsPill = new LinearLayout(act);
        dotsPill.setGravity(Gravity.CENTER);
        dotsPill.setBackground(new GlassDrawable(d, dp(15), 0.9f));
        dots = new HomePager.Dots(act);
        dotsPill.addView(dots);
        dotsPill.setOnClickListener(v -> host.openSearch());
        LinearLayout.LayoutParams dl = new LinearLayout.LayoutParams(-2, dp(30));
        dl.gravity = Gravity.CENTER_HORIZONTAL;
        dl.topMargin = dp(4);
        dl.bottomMargin = dp(12);
        root.addView(dotsPill, dl);
        return root;
    }

    boolean editing() {
        return editing;
    }

    List<View> entranceViews() {
        List<View> out = new ArrayList<>();
        int p = pager.page();
        if (p < grids.size()) {
            HomeGrid g = grids.get(p);
            for (int i = 0; i < g.getChildCount(); i++) out.add(g.getChildAt(i));
        }
        out.add(dotsPill);
        return out;
    }

    /** Pierwsze uruchomienie: domyślne aplikacje trafiają na ekran, gdy są już znane. */
    void migrateIfFresh() {
        if (!model.fresh) return;
        model.fresh = false;
        String old = prefs.getString("home", "");
        for (String k : old.split("\n")) if (!k.isEmpty() && !model.hasApp(k)) model.addApp(k);
        model.save();
    }

    void reload() {
        model.load();
        rebuild();
    }

    // ---------- budowanie ----------

    void rebuild() {
        if (pager == null) return;
        int keep = pager.page();
        pager.removeAllViews();
        grids.clear();
        int count = model.pages + (editing ? 1 : 0);
        for (int p = 0; p < count; p++) {
            HomeGrid g = new HomeGrid(act);
            g.setListener(this);
            g.setTag(p);
            for (HomeModel.Item it : model.onPage(p)) {
                View v = itemView(it);
                if (v != null) g.add(v, it);
            }
            g.setEditing(editing);
            grids.add(g);
            pager.addView(g);
        }
        pager.requestLayout();
        int target = Math.min(keep, count - 1);
        pager.post(() -> {
            pager.setPage(target, false);
            dots.set(pager.count(), target);
        });
        dots.set(count, target);
    }

    private TextView label(String s) {
        TextView t = new TextView(act);
        t.setText(s);
        t.setTextColor(Color.WHITE);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        t.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        t.setSingleLine(true);
        t.setEllipsize(TextUtils.TruncateAt.END);
        t.setGravity(Gravity.CENTER);
        t.setShadowLayer(dp(3), 0, dp(1), 0x99000000);
        t.setPadding(dp(2), dp(5), dp(2), 0);
        return t;
    }

    private void press(View v) {
        v.setOnTouchListener((vv, e) -> {
            int a = e.getActionMasked();
            if (a == MotionEvent.ACTION_DOWN) vv.animate().setStartDelay(0).scaleX(0.88f).scaleY(0.88f).setDuration(110).start();
            else if (a == MotionEvent.ACTION_UP || a == MotionEvent.ACTION_CANCEL)
                vv.animate().setStartDelay(0).scaleX(1f).scaleY(1f).setDuration(300).setInterpolator(new OvershootInterpolator(2.2f)).start();
            return false;
        });
    }

    private void roundOutline(View v, float radius) {
        v.setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View view, Outline o) {
                o.setRoundRect(0, 0, view.getWidth(), view.getHeight(), radius);
            }
        });
        v.setClipToOutline(true);
    }

    /** Komórka 1×1: ikona + podpis. */
    private LinearLayout cell(Drawable icon, Bitmap bmp, String text, View.OnClickListener click, View.OnLongClickListener longClick) {
        LinearLayout c = new LinearLayout(act);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setGravity(Gravity.CENTER_HORIZONTAL);
        c.setPadding(0, dp(6), 0, 0);
        int s = dp(host.icons().homeDp());
        ImageView iv = new ImageView(act);
        if (bmp != null) iv.setImageBitmap(bmp);
        else iv.setImageDrawable(icon);
        iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
        iv.setElevation(dp(3));
        iv.setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View view, Outline o) {
                o.setRoundRect(0, 0, view.getWidth(), view.getHeight(), host.icons().outlineRadius(view.getWidth()));
            }
        });
        c.addView(iv, new LinearLayout.LayoutParams(s, s));
        TextView t = label(text);
        if (!host.icons().labels()) t.setVisibility(View.GONE);
        c.addView(t, new LinearLayout.LayoutParams(-1, -2));
        iv.setOnClickListener(click);
        iv.setOnLongClickListener(longClick);
        press(iv);
        return c;
    }

    private View itemView(HomeModel.Item it) {
        switch (it.type) {
            case HomeModel.APP: {
                MainActivity.App a = host.app(it.key);
                if (a == null) return null;
                return cell(null, a.icon, a.label, v -> host.launch(a, v), v -> {
                    host.appMenu(a);
                    return true;
                });
            }
            case HomeModel.FOLDER:
                return folderCell(it);
            case HomeModel.PHOTO:
                return photoWidget(it);
            case HomeModel.CLOCK: {
                FrameLayout f = new FrameLayout(act);
                f.setPadding(dp(6), dp(8), dp(6), dp(8));
                View w = host.clockWidget();
                f.addView(w, new FrameLayout.LayoutParams(-1, -1));
                return f;
            }
            case HomeModel.SHORTCUT: {
                Shortcuts.Shortcut s = Shortcuts.find(act, it.key);
                if (s == null) return null;
                Bitmap b = ShortcutsActivity.icon(act, s, dp(64));
                return cell(null, b, s.name, v -> Shortcuts.run(act, s), v -> {
                    shortcutMenu(it, s);
                    return true;
                });
            }
            case HomeModel.APPSC: {
                ShortcutInfo si = appShortcut(it.key);
                if (si == null) return null;
                Drawable ic = null;
                try {
                    ic = launcherApps().getShortcutIconDrawable(si, act.getResources().getDisplayMetrics().densityDpi);
                } catch (Exception ignored) {
                }
                CharSequence l = si.getShortLabel();
                return cell(ic, null, l == null ? "" : l.toString(), v -> {
                    try {
                        launcherApps().startShortcut(si, null, null);
                    } catch (Exception e) {
                        Toast.makeText(act, "Skrót jest niedostępny", Toast.LENGTH_SHORT).show();
                    }
                }, v -> {
                    setEditing(true);
                    return true;
                });
            }
        }
        return null;
    }

    // ---------- widżet zdjęcia ----------

    static File widgetFile(Context c, String name) {
        File dir = new File(c.getFilesDir(), "widgets");
        if (!dir.exists()) //noinspection ResultOfMethodCallIgnored
            dir.mkdirs();
        return new File(dir, name);
    }

    private View photoWidget(HomeModel.Item it) {
        LinearLayout c = new LinearLayout(act);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setPadding(dp(8), dp(8), dp(8), 0);
        FrameLayout frame = new FrameLayout(act);
        frame.setBackground(new GlassDrawable(d, dp(22), 1f));
        roundOutline(frame, dp(22));
        frame.setElevation(dp(4));
        ImageView iv = new ImageView(act);
        iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
        frame.addView(iv, new FrameLayout.LayoutParams(-1, -1));
        File f = it.key != null ? widgetFile(act, it.key) : null;
        if (f != null && f.exists()) {
            int maxPx = dp(it.w * 100);
            new Thread(() -> {
                Bitmap b = decode(f, maxPx);
                act.runOnUiThread(() -> iv.setImageBitmap(b));
            }).start();
        } else {
            TextView t = new TextView(act);
            t.setText("🖼\nDotknij, aby\nwybrać zdjęcie");
            t.setGravity(Gravity.CENTER);
            t.setTextColor(0xCCFFFFFF);
            t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
            frame.addView(t, new FrameLayout.LayoutParams(-1, -1));
            frame.setOnClickListener(v -> {
                pendingPhoto = it;
                host.pickImage(REQ_WIDGET_PHOTO);
            });
        }
        frame.setOnLongClickListener(v -> {
            setEditing(true);
            return true;
        });
        c.addView(frame, new LinearLayout.LayoutParams(-1, 0, 1f));
        TextView l = label(it.name == null ? "" : it.name);
        l.setPadding(0, dp(5), 0, dp(4));
        if (it.name == null || it.name.isEmpty()) l.setVisibility(View.INVISIBLE);
        c.addView(l, new LinearLayout.LayoutParams(-1, -2));
        return c;
    }

    private static Bitmap decode(File f, int maxPx) {
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(f.getAbsolutePath(), o);
        int s = 1;
        while (Math.min(o.outWidth, o.outHeight) / (s * 2) >= maxPx) s *= 2;
        BitmapFactory.Options o2 = new BitmapFactory.Options();
        o2.inSampleSize = s;
        return BitmapFactory.decodeFile(f.getAbsolutePath(), o2);
    }

    /** Zapis wybranego zdjęcia widżetu (zmniejszone do 1440 px). */
    boolean onActivityResult(int req, int res, Intent data) {
        if (req != REQ_WIDGET_PHOTO) return false;
        HomeModel.Item it = pendingPhoto;
        pendingPhoto = null;
        if (it == null || res != Activity.RESULT_OK || data == null || data.getData() == null) return true;
        Uri u = data.getData();
        new Thread(() -> {
            try {
                BitmapFactory.Options o = new BitmapFactory.Options();
                o.inJustDecodeBounds = true;
                try (InputStream is = act.getContentResolver().openInputStream(u)) {
                    BitmapFactory.decodeStream(is, null, o);
                }
                int s = 1;
                while (Math.max(o.outWidth, o.outHeight) / (s * 2) >= 1440) s *= 2;
                BitmapFactory.Options o2 = new BitmapFactory.Options();
                o2.inSampleSize = s;
                Bitmap b;
                try (InputStream is = act.getContentResolver().openInputStream(u)) {
                    b = BitmapFactory.decodeStream(is, null, o2);
                }
                if (b == null) throw new Exception();
                String name = "w_" + it.id + "_" + System.currentTimeMillis() + ".jpg";
                try (FileOutputStream fo = new FileOutputStream(widgetFile(act, name))) {
                    b.compress(Bitmap.CompressFormat.JPEG, 90, fo);
                }
                act.runOnUiThread(() -> {
                    if (it.key != null) //noinspection ResultOfMethodCallIgnored
                        widgetFile(act, it.key).delete();
                    it.key = name;
                    model.save();
                    rebuild();
                });
            } catch (Exception e) {
                act.runOnUiThread(() -> Toast.makeText(act, "Nie udało się wczytać zdjęcia", Toast.LENGTH_SHORT).show());
            }
        }).start();
        return true;
    }

    // ---------- foldery ----------

    /** Ikona folderu: szkło z miniaturami 3×3. */
    private View folderCell(HomeModel.Item it) {
        int s = dp(host.icons().homeDp());
        List<Bitmap> minis = new ArrayList<>();
        for (String k : it.apps) {
            MainActivity.App a = host.app(k);
            if (a != null && a.icon != null) minis.add(a.icon);
            if (minis.size() == 9) break;
        }
        Bitmap b = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888);
        Canvas cv = new Canvas(b);
        GlassDrawable g = new GlassDrawable(d, host.icons().outlineRadius(s), 1.2f);
        g.setBounds(0, 0, s, s);
        g.draw(cv);
        float pad = s * 0.14f, gap = s * 0.05f, m = (s - pad * 2 - gap * 2) / 3f;
        Paint p = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG);
        for (int i = 0; i < minis.size(); i++) {
            float x = pad + (i % 3) * (m + gap), y = pad + (i / 3) * (m + gap);
            cv.drawBitmap(minis.get(i), null, new RectF(x, y, x + m, y + m), p);
        }
        return cell(null, b, it.name == null ? "Folder" : it.name, v -> openFolder(it), v -> {
            setEditing(true);
            return true;
        });
    }

    private void openFolder(HomeModel.Item it) {
        closeFolder();
        folderItem = it;
        FrameLayout f = new FrameLayout(act);
        f.setBackgroundColor(0x99000000);
        f.setOnClickListener(v -> closeFolder());
        LinearLayout col = new LinearLayout(act);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setGravity(Gravity.CENTER_HORIZONTAL);
        col.setClickable(true);
        EditText name = new EditText(act);
        name.setText(it.name == null ? "Folder" : it.name);
        name.setTextColor(Color.WHITE);
        name.setTextSize(TypedValue.COMPLEX_UNIT_SP, 28);
        name.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        name.setGravity(Gravity.CENTER);
        name.setBackground(null);
        name.setSingleLine(true);
        name.setShadowLayer(dp(4), 0, dp(1), 0x99000000);
        name.setTag("name");
        col.addView(name, new LinearLayout.LayoutParams(-1, -2));
        GridLayout grid = new GridLayout(act);
        grid.setColumnCount(3);
        grid.setPadding(dp(14), dp(18), dp(14), dp(10));
        grid.setBackground(new GlassDrawable(d, dp(36), 1.4f));
        int cellW = (Math.min(act.getResources().getDisplayMetrics().widthPixels, dp(420)) - dp(40) - dp(28)) / 3;
        for (String k : new ArrayList<>(it.apps)) {
            MainActivity.App a = host.app(k);
            if (a == null) continue;
            LinearLayout c = cell(null, a.icon, a.label, v -> {
                closeFolder();
                host.launch(a, v);
            }, v -> {
                folderAppMenu(it, a);
                return true;
            });
            GridLayout.LayoutParams lp = new GridLayout.LayoutParams();
            lp.width = cellW;
            lp.height = cellW + dp(18);
            grid.addView(c, lp);
        }
        LinearLayout.LayoutParams glp = new LinearLayout.LayoutParams(-2, -2);
        glp.topMargin = dp(12);
        col.addView(grid, glp);
        FrameLayout.LayoutParams clp = new FrameLayout.LayoutParams(-1, -2, Gravity.CENTER);
        clp.leftMargin = dp(20);
        clp.rightMargin = dp(20);
        f.addView(col, clp);
        overlay.addView(f, new FrameLayout.LayoutParams(-1, -1));
        folderView = f;
        f.setAlpha(0f);
        f.animate().alpha(1f).setDuration(200).start();
        col.setScaleX(0.7f);
        col.setScaleY(0.7f);
        col.animate().scaleX(1f).scaleY(1f).setDuration(320).setInterpolator(new OvershootInterpolator(1.2f)).start();
    }

    private void folderAppMenu(HomeModel.Item folder, MainActivity.App a) {
        new AlertDialog.Builder(act, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle(a.label)
                .setItems(new String[]{"Wyjmij z folderu", "Więcej opcji…"}, (dlg, w) -> {
                    if (w == 0) {
                        folder.apps.remove(a.key);
                        if (folder.apps.size() <= 1) {
                            String last = folder.apps.isEmpty() ? null : folder.apps.get(0);
                            if (last != null) {
                                folder.type = HomeModel.APP;
                                folder.key = last;
                                folder.apps.clear();
                                folder.name = null;
                            } else model.items.remove(folder);
                        }
                        HomeModel.Item out = new HomeModel.Item();
                        out.type = HomeModel.APP;
                        out.key = a.key;
                        model.place(out, folder.page);
                        model.save();
                        closeFolder();
                        rebuild();
                    } else {
                        host.appMenu(a);
                    }
                }).show();
    }

    boolean closeFolder() {
        if (folderView == null) return false;
        View f = folderView;
        folderView = null;
        EditText name = f.findViewWithTag("name");
        if (folderItem != null && name != null) {
            String n = name.getText().toString().trim();
            folderItem.name = n.isEmpty() ? "Folder" : n;
            model.save();
            rebuild();
        }
        folderItem = null;
        f.animate().alpha(0f).setDuration(160).withEndAction(() -> overlay.removeView(f)).start();
        return true;
    }

    // ---------- tryb edycji ----------

    void setEditing(boolean e) {
        if (editing == e) return;
        editing = e;
        if (!e) {
            model.trimPages();
            model.save();
        }
        rebuild();
        host.onEditChanged(e);
        act.getWindow().getDecorView().performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS);
        if (e) showEditBar();
        else hideEditBar();
    }

    private TextView pill(String s) {
        TextView t = new TextView(act);
        t.setText(s);
        t.setTextColor(Color.WHITE);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        t.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        t.setGravity(Gravity.CENTER);
        t.setPadding(dp(16), dp(9), dp(16), dp(9));
        t.setBackground(new GlassDrawable(d, dp(20), 1.3f));
        return t;
    }

    private int topInset() {
        int id = act.getResources().getIdentifier("status_bar_height", "dimen", "android");
        return id > 0 ? act.getResources().getDimensionPixelSize(id) : dp(28);
    }

    private void showEditBar() {
        hideEditBar();
        LinearLayout bar = new LinearLayout(act);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(dp(16), topInset() + dp(44), dp(16), 0);
        TextView add = pill("＋");
        add.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20);
        add.setPadding(dp(16), dp(4), dp(16), dp(6));
        add.setOnClickListener(v -> addMenu());
        bar.addView(add);
        TextView set = pill("⚙  Ustawienia");
        set.setOnClickListener(v -> host.showSettings());
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(-2, -2);
        slp.leftMargin = dp(8);
        bar.addView(set, slp);
        bar.addView(new View(act), new LinearLayout.LayoutParams(0, 1, 1f));
        TextView done = pill("Gotowe");
        done.setOnClickListener(v -> setEditing(false));
        bar.addView(done);
        overlay.addView(bar, new FrameLayout.LayoutParams(-1, -2, Gravity.TOP));
        editBar = bar;
        bar.setAlpha(0f);
        bar.setTranslationY(-dp(20));
        bar.animate().alpha(1f).translationY(0).setDuration(220).setInterpolator(new DecelerateInterpolator()).start();
    }

    private void hideEditBar() {
        if (editBar == null) return;
        View b = editBar;
        editBar = null;
        b.animate().alpha(0f).setDuration(150).withEndAction(() -> overlay.removeView(b)).start();
    }

    /** Wstecz / Home: zamyka folder albo kończy edycję. */
    boolean onBack() {
        if (closeFolder()) return true;
        if (editing) {
            setEditing(false);
            return true;
        }
        return false;
    }

    private void addMenu() {
        List<String> labels = new ArrayList<>();
        List<Runnable> actions = new ArrayList<>();
        int page = Math.min(pager.page(), model.pages);
        labels.add("🖼  Zdjęcie 2×2");
        actions.add(() -> addPhoto(2, 2, page));
        labels.add("🖼  Zdjęcie 4×2");
        actions.add(() -> addPhoto(4, 2, page));
        labels.add("🖼  Zdjęcie 4×4");
        actions.add(() -> addPhoto(4, 4, page));
        boolean hasClock = false;
        for (HomeModel.Item i : model.items) if (HomeModel.CLOCK.equals(i.type)) hasClock = true;
        if (!hasClock) {
            labels.add("🕘  Zegar i bateria 4×2");
            actions.add(() -> {
                model.add(HomeModel.CLOCK, null, null, 4, 2, page);
                model.save();
                rebuild();
            });
        }
        labels.add("⚡  Skrót z aplikacji Skróty");
        actions.add(this::addShortcutMenu);
        labels.add("📄  Nowa pusta strona");
        actions.add(() -> {
            model.pages++;
            model.save();
            rebuild();
            pager.post(() -> pager.setPage(model.pages - 1, true));
        });
        new AlertDialog.Builder(act, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle("Dodaj widżet")
                .setItems(labels.toArray(new String[0]), (dlg, w) -> actions.get(w).run()).show();
    }

    private void addPhoto(int w, int h, int page) {
        HomeModel.Item it = model.add(HomeModel.PHOTO, null, "Zdjęcie", w, h, page);
        model.save();
        rebuild();
        pendingPhoto = it;
        host.pickImage(REQ_WIDGET_PHOTO);
    }

    private void addShortcutMenu() {
        List<Shortcuts.Shortcut> list = Shortcuts.load(act);
        String[] names = new String[list.size() + 1];
        for (int i = 0; i < list.size(); i++) names[i] = list.get(i).glyph + "  " + list.get(i).name;
        names[list.size()] = "＋  Utwórz nowy skrót…";
        new AlertDialog.Builder(act, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle("Który skrót?")
                .setItems(names, (dlg, w) -> {
                    if (w == list.size()) {
                        act.startActivity(new Intent(act, ShortcutsActivity.class));
                        return;
                    }
                    model.add(HomeModel.SHORTCUT, list.get(w).id, null, 1, 1, Math.min(pager.page(), model.pages));
                    model.save();
                    rebuild();
                }).show();
    }

    private void shortcutMenu(HomeModel.Item it, Shortcuts.Shortcut s) {
        new AlertDialog.Builder(act, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle(s.name)
                .setItems(new String[]{"Edytuj w aplikacji Skróty", "Usuń z ekranu głównego", "Edytuj ekran główny"}, (dlg, w) -> {
                    if (w == 0) act.startActivity(new Intent(act, ShortcutsActivity.class));
                    else if (w == 1) {
                        model.items.remove(it);
                        model.save();
                        rebuild();
                    } else setEditing(true);
                }).show();
    }

    private void photoMenu(HomeModel.Item it) {
        List<String> labels = new ArrayList<>();
        List<Runnable> actions = new ArrayList<>();
        labels.add("Zmień zdjęcie");
        actions.add(() -> {
            pendingPhoto = it;
            host.pickImage(REQ_WIDGET_PHOTO);
        });
        labels.add("Zmień podpis");
        actions.add(() -> {
            EditText e = new EditText(act);
            e.setText(it.name == null ? "" : it.name);
            e.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
            FrameLayout f = new FrameLayout(act);
            f.setPadding(dp(20), dp(8), dp(20), 0);
            f.addView(e);
            new AlertDialog.Builder(act, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                    .setTitle("Podpis (puste = bez podpisu)").setView(f)
                    .setPositiveButton("OK", (d2, w2) -> {
                        it.name = e.getText().toString().trim();
                        model.save();
                        rebuild();
                    }).setNegativeButton("Anuluj", null).show();
        });
        int[][] sizes = {{2, 2}, {4, 2}, {4, 4}};
        for (int[] s : sizes) {
            if (s[0] == it.w && s[1] == it.h) continue;
            labels.add("Rozmiar " + s[0] + "×" + s[1]);
            actions.add(() -> {
                int ow = it.w, oh = it.h, ox = it.x, oy = it.y, op = it.page;
                it.w = s[0];
                it.h = s[1];
                int nx = Math.min(it.x, HomeModel.COLS - it.w);
                if (model.fits(it.page, nx, it.y, it.w, it.h, it)) it.x = nx;
                else {
                    model.place(it, it.page);
                    if (it.page == op && it.x == ox && it.y == oy && !model.fits(op, ox, oy, it.w, it.h, it)) {
                        it.w = ow;
                        it.h = oh;
                        Toast.makeText(act, "Za mało miejsca", Toast.LENGTH_SHORT).show();
                    }
                }
                model.save();
                rebuild();
            });
        }
        new AlertDialog.Builder(act, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle("Widżet zdjęcia")
                .setItems(labels.toArray(new String[0]), (dlg, w) -> actions.get(w).run()).show();
    }

    // ---------- HomeGrid.Listener ----------

    @Override
    public void onRemove(HomeModel.Item it) {
        if (HomeModel.FOLDER.equals(it.type)) {
            new AlertDialog.Builder(act, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                    .setTitle("Usunąć folder?")
                    .setMessage("Aplikacje zostaną w szufladzie aplikacji.")
                    .setPositiveButton("Usuń", (d2, w) -> {
                        model.items.remove(it);
                        model.save();
                        rebuild();
                    }).setNegativeButton("Anuluj", null).show();
            return;
        }
        if (HomeModel.PHOTO.equals(it.type) && it.key != null) //noinspection ResultOfMethodCallIgnored
            widgetFile(act, it.key).delete();
        model.items.remove(it);
        model.save();
        rebuild();
    }

    @Override
    public void onTapInEdit(HomeModel.Item it) {
        switch (it.type) {
            case HomeModel.PHOTO: photoMenu(it); break;
            case HomeModel.FOLDER: openFolder(it); break;
            case HomeModel.SHORTCUT: act.startActivity(new Intent(act, ShortcutsActivity.class)); break;
            default: break;
        }
    }

    @Override
    public void onDrop(HomeModel.Item it, int x, int y) {
        HomeModel.Item target = it.w == 1 && it.h == 1 ? model.at(it.page, x, y, it) : null;
        boolean isApp = HomeModel.APP.equals(it.type);
        if (isApp && target != null && target.w == 1 && target.h == 1
                && (HomeModel.APP.equals(target.type) || HomeModel.FOLDER.equals(target.type))) {
            // upuszczenie aplikacji na aplikację/folder = folder
            if (HomeModel.APP.equals(target.type)) {
                target.apps.clear();
                target.apps.add(target.key);
                target.type = HomeModel.FOLDER;
                target.key = null;
                target.name = "Folder";
            }
            if (!target.apps.contains(it.key)) target.apps.add(it.key);
            model.items.remove(it);
            model.save();
            rebuild();
            if ("Folder".equals(target.name)) openFolder(target);
            return;
        }
        if (model.fits(it.page, x, y, it.w, it.h, it)) {
            it.x = x;
            it.y = y;
            model.save();
        }
        rebuild();
    }

    @Override
    public void onEdgeHold(HomeModel.Item it, int dir) {
        int np = it.page + dir;
        if (np < 0) return;
        if (np >= model.pages) model.pages = np + 1;
        model.place(it, np);
        model.save();
        rebuild();
        final int target = it.page;
        pager.post(() -> pager.setPage(target, true));
    }

    @Override
    public void onDragState(boolean dragging) {
    }

    // ---------- skróty aplikacji Androida ----------

    private LauncherApps launcherApps() {
        return (LauncherApps) act.getSystemService(Context.LAUNCHER_APPS_SERVICE);
    }

    private UserHandle userFor(String hash) {
        UserManager um = (UserManager) act.getSystemService(Context.USER_SERVICE);
        for (UserHandle u : um.getUserProfiles()) if (String.valueOf(u.hashCode()).equals(hash)) return u;
        return Process.myUserHandle();
    }

    private ShortcutInfo appShortcut(String key) {
        try {
            String[] p = key.split("\\|");
            LauncherApps.ShortcutQuery q = new LauncherApps.ShortcutQuery();
            q.setPackage(p[0]);
            q.setShortcutIds(Collections.singletonList(p[1]));
            q.setQueryFlags(LauncherApps.ShortcutQuery.FLAG_MATCH_PINNED | LauncherApps.ShortcutQuery.FLAG_MATCH_DYNAMIC
                    | LauncherApps.ShortcutQuery.FLAG_MATCH_MANIFEST);
            List<ShortcutInfo> l = launcherApps().getShortcuts(q, userFor(p.length > 2 ? p[2] : ""));
            return l != null && !l.isEmpty() ? l.get(0) : null;
        } catch (Exception e) {
            return null;
        }
    }

    /** Skróty aplikacji (np. konkretny czat) – do menu po przytrzymaniu ikony. */
    List<ShortcutInfo> shortcutsFor(MainActivity.App a) {
        try {
            if (!launcherApps().hasShortcutHostPermission()) return Collections.emptyList();
            LauncherApps.ShortcutQuery q = new LauncherApps.ShortcutQuery();
            q.setPackage(a.component.getPackageName());
            q.setActivity(a.component);
            q.setQueryFlags(LauncherApps.ShortcutQuery.FLAG_MATCH_DYNAMIC | LauncherApps.ShortcutQuery.FLAG_MATCH_MANIFEST);
            List<ShortcutInfo> l = launcherApps().getShortcuts(q, a.user);
            if (l == null) return Collections.emptyList();
            List<ShortcutInfo> out = new ArrayList<>(l);
            out.sort((x, y) -> Integer.compare(x.getRank(), y.getRank()));
            return out.size() > 4 ? out.subList(0, 4) : out;
        } catch (Exception e) {
            return Collections.emptyList();
        }
    }

    void startAppShortcut(ShortcutInfo si) {
        try {
            launcherApps().startShortcut(si, null, null);
        } catch (Exception e) {
            Toast.makeText(act, "Skrót jest niedostępny", Toast.LENGTH_SHORT).show();
        }
    }

    void pinAppShortcut(ShortcutInfo si) {
        try {
            List<String> ids = new ArrayList<>();
            LauncherApps.ShortcutQuery q = new LauncherApps.ShortcutQuery();
            q.setPackage(si.getPackage());
            q.setQueryFlags(LauncherApps.ShortcutQuery.FLAG_MATCH_PINNED);
            List<ShortcutInfo> pinned = launcherApps().getShortcuts(q, si.getUserHandle());
            if (pinned != null) for (ShortcutInfo p : pinned) ids.add(p.getId());
            if (!ids.contains(si.getId())) ids.add(si.getId());
            launcherApps().pinShortcuts(si.getPackage(), ids, si.getUserHandle());
        } catch (Exception ignored) {
        }
        model.add(HomeModel.APPSC, si.getPackage() + "|" + si.getId() + "|" + si.getUserHandle().hashCode(), null, 1, 1,
                Math.min(pager.page(), model.pages));
        model.save();
        rebuild();
        Toast.makeText(act, "Dodano skrót na ekran główny", Toast.LENGTH_SHORT).show();
    }

    boolean hasApp(String key) {
        return model.hasApp(key);
    }

    void addApp(String key) {
        model.addApp(key);
        model.save();
        rebuild();
    }

    void removeApp(String key) {
        model.removeApp(key);
        model.save();
        rebuild();
    }
}
