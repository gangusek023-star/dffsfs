package pl.fala.launcher;

import android.app.Activity;
import android.app.ActivityOptions;
import android.app.KeyguardManager;
import android.app.Notification;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.service.notification.StatusBarNotification;
import android.text.TextUtils;
import android.text.format.DateUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.VelocityTracker;
import android.view.View;
import android.view.WindowManager;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.PathInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextClock;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Ekran blokady w stylu iPhone'a – pokazuje się nad systemową blokadą
 * po wyłączeniu ekranu. Przesunięcie w górę prosi system o odblokowanie
 * (PIN / odcisk palca zostaje systemowy, więc telefon jest dalej bezpieczny).
 */
public class LockActivity extends Activity {

    private final Handler main = new Handler(Looper.getMainLooper());
    private FrameLayout root;
    private LinearLayout content;
    private LinearLayout notifList;
    private ImageView torchBtn;
    private int insetTop = 0, insetBottom = 0;
    private BroadcastReceiver unlockReceiver;
    private CameraManager cameraManager;
    private String torchId;
    private boolean torchOn = false;
    private CameraManager.TorchCallback torchCallback;
    private boolean unlocking = false;
    private IslandView island;
    private IslandController islandCtl;

    private static boolean screenReceiverRegistered = false;

    /** Uruchamia ekran blokady Fali, jeśli jest włączony i nie trwa rozmowa. */
    static void launchIfEnabled(Context app) {
        if (!app.getSharedPreferences("fala", MODE_PRIVATE).getBoolean("lock", false)) return;
        android.media.AudioManager am = (android.media.AudioManager) app.getSystemService(Context.AUDIO_SERVICE);
        int mode = am != null ? am.getMode() : 0;
        if (mode == android.media.AudioManager.MODE_IN_CALL
                || mode == android.media.AudioManager.MODE_IN_COMMUNICATION
                || mode == android.media.AudioManager.MODE_RINGTONE) return;
        try {
            Intent li = new Intent(app, LockActivity.class);
            li.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_NO_ANIMATION
                    | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
            app.startActivity(li);
        } catch (Exception ignored) {
        }
    }

    /** Zapasowy odbiornik, gdy usługa wyspy nie działa. */
    static synchronized void ensureScreenReceiver(Context app) {
        if (screenReceiverRegistered) return;
        screenReceiverRegistered = true;
        BroadcastReceiver r = new BroadcastReceiver() {
            @Override
            public void onReceive(Context c, Intent i) {
                if (!Intent.ACTION_SCREEN_OFF.equals(i.getAction())) return;
                if (IslandService.instance != null) return; // usługa zajmuje się tym sama
                launchIfEnabled(app);
            }
        };
        IntentFilter f = new IntentFilter(Intent.ACTION_SCREEN_OFF);
        if (Build.VERSION.SDK_INT >= 33) app.registerReceiver(r, f, Context.RECEIVER_EXPORTED);
        else app.registerReceiver(r, f);
    }

    private int dp(float v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (Build.VERSION.SDK_INT >= 27) {
            setShowWhenLocked(true);
        } else {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED);
        }
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                        | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);

        buildUi();
        setContentView(root);
        root.setOnApplyWindowInsetsListener((v, ins) -> {
            insetTop = ins.getSystemWindowInsetTop();
            insetBottom = ins.getSystemWindowInsetBottom();
            content.setPadding(dp(20), insetTop + dp(10), dp(20), insetBottom + dp(10));
            FrameLayout.LayoutParams ilp = (FrameLayout.LayoutParams) island.getLayoutParams();
            ilp.topMargin = IslandView.topFor(this, Build.VERSION.SDK_INT >= 28 ? ins.getDisplayCutout() : null,
                    insetTop, island.idleH());
            island.setLayoutParams(ilp);
            island.setCoverTop(insetTop - ilp.topMargin);
            return ins;
        });

        // odblokowanie odciskiem palca / twarzą przez system -> zamknij się
        unlockReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context c, Intent i) {
                if (Intent.ACTION_USER_PRESENT.equals(i.getAction())) finishSmooth();
            }
        };
        IntentFilter f = new IntentFilter(Intent.ACTION_USER_PRESENT);
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(unlockReceiver, f, Context.RECEIVER_EXPORTED);
        else registerReceiver(unlockReceiver, f);

        initTorch();
        islandCtl.start();
    }

    @Override
    protected void onResume() {
        super.onResume();
        unlocking = false;
        content.animate().cancel();
        content.setTranslationY(0);
        content.setAlpha(1f);
        MediaListener.onChange = () -> main.post(this::refreshNotifications);
        refreshNotifications();
        islandCtl.refresh();
        KeyguardManager km = (KeyguardManager) getSystemService(Context.KEYGUARD_SERVICE);
        if (km != null && !km.isKeyguardLocked() && !hasWindowFocus()) {
            // ekran odblokowany w międzyczasie
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        MediaListener.onChange = null;
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        try { unregisterReceiver(unlockReceiver); } catch (Exception ignored) { }
        islandCtl.stop();
        try { if (cameraManager != null && torchCallback != null) cameraManager.unregisterTorchCallback(torchCallback); } catch (Exception ignored) { }
    }

    @Override
    public void onBackPressed() {
        // jak na prawdziwej blokadzie – wstecz nic nie robi
    }

    // ---------- UI ----------

    private void buildUi() {
        root = new FrameLayout(this);
        View scrim = new View(this);
        scrim.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{0x59000000, 0x14000000, 0x26000000, 0x80000000}));
        root.addView(scrim, new FrameLayout.LayoutParams(-1, -1));

        content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setGravity(Gravity.CENTER_HORIZONTAL);
        root.addView(content, new FrameLayout.LayoutParams(-1, -1));

        ImageView lock = new ImageView(this);
        lock.setImageResource(R.drawable.ic_lock);
        lock.setAlpha(0.9f);
        LinearLayout.LayoutParams llp = new LinearLayout.LayoutParams(dp(20), dp(20));
        llp.topMargin = dp(42);
        content.addView(lock, llp);

        TextClock date = new TextClock(this);
        date.setFormat24Hour("EEEE, d MMMM");
        date.setFormat12Hour("EEEE, d MMMM");
        date.setTextColor(0xF2FFFFFF);
        date.setTextSize(TypedValue.COMPLEX_UNIT_SP, 19);
        date.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        date.setShadowLayer(dp(6), 0, dp(1), 0x40000000);
        LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(-2, -2);
        dlp.topMargin = dp(14);
        content.addView(date, dlp);

        TextClock time = new TextClock(this);
        time.setFormat24Hour("H:mm");
        time.setFormat12Hour("h:mm");
        time.setTextColor(0xF5FFFFFF);
        time.setTextSize(TypedValue.COMPLEX_UNIT_SP, 104);
        time.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        time.setLetterSpacing(-0.04f);
        time.setIncludeFontPadding(false);
        time.setShadowLayer(dp(14), 0, dp(2), 0x40000000);
        content.addView(time, new LinearLayout.LayoutParams(-2, -2));

        ScrollView sv = new ScrollView(this);
        sv.setVerticalScrollBarEnabled(false);
        sv.setOverScrollMode(View.OVER_SCROLL_NEVER);
        sv.setClipToPadding(false);
        notifList = new LinearLayout(this);
        notifList.setOrientation(LinearLayout.VERTICAL);
        notifList.setGravity(Gravity.BOTTOM);
        sv.addView(notifList, new ScrollView.LayoutParams(-1, -2));
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(-1, 0, 1f);
        slp.topMargin = dp(24);
        content.addView(sv, slp);

        // dół: latarka, podpowiedź, aparat
        FrameLayout bottom = new FrameLayout(this);
        torchBtn = roundButton(R.drawable.ic_flash);
        torchBtn.setOnClickListener(v -> toggleTorch());
        bottom.addView(torchBtn, new FrameLayout.LayoutParams(dp(52), dp(52), Gravity.START | Gravity.CENTER_VERTICAL));
        ImageView cam = roundButton(R.drawable.ic_camera);
        cam.setOnClickListener(v -> openCamera());
        bottom.addView(cam, new FrameLayout.LayoutParams(dp(52), dp(52), Gravity.END | Gravity.CENTER_VERTICAL));
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(-1, dp(64));
        blp.leftMargin = dp(18);
        blp.rightMargin = dp(18);
        content.addView(bottom, blp);

        TextView hint = new TextView(this);
        hint.setText("Przesuń w górę, aby otworzyć");
        hint.setTextColor(0xCCFFFFFF);
        hint.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        hint.setShadowLayer(dp(4), 0, dp(1), 0x66000000);
        LinearLayout.LayoutParams hlp = new LinearLayout.LayoutParams(-2, -2);
        hlp.topMargin = dp(10);
        content.addView(hint, hlp);
        hint.animate().alpha(0.45f).setDuration(1400).setStartDelay(600).withEndAction(new Runnable() {
            @Override
            public void run() {
                float to = hint.getAlpha() < 0.6f ? 1f : 0.45f;
                hint.animate().alpha(to).setDuration(1400).setStartDelay(0).withEndAction(this).start();
            }
        }).start();

        View bar = new View(this);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xE6FFFFFF);
        bg.setCornerRadius(dp(3));
        bar.setBackground(bg);
        LinearLayout.LayoutParams barLp = new LinearLayout.LayoutParams(dp(134), dp(5));
        barLp.topMargin = dp(12);
        barLp.bottomMargin = dp(6);
        content.addView(bar, barLp);

        root.setOnTouchListener(new SwipeUp());

        island = new IslandView(this);
        root.addView(island, new FrameLayout.LayoutParams(island.containerW(), island.containerH(),
                Gravity.TOP | Gravity.CENTER_HORIZONTAL));
        islandCtl = new IslandController(this, island, new IslandController.Host() {
            @Override public void onExpandChanged(boolean expanded) { }
            @Override public void onIslandSize(int w, int h, boolean starting) { }
        });
    }

    private ImageView roundButton(int icon) {
        ImageView b = new ImageView(this);
        b.setImageResource(icon);
        b.setScaleType(ImageView.ScaleType.CENTER);
        b.setColorFilter(Color.WHITE);
        b.setBackground(new GlassDrawable(getResources().getDisplayMetrics().density, dp(26), 1.1f));
        return b;
    }

    // ---------- powiadomienia ----------

    private void refreshNotifications() {
        if (replying) {
            // nie przerywaj pisania odpowiedzi
            refreshPending = true;
            return;
        }
        notifList.removeAllViews();
        List<StatusBarNotification> list = new ArrayList<>(Arrays.asList(MediaListener.current()));
        list.removeIf(s -> !s.isClearable() || s.getPackageName().equals(getPackageName())
                || (s.getNotification().flags & Notification.FLAG_GROUP_SUMMARY) != 0);
        list.sort((a, b) -> Long.compare(b.getPostTime(), a.getPostTime()));
        int n = Math.min(list.size(), 6);
        for (int i = 0; i < n; i++) {
            View card = notifCard(list.get(i));
            if (card == null) continue;
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
            lp.topMargin = dp(8);
            notifList.addView(card, lp);
        }
    }

    private boolean replying = false;
    private boolean refreshPending = false;

    private View notifCard(StatusBarNotification sbn) {
        if (!NotifUi.usable(sbn)) return null;
        return NotifUi.card(this, sbn, true, new NotifUi.Callbacks() {
            @Override public void onOpen(PendingIntent pi) { unlock(pi); }
            @Override public void onActivityAction(PendingIntent pi) { unlock(pi); }

            @Override
            public void onDone() {
                replying = false;
                refreshNotifications();
            }

            @Override
            public void onWantsKeyboard(boolean want) {
                replying = want;
                if (!want && refreshPending) {
                    refreshPending = false;
                    refreshNotifications();
                }
            }
        });
    }

    // ---------- akcje ----------

    private void unlock(PendingIntent then) {
        if (unlocking) return;
        unlocking = true;
        content.animate().translationY(-dp(140)).alpha(0f).setDuration(260)
                .setInterpolator(new PathInterpolator(0.3f, 0f, 0.8f, 0.15f)).start();
        KeyguardManager km = (KeyguardManager) getSystemService(Context.KEYGUARD_SERVICE);
        if (km != null && km.isKeyguardLocked() && Build.VERSION.SDK_INT >= 26) {
            km.requestDismissKeyguard(this, new KeyguardManager.KeyguardDismissCallback() {
                @Override
                public void onDismissSucceeded() {
                    sendAndFinish(then);
                }

                @Override
                public void onDismissCancelled() {
                    resetPosition();
                }

                @Override
                public void onDismissError() {
                    sendAndFinish(then);
                }
            });
        } else {
            main.postDelayed(() -> sendAndFinish(then), 200);
        }
    }

    private void sendAndFinish(PendingIntent pi) {
        if (pi != null) {
            try {
                Bundle opts = null;
                if (Build.VERSION.SDK_INT >= 34) {
                    opts = ActivityOptions.makeBasic().setPendingIntentBackgroundActivityStartMode(
                            ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED).toBundle();
                }
                pi.send(this, 0, null, null, null, null, opts);
            } catch (Exception ignored) {
            }
        }
        finishSmooth();
    }

    private void finishSmooth() {
        finish();
        overridePendingTransition(0, android.R.anim.fade_out);
    }

    private void resetPosition() {
        unlocking = false;
        content.animate().translationY(0).alpha(1f).setDuration(380)
                .setInterpolator(new DecelerateInterpolator(2f)).start();
    }

    private void openCamera() {
        root.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
        try {
            Intent i = new Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA_SECURE);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(i);
        } catch (Exception e) {
            try {
                Intent i = new Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA);
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(i);
            } catch (Exception ignored) {
            }
        }
    }

    private void initTorch() {
        try {
            cameraManager = (CameraManager) getSystemService(Context.CAMERA_SERVICE);
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
                        torchBtn.setColorFilter(enabled ? 0xFFFFD60A : Color.WHITE);
                    }
                }
            };
            cameraManager.registerTorchCallback(torchCallback, main);
        } catch (Exception ignored) {
        }
    }

    private void toggleTorch() {
        root.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
        if (cameraManager == null || torchId == null) return;
        try {
            cameraManager.setTorchMode(torchId, !torchOn);
        } catch (Exception ignored) {
        }
    }

    /** Przesunięcie w górę całej zawartości = odblokowanie. */
    private class SwipeUp implements View.OnTouchListener {
        float downY;
        VelocityTracker vt;

        @Override
        public boolean onTouch(View v, MotionEvent e) {
            if (unlocking) return true;
            if (island.isExpanded() && e.getActionMasked() == MotionEvent.ACTION_DOWN) {
                island.collapse();
                return false;
            }
            if (vt == null) vt = VelocityTracker.obtain();
            vt.addMovement(e);
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    downY = e.getRawY();
                    content.animate().cancel();
                    return true;
                case MotionEvent.ACTION_MOVE: {
                    float dy = Math.min(0, e.getRawY() - downY);
                    content.setTranslationY(dy);
                    content.setAlpha(1f + dy / (root.getHeight() * 0.45f));
                    return true;
                }
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL: {
                    float dy = e.getRawY() - downY;
                    vt.computeCurrentVelocity(1000);
                    float vy = vt.getYVelocity();
                    vt.recycle();
                    vt = null;
                    if (dy < -root.getHeight() * 0.16f || vy < -1200) unlock(null);
                    else resetPosition();
                    return true;
                }
            }
            return true;
        }
    }
}
