package pl.fala.launcher;

import android.app.Activity;
import android.app.ActivityOptions;
import android.app.AlertDialog;
import android.app.SearchManager;
import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.LauncherActivityInfo;
import android.content.pm.LauncherApps;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.net.Uri;
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
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
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
        Drawable icon;
    }

    static final int T_CLOCK = 0, T_FAV = 1, T_SECTION = 2, T_APP = 3, T_LABEL = 4, T_HINT = 5;

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

    // ---------- state ----------

    private LauncherApps launcherApps;
    private SharedPreferences prefs;
    private final List<App> apps = new ArrayList<>();
    private final List<Item> items = new ArrayList<>();
    private final Map<String, Integer> sectionPos = new HashMap<>();
    private final ExecutorService exec = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());

    private FrameLayout root;
    private ListView list;
    private AlphabetView alphabet;
    private TextView bubble;
    private LinearLayout searchBar;
    private EditText searchField;
    private ImageView searchButton;
    private Adapter adapter;

    private String query = "";
    private boolean searching = false;
    private int insetTop = 0, insetBottom = 0;
    private LauncherApps.Callback callback;

    // ---------- lifecycle ----------

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                        | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);

        prefs = getSharedPreferences("fala", MODE_PRIVATE);
        launcherApps = (LauncherApps) getSystemService(Context.LAUNCHER_APPS_SERVICE);

        buildUi();
        setContentView(root);

        root.setOnApplyWindowInsetsListener((v, ins) -> {
            insetTop = ins.getSystemWindowInsetTop();
            insetBottom = ins.getSystemWindowInsetBottom();
            applyInsets();
            return ins;
        });

        callback = new LauncherApps.Callback() {
            @Override public void onPackageRemoved(String p, UserHandle u) { loadApps(); }
            @Override public void onPackageAdded(String p, UserHandle u) { loadApps(); }
            @Override public void onPackageChanged(String p, UserHandle u) { loadApps(); }
            @Override public void onPackagesAvailable(String[] p, UserHandle u, boolean r) { loadApps(); }
            @Override public void onPackagesUnavailable(String[] p, UserHandle u, boolean r) { loadApps(); }
        };
        launcherApps.registerCallback(callback, main);

        loadApps();
    }

    @Override
    protected void onResume() {
        super.onResume();
        list.setAlpha(0f);
        list.setTranslationY(dp(18));
        list.animate().alpha(1f).translationY(0f).setDuration(320)
                .setInterpolator(new DecelerateInterpolator(1.6f)).start();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        if (searching) {
            closeSearch();
        } else {
            list.smoothScrollToPositionFromTop(0, 0, 260);
        }
    }

    @Override
    public void onBackPressed() {
        if (searching) {
            closeSearch();
        } else {
            list.smoothScrollToPositionFromTop(0, 0, 260);
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        try {
            launcherApps.unregisterCallback(callback);
        } catch (Exception ignored) {
        }
        exec.shutdownNow();
    }

    // ---------- UI ----------

    private int dp(float v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private void buildUi() {
        root = new FrameLayout(this);

        View scrim = new View(this);
        scrim.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{0x73000000, 0x26000000, 0x40000000, 0x99000000}));
        root.addView(scrim, new FrameLayout.LayoutParams(-1, -1));

        list = new ListView(this);
        list.setDivider(null);
        list.setDividerHeight(0);
        list.setSelector(new ColorDrawable(Color.TRANSPARENT));
        list.setVerticalScrollBarEnabled(false);
        list.setClipToPadding(false);
        list.setOverScrollMode(View.OVER_SCROLL_NEVER);
        list.setCacheColorHint(Color.TRANSPARENT);
        adapter = new Adapter();
        list.setAdapter(adapter);
        list.setOnItemClickListener((p, v, pos, id) -> {
            Item it = items.get(pos);
            if (it.type == T_CLOCK) openClock();
            else if (it.app != null) launch(it.app, v);
        });
        list.setOnItemLongClickListener((p, v, pos, id) -> {
            Item it = items.get(pos);
            if (it.app != null) {
                showAppMenu(it.app);
                return true;
            }
            if (it.type == T_CLOCK || it.type == T_HINT || it.type == T_LABEL) {
                showSettings();
                return true;
            }
            return false;
        });

        // przeciągnięcie w dół na górze listy = panel powiadomień
        GestureDetector gd = new GestureDetector(this, new GestureDetector.SimpleOnGestureListener() {
            @Override
            public boolean onFling(MotionEvent e1, MotionEvent e2, float vx, float vy) {
                if (searching || e1 == null) return false;
                boolean atTop = list.getFirstVisiblePosition() == 0
                        && (list.getChildCount() == 0 || list.getChildAt(0).getTop() >= 0);
                if (atTop && vy > 1200 && Math.abs(vy) > Math.abs(vx) && e2.getY() - e1.getY() > dp(40)) {
                    expandNotifications();
                    return true;
                }
                return false;
            }
        });
        list.setOnTouchListener((v, e) -> {
            gd.onTouchEvent(e);
            return false;
        });
        list.setOnScrollListener(new AbsListView.OnScrollListener() {
            @Override
            public void onScrollStateChanged(AbsListView view, int state) {
                if (searching && state == SCROLL_STATE_TOUCH_SCROLL) hideKeyboard();
            }

            @Override
            public void onScroll(AbsListView view, int first, int visible, int total) {
            }
        });
        root.addView(list, new FrameLayout.LayoutParams(-1, -1));

        // pasek liter
        alphabet = new AlphabetView(this);
        FrameLayout.LayoutParams alp = new FrameLayout.LayoutParams(dp(120), -1, Gravity.END);
        root.addView(alphabet, alp);

        bubble = new TextView(this);
        bubble.setTextColor(Color.WHITE);
        bubble.setTextSize(TypedValue.COMPLEX_UNIT_SP, 34);
        bubble.setTypeface(Typeface.create("sans-serif-light", Typeface.NORMAL));
        bubble.setGravity(Gravity.CENTER);
        GradientDrawable bb = new GradientDrawable();
        bb.setShape(GradientDrawable.OVAL);
        bb.setColor(0x33FFFFFF);
        bb.setStroke(dp(1), 0x40FFFFFF);
        bubble.setBackground(bb);
        bubble.setAlpha(0f);
        FrameLayout.LayoutParams blp = new FrameLayout.LayoutParams(dp(68), dp(68), Gravity.TOP | Gravity.END);
        blp.rightMargin = dp(132);
        root.addView(bubble, blp);

        alphabet.setListener(new AlphabetView.Listener() {
            @Override
            public void onLetter(String letter, float y) {
                Integer pos = sectionPos.get(letter);
                if (pos != null) list.setSelectionFromTop(pos, insetTop + dp(24));
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

        // przycisk wyszukiwania
        searchButton = new ImageView(this);
        searchButton.setImageResource(R.drawable.ic_search);
        searchButton.setScaleType(ImageView.ScaleType.CENTER);
        GradientDrawable sbg = new GradientDrawable();
        sbg.setShape(GradientDrawable.OVAL);
        sbg.setColor(0x26FFFFFF);
        sbg.setStroke(dp(1), 0x33FFFFFF);
        searchButton.setBackground(new RippleDrawable(ColorStateList.valueOf(0x40FFFFFF), sbg, null));
        searchButton.setOnClickListener(v -> openSearch());
        searchButton.setContentDescription("Szukaj");
        FrameLayout.LayoutParams fbp = new FrameLayout.LayoutParams(dp(52), dp(52), Gravity.BOTTOM | Gravity.END);
        fbp.rightMargin = dp(20);
        root.addView(searchButton, fbp);

        // pole wyszukiwania
        searchBar = new LinearLayout(this);
        searchBar.setOrientation(LinearLayout.HORIZONTAL);
        searchBar.setGravity(Gravity.CENTER_VERTICAL);
        GradientDrawable pill = new GradientDrawable();
        pill.setCornerRadius(dp(28));
        pill.setColor(0xCC1A1A1F);
        pill.setStroke(dp(1), 0x33FFFFFF);
        searchBar.setBackground(pill);
        searchBar.setPadding(dp(18), 0, dp(18), 0);
        ImageView sIcon = new ImageView(this);
        sIcon.setImageResource(R.drawable.ic_search);
        sIcon.setAlpha(0.7f);
        searchBar.addView(sIcon, new LinearLayout.LayoutParams(dp(22), dp(22)));
        searchField = new EditText(this);
        searchField.setBackground(null);
        searchField.setHint("Szukaj aplikacji…");
        searchField.setHintTextColor(0x99FFFFFF);
        searchField.setTextColor(Color.WHITE);
        searchField.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17);
        searchField.setSingleLine(true);
        searchField.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        searchField.setImeOptions(EditorInfo.IME_ACTION_GO);
        searchField.setPadding(dp(12), 0, 0, 0);
        searchField.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) { }
            @Override
            public void afterTextChanged(Editable s) {
                query = s.toString().trim();
                rebuildItems();
            }
        });
        searchField.setOnEditorActionListener((v, actionId, event) -> {
            boolean go = actionId == EditorInfo.IME_ACTION_GO
                    || (event != null && event.getKeyCode() == KeyEvent.KEYCODE_ENTER
                    && event.getAction() == KeyEvent.ACTION_DOWN);
            if (!go) return false;
            for (Item it : items) {
                if (it.app != null) {
                    launch(it.app, null);
                    return true;
                }
            }
            if (!query.isEmpty()) webSearch(query);
            return true;
        });
        searchBar.addView(searchField, new LinearLayout.LayoutParams(0, -1, 1f));
        searchBar.setVisibility(View.GONE);
        FrameLayout.LayoutParams slp = new FrameLayout.LayoutParams(-1, dp(56), Gravity.BOTTOM);
        slp.leftMargin = dp(16);
        slp.rightMargin = dp(16);
        root.addView(searchBar, slp);
    }

    private void applyInsets() {
        list.setPadding(dp(28), 0, dp(64), insetBottom + dp(100));
        alphabet.setPadding(0, insetTop + dp(24), 0, insetBottom + dp(96));

        FrameLayout.LayoutParams fbp = (FrameLayout.LayoutParams) searchButton.getLayoutParams();
        fbp.bottomMargin = insetBottom + dp(20);
        searchButton.setLayoutParams(fbp);

        FrameLayout.LayoutParams slp = (FrameLayout.LayoutParams) searchBar.getLayoutParams();
        slp.bottomMargin = insetBottom + dp(14);
        searchBar.setLayoutParams(slp);

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

    private void loadApps() {
        exec.execute(() -> {
            List<App> out = new ArrayList<>();
            UserManager um = (UserManager) getSystemService(Context.USER_SERVICE);
            List<UserHandle> users = um.getUserProfiles();
            for (UserHandle u : users) {
                List<LauncherActivityInfo> infos;
                try {
                    infos = launcherApps.getActivityList(null, u);
                } catch (Exception e) {
                    continue;
                }
                for (LauncherActivityInfo i : infos) {
                    ComponentName cn = i.getComponentName();
                    if (cn.getPackageName().equals(getPackageName())) continue;
                    App a = new App();
                    CharSequence l = i.getLabel();
                    a.label = l == null ? cn.getPackageName() : l.toString().trim();
                    a.norm = normalize(a.label).toLowerCase(Locale.ROOT);
                    a.section = sectionOf(a.norm);
                    a.component = cn;
                    a.user = u;
                    a.key = cn.flattenToString() + "#" + u.hashCode();
                    try {
                        a.icon = i.getBadgedIcon(0);
                    } catch (Exception e) {
                        a.icon = null;
                    }
                    out.add(a);
                }
            }
            final Collator col = Collator.getInstance(new Locale("pl", "PL"));
            Collections.sort(out, (x, y) -> {
                int s = x.section.compareTo(y.section);
                return s != 0 ? s : col.compare(x.label, y.label);
            });
            if (!prefs.contains("fav")) pickDefaultFavorites(out);
            main.post(() -> {
                apps.clear();
                apps.addAll(out);
                rebuildItems();
            });
        });
    }

    private void pickDefaultFavorites(List<App> all) {
        PackageManager pm = getPackageManager();
        Intent[] intents = new Intent[]{
                new Intent(Intent.ACTION_DIAL),
                new Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:")),
                new Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA),
                new Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com")),
        };
        List<String> fav = new ArrayList<>();
        for (Intent in : intents) {
            try {
                ResolveInfo ri = pm.resolveActivity(in, PackageManager.MATCH_DEFAULT_ONLY);
                if (ri == null || ri.activityInfo == null) continue;
                String pkg = ri.activityInfo.packageName;
                for (App a : all) {
                    if (a.component.getPackageName().equals(pkg) && !fav.contains(a.key)) {
                        fav.add(a.key);
                        break;
                    }
                }
            } catch (Exception ignored) {
            }
        }
        saveFavorites(fav);
    }

    private List<String> getFavorites() {
        String s = prefs.getString("fav", "");
        List<String> out = new ArrayList<>();
        for (String k : s.split("\n")) if (!k.isEmpty()) out.add(k);
        return out;
    }

    private void saveFavorites(List<String> fav) {
        prefs.edit().putString("fav", TextUtils.join("\n", fav)).apply();
    }

    private boolean showIcons() {
        return prefs.getBoolean("icons", true);
    }

    private void rebuildItems() {
        items.clear();
        sectionPos.clear();
        LinkedHashSet<String> letters = new LinkedHashSet<>();

        if (query.isEmpty()) {
            items.add(new Item(T_CLOCK, null, null));
            Map<String, App> byKey = new HashMap<>();
            for (App a : apps) byKey.put(a.key, a);
            int favCount = 0;
            for (String k : getFavorites()) {
                App a = byKey.get(k);
                if (a != null) {
                    items.add(new Item(T_FAV, a, null));
                    favCount++;
                }
            }
            if (favCount == 0 && !apps.isEmpty()) {
                items.add(new Item(T_HINT, null, "Przytrzymaj dowolną aplikację poniżej, aby dodać ją do ulubionych."));
            }
            items.add(new Item(T_LABEL, null, "WSZYSTKIE APLIKACJE"));
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
    }

    // ---------- akcje ----------

    private void launch(App a, View v) {
        try {
            Rect r = null;
            Bundle opts = null;
            if (v != null) {
                int[] loc = new int[2];
                v.getLocationOnScreen(loc);
                r = new Rect(loc[0], loc[1], loc[0] + v.getWidth(), loc[1] + v.getHeight());
                opts = ActivityOptions.makeClipRevealAnimation(v, 0, 0, v.getWidth(), v.getHeight()).toBundle();
            }
            launcherApps.startMainActivity(a.component, a.user, r, opts);
            if (searching) main.postDelayed(this::closeSearch, 400);
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

    private void openCalendar() {
        Uri uri = Uri.parse("content://com.android.calendar/time/" + System.currentTimeMillis());
        safeStart(new Intent(Intent.ACTION_VIEW, uri));
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

    @SuppressWarnings({"WrongConstant", "JavaReflectionMemberAccess"})
    private void expandNotifications() {
        try {
            Object sb = getSystemService("statusbar");
            Class<?> c = Class.forName("android.app.StatusBarManager");
            c.getMethod("expandNotificationsPanel").invoke(sb);
        } catch (Exception ignored) {
        }
    }

    private void openSearch() {
        searching = true;
        searchButton.animate().alpha(0f).scaleX(0.6f).scaleY(0.6f).setDuration(150)
                .withEndAction(() -> searchButton.setVisibility(View.GONE)).start();
        alphabet.setVisibility(View.GONE);
        searchBar.setVisibility(View.VISIBLE);
        searchBar.setAlpha(0f);
        searchBar.setTranslationY(dp(24));
        searchBar.animate().alpha(1f).translationY(0f).setDuration(220)
                .setInterpolator(new DecelerateInterpolator()).start();
        searchField.setText("");
        searchField.requestFocus();
        InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
        searchField.postDelayed(() -> imm.showSoftInput(searchField, InputMethodManager.SHOW_IMPLICIT), 80);
    }

    private void closeSearch() {
        if (!searching) return;
        searching = false;
        hideKeyboard();
        searchField.setText("");
        searchBar.setVisibility(View.GONE);
        alphabet.setVisibility(View.VISIBLE);
        searchButton.setVisibility(View.VISIBLE);
        searchButton.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(180).start();
        query = "";
        rebuildItems();
        list.setSelection(0);
    }

    private void hideKeyboard() {
        InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
        imm.hideSoftInputFromWindow(searchField.getWindowToken(), 0);
    }

    private void showAppMenu(App a) {
        List<String> labels = new ArrayList<>();
        List<Runnable> actions = new ArrayList<>();
        final List<String> fav = getFavorites();
        final int idx = fav.indexOf(a.key);

        if (idx < 0) {
            labels.add("★  Dodaj do ulubionych");
            actions.add(() -> {
                fav.add(a.key);
                saveFavorites(fav);
                rebuildItems();
            });
        } else {
            labels.add("☆  Usuń z ulubionych");
            actions.add(() -> {
                fav.remove(a.key);
                saveFavorites(fav);
                rebuildItems();
            });
            if (idx > 0) {
                labels.add("↑  Przesuń wyżej");
                actions.add(() -> {
                    Collections.swap(fav, idx, idx - 1);
                    saveFavorites(fav);
                    rebuildItems();
                });
            }
            if (idx < fav.size() - 1) {
                labels.add("↓  Przesuń niżej");
                actions.add(() -> {
                    Collections.swap(fav, idx, idx + 1);
                    saveFavorites(fav);
                    rebuildItems();
                });
            }
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

    private void showSettings() {
        List<String> labels = new ArrayList<>();
        List<Runnable> actions = new ArrayList<>();
        labels.add(showIcons() ? "Ukryj ikony" : "Pokaż ikony");
        actions.add(() -> {
            prefs.edit().putBoolean("icons", !showIcons()).apply();
            adapter.notifyDataSetChanged();
        });
        labels.add("Zmień tapetę");
        actions.add(() -> safeStart(Intent.createChooser(new Intent(Intent.ACTION_SET_WALLPAPER), "Tapeta")));
        labels.add("Ustaw Falę jako domyślny ekran główny");
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
        bg.setCornerRadius(dp(26));
        bg.setColor(0xF21C1C22);
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

    // ---------- adapter ----------

    private class Adapter extends BaseAdapter {
        @Override public int getCount() { return items.size(); }
        @Override public Object getItem(int p) { return items.get(p); }
        @Override public long getItemId(int p) { return p; }
        @Override public int getViewTypeCount() { return 6; }
        @Override public int getItemViewType(int p) { return items.get(p).type; }
        @Override public boolean areAllItemsEnabled() { return false; }

        @Override
        public boolean isEnabled(int p) {
            int t = items.get(p).type;
            return t == T_CLOCK || t == T_FAV || t == T_APP || t == T_HINT;
        }

        @Override
        public View getView(int pos, View cv, ViewGroup parent) {
            Item it = items.get(pos);
            switch (it.type) {
                case T_CLOCK: return clockView(cv);
                case T_FAV: return appRow(cv, it.app, true);
                case T_APP: return appRow(cv, it.app, false);
                case T_SECTION: return textRow(cv, it.text, 13, 0x8CFFFFFF, "sans-serif-medium", dp(20), dp(6));
                case T_LABEL: return textRow(cv, it.text, 11, 0x80FFFFFF, "sans-serif-medium", dp(44), dp(2));
                default: return textRow(cv, it.text, 14, 0x99FFFFFF, "sans-serif", dp(14), dp(14));
            }
        }
    }

    private View clockView(View cv) {
        LinearLayout box;
        if (cv instanceof LinearLayout && "clock".equals(cv.getTag())) {
            box = (LinearLayout) cv;
        } else {
            box = new LinearLayout(this);
            box.setTag("clock");
            box.setOrientation(LinearLayout.VERTICAL);

            TextClock time = new TextClock(this);
            time.setFormat24Hour("HH:mm");
            time.setFormat12Hour("h:mm");
            time.setTextColor(Color.WHITE);
            time.setTextSize(TypedValue.COMPLEX_UNIT_SP, 72);
            time.setTypeface(Typeface.create("sans-serif-thin", Typeface.NORMAL));
            time.setLetterSpacing(-0.02f);
            time.setIncludeFontPadding(false);
            box.addView(time);

            TextClock date = new TextClock(this);
            date.setFormat24Hour("EEEE, d MMMM");
            date.setFormat12Hour("EEEE, d MMMM");
            date.setTextColor(0xD9FFFFFF);
            date.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
            date.setTypeface(Typeface.create("sans-serif-light", Typeface.NORMAL));
            date.setPadding(dp(4), dp(6), 0, 0);
            date.setOnClickListener(v -> openCalendar());
            date.setOnLongClickListener(v -> {
                showSettings();
                return true;
            });
            box.addView(date);
        }
        box.setPadding(0, insetTop + dp(64), 0, dp(36));
        return box;
    }

    private View appRow(View cv, App a, boolean big) {
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
            h = new Holder();
            h.icon = new ImageView(this);
            int s = big ? dp(42) : dp(34);
            LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(s, s);
            ilp.rightMargin = big ? dp(18) : dp(16);
            row.addView(h.icon, ilp);
            h.label = new TextView(this);
            h.label.setTextColor(Color.WHITE);
            h.label.setSingleLine(true);
            h.label.setEllipsize(TextUtils.TruncateAt.END);
            if (big) {
                h.label.setTextSize(TypedValue.COMPLEX_UNIT_SP, 25);
                h.label.setTypeface(Typeface.create("sans-serif-light", Typeface.NORMAL));
                row.setPadding(dp(4), dp(11), dp(4), dp(11));
            } else {
                h.label.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
                h.label.setTypeface(Typeface.create("sans-serif", Typeface.NORMAL));
                h.label.setAlpha(0.92f);
                row.setPadding(dp(4), dp(9), dp(4), dp(9));
            }
            row.addView(h.label, new LinearLayout.LayoutParams(0, -2, 1f));
            row.setTag(h);
        }
        h.label.setText(a.label);
        if (showIcons() && a.icon != null) {
            h.icon.setVisibility(View.VISIBLE);
            Drawable.ConstantState cs = a.icon.getConstantState();
            h.icon.setImageDrawable(cs != null ? cs.newDrawable(getResources()) : a.icon);
        } else {
            h.icon.setVisibility(View.GONE);
        }
        return row;
    }

    private View textRow(View cv, String text, int sp, int color, String font, int padTop, int padBottom) {
        TextView tv;
        if (cv instanceof TextView) {
            tv = (TextView) cv;
        } else {
            tv = new TextView(this);
        }
        tv.setText(text);
        tv.setTextColor(color);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        tv.setTypeface(Typeface.create(font, Typeface.NORMAL));
        tv.setLetterSpacing(sp <= 13 ? 0.12f : 0f);
        tv.setPadding(dp(4), padTop, dp(4), padBottom);
        return tv;
    }
}
