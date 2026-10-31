package pl.turboclean

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/**
 * Automatyczne czyszczenie pamięci podręcznej innych aplikacji (bez roota).
 * Dla każdej aplikacji: otwiera „Informacje o aplikacji” → „Pamięć i pamięć podręczna”
 * → naciska „Wyczyść pamięć podręczną”. Działa tylko po uruchomieniu z TurboClean.
 */
class CacheCleanerService : AccessibilityService() {

    private enum class Step { OPEN_STORAGE, CLEAR, WAIT }

    private val h = Handler(Looper.getMainLooper())
    private var step = Step.WAIT
    private var current: String? = null
    private var scrolls = 0

    private val storageLabels = listOf(
        "pamięć i pamięć podręczna", "storage & cache", "storage and cache",
        "pamięć wewnętrzna", "pamięć masowa", "storage", "pamięć"
    )
    // UWAGA: nigdy „Wyczyść pamięć” / „Wyczyść dane” – to kasuje dane aplikacji!
    private val clearLabels = listOf("wyczyść pamięć podręczną", "clear cache")

    override fun onServiceConnected() {
        instance = this
    }

    override fun onDestroy() {
        instance = null
        super.onDestroy()
    }

    override fun onInterrupt() {}

    override fun onAccessibilityEvent(e: AccessibilityEvent) {
        if (queue == null || current == null) return
        val pkg = e.packageName?.toString() ?: return
        if (!pkg.contains("settings")) return
        val root = rootInActiveWindow ?: return

        when (step) {
            Step.OPEN_STORAGE -> {
                // Pomijamy pozycję „Pamięć” oznaczającą RAM (np. „Wykorzystanie pamięci”)
                val node = find(root, storageLabels) { !it.contains("wykorzystanie") && !it.contains("ram") && !it.contains("memory") }
                if (node != null && click(node)) {
                    step = Step.CLEAR
                    scrolls = 0
                } else if (scrolls < 3) {
                    scrolls++
                    scrollable(root)?.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
                }
            }
            Step.CLEAR -> {
                val node = find(root, clearLabels) { true } ?: return
                step = Step.WAIT
                var c: AccessibilityNodeInfo? = node
                while (c != null && !c.isClickable) c = c.parent
                if (c != null && c.isEnabled) {
                    c.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                    cleaned++
                }
                h.removeCallbacks(timeout)
                h.postDelayed({ next() }, 500)
            }
            Step.WAIT -> {}
        }
    }

    private val timeout = Runnable { next() } // aplikacja nie odpowiedziała – idziemy dalej

    private fun next() {
        h.removeCallbacks(timeout)
        val q = queue ?: return
        val pkg = q.removeFirstOrNull()
        if (pkg == null) { finish(); return }
        current = pkg
        step = Step.OPEN_STORAGE
        scrolls = 0
        progress?.invoke(total - q.size, total)
        val i = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$pkg"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION)
        try { startActivity(i) } catch (ex: Exception) { }
        h.postDelayed(timeout, 4500)
    }

    private fun finish() {
        queue = null
        current = null
        step = Step.WAIT
        performGlobalAction(GLOBAL_ACTION_BACK)
        startActivity(
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
                .putExtra("cache_done", cleaned)
        )
    }

    private fun find(root: AccessibilityNodeInfo, labels: List<String>, ok: (String) -> Boolean): AccessibilityNodeInfo? {
        val all = mutableListOf<AccessibilityNodeInfo>()
        flatten(root, all, 0)
        for (l in labels) {
            all.firstOrNull { n ->
                val t = n.text?.toString()?.lowercase()?.trim() ?: return@firstOrNull false
                (if (l == "pamięć" || l == "storage") t == l else t.startsWith(l)) && ok(t)
            }?.let { return it }
        }
        return null
    }

    private fun click(n: AccessibilityNodeInfo): Boolean {
        var c: AccessibilityNodeInfo? = n
        while (c != null && !c.isClickable) c = c.parent
        return c?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true
    }

    private fun scrollable(n: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        if (n == null) return null
        if (n.isScrollable) return n
        for (i in 0 until n.childCount) scrollable(n.getChild(i))?.let { return it }
        return null
    }

    private fun flatten(n: AccessibilityNodeInfo?, out: MutableList<AccessibilityNodeInfo>, d: Int) {
        if (n == null || d > 30) return
        out.add(n)
        for (i in 0 until n.childCount) flatten(n.getChild(i), out, d + 1)
    }

    companion object {
        private var instance: CacheCleanerService? = null
        private var queue: ArrayDeque<String>? = null
        private var total = 0
        private var cleaned = 0
        var progress: ((Int, Int) -> Unit)? = null

        fun isEnabled(ctx: Context): Boolean {
            val list = Settings.Secure.getString(ctx.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: return false
            return list.split(':').any { it.startsWith(ctx.packageName + "/") }
        }

        /** Zwraca false, jeśli usługa nie jest włączona. */
        fun start(pkgs: List<String>): Boolean {
            val s = instance ?: return false
            queue = ArrayDeque(pkgs)
            total = pkgs.size
            cleaned = 0
            s.next()
            return true
        }
    }
}
