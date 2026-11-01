package pl.fala.sms;

import android.app.Activity;
import android.app.PendingIntent;
import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;
import android.net.Uri;
import android.telephony.SmsManager;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;

/** Wysyłanie i odbieranie MMS (zdjęcia) przez systemową usługę MMS. */
public final class Mms {

    static final String ACTION_MMS_SENT = "pl.fala.sms.MMS_SENT";
    static final String ACTION_MMS_DOWNLOADED = "pl.fala.sms.MMS_DOWNLOADED";
    static final String AUTH = "pl.fala.sms.files";
    static final int MAX_BYTES = 280 * 1024;

    private Mms() {
    }

    static Uri fileUri(String name) {
        return Uri.parse("content://" + AUTH + "/" + name);
    }

    static File file(Context c, String name) {
        File d = new File(c.getCacheDir(), "mms");
        if (!d.exists()) d.mkdirs();
        return new File(d, name);
    }

    /** Zdjęcie zmniejszone i skompresowane do limitu operatora. */
    static byte[] prepareImage(Context c, Uri src) throws Exception {
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        try (InputStream is = c.getContentResolver().openInputStream(src)) {
            BitmapFactory.decodeStream(is, null, o);
        }
        int sample = 1;
        while (Math.max(o.outWidth, o.outHeight) / (sample * 2) >= 1280) sample *= 2;
        BitmapFactory.Options o2 = new BitmapFactory.Options();
        o2.inSampleSize = sample;
        Bitmap bm;
        try (InputStream is = c.getContentResolver().openInputStream(src)) {
            bm = BitmapFactory.decodeStream(is, null, o2);
        }
        if (bm == null) throw new Exception("decode");
        bm = rotate(c, src, bm);
        int maxSide = 1280;
        byte[] out = null;
        for (int attempt = 0; attempt < 8; attempt++) {
            float s = Math.min(1f, maxSide / (float) Math.max(bm.getWidth(), bm.getHeight()));
            Bitmap scaled = s < 1f ? Bitmap.createScaledBitmap(bm, Math.round(bm.getWidth() * s), Math.round(bm.getHeight() * s), true) : bm;
            for (int q = 85; q >= 45; q -= 10) {
                ByteArrayOutputStream bo = new ByteArrayOutputStream();
                scaled.compress(Bitmap.CompressFormat.JPEG, q, bo);
                out = bo.toByteArray();
                if (out.length <= MAX_BYTES) return out;
            }
            maxSide = Math.round(maxSide * 0.75f);
        }
        return out;
    }

    private static Bitmap rotate(Context c, Uri src, Bitmap bm) {
        try (InputStream is = c.getContentResolver().openInputStream(src)) {
            android.media.ExifInterface ex = new android.media.ExifInterface(is);
            int or = ex.getAttributeInt(android.media.ExifInterface.TAG_ORIENTATION, 1);
            int deg = or == 6 ? 90 : or == 3 ? 180 : or == 8 ? 270 : 0;
            if (deg == 0) return bm;
            Matrix m = new Matrix();
            m.postRotate(deg);
            return Bitmap.createBitmap(bm, 0, 0, bm.getWidth(), bm.getHeight(), m, true);
        } catch (Exception e) {
            return bm;
        }
    }

    // ---------- zapis w bazie wiadomości telefonu ----------

    /** Zapisuje MMS w systemowej bazie (msg_box: 1 odebrane, 2 wysłane, 4 wysyłane, 5 błąd). */
    static Uri persist(Context c, long threadId, String address, String text, byte[] image, String imageType,
                       int box, int subId, String trId, long dateSec) {
        ContentResolver cr = c.getContentResolver();
        ContentValues v = new ContentValues();
        v.put("thread_id", threadId);
        v.put("date", dateSec);
        v.put("date_sent", box == 1 ? dateSec : 0);
        v.put("msg_box", box);
        v.put("read", box == 1 ? 0 : 1);
        v.put("seen", box == 1 ? 0 : 1);
        v.put("m_type", box == 1 ? 132 : 128);
        v.put("v", 18);
        v.put("ct_t", "application/vnd.wap.multipart.mixed");
        v.put("m_cls", "personal");
        v.put("pri", 129);
        v.put("tr_id", trId);
        v.put("text_only", image == null ? 1 : 0);
        if (subId >= 0) v.put("sub_id", subId);
        Uri msg;
        try {
            msg = cr.insert(Uri.parse("content://mms"), v);
        } catch (Exception e) {
            return null;
        }
        if (msg == null) return null;
        long id = ContentUris.parseId(msg);
        try {
            ContentValues a = new ContentValues();
            a.put("address", address);
            a.put("type", box == 1 ? 137 : 151); // 137 = FROM, 151 = TO
            a.put("charset", 106);
            cr.insert(Uri.parse("content://mms/" + id + "/addr"), a);
            if (box != 1) {
                ContentValues me = new ContentValues();
                me.put("address", "insert-address-token");
                me.put("type", 137);
                me.put("charset", 106);
                cr.insert(Uri.parse("content://mms/" + id + "/addr"), me);
            }
            Uri partUri = Uri.parse("content://mms/" + id + "/part");
            if (text != null && !text.isEmpty()) {
                ContentValues t = new ContentValues();
                t.put("mid", id);
                t.put("ct", "text/plain");
                t.put("chset", 106);
                t.put("cid", "<text>");
                t.put("cl", "text.txt");
                t.put("text", text);
                cr.insert(partUri, t);
            }
            if (image != null) {
                ContentValues p = new ContentValues();
                p.put("mid", id);
                p.put("ct", imageType);
                p.put("cid", "<image>");
                p.put("cl", "image.jpg");
                p.put("name", "image.jpg");
                Uri pu = cr.insert(partUri, p);
                if (pu != null) {
                    try (OutputStream os = cr.openOutputStream(pu)) {
                        if (os != null) os.write(image);
                    }
                }
            }
        } catch (Exception ignored) {
        }
        return msg;
    }

    static void setBox(Context c, Uri msg, int box) {
        if (msg == null) return;
        ContentValues v = new ContentValues();
        v.put("msg_box", box);
        try {
            c.getContentResolver().update(msg, v, null, null);
        } catch (Exception ignored) {
        }
    }

    // ---------- wysyłanie ----------

    static void send(Context c, String address, String text, Uri image, int subId) throws Exception {
        byte[] jpg = image != null ? prepareImage(c, image) : null;
        String trId = "T" + Long.toHexString(System.currentTimeMillis());
        List<MmsPdu.Part> parts = new ArrayList<>();
        if (text != null && !text.isEmpty()) parts.add(new MmsPdu.Part("text/plain", "text.txt", text.getBytes("UTF-8")));
        if (jpg != null) parts.add(new MmsPdu.Part("image/jpeg", "image.jpg", jpg));
        byte[] pdu = MmsPdu.sendReq(address, trId, parts);

        long tid = Sms.threadId(c, address);
        Uri msg = persist(c, tid, address, text, jpg, "image/jpeg", 4, subId, trId, System.currentTimeMillis() / 1000);

        String name = "send_" + System.currentTimeMillis() + ".pdu";
        try (FileOutputStream fo = new FileOutputStream(file(c, name))) {
            fo.write(pdu);
        }
        Intent si = new Intent(c, StatusReceiver.class).setAction(ACTION_MMS_SENT);
        if (msg != null) si.setData(msg);
        si.putExtra("file", name);
        PendingIntent pi = PendingIntent.getBroadcast(c, (int) (System.currentTimeMillis() & 0x7FFFFFF), si, Sms.pendingFlags(true));
        SmsManager sm = Sms.manager(c, subId);
        sm.sendMultimediaMessage(c, fileUri(name), null, null, pi);
    }

    // ---------- odbiór ----------

    /** Z powiadomienia WAP Push pobiera właściwą wiadomość MMS. */
    static void onNotification(Context c, byte[] pushData, int subId) {
        MmsPdu.Parsed n = MmsPdu.parse(pushData);
        if (n.messageType != 0x82 || n.contentLocation == null) return;
        String name = "dl_" + System.currentTimeMillis() + ".pdu";
        try {
            //noinspection ResultOfMethodCallIgnored
            file(c, name).createNewFile();
        } catch (Exception ignored) {
        }
        Intent di = new Intent(c, StatusReceiver.class).setAction(ACTION_MMS_DOWNLOADED);
        di.putExtra("file", name);
        di.putExtra("sub", subId);
        di.putExtra("from", n.from);
        di.putExtra("tr", n.transactionId);
        PendingIntent pi = PendingIntent.getBroadcast(c, (int) (System.currentTimeMillis() & 0x7FFFFFF), di, Sms.pendingFlags(true));
        try {
            Sms.manager(c, subId).downloadMultimediaMessage(c, n.contentLocation, fileUri(name), null, pi);
        } catch (Exception ignored) {
        }
    }

    /** Po pobraniu: zapis w skrzynce, potwierdzenie dla operatora, powiadomienie. */
    static void onDownloaded(Context c, Intent intent, int resultCode) {
        String name = intent.getStringExtra("file");
        int subId = intent.getIntExtra("sub", -1);
        File f = name != null ? file(c, name) : null;
        try {
            if (resultCode != Activity.RESULT_OK || f == null || !f.exists() || f.length() == 0) return;
            byte[] data = new byte[(int) f.length()];
            try (FileInputStream fi = new FileInputStream(f)) {
                int off = 0, k;
                while (off < data.length && (k = fi.read(data, off, data.length - off)) > 0) off += k;
            }
            MmsPdu.Parsed p = MmsPdu.parse(data);
            String from = p.from != null ? p.from : intent.getStringExtra("from");
            if (from == null) from = "";
            StringBuilder text = new StringBuilder();
            byte[] img = null;
            String imgType = "image/jpeg";
            for (MmsPdu.Part part : p.parts) {
                if (part.contentType.startsWith("text/plain")) {
                    if (text.length() > 0) text.append('\n');
                    text.append(new String(part.data, "UTF-8"));
                } else if (part.contentType.startsWith("image/") && img == null) {
                    img = part.data;
                    imgType = part.contentType.equals("image/*") ? "image/jpeg" : part.contentType;
                }
            }
            if (p.subject != null && text.length() == 0 && img == null) text.append(p.subject);
            long tid = Sms.threadId(c, from);
            persist(c, tid, from, text.toString(), img, imgType, 1, subId, p.transactionId, System.currentTimeMillis() / 1000);
            String tr = p.transactionId != null ? p.transactionId : intent.getStringExtra("tr");
            if (tr != null) ack(c, tr, subId);
            Sms.notifyThread(c, tid, from, img != null ? (text.length() > 0 ? "📷 " + text : "📷 Zdjęcie") : text.toString());
        } catch (Exception ignored) {
        } finally {
            if (f != null) //noinspection ResultOfMethodCallIgnored
                f.delete();
        }
    }

    private static void ack(Context c, String trId, int subId) {
        try {
            String name = "ack_" + System.currentTimeMillis() + ".pdu";
            try (FileOutputStream fo = new FileOutputStream(file(c, name))) {
                fo.write(MmsPdu.notifyRespInd(trId));
            }
            Sms.manager(c, subId).sendMultimediaMessage(c, fileUri(name), null, null, null);
        } catch (Exception ignored) {
        }
    }
}
