package pl.fala.launcher;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.provider.Settings;

/** Po restarcie telefonu przywraca wyspę i ekran blokady. */
public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context c, Intent i) {
        String a = i.getAction();
        if (!Intent.ACTION_BOOT_COMPLETED.equals(a) && !Intent.ACTION_MY_PACKAGE_REPLACED.equals(a)) return;
        boolean global = c.getSharedPreferences("fala", Context.MODE_PRIVATE).getBoolean("island_global", true);
        if (global && Settings.canDrawOverlays(c)) IslandService.start(c.getApplicationContext());
        LockActivity.ensureScreenReceiver(c.getApplicationContext());
    }
}
