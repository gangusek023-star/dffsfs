package pl.turbocpu

import android.Manifest
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.graphics.Color
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import kotlin.concurrent.thread

class MainActivity : AppCompatActivity() {

    private lateinit var prefs: Prefs
    private lateinit var statusText: TextView
    private lateinit var coresText: TextView
    private lateinit var protectedText: TextView
    private lateinit var boostText: TextView
    private val ui = Handler(Looper.getMainLooper())
    private var hasRoot = false

    private val orange = Color.parseColor("#FF5722")
    private val card = Color.parseColor("#1E1E1E")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        if (Build.VERSION.SDK_INT >= 33) requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        setContentView(buildUi())
        thread {
            hasRoot = RootShell.hasRoot()
            ui.post {
                if (!hasRoot) {
                    AlertDialog.Builder(this)
                        .setTitle("Brak roota")
                        .setMessage("Zmiana taktowania procesora i ochrona aplikacji wymagają uprawnień root (np. Magisk / KernelSU). Bez nich aplikacja pokazuje tylko odczyty.")
                        .setPositiveButton("OK", null).show()
                }
            }
        }
        ui.post(refresh)
    }

    override fun onDestroy() {
        ui.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    // ---------- UI ----------

    private fun buildUi(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(24), dp(16), dp(24))
        }

        root.addView(TextView(this).apply {
            text = "⚡ TurboCPU"; textSize = 28f; setTypeface(null, Typeface.BOLD); setTextColor(orange)
        })

        // Status
        val statusCard = card()
        statusText = TextView(this).apply { textSize = 16f; setTextColor(Color.WHITE) }
        coresText = TextView(this).apply { textSize = 13f; setTextColor(Color.LTGRAY); typeface = Typeface.MONOSPACE }
        statusCard.addView(statusText); statusCard.addView(coresText)
        root.addView(statusCard)

        // Profile
        val profCard = card()
        profCard.addView(header("Profil procesora"))
        val grid = GridLayout(this).apply { columnCount = 2 }
        CpuManager.Profile.values().forEach { p ->
            grid.addView(Button(this).apply {
                text = p.label
                setOnClickListener { applyProfile(p) }
                layoutParams = GridLayout.LayoutParams().apply {
                    width = 0; columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f); setMargins(dp(4), dp(4), dp(4), dp(4))
                }
            })
        }
        profCard.addView(grid)

        val limitLabel = TextView(this).apply { setTextColor(Color.LTGRAY) }
        fun updLimit() { limitLabel.text = "Limit taktowania: ${prefs.maxPercent}% fabrycznego maksimum" }
        updLimit()
        profCard.addView(limitLabel)
        profCard.addView(SeekBar(this).apply {
            max = 50; progress = prefs.maxPercent - 50
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, v: Int, u: Boolean) { prefs.maxPercent = v + 50; updLimit() }
                override fun onStartTrackingTouch(s: SeekBar?) {}
                override fun onStopTrackingTouch(s: SeekBar?) {}
            })
        })

        val tempLabel = TextView(this).apply { setTextColor(Color.LTGRAY) }
        fun updTemp() { tempLabel.text = "Bezpiecznik temperatury: ${prefs.tempLimit}°C" }
        updTemp()
        profCard.addView(tempLabel)
        profCard.addView(SeekBar(this).apply {
            max = 30; progress = prefs.tempLimit - 60
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, v: Int, u: Boolean) { prefs.tempLimit = v + 60; updTemp() }
                override fun onStartTrackingTouch(s: SeekBar?) {}
                override fun onStopTrackingTouch(s: SeekBar?) {}
            })
        })
        root.addView(profCard)

        // Not Crash
        val ncCard = card()
        ncCard.addView(header("🛡️ Not Crash – ochrona aplikacji"))
        ncCard.addView(TextView(this).apply {
            setTextColor(Color.LTGRAY); textSize = 13f
            text = "Wybrane aplikacje nie będą zamykane przez system przy braku pamięci, a po nagłym zamknięciu uruchomią się ponownie."
        })
        protectedText = TextView(this).apply { setTextColor(Color.WHITE); setPadding(0, dp(8), 0, dp(8)) }
        ncCard.addView(protectedText)
        ncCard.addView(Button(this).apply {
            text = "Wybierz chronione aplikacje"
            setOnClickListener { pickApps("Chronione aplikacje", prefs.protectedApps) { prefs.protectedApps = it; renderLists() } }
        })
        ncCard.addView(CheckBox(this).apply {
            text = "Automatyczny restart po zamknięciu"; setTextColor(Color.WHITE)
            isChecked = prefs.autoRestart
            setOnCheckedChangeListener { _, c -> prefs.autoRestart = c }
        })
        root.addView(ncCard)

        // Boost per app
        val bCard = card()
        bCard.addView(header("🚀 Boost dla aplikacji"))
        bCard.addView(TextView(this).apply {
            setTextColor(Color.LTGRAY); textSize = 13f
            text = "Gdy wybrana aplikacja (np. gra) jest otwarta, procesor przechodzi na Turbo. Po wyjściu wraca profil domyślny."
        })
        boostText = TextView(this).apply { setTextColor(Color.WHITE); setPadding(0, dp(8), 0, dp(8)) }
        bCard.addView(boostText)
        bCard.addView(Button(this).apply {
            text = "Wybierz aplikacje do boostu"
            setOnClickListener { pickApps("Aplikacje z boostem", prefs.boostApps) { prefs.boostApps = it; renderLists() } }
        })
        root.addView(bCard)

        // Guard switch
        root.addView(Switch(this).apply {
            text = "Włącz ochronę i boost w tle"; textSize = 18f; setTextColor(Color.WHITE)
            setPadding(dp(8), dp(16), dp(8), dp(16))
            isChecked = prefs.guardEnabled
            setOnCheckedChangeListener { _, on ->
                prefs.guardEnabled = on
                if (on) GuardService.start(this@MainActivity) else GuardService.stop(this@MainActivity)
            }
        })

        renderLists()
        return ScrollView(this).apply { addView(root) }
    }

    private fun card() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setBackgroundColor(card)
        setPadding(dp(14), dp(14), dp(14), dp(14))
        layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) }
    }

    private fun header(t: String) = TextView(this).apply {
        text = t; textSize = 18f; setTypeface(null, Typeface.BOLD); setTextColor(Color.WHITE)
        setPadding(0, 0, 0, dp(6))
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun renderLists() {
        protectedText.text = labels(prefs.protectedApps).ifEmpty { "Brak wybranych aplikacji" }
        boostText.text = labels(prefs.boostApps).ifEmpty { "Brak wybranych aplikacji" }
    }

    private fun labels(pkgs: Set<String>) = pkgs.joinToString("\n") { "• " + appLabel(it) }

    private fun appLabel(pkg: String) = try {
        packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString()
    } catch (e: Exception) { pkg }

    // ---------- Akcje ----------

    private fun applyProfile(p: CpuManager.Profile) {
        if (!hasRoot) { toast("Wymagany root"); return }
        if (p == CpuManager.Profile.MAX) {
            AlertDialog.Builder(this)
                .setTitle("Tryb Turbo")
                .setMessage("Rdzenie będą stale pracować na maksymalnym taktowaniu. Telefon będzie się mocniej grzał i szybciej rozładowywał. Kontynuować?")
                .setPositiveButton("Tak") { _, _ -> doApply(p) }
                .setNegativeButton("Anuluj", null).show()
        } else doApply(p)
    }

    private fun doApply(p: CpuManager.Profile) {
        prefs.defaultProfile = p.name
        thread {
            CpuManager.applyProfile(p, prefs.maxPercent)
            ui.post { toast("Ustawiono: ${p.label}") }
        }
    }

    private fun pickApps(title: String, selected: Set<String>, onDone: (Set<String>) -> Unit) {
        val apps = packageManager.getInstalledApplications(0)
            .filter { (it.flags and ApplicationInfo.FLAG_SYSTEM) == 0 || packageManager.getLaunchIntentForPackage(it.packageName) != null }
            .filter { it.packageName != packageName }
            .map { it.packageName to packageManager.getApplicationLabel(it).toString() }
            .sortedBy { it.second.lowercase() }
        val names = apps.map { it.second }.toTypedArray()
        val checked = apps.map { it.first in selected }.toBooleanArray()
        AlertDialog.Builder(this)
            .setTitle(title)
            .setMultiChoiceItems(names, checked) { _, i, c -> checked[i] = c }
            .setPositiveButton("Zapisz") { _, _ ->
                onDone(apps.filterIndexed { i, _ -> checked[i] }.map { it.first }.toSet())
            }
            .setNegativeButton("Anuluj", null).show()
    }

    private val refresh = object : Runnable {
        override fun run() {
            thread {
                val sb = StringBuilder()
                for (c in CpuManager.cores) {
                    val cur = CpuManager.curFreq(c)
                    val max = CpuManager.hwMax(c)
                    sb.append("CPU$c  ${if (cur > 0) CpuManager.fmt(cur) else "offline"}".padEnd(20))
                    sb.append(" / ${CpuManager.fmt(max)}  ${CpuManager.governor(c)}\n")
                }
                val t = CpuManager.temperature()
                val status = "Root: ${if (hasRoot) "✅" else "❌"}   Temp: %.0f°C   Profil: %s"
                    .format(t, CpuManager.Profile.valueOf(prefs.defaultProfile).label)
                ui.post { statusText.text = status; coresText.text = sb.toString().trimEnd() }
            }
            ui.postDelayed(this, 2000)
        }
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()
}
