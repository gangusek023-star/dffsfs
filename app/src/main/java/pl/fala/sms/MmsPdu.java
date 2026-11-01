package pl.fala.sms;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Minimalne kodowanie i dekodowanie PDU MMS (OMA MMS Encapsulation 1.2 / WSP):
 * m-send-req (wysyłanie), m-notification-ind (powiadomienie), m-retrieve-conf (pobrana wiadomość),
 * m-notifyresp-ind (potwierdzenie pobrania).
 */
public final class MmsPdu {

    private MmsPdu() {
    }

    // ---------- kodowanie ----------

    static class Part {
        String contentType;
        String name;
        byte[] data;

        Part(String ct, String name, byte[] data) {
            this.contentType = ct;
            this.name = name;
            this.data = data;
        }
    }

    private static void uintvar(ByteArrayOutputStream o, long v) {
        byte[] tmp = new byte[5];
        int n = 0;
        do {
            tmp[n++] = (byte) (v & 0x7F);
            v >>>= 7;
        } while (v > 0);
        for (int i = n - 1; i >= 0; i--) o.write(i > 0 ? (tmp[i] | 0x80) : tmp[i]);
    }

    private static void text(ByteArrayOutputStream o, String s) {
        byte[] b = s.getBytes(StandardCharsets.UTF_8);
        if (b.length > 0 && (b[0] & 0xFF) >= 0x80) o.write(0x7F);
        o.write(b, 0, b.length);
        o.write(0);
    }

    private static void valueLength(ByteArrayOutputStream o, int len) {
        if (len < 31) o.write(len);
        else {
            o.write(31);
            uintvar(o, len);
        }
    }

    /** Typ zawartości części: krótka forma dla znanych typów + parametr name. */
    private static byte[] partContentType(Part p) {
        ByteArrayOutputStream inner = new ByteArrayOutputStream();
        int wk = -1;
        switch (p.contentType) {
            case "text/plain": wk = 0x03; break;
            case "image/gif": wk = 0x1D; break;
            case "image/jpeg": wk = 0x1E; break;
            case "image/png": wk = 0x20; break;
        }
        if (wk >= 0) inner.write(0x80 | wk);
        else text(inner, p.contentType);
        if ("text/plain".equals(p.contentType)) {
            inner.write(0x81); // Charset
            inner.write(0xEA); // utf-8 (106)
        }
        if (p.name != null) {
            inner.write(0x85); // Name
            text(inner, p.name);
        }
        byte[] in = inner.toByteArray();
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        valueLength(o, in.length);
        o.write(in, 0, in.length);
        return o.toByteArray();
    }

    static byte[] sendReq(String to, String transactionId, List<Part> parts) {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        o.write(0x8C); o.write(0x80);                 // X-Mms-Message-Type: m-send-req
        o.write(0x98); text(o, transactionId);          // X-Mms-Transaction-ID
        o.write(0x8D); o.write(0x92);                 // X-Mms-MMS-Version: 1.2
        o.write(0x85); longInt(o, System.currentTimeMillis() / 1000); // Date
        o.write(0x89); o.write(0x01); o.write(0x81);  // From: insert-address-token
        o.write(0x97); text(o, toAddress(to));          // To
        o.write(0x8A); o.write(0x80);                 // X-Mms-Message-Class: personal
        o.write(0x8F); o.write(0x81);                 // X-Mms-Priority: normal
        o.write(0x86); o.write(0x81);                 // X-Mms-Delivery-Report: no
        o.write(0x90); o.write(0x81);                 // X-Mms-Read-Report: no
        o.write(0x84); o.write(0xA3);                 // Content-Type: application/vnd.wap.multipart.mixed
        uintvar(o, parts.size());
        for (Part p : parts) {
            ByteArrayOutputStream h = new ByteArrayOutputStream();
            byte[] ct = partContentType(p);
            h.write(ct, 0, ct.length);
            if (p.name != null) {
                h.write(0x8E); // Content-Location
                text(h, p.name);
            }
            byte[] hb = h.toByteArray();
            uintvar(o, hb.length);
            uintvar(o, p.data.length);
            o.write(hb, 0, hb.length);
            o.write(p.data, 0, p.data.length);
        }
        return o.toByteArray();
    }

    static byte[] notifyRespInd(String transactionId) {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        o.write(0x8C); o.write(0x83);         // m-notifyresp-ind
        o.write(0x98); text(o, transactionId);
        o.write(0x8D); o.write(0x92);
        o.write(0x95); o.write(0x81);         // X-Mms-Status: retrieved
        return o.toByteArray();
    }

    private static void longInt(ByteArrayOutputStream o, long v) {
        byte[] b = new byte[8];
        int n = 0;
        while (v > 0 || n == 0) {
            b[n++] = (byte) (v & 0xFF);
            v >>>= 8;
        }
        o.write(n);
        for (int i = n - 1; i >= 0; i--) o.write(b[i]);
    }

    static String toAddress(String number) {
        String n = number.replaceAll("[^0-9+]", "");
        if (n.isEmpty()) n = number;
        return n + "/TYPE=PLMN";
    }

    // ---------- dekodowanie ----------

    static class Parsed {
        int messageType = -1;
        String transactionId;
        String contentLocation;
        String from;
        String subject;
        final List<Part> parts = new ArrayList<>();
    }

    private static final class Reader {
        final byte[] d;
        int i;

        Reader(byte[] d) {
            this.d = d;
        }

        boolean more() {
            return i < d.length;
        }

        int peek() {
            return d[i] & 0xFF;
        }

        int u8() {
            return d[i++] & 0xFF;
        }

        long uintvar() {
            long v = 0;
            for (int k = 0; k < 5 && i < d.length; k++) {
                int b = u8();
                v = (v << 7) | (b & 0x7F);
                if ((b & 0x80) == 0) break;
            }
            return v;
        }

        String text() {
            if (i < d.length && (peek() == 0x7F || peek() == 0x22)) i++;
            int s = i;
            while (i < d.length && d[i] != 0) i++;
            String r = new String(d, s, i - s, StandardCharsets.UTF_8);
            if (i < d.length) i++;
            return r;
        }

        /** Długość wartości w formie value-length. */
        int valueLength() {
            int b = u8();
            if (b < 31) return b;
            return (int) uintvar();
        }

        /** Pomija dowolną wartość nagłówka (reguły WSP). */
        void skipValue() {
            int b = peek();
            if (b >= 0x80) i++;
            else if (b <= 30) i += 1 + b;
            else if (b == 31) {
                i++;
                int len = (int) uintvar();
                i += len;
            } else text();
        }

        /** Encoded-string-value: tekst albo [długość, kodowanie, tekst]. */
        String encodedString() {
            int b = peek();
            if (b <= 31) {
                int len = valueLength();
                int end = i + len;
                if (i < d.length && peek() >= 0x80) i++; // kodowanie (short-integer)
                else if (i < d.length && peek() <= 30) i += 1 + peek(); // long-integer
                String s = text();
                i = end;
                return s;
            }
            return text();
        }
    }

    static Parsed parse(byte[] data) {
        Parsed p = new Parsed();
        Reader r = new Reader(data);
        try {
            while (r.more()) {
                int h = r.u8();
                if ((h & 0x80) == 0) { // nagłówek aplikacji: nazwa tekstowa + wartość tekstowa
                    r.i--;
                    r.text();
                    r.text();
                    continue;
                }
                switch (h) {
                    case 0x8C: p.messageType = r.u8(); break;
                    case 0x98: p.transactionId = r.text(); break;
                    case 0x83: p.contentLocation = r.text(); break;
                    case 0x96: p.subject = r.encodedString(); break;
                    case 0x89: {
                        int len = r.valueLength();
                        int end = r.i + len;
                        int tok = r.u8();
                        if (tok == 0x80) p.from = cleanAddress(r.encodedString());
                        r.i = end;
                        break;
                    }
                    case 0x84: { // Content-Type – ostatni nagłówek, dalej treść
                        r.skipValue();
                        parseBody(r, p);
                        return p;
                    }
                    default:
                        r.skipValue();
                }
            }
        } catch (Exception ignored) {
        }
        return p;
    }

    private static void parseBody(Reader r, Parsed p) {
        long n = r.uintvar();
        for (int k = 0; k < n && r.more(); k++) {
            int hl = (int) r.uintvar();
            int dl = (int) r.uintvar();
            int hs = r.i;
            String ct = contentType(r);
            int ds = hs + hl;
            if (ds + dl > r.d.length) break;
            byte[] data = new byte[dl];
            System.arraycopy(r.d, ds, data, 0, dl);
            p.parts.add(new Part(ct, null, data));
            r.i = ds + dl;
        }
    }

    private static String contentType(Reader r) {
        int b = r.peek();
        if (b >= 0x80) {
            r.i++;
            return wellKnown(b & 0x7F);
        }
        if (b <= 31) {
            int len = r.valueLength();
            int end = r.i + len;
            String ct;
            if (r.peek() >= 0x80) ct = wellKnown(r.u8() & 0x7F);
            else ct = r.text();
            r.i = end;
            return ct;
        }
        return r.text();
    }

    private static String wellKnown(int v) {
        switch (v) {
            case 0x03: return "text/plain";
            case 0x1C: return "image/*";
            case 0x1D: return "image/gif";
            case 0x1E: return "image/jpeg";
            case 0x20: return "image/png";
            case 0x21: return "image/vnd.wap.wbmp";
            default: return "application/octet-stream";
        }
    }

    static String cleanAddress(String a) {
        if (a == null) return null;
        int s = a.indexOf('/');
        return (s > 0 ? a.substring(0, s) : a).trim();
    }
}
