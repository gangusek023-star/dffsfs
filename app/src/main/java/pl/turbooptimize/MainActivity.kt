package pl.turbooptimize

import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import kotlin.concurrent.thread

class MainActivity : AppCompatActivity() {

    private val ui = Handler(Looper.getMainLooper())
    private val blue = Color.parseColor("#2979FF")
    private val cardBg = Color.parseColor("#1E1E1E")
    private val okColor = Color.parseColor("#00C853")
    private val badColor = Color.parseColor("#FF9100")

    private lateinit var scoreText: TextView
    private lateinit var scoreLabel: TextView
    private lateinit var batteryText: TextView
    private lateinit var checksBox: LinearLayout
    private lateinit var optimizeBtn: Button
    private lateinit var statusText: TextView

    private var checks: List<Check> = emptyList()
    private var pendingOptimize = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(buildUi())
    }

    override fun onResume() {
        super.onResume()
        if (pendingOptimize && Checks.canWriteSettings(this)) {
            pendingOptimize = false
            optimizeAll()
        } else analyze()
    }

    // ---------------- UI ----------------

    private fun buildUi(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(24), dp(16), dp(32))
        }
        root.addView(TextView(this).apply {
            text = "⚙️ Turbo Optimization"; textSize = 26f; setTypeface(null, Typeface.BOLD); setTextColor(blue)
        })

        // Wynik
        val scoreCard = card().apply { gravity = Gravity.CENTER_HORIZONTAL }
        scoreText = TextView(this).apply {
            textSize = 64f; setTypeface(null, Typeface.BOLD); gravity = Gravity.CENTER
            text = "…"
            val ring = GradientDrawable().apply {
                shape = GradientDrawable.OVAL; setStroke(dp(8), blue); setColor(Color.TRANSPARENT)
            }
            background = ring
            layoutParams = LinearLayout.LayoutParams(dp(170), dp(170)).apply { gravity = Gravity.CENTER_HORIZONTAL }
        }
        scoreLabel = label(16f, Color.LTGRAY).apply { gravity = Gravity.CENTER; setPadding(0, dp(10), 0, 0) }
        scoreCard.addView(scoreText); scoreCard.addView(scoreLabel)
        root.addView(scoreCard)

        optimizeBtn = Button(this).apply {
            text = "⚡  OPTYMALIZUJ WSZYSTKO"; textSize = 18f
            layoutParams = LinearLayout.LayoutParams(-1, dp(64)).apply { topMargin = dp(12) }
            setOnClickListener { optimizeAll() }
        }
        root.addView(optimizeBtn)

        statusText = label(14f, Color.LTGRAY).apply { setPadding(dp(4), dp(8), dp(4), 0) }
        root.addView(statusText)

        // Bateria
        val bat = card()
        bat.addView(header("🔋 Bateria"))
        batteryText = label(14f, Color.WHITE)
        bat.addView(batteryText)
        root.addView(bat)

        // Kontrole
        val cc = card()
        cc.addView(header("📋 Raport"))
        checksBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        cc.addView(checksBox)
        root.addView(cc)

        // Narzędzia
        val tools = card()
        tools.addView(header("🧰 Szybkie narzędzia"))
        tools.addView(toolBtn("🚀 Zwolnij RAM") {
            statusText.text = "Zamykam aplikacje w tle…"
            thread { val f = Checks.freeRam(this); ui.post { statusText.text = "Zwolniono ${fmt(f)} RAM"; analyze() } }
        })
        tools.addView(toolBtn("🔋 Oszczędzanie baterii") { open(Intent(Settings.ACTION_BATTERY_SAVER_SETTINGS)) })
        tools.addView(toolBtn("📶 Internet i Wi-Fi") {
            if (android.os.Build.VERSION.SDK_INT >= 29) open(Intent(Settings.Panel.ACTION_INTERNET_CONNECTIVITY))
            else open(Intent(Settings.ACTION_WIFI_SETTINGS))
        })
        tools.addView(toolBtn("🔔 Powiadomienia aplikacji") { open(Intent("android.settings.ALL_APPS_NOTIFICATION_SETTINGS")) })
        tools.addView(toolBtn("📊 Zużycie danych") { open(Intent(Settings.ACTION_DATA_USAGE_SETTINGS)) })
        root.addView(tools)

        return ScrollView(this).apply { addView(root) }
    }

    private fun card() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setBackgroundColor(cardBg)
        setPadding(dp(14), dp(14), dp(14), dp(14))
        layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) }
    }

    private fun header(t: String) = TextView(this).apply {
        text = t; textSize = 18f; setTypeface(null, Typeface.BOLD); setTextColor(Color.WHITE); setPadding(0, 0, 0, dp(8))
    }

    private fun label(size: Float, color: Int) = TextView(this).apply { textSize = size; setTextColor(color) }

    private fun toolBtn(t: String, onClick: () -> Unit) = Button(this).apply {
        text = t; isAllCaps = false; setOnClickListener { onClick() }
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun fmt(b: Long) = if (b >= 1L shl 30) "%.2f GB".format(b / (1L shl 30).toFloat()) else "%.0f MB".format(b / (1L shl 20).toFloat())

    // ---------------- Analiza ----------------

    private fun analyze() {
        thread {
            val list = Checks.runAll(this)
            val b = Checks.battery(this)
            ui.post {
                checks = list
                render(list)
                batteryText.text = "Poziom: ${b.level}%${if (b.charging) " (ładuje się)" else ""}\n" +
                    "Temperatura: %.1f°C\nNapięcie: %.2f V\nStan: %s".format(b.temp, b.voltage / 1000f, b.health)
            }
        }
    }

    private fun render(list: List<Check>) {
        val s = Checks.score(list)
        scoreText.text = "$s"
        val color = when { s >= 85 -> okColor; s >= 60 -> badColor; else -> Color.parseColor("#FF1744") }
        scoreText.setTextColor(color)
        (scoreText.background as GradientDrawable).setStroke(dp(8), color)
        scoreLabel.text = when {
            s >= 85 -> "Telefon jest zoptymalizowany 👍"
            s >= 60 -> "Można to poprawić"
            else -> "Telefon wymaga optymalizacji"
        }

        checksBox.removeAllViews()
        list.sortedBy { it.ok }.forEach { c ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(6), 0, dp(6))
            }
            val texts = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
            }
            texts.addView(label(15f, if (c.ok) okColor else badColor).apply { text = (if (c.ok) "✓ " else "! ") + c.title })
            texts.addView(label(12f, Color.LTGRAY).apply { text = c.detail })
            row.addView(texts)
            if (!c.ok || c.apps.isNotEmpty()) {
                row.addView(Button(this).apply {
                    text = if (c.ok) "Pokaż" else c.fixLabel
                    isAllCaps = false; textSize = 12f
                    setOnClickListener { handle(c) }
                })
            }
            checksBox.addView(row)
        }
    }

    // ---------------- Naprawy ----------------

    private fun needWriteSettings(): Boolean {
        if (Checks.canWriteSettings(this)) return false
        AlertDialog.Builder(this)
            .setTitle("Potrzebne uprawnienie")
            .setMessage("Aby zmienić jasność, wygaszanie ekranu i dźwięki, włącz „Modyfikowanie ustawień systemu” dla Turbo Optimization.")
            .setPositiveButton("Otwórz") { _, _ ->
                pendingOptimize = true
                open(Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, Uri.parse("package:$packageName")))
            }
            .setNegativeButton("Anuluj", null).show()
        return true
    }

    private fun handle(c: Check) {
        when (c.id) {
            "bright", "timeout", "touch" -> {
                if (needWriteSettings()) return
                Checks.fix(this, c.id); toast("Gotowe: ${c.title}"); analyze()
            }
            "ram" -> thread { val f = Checks.freeRam(this); ui.post { toast("Zwolniono ${fmt(f)} RAM"); analyze() } }
            "anim" -> animationGuide()
            "storage" -> {
                toast("Usuń zbędne pliki – możesz też użyć aplikacji TurboClean")
                open(Intent(Settings.ACTION_INTERNAL_STORAGE_SETTINGS))
            }
            "temp" -> info("Telefon za gorący",
                "• Zdejmij etui i odłóż telefon na chwilę\n• Nie używaj go podczas ładowania\n• Zamknij gry i nawigację\n• Zmniejsz jasność\n\nCiepły telefon sam zwalnia procesor, żeby się nie przegrzać.")
            "unused" -> if (!Checks.hasUsageAccess(this)) {
                toast("Znajdź Turbo Optimization i włącz dostęp")
                open(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
            } else appList(c, "Stuknij aplikację, aby ją odinstalować") { uninstall(it) }
            "battopt" -> appList(c, "Stuknij aplikację i ustaw baterię na „Optymalizowane” lub „Ograniczone”") { appDetails(it) }
            "boot" -> appList(c, "Stuknij aplikację, której nie potrzebujesz od razu, i ogranicz jej baterię („Ograniczone”) – wtedy nie będzie startować w tle.") { appDetails(it) }
        }
    }

    private fun optimizeAll() {
        val autoTodo = checks.filter { !it.ok && it.auto }
        if (autoTodo.any { it.id != "ram" } && needWriteSettings()) return
        optimizeBtn.isEnabled = false
        statusText.text = "Optymalizuję…"
        thread {
            val done = mutableListOf<String>()
            for (c in autoTodo) if (Checks.fix(this, c.id)) done += c.title
            if (autoTodo.none { it.id == "ram" }) Checks.freeRam(this)
            val list = Checks.runAll(this)
            ui.post {
                optimizeBtn.isEnabled = true
                checks = list
                render(list)
                val manual = list.filter { !it.ok && !it.auto }
                statusText.text = buildString {
                    append("✅ Zoptymalizowano")
                    if (done.isNotEmpty()) append(": " + done.joinToString(", "))
                    append(". Zwolniono RAM.")
                    if (manual.isNotEmpty()) append("\nDo zrobienia ręcznie (pomarańczowe w raporcie): " + manual.joinToString(", ") { it.title })
                }
            }
        }
    }

    private fun animationGuide() {
        AlertDialog.Builder(this)
            .setTitle("Przyspiesz animacje")
            .setMessage(
                "Android nie pozwala aplikacjom zmieniać tego samodzielnie, ale zrobisz to w minutę:\n\n" +
                "1. Ustawienia → Informacje o telefonie → stuknij 7 razy „Numer kompilacji”.\n" +
                "2. Wróć: Ustawienia → System → Opcje programisty.\n" +
                "3. Przewiń do sekcji „Rysowanie” i ustaw na 0.5x:\n" +
                "   • Skala animacji okna\n   • Skala animacji przejścia\n   • Skala długości animacji\n\n" +
                "Telefon od razu będzie reagował szybciej."
            )
            .setPositiveButton("Opcje programisty") { _, _ ->
                try { startActivity(Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)) }
                catch (e: Exception) { open(Intent(Settings.ACTION_DEVICE_INFO_SETTINGS)) }
            }
            .setNeutralButton("Informacje o telefonie") { _, _ -> open(Intent(Settings.ACTION_DEVICE_INFO_SETTINGS)) }
            .setNegativeButton("Zamknij", null).show()
    }

    private fun appList(c: Check, hint: String, onPick: (String) -> Unit) {
        if (c.apps.isEmpty()) { toast("Brak aplikacji na liście"); return }
        val names = c.apps.map { "${it.label}\n${it.info}" }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle(c.title)
            .setItems(names) { _, i -> onPick(c.apps[i].pkg) }
            .setNegativeButton("Zamknij", null)
            .create().also { d ->
                d.setOnShowListener { toast(hint) }
                d.show()
            }
    }

    private fun uninstall(pkg: String) {
        open(Intent(Intent.ACTION_DELETE, Uri.parse("package:$pkg")))
    }

    private fun appDetails(pkg: String) {
        open(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$pkg")))
    }

    private fun info(title: String, msg: String) {
        AlertDialog.Builder(this).setTitle(title).setMessage(msg).setPositiveButton("OK", null).show()
    }

    private fun open(i: Intent) {
        try { startActivity(i) } catch (e: Exception) {
            try { startActivity(Intent(Settings.ACTION_SETTINGS)) } catch (_: Exception) {}
        }
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()
}
