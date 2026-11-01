package pl.fala.launcher;

import android.app.Notification;
import android.app.PendingIntent;
import android.app.Person;
import android.app.RemoteInput;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Outline;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.Icon;
import android.os.Build;
import android.os.Bundle;
import android.os.Parcelable;
import android.service.notification.StatusBarNotification;
import android.text.InputType;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.TextUtils;
import android.text.format.DateUtils;
import android.text.style.StyleSpan;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.VelocityTracker;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewOutlineProvider;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * Interaktywna karta powiadomienia: przyciski akcji, szybka odpowiedź,
 * podpowiedzi odpowiedzi, historia wiadomości i odrzucanie przesunięciem.
 */
public class NotifUi {

    public interface Callbacks {
        /** Dotknięcie treści – otwórz aplikację. */
        void onOpen(PendingIntent pi);

        /** Akcja, która otwiera okno aplikacji (np. „Pokaż”) – może wymagać odblokowania. */
        void onActivityAction(PendingIntent pi);

        /** Karta odrzucona lub obsłużona. */
        void onDone();

        /** Pole odpowiedzi chce klawiatury (nakładka musi stać się aktywna). */
        void onWantsKeyboard(boolean want);
    }

    private static int dp(Context c, float v) {
        return Math.round(v * c.getResources().getDisplayMetrics().density);
    }

    public static boolean usable(StatusBarNotification sbn) {
        Bundle ex = sbn.getNotification().extras;
        return !TextUtils.isEmpty(ex.getCharSequence(Notification.EXTRA_TITLE))
                || !TextUtils.isEmpty(ex.getCharSequence(Notification.EXTRA_TEXT));
    }

    /**
     * @param glass true = karta na szkle (ekran blokady), false = przezroczysta (wnętrze wyspy)
     */
    public static View card(Context c, StatusBarNotification sbn, boolean glass, Callbacks cb) {
        final Notification nf = sbn.getNotification();
        final Bundle ex = nf.extras;
        PackageManager pm = c.getPackageManager();
        String appName = sbn.getPackageName();
        Drawable appIcon = null;
        try {
            appName = pm.getApplicationLabel(pm.getApplicationInfo(sbn.getPackageName(), 0)).toString();
            appIcon = pm.getApplicationIcon(sbn.getPackageName());
        } catch (Exception ignored) {
        }

        SwipeCard card = new SwipeCard(c);
        card.sbn = sbn;
        card.cb = cb;
        card.setOrientation(LinearLayout.VERTICAL);
        if (glass) {
            card.setBackground(new GlassDrawable(c.getResources().getDisplayMetrics().density, dp(c, 22), 1.15f));
            card.setPadding(dp(c, 16), dp(c, 14), dp(c, 16), dp(c, 14));
        } else {
            card.setPadding(dp(c, 18), dp(c, 16), dp(c, 18), dp(c, 14));
        }

        // nagłówek: ikona, aplikacja, czas, zamknij
        LinearLayout head = new LinearLayout(c);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        ImageView iv = new ImageView(c);
        iv.setImageDrawable(appIcon);
        head.addView(iv, new LinearLayout.LayoutParams(dp(c, 24), dp(c, 24)));
        TextView app = text(c, appName, 14, 0xCCFFFFFF, true);
        app.setPadding(dp(c, 8), 0, 0, 0);
        app.setSingleLine(true);
        head.addView(app, new LinearLayout.LayoutParams(0, -2, 1f));
        TextView when = text(c, DateUtils.getRelativeTimeSpanString(sbn.getPostTime(), System.currentTimeMillis(),
                DateUtils.MINUTE_IN_MILLIS, DateUtils.FORMAT_ABBREV_RELATIVE).toString(), 13, 0x99FFFFFF, false);
        head.addView(when);
        if (sbn.isClearable()) {
            TextView x = text(c, "✕", 18, 0xB3FFFFFF, false);
            x.setGravity(Gravity.CENTER);
            x.setMinWidth(dp(c, 44));
            x.setMinHeight(dp(c, 40));
            x.setOnClickListener(v -> dismiss(card, sbn, cb, true));
            head.addView(x);
        }
        card.addView(head);

        // treść + awatar
        LinearLayout body = new LinearLayout(c);
        body.setOrientation(LinearLayout.HORIZONTAL);
        body.setPadding(0, dp(c, 8), 0, 0);
        LinearLayout texts = new LinearLayout(c);
        texts.setOrientation(LinearLayout.VERTICAL);
        CharSequence title = ex.getCharSequence(Notification.EXTRA_TITLE);
        if (!TextUtils.isEmpty(title)) {
            TextView t = text(c, title, 17, Color.WHITE, true);
            t.setSingleLine(true);
            t.setEllipsize(TextUtils.TruncateAt.END);
            texts.addView(t);
        }
        final TextView msg = text(c, bodyText(ex), 16, 0xE6FFFFFF, false);
        msg.setMaxLines(4);
        msg.setEllipsize(TextUtils.TruncateAt.END);
        msg.setLineSpacing(dp(c, 2), 1f);
        msg.setPadding(0, dp(c, 2), 0, 0);
        if (msg.getText().length() > 0) texts.addView(msg);
        body.addView(texts, new LinearLayout.LayoutParams(0, -2, 1f));
        Drawable avatar = largeIcon(c, nf);
        if (avatar != null) {
            ImageView av = new ImageView(c);
            av.setImageDrawable(avatar);
            av.setScaleType(ImageView.ScaleType.CENTER_CROP);
            av.setOutlineProvider(new ViewOutlineProvider() {
                @Override
                public void getOutline(View view, Outline outline) {
                    outline.setOval(0, 0, view.getWidth(), view.getHeight());
                }
            });
            av.setClipToOutline(true);
            LinearLayout.LayoutParams alp = new LinearLayout.LayoutParams(dp(c, 46), dp(c, 46));
            alp.leftMargin = dp(c, 10);
            body.addView(av, alp);
        }
        card.addView(body);

        // dotknięcie treści: pierwszy raz rozwija, drugi otwiera aplikację
        final PendingIntent content = nf.contentIntent;
        body.setOnClickListener(v -> {
            if (msg.getMaxLines() <= 4 && msg.getLineCount() > 4) {
                msg.setMaxLines(12);
            } else {
                cb.onOpen(content);
            }
        });

        // akcje
        Notification.Action[] actions = nf.actions;
        if (actions != null && actions.length > 0) {
            LinearLayout reply = new LinearLayout(c);
            reply.setOrientation(LinearLayout.VERTICAL);
            reply.setVisibility(View.GONE);

            LinearLayout row = new LinearLayout(c);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setPadding(0, dp(c, 12), 0, 0);
            int shown = 0;
            for (final Notification.Action a : actions) {
                if (a == null || a.title == null || shown >= 3) continue;
                shown++;
                final RemoteInput[] inputs = a.getRemoteInputs();
                final RemoteInput free = freeInput(inputs);
                TextView b = button(c, a.title.toString(), free != null);
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -2, 1f);
                if (shown > 1) lp.leftMargin = dp(c, 8);
                row.addView(b, lp);
                if (free != null) {
                    b.setOnClickListener(v -> {
                        if (reply.getVisibility() == View.VISIBLE) {
                            reply.setVisibility(View.GONE);
                            cb.onWantsKeyboard(false);
                        } else {
                            buildReply(c, reply, sbn, a, inputs, free, cb, card);
                            reply.setVisibility(View.VISIBLE);
                        }
                    });
                } else {
                    b.setOnClickListener(v -> {
                        boolean activity = Build.VERSION.SDK_INT >= 31 && a.actionIntent != null && a.actionIntent.isActivity();
                        if (activity) {
                            cb.onActivityAction(a.actionIntent);
                        } else {
                            IslandController.sendPending(c, a.actionIntent);
                            confirm(card, "✓  " + a.title);
                            card.postDelayed(cb::onDone, 900);
                        }
                    });
                }
            }
            if (shown > 0) card.addView(row);
            card.addView(reply);
        }

        return card;
    }

    private static RemoteInput freeInput(RemoteInput[] inputs) {
        if (inputs == null) return null;
        for (RemoteInput r : inputs) if (r.getAllowFreeFormInput()) return r;
        return inputs.length > 0 ? inputs[0] : null;
    }

    private static void buildReply(Context c, LinearLayout reply, StatusBarNotification sbn,
                                   Notification.Action a, RemoteInput[] inputs, RemoteInput free,
                                   Callbacks cb, View card) {
        reply.removeAllViews();
        // gotowe podpowiedzi odpowiedzi
        CharSequence[] choices = free.getChoices();
        if (choices != null && choices.length > 0) {
            HorizontalScrollView hs = new HorizontalScrollView(c);
            hs.setHorizontalScrollBarEnabled(false);
            LinearLayout chips = new LinearLayout(c);
            chips.setOrientation(LinearLayout.HORIZONTAL);
            for (CharSequence ch : choices) {
                TextView chip = button(c, ch.toString(), false);
                chip.setPadding(dp(c, 14), dp(c, 8), dp(c, 14), dp(c, 8));
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
                lp.rightMargin = dp(c, 6);
                chips.addView(chip, lp);
                chip.setOnClickListener(v -> send(c, a, inputs, free, ch, card, cb));
            }
            hs.addView(chips);
            LinearLayout.LayoutParams hlp = new LinearLayout.LayoutParams(-1, -2);
            hlp.topMargin = dp(c, 8);
            reply.addView(hs, hlp);
        }
        if (!free.getAllowFreeFormInput()) return;

        LinearLayout bar = new LinearLayout(c);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        GradientDrawable g = new GradientDrawable();
        g.setCornerRadius(dp(c, 20));
        g.setColor(0x26FFFFFF);
        g.setStroke(dp(c, 1), 0x33FFFFFF);
        bar.setBackground(g);
        bar.setPadding(dp(c, 14), 0, dp(c, 4), 0);
        final EditText et = new EditText(c);
        et.setBackground(null);
        CharSequence label = free.getLabel();
        et.setHint(TextUtils.isEmpty(label) ? "Odpowiedz…" : label);
        et.setHintTextColor(0x80FFFFFF);
        et.setTextColor(Color.WHITE);
        et.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17);
        et.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        et.setMaxLines(4);
        et.setImeOptions(EditorInfo.IME_ACTION_SEND);
        bar.addView(et, new LinearLayout.LayoutParams(0, -2, 1f));
        ImageView sendBtn = new ImageView(c);
        sendBtn.setImageResource(R.drawable.ic_send);
        sendBtn.setColorFilter(Color.WHITE);
        sendBtn.setScaleType(ImageView.ScaleType.CENTER);
        bar.addView(sendBtn, new LinearLayout.LayoutParams(dp(c, 48), dp(c, 48)));
        Runnable doSend = () -> {
            String txt = et.getText().toString().trim();
            if (txt.isEmpty()) return;
            InputMethodManager imm = (InputMethodManager) c.getSystemService(Context.INPUT_METHOD_SERVICE);
            imm.hideSoftInputFromWindow(et.getWindowToken(), 0);
            send(c, a, inputs, free, txt, card, cb);
        };
        sendBtn.setOnClickListener(v -> doSend.run());
        et.setOnEditorActionListener((v, id, e) -> {
            if (id == EditorInfo.IME_ACTION_SEND) {
                doSend.run();
                return true;
            }
            return false;
        });
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(-1, -2);
        blp.topMargin = dp(c, 8);
        reply.addView(bar, blp);

        cb.onWantsKeyboard(true);
        et.postDelayed(() -> {
            et.requestFocus();
            InputMethodManager imm = (InputMethodManager) c.getSystemService(Context.INPUT_METHOD_SERVICE);
            imm.showSoftInput(et, InputMethodManager.SHOW_IMPLICIT);
        }, 150);
    }

    private static void send(Context c, Notification.Action a, RemoteInput[] inputs, RemoteInput free,
                             CharSequence text, View card, Callbacks cb) {
        try {
            Intent fill = new Intent();
            Bundle results = new Bundle();
            results.putCharSequence(free.getResultKey(), text);
            RemoteInput.addResultsToIntent(inputs, fill, results);
            a.actionIntent.send(c, 0, fill);
            confirm(card, "✓  Wysłano: " + text);
        } catch (Exception e) {
            confirm(card, "Nie udało się wysłać");
        }
        cb.onWantsKeyboard(false);
        card.postDelayed(cb::onDone, 1100);
    }

    private static void confirm(View card, String msg) {
        if (!(card instanceof LinearLayout)) return;
        LinearLayout l = (LinearLayout) card;
        Context c = l.getContext();
        TextView t = text(c, msg, 14, 0xFF30D158, true);
        t.setPadding(0, dp(c, 10), 0, 0);
        t.setSingleLine(true);
        t.setEllipsize(TextUtils.TruncateAt.END);
        // schowaj akcje i odpowiedź, pokaż potwierdzenie
        for (int i = 2; i < l.getChildCount(); i++) l.getChildAt(i).setVisibility(View.GONE);
        l.addView(t);
    }

    /** Karta, którą można odrzucić przesunięciem w bok – także gdy palec zaczyna na treści. */
    static class SwipeCard extends LinearLayout {
        private final int slop;
        private float downX, downY;
        private boolean dragging = false;
        private VelocityTracker vt;
        StatusBarNotification sbn;
        Callbacks cb;

        SwipeCard(Context c) {
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
                    downX = e.getRawX();
                    downY = e.getRawY();
                    dragging = false;
                    return false;
                case MotionEvent.ACTION_MOVE: {
                    float dx = e.getRawX() - downX;
                    float dy = e.getRawY() - downY;
                    if (Math.abs(dx) > slop * 1.5f && Math.abs(dx) > Math.abs(dy) * 1.4f) {
                        dragging = true;
                        if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(true);
                        return true;
                    }
                    return false;
                }
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    if (vt != null) { vt.recycle(); vt = null; }
                    return false;
            }
            return false;
        }

        @Override
        public boolean onTouchEvent(MotionEvent e) {
            track(e);
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    downX = e.getRawX();
                    downY = e.getRawY();
                    return true;
                case MotionEvent.ACTION_MOVE: {
                    float dx = e.getRawX() - downX;
                    float dy = e.getRawY() - downY;
                    if (!dragging && Math.abs(dx) > slop * 1.5f && Math.abs(dx) > Math.abs(dy) * 1.4f) {
                        dragging = true;
                        if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(true);
                    }
                    if (dragging) {
                        setTranslationX(dx);
                        setAlpha(1f - Math.min(0.8f, Math.abs(dx) / Math.max(1, getWidth())));
                    }
                    return true;
                }
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL: {
                    float dx = e.getRawX() - downX;
                    float vx = 0;
                    if (vt != null) {
                        vt.computeCurrentVelocity(1000);
                        vx = vt.getXVelocity();
                        vt.recycle();
                        vt = null;
                    }
                    if (dragging) {
                        dragging = false;
                        if (Math.abs(dx) > getWidth() * 0.35f || Math.abs(vx) > 1500) {
                            float dir = Math.signum(dx == 0 ? vx : dx);
                            animate().translationX(dir * getWidth()).alpha(0f).setDuration(180)
                                    .withEndAction(() -> dismiss(this, sbn, cb, false)).start();
                        } else {
                            animate().translationX(0).alpha(1f).setDuration(220).start();
                        }
                    }
                    return true;
                }
            }
            return true;
        }
    }

    private static void dismiss(View card, StatusBarNotification sbn, Callbacks cb, boolean animate) {
        if (sbn.isClearable()) MediaListener.dismiss(sbn.getKey());
        if (animate) {
            card.animate().alpha(0f).scaleX(0.95f).scaleY(0.95f).setDuration(160)
                    .withEndAction(cb::onDone).start();
        } else {
            cb.onDone();
        }
    }

    // ---------- pomocnicze ----------

    /** Tekst: wiadomości z czatu (nadawca: treść), długi tekst lub zwykły. */
    private static CharSequence bodyText(Bundle ex) {
        Parcelable[] msgs = ex.getParcelableArray(Notification.EXTRA_MESSAGES);
        if (msgs != null && msgs.length > 0) {
            SpannableStringBuilder sb = new SpannableStringBuilder();
            int from = Math.max(0, msgs.length - 4);
            for (int i = from; i < msgs.length; i++) {
                if (!(msgs[i] instanceof Bundle)) continue;
                Bundle m = (Bundle) msgs[i];
                CharSequence txt = m.getCharSequence("text");
                if (txt == null) continue;
                CharSequence sender = m.getCharSequence("sender");
                if (sender == null && Build.VERSION.SDK_INT >= 28) {
                    Parcelable p = m.getParcelable("sender_person");
                    if (p instanceof Person) sender = ((Person) p).getName();
                }
                if (sb.length() > 0) sb.append('\n');
                if (sender != null && ex.getBoolean(Notification.EXTRA_IS_GROUP_CONVERSATION, false)) {
                    int st = sb.length();
                    sb.append(sender).append(": ");
                    sb.setSpan(new StyleSpan(Typeface.BOLD), st, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                }
                sb.append(txt);
            }
            if (sb.length() > 0) return sb;
        }
        CharSequence big = ex.getCharSequence(Notification.EXTRA_BIG_TEXT);
        if (!TextUtils.isEmpty(big)) return big;
        CharSequence t = ex.getCharSequence(Notification.EXTRA_TEXT);
        return t == null ? "" : t;
    }

    private static Drawable largeIcon(Context c, Notification n) {
        try {
            Icon i = n.getLargeIcon();
            return i != null ? i.loadDrawable(c) : null;
        } catch (Exception e) {
            return null;
        }
    }

    private static TextView text(Context c, CharSequence s, int sp, int color, boolean medium) {
        TextView t = new TextView(c);
        t.setText(s);
        t.setTextColor(color);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTypeface(Typeface.create(medium ? "sans-serif-medium" : "sans-serif", Typeface.NORMAL));
        return t;
    }

    private static TextView button(Context c, String label, boolean primary) {
        TextView b = new TextView(c);
        b.setText(label);
        b.setGravity(Gravity.CENTER);
        b.setSingleLine(true);
        b.setEllipsize(TextUtils.TruncateAt.END);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        b.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        b.setTextColor(primary ? 0xFF111111 : Color.WHITE);
        b.setMinHeight(dp(c, 46));
        b.setPadding(dp(c, 12), dp(c, 12), dp(c, 12), dp(c, 12));
        GradientDrawable g = new GradientDrawable();
        g.setCornerRadius(dp(c, 16));
        g.setColor(primary ? 0xF2FFFFFF : 0x2EFFFFFF);
        b.setBackground(g);
        return b;
    }
}
