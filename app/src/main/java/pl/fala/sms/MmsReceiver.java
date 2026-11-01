package pl.fala.sms;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Powiadomienie o MMS (WAP Push) – zlecamy pobranie wiadomości. */
public class MmsReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context c, Intent intent) {
        byte[] data = intent.getByteArrayExtra("data");
        if (data == null) return;
        int sub = intent.getIntExtra("android.telephony.extra.SUBSCRIPTION_INDEX", intent.getIntExtra("subscription", -1));
        final PendingResult pr = goAsync();
        new Thread(() -> {
            try {
                Mms.onNotification(c, data, sub);
            } finally {
                pr.finish();
            }
        }).start();
    }
}
