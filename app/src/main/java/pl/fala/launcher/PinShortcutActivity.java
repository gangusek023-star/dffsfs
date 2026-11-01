package pl.fala.launcher;

import android.app.Activity;
import android.content.Context;
import android.content.pm.LauncherApps;
import android.content.pm.ShortcutInfo;
import android.os.Bundle;
import android.widget.Toast;

/** Przypinanie skrótów przez inne aplikacje (np. „Dodaj do ekranu głównego” w Chrome). */
public class PinShortcutActivity extends Activity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        try {
            LauncherApps la = (LauncherApps) getSystemService(Context.LAUNCHER_APPS_SERVICE);
            LauncherApps.PinItemRequest req = la.getPinItemRequest(getIntent());
            if (req != null && req.getRequestType() == LauncherApps.PinItemRequest.REQUEST_TYPE_SHORTCUT && req.isValid()) {
                ShortcutInfo si = req.getShortcutInfo();
                if (si != null && req.accept()) {
                    HomeModel m = new HomeModel(getSharedPreferences("fala", MODE_PRIVATE));
                    m.add(HomeModel.APPSC, si.getPackage() + "|" + si.getId() + "|" + si.getUserHandle().hashCode(), null, 1, 1, 0);
                    m.save();
                    CharSequence l = si.getShortLabel();
                    Toast.makeText(this, "Dodano „" + (l == null ? "skrót" : l) + "” na ekran główny", Toast.LENGTH_SHORT).show();
                }
            }
        } catch (Exception ignored) {
        }
        finish();
    }
}
