package pl.fala.launcher;

import android.app.KeyguardManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.graphics.PixelFormat;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.provider.Settings;
import android.service.notification.StatusBarNotification;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.widget.FrameLayout;

/**
 * Dynamic Island nad wszystkimi aplikacjami – nakładka systemowa.
 * Okno nakładki ma rozmiar pigułki, więc nie blokuje dotyku w aplikacjach.
 */
public class IslandService extends Service {

    private static final String CHANNEL = "island";
    static volatile IslandService instance;

    private final Handler main = new Handler(Looper.getMainLooper());
    private WindowManager wm;
    private FrameLayout root;
    private IslandView island;
    private IslandController ctl;
    private WindowManager.LayoutParams lp;
    private boolean attached = false;
    private boolean fullscreenApp = false;
    private boolean locked = false;
    private BroadcastReceiver screenReceiver;
    private String lastNotifKey = null;
    private long lastNotifTime = 0;

    static void start(Context c) {
        if (instance != null) return;
        try {
            Intent i = new Intent(c, IslandService.class);
            if (Build.VERSION.SDK_INT >= 26) c.startForegroundService(i);
            else c.startService(i);
        } catch (Exception ignored) {
        }
    }

    static void stop(Context c) {
        if (instance == null) return;
        try {
            c.stopService(new Intent(c, IslandService.class));
        } catch (Exception ignored) {
        }
    }

    private int dp(float v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
        goForeground();
        if (!Settings.canDrawOverlays(this)) {
            stopSelf();
            return;
        }
        wm = (WindowManager) getSystemService(Context.WINDOW_SERVICE);
        buildOverlay();
        LockActivity.ensureScreenReceiver(getApplicationContext());

        screenReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context c, Intent i) {
                String a = i.getAction();
                if (Intent.ACTION_SCREEN_OFF.equals(a)) {
                    locked = true;
                    island.collapse();
                } else if (Intent.ACTION_USER_PRESENT.equals(a)) {
                    locked = false;
                } else if (Intent.ACTION_SCREEN_ON.equals(a)) {
                    KeyguardManager km = (KeyguardManager) getSystemService(Context.KEYGUARD_SERVICE);
                    locked = km != null && km.isKeyguardLocked();
                }
                updateVisibility();
            }
        };
        IntentFilter f = new IntentFilter();
        f.addAction(Intent.ACTION_SCREEN_OFF);
        f.addAction(Intent.ACTION_SCREEN_ON);
        f.addAction(Intent.ACTION_USER_PRESENT);
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(screenReceiver, f, Context.RECEIVER_EXPORTED);
        else registerReceiver(screenReceiver, f);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        instance = null;
        try { if (screenReceiver != null) unregisterReceiver(screenReceiver); } catch (Exception ignored) { }
        if (ctl != null) ctl.stop();
        if (attached) {
            try { wm.removeView(root); } catch (Exception ignored) { }
            attached = false;
        }
    }

    private void goForeground() {
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel ch = new NotificationChannel(CHANNEL, "Wyspa Fala", NotificationManager.IMPORTANCE_MIN);
            ch.setShowBadge(false);
            nm.createNotificationChannel(ch);
        }
        PendingIntent open = PendingIntent.getActivity(this, 0,
                new Intent(this, MainActivity.class), PendingIntent.FLAG_IMMUTABLE);
        Notification.Builder b = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(this, CHANNEL) : new Notification.Builder(this);
        Notification n = b.setSmallIcon(R.drawable.ic_music)
                .setContentTitle("Wyspa jest aktywna")
                .setContentText("Fala wyświetla wyspę nad aplikacjami")
                .setContentIntent(open)
                .setOngoing(true)
                .build();
        try {
            if (Build.VERSION.SDK_INT >= 34) {
                startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
            } else {
                startForeground(1, n);
            }
        } catch (Exception e) {
            stopSelf();
        }
    }

    // ---------- nakładka ----------

    private int statusBarHeight() {
        int id = getResources().getIdentifier("status_bar_height", "dimen", "android");
        return id > 0 ? getResources().getDimensionPixelSize(id) : dp(28);
    }

    private void buildOverlay() {
        root = new FrameLayout(this);
        island = new IslandView(this);
        root.addView(island, new FrameLayout.LayoutParams(-1, -1, Gravity.TOP | Gravity.CENTER_HORIZONTAL));

        ctl = new IslandController(this, island, new IslandController.Host() {
            @Override
            public void onExpandChanged(boolean expanded) {
                if (expanded) lp.flags &= ~WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL;
                else lp.flags |= WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL;
                apply();
            }

            @Override
            public void onIslandSize(int w, int h, boolean starting) {
                resize(w, h, starting);
            }
        });

        // dotyk poza wyspą zwija ją
        root.setOnTouchListener((v, e) -> {
            if (e.getActionMasked() == MotionEvent.ACTION_OUTSIDE || e.getActionMasked() == MotionEvent.ACTION_DOWN) {
                if (island.isExpanded()) island.collapse();
            }
            return false;
        });

        // gry i filmy na pełnym ekranie – chowamy wyspę
        if (Build.VERSION.SDK_INT >= 30) {
            root.setOnApplyWindowInsetsListener((v, ins) -> {
                boolean full = !ins.isVisible(WindowInsets.Type.statusBars());
                if (full != fullscreenApp) {
                    fullscreenApp = full;
                    main.post(this::updateVisibility);
                }
                return ins;
            });
        }

        int type = Build.VERSION.SDK_INT >= 26
                ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                : WindowManager.LayoutParams.TYPE_PHONE;
        lp = new WindowManager.LayoutParams(
                island.idleW() + dp(16), island.idleH() + dp(12), type,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                        | WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                        | WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
        lp.y = Math.max(dp(6), (statusBarHeight() - island.idleH()) / 2);
        if (Build.VERSION.SDK_INT >= 28) {
            lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
        }
        lp.setTitle("FalaIsland");
        try {
            wm.addView(root, lp);
            attached = true;
        } catch (Exception e) {
            stopSelf();
            return;
        }
        ctl.start();
    }

    /** Okno rośnie przed animacją i maleje po niej – dotyk w aplikacjach zostaje wolny. */
    private void resize(int w, int h, boolean starting) {
        if (!attached) return;
        int ww = w + dp(16);
        int wh = h + dp(14);
        if (starting) {
            if (ww > lp.width || wh > lp.height) {
                lp.width = Math.max(ww, lp.width);
                lp.height = Math.max(wh, lp.height);
                apply();
            }
        } else {
            lp.width = ww;
            lp.height = wh;
            apply();
        }
    }

    private void apply() {
        if (!attached) return;
        try {
            wm.updateViewLayout(root, lp);
        } catch (Exception ignored) {
        }
    }

    private void updateVisibility() {
        if (root == null) return;
        boolean show = !locked && !fullscreenApp;
        root.setVisibility(show ? View.VISIBLE : View.GONE);
    }

    // ---------- powiadomienia na wyspie ----------

    void onNotification(StatusBarNotification sbn) {
        if (island == null || locked || fullscreenApp) return;
        if (!getSharedPreferences("fala", MODE_PRIVATE).getBoolean("island_notifs", true)) return;
        if (sbn.isOngoing() || sbn.getPackageName().equals(getPackageName())) return;
        Notification n = sbn.getNotification();
        if ((n.flags & Notification.FLAG_GROUP_SUMMARY) != 0) return;
        if ((n.flags & Notification.FLAG_ONLY_ALERT_ONCE) != 0 && sbn.getKey().equals(lastNotifKey)) return;
        if (System.currentTimeMillis() - sbn.getPostTime() > 10000) return;
        if (Notification.CATEGORY_TRANSPORT.equals(n.category) || Notification.CATEGORY_PROGRESS.equals(n.category)) return;
        Bundle ex = n.extras;
        CharSequence title = ex.getCharSequence(Notification.EXTRA_TITLE);
        CharSequence text = ex.getCharSequence(Notification.EXTRA_TEXT);
        if (TextUtils.isEmpty(title) && TextUtils.isEmpty(text)) return;
        if (sbn.getKey().equals(lastNotifKey) && System.currentTimeMillis() - lastNotifTime < 1500) return;
        lastNotifKey = sbn.getKey();
        lastNotifTime = System.currentTimeMillis();

        Drawable icon = null;
        try {
            PackageManager pm = getPackageManager();
            icon = pm.getApplicationIcon(sbn.getPackageName());
        } catch (Exception ignored) {
        }
        final PendingIntent pi = n.contentIntent;
        final String t = title != null ? title.toString() : "";
        final String tx = text != null ? text.toString() : "";
        final Drawable ic = icon;
        main.post(() -> island.showNotification(ic, t, tx, () -> IslandController.sendPending(this, pi)));
    }
}
