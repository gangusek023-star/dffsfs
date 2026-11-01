package pl.fala.sms;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/**
 * Wymagany, aby aplikacja mogła być domyślną aplikacją SMS.
 * Pobieranie MMS nie jest obsługiwane.
 */
public class MmsReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context c, Intent intent) {
    }
}
