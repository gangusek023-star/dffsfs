package pl.fala.sms;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.provider.Telephony;
import android.telephony.SmsMessage;

/** Wynik wysyłki SMS/MMS, raport doręczenia i pobranie MMS. */
public class StatusReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context c, Intent intent) {
        String action = intent.getAction();
        Uri uri = intent.getData();
        int code = getResultCode();

        if (Mms.ACTION_MMS_SENT.equals(action)) {
            Mms.setBox(c, uri, code == Activity.RESULT_OK ? 2 : 5);
            String f = intent.getStringExtra("file");
            if (f != null) //noinspection ResultOfMethodCallIgnored
                Mms.file(c, f).delete();
            return;
        }
        if (Mms.ACTION_MMS_DOWNLOADED.equals(action)) {
            final PendingResult pr = goAsync();
            new Thread(() -> {
                try {
                    Mms.onDownloaded(c, intent, code);
                } finally {
                    pr.finish();
                }
            }).start();
            return;
        }
        if (uri == null) return;
        ContentValues v = new ContentValues();
        if (Sms.ACTION_SENT.equals(action)) {
            if (code != Activity.RESULT_OK) {
                v.put(Telephony.Sms.TYPE, Telephony.Sms.MESSAGE_TYPE_FAILED);
            } else if (intent.getBooleanExtra("last", true)) {
                v.put(Telephony.Sms.TYPE, Telephony.Sms.MESSAGE_TYPE_SENT);
            } else {
                return;
            }
        } else if (Sms.ACTION_DELIVERED.equals(action)) {
            // prawdziwy status z raportu doręczenia od operatora
            int status = Telephony.Sms.STATUS_COMPLETE;
            try {
                byte[] pdu = intent.getByteArrayExtra("pdu");
                String format = intent.getStringExtra("format");
                if (pdu != null) {
                    SmsMessage m = SmsMessage.createFromPdu(pdu, format);
                    int s = m.getStatus();
                    if ("3gpp2".equals(format)) {
                        int err = (s >> 24) & 0x03;
                        status = err == 0 ? Telephony.Sms.STATUS_COMPLETE : err == 2 ? Telephony.Sms.STATUS_PENDING : Telephony.Sms.STATUS_FAILED;
                    } else {
                        status = s < 0x20 ? Telephony.Sms.STATUS_COMPLETE : s < 0x40 ? Telephony.Sms.STATUS_PENDING : Telephony.Sms.STATUS_FAILED;
                    }
                }
            } catch (Exception ignored) {
            }
            v.put(Telephony.Sms.STATUS, status);
        } else {
            return;
        }
        try {
            c.getContentResolver().update(uri, v, null, null);
        } catch (Exception ignored) {
        }
    }
}
