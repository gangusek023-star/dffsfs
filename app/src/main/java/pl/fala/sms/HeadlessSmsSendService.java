package pl.fala.sms;

import android.app.Service;
import android.content.Intent;
import android.net.Uri;
import android.os.IBinder;
import android.telephony.TelephonyManager;

/** „Odpowiedz SMS-em” przy odrzucaniu połączenia. */
public class HeadlessSmsSendService extends Service {
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && TelephonyManager.ACTION_RESPOND_VIA_MESSAGE.equals(intent.getAction())) {
            Uri u = intent.getData();
            String text = intent.getStringExtra(Intent.EXTRA_TEXT);
            if (u != null && text != null && !text.isEmpty()) {
                String addr = u.getSchemeSpecificPart();
                if (addr != null) {
                    int q = addr.indexOf('?');
                    if (q >= 0) addr = addr.substring(0, q);
                    final String a = addr.replace("//", "");
                    new Thread(() -> {
                        Sms.send(this, a, text, Sms.defaultSub());
                        stopSelf(startId);
                    }).start();
                    return START_NOT_STICKY;
                }
            }
        }
        stopSelf(startId);
        return START_NOT_STICKY;
    }
}
