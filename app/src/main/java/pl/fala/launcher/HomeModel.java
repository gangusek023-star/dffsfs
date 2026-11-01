package pl.fala.launcher;

import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Układ ekranu głównego: strony z siatką 4×6. Elementy: aplikacja, folder,
 * widżet zdjęcia, zegar, skrót (Skróty) i skrót aplikacji Androida.
 */
public class HomeModel {

    static final int COLS = 4, ROWS = 6;

    static final String APP = "app", FOLDER = "folder", PHOTO = "photo", CLOCK = "clock", SHORTCUT = "shortcut", APPSC = "appsc";

    static class Item {
        String id = UUID.randomUUID().toString().substring(0, 8);
        String type;
        String key;          // aplikacja / plik zdjęcia / id skrótu / "pakiet|id|użytkownik"
        String name;         // podpis (folder, widżet)
        List<String> apps = new ArrayList<>(); // folder
        int page, x, y, w = 1, h = 1;

        JSONObject json() throws Exception {
            JSONObject o = new JSONObject();
            o.put("id", id).put("t", type).put("p", page).put("x", x).put("y", y).put("w", w).put("h", h);
            if (key != null) o.put("k", key);
            if (name != null) o.put("n", name);
            if (!apps.isEmpty()) o.put("a", new JSONArray(apps));
            return o;
        }

        static Item from(JSONObject o) {
            Item i = new Item();
            i.id = o.optString("id", i.id);
            i.type = o.optString("t", APP);
            i.key = o.has("k") ? o.optString("k") : null;
            i.name = o.has("n") ? o.optString("n") : null;
            i.page = o.optInt("p");
            i.x = o.optInt("x");
            i.y = o.optInt("y");
            i.w = Math.max(1, o.optInt("w", 1));
            i.h = Math.max(1, o.optInt("h", 1));
            JSONArray a = o.optJSONArray("a");
            if (a != null) for (int k = 0; k < a.length(); k++) i.apps.add(a.optString(k));
            return i;
        }
    }

    final List<Item> items = new ArrayList<>();
    int pages = 1;
    /** Nowa instalacja – domyślne aplikacje dodamy, gdy będą wybrane. */
    boolean fresh = false;
    /** Numer wersji zapisanego układu – przeładowujemy tylko, gdy zmienił go ktoś inny. */
    long rev = 0;
    private final SharedPreferences prefs;

    HomeModel(SharedPreferences prefs) {
        this.prefs = prefs;
        load();
    }

    long savedRev() {
        return prefs.getLong("home2_rev", 0);
    }

    void load() {
        items.clear();
        rev = savedRev();
        String s = prefs.getString("home2", null);
        if (s != null) {
            try {
                JSONObject o = new JSONObject(s);
                pages = Math.max(1, o.optInt("pages", 1));
                JSONArray a = o.getJSONArray("items");
                for (int i = 0; i < a.length(); i++) items.add(Item.from(a.getJSONObject(i)));
                return;
            } catch (Exception ignored) {
            }
        }
        // przeniesienie ze starego układu: zegar na górze, potem dotychczasowe aplikacje
        pages = 1;
        Item clock = new Item();
        clock.type = CLOCK;
        clock.w = 4;
        clock.h = 2;
        items.add(clock);
        String old = prefs.getString("home", "");
        if (old.isEmpty()) {
            fresh = true;
            return;
        }
        for (String k : old.split("\n")) if (!k.isEmpty()) addApp(k);
        save();
    }

    void save() {
        try {
            JSONArray a = new JSONArray();
            for (Item i : items) a.put(i.json());
            rev = savedRev() + 1;
            prefs.edit().putString("home2", new JSONObject().put("pages", pages).put("items", a).toString())
                    .putLong("home2_rev", rev).apply();
        } catch (Exception ignored) {
        }
    }

    List<Item> onPage(int p) {
        List<Item> out = new ArrayList<>();
        for (Item i : items) if (i.page == p) out.add(i);
        return out;
    }

    boolean[][] occupied(int page, Item ignore) {
        boolean[][] o = new boolean[ROWS][COLS];
        for (Item i : items) {
            if (i.page != page || i == ignore) continue;
            for (int y = i.y; y < Math.min(ROWS, i.y + i.h); y++)
                for (int x = i.x; x < Math.min(COLS, i.x + i.w); x++) o[y][x] = true;
        }
        return o;
    }

    boolean fits(int page, int x, int y, int w, int h, Item ignore) {
        if (x < 0 || y < 0 || x + w > COLS || y + h > ROWS) return false;
        boolean[][] o = occupied(page, ignore);
        for (int yy = y; yy < y + h; yy++) for (int xx = x; xx < x + w; xx++) if (o[yy][xx]) return false;
        return true;
    }

    /** Umieszcza element w pierwszym wolnym miejscu (od podanej strony), w razie potrzeby dodaje stronę. */
    void place(Item it, int fromPage) {
        for (int p = fromPage; p < pages + 1; p++) {
            for (int y = 0; y <= ROWS - it.h; y++) {
                for (int x = 0; x <= COLS - it.w; x++) {
                    if (fits(p, x, y, it.w, it.h, it)) {
                        it.page = p;
                        it.x = x;
                        it.y = y;
                        if (p >= pages) pages = p + 1;
                        if (!items.contains(it)) items.add(it);
                        return;
                    }
                }
            }
        }
    }

    Item addApp(String key) {
        Item it = new Item();
        it.type = APP;
        it.key = key;
        place(it, 0);
        return it;
    }

    Item add(String type, String key, String name, int w, int h, int fromPage) {
        Item it = new Item();
        it.type = type;
        it.key = key;
        it.name = name;
        it.w = w;
        it.h = h;
        place(it, fromPage);
        return it;
    }

    boolean hasApp(String key) {
        for (Item i : items) {
            if (APP.equals(i.type) && key.equals(i.key)) return true;
            if (FOLDER.equals(i.type) && i.apps.contains(key)) return true;
        }
        return false;
    }

    void removeApp(String key) {
        List<Item> rm = new ArrayList<>();
        for (Item i : items) {
            if (APP.equals(i.type) && key.equals(i.key)) rm.add(i);
            else if (FOLDER.equals(i.type) && i.apps.remove(key)) {
                if (i.apps.size() == 1) { // folder z jedną aplikacją wraca do zwykłej ikony
                    i.type = APP;
                    i.key = i.apps.get(0);
                    i.apps.clear();
                    i.name = null;
                } else if (i.apps.isEmpty()) rm.add(i);
            }
        }
        items.removeAll(rm);
    }

    Item at(int page, int x, int y, Item ignore) {
        for (Item i : items) {
            if (i == ignore || i.page != page) continue;
            if (x >= i.x && x < i.x + i.w && y >= i.y && y < i.y + i.h) return i;
        }
        return null;
    }

    /** Usuwa puste strony na końcu (zostawia co najmniej jedną). */
    void trimPages() {
        while (pages > 1 && onPage(pages - 1).isEmpty()) pages--;
    }
}
