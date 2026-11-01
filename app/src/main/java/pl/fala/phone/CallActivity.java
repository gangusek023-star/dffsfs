package pl.fala.phone;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.KeyguardManager;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Parcelable;
import android.os.PowerManager;
import android.telecom.Call;
import android.telecom.CallAudioState;
import android.telecom.PhoneAccount;
import android.telecom.PhoneAccountHandle;
import android.telecom.TelecomManager;
import android.telecom.VideoProfile;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.view.animation.DecelerateInterpolator;
import android.widget.FrameLayout;
import android.widget.GridLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

/** Ekran rozmowy: przychodzące, łączenie, w trakcie, wybór karty SIM. */
public class CallActivity extends Activity implements CallService.Listener {

    private final Handler main = new Handler(Looper.getMainLooper());
    private FrameLayout root;
    private ImageView avatar;
    private TextView name, state, number;
    private LinearLayout incomingBox, activeBox;
    private FrameLayout keypad;
    private TextView keypadDigits;
    private Toggle muteBtn, speakerBtn, holdBtn, keypadBtn;
    private String shownNumber = null;
    private PowerManager.WakeLock proximity;
    private boolean simAsked = false;
    private CallAudioState audio;

    private final Runnable timer = new Runnable() {
        @Override
        public void run() {
            refreshState();
            main.postDelayed(this, 1000);
        }
    };

    private int dp(float v) {
        return Ph.dp(this, v);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (Build.VERSION.SDK_INT >= 27) {
            setShowWhenLocked(true);
            setTurnScreenOn(true);
            KeyguardManager km = getSystemService(KeyguardManager.class);
            if (km != null) km.requestDismissKeyguard(this, null);
        } else {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED | WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON);
        }
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        getWindow().setStatusBarColor(Color.TRANSPARENT);
        getWindow().setNavigationBarColor(0xFF06060A);
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LAYOUT_STABLE | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN);
        build();
        setContentView(root);
        try {
            PowerManager pm = getSystemService(PowerManager.class);
            if (pm.isWakeLockLevelSupported(PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK)) {
                proximity = pm.newWakeLock(PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK, "fala:proximity");
            }
        } catch (Exception ignored) {
        }
        handleAction(getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        handleAction(intent);
    }

    private void handleAction(Intent i) {
        if (i != null && ActionReceiver.ANSWER.equals(i.getAction())) {
            Call c = CallService.primary();
            if (c != null && c.getState() == Call.STATE_RINGING) c.answer(VideoProfile.STATE_AUDIO_ONLY);
        }
    }

    @Override
    protected void onStart() {
        super.onStart();
        CallService.listeners.add(this);
        if (CallService.instance != null) audio = CallService.instance.getCallAudioState();
        onCallsChanged();
        main.post(timer);
    }

    @Override
    protected void onStop() {
        super.onStop();
        CallService.listeners.remove(this);
        main.removeCallbacks(timer);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        releaseProximity();
    }

    @Override
    public void onBackPressed() {
        if (keypad.getVisibility() == View.VISIBLE) {
            keypad.setVisibility(View.GONE);
            return;
        }
        moveTaskToBack(true);
    }

    // ---------- interfejs ----------

    private void build() {
        root = new FrameLayout(this);
        root.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{0xFF1E2B4A, 0xFF101426, 0xFF06060A}));

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setGravity(Gravity.CENTER_HORIZONTAL);
        col.setPadding(dp(24), dp(80), dp(24), dp(40));
        root.addView(col, new FrameLayout.LayoutParams(-1, -1));

        avatar = new ImageView(this);
        avatar.setElevation(dp(10));
        col.addView(avatar, new LinearLayout.LayoutParams(dp(120), dp(120)));
        name = Ph.tv(this, "", 32, Color.WHITE, false);
        name.setGravity(Gravity.CENTER);
        name.setPadding(0, dp(20), 0, dp(4));
        name.setMaxLines(2);
        col.addView(name, new LinearLayout.LayoutParams(-1, -2));
        number = Ph.tv(this, "", 16, Ph.TEXT2, false);
        number.setGravity(Gravity.CENTER);
        col.addView(number, new LinearLayout.LayoutParams(-1, -2));
        state = Ph.tv(this, "", 17, 0xCCFFFFFF, true);
        state.setGravity(Gravity.CENTER);
        state.setPadding(0, dp(10), 0, 0);
        col.addView(state, new LinearLayout.LayoutParams(-1, -2));

        col.addView(new View(this), new LinearLayout.LayoutParams(1, 0, 1f));

        // --- przychodzące ---
        incomingBox = new LinearLayout(this);
        incomingBox.setOrientation(LinearLayout.VERTICAL);
        incomingBox.setGravity(Gravity.CENTER_HORIZONTAL);
        TextView msg = Ph.tv(this, "💬  Odpisz SMS-em", 15, Color.WHITE, true);
        msg.setPadding(dp(18), dp(10), dp(18), dp(10));
        msg.setBackground(Ph.round(0x26FFFFFF, dp(20)));
        msg.setOnClickListener(v -> quickReply());
        incomingBox.addView(msg);
        LinearLayout two = new LinearLayout(this);
        two.setPadding(0, dp(34), 0, 0);
        two.addView(bigButton("Odrzuć", Ph.RED, R.drawable.ic_call_end, v -> {
            Call c = CallService.primary();
            if (c != null) c.reject(false, null);
        }), new LinearLayout.LayoutParams(0, -2, 1f));
        two.addView(bigButton("Odbierz", Ph.GREEN, R.drawable.ic_phone, v -> {
            Call c = CallService.primary();
            if (c != null) c.answer(VideoProfile.STATE_AUDIO_ONLY);
        }), new LinearLayout.LayoutParams(0, -2, 1f));
        incomingBox.addView(two, new LinearLayout.LayoutParams(-1, -2));
        col.addView(incomingBox, new LinearLayout.LayoutParams(-1, -2));

        // --- w trakcie ---
        activeBox = new LinearLayout(this);
        activeBox.setOrientation(LinearLayout.VERTICAL);
        activeBox.setGravity(Gravity.CENTER_HORIZONTAL);
        GridLayout grid = new GridLayout(this);
        grid.setColumnCount(3);
        muteBtn = new Toggle("Wycisz", R.drawable.ic_mic_off, v -> {
            if (CallService.instance != null) CallService.instance.setMuted(!muteBtn.on);
        });
        keypadBtn = new Toggle("Klawiatura", R.drawable.ic_dialpad, v -> showKeypad());
        speakerBtn = new Toggle("Głośnik", R.drawable.ic_speaker, v -> toggleSpeaker());
        holdBtn = new Toggle("Wstrzymaj", R.drawable.ic_pause, v -> {
            Call c = CallService.primary();
            if (c == null) return;
            if (c.getState() == Call.STATE_HOLDING) c.unhold();
            else c.hold();
        });
        Toggle sms = new Toggle("Wiadomość", R.drawable.ic_message, v -> Ph.sms(this, shownNumber));
        Toggle contacts = new Toggle("Kontakty", R.drawable.ic_person, v ->
                startActivity(new Intent(this, MainActivity.class).putExtra("tab", 2)));
        for (Toggle t : new Toggle[]{muteBtn, keypadBtn, speakerBtn, holdBtn, sms, contacts}) {
            GridLayout.LayoutParams lp = new GridLayout.LayoutParams(GridLayout.spec(GridLayout.UNDEFINED, 1f), GridLayout.spec(GridLayout.UNDEFINED, 1f));
            lp.width = 0;
            lp.setMargins(dp(6), dp(10), dp(6), dp(10));
            grid.addView(t.view, lp);
        }
        activeBox.addView(grid, new LinearLayout.LayoutParams(-1, -2));
        View end = bigButton("", Ph.RED, R.drawable.ic_call_end, v -> {
            Call c = CallService.primary();
            if (c != null) c.disconnect();
            else finish();
        });
        LinearLayout.LayoutParams elp = new LinearLayout.LayoutParams(-2, -2);
        elp.topMargin = dp(26);
        activeBox.addView(end, elp);
        col.addView(activeBox, new LinearLayout.LayoutParams(-1, -2));

        // --- klawiatura w trakcie rozmowy ---
        keypad = new FrameLayout(this);
        keypad.setBackgroundColor(0xF2070A14);
        keypad.setVisibility(View.GONE);
        LinearLayout kc = new LinearLayout(this);
        kc.setOrientation(LinearLayout.VERTICAL);
        kc.setGravity(Gravity.CENTER_HORIZONTAL);
        kc.setPadding(dp(30), dp(90), dp(30), dp(40));
        keypadDigits = Ph.tv(this, "", 34, Color.WHITE, false);
        keypadDigits.setGravity(Gravity.CENTER);
        keypadDigits.setSingleLine(true);
        kc.addView(keypadDigits, new LinearLayout.LayoutParams(-1, dp(60)));
        kc.addView(Dialpad.build(this, ch -> {
            Call c = CallService.primary();
            if (c == null) return;
            c.playDtmfTone(ch);
            main.postDelayed(c::stopDtmfTone, 160);
            keypadDigits.append(String.valueOf(ch));
        }, null), new LinearLayout.LayoutParams(-1, -2));
        TextView hide = Ph.tv(this, "Ukryj", 17, Color.WHITE, true);
        hide.setPadding(dp(20), dp(26), dp(20), dp(10));
        hide.setOnClickListener(v -> keypad.setVisibility(View.GONE));
        kc.addView(hide);
        keypad.addView(kc, new FrameLayout.LayoutParams(-1, -1));
        root.addView(keypad, new FrameLayout.LayoutParams(-1, -1));
    }

    private View bigButton(String label, int color, int icon, View.OnClickListener l) {
        LinearLayout b = new LinearLayout(this);
        b.setOrientation(LinearLayout.VERTICAL);
        b.setGravity(Gravity.CENTER_HORIZONTAL);
        ImageView iv = new ImageView(this);
        iv.setImageResource(icon);
        iv.setScaleType(ImageView.ScaleType.CENTER);
        iv.setBackground(Ph.circle(color));
        iv.setElevation(dp(6));
        iv.setOnClickListener(v -> {
            v.animate().scaleX(0.9f).scaleY(0.9f).setDuration(80).withEndAction(() ->
                    v.animate().scaleX(1f).scaleY(1f).setDuration(120).start()).start();
            l.onClick(v);
        });
        iv.setScaleX(1.25f);
        iv.setScaleY(1.25f);
        b.addView(iv, new LinearLayout.LayoutParams(dp(72), dp(72)));
        if (!label.isEmpty()) {
            TextView t = Ph.tv(this, label, 14, Color.WHITE, true);
            t.setPadding(0, dp(14), 0, 0);
            b.addView(t);
        }
        return b;
    }

    /** Okrągły przycisk-przełącznik (wycisz, głośnik…). */
    private class Toggle {
        final LinearLayout view;
        final ImageView icon;
        boolean on = false;

        Toggle(String label, int res, View.OnClickListener l) {
            view = new LinearLayout(CallActivity.this);
            view.setOrientation(LinearLayout.VERTICAL);
            view.setGravity(Gravity.CENTER_HORIZONTAL);
            icon = new ImageView(CallActivity.this);
            icon.setImageResource(res);
            icon.setScaleType(ImageView.ScaleType.CENTER);
            icon.setOnClickListener(l);
            view.addView(icon, new LinearLayout.LayoutParams(dp(68), dp(68)));
            TextView t = Ph.tv(CallActivity.this, label, 13, 0xCCFFFFFF, false);
            t.setPadding(0, dp(8), 0, 0);
            view.addView(t);
            set(false);
        }

        void set(boolean v) {
            on = v;
            icon.setBackground(Ph.circle(v ? Color.WHITE : 0x2EFFFFFF));
            icon.setColorFilter(v ? 0xFF101426 : Color.WHITE);
        }
    }

    private void showKeypad() {
        keypadDigits.setText("");
        keypad.setVisibility(View.VISIBLE);
        keypad.setAlpha(0f);
        keypad.animate().alpha(1f).setDuration(180).start();
    }

    private void toggleSpeaker() {
        if (CallService.instance == null) return;
        CallAudioState a = CallService.instance.getCallAudioState();
        int route;
        if (a != null && a.getRoute() == CallAudioState.ROUTE_SPEAKER) {
            int sup = a.getSupportedRouteMask();
            if ((sup & CallAudioState.ROUTE_WIRED_HEADSET) != 0) route = CallAudioState.ROUTE_WIRED_HEADSET;
            else if ((sup & CallAudioState.ROUTE_BLUETOOTH) != 0) route = CallAudioState.ROUTE_BLUETOOTH;
            else route = CallAudioState.ROUTE_EARPIECE;
        } else route = CallAudioState.ROUTE_SPEAKER;
        CallService.instance.setAudioRoute(route);
    }

    private void quickReply() {
        String[] msgs = {"Nie mogę teraz rozmawiać.", "Oddzwonię później.", "Jestem w drodze.", "Napisz, proszę, SMS-a."};
        new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle("Odrzuć i wyślij")
                .setItems(msgs, (d, w) -> {
                    Call c = CallService.primary();
                    if (c != null) c.reject(true, msgs[w]);
                }).show();
    }

    // ---------- stan połączenia ----------

    @Override
    public void onCallsChanged() {
        main.post(this::refreshState);
    }

    @Override
    public void onAudioChanged(CallAudioState s) {
        audio = s;
        main.post(() -> {
            muteBtn.set(s.isMuted());
            speakerBtn.set(s.getRoute() == CallAudioState.ROUTE_SPEAKER);
            updateProximity();
        });
    }

    private void refreshState() {
        Call c = CallService.primary();
        if (c == null) {
            releaseProximity();
            if (!isFinishing()) {
                state.setText("Rozłączono");
                main.postDelayed(this::finishAndRemoveTask, 900);
            }
            return;
        }
        String num = CallService.numberOf(c);
        if (!num.equals(shownNumber)) {
            shownNumber = num;
            Ph.Contact k = Ph.lookup(this, num);
            name.setText(k.name);
            number.setText(k.name.equals(num) ? "" : num);
            Bitmap b = Ph.avatar(this, k.name, k.photo, dp(120));
            avatar.setImageBitmap(b);
            avatar.setScaleX(0.8f);
            avatar.setScaleY(0.8f);
            avatar.animate().scaleX(1f).scaleY(1f).setDuration(350).setInterpolator(new DecelerateInterpolator()).start();
        }
        int s = c.getState();
        boolean ringing = s == Call.STATE_RINGING;
        incomingBox.setVisibility(ringing ? View.VISIBLE : View.GONE);
        activeBox.setVisibility(ringing ? View.GONE : View.VISIBLE);
        holdBtn.set(s == Call.STATE_HOLDING);
        switch (s) {
            case Call.STATE_RINGING: state.setText("Połączenie przychodzące"); break;
            case Call.STATE_DIALING:
            case Call.STATE_CONNECTING: state.setText("Dzwonię…"); break;
            case Call.STATE_HOLDING: state.setText("Wstrzymane"); break;
            case Call.STATE_DISCONNECTING:
            case Call.STATE_DISCONNECTED: state.setText("Rozłączono"); break;
            case Call.STATE_SELECT_PHONE_ACCOUNT: state.setText("Wybierz kartę SIM"); chooseSim(c); break;
            case Call.STATE_ACTIVE: {
                long since = c.getDetails().getConnectTimeMillis();
                state.setText(since > 0 ? Ph.duration((System.currentTimeMillis() - since) / 1000) : "Połączono");
                break;
            }
            default: state.setText("");
        }
        if (audio != null) {
            muteBtn.set(audio.isMuted());
            speakerBtn.set(audio.getRoute() == CallAudioState.ROUTE_SPEAKER);
        }
        updateProximity();
    }

    /** Dwie karty SIM bez domyślnej: system pyta aplikację Telefon, z której zadzwonić. */
    private void chooseSim(Call c) {
        if (simAsked) return;
        simAsked = true;
        Bundle ex = c.getDetails().getIntentExtras();
        List<PhoneAccountHandle> handles = new ArrayList<>();
        if (ex != null) {
            ArrayList<Parcelable> list = ex.getParcelableArrayList(Call.AVAILABLE_PHONE_ACCOUNTS);
            if (list != null) for (Parcelable p : list) if (p instanceof PhoneAccountHandle) handles.add((PhoneAccountHandle) p);
        }
        if (handles.isEmpty() && checkSelfPermission(Manifest.permission.READ_PHONE_STATE) == PackageManager.PERMISSION_GRANTED) {
            try {
                handles.addAll(getSystemService(TelecomManager.class).getCallCapablePhoneAccounts());
            } catch (Exception ignored) {
            }
        }
        if (handles.isEmpty()) {
            c.disconnect();
            return;
        }
        String[] labels = new String[handles.size()];
        for (int i = 0; i < handles.size(); i++) {
            String l = "SIM " + (i + 1);
            try {
                PhoneAccount a = getSystemService(TelecomManager.class).getPhoneAccount(handles.get(i));
                if (a != null && a.getLabel() != null) l = "SIM " + (i + 1) + " · " + a.getLabel();
            } catch (Exception ignored) {
            }
            labels[i] = l;
        }
        new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle("Zadzwoń z karty")
                .setItems(labels, (d, w) -> c.phoneAccountSelected(handles.get(w), false))
                .setOnCancelListener(d -> c.disconnect())
                .show();
    }

    private void updateProximity() {
        if (proximity == null) return;
        Call c = CallService.primary();
        boolean speaker = audio != null && audio.getRoute() != CallAudioState.ROUTE_EARPIECE;
        boolean want = c != null && (c.getState() == Call.STATE_ACTIVE || c.getState() == Call.STATE_DIALING) && !speaker
                && keypad.getVisibility() != View.VISIBLE;
        if (want && !proximity.isHeld()) proximity.acquire(3 * 60 * 60 * 1000L);
        else if (!want) releaseProximity();
    }

    private void releaseProximity() {
        if (proximity != null && proximity.isHeld()) {
            try {
                proximity.release();
            } catch (Exception ignored) {
            }
        }
    }
}
