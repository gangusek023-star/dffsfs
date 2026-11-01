package pl.fala.phone;

import android.Manifest;
import android.app.role.RoleManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.ContactsContract;
import android.telecom.TelecomManager;
import android.util.TypedValue;
import android.view.Gravity;
import android.widget.TextView;
import android.widget.Toast;

import java.io.InputStream;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/** Wspólne: kontakty, awatary, dzwonienie, kolory. */
final class Ph {
    static final Locale PL = new Locale("pl", "PL");
    static final int BG = 0xFF0B0B10;
    static final int TEXT2 = 0x99FFFFFF;
    static final int GREEN = 0xFF34C759;
    static final int RED = 0xFFFF3B30;
    static final int ACCENT = 0xFF4A9DFF;

    private Ph() {
    }

    static int dp(Context c, float v) {
        return Math.round(v * c.getResources().getDisplayMetrics().density);
    }

    static TextView tv(Context c, CharSequence s, float sp, int color, boolean bold) {
        TextView t = new TextView(c);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        t.setTypeface(Typeface.create(bold ? "sans-serif-medium" : "sans-serif", Typeface.NORMAL));
        return t;
    }

    static GradientDrawable round(int color, float r) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(r);
        return g;
    }

    static GradientDrawable circle(int color) {
        GradientDrawable g = new GradientDrawable();
        g.setShape(GradientDrawable.OVAL);
        g.setColor(color);
        return g;
    }

    static RippleDrawable ripple(Context c) {
        return new RippleDrawable(ColorStateList.valueOf(0x22FFFFFF), null, round(Color.WHITE, dp(c, 14)));
    }

    static TextView button(Context c, String s, int bg, int fg) {
        TextView b = tv(c, s, 16, fg, true);
        b.setGravity(Gravity.CENTER);
        b.setPadding(dp(c, 18), dp(c, 14), dp(c, 18), dp(c, 14));
        b.setBackground(round(bg, dp(c, 22)));
        return b;
    }

    // ---------- kontakty ----------

    static class Contact {
        String name;
        String number;
        Uri photo;
        long id = -1;
    }

    private static final Map<String, Contact> cache = new HashMap<>();

    static Contact lookup(Context c, String number) {
        if (number == null) number = "";
        synchronized (cache) {
            Contact k = cache.get(number);
            if (k != null) return k;
        }
        Contact k = new Contact();
        k.number = number;
        k.name = number.isEmpty() ? "Numer prywatny" : number;
        if (!number.isEmpty() && c.checkSelfPermission(Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED) {
            try (Cursor cur = c.getContentResolver().query(
                    Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(number)),
                    new String[]{ContactsContract.PhoneLookup.DISPLAY_NAME, ContactsContract.PhoneLookup.PHOTO_URI,
                            ContactsContract.PhoneLookup._ID}, null, null, null)) {
                if (cur != null && cur.moveToFirst()) {
                    k.name = cur.getString(0);
                    String p = cur.getString(1);
                    if (p != null) k.photo = Uri.parse(p);
                    k.id = cur.getLong(2);
                }
            } catch (Exception ignored) {
            }
        }
        synchronized (cache) {
            cache.put(number, k);
        }
        return k;
    }

    static int colorFor(String s) {
        int[] cols = {0xFF5B8CFF, 0xFFB46BFF, 0xFFFF6B9A, 0xFFFF9F45, 0xFF2EC4A6, 0xFF4CB3FF, 0xFFE0629B, 0xFF7DCB4F};
        return cols[Math.abs((s == null ? "" : s).hashCode()) % cols.length];
    }

    static String initials(String name) {
        if (name == null || name.isEmpty()) return "?";
        char f = name.charAt(0);
        if (f == '+' || Character.isDigit(f)) return "#";
        String[] p = name.trim().split("\\s+");
        String s = p[0].substring(0, 1);
        if (p.length > 1 && !p[1].isEmpty()) s += p[1].substring(0, 1);
        return s.toUpperCase(Locale.ROOT);
    }

    static Bitmap avatar(Context c, String name, Uri photo, int px) {
        Bitmap out = Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888);
        Canvas cv = new Canvas(out);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        if (photo != null) {
            try (InputStream is = c.getContentResolver().openInputStream(photo)) {
                Bitmap ph = BitmapFactory.decodeStream(is);
                if (ph != null) {
                    int s = Math.min(ph.getWidth(), ph.getHeight());
                    Bitmap sq = Bitmap.createBitmap(ph, (ph.getWidth() - s) / 2, (ph.getHeight() - s) / 2, s, s);
                    p.setShader(new BitmapShader(Bitmap.createScaledBitmap(sq, px, px, true), Shader.TileMode.CLAMP, Shader.TileMode.CLAMP));
                    cv.drawCircle(px / 2f, px / 2f, px / 2f, p);
                    return out;
                }
            } catch (Exception ignored) {
            }
        }
        int col = colorFor(name);
        float[] hsv = new float[3];
        Color.colorToHSV(col, hsv);
        hsv[2] = Math.min(1f, hsv[2] * 1.25f);
        hsv[1] *= 0.8f;
        p.setShader(new LinearGradient(0, 0, 0, px, Color.HSVToColor(hsv), col, Shader.TileMode.CLAMP));
        cv.drawCircle(px / 2f, px / 2f, px / 2f, p);
        p.setShader(null);
        p.setColor(Color.WHITE);
        p.setTextAlign(Paint.Align.CENTER);
        p.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        String in = initials(name);
        p.setTextSize(px * (in.length() > 1 ? 0.36f : 0.42f));
        cv.drawText(in, px / 2f, px / 2f - (p.descent() + p.ascent()) / 2f, p);
        return out;
    }

    // ---------- dzwonienie ----------

    static boolean isDefaultDialer(Context c) {
        try {
            TelecomManager tm = c.getSystemService(TelecomManager.class);
            return c.getPackageName().equals(tm.getDefaultDialerPackage());
        } catch (Exception e) {
            return false;
        }
    }

    static Intent requestDefaultIntent(Context c) {
        if (Build.VERSION.SDK_INT >= 29) {
            RoleManager rm = c.getSystemService(RoleManager.class);
            if (rm != null && rm.isRoleAvailable(RoleManager.ROLE_DIALER)) return rm.createRequestRoleIntent(RoleManager.ROLE_DIALER);
        }
        return new Intent(TelecomManager.ACTION_CHANGE_DEFAULT_DIALER)
                .putExtra(TelecomManager.EXTRA_CHANGE_DEFAULT_DIALER_PACKAGE_NAME, c.getPackageName());
    }

    static void call(Context c, String number) {
        if (number == null || number.trim().isEmpty()) return;
        Uri uri = Uri.fromParts("tel", number.trim(), null);
        if (c.checkSelfPermission(Manifest.permission.CALL_PHONE) != PackageManager.PERMISSION_GRANTED) {
            c.startActivity(new Intent(Intent.ACTION_DIAL, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            return;
        }
        try {
            if (isDefaultDialer(c)) {
                TelecomManager tm = c.getSystemService(TelecomManager.class);
                tm.placeCall(uri, new Bundle());
            } else {
                c.startActivity(new Intent(Intent.ACTION_CALL, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            }
        } catch (Exception e) {
            Toast.makeText(c, "Nie można zadzwonić", Toast.LENGTH_SHORT).show();
        }
    }

    static void sms(Context c, String number) {
        try {
            c.startActivity(new Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:" + Uri.encode(number))).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (Exception ignored) {
        }
    }

    // ---------- czas ----------

    static String when(long ms) {
        Calendar now = Calendar.getInstance(), t = Calendar.getInstance();
        t.setTimeInMillis(ms);
        String time = new SimpleDateFormat("HH:mm", PL).format(new Date(ms));
        if (now.get(Calendar.YEAR) == t.get(Calendar.YEAR) && now.get(Calendar.DAY_OF_YEAR) == t.get(Calendar.DAY_OF_YEAR)) return time;
        now.add(Calendar.DAY_OF_YEAR, -1);
        if (now.get(Calendar.YEAR) == t.get(Calendar.YEAR) && now.get(Calendar.DAY_OF_YEAR) == t.get(Calendar.DAY_OF_YEAR)) return "wczoraj";
        if (System.currentTimeMillis() - ms < 6L * 86400000L) return new SimpleDateFormat("EEEE", PL).format(new Date(ms));
        return new SimpleDateFormat("d.MM.yy", PL).format(new Date(ms));
    }

    static String duration(long sec) {
        if (sec < 3600) return String.format(PL, "%d:%02d", sec / 60, sec % 60);
        return String.format(PL, "%d:%02d:%02d", sec / 3600, (sec / 60) % 60, sec % 60);
    }
}
