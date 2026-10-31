package pl.turbocpu

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Po restarcie telefonu przywraca profil i włącza ochronę, jeśli była aktywna. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val p = Prefs(ctx)
        if (p.guardEnabled) GuardService.start(ctx)
    }
}
