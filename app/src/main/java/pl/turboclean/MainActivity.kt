package pl.turboclean

import android.Manifest
import android.app.ActivityManager
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.StatFs
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import kotlin.concurrent.thread

class MainActivity : AppCompatActivity() {

    private val ui = Handler(Looper.getMainLooper())
    private val green = Color.parseColor("#00C853")
    private val cardBg = Color.parseColor("#1E1E1E")

    private lateinit var storageText: TextView
    private lateinit var storageBar: ProgressBar
    private lateinit var ramText: TextView
    private lateinit var ramBar: ProgressBar
    private lateinit var permBox: LinearLayout
    private lateinit var results: LinearLayout
    private lateinit var statusText: TextView
    private lateinit var scanBtn: Button
    private lateinit var cleanBtn: Button

    private var categories: List<Category> = emptyList()
    private var caches: List<AppCache> = emptyList()
    private var cacheChecked = true

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(buildUi())
        if (Build.VERSION.SDK_INT < 30) {
            requestPermissions(arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.WRITE_EXTERNAL_STORAGE), 1)
        }
    }

    override fun onResume() {
        super.onResume()
        refreshStats()
        renderPermissions()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.hasExtra("cache_done")) {
            val n = intent.getIntExtra("cache_done", 0)
            statusText.text = "✅ Gotowe! Wyczyszczono pamięć podręczną $n aplikacji."
            CacheCleanerService.progress = null
            ui.postDelayed({ refreshStats() }, 800)
        }
    }

    // ---------------- UI ----------------

    private fun buildUi(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(24), dp(16), dp(32))
        }
        root.addView(TextView(this).apply {
            text = "🧹 TurboClean"; textSize = 28f; setTypeface(null, Typeface.BOLD); setTextColor(green)
        })

        val stats = card()
        storageText = label(15f, Color.WHITE)
        storageBar = bar()
        ramText = label(15f, Color.WHITE)
        ramBar = bar()
        stats.addView(storageText); stats.addView(storageBar)
        stats.addView(ramText); stats.addView(ramBar)
        root.addView(stats)

        permBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(permBox)

        scanBtn = bigButton("🔍  SKANUJ") { scan() }
        root.addView(scanBtn)

        statusText = label(15f, Color.LTGRAY).apply { setPadding(dp(4), dp(8), dp(4), dp(8)) }
        root.addView(statusText)

        results = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(results)

        cleanBtn = bigButton("🧹  WYCZYŚĆ") { confirmClean() }.apply { visibility = View.GONE }
        root.addView(cleanBtn)

        root.addView(bigButton("🚀  PRZYSPIESZ (zwolnij RAM)") { boostRam() })

        root.addView(label(12f, Color.GRAY).apply {
            setPadding(dp(4), dp(12), dp(4), 0)
            text = "Zdjęcia, filmy, dokumenty i dane aplikacji nie są usuwane. Duże pliki i Kosz usuwasz tylko wtedy, gdy sam je zaznaczysz."
        })

        return ScrollView(this).apply { addView(root) }
    }

    private fun card() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setBackgroundColor(cardBg)
        setPadding(dp(14), dp(14), dp(14), dp(14))
        layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) }
    }

    private fun label(size: Float, color: Int) = TextView(this).apply { textSize = size; setTextColor(color) }

    private fun bar() = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
        max = 100
        layoutParams = LinearLayout.LayoutParams(-1, dp(14)).apply { bottomMargin = dp(10); topMargin = dp(4) }
    }

    private fun bigButton(t: String, onClick: () -> Unit) = Button(this).apply {
        text = t; textSize = 17f
        layoutParams = LinearLayout.LayoutParams(-1, dp(60)).apply { topMargin = dp(12) }
        setOnClickListener { onClick() }
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    // ---------------- Statystyki ----------------

    private fun refreshStats() {
        val st = StatFs(Environment.getDataDirectory().path)
        val total = st.totalBytes
        val free = st.availableBytes
        val used = total - free
        storageText.text = "💾 Pamięć: ${JunkScanner.fmt(used)} / ${JunkScanner.fmt(total)}  (wolne ${JunkScanner.fmt(free)})"
        storageBar.progress = (used * 100 / total).toInt()

        val mi = ActivityManager.MemoryInfo()
        (getSystemService(ACTIVITY_SERVICE) as ActivityManager).getMemoryInfo(mi)
        val ramUsed = mi.totalMem - mi.availMem
        ramText.text = "🧠 RAM: ${JunkScanner.fmt(ramUsed)} / ${JunkScanner.fmt(mi.totalMem)}"
        ramBar.progress = (ramUsed * 100 / mi.totalMem).toInt()
    }

    // ---------------- Uprawnienia ----------------

    private fun hasFiles(): Boolean =
        if (Build.VERSION.SDK_INT >= 30) Environment.isExternalStorageManager()
        else checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) == android.content.pm.PackageManager.PERMISSION_GRANTED

    private fun renderPermissions() {
        permBox.removeAllViews()
        val missing = mutableListOf<Pair<String, () -> Unit>>()
        if (!hasFiles()) missing += "📁 Zezwól na dostęp do plików (do usuwania śmieci)" to {
            if (Build.VERSION.SDK_INT >= 30) {
                try {
                    startActivity(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:$packageName")))
                } catch (e: Exception) {
                    startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
                }
            } else requestPermissions(arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE), 1)
        }
        if (!JunkScanner.hasUsageAccess(this)) missing += "📊 Zezwól na dostęp do użycia (do mierzenia pamięci podręcznej)" to {
            toast("Znajdź TurboClean i włącz dostęp")
            startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
        }
        if (!CacheCleanerService.isEnabled(this)) missing += "⚡ Włącz automatyczne czyszczenie pamięci podręcznej" to { showA11yHelp() }
        if (missing.isEmpty()) return

        val c = card()
        c.addView(label(16f, Color.WHITE).apply { text = "Potrzebne uprawnienia"; setTypeface(null, Typeface.BOLD) })
        missing.forEach { (t, action) ->
            c.addView(Button(this).apply { text = t; isAllCaps = false; setOnClickListener { action() } })
        }
        permBox.addView(c)
    }

    private fun showA11yHelp() {
        AlertDialog.Builder(this)
            .setTitle("Automatyczne czyszczenie")
            .setMessage(
                "Android nie pozwala aplikacjom czyścić pamięci podręcznej innych aplikacji. " +
                "TurboClean zrobi to za ciebie, klikając „Wyczyść pamięć podręczną” w ustawieniach każdej aplikacji.\n\n" +
                "1. Stuknij „Otwórz ustawienia” i włącz „TurboClean” (czasem w „Pobrane aplikacje”).\n\n" +
                "Jeśli przełącznik jest szary („Ustawienie z ograniczeniami”):\n" +
                "• stuknij „Informacje o aplikacji”,\n• w prawym górnym rogu ⋮,\n" +
                "• „Zezwól na ustawienia z ograniczeniami”,\n• wróć i włącz ponownie."
            )
            .setPositiveButton("Otwórz ustawienia") { _, _ -> startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
            .setNeutralButton("Informacje o aplikacji") { _, _ ->
                startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
            }
            .setNegativeButton("Anuluj", null).show()
    }

    // ---------------- Skanowanie ----------------

    private fun scan() {
        if (!hasFiles()) { toast("Najpierw zezwól na dostęp do plików"); renderPermissions(); return }
        scanBtn.isEnabled = false
        cleanBtn.visibility = View.GONE
        results.removeAllViews()
        statusText.text = "Skanuję…"
        thread {
            val cats = JunkScanner.scan { msg -> ui.post { statusText.text = msg } }
            ui.post { statusText.text = "Sprawdzam pamięć podręczną aplikacji…" }
            val cc = JunkScanner.appCaches(this)
            ui.post {
                categories = cats
                caches = cc
                scanBtn.isEnabled = true
                renderResults()
            }
        }
    }

    private fun renderResults() {
        results.removeAllViews()
        val c = card()
        c.addView(label(17f, Color.WHITE).apply { text = "Znalezione śmieci"; setTypeface(null, Typeface.BOLD) })

        // Pamięć podręczna aplikacji
        val cacheSize = caches.sumOf { it.bytes }
        val cacheDesc = when {
            !JunkScanner.hasUsageAccess(this) -> "Zezwól na dostęp do użycia, żeby to zmierzyć"
            caches.isEmpty() -> "Prawie pusta"
            else -> "${caches.size} aplikacji · najwięcej: " + caches.take(3).joinToString(", ") { "${it.label} ${JunkScanner.fmt(it.bytes)}" }
        }
        addRow(c, "Pamięć podręczna aplikacji", cacheSize, cacheDesc, cacheChecked && caches.isNotEmpty(), caches.isNotEmpty()) {
            cacheChecked = it; updateTotal()
        }

        categories.forEach { cat ->
            addRow(c, cat.title, cat.size, cat.desc, cat.checked && cat.files.isNotEmpty(), cat.files.isNotEmpty()) {
                cat.checked = it; updateTotal()
            }
        }
        results.addView(c)
        cleanBtn.visibility = View.VISIBLE
        updateTotal()
    }

    private fun addRow(parent: LinearLayout, title: String, size: Long, desc: String, checked: Boolean, enabled: Boolean, onChange: (Boolean) -> Unit) {
        parent.addView(CheckBox(this).apply {
            text = "$title — ${JunkScanner.fmt(size)}"
            setTextColor(if (enabled) Color.WHITE else Color.GRAY)
            textSize = 15f
            isChecked = checked
            isEnabled = enabled
            setOnCheckedChangeListener { _, b -> onChange(b) }
        })
        if (desc.isNotBlank()) parent.addView(label(12f, Color.LTGRAY).apply {
            text = desc; setPadding(dp(32), 0, 0, dp(8))
        })
    }

    private fun selectedSize(): Long =
        categories.filter { it.checked }.sumOf { it.size } + if (cacheChecked) caches.sumOf { it.bytes } else 0

    private fun updateTotal() {
        val s = selectedSize()
        statusText.text = "Do wyczyszczenia: ${JunkScanner.fmt(s)}"
        cleanBtn.text = "🧹  WYCZYŚĆ ${JunkScanner.fmt(s)}"
        cleanBtn.isEnabled = s > 0 || (cacheChecked && caches.isNotEmpty()) || categories.any { it.checked && it.files.isNotEmpty() }
    }

    // ---------------- Czyszczenie ----------------

    private fun confirmClean() {
        val risky = categories.filter { it.checked && (it.id == "large" || it.id == "trash") && it.files.isNotEmpty() }
        if (risky.isNotEmpty()) {
            AlertDialog.Builder(this)
                .setTitle("Na pewno?")
                .setMessage("Zaznaczono: ${risky.joinToString { it.title }}. Tych plików nie da się odzyskać.")
                .setPositiveButton("Usuń") { _, _ -> clean() }
                .setNegativeButton("Anuluj", null).show()
        } else clean()
    }

    private fun clean() {
        cleanBtn.isEnabled = false
        scanBtn.isEnabled = false
        statusText.text = "Czyszczę pliki…"
        thread {
            val freed = JunkScanner.delete(categories)
            ui.post {
                results.removeAllViews()
                cleanBtn.visibility = View.GONE
                scanBtn.isEnabled = true
                refreshStats()
                statusText.text = "✅ Usunięto ${JunkScanner.fmt(freed)} plików-śmieci."
                if (cacheChecked && caches.isNotEmpty()) cleanCaches(freed)
            }
        }
    }

    private fun cleanCaches(freedFiles: Long) {
        if (!CacheCleanerService.isEnabled(this)) {
            statusText.text = "✅ Usunięto ${JunkScanner.fmt(freedFiles)}.\nAby czyścić też pamięć podręczną aplikacji, włącz automatyczne czyszczenie."
            showA11yHelp()
            return
        }
        CacheCleanerService.progress = { i, n -> ui.post { statusText.text = "Czyszczę pamięć podręczną: $i / $n…" } }
        toast("Nie dotykaj ekranu – TurboClean czyści pamięć podręczną aplikacji")
        val ok = CacheCleanerService.start(caches.map { it.pkg })
        if (!ok) {
            statusText.text = "Usługa czyszczenia nie działa – wyłącz ją i włącz ponownie w ułatwieniach dostępu."
        }
    }

    private fun boostRam() {
        val am = getSystemService(ACTIVITY_SERVICE) as ActivityManager
        val before = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }.availMem
        statusText.text = "Zamykam aplikacje w tle…"
        thread {
            val pkgs = packageManager.getInstalledApplications(0)
                .filter { it.packageName != packageName && (it.flags and ApplicationInfo.FLAG_PERSISTENT) == 0 }
            pkgs.forEach { try { am.killBackgroundProcesses(it.packageName) } catch (e: Exception) {} }
            Thread.sleep(1200)
            val after = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }.availMem
            val gained = (after - before).coerceAtLeast(0)
            ui.post {
                refreshStats()
                statusText.text = "🚀 Zwolniono ${JunkScanner.fmt(gained)} RAM."
            }
        }
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()
}
