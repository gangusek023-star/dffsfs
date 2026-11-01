package pl.fala.sms;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.provider.Telephony;
import android.telephony.SmsMessage;

import java.util.LinkedHashMap;
import java.util.Map;

/** Odbiór SMS-ów (aplikacja domyślna sama zapisuje je w skrzynce i pokazuje powiadomienie). */
public class SmsReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context c, Intent intent) {
        if (!Telephony.Sms.Intents.SMS_DELIVER_ACTION.equals(intent.getAction())) return;
        SmsMessage[] msgs = Telephony.Sms.Intents.getMessagesFromIntent(intent);
        if (msgs == null || msgs.length == 0) return;
        int sub = intent.getIntExtra("android.telephony.extra.SUBSCRIPTION_INDEX", intent.getIntExtra("subscription", -1));
        // wiadomości wieloczęściowe łączymy według nadawcy
        Map<String, StringBuilder> bodies = new LinkedHashMap<>();
        Map<String, Long> times = new LinkedHashMap<>();
        for (SmsMessage m : msgs) {
            if (m == null) continue;
            String from = m.getDisplayOriginatingAddress();
            if (from == null) from = "";
            StringBuilder sb = bodies.get(from);
            if (sb == null) {
                sb = new StringBuilder();
                bodies.put(from, sb);
                times.put(from, m.getTimestampMillis());
            }
            String b = m.getDisplayMessageBody();
            if (b != null) sb.append(b);
        }
        final PendingResult pr = goAsync();
        new Thread(() -> {
            try {
                for (Map.Entry<String, StringBuilder> e : bodies.entrySet()) {
                    Sms.saveIncoming(c, e.getKey(), e.getValue().toString(), times.get(e.getKey()), sub);
                    long tid = Sms.threadId(c, e.getKey());
                    Sms.notifyThread(c, tid, e.getKey());
                }
            } finally {
                pr.finish();
            }
        }).start();
    }
}
