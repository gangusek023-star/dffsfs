package pl.fala.sms;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Person;
import android.app.RemoteInput;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.graphics.drawable.Icon;
import android.net.Uri;
import android.os.Build;
import android.provider.ContactsContract;
import android.provider.Telephony;
import android.telephony.SmsManager;
import android.telephony.SubscriptionManager;
import android.text.TextUtils;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Logika SMS: wysyłanie, zapis do skrzynek, kontakty, powiadomienia. */
public final class Sms {

    static final String CHANNEL = "messages";
    static final String ACTION_SENT = "pl.fala.sms.SENT";
    static final String ACTION_DELIVERED = "pl.fala.sms.DELIVERED";
    static final String ACTION_REPLY = "pl.fala.sms.REPLY";
    static final String ACTION_READ = "pl.fala.sms.READ";
    static final String KEY_REPLY = "reply";

    private static final Map<String, Contact> contactCache = new HashMap<>();

    private Sms() {
    }

    static boolean isDefault(Context c) {
        String d = Telephony.Sms.getDefaultSmsPackage(c);
        return c.getPackageName().equals(d);
    }

    // ---------- kontakty ----------

    static class Contact {
        String name;
        String number;
        Uri photo;
    }

    static Contact contact(Context c, String number) {
        if (number == null) number = "";
        synchronized (contactCache) {
            Contact k = contactCache.get(number);
            if (k != null) return k;
        }
        Contact k = new Contact();
        k.number = number;
        k.name = number;
        if (!number.isEmpty()) {
            try (Cursor cur = c.getContentResolver().query(
                    Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(number)),
                    new String[]{ContactsContract.PhoneLookup.DISPLAY_NAME, ContactsContract.PhoneLookup.PHOTO_THUMBNAIL_URI},
                    null, null, null)) {
                if (cur != null && cur.moveToFirst()) {
                    k.name = cur.getString(0);
                    String p = cur.getString(1);
                    if (p != null) k.photo = Uri.parse(p);
                }
            } catch (Exception ignored) {
            }
        }
        synchronized (contactCache) {
            contactCache.put(number, k);
        }
        return k;
    }

    static void clearContacts() {
        synchronized (contactCache) {
            contactCache.clear();
        }
    }

    static int colorFor(String s) {
        int[] cols = {0xFF5B8CFF, 0xFFB46BFF, 0xFFFF6B9A, 0xFFFF9F45, 0xFF2EC4A6, 0xFF4CB3FF, 0xFFE0629B, 0xFF7DCB4F};
        return cols[Math.abs((s == null ? "" : s).hashCode()) % cols.length];
    }

    static String initials(String name) {
        if (name == null || name.isEmpty()) return "?";
        if (name.startsWith("+") || Character.isDigit(name.charAt(0))) return "#";
        String[] p = name.trim().split("\\s+");
        String s = p[0].substring(0, 1);
        if (p.length > 1 && !p[1].isEmpty()) s += p[1].substring(0, 1);
        return s.toUpperCase(Locale.ROOT);
    }

    /** Okrągły awatar: zdjęcie kontaktu albo inicjały na kolorowym tle. */
    static Bitmap avatar(Context c, Contact k, int px) {
        Bitmap out = Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888);
        Canvas cv = new Canvas(out);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        if (k.photo != null) {
            try (InputStream is = c.getContentResolver().openInputStream(k.photo)) {
                Bitmap ph = BitmapFactory.decodeStream(is);
                if (ph != null) {
                    android.graphics.BitmapShader sh = new android.graphics.BitmapShader(
                            Bitmap.createScaledBitmap(ph, px, px, true),
                            android.graphics.Shader.TileMode.CLAMP, android.graphics.Shader.TileMode.CLAMP);
                    p.setShader(sh);
                    cv.drawCircle(px / 2f, px / 2f, px / 2f, p);
                    return out;
                }
            } catch (Exception ignored) {
            }
        }
        int col = colorFor(k.name);
        p.setShader(new android.graphics.LinearGradient(0, 0, 0, px, lighten(col), col, android.graphics.Shader.TileMode.CLAMP));
        cv.drawCircle(px / 2f, px / 2f, px / 2f, p);
        p.setShader(null);
        p.setColor(Color.WHITE);
        p.setTextAlign(Paint.Align.CENTER);
        p.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        String in = initials(k.name);
        p.setTextSize(px * (in.length() > 1 ? 0.36f : 0.42f));
        cv.drawText(in, px / 2f, px / 2f - (p.descent() + p.ascent()) / 2f, p);
        return out;
    }

    static int lighten(int c) {
        float[] hsv = new float[3];
        Color.colorToHSV(c, hsv);
        hsv[2] = Math.min(1f, hsv[2] * 1.25f);
        hsv[1] *= 0.8f;
        return Color.HSVToColor(hsv);
    }

    // ---------- wysyłanie ----------

    static SmsManager manager(Context c, int subId) {
        if (subId >= 0) {
            if (Build.VERSION.SDK_INT >= 31) {
                SmsManager m = c.getSystemService(SmsManager.class);
                if (m != null) return m.createForSubscriptionId(subId);
            }
            //noinspection deprecation
            return SmsManager.getSmsManagerForSubscriptionId(subId);
        }
        if (Build.VERSION.SDK_INT >= 31) {
            SmsManager m = c.getSystemService(SmsManager.class);
            if (m != null) return m;
        }
        //noinspection deprecation
        return SmsManager.getDefault();
    }

    static int pendingFlags(boolean mutable) {
        int f = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= 31) f |= mutable ? PendingIntent.FLAG_MUTABLE : PendingIntent.FLAG_IMMUTABLE;
        else if (!mutable) f |= PendingIntent.FLAG_IMMUTABLE;
        return f;
    }

    /** Zapisuje wiadomość w „Wysyłanych” i wysyła ją. Zwraca uri wiadomości. */
    static Uri send(Context c, String address, String body, int subId) {
        ContentResolver cr = c.getContentResolver();
        ContentValues v = new ContentValues();
        v.put(Telephony.Sms.ADDRESS, address);
        v.put(Telephony.Sms.BODY, body);
        v.put(Telephony.Sms.DATE, System.currentTimeMillis());
        v.put(Telephony.Sms.READ, 1);
        v.put(Telephony.Sms.SEEN, 1);
        v.put(Telephony.Sms.TYPE, Telephony.Sms.MESSAGE_TYPE_OUTBOX);
        v.put(Telephony.Sms.STATUS, Telephony.Sms.STATUS_PENDING);
        if (subId >= 0) v.put(Telephony.Sms.SUBSCRIPTION_ID, subId);
        Uri uri = null;
        try {
            uri = cr.insert(Telephony.Sms.Outbox.CONTENT_URI, v);
        } catch (Exception ignored) {
        }
        try {
            SmsManager sm = manager(c, subId);
            ArrayList<String> parts = sm.divideMessage(body);
            ArrayList<PendingIntent> sent = new ArrayList<>(), deliv = new ArrayList<>();
            int req = (int) (System.currentTimeMillis() & 0x7FFFFFF);
            for (int i = 0; i < parts.size(); i++) {
                Intent si = new Intent(c, StatusReceiver.class).setAction(ACTION_SENT);
                if (uri != null) si.setData(uri);
                si.putExtra("last", i == parts.size() - 1);
                sent.add(PendingIntent.getBroadcast(c, req + i, si, pendingFlags(false)));
                Intent di = new Intent(c, StatusReceiver.class).setAction(ACTION_DELIVERED);
                if (uri != null) di.setData(uri);
                deliv.add(PendingIntent.getBroadcast(c, req + 1000 + i, di, pendingFlags(false)));
            }
            if (parts.size() > 1) sm.sendMultipartTextMessage(address, null, parts, sent, deliv);
            else sm.sendTextMessage(address, null, body, sent.get(0), deliv.get(0));
        } catch (Exception e) {
            if (uri != null) {
                ContentValues f = new ContentValues();
                f.put(Telephony.Sms.TYPE, Telephony.Sms.MESSAGE_TYPE_FAILED);
                try {
                    cr.update(uri, f, null, null);
                } catch (Exception ignored) {
                }
            }
        }
        return uri;
    }

    // ---------- odbiór ----------

    static Uri saveIncoming(Context c, String address, String body, long sentAt, int subId) {
        ContentValues v = new ContentValues();
        v.put(Telephony.Sms.ADDRESS, address);
        v.put(Telephony.Sms.BODY, body);
        v.put(Telephony.Sms.DATE, System.currentTimeMillis());
        v.put(Telephony.Sms.DATE_SENT, sentAt);
        v.put(Telephony.Sms.READ, 0);
        v.put(Telephony.Sms.SEEN, 0);
        v.put(Telephony.Sms.TYPE, Telephony.Sms.MESSAGE_TYPE_INBOX);
        if (subId >= 0) v.put(Telephony.Sms.SUBSCRIPTION_ID, subId);
        try {
            return c.getContentResolver().insert(Telephony.Sms.Inbox.CONTENT_URI, v);
        } catch (Exception e) {
            return null;
        }
    }

    static long threadId(Context c, String address) {
        try {
            return Telephony.Threads.getOrCreateThreadId(c, address);
        } catch (Exception e) {
            return -1;
        }
    }

    static void markRead(Context c, long threadId) {
        if (threadId < 0) return;
        ContentValues v = new ContentValues();
        v.put(Telephony.Sms.READ, 1);
        v.put(Telephony.Sms.SEEN, 1);
        try {
            c.getContentResolver().update(Telephony.Sms.CONTENT_URI, v,
                    Telephony.Sms.THREAD_ID + "=? AND " + Telephony.Sms.READ + "=0", new String[]{String.valueOf(threadId)});
        } catch (Exception ignored) {
        }
        NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
        nm.cancel((int) threadId);
    }

    // ---------- powiadomienia ----------

    static void ensureChannel(Context c) {
        if (Build.VERSION.SDK_INT < 26) return;
        NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
        NotificationChannel ch = new NotificationChannel(CHANNEL, "Wiadomości", NotificationManager.IMPORTANCE_HIGH);
        ch.enableVibration(true);
        ch.setShowBadge(true);
        nm.createNotificationChannel(ch);
    }

    /** Powiadomienie z ostatnimi nieprzeczytanymi wiadomościami rozmowy i szybką odpowiedzią. */
    static void notifyThread(Context c, long threadId, String address) {
        ensureChannel(c);
        Contact k = contact(c, address);
        List<String[]> unread = new ArrayList<>();
        try (Cursor cur = c.getContentResolver().query(Telephony.Sms.CONTENT_URI,
                new String[]{Telephony.Sms.BODY, Telephony.Sms.DATE},
                Telephony.Sms.THREAD_ID + "=? AND " + Telephony.Sms.READ + "=0 AND " + Telephony.Sms.TYPE + "=1",
                new String[]{String.valueOf(threadId)}, Telephony.Sms.DATE + " ASC")) {
            while (cur != null && cur.moveToNext()) unread.add(new String[]{cur.getString(0), String.valueOf(cur.getLong(1))});
        } catch (Exception ignored) {
        }
        if (unread.isEmpty()) return;
        int from = Math.max(0, unread.size() - 6);

        Intent open = new Intent(c, ConversationActivity.class)
                .putExtra("thread", threadId).putExtra("address", address)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent pOpen = PendingIntent.getActivity(c, (int) threadId, open, pendingFlags(false));

        Intent reply = new Intent(c, ActionReceiver.class).setAction(ACTION_REPLY)
                .putExtra("thread", threadId).putExtra("address", address);
        PendingIntent pReply = PendingIntent.getBroadcast(c, (int) threadId, reply, pendingFlags(true));
        RemoteInput ri = new RemoteInput.Builder(KEY_REPLY).setLabel("Odpowiedz…").build();
        Notification.Action aReply = new Notification.Action.Builder(
                Icon.createWithResource(c, R.drawable.ic_send), "Odpowiedz", pReply).addRemoteInput(ri).build();

        Intent read = new Intent(c, ActionReceiver.class).setAction(ACTION_READ)
                .putExtra("thread", threadId).putExtra("address", address);
        PendingIntent pRead = PendingIntent.getBroadcast(c, (int) threadId + 7, read, pendingFlags(false));
        Notification.Action aRead = new Notification.Action.Builder(
                Icon.createWithResource(c, R.drawable.ic_check), "Przeczytane", pRead).build();

        Bitmap av = avatar(c, k, 128);
        Notification.Builder b = Build.VERSION.SDK_INT >= 26 ? new Notification.Builder(c, CHANNEL) : new Notification.Builder(c);
        b.setSmallIcon(R.drawable.ic_bubble)
                .setColor(0xFF4A7DFF)
                .setLargeIcon(av)
                .setContentTitle(k.name)
                .setContentText(unread.get(unread.size() - 1)[0])
                .setContentIntent(pOpen)
                .setAutoCancel(true)
                .setCategory(Notification.CATEGORY_MESSAGE)
                .setWhen(Long.parseLong(unread.get(unread.size() - 1)[1]))
                .setShowWhen(true)
                .addAction(aReply)
                .addAction(aRead);
        if (Build.VERSION.SDK_INT >= 28) {
            Person me = new Person.Builder().setName("Ja").build();
            Person them = new Person.Builder().setName(k.name).setIcon(Icon.createWithBitmap(av)).build();
            Notification.MessagingStyle ms = new Notification.MessagingStyle(me);
            for (int i = from; i < unread.size(); i++) {
                ms.addMessage(unread.get(i)[0], Long.parseLong(unread.get(i)[1]), them);
            }
            b.setStyle(ms);
        } else {
            StringBuilder sb = new StringBuilder();
            for (int i = from; i < unread.size(); i++) {
                if (sb.length() > 0) sb.append('\n');
                sb.append(unread.get(i)[0]);
            }
            b.setStyle(new Notification.BigTextStyle().bigText(sb));
        }
        if (unread.size() > 1) b.setNumber(unread.size());
        NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
        try {
            nm.notify((int) threadId, b.build());
        } catch (Exception ignored) {
        }
    }

    // ---------- SIM ----------

    static class Sim {
        int subId;
        String label;
    }

    static List<Sim> sims(Context c) {
        List<Sim> out = new ArrayList<>();
        try {
            SubscriptionManager sm = c.getSystemService(SubscriptionManager.class);
            List<android.telephony.SubscriptionInfo> list = sm.getActiveSubscriptionInfoList();
            if (list != null) {
                for (android.telephony.SubscriptionInfo i : list) {
                    Sim s = new Sim();
                    s.subId = i.getSubscriptionId();
                    CharSequence n = i.getDisplayName();
                    s.label = "SIM " + (i.getSimSlotIndex() + 1) + (TextUtils.isEmpty(n) ? "" : " · " + n);
                    out.add(s);
                }
            }
        } catch (Exception ignored) {
        }
        return out;
    }

    static int defaultSub() {
        try {
            return SubscriptionManager.getDefaultSmsSubscriptionId();
        } catch (Exception e) {
            return -1;
        }
    }

    static String normalize(String n) {
        if (n == null) return "";
        return n.replaceAll("[^0-9+]", "");
    }
}
