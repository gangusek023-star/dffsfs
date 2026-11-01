package pl.fala.sms;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.role.RoleManager;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.database.ContentObserver;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Telephony;
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

/** Lista rozmów + ekran ustawienia jako domyślnej aplikacji SMS. */
public class MainActivity extends Activity {

    static class Thread_ {
        long id;
        String address;
        String snippet;
        long date;
        boolean read;
        Sms.Contact contact;
        Bitmap avatar;
    }

    private static final int REQ_ROLE = 3, REQ_PERMS = 4;

    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService exec = Executors.newSingleThreadExecutor();
    private final List<Thread_> all = new ArrayList<>();
    private final List<Thread_> shown = new ArrayList<>();
    private final Map<Long, Bitmap> avatars = new HashMap<>();
    private FrameLayout root;
    private ListView list;
    private Adapter adapter;
    private TextView empty;
    private String query = "";
    private ContentObserver observer;
    private final Runnable reload = this::load;

    private int dp(float v) {
        return Ui.dp(this, v);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setStatusBarColor(Ui.BG);
        getWindow().setNavigationBarColor(Ui.BG);
        root = new FrameLayout(this);
        root.setBackgroundColor(Ui.BG);
        setContentView(root);
        Sms.ensureChannel(this);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (!Sms.isDefault(this)) {
            showSetup();
            return;
        }
        if (list == null || list.getParent() == null) buildList();
        askPermissions();
        if (observer == null) {
            observer = new ContentObserver(main) {
                @Override
                public void onChange(boolean selfChange) {
                    main.removeCallbacks(reload);
                    main.postDelayed(reload, 250);
                }
            };
            getContentResolver().registerContentObserver(Uri.parse("content://mms-sms/"), true, observer);
            getContentResolver().registerContentObserver(Telephony.Sms.CONTENT_URI, true, observer);
        }
        load();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (observer != null) getContentResolver().unregisterContentObserver(observer);
        exec.shutdownNow();
    }

    private void askPermissions() {
        List<String> need = new ArrayList<>();
        for (String p : new String[]{Manifest.permission.READ_CONTACTS, Manifest.permission.READ_PHONE_STATE,
                Manifest.permission.READ_SMS, Manifest.permission.SEND_SMS, Manifest.permission.RECEIVE_SMS}) {
            if (checkSelfPermission(p) != PackageManager.PERMISSION_GRANTED) need.add(p);
        }
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            need.add(Manifest.permission.POST_NOTIFICATIONS);
        }
        if (!need.isEmpty()) requestPermissions(need.toArray(new String[0]), REQ_PERMS);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        Sms.clearContacts();
        avatars.clear();
        load();
    }

    // ---------- ustawienie jako domyślnej ----------

    private void showSetup() {
        root.removeAllViews();
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setGravity(Gravity.CENTER);
        c.setPadding(dp(32), dp(32), dp(32), dp(32));
        ImageView logo = new ImageView(this);
        logo.setImageResource(R.drawable.ic_bubble);
        logo.setScaleType(ImageView.ScaleType.CENTER);
        logo.setBackground(Ui.accent(dp(30)));
        logo.setColorFilter(Color.WHITE);
        c.addView(logo, new LinearLayout.LayoutParams(dp(96), dp(96)));
        TextView t = Ui.tv(this, "Fala Wiadomości", 28, Color.WHITE, true);
        t.setPadding(0, dp(22), 0, dp(8));
        c.addView(t);
        TextView d = Ui.tv(this, "Żeby wysyłać i odbierać SMS-y, ustaw Falę jako domyślną aplikację do wiadomości. "
                + "Twoje dotychczasowe rozmowy zostaną – są zapisane w telefonie.", 15, Ui.TEXT2, false);
        d.setGravity(Gravity.CENTER);
        d.setLineSpacing(dp(3), 1f);
        c.addView(d);
        TextView b = Ui.button(this, "Ustaw jako domyślną", true);
        b.setOnClickListener(v -> requestDefault());
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(-1, -2);
        blp.topMargin = dp(28);
        c.addView(b, blp);
        TextView mms = Ui.tv(this, "Uwaga: MMS-y (zdjęcia przez SMS) nie są pobierane. Starsze MMS-y zobaczysz w rozmowach.", 12, 0x66FFFFFF, false);
        mms.setGravity(Gravity.CENTER);
        mms.setPadding(0, dp(18), 0, 0);
        c.addView(mms);
        root.addView(c, new FrameLayout.LayoutParams(-1, -1));
    }

    private void requestDefault() {
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                RoleManager rm = getSystemService(RoleManager.class);
                if (rm != null && rm.isRoleAvailable(RoleManager.ROLE_SMS)) {
                    startActivityForResult(rm.createRequestRoleIntent(RoleManager.ROLE_SMS), REQ_ROLE);
                    return;
                }
            }
            Intent i = new Intent(Telephony.Sms.Intents.ACTION_CHANGE_DEFAULT);
            i.putExtra(Telephony.Sms.Intents.EXTRA_PACKAGE_NAME, getPackageName());
            startActivityForResult(i, REQ_ROLE);
        } catch (Exception e) {
            Toast.makeText(this, "Ustaw Falę w Ustawienia → Aplikacje → Aplikacje domyślne → SMS", Toast.LENGTH_LONG).show();
        }
    }

    // ---------- lista rozmów ----------

    private void buildList() {
        root.removeAllViews();
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);

        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.VERTICAL);
        header.setPadding(dp(20), dp(26), dp(20), dp(8));
        header.addView(Ui.tv(this, "Wiadomości", 34, Color.WHITE, true));
        LinearLayout search = new LinearLayout(this);
        search.setGravity(Gravity.CENTER_VERTICAL);
        search.setBackground(Ui.round(0xFF1C1C24, dp(14)));
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
        q.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void afterTextChanged(Editable s) { query = s.toString().trim().toLowerCase(Locale.ROOT); filter(); }
        });
        search.addView(q, new LinearLayout.LayoutParams(0, dp(44), 1f));
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(-1, -2);
        slp.topMargin = dp(12);
        header.addView(search, slp);
        col.addView(header);

        list = new ListView(this);
        list.setDivider(null);
        list.setSelector(new ColorDrawable(Color.TRANSPARENT));
        list.setClipToPadding(false);
        list.setPadding(dp(8), 0, dp(8), dp(100));
        adapter = new Adapter();
        list.setAdapter(adapter);
        list.setOnItemClickListener((p, v, pos, id) -> open(shown.get(pos)));
        list.setOnItemLongClickListener((p, v, pos, id) -> {
            menu(shown.get(pos));
            return true;
        });
        col.addView(list, new LinearLayout.LayoutParams(-1, 0, 1f));
        root.addView(col, new FrameLayout.LayoutParams(-1, -1));

        empty = Ui.tv(this, "Brak wiadomości.\nDotknij ✎, aby napisać pierwszą.", 15, Ui.TEXT2, false);
        empty.setGravity(Gravity.CENTER);
        empty.setVisibility(View.GONE);
        root.addView(empty, new FrameLayout.LayoutParams(-1, -2, Gravity.CENTER));

        TextView fab = Ui.tv(this, "✎", 26, Color.WHITE, true);
        fab.setGravity(Gravity.CENTER);
        GradientDrawable g = new GradientDrawable(GradientDrawable.Orientation.TL_BR, new int[]{0xFF5A8DFF, Ui.ACCENT2});
        g.setShape(GradientDrawable.OVAL);
        fab.setBackground(g);
        fab.setElevation(dp(8));
        fab.setOnClickListener(v -> startActivity(new Intent(this, ConversationActivity.class)));
        FrameLayout.LayoutParams flp = new FrameLayout.LayoutParams(dp(62), dp(62), Gravity.BOTTOM | Gravity.END);
        flp.rightMargin = dp(22);
        flp.bottomMargin = dp(28);
        root.addView(fab, flp);
    }

    private void open(Thread_ t) {
        startActivity(new Intent(this, ConversationActivity.class)
                .putExtra("thread", t.id).putExtra("address", t.address));
    }

    private void menu(Thread_ t) {
        String[] items = {t.read ? "Oznacz jako nieprzeczytane" : "Oznacz jako przeczytane", "Zadzwoń", "Usuń rozmowę"};
        new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle(t.contact != null ? t.contact.name : t.address)
                .setItems(items, (d, w) -> {
                    if (w == 0) {
                        exec.execute(() -> {
                            if (t.read) {
                                android.content.ContentValues v = new android.content.ContentValues();
                                v.put(Telephony.Sms.READ, 0);
                                try {
                                    getContentResolver().update(Telephony.Sms.CONTENT_URI, v, Telephony.Sms.THREAD_ID + "=? AND "
                                            + Telephony.Sms.TYPE + "=1", new String[]{String.valueOf(t.id)});
                                } catch (Exception ignored) {
                                }
                            } else Sms.markRead(this, t.id);
                        });
                    } else if (w == 1) {
                        try {
                            startActivity(new Intent(Intent.ACTION_DIAL, Uri.parse("tel:" + t.address)));
                        } catch (Exception ignored) {
                        }
                    } else {
                        new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                                .setTitle("Usunąć rozmowę?")
                                .setMessage("Wszystkie wiadomości z tej rozmowy zostaną usunięte.")
                                .setPositiveButton("Usuń", (d2, w2) -> exec.execute(() -> {
                                    try {
                                        getContentResolver().delete(Uri.parse("content://mms-sms/conversations/" + t.id), null, null);
                                    } catch (Exception e) {
                                        main.post(() -> Toast.makeText(this, "Nie udało się usunąć", Toast.LENGTH_SHORT).show());
                                    }
                                }))
                                .setNegativeButton("Anuluj", null).show();
                    }
                }).show();
    }

    private void load() {
        if (checkSelfPermission(Manifest.permission.READ_SMS) != PackageManager.PERMISSION_GRANTED) return;
        exec.execute(() -> {
            List<Thread_> out = queryThreads();
            for (Thread_ t : out) {
                t.contact = Sms.contact(this, t.address);
                Bitmap a = avatars.get(t.id);
                if (a == null) {
                    a = Sms.avatar(this, t.contact, dp(52));
                    avatars.put(t.id, a);
                }
                t.avatar = a;
            }
            main.post(() -> {
                all.clear();
                all.addAll(out);
                filter();
            });
        });
    }

    /** Rozmowy: z tabeli wątków (SMS + MMS), a gdy niedostępna – grupowanie SMS-ów. */
    private List<Thread_> queryThreads() {
        List<Thread_> out = new ArrayList<>();
        Map<String, String> addr = new HashMap<>();
        try (Cursor c = getContentResolver().query(Uri.parse("content://mms-sms/canonical-addresses"),
                new String[]{"_id", "address"}, null, null, null)) {
            while (c != null && c.moveToNext()) addr.put(c.getString(0), c.getString(1));
        } catch (Exception ignored) {
        }
        try (Cursor c = getContentResolver().query(Uri.parse("content://mms-sms/conversations?simple=true"),
                new String[]{"_id", "date", "snippet", "read", "recipient_ids", "message_count"}, null, null, "date DESC")) {
            while (c != null && c.moveToNext()) {
                if (c.getInt(5) == 0) continue;
                Thread_ t = new Thread_();
                t.id = c.getLong(0);
                t.date = c.getLong(1);
                t.snippet = c.getString(2);
                t.read = c.getInt(3) != 0;
                String rid = c.getString(4);
                String first = rid == null ? "" : rid.trim().split(" ")[0];
                t.address = addr.containsKey(first) ? addr.get(first) : "";
                if (TextUtils.isEmpty(t.snippet)) t.snippet = "📷 MMS";
                out.add(t);
            }
            if (!out.isEmpty()) return out;
        } catch (Exception ignored) {
        }
        Map<Long, Thread_> byId = new HashMap<>();
        try (Cursor c = getContentResolver().query(Telephony.Sms.CONTENT_URI,
                new String[]{Telephony.Sms.THREAD_ID, Telephony.Sms.ADDRESS, Telephony.Sms.BODY, Telephony.Sms.DATE, Telephony.Sms.READ},
                null, null, Telephony.Sms.DATE + " DESC")) {
            while (c != null && c.moveToNext()) {
                long id = c.getLong(0);
                Thread_ t = byId.get(id);
                if (t == null) {
                    t = new Thread_();
                    t.id = id;
                    t.address = c.getString(1);
                    t.snippet = c.getString(2);
                    t.date = c.getLong(3);
                    t.read = true;
                    byId.put(id, t);
                    out.add(t);
                }
                if (c.getInt(4) == 0) t.read = false;
            }
        } catch (Exception ignored) {
        }
        return out;
    }

    private void filter() {
        shown.clear();
        for (Thread_ t : all) {
            if (query.isEmpty()) { shown.add(t); continue; }
            String n = t.contact != null ? t.contact.name.toLowerCase(Locale.ROOT) : "";
            if (n.contains(query) || (t.address != null && t.address.contains(query))
                    || (t.snippet != null && t.snippet.toLowerCase(Locale.ROOT).contains(query))) shown.add(t);
        }
        if (adapter != null) adapter.notifyDataSetChanged();
        if (empty != null) empty.setVisibility(all.isEmpty() ? View.VISIBLE : View.GONE);
    }

    static class Holder {
        ImageView av;
        TextView name, time, snippet;
        View dot;
    }

    private class Adapter extends BaseAdapter {
        @Override public int getCount() { return shown.size(); }
        @Override public Object getItem(int p) { return shown.get(p); }
        @Override public long getItemId(int p) { return shown.get(p).id; }

        @Override
        public View getView(int pos, View cv, ViewGroup parent) {
            Holder h;
            if (cv == null) {
                LinearLayout row = new LinearLayout(MainActivity.this);
                row.setGravity(Gravity.CENTER_VERTICAL);
                row.setPadding(dp(12), dp(10), dp(12), dp(10));
                row.setBackground(Ui.ripple(MainActivity.this));
                h = new Holder();
                h.dot = new View(MainActivity.this);
                h.dot.setBackground(Ui.round(Ui.ACCENT, dp(5)));
                LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(dp(10), dp(10));
                dlp.rightMargin = dp(8);
                row.addView(h.dot, dlp);
                h.av = new ImageView(MainActivity.this);
                row.addView(h.av, new LinearLayout.LayoutParams(dp(52), dp(52)));
                LinearLayout col = new LinearLayout(MainActivity.this);
                col.setOrientation(LinearLayout.VERTICAL);
                col.setPadding(dp(14), 0, 0, 0);
                LinearLayout top = new LinearLayout(MainActivity.this);
                h.name = Ui.tv(MainActivity.this, "", 17, Color.WHITE, true);
                h.name.setSingleLine(true);
                h.name.setEllipsize(TextUtils.TruncateAt.END);
                top.addView(h.name, new LinearLayout.LayoutParams(0, -2, 1f));
                h.time = Ui.tv(MainActivity.this, "", 13, Ui.TEXT2, false);
                top.addView(h.time);
                col.addView(top);
                h.snippet = Ui.tv(MainActivity.this, "", 15, Ui.TEXT2, false);
                h.snippet.setMaxLines(2);
                h.snippet.setEllipsize(TextUtils.TruncateAt.END);
                h.snippet.setPadding(0, dp(2), 0, 0);
                col.addView(h.snippet);
                row.addView(col, new LinearLayout.LayoutParams(0, -2, 1f));
                row.setTag(h);
                cv = row;
            } else h = (Holder) cv.getTag();
            Thread_ t = shown.get(pos);
            h.av.setImageBitmap(t.avatar);
            h.name.setText(t.contact != null ? t.contact.name : t.address);
            h.time.setText(Ui.shortTime(t.date));
            h.snippet.setText(t.snippet);
            h.dot.setVisibility(t.read ? View.INVISIBLE : View.VISIBLE);
            h.name.setTypeface(Typeface.create(t.read ? "sans-serif-medium" : "sans-serif-black", Typeface.NORMAL));
            h.snippet.setTextColor(t.read ? Ui.TEXT2 : 0xE6FFFFFF);
            return cv;
        }
    }
}
