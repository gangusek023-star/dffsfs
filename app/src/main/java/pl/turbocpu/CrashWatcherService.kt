package pl.turbocpu

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/**
 * Not Crash BEZ ROOTA (usługa ułatwień dostępu).
 *
 *  - Aplikacja nie odpowiada (ANR)  -> automatycznie klika „Czekaj”, więc
 *    zawieszona aplikacja nie zostaje zamknięta.
 *  - Aplikacja się zatrzymała (crash) -> klika „Otwórz aplikację ponownie”,
 *    a jeśli takiego przycisku nie ma, zamyka okno i sama uruchamia aplikację.
 *
 * Działa tylko dla aplikacji wybranych w TurboCPU. Limit: 5 restartów na 2 minuty.
 */
class CrashWatcherService : AccessibilityService() {

    private val ui = Handler(Looper.getMainLooper())
    private var lastProtected: String? = null
    private var lastProtectedTime = 0L
    private val restarts = mutableMapOf<String, MutableList<Long>>()
    private var lastActionTime = 0L

    private val anrWords = listOf("nie odpowiada", "isn't responding", "not responding", "no responde")
    private val crashWords = listOf(
        "zatrzym", "przestał", "przestala", "wystąpił błąd", "has stopped", "keeps stopping",
        "stopped working", "se detuvo", "se ha detenido"
    )
    private val waitButtons = listOf("czekaj", "wait", "esperar")
    private val reopenButtons = listOf("otwórz aplikację ponownie", "otwórz ponownie", "open app again", "reopen", "abrir")
    private val closeButtons = listOf("zamknij aplikację", "close app", "zamknij", "ok", "cerrar")

    override fun onAccessibilityEvent(e: AccessibilityEvent) {
        val pkg = e.packageName?.toString() ?: return
        val protected = Prefs(this).protectedApps
        if (protected.isEmpty()) return

        if (pkg in protected) {
            lastProtected = pkg
            lastProtectedTime = System.currentTimeMillis()
            return
        }
        // Okna awarii i „nie odpowiada” pokazuje system (pakiet "android")
        if (pkg != "android" && pkg != "com.android.systemui") return
        if (System.currentTimeMillis() - lastActionTime < 1500) return

        val root = rootInActiveWindow ?: e.source ?: return
        val texts = mutableListOf<String>()
        e.text?.forEach { texts.add(it.toString()) }
        collectText(root, texts)
        val all = texts.joinToString(" ").lowercase()

        val isAnr = anrWords.any { all.contains(it) }
        val isCrash = !isAnr && crashWords.any { all.contains(it) }
        if (!isAnr && !isCrash) return

        // Której chronionej aplikacji dotyczy okno? Szukamy jej nazwy w treści.
        val target = protected.firstOrNull { p -> label(p).lowercase().let { it.isNotBlank() && all.contains(it) } }
            ?: lastProtected?.takeIf { it in protected && System.currentTimeMillis() - lastProtectedTime < 5000 }
            ?: return

        lastActionTime = System.currentTimeMillis()

        if (isAnr) {
            if (clickButton(root, waitButtons)) {
                Log.i(TAG, "ANR $target -> Czekaj")
                toast("TurboCPU: ${label(target)} – czekam, aż zacznie odpowiadać")
            }
            return
        }

        // Awaria
        if (!canRestart(target)) {
            toast("TurboCPU: ${label(target)} ciągle się wysypuje – wstrzymuję restarty")
            return
        }
        if (clickButton(root, reopenButtons)) {
            Log.i(TAG, "Crash $target -> Otwórz ponownie")
        } else {
            clickButton(root, closeButtons)
            if (Prefs(this).autoRestart) ui.postDelayed({ relaunch(target) }, 900)
        }
        toast("TurboCPU: przywracam ${label(target)}")
    }

    override fun onInterrupt() {}

    private fun canRestart(pkg: String): Boolean {
        val now = System.currentTimeMillis()
        val list = restarts.getOrPut(pkg) { mutableListOf() }
        list.removeAll { now - it > 120_000 }
        if (list.size >= 5) return false
        list.add(now)
        return true
    }

    private fun relaunch(pkg: String) {
        val i = packageManager.getLaunchIntentForPackage(pkg) ?: return
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        try { startActivity(i) } catch (ex: Exception) { Log.e(TAG, "relaunch", ex) }
    }

    private fun collectText(n: AccessibilityNodeInfo?, out: MutableList<String>, depth: Int = 0) {
        if (n == null || depth > 25) return
        n.text?.let { out.add(it.toString()) }
        n.contentDescription?.let { out.add(it.toString()) }
        for (i in 0 until n.childCount) collectText(n.getChild(i), out, depth + 1)
    }

    /** Klika pierwszy przycisk, którego tekst pasuje do listy (kolejność = priorytet). */
    private fun clickButton(root: AccessibilityNodeInfo, labels: List<String>): Boolean {
        val nodes = mutableListOf<AccessibilityNodeInfo>()
        flatten(root, nodes)
        for (wanted in labels) {
            val node = nodes.firstOrNull { n ->
                val t = (n.text ?: n.contentDescription)?.toString()?.lowercase()?.trim() ?: return@firstOrNull false
                if (wanted.length <= 3) t == wanted else t.contains(wanted)
            } ?: continue
            var c: AccessibilityNodeInfo? = node
            while (c != null && !c.isClickable) c = c.parent
            if (c?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true) return true
        }
        return false
    }

    private fun flatten(n: AccessibilityNodeInfo?, out: MutableList<AccessibilityNodeInfo>, depth: Int = 0) {
        if (n == null || depth > 25) return
        out.add(n)
        for (i in 0 until n.childCount) flatten(n.getChild(i), out, depth + 1)
    }

    private fun label(pkg: String): String = try {
        packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString()
    } catch (e: Exception) { "" }

    private fun toast(s: String) = ui.post {
        android.widget.Toast.makeText(this, s, android.widget.Toast.LENGTH_SHORT).show()
    }

    companion object {
        private const val TAG = "TurboCPU"

        fun isEnabled(ctx: Context): Boolean {
            val list = Settings.Secure.getString(ctx.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: return false
            return list.split(':').any { it.startsWith(ctx.packageName + "/") }
        }
    }
}
