package pl.fala.sms;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.database.ContentObserver;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.ContactsContract;
import android.provider.Telephony;
import android.telephony.SmsMessage;
import android.text.Editable;
import android.text.InputType;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.inputmethod.InputMethodManager;
import android.widget.AbsListView;
import android.widget.BaseAdapter;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Rozmowa (dymki, pisanie, wysyłanie) oraz tryb „nowa wiadomość” z wyborem odbiorcy. */
public class ConversationActivity extends Activity {

    static class Msg {
        long id;
        String body;
        long date;
        int type;     // 1 odebrana, 2 wysłana, 4 wysyłana, 5 błąd
        int status;
        boolean mms;
        Uri image;
        Bitmap bitmap;
        boolean header;
        String headerText;
    }

    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService exec = Executors.newSingleThreadExecutor();
    private final List<Msg> msgs = new ArrayList<>();
    private long threadId = -1;
    private String address = null;
    private int subId = -1;
    private List<Sms.Sim> sims = new ArrayList<>();

    private LinearLayout rootCol;
    private ListView list;
    private Adapter adapter;
    private EditText input;
    private TextView sendBtn, counter, simChip;
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
        getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        rootCol = new LinearLayout(this);
        rootCol.setOrientation(LinearLayout.VERTICAL);
        rootCol.setBackgroundColor(Ui.BG);
        setContentView(rootCol);
        handleIntent(getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleIntent(intent);
    }

    private void handleIntent(Intent i) {
        threadId = i.getLongExtra("thread", -1);
        address = i.getStringExtra("address");
        String body = i.getStringExtra("sms_body");
        if (body == null) body = i.getStringExtra(Intent.EXTRA_TEXT);
        Uri data = i.getData();
        if (data != null && address == null) {
            String ssp = data.getSchemeSpecificPart();
            if (ssp != null) {
                int q = ssp.indexOf('?');
                if (q >= 0) ssp = ssp.substring(0, q);
                ssp = ssp.replace("//", "").trim();
                if (!ssp.isEmpty()) address = ssp.split("[,;]")[0];
            }
            String b = data.getQueryParameter("body");
            if (body == null && b != null) body = b;
        }
        if (address != null && threadId < 0) threadId = Sms.threadId(this, address);
        rootCol.removeAllViews();
        if (address == null || address.isEmpty()) {
            buildPicker(body);
        } else {
            buildConversation(body);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (threadId >= 0) {
            final long t = threadId;
            exec.execute(() -> Sms.markRead(this, t));
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (observer != null) getContentResolver().unregisterContentObserver(observer);
        exec.shutdownNow();
    }

    // ---------- nowa wiadomość: wybór odbiorcy ----------

    private void buildPicker(String body) {
        LinearLayout bar = topBar("Nowa wiadomość", null);
        rootCol.addView(bar);
        LinearLayout to = new LinearLayout(this);
        to.setGravity(Gravity.CENTER_VERTICAL);
        to.setPadding(dp(16), dp(4), dp(16), dp(8));
        to.addView(Ui.tv(this, "Do:", 16, Ui.TEXT2, true));
        EditText field = new EditText(this);
        field.setBackground(null);
        field.setHint("Imię lub numer");
        field.setHintTextColor(0x66FFFFFF);
        field.setTextColor(Color.WHITE);
        field.setSingleLine(true);
        field.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17);
        field.setPadding(dp(10), 0, 0, 0);
        to.addView(field, new LinearLayout.LayoutParams(0, dp(48), 1f));
        TextView go = Ui.tv(this, "Dalej", 15, Color.WHITE, true);
        go.setPadding(dp(14), dp(8), dp(14), dp(8));
        go.setBackground(Ui.accent(dp(16)));
        to.addView(go);
        rootCol.addView(to);
        View line = new View(this);
        line.setBackgroundColor(0x1AFFFFFF);
        rootCol.addView(line, new LinearLayout.LayoutParams(-1, 1));

        ListView results = new ListView(this);
        results.setDivider(null);
        results.setSelector(new ColorDrawable(Color.TRANSPARENT));
        List<String[]> found = new ArrayList<>();
        BaseAdapter ad = new BaseAdapter() {
            @Override public int getCount() { return found.size(); }
            @Override public Object getItem(int p) { return found.get(p); }
            @Override public long getItemId(int p) { return p; }

            @Override
            public View getView(int p, View cv, ViewGroup parent) {
                LinearLayout row = new LinearLayout(ConversationActivity.this);
                row.setGravity(Gravity.CENTER_VERTICAL);
                row.setPadding(dp(16), dp(10), dp(16), dp(10));
                row.setBackground(Ui.ripple(ConversationActivity.this));
                ImageView av = new ImageView(ConversationActivity.this);
                Sms.Contact k = new Sms.Contact();
                k.name = found.get(p)[0];
                k.number = found.get(p)[1];
                k.photo = found.get(p)[2] != null ? Uri.parse(found.get(p)[2]) : null;
                av.setImageBitmap(Sms.avatar(ConversationActivity.this, k, dp(44)));
                row.addView(av, new LinearLayout.LayoutParams(dp(44), dp(44)));
                LinearLayout col = new LinearLayout(ConversationActivity.this);
                col.setOrientation(LinearLayout.VERTICAL);
                col.setPadding(dp(14), 0, 0, 0);
                col.addView(Ui.tv(ConversationActivity.this, k.name, 16, Color.WHITE, true));
                col.addView(Ui.tv(ConversationActivity.this, k.number, 14, Ui.TEXT2, false));
                row.addView(col);
                return row;
            }
        };
        results.setAdapter(ad);
        results.setOnItemClickListener((p, v, pos, id) -> openTo(found.get(pos)[1], body));
        rootCol.addView(results, new LinearLayout.LayoutParams(-1, 0, 1f));

        Runnable search = () -> {
            String q = field.getText().toString().trim();
            exec.execute(() -> {
                List<String[]> r = new ArrayList<>();
                if (checkSelfPermission(Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED) {
                    Uri uri = q.isEmpty() ? ContactsContract.CommonDataKinds.Phone.CONTENT_URI
                            : Uri.withAppendedPath(ContactsContract.CommonDataKinds.Phone.CONTENT_FILTER_URI, Uri.encode(q));
                    try (Cursor c = getContentResolver().query(uri, new String[]{
                                    ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                                    ContactsContract.CommonDataKinds.Phone.NUMBER,
                                    ContactsContract.CommonDataKinds.Phone.PHOTO_THUMBNAIL_URI},
                            null, null, ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME + " ASC")) {
                        java.util.Set<String> seen = new java.util.HashSet<>();
                        while (c != null && c.moveToNext() && r.size() < 80) {
                            String num = c.getString(1);
                            if (num == null || !seen.add(Sms.normalize(num))) continue;
                            r.add(new String[]{c.getString(0), num, c.getString(2)});
                        }
                    } catch (Exception ignored) {
                    }
                }
                main.post(() -> {
                    found.clear();
                    found.addAll(r);
                    ad.notifyDataSetChanged();
                });
            });
        };
        field.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void afterTextChanged(Editable s) {
                main.removeCallbacks(search);
                main.postDelayed(search, 200);
            }
        });
        go.setOnClickListener(v -> {
            String n = field.getText().toString().trim();
            if (Sms.normalize(n).length() >= 3) openTo(Sms.normalize(n), body);
            else if (!found.isEmpty()) openTo(found.get(0)[1], body);
            else Toast.makeText(this, "Wpisz numer telefonu", Toast.LENGTH_SHORT).show();
        });
        search.run();
        field.requestFocus();
        field.postDelayed(() -> ((InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE))
                .showSoftInput(field, InputMethodManager.SHOW_IMPLICIT), 200);
    }

    private void openTo(String number, String body) {
        address = number;
        threadId = Sms.threadId(this, number);
        rootCol.removeAllViews();
        buildConversation(body);
    }

    // ---------- rozmowa ----------

    private LinearLayout topBar(String title, Sms.Contact k) {
        LinearLayout bar = new LinearLayout(this);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(dp(6), dp(10), dp(12), dp(10));
        TextView back = Ui.tv(this, "‹", 34, Ui.ACCENT, false);
        back.setPadding(dp(12), 0, dp(12), dp(4));
        back.setOnClickListener(v -> finish());
        bar.addView(back);
        if (k != null) {
            ImageView av = new ImageView(this);
            exec.execute(() -> {
                Bitmap b = Sms.avatar(this, k, dp(40));
                main.post(() -> av.setImageBitmap(b));
            });
            bar.addView(av, new LinearLayout.LayoutParams(dp(40), dp(40)));
        }
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(dp(12), 0, 0, 0);
        TextView t = Ui.tv(this, title, 18, Color.WHITE, true);
        t.setSingleLine(true);
        t.setEllipsize(TextUtils.TruncateAt.END);
        col.addView(t);
        if (k != null && !k.name.equals(k.number)) col.addView(Ui.tv(this, k.number, 13, Ui.TEXT2, false));
        bar.addView(col, new LinearLayout.LayoutParams(0, -2, 1f));
        if (k != null) {
            TextView call = Ui.tv(this, "📞", 20, Color.WHITE, false);
            call.setGravity(Gravity.CENTER);
            call.setBackground(Ui.round(0x1FFFFFFF, dp(20)));
            call.setOnClickListener(v -> {
                try {
                    startActivity(new Intent(Intent.ACTION_DIAL, Uri.parse("tel:" + address)));
                } catch (Exception ignored) {
                }
            });
            bar.addView(call, new LinearLayout.LayoutParams(dp(40), dp(40)));
        }
        return bar;
    }

    private void buildConversation(String prefill) {
        Sms.Contact k = Sms.contact(this, address);
        rootCol.addView(topBar(k.name, k));
        View line = new View(this);
        line.setBackgroundColor(0x14FFFFFF);
        rootCol.addView(line, new LinearLayout.LayoutParams(-1, 1));

        list = new ListView(this);
        list.setDivider(null);
        list.setSelector(new ColorDrawable(Color.TRANSPARENT));
        list.setStackFromBottom(true);
        list.setTranscriptMode(AbsListView.TRANSCRIPT_MODE_NORMAL);
        list.setClipToPadding(false);
        list.setPadding(dp(12), dp(8), dp(12), dp(8));
        adapter = new Adapter();
        list.setAdapter(adapter);
        list.setOnItemLongClickListener((p, v, pos, id) -> {
            Msg m = msgs.get(pos);
            if (!m.header) bubbleMenu(m);
            return true;
        });
        list.setOnItemClickListener((p, v, pos, id) -> {
            Msg m = msgs.get(pos);
            if (m.type == Telephony.Sms.MESSAGE_TYPE_FAILED && !m.mms) retry(m);
        });
        rootCol.addView(list, new LinearLayout.LayoutParams(-1, 0, 1f));

        // pasek pisania
        LinearLayout composer = new LinearLayout(this);
        composer.setOrientation(LinearLayout.VERTICAL);
        composer.setPadding(dp(10), dp(6), dp(10), dp(10));
        sims = Sms.sims(this);
        subId = Sms.defaultSub();
        if (sims.size() > 1) {
            simChip = Ui.tv(this, "", 13, Color.WHITE, true);
            simChip.setPadding(dp(12), dp(6), dp(12), dp(6));
            simChip.setBackground(Ui.round(0x1FFFFFFF, dp(12)));
            simChip.setOnClickListener(v -> {
                int idx = 0;
                for (int i = 0; i < sims.size(); i++) if (sims.get(i).subId == subId) idx = i;
                subId = sims.get((idx + 1) % sims.size()).subId;
                updateSim();
            });
            LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(-2, -2);
            slp.bottomMargin = dp(6);
            slp.leftMargin = dp(6);
            composer.addView(simChip, slp);
            updateSim();
        }
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.BOTTOM);
        LinearLayout pill = new LinearLayout(this);
        pill.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable pg = Ui.round(0xFF1C1C24, dp(22));
        pg.setStroke(dp(1), 0x26FFFFFF);
        pill.setBackground(pg);
        pill.setPadding(dp(14), dp(2), dp(14), dp(2));
        input = new EditText(this);
        input.setBackground(null);
        input.setHint("Wiadomość SMS");
        input.setHintTextColor(0x66FFFFFF);
        input.setTextColor(Color.WHITE);
        input.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
                | InputType.TYPE_TEXT_FLAG_MULTI_LINE | InputType.TYPE_TEXT_FLAG_AUTO_CORRECT);
        input.setMaxLines(6);
        input.setMinHeight(dp(44));
        pill.addView(input, new LinearLayout.LayoutParams(-1, -2));
        counter = Ui.tv(this, "", 11, Ui.TEXT2, false);
        counter.setGravity(Gravity.END);
        counter.setVisibility(View.GONE);
        pill.addView(counter, new LinearLayout.LayoutParams(-1, -2));
        row.addView(pill, new LinearLayout.LayoutParams(0, -2, 1f));
        sendBtn = Ui.tv(this, "↑", 22, Color.WHITE, true);
        sendBtn.setGravity(Gravity.CENTER);
        GradientDrawable sg = new GradientDrawable(GradientDrawable.Orientation.TL_BR, new int[]{0xFF5A8DFF, Ui.ACCENT2});
        sg.setShape(GradientDrawable.OVAL);
        sendBtn.setBackground(sg);
        sendBtn.setAlpha(0.35f);
        sendBtn.setOnClickListener(v -> send());
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(dp(46), dp(46));
        blp.leftMargin = dp(8);
        row.addView(sendBtn, blp);
        composer.addView(row);
        rootCol.addView(composer);

        input.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) { }

            @Override
            public void afterTextChanged(Editable s) {
                boolean has = s.toString().trim().length() > 0;
                sendBtn.animate().alpha(has ? 1f : 0.35f).scaleX(has ? 1f : 0.9f).scaleY(has ? 1f : 0.9f).setDuration(150).start();
                if (has) {
                    int[] l = SmsMessage.calculateLength(s, false);
                    if (l[0] > 1 || l[2] < 20) {
                        counter.setVisibility(View.VISIBLE);
                        counter.setText(l[2] + " / " + l[0] + (l[0] > 1 ? " SMS" : ""));
                    } else counter.setVisibility(View.GONE);
                } else counter.setVisibility(View.GONE);
            }
        });
        if (prefill != null) input.setText(prefill);

        if (observer == null) {
            observer = new ContentObserver(main) {
                @Override
                public void onChange(boolean selfChange) {
                    main.removeCallbacks(reload);
                    main.postDelayed(reload, 150);
                }
            };
            getContentResolver().registerContentObserver(Telephony.Sms.CONTENT_URI, true, observer);
            getContentResolver().registerContentObserver(Uri.parse("content://mms-sms/"), true, observer);
        }
        load();
    }

    private void updateSim() {
        if (simChip == null) return;
        String l = "SIM";
        for (Sms.Sim s : sims) if (s.subId == subId) l = s.label;
        simChip.setText("📶 " + l + "  ⇄");
    }

    private void send() {
        String text = input.getText().toString().trim();
        if (text.isEmpty() || address == null) return;
        if (!Sms.isDefault(this)) {
            Toast.makeText(this, "Ustaw Falę jako domyślną aplikację SMS", Toast.LENGTH_LONG).show();
            return;
        }
        input.setText("");
        final int sub = subId;
        exec.execute(() -> {
            Sms.send(this, address, text, sub);
            if (threadId < 0) threadId = Sms.threadId(this, address);
            main.post(this::load);
        });
    }

    private void retry(Msg m) {
        new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle("Nie wysłano")
                .setMessage("Wysłać ponownie?")
                .setPositiveButton("Wyślij", (d, w) -> exec.execute(() -> {
                    try {
                        getContentResolver().delete(Uri.withAppendedPath(Telephony.Sms.CONTENT_URI, String.valueOf(m.id)), null, null);
                    } catch (Exception ignored) {
                    }
                    Sms.send(this, address, m.body, subId);
                }))
                .setNegativeButton("Anuluj", null).show();
    }

    private void bubbleMenu(Msg m) {
        String[] items = m.mms ? new String[]{"Kopiuj tekst"} : new String[]{"Kopiuj tekst", "Przekaż dalej", "Usuń"};
        new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setItems(items, (d, w) -> {
                    if (w == 0) {
                        ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                        cm.setPrimaryClip(ClipData.newPlainText("SMS", m.body == null ? "" : m.body));
                        Toast.makeText(this, "Skopiowano", Toast.LENGTH_SHORT).show();
                    } else if (w == 1) {
                        startActivity(new Intent(this, ConversationActivity.class).putExtra("sms_body", m.body));
                    } else {
                        exec.execute(() -> {
                            try {
                                getContentResolver().delete(Uri.withAppendedPath(Telephony.Sms.CONTENT_URI, String.valueOf(m.id)), null, null);
                            } catch (Exception ignored) {
                            }
                        });
                    }
                }).show();
    }

    private void load() {
        if (threadId < 0 || checkSelfPermission(Manifest.permission.READ_SMS) != PackageManager.PERMISSION_GRANTED) return;
        final long tid = threadId;
        exec.execute(() -> {
            List<Msg> raw = new ArrayList<>();
            try (Cursor c = getContentResolver().query(Telephony.Sms.CONTENT_URI,
                    new String[]{Telephony.Sms._ID, Telephony.Sms.BODY, Telephony.Sms.DATE, Telephony.Sms.TYPE, Telephony.Sms.STATUS},
                    Telephony.Sms.THREAD_ID + "=?", new String[]{String.valueOf(tid)}, Telephony.Sms.DATE + " ASC")) {
                while (c != null && c.moveToNext()) {
                    Msg m = new Msg();
                    m.id = c.getLong(0);
                    m.body = c.getString(1);
                    m.date = c.getLong(2);
                    m.type = c.getInt(3);
                    m.status = c.getInt(4);
                    raw.add(m);
                }
            } catch (Exception ignored) {
            }
            loadMms(tid, raw);
            raw.sort((a, b) -> Long.compare(a.date, b.date));
            List<Msg> out = new ArrayList<>();
            long last = 0;
            for (Msg m : raw) {
                if (m.date - last > 20 * 60 * 1000L) {
                    Msg h = new Msg();
                    h.header = true;
                    h.headerText = Ui.dayHeader(m.date);
                    out.add(h);
                }
                last = m.date;
                out.add(m);
            }
            main.post(() -> {
                msgs.clear();
                msgs.addAll(out);
                if (adapter != null) adapter.notifyDataSetChanged();
            });
        });
    }

    /** Starsze MMS-y w rozmowie: tekst i zdjęcia (tylko do odczytu). */
    private void loadMms(long tid, List<Msg> out) {
        try (Cursor c = getContentResolver().query(Uri.parse("content://mms"),
                new String[]{"_id", "date", "msg_box"}, "thread_id=?", new String[]{String.valueOf(tid)}, null)) {
            while (c != null && c.moveToNext()) {
                Msg m = new Msg();
                m.mms = true;
                m.id = c.getLong(0);
                m.date = c.getLong(1) * 1000L;
                m.type = c.getInt(2) == 1 ? 1 : 2;
                StringBuilder sb = new StringBuilder();
                try (Cursor p = getContentResolver().query(Uri.parse("content://mms/part"),
                        new String[]{"_id", "ct", "text"}, "mid=?", new String[]{String.valueOf(m.id)}, null)) {
                    while (p != null && p.moveToNext()) {
                        String ct = p.getString(1);
                        if ("text/plain".equals(ct) && p.getString(2) != null) {
                            if (sb.length() > 0) sb.append('\n');
                            sb.append(p.getString(2));
                        } else if (ct != null && ct.startsWith("image/") && m.image == null) {
                            m.image = Uri.parse("content://mms/part/" + p.getLong(0));
                            m.bitmap = decode(m.image, dp(220));
                        }
                    }
                } catch (Exception ignored) {
                }
                m.body = sb.toString();
                out.add(m);
            }
        } catch (Exception ignored) {
        }
    }

    private Bitmap decode(Uri u, int maxPx) {
        try {
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            try (InputStream is = getContentResolver().openInputStream(u)) {
                BitmapFactory.decodeStream(is, null, o);
            }
            int s = 1;
            while (Math.max(o.outWidth, o.outHeight) / (s * 2) >= maxPx) s *= 2;
            BitmapFactory.Options o2 = new BitmapFactory.Options();
            o2.inSampleSize = s;
            try (InputStream is = getContentResolver().openInputStream(u)) {
                return BitmapFactory.decodeStream(is, null, o2);
            }
        } catch (Exception e) {
            return null;
        }
    }

    // ---------- dymki ----------

    private class Adapter extends BaseAdapter {
        @Override public int getCount() { return msgs.size(); }
        @Override public Object getItem(int p) { return msgs.get(p); }
        @Override public long getItemId(int p) { return p; }
        @Override public int getViewTypeCount() { return 3; }

        @Override
        public int getItemViewType(int p) {
            Msg m = msgs.get(p);
            return m.header ? 0 : (m.type == 1 ? 1 : 2);
        }

        @Override
        public View getView(int pos, View cv, ViewGroup parent) {
            Msg m = msgs.get(pos);
            if (m.header) {
                TextView t = cv instanceof TextView ? (TextView) cv : Ui.tv(ConversationActivity.this, "", 12, Ui.TEXT2, true);
                t.setGravity(Gravity.CENTER);
                t.setPadding(0, dp(14), 0, dp(6));
                t.setText(m.headerText);
                return t;
            }
            boolean mine = m.type != 1;
            LinearLayout row;
            if (cv instanceof LinearLayout) row = (LinearLayout) cv;
            else {
                row = new LinearLayout(ConversationActivity.this);
                row.setOrientation(LinearLayout.VERTICAL);
                row.setGravity(mine ? Gravity.END : Gravity.START);
            }
            row.removeAllViews();
            boolean nextSame = pos + 1 < msgs.size() && !msgs.get(pos + 1).header && (msgs.get(pos + 1).type != 1) == mine;
            row.setPadding(mine ? dp(56) : 0, dp(1), mine ? 0 : dp(56), nextSame ? dp(1) : dp(6));

            float r = dp(20), small = dp(6);
            if (m.bitmap != null) {
                ImageView iv = new ImageView(ConversationActivity.this);
                iv.setImageBitmap(m.bitmap);
                iv.setAdjustViewBounds(true);
                iv.setMaxWidth(dp(220));
                iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
                iv.setClipToOutline(true);
                iv.setBackground(Ui.round(0xFF2A2A33, dp(18)));
                row.addView(iv, new LinearLayout.LayoutParams(-2, -2));
            }
            if (m.body != null && !m.body.isEmpty()) {
                TextView b = Ui.tv(ConversationActivity.this, m.body, 16, Color.WHITE, false);
                b.setPadding(dp(14), dp(9), dp(14), dp(9));
                b.setLineSpacing(dp(1), 1f);
                b.setTextIsSelectable(false);
                b.setAutoLinkMask(android.text.util.Linkify.WEB_URLS | android.text.util.Linkify.PHONE_NUMBERS);
                b.setLinkTextColor(mine ? 0xFFE8EEFF : 0xFF8FB2FF);
                GradientDrawable g;
                if (mine) {
                    g = new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                            m.type == Telephony.Sms.MESSAGE_TYPE_FAILED ? new int[]{0xFF8A3A3A, 0xFF6A2A2A} : new int[]{0xFF5A8DFF, Ui.ACCENT2});
                    g.setCornerRadii(new float[]{r, r, r, r, nextSame ? r : small, nextSame ? r : small, r, r});
                } else {
                    g = new GradientDrawable();
                    g.setColor(0xFF26262E);
                    g.setCornerRadii(new float[]{r, r, r, r, r, r, nextSame ? r : small, nextSame ? r : small});
                }
                b.setBackground(g);
                b.setMaxWidth(Math.round(getResources().getDisplayMetrics().widthPixels * 0.75f));
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
                if (m.bitmap != null) lp.topMargin = dp(3);
                row.addView(b, lp);
            }
            // status pod ostatnią wysłaną
            if (mine && !nextSame && !m.mms) {
                String st;
                int col = Ui.TEXT2;
                if (m.type == Telephony.Sms.MESSAGE_TYPE_FAILED) { st = "Nie wysłano · dotknij, aby ponowić"; col = 0xFFFF6B6B; }
                else if (m.type == Telephony.Sms.MESSAGE_TYPE_OUTBOX || m.type == Telephony.Sms.MESSAGE_TYPE_QUEUED) st = "Wysyłanie…";
                else if (m.status == Telephony.Sms.STATUS_COMPLETE) st = "Dostarczono";
                else st = "Wysłano";
                TextView s = Ui.tv(ConversationActivity.this, st, 11, col, true);
                s.setPadding(dp(4), dp(3), dp(4), 0);
                row.addView(s);
            }
            return row;
        }
    }
}
