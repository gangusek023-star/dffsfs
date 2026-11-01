package pl.fala.phone;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.telecom.Call;
import android.telecom.VideoProfile;

/** Odbierz / Odrzuć / Rozłącz z powiadomienia. */
public class ActionReceiver extends BroadcastReceiver {
    static final String ANSWER = "pl.fala.phone.ANSWER", DECLINE = "pl.fala.phone.DECLINE", HANGUP = "pl.fala.phone.HANGUP";

    @Override
    public void onReceive(Context c, Intent intent) {
        Call call = CallService.primary();
        if (call == null) return;
        String a = intent.getAction();
        if (ANSWER.equals(a)) {
            call.answer(VideoProfile.STATE_AUDIO_ONLY);
        } else if (DECLINE.equals(a)) {
            call.reject(false, null);
        } else if (HANGUP.equals(a)) {
            call.disconnect();
        }
    }
}
