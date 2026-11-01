package pl.fala.phone;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.CallLog;
import android.provider.ContactsContract;
import android.provider.Settings;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.GridView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Aplikacja Telefon: Ulubione, Ostatnie, Kontakty, Klawiatura. */
public class MainActivity extends Activity {

    static class Entry {
        String name, number, sub;
        Uri photo;
        int type;     // typ połączenia z rejestru (1 przych., 2 wych., 3 nieodebr., 5 odrzuc.)
        int count = 1;
        long date;
        long contactId = -1;
        Bitmap avatar;
    }

    private static final String[] TABS = {"Ulubione", "Ostatnie", "Kontakty", "Klawiatura"};
    private static final int[] ICONS = {R.drawable.ic_star, R.drawable.ic_clock, R.drawable.ic_person, R.drawable.ic_dialpad};

    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService exec = Executors.newSingleThreadExecutor();
    private final Map<String, Bitmap> avatarCache = new HashMap<>();
    private FrameLayout content;
    private LinearLayout nav;
    private LinearLayout banner;
    private int tab = 1;
    private String dialed = "";
    private TextView dialText;

    private int dp(float v) {
        return Ph.dp(this, v);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setStatusBarColor(Ph.BG);
        getWindow().setNavigationBarColor(0xFF111116);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Ph.BG);
        banner = new LinearLayout(this);
        banner.setOrientation(LinearLayout.VERTICAL);
        root.addView(banner);
        content = new FrameLayout(this);
        root.addView(content, new LinearLayout.LayoutParams(-1, 0, 1f));
        nav = new LinearLayout(this);
        nav.setBackgroundColor(0xFF111116);
        nav.setPadding(0, dp(6), 0, dp(6));
        for (int i = 0; i < 4; i++) {
            final int idx = i;
            LinearLayout b = new LinearLayout(this);
            b.setOrientation(LinearLayout.VERTICAL);
            b.setGravity(Gravity.CENTER);
            b.setPadding(0, dp(6), 0, dp(4));
            ImageView iv = new ImageView(this);
            iv.setImageResource(ICONS[i]);
            b.addView(iv, new LinearLayout.LayoutParams(dp(26), dp(26)));
            TextView t = Ph.tv(this, TABS[i], 11, Color.WHITE, true);
            t.setPadding(0, dp(3), 0, 0);
            b.addView(t);
            b.setOnClickListener(v -> show(idx));
            nav.addView(b, new LinearLayout.LayoutParams(0, -2, 1f));
        }
        root.addView(nav);
        setContentView(root);
        CallService.channels(this);
        handleIntent(getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        handleIntent(intent);
    }

    private void handleIntent(Intent i) {
        Uri d = i.getData();
        if (d != null && "tel".equals(d.getScheme())) {
            dialed = d.getSchemeSpecificPart() == null ? "" : d.getSchemeSpecificPart();
            show(3);
            return;
        }
        if (Intent.ACTION_DIAL.equals(i.getAction())) {
            show(3);
            return;
        }
        show(i.getIntExtra("tab", tab));
    }

    @Override
    protected void onResume() {
        super.onResume();
        buildBanner();
        askPermissions();
        if (tab != 3) show(tab);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        exec.shutdownNow();
    }

    private void askPermissions() {
        List<String> need = new ArrayList<>();
        for (String p : new String[]{Manifest.permission.READ_CONTACTS, Manifest.permission.READ_CALL_LOG,
                Manifest.permission.CALL_PHONE, Manifest.permission.READ_PHONE_STATE}) {
            if (checkSelfPermission(p) != PackageManager.PERMISSION_GRANTED) need.add(p);
        }
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            need.add(Manifest.permission.POST_NOTIFICATIONS);
        }
        if (!need.isEmpty()) requestPermissions(need.toArray(new String[0]), 1);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        show(tab);
    }

    /** Pasek zachęcający do ustawienia aplikacji jako domyślnej (i pozwolenia na ekran przy połączeniu). */
    private void buildBanner() {
        banner.removeAllViews();
        boolean def = Ph.isDefaultDialer(this);
        boolean fsi = true;
        if (Build.VERSION.SDK_INT >= 34) {
            android.app.NotificationManager nm = getSystemService(android.app.NotificationManager.class);
            fsi = nm.canUseFullScreenIntent();
        }
        if (def && fsi) return;
        LinearLayout b = new LinearLayout(this);
        b.setOrientation(LinearLayout.VERTICAL);
        b.setBackground(Ph.round(0xFF1A2236, dp(18)));
        b.setPadding(dp(16), dp(14), dp(16), dp(14));
        b.addView(Ph.tv(this, def ? "Pozwól pokazywać połączenia na pełnym ekranie" : "Ustaw Fala Telefon jako domyślną", 15, Color.WHITE, true));
        b.addView(Ph.tv(this, def ? "Bez tego połączenie przychodzące pokaże się tylko jako powiadomienie."
                : "Wtedy ekran rozmowy, odbieranie i wybór karty SIM będą w tej aplikacji.", 13, Ph.TEXT2, false));
        TextView go = Ph.button(this, def ? "Zezwól" : "Ustaw", Ph.ACCENT, Color.WHITE);
        go.setOnClickListener(v -> {
            try {
                if (!def) startActivityForResult(Ph.requestDefaultIntent(this), 9);
                else startActivity(new Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, Uri.parse("package:" + getPackageName())));
            } catch (Exception e) {
                Toast.makeText(this, "Ustawienia → Aplikacje → Aplikacje domyślne → Telefon", Toast.LENGTH_LONG).show();
            }
        });
        LinearLayout.LayoutParams glp = new LinearLayout.LayoutParams(-2, -2);
        glp.topMargin = dp(10);
        b.addView(go, glp);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.setMargins(dp(14), dp(14), dp(14), 0);
        banner.addView(b, lp);
    }

    private void show(int t) {
        tab = Math.max(0, Math.min(3, t));
        for (int i = 0; i < nav.getChildCount(); i++) {
            LinearLayout b = (LinearLayout) nav.getChildAt(i);
            int col = i == tab ? Ph.ACCENT : 0x80FFFFFF;
            ((ImageView) b.getChildAt(0)).setColorFilter(col);
            ((TextView) b.getChildAt(1)).setTextColor(col);
        }
        content.removeAllViews();
        View v;
        switch (tab) {
            case 0: v = favorites(); break;
            case 1: v = recents(); break;
            case 2: v = contacts(); break;
            default: v = dialer();
        }
        content.addView(v, new FrameLayout.LayoutParams(-1, -1));
    }

    private TextView title(String s) {
        TextView t = Ph.tv(this, s, 32, Color.WHITE, true);
        t.setPadding(dp(20), dp(18), dp(20), dp(8));
        return t;
    }

    private Bitmap avatarFor(String key, String name, Uri photo, int px) {
        String k = key + "@" + px;
        synchronized (avatarCache) {
            Bitmap b = avatarCache.get(k);
            if (b != null) return b;
        }
        Bitmap b = Ph.avatar(this, name, photo, px);
        synchronized (avatarCache) {
            avatarCache.put(k, b);
        }
        return b;
    }

    // ---------- Ostatnie ----------

    private View recents() {
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.addView(title("Ostatnie"));
        final boolean[] missedOnly = {false};
        LinearLayout seg = new LinearLayout(this);
        seg.setPadding(dp(20), 0, dp(20), dp(8));
        TextView all = Ph.tv(this, "Wszystkie", 14, Color.WHITE, true), missed = Ph.tv(this, "Nieodebrane", 14, Color.WHITE, true);
        for (TextView x : new TextView[]{all, missed}) {
            x.setPadding(dp(16), dp(7), dp(16), dp(7));
            seg.addView(x);
        }
        col.addView(seg);
        ListView list = new ListView(this);
        list.setDivider(null);
        list.setSelector(new ColorDrawable(Color.TRANSPARENT));
        List<Entry> items = new ArrayList<>();
        List<Entry> allItems = new ArrayList<>();
        BaseAdapter ad = entryAdapter(items, true);
        list.setAdapter(ad);
        list.setOnItemClickListener((p, v, pos, id) -> Ph.call(this, items.get(pos).number));
        list.setOnItemLongClickListener((p, v, pos, id) -> {
            details(items.get(pos));
            return true;
        });
        col.addView(list, new LinearLayout.LayoutParams(-1, 0, 1f));
        TextView empty = Ph.tv(this, "", 15, Ph.TEXT2, false);
        empty.setGravity(Gravity.CENTER);
        col.addView(empty, new LinearLayout.LayoutParams(-1, -2));

        Runnable apply = () -> {
            items.clear();
            for (Entry e : allItems) if (!missedOnly[0] || e.type == CallLog.Calls.MISSED_TYPE) items.add(e);
            ad.notifyDataSetChanged();
            all.setBackground(missedOnly[0] ? null : Ph.round(0x33FFFFFF, dp(14)));
            missed.setBackground(missedOnly[0] ? Ph.round(0x33FFFFFF, dp(14)) : null);
            empty.setText(items.isEmpty() ? "Brak połączeń" : "");
        };
        all.setOnClickListener(v -> { missedOnly[0] = false; apply.run(); });
        missed.setOnClickListener(v -> { missedOnly[0] = true; apply.run(); });
        apply.run();

        if (checkSelfPermission(Manifest.permission.READ_CALL_LOG) != PackageManager.PERMISSION_GRANTED) {
            empty.setText("Zezwól na dostęp do rejestru połączeń");
            return col;
        }
        exec.execute(() -> {
            List<Entry> out = new ArrayList<>();
            try (Cursor c = getContentResolver().query(CallLog.Calls.CONTENT_URI,
                    new String[]{CallLog.Calls.NUMBER, CallLog.Calls.TYPE, CallLog.Calls.DATE, CallLog.Calls.CACHED_NAME},
                    null, null, CallLog.Calls.DATE + " DESC")) {
                Entry last = null;
                while (c != null && c.moveToNext() && out.size() < 300) {
                    String num = c.getString(0);
                    int type = c.getInt(1);
                    if (type == CallLog.Calls.REJECTED_TYPE || type == CallLog.Calls.BLOCKED_TYPE) type = CallLog.Calls.MISSED_TYPE;
                    if (last != null && TextUtils.equals(last.number, num) && last.type == type) {
                        last.count++;
                        continue;
                    }
                    Entry e = new Entry();
                    e.number = num;
                    e.type = type;
                    e.date = c.getLong(2);
                    Ph.Contact k = Ph.lookup(this, num);
                    e.name = k.name;
                    e.photo = k.photo;
                    e.contactId = k.id;
                    e.avatar = avatarFor(num == null ? "" : num, e.name, e.photo, dp(44));
                    out.add(e);
                    last = e;
                }
            } catch (Exception ignored) {
            }
            main.post(() -> {
                allItems.clear();
                allItems.addAll(out);
                apply.run();
            });
        });
        return col;
    }

    // ---------- Kontakty ----------

    private View contacts() {
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.addView(title("Kontakty"));
        LinearLayout search = new LinearLayout(this);
        search.setGravity(Gravity.CENTER_VERTICAL);
        search.setBackground(Ph.round(0xFF1C1C24, dp(14)));
        search.setPadding(dp(12), 0, dp(12), 0);
        ImageView si = new ImageView(this);
        si.setImageResource(R.drawable.ic_search);
        si.setAlpha(0.6f);
        search.addView(si, new LinearLayout.LayoutParams(dp(20), dp(20)));
        EditText q = new EditText(this);
        q.setBackground(null);
        q.setHint("Szukaj");
        q.setHintTextColor(0x66FFFFFF);
        q.setTextColor(Color.WHITE);
        q.setSingleLine(true);
        q.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        search.addView(q, new LinearLayout.LayoutParams(0, dp(44), 1f));
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(-1, -2);
        slp.setMargins(dp(16), 0, dp(16), dp(8));
        col.addView(search, slp);

        ListView list = new ListView(this);
        list.setDivider(null);
        list.setSelector(new ColorDrawable(Color.TRANSPARENT));
        list.setFastScrollEnabled(true);
        List<Entry> items = new ArrayList<>(), allItems = new ArrayList<>();
        BaseAdapter ad = entryAdapter(items, false);
        list.setAdapter(ad);
        list.setOnItemClickListener((p, v, pos, id) -> details(items.get(pos)));
        col.addView(list, new LinearLayout.LayoutParams(-1, 0, 1f));
        Runnable filter = () -> {
            String s = q.getText().toString().trim().toLowerCase(Locale.ROOT);
            items.clear();
            for (Entry e : allItems) {
                if (s.isEmpty() || e.name.toLowerCase(Locale.ROOT).contains(s)
                        || (e.number != null && e.number.replace(" ", "").contains(s))) items.add(e);
            }
            ad.notifyDataSetChanged();
        };
        q.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void afterTextChanged(Editable s) { filter.run(); }
        });
        loadContacts(false, out -> {
            allItems.clear();
            allItems.addAll(out);
            filter.run();
        });
        return col;
    }

    interface Done {
        void on(List<Entry> list);
    }

    private void loadContacts(boolean starredOnly, Done done) {
        if (checkSelfPermission(Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) return;
        exec.execute(() -> {
            List<Entry> out = new ArrayList<>();
            Map<Long, Entry> byId = new HashMap<>();
            String sel = ContactsContract.CommonDataKinds.Phone.NUMBER + " IS NOT NULL"
                    + (starredOnly ? " AND " + ContactsContract.CommonDataKinds.Phone.STARRED + "=1" : "");
            try (Cursor c = getContentResolver().query(ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                    new String[]{ContactsContract.CommonDataKinds.Phone.CONTACT_ID, ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                            ContactsContract.CommonDataKinds.Phone.NUMBER, ContactsContract.CommonDataKinds.Phone.PHOTO_THUMBNAIL_URI},
                    sel, null, ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME + " COLLATE LOCALIZED ASC")) {
                while (c != null && c.moveToNext()) {
                    long id = c.getLong(0);
                    if (byId.containsKey(id)) continue;
                    Entry e = new Entry();
                    e.contactId = id;
                    e.name = c.getString(1) == null ? c.getString(2) : c.getString(1);
                    e.number = c.getString(2);
                    String ph = c.getString(3);
                    if (ph != null) e.photo = Uri.parse(ph);
                    e.sub = e.number;
                    e.avatar = avatarFor("c" + id, e.name, e.photo, starredOnly ? dp(84) : dp(44));
                    byId.put(id, e);
                    out.add(e);
                }
            } catch (Exception ignored) {
            }
            main.post(() -> done.on(out));
        });
    }

    // ---------- Ulubione ----------

    private View favorites() {
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.addView(title("Ulubione"));
        GridView grid = new GridView(this);
        grid.setNumColumns(3);
        grid.setVerticalSpacing(dp(18));
        grid.setSelector(new ColorDrawable(Color.TRANSPARENT));
        grid.setPadding(dp(10), dp(10), dp(10), dp(10));
        List<Entry> items = new ArrayList<>();
        BaseAdapter ad = new BaseAdapter() {
            @Override public int getCount() { return items.size(); }
            @Override public Object getItem(int p) { return items.get(p); }
            @Override public long getItemId(int p) { return p; }

            @Override
            public View getView(int p, View cv, ViewGroup parent) {
                LinearLayout c = new LinearLayout(MainActivity.this);
                c.setOrientation(LinearLayout.VERTICAL);
                c.setGravity(Gravity.CENTER_HORIZONTAL);
                ImageView iv = new ImageView(MainActivity.this);
                iv.setImageBitmap(items.get(p).avatar);
                c.addView(iv, new LinearLayout.LayoutParams(dp(84), dp(84)));
                TextView t = Ph.tv(MainActivity.this, items.get(p).name, 14, Color.WHITE, true);
                t.setGravity(Gravity.CENTER);
                t.setSingleLine(true);
                t.setEllipsize(TextUtils.TruncateAt.END);
                t.setPadding(dp(4), dp(8), dp(4), 0);
                c.addView(t, new LinearLayout.LayoutParams(-1, -2));
                return c;
            }
        };
        grid.setAdapter(ad);
        grid.setOnItemClickListener((p, v, pos, id) -> Ph.call(this, items.get(pos).number));
        grid.setOnItemLongClickListener((p, v, pos, id) -> {
            details(items.get(pos));
            return true;
        });
        col.addView(grid, new LinearLayout.LayoutParams(-1, 0, 1f));
        TextView empty = Ph.tv(this, "Brak ulubionych.\nOznacz kontakt gwiazdką w aplikacji Kontakty – pojawi się tutaj.", 15, Ph.TEXT2, false);
        empty.setGravity(Gravity.CENTER);
        empty.setPadding(dp(30), 0, dp(30), dp(80));
        col.addView(empty, new LinearLayout.LayoutParams(-1, -2));
        loadContacts(true, out -> {
            items.clear();
            items.addAll(out);
            ad.notifyDataSetChanged();
            empty.setVisibility(out.isEmpty() ? View.VISIBLE : View.GONE);
        });
        return col;
    }

    // ---------- Klawiatura ----------

    private View dialer() {
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setGravity(Gravity.CENTER_HORIZONTAL);
        col.setPadding(dp(20), dp(10), dp(20), dp(16));
        TextView suggestion = Ph.tv(this, "", 14, Ph.ACCENT, true);
        suggestion.setGravity(Gravity.CENTER);
        suggestion.setMinHeight(dp(26));
        dialText = Ph.tv(this, dialed, 38, Color.WHITE, false);
        dialText.setGravity(Gravity.CENTER);
        dialText.setSingleLine(true);
        dialText.setEllipsize(TextUtils.TruncateAt.START);
        col.addView(new View(this), new LinearLayout.LayoutParams(1, 0, 1f));
        col.addView(dialText, new LinearLayout.LayoutParams(-1, dp(64)));
        col.addView(suggestion, new LinearLayout.LayoutParams(-1, -2));
        final String[] sugNumber = {null};
        Runnable updateSug = () -> {
            dialText.setText(dialed);
            sugNumber[0] = null;
            suggestion.setText("");
            if (dialed.length() < 3 || checkSelfPermission(Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) return;
            final String d = dialed;
            exec.execute(() -> {
                String name = null, num = null;
                try (Cursor c = getContentResolver().query(
                        Uri.withAppendedPath(ContactsContract.CommonDataKinds.Phone.CONTENT_FILTER_URI, Uri.encode(d)),
                        new String[]{ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME, ContactsContract.CommonDataKinds.Phone.NUMBER},
                        null, null, null)) {
                    if (c != null && c.moveToFirst()) {
                        name = c.getString(0);
                        num = c.getString(1);
                    }
                } catch (Exception ignored) {
                }
                final String n = name, nu = num;
                main.post(() -> {
                    if (!d.equals(dialed)) return;
                    if (n != null) {
                        sugNumber[0] = nu;
                        suggestion.setText(n + "  ·  " + nu);
                    }
                });
            });
        };
        suggestion.setOnClickListener(v -> {
            if (sugNumber[0] != null) {
                dialed = sugNumber[0].replace(" ", "");
                updateSug.run();
            }
        });
        LinearLayout.LayoutParams glp = new LinearLayout.LayoutParams(-2, -2);
        glp.topMargin = dp(10);
        col.addView(Dialpad.build(this, ch -> {
            if (dialed.length() < 30) dialed += ch;
            updateSug.run();
        }, null), glp);

        FrameLayout bottom = new FrameLayout(this);
        ImageView call = new ImageView(this);
        call.setImageResource(R.drawable.ic_phone);
        call.setScaleType(ImageView.ScaleType.CENTER);
        call.setScaleX(1.2f);
        call.setScaleY(1.2f);
        call.setBackground(Ph.circle(Ph.GREEN));
        call.setOnClickListener(v -> {
            if (dialed.isEmpty()) {
                // jak w iPhonie: pusty numer → wstaw ostatnio wybierany
                exec.execute(() -> {
                    String last = null;
                    try (Cursor c = getContentResolver().query(CallLog.Calls.CONTENT_URI, new String[]{CallLog.Calls.NUMBER},
                            CallLog.Calls.TYPE + "=" + CallLog.Calls.OUTGOING_TYPE, null, CallLog.Calls.DATE + " DESC")) {
                        if (c != null && c.moveToFirst()) last = c.getString(0);
                    } catch (Exception ignored) {
                    }
                    final String l = last;
                    main.post(() -> {
                        if (l != null) {
                            dialed = l;
                            updateSug.run();
                        }
                    });
                });
            } else Ph.call(this, dialed);
        });
        bottom.addView(call, new FrameLayout.LayoutParams(dp(74), dp(74), Gravity.CENTER));
        ImageView back = new ImageView(this);
        back.setImageResource(R.drawable.ic_backspace);
        back.setAlpha(0.7f);
        back.setScaleType(ImageView.ScaleType.CENTER);
        back.setOnClickListener(v -> {
            if (!dialed.isEmpty()) dialed = dialed.substring(0, dialed.length() - 1);
            updateSug.run();
        });
        back.setOnLongClickListener(v -> {
            dialed = "";
            updateSug.run();
            return true;
        });
        FrameLayout.LayoutParams blp = new FrameLayout.LayoutParams(dp(60), dp(60), Gravity.CENTER_VERTICAL | Gravity.END);
        blp.rightMargin = dp(40);
        bottom.addView(back, blp);
        LinearLayout.LayoutParams bl = new LinearLayout.LayoutParams(-1, dp(90));
        bl.topMargin = dp(6);
        col.addView(bottom, bl);
        updateSug.run();
        return col;
    }

    // ---------- wspólne ----------

    static class Holder {
        ImageView av;
        TextView name, sub, time;
    }

    private BaseAdapter entryAdapter(List<Entry> items, boolean calls) {
        return new BaseAdapter() {
            @Override public int getCount() { return items.size(); }
            @Override public Object getItem(int p) { return items.get(p); }
            @Override public long getItemId(int p) { return p; }

            @Override
            public View getView(int pos, View cv, ViewGroup parent) {
                Holder h;
                if (cv == null) {
                    LinearLayout row = new LinearLayout(MainActivity.this);
                    row.setGravity(Gravity.CENTER_VERTICAL);
                    row.setPadding(dp(18), dp(9), dp(14), dp(9));
                    row.setBackground(Ph.ripple(MainActivity.this));
                    h = new Holder();
                    h.av = new ImageView(MainActivity.this);
                    row.addView(h.av, new LinearLayout.LayoutParams(dp(44), dp(44)));
                    LinearLayout c = new LinearLayout(MainActivity.this);
                    c.setOrientation(LinearLayout.VERTICAL);
                    c.setPadding(dp(14), 0, 0, 0);
                    h.name = Ph.tv(MainActivity.this, "", 17, Color.WHITE, true);
                    h.name.setSingleLine(true);
                    h.name.setEllipsize(TextUtils.TruncateAt.END);
                    c.addView(h.name);
                    h.sub = Ph.tv(MainActivity.this, "", 13, Ph.TEXT2, false);
                    c.addView(h.sub);
                    row.addView(c, new LinearLayout.LayoutParams(0, -2, 1f));
                    h.time = Ph.tv(MainActivity.this, "", 13, Ph.TEXT2, false);
                    row.addView(h.time);
                    TextView info = Ph.tv(MainActivity.this, "ⓘ", 20, Ph.ACCENT, false);
                    info.setPadding(dp(12), 0, dp(4), 0);
                    info.setTag("info");
                    row.addView(info);
                    row.setTag(h);
                    cv = row;
                } else h = (Holder) cv.getTag();
                Entry e = items.get(pos);
                h.av.setImageBitmap(e.avatar);
                h.name.setText(e.count > 1 ? e.name + " (" + e.count + ")" : e.name);
                LinearLayout row = (LinearLayout) cv;
                View info = row.findViewWithTag("info");
                info.setOnClickListener(v -> details(e));
                if (calls) {
                    boolean missed = e.type == CallLog.Calls.MISSED_TYPE;
                    h.name.setTextColor(missed ? Ph.RED : Color.WHITE);
                    String arrow = e.type == CallLog.Calls.OUTGOING_TYPE ? "↗ wychodzące" : missed ? "✕ nieodebrane" : "↙ przychodzące";
                    h.sub.setText(arrow + (e.name.equals(e.number) ? "" : "  ·  " + e.number));
                    h.time.setText(Ph.when(e.date));
                    h.time.setVisibility(View.VISIBLE);
                } else {
                    h.name.setTextColor(Color.WHITE);
                    h.sub.setText(e.number);
                    h.time.setVisibility(View.GONE);
                }
                return cv;
            }
        };
    }

    /** Karta kontaktu / numeru: zadzwoń, wiadomość, kopiuj, wszystkie numery. */
    private void details(Entry e) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER_HORIZONTAL);
        box.setPadding(dp(22), dp(26), dp(22), dp(18));
        box.setBackground(Ph.round(0xF01C1C22, dp(28)));
        ImageView av = new ImageView(this);
        av.setImageBitmap(Ph.avatar(this, e.name, e.photo, dp(88)));
        box.addView(av, new LinearLayout.LayoutParams(dp(88), dp(88)));
        TextView n = Ph.tv(this, e.name, 22, Color.WHITE, true);
        n.setGravity(Gravity.CENTER);
        n.setPadding(0, dp(12), 0, dp(14));
        box.addView(n, new LinearLayout.LayoutParams(-1, -2));
        AlertDialog dlg = new AlertDialog.Builder(this).setView(box).create();

        List<String> numbers = new ArrayList<>();
        if (e.contactId >= 0 && checkSelfPermission(Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED) {
            try (Cursor c = getContentResolver().query(ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                    new String[]{ContactsContract.CommonDataKinds.Phone.NUMBER},
                    ContactsContract.CommonDataKinds.Phone.CONTACT_ID + "=?", new String[]{String.valueOf(e.contactId)}, null)) {
                while (c != null && c.moveToNext()) {
                    String num = c.getString(0);
                    if (num != null && !numbers.contains(num)) numbers.add(num);
                }
            } catch (Exception ignored) {
            }
        }
        if (numbers.isEmpty() && e.number != null) numbers.add(e.number);
        for (String num : numbers) {
            LinearLayout row = new LinearLayout(this);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(dp(14), dp(10), dp(10), dp(10));
            row.setBackground(Ph.round(0x14FFFFFF, dp(16)));
            TextView t = Ph.tv(this, num, 17, Color.WHITE, false);
            row.addView(t, new LinearLayout.LayoutParams(0, -2, 1f));
            ImageView sms = new ImageView(this);
            sms.setImageResource(R.drawable.ic_message);
            sms.setScaleType(ImageView.ScaleType.CENTER);
            sms.setBackground(Ph.circle(0xFF2A3550));
            sms.setOnClickListener(v -> { dlg.dismiss(); Ph.sms(this, num); });
            row.addView(sms, new LinearLayout.LayoutParams(dp(42), dp(42)));
            ImageView ph = new ImageView(this);
            ph.setImageResource(R.drawable.ic_phone);
            ph.setScaleType(ImageView.ScaleType.CENTER);
            ph.setBackground(Ph.circle(Ph.GREEN));
            ph.setOnClickListener(v -> { dlg.dismiss(); Ph.call(this, num); });
            LinearLayout.LayoutParams plp = new LinearLayout.LayoutParams(dp(42), dp(42));
            plp.leftMargin = dp(8);
            row.addView(ph, plp);
            row.setOnLongClickListener(v -> {
                ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                cm.setPrimaryClip(ClipData.newPlainText("numer", num));
                Toast.makeText(this, "Skopiowano numer", Toast.LENGTH_SHORT).show();
                return true;
            });
            LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(-1, -2);
            rlp.bottomMargin = dp(8);
            box.addView(row, rlp);
        }
        if (e.contactId < 0 && e.number != null && !e.number.isEmpty()) {
            TextView add = Ph.tv(this, "＋ Dodaj do kontaktów", 15, Ph.ACCENT, true);
            add.setPadding(0, dp(8), 0, 0);
            add.setOnClickListener(v -> {
                dlg.dismiss();
                Intent i = new Intent(Intent.ACTION_INSERT_OR_EDIT).setType(ContactsContract.Contacts.CONTENT_ITEM_TYPE)
                        .putExtra(ContactsContract.Intents.Insert.PHONE, e.number);
                try {
                    startActivity(i);
                } catch (Exception ignored) {
                }
            });
            box.addView(add);
        } else if (e.contactId >= 0) {
            TextView open = Ph.tv(this, "Otwórz w Kontaktach", 15, Ph.ACCENT, true);
            open.setPadding(0, dp(8), 0, 0);
            open.setOnClickListener(v -> {
                dlg.dismiss();
                try {
                    startActivity(new Intent(Intent.ACTION_VIEW, Uri.withAppendedPath(ContactsContract.Contacts.CONTENT_URI, String.valueOf(e.contactId))));
                } catch (Exception ignored) {
                }
            });
            box.addView(open);
        }
        dlg.show();
        if (dlg.getWindow() != null) dlg.getWindow().setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
    }
}
