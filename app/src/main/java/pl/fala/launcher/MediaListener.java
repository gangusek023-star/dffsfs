package pl.fala.launcher;

import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;

/**
 * Dostęp do powiadomień: muzyka na wyspie i powiadomienia na ekranie blokady.
 */
public class MediaListener extends NotificationListenerService {

    static volatile MediaListener instance;
    static volatile Runnable onChange;

    @Override
    public void onListenerConnected() {
        instance = this;
        Runnable r = onChange;
        if (r != null) r.run();
    }

    @Override
    public void onListenerDisconnected() {
        instance = null;
    }

    @Override
    public void onNotificationPosted(StatusBarNotification sbn) {
        Runnable r = onChange;
        if (r != null) r.run();
    }

    @Override
    public void onNotificationRemoved(StatusBarNotification sbn) {
        Runnable r = onChange;
        if (r != null) r.run();
    }

    static StatusBarNotification[] current() {
        MediaListener l = instance;
        if (l == null) return new StatusBarNotification[0];
        try {
            StatusBarNotification[] n = l.getActiveNotifications();
            return n != null ? n : new StatusBarNotification[0];
        } catch (Exception e) {
            return new StatusBarNotification[0];
        }
    }
}
