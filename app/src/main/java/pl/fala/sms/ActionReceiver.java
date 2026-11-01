package pl.fala.sms;

import android.app.RemoteInput;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;

/** Odpowiedź i „przeczytane” prosto z powiadomienia. */
public class ActionReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context c, Intent intent) {
        long thread = intent.getLongExtra("thread", -1);
        String address = intent.getStringExtra("address");
        if (Sms.ACTION_REPLY.equals(intent.getAction())) {
            Bundle r = RemoteInput.getResultsFromIntent(intent);
            CharSequence text = r != null ? r.getCharSequence(Sms.KEY_REPLY) : null;
            if (text == null || text.toString().trim().isEmpty() || address == null) return;
            final PendingResult pr = goAsync();
            new Thread(() -> {
                try {
                    Sms.send(c, address, text.toString().trim(), Sms.defaultSub());
                    Sms.markRead(c, thread);
                } finally {
                    pr.finish();
                }
            }).start();
        } else if (Sms.ACTION_READ.equals(intent.getAction())) {
            final PendingResult pr = goAsync();
            new Thread(() -> {
                try {
                    Sms.markRead(c, thread);
                } finally {
                    pr.finish();
                }
            }).start();
        }
    }
}
