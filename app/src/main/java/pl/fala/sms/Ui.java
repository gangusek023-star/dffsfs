package pl.fala.sms;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.content.res.ColorStateList;
import android.util.TypedValue;
import android.view.Gravity;
import android.widget.TextView;

import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.Locale;

/** Wspólne kolory, czcionki i elementy interfejsu. */
final class Ui {
    static final Locale PL = new Locale("pl", "PL");
    static final int BG = 0xFF0B0B10;
    static final int CARD = 0xFF1C1C22;
    static final int TEXT2 = 0x99FFFFFF;
    static final int ACCENT = 0xFF4A7DFF;
    static final int ACCENT2 = 0xFF7A5CFF;

    private Ui() {
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

    static GradientDrawable round(int color, float radiusPx) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(radiusPx);
        return g;
    }

    static GradientDrawable accent(float radiusPx) {
        GradientDrawable g = new GradientDrawable(GradientDrawable.Orientation.TL_BR, new int[]{0xFF5A8DFF, ACCENT2});
        g.setCornerRadius(radiusPx);
        return g;
    }

    static RippleDrawable ripple(Context c) {
        GradientDrawable mask = round(Color.WHITE, dp(c, 14));
        return new RippleDrawable(ColorStateList.valueOf(0x22FFFFFF), null, mask);
    }

    static TextView button(Context c, String s, boolean primary) {
        TextView b = tv(c, s, 16, primary ? Color.WHITE : 0xFFE6E6F0, true);
        b.setGravity(Gravity.CENTER);
        b.setPadding(dp(c, 18), dp(c, 14), dp(c, 18), dp(c, 14));
        b.setBackground(primary ? accent(dp(c, 22)) : round(0x1FFFFFFF, dp(c, 22)));
        return b;
    }

    /** Krótki czas na liście rozmów. */
    static String shortTime(long ms) {
        Calendar now = Calendar.getInstance(), t = Calendar.getInstance();
        t.setTimeInMillis(ms);
        if (now.get(Calendar.YEAR) == t.get(Calendar.YEAR) && now.get(Calendar.DAY_OF_YEAR) == t.get(Calendar.DAY_OF_YEAR)) {
            return new SimpleDateFormat("HH:mm", PL).format(new Date(ms));
        }
        now.add(Calendar.DAY_OF_YEAR, -1);
        if (now.get(Calendar.YEAR) == t.get(Calendar.YEAR) && now.get(Calendar.DAY_OF_YEAR) == t.get(Calendar.DAY_OF_YEAR)) {
            return "wczoraj";
        }
        if (System.currentTimeMillis() - ms < 6L * 86400000L) {
            return new SimpleDateFormat("EEE", PL).format(new Date(ms));
        }
        return new SimpleDateFormat("d.MM.yy", PL).format(new Date(ms));
    }

    /** Nagłówek z datą w rozmowie. */
    static String dayHeader(long ms) {
        Calendar now = Calendar.getInstance(), t = Calendar.getInstance();
        t.setTimeInMillis(ms);
        String time = new SimpleDateFormat("HH:mm", PL).format(new Date(ms));
        if (now.get(Calendar.YEAR) == t.get(Calendar.YEAR) && now.get(Calendar.DAY_OF_YEAR) == t.get(Calendar.DAY_OF_YEAR)) {
            return "Dziś " + time;
        }
        now.add(Calendar.DAY_OF_YEAR, -1);
        if (now.get(Calendar.YEAR) == t.get(Calendar.YEAR) && now.get(Calendar.DAY_OF_YEAR) == t.get(Calendar.DAY_OF_YEAR)) {
            return "Wczoraj " + time;
        }
        return new SimpleDateFormat("EEEE, d MMMM, HH:mm", PL).format(new Date(ms));
    }
}
