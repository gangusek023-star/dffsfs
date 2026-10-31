package pl.fala.launcher;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.LauncherActivityInfo;
import android.content.pm.LauncherApps;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraManager;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.UserHandle;
import android.os.UserManager;
import android.provider.AlarmClock;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import android.text.InputType;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.animation.DecelerateInterpolator;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Asystent głosowy "Fala" – świecąca kula jak Siri, poświata na krawędziach,
 * rozpoznawanie mowy, proste komendy w telefonie i odpowiedzi od Claude.
 */
public class AssistantActivity extends Activity {

    static final String MODEL = "claude-haiku-5-5";
    private static final int REQ_MIC = 7;
    private static final Locale PL = new Locale("pl", "PL");

    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService net = Executors.newSingleThreadExecutor();
    private final List<JSONObject> history = new ArrayList<>();

    private SharedPreferences prefs;
    private FrameLayout root;
    private View scrim;
    private EdgeGlowView edge;
    private OrbView orb;
    private TextView status;
    private TextView heard;
    private TextView answer;
    private LinearLayout answerCard;
    private EditText input;

    private SpeechRecognizer recognizer;
    private TextToSpeech tts;
    private boolean ttsReady = false;
    private boolean listening = false;
    private boolean speaking = false;
    private Runnable speakPulse;

    private int dp(float v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = getSharedPreferences("fala", MODE_PRIVATE);
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                        | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
        buildUi();
        setContentView(root);

        tts = new TextToSpeech(this, st -> {
            if (st == TextToSpeech.SUCCESS) {
                tts.setLanguage(PL);
                ttsReady = true;
            }
        });
        tts.setOnUtteranceProgressListener(new UtteranceProgressListener() {
            @Override public void onStart(String id) { main.post(() -> setSpeaking(true)); }
            @Override public void onDone(String id) { main.post(() -> setSpeaking(false)); }
            @Override public void onError(String id) { main.post(() -> setSpeaking(false)); }
        });

        scrim.setAlpha(0f);
        scrim.animate().alpha(1f).setDuration(300).start();
        orb.setScaleX(0.3f);
        orb.setScaleY(0.3f);
        orb.setAlpha(0f);
        orb.animate().scaleX(1f).scaleY(1f).alpha(1f).setDuration(450)
                .setInterpolator(new android.view.animation.OvershootInterpolator(1.4f)).start();
        edge.show(true);

        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            main.postDelayed(this::startListening, 250);
        } else {
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, REQ_MIC);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        if (requestCode == REQ_MIC) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                startListening();
            } else {
                status.setText("Bez mikrofonu – napisz pytanie poniżej");
            }
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (recognizer != null) recognizer.destroy();
        if (tts != null) tts.shutdown();
        net.shutdownNow();
    }

    @Override
    public void onBackPressed() {
        close();
    }

    private void close() {
        if (recognizer != null) recognizer.cancel();
        if (tts != null) tts.stop();
        edge.show(false);
        orb.animate().scaleX(0.3f).scaleY(0.3f).alpha(0f).setDuration(220).start();
        root.animate().alpha(0f).setDuration(260).withEndAction(() -> {
            finish();
            overridePendingTransition(0, 0);
        }).start();
    }

    // ---------- UI ----------

    private void buildUi() {
        root = new FrameLayout(this);
        scrim = new View(this);
        scrim.setBackground(new android.graphics.drawable.GradientDrawable(
                android.graphics.drawable.GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{0x66000000, 0x99000000, 0xE6000000}));
        scrim.setOnClickListener(v -> close());
        root.addView(scrim, new FrameLayout.LayoutParams(-1, -1));

        edge = new EdgeGlowView(this);
        root.addView(edge, new FrameLayout.LayoutParams(-1, -1));

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setGravity(Gravity.CENTER_HORIZONTAL);
        FrameLayout.LayoutParams clp = new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM);
        root.addView(col, clp);
        root.setOnApplyWindowInsetsListener((v, ins) -> {
            col.setPadding(dp(20), 0, dp(20), ins.getSystemWindowInsetBottom() + dp(16));
            return ins;
        });

        heard = new TextView(this);
        heard.setTextColor(Color.WHITE);
        heard.setTextSize(TypedValue.COMPLEX_UNIT_SP, 24);
        heard.setTypeface(Typeface.create("sans-serif-light", Typeface.NORMAL));
        heard.setGravity(Gravity.CENTER);
        heard.setPadding(dp(8), 0, dp(8), dp(14));
        col.addView(heard, new LinearLayout.LayoutParams(-1, -2));

        answerCard = new LinearLayout(this);
        answerCard.setOrientation(LinearLayout.VERTICAL);
        answerCard.setBackground(new GlassDrawable(getResources().getDisplayMetrics().density, dp(26), 1.2f));
        answerCard.setPadding(dp(20), dp(16), dp(20), dp(18));
        answerCard.setVisibility(View.GONE);
        answer = new TextView(this);
        answer.setTextColor(Color.WHITE);
        answer.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17);
        answer.setLineSpacing(dp(3), 1f);
        answer.setTextIsSelectable(true);
        android.widget.ScrollView asv = new android.widget.ScrollView(this);
        asv.setVerticalScrollBarEnabled(false);
        asv.addView(answer);
        answerCard.addView(asv, new LinearLayout.LayoutParams(-1, -2));
        LinearLayout.LayoutParams alp = new LinearLayout.LayoutParams(-1, -2);
        alp.bottomMargin = dp(18);
        col.addView(answerCard, alp);
        answerCard.post(() -> asv.getLayoutParams().height = -2);
        answer.setMaxHeight(getResources().getDisplayMetrics().heightPixels / 2);

        orb = new OrbView(this);
        orb.setOnClickListener(v -> {
            if (listening) stopListening();
            else {
                if (tts != null) tts.stop();
                startListening();
            }
        });
        col.addView(orb, new LinearLayout.LayoutParams(dp(150), dp(150)));

        status = new TextView(this);
        status.setTextColor(0xB3FFFFFF);
        status.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        status.setGravity(Gravity.CENTER);
        status.setText("Fala");
        status.setPadding(0, 0, 0, dp(12));
        col.addView(status, new LinearLayout.LayoutParams(-1, -2));

        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setBackground(new GlassDrawable(getResources().getDisplayMetrics().density, dp(24), 1f));
        bar.setPadding(dp(18), 0, dp(6), 0);
        input = new EditText(this);
        input.setBackground(null);
        input.setHint("Napisz do Fali…");
        input.setHintTextColor(0x99FFFFFF);
        input.setTextColor(Color.WHITE);
        input.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        input.setSingleLine(true);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        input.setImeOptions(EditorInfo.IME_ACTION_SEND);
        input.setOnFocusChangeListener((v, f) -> { if (f && listening) stopListening(); });
        input.setOnEditorActionListener((v, id, ev) -> {
            boolean go = id == EditorInfo.IME_ACTION_SEND
                    || (ev != null && ev.getKeyCode() == KeyEvent.KEYCODE_ENTER && ev.getAction() == KeyEvent.ACTION_DOWN);
            if (go) sendTyped();
            return go;
        });
        bar.addView(input, new LinearLayout.LayoutParams(0, dp(48), 1f));
        ImageView send = new ImageView(this);
        send.setImageResource(R.drawable.ic_send);
        send.setScaleType(ImageView.ScaleType.CENTER);
        send.setColorFilter(Color.WHITE);
        send.setOnClickListener(v -> sendTyped());
        bar.addView(send, new LinearLayout.LayoutParams(dp(44), dp(44)));
        col.addView(bar, new LinearLayout.LayoutParams(-1, dp(50)));
    }

    private void sendTyped() {
        String t = input.getText().toString().trim();
        if (t.isEmpty()) return;
        input.setText("");
        InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
        imm.hideSoftInputFromWindow(input.getWindowToken(), 0);
        handle(t);
    }

    private void setSpeaking(boolean on) {
        speaking = on;
        if (speakPulse != null) main.removeCallbacks(speakPulse);
        if (on) {
            speakPulse = new Runnable() {
                double t = 0;

                @Override
                public void run() {
                    t += 0.12;
                    float l = (float) (0.35 + 0.3 * Math.abs(Math.sin(t * 2.3)) + 0.15 * Math.sin(t * 5.1));
                    orb.setLevel(l);
                    edge.setLevel(l * 0.6f);
                    if (speaking) main.postDelayed(this, 40);
                }
            };
            main.post(speakPulse);
        } else {
            orb.setLevel(0f);
            edge.setLevel(0f);
        }
    }

    // ---------- mowa ----------

    private void startListening() {
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            status.setText("Rozpoznawanie mowy niedostępne – napisz pytanie");
            return;
        }
        if (recognizer == null) {
            recognizer = SpeechRecognizer.createSpeechRecognizer(this);
            recognizer.setRecognitionListener(new RecognitionListener() {
                @Override public void onReadyForSpeech(Bundle b) { status.setText("Słucham…"); }
                @Override public void onBeginningOfSpeech() { }

                @Override
                public void onRmsChanged(float rms) {
                    float l = Math.max(0f, Math.min(1f, (rms + 2f) / 12f));
                    orb.setLevel(l);
                    edge.setLevel(l);
                }

                @Override public void onBufferReceived(byte[] b) { }
                @Override public void onEndOfSpeech() { status.setText("Chwileczkę…"); }

                @Override
                public void onError(int error) {
                    listening = false;
                    orb.setLevel(0f);
                    edge.setLevel(0f);
                    if (error == SpeechRecognizer.ERROR_NO_MATCH || error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT) {
                        status.setText("Nic nie usłyszałam – dotknij kuli");
                    } else if (error == SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS) {
                        status.setText("Brak dostępu do mikrofonu");
                    } else if (error == SpeechRecognizer.ERROR_NETWORK || error == SpeechRecognizer.ERROR_NETWORK_TIMEOUT) {
                        status.setText("Brak internetu – napisz pytanie");
                    } else {
                        status.setText("Dotknij kuli, aby mówić");
                    }
                }

                @Override
                public void onResults(Bundle results) {
                    listening = false;
                    orb.setLevel(0f);
                    edge.setLevel(0f);
                    ArrayList<String> r = results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
                    if (r != null && !r.isEmpty()) handle(r.get(0));
                    else status.setText("Dotknij kuli, aby mówić");
                }

                @Override
                public void onPartialResults(Bundle partial) {
                    ArrayList<String> r = partial.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
                    if (r != null && !r.isEmpty() && !r.get(0).isEmpty()) heard.setText(r.get(0));
                }

                @Override public void onEvent(int t, Bundle b) { }
            });
        }
        Intent i = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        i.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        i.putExtra(RecognizerIntent.EXTRA_LANGUAGE, "pl-PL");
        i.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true);
        i.putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, getPackageName());
        try {
            recognizer.startListening(i);
            listening = true;
            heard.setText("");
            status.setText("Słucham…");
            root.performHapticFeedback(android.view.HapticFeedbackConstants.CONTEXT_CLICK);
        } catch (Exception e) {
            status.setText("Nie mogę włączyć mikrofonu");
        }
    }

    private void stopListening() {
        listening = false;
        if (recognizer != null) recognizer.stopListening();
    }

    // ---------- logika ----------

    private static String norm(String s) {
        String n = Normalizer.normalize(s, Normalizer.Form.NFD).replaceAll("\\p{Mn}+", "");
        return n.replace('ł', 'l').replace('Ł', 'L').toLowerCase(Locale.ROOT).trim();
    }

    private void handle(String text) {
        heard.setText("„" + text + "”");
        String n = norm(text);
        String local = localCommand(n);
        if (local != null) {
            reply(local);
            return;
        }
        askClaude(text);
    }

    /** Komendy wykonywane w telefonie, bez internetu. Zwraca odpowiedź lub null. */
    private String localCommand(String n) {
        // latarka
        if (n.contains("latark")) {
            boolean off = n.contains("wylacz") || n.contains("zgas");
            boolean on = !off && (n.contains("wlacz") || n.contains("zapal"));
            Boolean res = setTorch(off ? Boolean.FALSE : (on ? Boolean.TRUE : null));
            if (res == null) return "Nie znalazłam latarki w tym telefonie.";
            return res ? "Włączyłam latarkę." : "Wyłączyłam latarkę.";
        }
        // godzina / data / bateria
        if (n.contains("ktora godzina") || n.equals("godzina") || n.contains("jaka jest godzina")) {
            return "Jest " + android.text.format.DateFormat.getTimeFormat(this).format(new Date()) + ".";
        }
        if (n.contains("jaki dzis") || n.contains("jaka jest data") || n.contains("ktorego jest") || n.equals("data")) {
            return "Dziś jest " + new SimpleDateFormat("EEEE, d MMMM yyyy", PL).format(new Date()) + ".";
        }
        if (n.contains("bateri")) {
            Intent b = registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
            if (b != null) {
                int lvl = b.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) * 100 / Math.max(1, b.getIntExtra(BatteryManager.EXTRA_SCALE, 100));
                return "Bateria jest naładowana w " + lvl + " procentach.";
            }
        }
        // minutnik
        Matcher mt = Pattern.compile("(minutnik|timer|odlicz).*?(\\d+)\\s*(sekund|minut|godzin)").matcher(n);
        if (mt.find()) {
            int v = Integer.parseInt(mt.group(2));
            String unit = mt.group(3);
            int secs = unit.startsWith("sekund") ? v : unit.startsWith("minut") ? v * 60 : v * 3600;
            Intent i = new Intent(AlarmClock.ACTION_SET_TIMER);
            i.putExtra(AlarmClock.EXTRA_LENGTH, secs);
            i.putExtra(AlarmClock.EXTRA_SKIP_UI, true);
            i.putExtra(AlarmClock.EXTRA_MESSAGE, "Fala");
            if (start(i)) return "Ustawiłam minutnik na " + v + " " + unit + (unit.endsWith("y") ? "" : "") + ".";
            return "Nie udało się ustawić minutnika.";
        }
        // budzik
        Matcher ma = Pattern.compile("budzik.*?(\\d{1,2})(?:[:. ](\\d{2}))?").matcher(n);
        if (ma.find()) {
            int h = Integer.parseInt(ma.group(1));
            int m = ma.group(2) != null ? Integer.parseInt(ma.group(2)) : 0;
            if (h < 24 && m < 60) {
                Intent i = new Intent(AlarmClock.ACTION_SET_ALARM);
                i.putExtra(AlarmClock.EXTRA_HOUR, h);
                i.putExtra(AlarmClock.EXTRA_MINUTES, m);
                i.putExtra(AlarmClock.EXTRA_SKIP_UI, true);
                i.putExtra(AlarmClock.EXTRA_MESSAGE, "Fala");
                if (start(i)) return String.format(PL, "Budzik ustawiony na %d:%02d.", h, m);
                return "Nie udało się ustawić budzika.";
            }
        }
        // otwieranie aplikacji
        Matcher mo = Pattern.compile("^(otworz|uruchom|wlacz|odpal|pokaz)\\s+(aplikacje\\s+)?(.+)$").matcher(n);
        if (mo.find()) {
            String name = mo.group(3).trim();
            AppHit hit = findApp(name);
            if (hit != null) {
                LauncherApps la = (LauncherApps) getSystemService(Context.LAUNCHER_APPS_SERVICE);
                try {
                    la.startMainActivity(hit.info.getComponentName(), hit.user, null, null);
                    main.postDelayed(this::close, 300);
                    return "Otwieram " + hit.info.getLabel() + ".";
                } catch (Exception ignored) {
                }
            }
        }
        return null;
    }

    private boolean start(Intent i) {
        try {
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(i);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    static class AppHit {
        LauncherActivityInfo info;
        UserHandle user;
    }

    private AppHit findApp(String name) {
        LauncherApps la = (LauncherApps) getSystemService(Context.LAUNCHER_APPS_SERVICE);
        UserManager um = (UserManager) getSystemService(Context.USER_SERVICE);
        AppHit best = null;
        int bestScore = 0;
        for (UserHandle u : um.getUserProfiles()) {
            for (LauncherActivityInfo i : la.getActivityList(null, u)) {
                String l = norm(i.getLabel().toString());
                int score = l.equals(name) ? 3 : l.startsWith(name) ? 2 : (l.contains(name) || name.contains(l)) ? 1 : 0;
                if (score > bestScore) {
                    bestScore = score;
                    best = new AppHit();
                    best.info = i;
                    best.user = u;
                }
            }
        }
        return best;
    }

    private Boolean setTorch(Boolean on) {
        try {
            CameraManager cm = (CameraManager) getSystemService(Context.CAMERA_SERVICE);
            for (String id : cm.getCameraIdList()) {
                Boolean f = cm.getCameraCharacteristics(id).get(CameraCharacteristics.FLASH_INFO_AVAILABLE);
                if (f != null && f) {
                    boolean target = on != null ? on : !prefs.getBoolean("torch_last", false);
                    cm.setTorchMode(id, target);
                    prefs.edit().putBoolean("torch_last", target).apply();
                    return target;
                }
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    private void reply(String text) {
        answerCard.setVisibility(View.VISIBLE);
        answerCard.setAlpha(0f);
        answerCard.setTranslationY(dp(16));
        answerCard.animate().alpha(1f).translationY(0).setDuration(320)
                .setInterpolator(new DecelerateInterpolator(2f)).start();
        typeOut(text);
        status.setText("Dotknij kuli, aby mówić dalej");
        if (ttsReady && prefs.getBoolean("assistant_voice", true)) {
            Bundle b = new Bundle();
            tts.speak(text, TextToSpeech.QUEUE_FLUSH, b, "fala" + System.nanoTime());
        }
    }

    /** Tekst pojawia się słowo po słowie. */
    private void typeOut(String text) {
        final String[] words = text.split(" ");
        final StringBuilder sb = new StringBuilder();
        answer.setText("");
        main.post(new Runnable() {
            int i = 0;

            @Override
            public void run() {
                if (i >= words.length || isFinishing()) return;
                if (sb.length() > 0) sb.append(' ');
                sb.append(words[i++]);
                answer.setText(sb);
                main.postDelayed(this, 28);
            }
        });
    }

    private void askClaude(String text) {
        String key = prefs.getString("api_key", "").trim();
        if (key.isEmpty()) {
            reply("Żebym mogła odpowiadać na każde pytanie, dodaj klucz API Claude: przytrzymaj puste miejsce na ekranie głównym i wybierz „Asystent Fala”. Bez klucza umiem otwierać aplikacje, włączać latarkę, ustawiać budzik i minutnik.");
            return;
        }
        status.setText("Myślę…");
        orb.setThinking(true);
        edge.setLevel(0.4f);
        net.execute(() -> {
            String result;
            try {
                JSONObject user = new JSONObject().put("role", "user").put("content", text);
                JSONArray msgs = new JSONArray();
                synchronized (history) {
                    history.add(user);
                    while (history.size() > 12) history.remove(0);
                    for (JSONObject m : history) msgs.put(m);
                }
                String now = new SimpleDateFormat("EEEE, d MMMM yyyy, HH:mm", PL).format(new Date());
                JSONObject body = new JSONObject()
                        .put("model", MODEL)
                        .put("max_tokens", 500)
                        .put("system", "Jesteś Fala – asystentką głosową w telefonie z Androidem, w stylu Siri. "
                                + "Odpowiadasz po polsku, krótko i naturalnie (zwykle 1–3 zdania), bo odpowiedź jest czytana na głos. "
                                + "Nie używaj markdown, list ani emoji. Teraz jest " + now + ".")
                        .put("messages", msgs);

                HttpURLConnection c = (HttpURLConnection) new URL("https://api.anthropic.com/v1/messages").openConnection();
                c.setRequestMethod("POST");
                c.setConnectTimeout(15000);
                c.setReadTimeout(45000);
                c.setDoOutput(true);
                c.setRequestProperty("content-type", "application/json");
                c.setRequestProperty("x-api-key", key);
                c.setRequestProperty("anthropic-version", "2023-06-01");
                try (OutputStream os = c.getOutputStream()) {
                    os.write(body.toString().getBytes(StandardCharsets.UTF_8));
                }
                int code = c.getResponseCode();
                InputStream is = code >= 200 && code < 300 ? c.getInputStream() : c.getErrorStream();
                StringBuilder sb = new StringBuilder();
                if (is != null) {
                    try (BufferedReader br = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
                        String line;
                        while ((line = br.readLine()) != null) sb.append(line);
                    }
                }
                if (code == 200) {
                    JSONArray content = new JSONObject(sb.toString()).getJSONArray("content");
                    StringBuilder out = new StringBuilder();
                    for (int i = 0; i < content.length(); i++) {
                        JSONObject part = content.getJSONObject(i);
                        if ("text".equals(part.optString("type"))) out.append(part.optString("text"));
                    }
                    result = clean(out.toString());
                    synchronized (history) {
                        history.add(new JSONObject().put("role", "assistant").put("content", result));
                    }
                } else {
                    synchronized (history) {
                        if (!history.isEmpty()) history.remove(history.size() - 1);
                    }
                    if (code == 401) result = "Klucz API jest nieprawidłowy. Sprawdź go w ustawieniach Fali.";
                    else if (code == 429) result = "Za dużo pytań naraz albo skończył się limit. Spróbuj za chwilę.";
                    else if (code == 529 || code >= 500) result = "Serwer jest teraz przeciążony. Spróbuj za chwilę.";
                    else result = "Coś poszło nie tak (błąd " + code + ").";
                }
            } catch (java.net.UnknownHostException e) {
                result = "Nie mam połączenia z internetem.";
            } catch (Exception e) {
                result = "Nie udało się połączyć. Spróbuj jeszcze raz.";
            }
            final String r = result.isEmpty() ? "Hmm, nie wiem, co odpowiedzieć." : result;
            main.post(() -> {
                if (isFinishing()) return;
                orb.setThinking(false);
                edge.setLevel(0f);
                reply(r);
            });
        });
    }

    private static String clean(String s) {
        return s.replace("**", "").replace("__", "").replaceAll("(?m)^#+\\s*", "")
                .replaceAll("(?m)^\\s*[-*•]\\s+", "").trim();
    }
}
