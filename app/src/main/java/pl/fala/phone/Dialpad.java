package pl.fala.phone;

import android.content.Context;
import android.graphics.Color;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.View;
import android.widget.GridLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Klawiatura numeryczna z okrągłymi klawiszami i literami. */
final class Dialpad {
    interface Key {
        void on(char c);
    }

    private static final String[] DIGITS = {"1", "2", "3", "4", "5", "6", "7", "8", "9", "*", "0", "#"};
    private static final String[] LETTERS = {"", "ABC", "DEF", "GHI", "JKL", "MNO", "PQRS", "TUV", "WXYZ", "", "+", ""};

    private Dialpad() {
    }

    static View build(Context c, Key key, Key longZero) {
        GridLayout g = new GridLayout(c);
        g.setColumnCount(3);
        for (int i = 0; i < 12; i++) {
            final char ch = DIGITS[i].charAt(0);
            LinearLayout k = new LinearLayout(c);
            k.setOrientation(LinearLayout.VERTICAL);
            k.setGravity(Gravity.CENTER);
            k.setBackground(Ph.circle(0x24FFFFFF));
            TextView d = Ph.tv(c, DIGITS[i], 32, Color.WHITE, false);
            d.setGravity(Gravity.CENTER);
            d.setIncludeFontPadding(false);
            k.addView(d);
            if (!LETTERS[i].isEmpty()) {
                TextView l = Ph.tv(c, LETTERS[i], 10, 0xB3FFFFFF, true);
                l.setLetterSpacing(0.2f);
                l.setGravity(Gravity.CENTER);
                k.addView(l);
            }
            k.setOnClickListener(v -> {
                v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
                v.animate().scaleX(0.92f).scaleY(0.92f).setDuration(60).withEndAction(() ->
                        v.animate().scaleX(1f).scaleY(1f).setDuration(120).start()).start();
                key.on(ch);
            });
            if (ch == '0') {
                k.setOnLongClickListener(v -> {
                    v.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
                    if (longZero != null) longZero.on('+');
                    else key.on('+');
                    return true;
                });
            }
            int s = Ph.dp(c, 76);
            GridLayout.LayoutParams lp = new GridLayout.LayoutParams(GridLayout.spec(GridLayout.UNDEFINED), GridLayout.spec(GridLayout.UNDEFINED, 1f));
            lp.width = s;
            lp.height = s;
            lp.setMargins(Ph.dp(c, 12), Ph.dp(c, 8), Ph.dp(c, 12), Ph.dp(c, 8));
            lp.setGravity(Gravity.CENTER);
            g.addView(k, lp);
        }
        return g;
    }
}
