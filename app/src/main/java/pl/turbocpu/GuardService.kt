package pl.turbocpu

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.util.Log

/**
 * Funkcja "Not Crash":
 *  1. Ochrona przed zabiciem: wybrane aplikacje dostają oom_score_adj = -1000,
 *     więc system (Low Memory Killer) nie zamyka ich przy braku RAM-u.
 *  2. Auto-restart: jeśli chroniona aplikacja nagle zniknie (crash / zabicie),
 *     zostaje uruchomiona ponownie.
 *  3. Boost per aplikacja: gdy wybrana gra/aplikacja jest na pierwszym planie,
 *     włącza się profil wydajności, a po wyjściu wraca profil domyślny.
 *  4. Bezpiecznik termiczny: powyżej limitu temperatury boost jest wyłączany,
 *     żeby telefon nie przegrzał się i nie zrestartował.
 */
class GuardService : Service() {

    private lateinit var prefs: Prefs
    private lateinit var thread: HandlerThread
    private lateinit var handler: Handler

    private val lastSeenRunning = mutableSetOf<String>()
    private val restartCount = mutableMapOf<String, Int>()
    private var boosted = false
    private var throttled = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        prefs = Prefs(this)
        thread = HandlerThread("guard").also { it.start() }
        handler = Handler(thread.looper)
        startForegroundCompat("Ochrona aktywna")
        handler.post(loop)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        thread.quitSafely()
        super.onDestroy()
    }

    private val loop = object : Runnable {
        override fun run() {
            try { tick() } catch (e: Exception) { Log.e("TurboCPU", "tick", e) }
            handler.postDelayed(this, 3000)
        }
    }

    private fun tick() {
        val protected = prefs.protectedApps
        val boost = prefs.boostApps
        val fg = foregroundPackage()

        // 1 + 2: ochrona i auto-restart
        val cmds = StringBuilder()
        for (pkg in protected) {
            val pids = RootShell.run("pidof $pkg").split(" ").filter { it.isNotBlank() }
            if (pids.isNotEmpty()) {
                pids.forEach { cmds.append("echo -1000 > /proc/$it/oom_score_adj 2>/dev/null; ") }
                lastSeenRunning.add(pkg)
            } else if (pkg in lastSeenRunning) {
                lastSeenRunning.remove(pkg)
                val n = restartCount.getOrDefault(pkg, 0)
                // Limit restartów, żeby nie wpaść w pętlę, gdy aplikacja crashuje przy starcie
                if (prefs.autoRestart && n < 5) {
                    restartCount[pkg] = n + 1
                    Log.i("TurboCPU", "Restart $pkg (próba ${n + 1})")
                    RootShell.run("monkey -p $pkg -c android.intent.category.LAUNCHER 1")
                    updateNotification("Przywrócono: $pkg")
                }
            }
        }
        if (cmds.isNotEmpty()) RootShell.run(cmds.toString())

        // 3 + 4: boost per aplikacja z bezpiecznikiem temperatury
        val temp = CpuManager.temperature()
        val hot = temp >= prefs.tempLimit
        val wantBoost = fg != null && fg in boost

        when {
            wantBoost && hot -> if (!throttled) {
                CpuManager.applyProfile(CpuManager.Profile.BALANCED, 85)
                throttled = true; boosted = false
                updateNotification("Za gorąco (%.0f°C) – boost wstrzymany".format(temp))
            }
            // Po przegrzaniu boost wraca dopiero 5°C poniżej limitu (histereza)
            wantBoost -> if (!boosted && (!throttled || temp < prefs.tempLimit - 5)) {
                CpuManager.applyProfile(CpuManager.Profile.MAX, prefs.maxPercent)
                boosted = true; throttled = false
                updateNotification("Boost: $fg")
            }
            !wantBoost && (boosted || throttled) -> {
                CpuManager.applyProfile(CpuManager.Profile.valueOf(prefs.defaultProfile), prefs.maxPercent)
                boosted = false; throttled = false
                updateNotification("Ochrona aktywna")
            }
        }
    }

    /** Pakiet aplikacji na pierwszym planie (przez dumpsys, wymaga roota). */
    private fun foregroundPackage(): String? {
        val out = RootShell.run("dumpsys activity activities | grep -E 'mResumedActivity|topResumedActivity' | head -n 1")
        return Regex("u0 ([a-zA-Z0-9_.]+)/").find(out)?.groupValues?.get(1)
    }

    private fun startForegroundCompat(text: String) {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(NotificationChannel("guard", "Ochrona aplikacji", NotificationManager.IMPORTANCE_LOW))
        val n = buildNotification(text)
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(1, n)
        }
    }

    private fun buildNotification(text: String): Notification =
        Notification.Builder(this, "guard")
            .setContentTitle("TurboCPU")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_manage)
            .setOngoing(true)
            .build()

    private fun updateNotification(text: String) {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(1, buildNotification(text))
    }

    companion object {
        fun start(ctx: Context) = ctx.startForegroundService(Intent(ctx, GuardService::class.java))
        fun stop(ctx: Context) = ctx.stopService(Intent(ctx, GuardService::class.java))
    }
}
