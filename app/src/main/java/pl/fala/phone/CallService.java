package pl.fala.phone;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Person;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.drawable.Icon;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.telecom.Call;
import android.telecom.CallAudioState;
import android.telecom.InCallService;

import java.util.ArrayList;
import java.util.List;

/**
 * Serce aplikacji Telefon: system przekazuje tu każde połączenie,
 * a my pokazujemy ekran rozmowy i powiadomienia.
 */
public class CallService extends InCallService {

    interface Listener {
        void onCallsChanged();

        void onAudioChanged(CallAudioState s);
    }

    static final String CH_INCOMING = "incoming", CH_ONGOING = "ongoing";
    static final int NOTIF = 77;

    static CallService instance;
    static final List<Call> calls = new ArrayList<>();
    static final List<Listener> listeners = new ArrayList<>();
    private final Handler main = new Handler(Looper.getMainLooper());

    private final Call.Callback cb = new Call.Callback() {
        @Override
        public void onStateChanged(Call call, int state) {
            update();
            if (state == Call.STATE_DISCONNECTED) main.postDelayed(() -> {
                calls.remove(call);
                update();
            }, 1200);
        }

        @Override
        public void onDetailsChanged(Call call, Call.Details details) {
            update();
        }
    };

    /** Połączenie najważniejsze dla ekranu: dzwoniące, potem aktywne, potem pozostałe. */
    static Call primary() {
        Call best = null;
        for (Call c : calls) {
            int s = c.getState();
            if (s == Call.STATE_RINGING) return c;
            if (best == null || s == Call.STATE_ACTIVE || s == Call.STATE_DIALING || s == Call.STATE_CONNECTING
                    || s == Call.STATE_SELECT_PHONE_ACCOUNT) best = c;
        }
        return best;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
        channels(this);
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        if (instance == this) instance = null;
    }

    static void channels(Context c) {
        if (Build.VERSION.SDK_INT < 26) return;
        NotificationManager nm = c.getSystemService(NotificationManager.class);
        NotificationChannel in = new NotificationChannel(CH_INCOMING, "Połączenia przychodzące", NotificationManager.IMPORTANCE_HIGH);
        in.setSound(null, null); // dzwonek gra system (Telecom)
        nm.createNotificationChannel(in);
        NotificationChannel on = new NotificationChannel(CH_ONGOING, "Trwająca rozmowa", NotificationManager.IMPORTANCE_LOW);
        nm.createNotificationChannel(on);
    }

    @Override
    public void onCallAdded(Call call) {
        calls.add(call);
        call.registerCallback(cb);
        if (call.getState() != Call.STATE_RINGING) openUi();
        else {
            update();
            openUi(); // przy włączonym ekranie – od razu; inaczej przez powiadomienie pełnoekranowe
        }
        update();
    }

    @Override
    public void onCallRemoved(Call call) {
        call.unregisterCallback(cb);
        calls.remove(call);
        update();
    }

    @Override
    public void onCallAudioStateChanged(CallAudioState audioState) {
        for (Listener l : new ArrayList<>(listeners)) l.onAudioChanged(audioState);
    }

    private void openUi() {
        try {
            startActivity(new Intent(this, CallActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP));
        } catch (Exception ignored) {
        }
    }

    private void update() {
        for (Listener l : new ArrayList<>(listeners)) l.onCallsChanged();
        notification();
    }

    static String numberOf(Call c) {
        Uri h = c.getDetails().getHandle();
        return h != null ? h.getSchemeSpecificPart() : "";
    }

    private PendingIntent action(String a, int req) {
        if (ActionReceiver.ANSWER.equals(a)) {
            // odebranie musi otworzyć ekran bezpośrednio (Android blokuje „trampoliny” z powiadomień)
            Intent i = new Intent(this, CallActivity.class).setAction(a).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            return PendingIntent.getActivity(this, req, i, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        }
        Intent i = new Intent(this, ActionReceiver.class).setAction(a);
        return PendingIntent.getBroadcast(this, req, i, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    private void notification() {
        NotificationManager nm = getSystemService(NotificationManager.class);
        Call c = primary();
        if (c == null || c.getState() == Call.STATE_DISCONNECTED) {
            nm.cancel(NOTIF);
            return;
        }
        String number = numberOf(c);
        Ph.Contact k = Ph.lookup(this, number);
        Bitmap av = Ph.avatar(this, k.name, k.photo, 160);
        PendingIntent open = PendingIntent.getActivity(this, 1,
                new Intent(this, CallActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        boolean ringing = c.getState() == Call.STATE_RINGING;
        Notification.Builder b = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(this, ringing ? CH_INCOMING : CH_ONGOING) : new Notification.Builder(this);
        b.setSmallIcon(R.drawable.ic_phone)
                .setContentTitle(k.name)
                .setLargeIcon(av)
                .setContentIntent(open)
                .setOngoing(true)
                .setCategory(Notification.CATEGORY_CALL)
                .setColor(Ph.GREEN);
        if (ringing) {
            b.setContentText("Połączenie przychodzące").setFullScreenIntent(open, true);
            if (Build.VERSION.SDK_INT >= 31) {
                Person p = new Person.Builder().setName(k.name).setIcon(Icon.createWithBitmap(av)).setImportant(true).build();
                b.setStyle(Notification.CallStyle.forIncomingCall(p, action(ActionReceiver.DECLINE, 2), action(ActionReceiver.ANSWER, 3)));
            } else {
                b.addAction(new Notification.Action.Builder(null, "Odrzuć", action(ActionReceiver.DECLINE, 2)).build());
                b.addAction(new Notification.Action.Builder(null, "Odbierz", action(ActionReceiver.ANSWER, 3)).build());
            }
        } else {
            String st = c.getState() == Call.STATE_ACTIVE ? "Trwa rozmowa"
                    : c.getState() == Call.STATE_HOLDING ? "Wstrzymane" : "Łączenie…";
            b.setContentText(st);
            long since = c.getDetails().getConnectTimeMillis();
            if (c.getState() == Call.STATE_ACTIVE && since > 0) {
                b.setUsesChronometer(true).setWhen(since).setShowWhen(true);
            }
            b.addAction(new Notification.Action.Builder(null, "Rozłącz", action(ActionReceiver.HANGUP, 4)).build());
        }
        try {
            nm.notify(NOTIF, b.build());
        } catch (Exception ignored) {
        }
    }
}
