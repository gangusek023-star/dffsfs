package pl.fala.launcher;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.LauncherApps;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraManager;
import android.media.AudioManager;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.os.UserHandle;
import android.os.UserManager;
import android.provider.AlarmClock;
import android.provider.Settings;
import android.speech.tts.TextToSpeech;
import android.view.KeyEvent;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/** Skróty (jak w iOS): nazwa, kolor, ikona i lista akcji wykonywanych po kolei. */
public final class Shortcuts {

    static final int[] COLORS = {0xFFFF5E5B, 0xFFFF9F43, 0xFFFFC93C, 0xFF2ED47A, 0xFF22C1C3, 0xFF4A7DFF, 0xFF7A5CFF, 0xFFE15BCB, 0xFF6B7280, 0xFF1F2937};

    /** Typy akcji: identyfikator, nazwa, emoji. */
    static final String[][] ACTIONS = {
            {"app", "Otwórz aplikację", "📱"},
            {"url", "Otwórz stronę", "🌐"},
            {"call", "Zadzwoń", "📞"},
            {"sms", "Napisz SMS", "💬"},
            {"torch", "Latarka", "🔦"},
            {"timer", "Minutnik", "⏱"},
            {"alarm", "Budzik", "⏰"},
            {"say", "Powiedz tekst", "🗣"},
            {"toast", "Pokaż komunikat", "💡"},
            {"settings", "Otwórz ustawienia", "⚙️"},
            {"media", "Muzyka: odtwórz/pauza", "⏯"},
            {"next", "Muzyka: następny", "⏭"},
            {"volume", "Ustaw głośność", "🔊"},
            {"wait", "Czekaj", "⏳"},
            {"maps", "Nawiguj do", "🗺"},
            {"assistant", "Zapytaj Falę", "✦"},
    };

    static final String[][] SETTINGS = {
            {Settings.ACTION_WIFI_SETTINGS, "Wi-Fi"},
            {Settings.ACTION_BLUETOOTH_SETTINGS, "Bluetooth"},
            {Settings.ACTION_DISPLAY_SETTINGS, "Wyświetlacz"},
            {Settings.ACTION_SOUND_SETTINGS, "Dźwięk"},
            {Settings.ACTION_BATTERY_SAVER_SETTINGS, "Oszczędzanie baterii"},
            {Settings.ACTION_LOCATION_SOURCE_SETTINGS, "Lokalizacja"},
            {Settings.ACTION_DATA_ROAMING_SETTINGS, "Dane komórkowe"},
            {Settings.ACTION_AIRPLANE_MODE_SETTINGS, "Tryb samolotowy"},
            {Settings.ACTION_SETTINGS, "Wszystkie ustawienia"},
    };

    static class Action {
        String type;
        String a = "";   // główny parametr
        String b = "";   // drugi parametr (np. treść SMS)

        JSONObject json() throws Exception {
            return new JSONObject().put("t", type).put("a", a).put("b", b);
        }
    }

    static class Shortcut {
        String id = UUID.randomUUID().toString().substring(0, 8);
        String name = "Nowy skrót";
        int color = COLORS[5];
        String glyph = "✦";
        final List<Action> actions = new ArrayList<>();
    }

    static String label(String type) {
        for (String[] a : ACTIONS) if (a[0].equals(type)) return a[2] + "  " + a[1];
        return type;
    }

    // ---------- zapis ----------

    static List<Shortcut> load(Context c) {
        List<Shortcut> out = new ArrayList<>();
        SharedPreferences p = c.getSharedPreferences("fala", Context.MODE_PRIVATE);
        try {
            JSONArray arr = new JSONArray(p.getString("shortcuts", "[]"));
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                Shortcut s = new Shortcut();
                s.id = o.optString("id", s.id);
                s.name = o.optString("name", s.name);
                s.color = o.optInt("color", s.color);
                s.glyph = o.optString("glyph", s.glyph);
                JSONArray acts = o.optJSONArray("actions");
                if (acts != null) for (int k = 0; k < acts.length(); k++) {
                    JSONObject ao = acts.getJSONObject(k);
                    Action a = new Action();
                    a.type = ao.optString("t");
                    a.a = ao.optString("a");
                    a.b = ao.optString("b");
                    s.actions.add(a);
                }
                out.add(s);
            }
        } catch (Exception ignored) {
        }
        return out;
    }

    static void save(Context c, List<Shortcut> list) {
        try {
            JSONArray arr = new JSONArray();
            for (Shortcut s : list) {
                JSONArray acts = new JSONArray();
                for (Action a : s.actions) acts.put(a.json());
                arr.put(new JSONObject().put("id", s.id).put("name", s.name).put("color", s.color)
                        .put("glyph", s.glyph).put("actions", acts));
            }
            c.getSharedPreferences("fala", Context.MODE_PRIVATE).edit().putString("shortcuts", arr.toString()).apply();
        } catch (Exception ignored) {
        }
    }

    static Shortcut find(Context c, String id) {
        for (Shortcut s : load(c)) if (s.id.equals(id)) return s;
        return null;
    }

    static java.io.File iconFile(Context c, String id) {
        java.io.File d = new java.io.File(c.getFilesDir(), "shortcuts");
        if (!d.exists()) //noinspection ResultOfMethodCallIgnored
            d.mkdirs();
        return new java.io.File(d, id + ".png");
    }

    // ---------- wykonywanie ----------

    private static TextToSpeech tts;

    static void run(Context c, Shortcut s) {
        if (s.actions.isEmpty()) {
            Toast.makeText(c, "Skrót „" + s.name + "” nie ma akcji", Toast.LENGTH_SHORT).show();
            return;
        }
        Handler h = new Handler(Looper.getMainLooper());
        long delay = 0;
        for (Action a : s.actions) {
            if ("wait".equals(a.type)) {
                delay += Math.max(0, parseInt(a.a, 1)) * 1000L;
                continue;
            }
            final Action act = a;
            h.postDelayed(() -> exec(c, act), delay);
            delay += 350; // akcje otwierające okna nie mogą się nakładać
        }
    }

    private static int parseInt(String s, int def) {
        try {
            return Integer.parseInt(s.trim());
        } catch (Exception e) {
            return def;
        }
    }

    private static void start(Context c, Intent i) {
        try {
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            c.startActivity(i);
        } catch (Exception e) {
            Toast.makeText(c, "Nie można wykonać akcji", Toast.LENGTH_SHORT).show();
        }
    }

    private static void exec(Context c, Action a) {
        switch (a.type) {
            case "app": {
                ComponentName cn = ComponentName.unflattenFromString(a.a.contains("#") ? a.a.substring(0, a.a.indexOf('#')) : a.a);
                if (cn == null) return;
                LauncherApps la = (LauncherApps) c.getSystemService(Context.LAUNCHER_APPS_SERVICE);
                UserManager um = (UserManager) c.getSystemService(Context.USER_SERVICE);
                UserHandle user = android.os.Process.myUserHandle();
                if (a.a.contains("#")) {
                    String h = a.a.substring(a.a.indexOf('#') + 1);
                    for (UserHandle u : um.getUserProfiles()) if (String.valueOf(u.hashCode()).equals(h)) user = u;
                }
                try {
                    la.startMainActivity(cn, user, null, null);
                } catch (Exception e) {
                    Toast.makeText(c, "Brak aplikacji", Toast.LENGTH_SHORT).show();
                }
                break;
            }
            case "url": {
                String u = a.a.trim();
                if (!u.contains("://")) u = "https://" + u;
                start(c, new Intent(Intent.ACTION_VIEW, Uri.parse(u)));
                break;
            }
            case "call":
                start(c, new Intent(Intent.ACTION_DIAL, Uri.parse("tel:" + Uri.encode(a.a))));
                break;
            case "sms": {
                Intent i = new Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:" + Uri.encode(a.a)));
                if (!a.b.isEmpty()) i.putExtra("sms_body", a.b);
                start(c, i);
                break;
            }
            case "torch":
                torch(c, a.a);
                break;
            case "timer": {
                Intent i = new Intent(AlarmClock.ACTION_SET_TIMER)
                        .putExtra(AlarmClock.EXTRA_LENGTH, parseInt(a.a, 5) * 60)
                        .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
                        .putExtra(AlarmClock.EXTRA_MESSAGE, "Skrót");
                start(c, i);
                break;
            }
            case "alarm": {
                String[] hm = a.a.split("[:.]");
                Intent i = new Intent(AlarmClock.ACTION_SET_ALARM)
                        .putExtra(AlarmClock.EXTRA_HOUR, parseInt(hm[0], 7))
                        .putExtra(AlarmClock.EXTRA_MINUTES, hm.length > 1 ? parseInt(hm[1], 0) : 0)
                        .putExtra(AlarmClock.EXTRA_SKIP_UI, true);
                start(c, i);
                break;
            }
            case "say":
                say(c, a.a);
                break;
            case "toast":
                Toast.makeText(c, a.a, Toast.LENGTH_LONG).show();
                break;
            case "settings":
                start(c, new Intent(a.a.isEmpty() ? Settings.ACTION_SETTINGS : a.a));
                break;
            case "media":
                mediaKey(c, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE);
                break;
            case "next":
                mediaKey(c, KeyEvent.KEYCODE_MEDIA_NEXT);
                break;
            case "volume": {
                AudioManager am = (AudioManager) c.getSystemService(Context.AUDIO_SERVICE);
                int max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
                am.setStreamVolume(AudioManager.STREAM_MUSIC, Math.round(max * Math.max(0, Math.min(100, parseInt(a.a, 50))) / 100f),
                        AudioManager.FLAG_SHOW_UI);
                break;
            }
            case "maps":
                start(c, new Intent(Intent.ACTION_VIEW, Uri.parse("google.navigation:q=" + Uri.encode(a.a))));
                break;
            case "assistant":
                start(c, new Intent(c, AssistantActivity.class));
                break;
        }
    }

    private static void mediaKey(Context c, int code) {
        AudioManager am = (AudioManager) c.getSystemService(Context.AUDIO_SERVICE);
        long t = SystemClock.uptimeMillis();
        am.dispatchMediaKeyEvent(new KeyEvent(t, t, KeyEvent.ACTION_DOWN, code, 0));
        am.dispatchMediaKeyEvent(new KeyEvent(t, t, KeyEvent.ACTION_UP, code, 0));
    }

    private static void say(Context c, String text) {
        Context app = c.getApplicationContext();
        if (tts == null) {
            tts = new TextToSpeech(app, st -> {
                if (st == TextToSpeech.SUCCESS) {
                    tts.setLanguage(new Locale("pl", "PL"));
                    tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "sc");
                }
            });
        } else {
            tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "sc");
        }
    }

    private static void torch(Context c, String mode) {
        try {
            CameraManager cm = (CameraManager) c.getSystemService(Context.CAMERA_SERVICE);
            SharedPreferences p = c.getSharedPreferences("fala", Context.MODE_PRIVATE);
            for (String id : cm.getCameraIdList()) {
                Boolean f = cm.getCameraCharacteristics(id).get(CameraCharacteristics.FLASH_INFO_AVAILABLE);
                if (f != null && f) {
                    boolean on = "on".equals(mode) || (!"off".equals(mode) && !p.getBoolean("torch_last", false));
                    cm.setTorchMode(id, on);
                    p.edit().putBoolean("torch_last", on).apply();
                    return;
                }
            }
        } catch (Exception ignored) {
        }
    }
}
