package pl.fala.sms;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.provider.Telephony;

/** Wynik wysyłki i potwierdzenie doręczenia – aktualizuje status wiadomości. */
public class StatusReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context c, Intent intent) {
        Uri uri = intent.getData();
        if (uri == null) return;
        ContentValues v = new ContentValues();
        if (Sms.ACTION_SENT.equals(intent.getAction())) {
            boolean ok = getResultCode() == Activity.RESULT_OK;
            if (!ok) {
                v.put(Telephony.Sms.TYPE, Telephony.Sms.MESSAGE_TYPE_FAILED);
            } else if (intent.getBooleanExtra("last", true)) {
                v.put(Telephony.Sms.TYPE, Telephony.Sms.MESSAGE_TYPE_SENT);
            } else {
                return;
            }
        } else if (Sms.ACTION_DELIVERED.equals(intent.getAction())) {
            v.put(Telephony.Sms.STATUS, Telephony.Sms.STATUS_COMPLETE);
        } else {
            return;
        }
        try {
            c.getContentResolver().update(uri, v, null, null);
        } catch (Exception ignored) {
        }
    }
}
