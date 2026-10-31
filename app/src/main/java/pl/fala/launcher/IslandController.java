package pl.fala.launcher;

import android.app.ActivityOptions;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Bitmap;
import android.graphics.drawable.Drawable;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraManager;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.widget.Toast;

import java.util.List;

/**
 * Wspólna logika wyspy (bateria, latarka, muzyka, przyciski) –
 * używana na ekranie głównym, na ekranie blokady i w nakładce nad aplikacjami.
 */
public class IslandController implements IslandView.Listener {

    public interface Host {
        void onExpandChanged(boolean expanded);

        void onIslandSize(int w, int h, boolean starting);
    }

    private final Context ctx;
    private final IslandView island;
    private final Host host;
    private final Handler main = new Handler(Looper.getMainLooper());

    private BroadcastReceiver batteryReceiver;
    private int batteryLevel = -1;

    private CameraManager cameraManager;
    private String torchId;
    private boolean torchOn = false;
    private CameraManager.TorchCallback torchCallback;

    private MediaSessionManager msm;
    private MediaSessionManager.OnActiveSessionsChangedListener sessionsListener;
    private MediaController controller;
    private MediaController.Callback controllerCallback;
    private boolean mediaRegistered = false;
    private boolean started = false;

    public IslandController(Context ctx, IslandView island, Host host) {
        this.ctx = ctx;
        this.island = island;
        this.host = host;
        island.setListener(this);
    }

    public IslandView view() {
        return island;
    }

    public void start() {
        if (started) return;
        started = true;
        registerBattery();
        initTorch();
        setupMedia();
    }

    /** Wywołuj przy powrocie na ekran – dostęp do powiadomień mógł się zmienić. */
    public void refresh() {
        setupMedia();
    }

    public void stop() {
        if (!started) return;
        started = false;
        try { if (batteryReceiver != null) ctx.unregisterReceiver(batteryReceiver); } catch (Exception ignored) { }
        try { if (cameraManager != null && torchCallback != null) cameraManager.unregisterTorchCallback(torchCallback); } catch (Exception ignored) { }
        try { if (msm != null && sessionsListener != null) msm.removeOnActiveSessionsChangedListener(sessionsListener); } catch (Exception ignored) { }
        try { if (controller != null && controllerCallback != null) controller.unregisterCallback(controllerCallback); } catch (Exception ignored) { }
        mediaRegistered = false;
        controller = null;
    }

    static boolean hasNotificationAccess(Context c) {
        String flat = Settings.Secure.getString(c.getContentResolver(), "enabled_notification_listeners");
        return flat != null && flat.contains(c.getPackageName());
    }

    // ---------- bateria ----------

    private void registerBattery() {
        batteryReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context c, Intent i) {
                String act = i.getAction();
                if (Intent.ACTION_BATTERY_CHANGED.equals(act)) {
                    int lvl = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
                    int scale = i.getIntExtra(BatteryManager.EXTRA_SCALE, 100);
                    int status = i.getIntExtra(BatteryManager.EXTRA_STATUS, -1);
                    batteryLevel = (lvl >= 0 && scale > 0) ? lvl * 100 / scale : -1;
                    boolean charging = status == BatteryManager.BATTERY_STATUS_CHARGING
                            || status == BatteryManager.BATTERY_STATUS_FULL;
                    island.setBattery(batteryLevel, charging);
                } else if (Intent.ACTION_POWER_CONNECTED.equals(act)) {
                    main.postDelayed(island::showChargingBanner, 350);
                }
            }
        };
        IntentFilter f = new IntentFilter();
        f.addAction(Intent.ACTION_BATTERY_CHANGED);
        f.addAction(Intent.ACTION_POWER_CONNECTED);
        if (Build.VERSION.SDK_INT >= 33) ctx.registerReceiver(batteryReceiver, f, Context.RECEIVER_EXPORTED);
        else ctx.registerReceiver(batteryReceiver, f);
    }

    // ---------- latarka ----------

    private void initTorch() {
        try {
            cameraManager = (CameraManager) ctx.getSystemService(Context.CAMERA_SERVICE);
            for (String id : cameraManager.getCameraIdList()) {
                Boolean flash = cameraManager.getCameraCharacteristics(id)
                        .get(CameraCharacteristics.FLASH_INFO_AVAILABLE);
                if (flash != null && flash) {
                    torchId = id;
                    break;
                }
            }
            torchCallback = new CameraManager.TorchCallback() {
                @Override
                public void onTorchModeChanged(String cameraId, boolean enabled) {
                    if (cameraId.equals(torchId)) {
                        torchOn = enabled;
                        island.setTorch(enabled);
                    }
                }
            };
            cameraManager.registerTorchCallback(torchCallback, main);
        } catch (Exception ignored) {
        }
    }

    // ---------- muzyka ----------

    private void setupMedia() {
        if (mediaRegistered) return;
        boolean access = hasNotificationAccess(ctx);
        island.setMusicAccess(access);
        if (!access) return;
        try {
            msm = (MediaSessionManager) ctx.getSystemService(Context.MEDIA_SESSION_SERVICE);
            ComponentName cn = new ComponentName(ctx, MediaListener.class);
            sessionsListener = this::pickController;
            msm.addOnActiveSessionsChangedListener(sessionsListener, cn, main);
            pickController(msm.getActiveSessions(cn));
            mediaRegistered = true;
        } catch (Exception ignored) {
        }
    }

    private void pickController(List<MediaController> list) {
        MediaController best = null;
        if (list != null) {
            for (MediaController c : list) {
                PlaybackState s = c.getPlaybackState();
                if (s != null && s.getState() == PlaybackState.STATE_PLAYING) {
                    best = c;
                    break;
                }
            }
            if (best == null && !list.isEmpty()) best = list.get(0);
        }
        boolean same = controller != null && best != null
                && controller.getSessionToken().equals(best.getSessionToken());
        if (!same) {
            if (controller != null && controllerCallback != null) {
                try { controller.unregisterCallback(controllerCallback); } catch (Exception ignored) { }
            }
            controller = best;
            if (controller != null) {
                controllerCallback = new MediaController.Callback() {
                    @Override public void onPlaybackStateChanged(PlaybackState state) { refreshMusic(); }
                    @Override public void onMetadataChanged(MediaMetadata metadata) { refreshMusic(); }

                    @Override
                    public void onSessionDestroyed() {
                        controller = null;
                        refreshMusic();
                    }
                };
                controller.registerCallback(controllerCallback, main);
            }
        }
        refreshMusic();
    }

    private void refreshMusic() {
        MediaController c = controller;
        MediaMetadata md = c != null ? c.getMetadata() : null;
        if (c == null || md == null) {
            island.setMusic(null, null, null, null, false);
            return;
        }
        String title = md.getString(MediaMetadata.METADATA_KEY_TITLE);
        if (title == null) title = md.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE);
        String artist = md.getString(MediaMetadata.METADATA_KEY_ARTIST);
        if (artist == null) artist = md.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST);
        Bitmap art = md.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART);
        if (art == null) art = md.getBitmap(MediaMetadata.METADATA_KEY_ART);
        if (art == null) art = md.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON);
        PlaybackState s = c.getPlaybackState();
        boolean playing = s != null && s.getState() == PlaybackState.STATE_PLAYING;
        Drawable appIcon = null;
        try {
            appIcon = ctx.getPackageManager().getApplicationIcon(c.getPackageName());
        } catch (Exception ignored) {
        }
        if (title == null) island.setMusic(null, null, null, null, false);
        else island.setMusic(title, artist, art, appIcon, playing);
    }

    // ---------- akcje ----------

    private void startSafe(Intent i) {
        try {
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            ctx.startActivity(i);
        } catch (Exception e) {
            Toast.makeText(ctx, "Brak aplikacji do tej akcji", Toast.LENGTH_SHORT).show();
        }
    }

    static void sendPending(Context ctx, PendingIntent pi) {
        if (pi == null) return;
        try {
            Bundle opts = null;
            if (Build.VERSION.SDK_INT >= 34) {
                opts = ActivityOptions.makeBasic().setPendingIntentBackgroundActivityStartMode(
                        ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED).toBundle();
            }
            pi.send(ctx, 0, null, null, null, null, opts);
        } catch (Exception ignored) {
        }
    }

    @SuppressWarnings({"WrongConstant", "JavaReflectionMemberAccess"})
    static void expandNotifications(Context ctx) {
        try {
            Object sb = ctx.getSystemService("statusbar");
            Class<?> c = Class.forName("android.app.StatusBarManager");
            c.getMethod("expandNotificationsPanel").invoke(sb);
        } catch (Exception ignored) {
        }
    }

    // ---------- IslandView.Listener ----------

    @Override
    public void onPlayPause() {
        if (controller == null) return;
        PlaybackState s = controller.getPlaybackState();
        if (s != null && s.getState() == PlaybackState.STATE_PLAYING) controller.getTransportControls().pause();
        else controller.getTransportControls().play();
    }

    @Override
    public void onNext() {
        if (controller != null) controller.getTransportControls().skipToNext();
    }

    @Override
    public void onPrev() {
        if (controller != null) controller.getTransportControls().skipToPrevious();
    }

    @Override
    public void onTorch() {
        if (cameraManager == null || torchId == null) {
            Toast.makeText(ctx, "Brak latarki", Toast.LENGTH_SHORT).show();
            return;
        }
        try {
            cameraManager.setTorchMode(torchId, !torchOn);
        } catch (Exception e) {
            Toast.makeText(ctx, "Latarka jest zajęta", Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    public void onSettings() {
        island.collapse();
        startSafe(new Intent(Settings.ACTION_SETTINGS));
    }

    @Override
    public void onNotifications() {
        island.collapse();
        expandNotifications(ctx);
    }

    @Override
    public void onEnableMusic() {
        island.collapse();
        Toast.makeText(ctx, "Włącz dostęp dla aplikacji Fala", Toast.LENGTH_LONG).show();
        startSafe(new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS));
    }

    @Override
    public void onOpenMusic() {
        if (controller == null) return;
        PendingIntent pi = controller.getSessionActivity();
        island.collapse();
        if (pi != null) {
            sendPending(ctx, pi);
            return;
        }
        Intent i = ctx.getPackageManager().getLaunchIntentForPackage(controller.getPackageName());
        if (i != null) startSafe(i);
    }

    @Override
    public void onAssistant() {
        island.collapse();
        Intent i = new Intent(ctx, AssistantActivity.class);
        i.addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION);
        startSafe(i);
    }

    @Override
    public void onExpandChanged(boolean expanded) {
        if (host != null) host.onExpandChanged(expanded);
    }

    @Override
    public void onIslandSize(int w, int h, boolean starting) {
        if (host != null) host.onIslandSize(w, h, starting);
    }
}
