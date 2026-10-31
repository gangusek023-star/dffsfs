package pl.turbooptimize

import android.app.ActivityManager
import android.app.AppOpsManager
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.BatteryManager
import android.os.Build
import android.os.Environment
import android.os.PowerManager
import android.os.Process
import android.os.StatFs
import android.provider.Settings

/** Jeden punkt kontroli w raporcie optymalizacji. */
data class Check(
    val id: String,
    val title: String,
    val detail: String,
    val ok: Boolean,
    val weight: Int,           // ile punktów wyniku waży
    val auto: Boolean,         // da się naprawić automatycznie
    val fixLabel: String = "Napraw",
    val apps: List<AppItem> = emptyList(),
)

data class AppItem(val pkg: String, val label: String, val info: String)

data class BatteryInfo(val level: Int, val temp: Float, val voltage: Int, val charging: Boolean, val health: String)

object Checks {

    fun canWriteSettings(ctx: Context) = Settings.System.canWrite(ctx)

    fun hasUsageAccess(ctx: Context): Boolean {
        val ops = ctx.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        val mode = if (Build.VERSION.SDK_INT >= 29)
            ops.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), ctx.packageName)
        else @Suppress("DEPRECATION")
            ops.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), ctx.packageName)
        return mode == AppOpsManager.MODE_ALLOWED
    }

    fun battery(ctx: Context): BatteryInfo {
        val i = ctx.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = i?.let { it.getIntExtra(BatteryManager.EXTRA_LEVEL, 0) * 100 / it.getIntExtra(BatteryManager.EXTRA_SCALE, 100).coerceAtLeast(1) } ?: 0
        val temp = (i?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) ?: 0) / 10f
        val volt = i?.getIntExtra(BatteryManager.EXTRA_VOLTAGE, 0) ?: 0
        val status = i?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val charging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
        val health = when (i?.getIntExtra(BatteryManager.EXTRA_HEALTH, 0)) {
            BatteryManager.BATTERY_HEALTH_GOOD -> "Dobra"
            BatteryManager.BATTERY_HEALTH_OVERHEAT -> "Przegrzana"
            BatteryManager.BATTERY_HEALTH_DEAD -> "Zużyta"
            BatteryManager.BATTERY_HEALTH_COLD -> "Za zimna"
            else -> "Nieznana"
        }
        return BatteryInfo(level, temp, volt, charging, health)
    }

    private fun userApps(ctx: Context): List<ApplicationInfo> {
        val pm = ctx.packageManager
        return pm.getInstalledApplications(0).filter {
            it.packageName != ctx.packageName &&
                (it.flags and ApplicationInfo.FLAG_SYSTEM) == 0 &&
                (it.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) == 0
        }
    }

    private fun label(ctx: Context, ai: ApplicationInfo) = ctx.packageManager.getApplicationLabel(ai).toString()

    fun runAll(ctx: Context): List<Check> {
        val list = mutableListOf<Check>()
        val cr = ctx.contentResolver

        // 1. Animacje
        val anim = Settings.Global.getFloat(cr, Settings.Global.ANIMATOR_DURATION_SCALE, 1f)
        val trans = Settings.Global.getFloat(cr, Settings.Global.TRANSITION_ANIMATION_SCALE, 1f)
        val win = Settings.Global.getFloat(cr, Settings.Global.WINDOW_ANIMATION_SCALE, 1f)
        val animOk = anim <= 0.5f && trans <= 0.5f && win <= 0.5f
        list += Check("anim", "Szybsze animacje",
            if (animOk) "Animacje są przyspieszone (${win}x)" else "Animacje 1x – ustaw 0.5x, a telefon będzie działał wyraźnie szybciej",
            animOk, 15, false, "Pokaż jak")

        // 2. Jasność automatyczna
        val autoBright = Settings.System.getInt(cr, Settings.System.SCREEN_BRIGHTNESS_MODE, 0) == Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC
        list += Check("bright", "Automatyczna jasność",
            if (autoBright) "Włączona – ekran nie świeci mocniej niż trzeba" else "Wyłączona – ekran zużywa więcej baterii",
            autoBright, 10, true, "Włącz")

        // 3. Wygaszanie ekranu
        val timeout = Settings.System.getInt(cr, Settings.System.SCREEN_OFF_TIMEOUT, 30000)
        val toOk = timeout <= 60000
        list += Check("timeout", "Wygaszanie ekranu",
            "Ekran gaśnie po ${timeout / 1000} s" + if (toOk) "" else " – ustawię 30 s",
            toOk, 10, true, "Ustaw 30 s")

        // 4. Dźwięki i wibracje dotyku
        val touchSnd = Settings.System.getInt(cr, Settings.System.SOUND_EFFECTS_ENABLED, 0) == 1
        list += Check("touch", "Dźwięki dotyku",
            if (touchSnd) "Włączone – lekko zużywają baterię" else "Wyłączone",
            !touchSnd, 5, true, "Wyłącz")

        // 5. Wolne miejsce
        val st = StatFs(Environment.getDataDirectory().path)
        val freePct = (st.availableBytes * 100 / st.totalBytes).toInt()
        list += Check("storage", "Wolne miejsce",
            "Wolne $freePct% pamięci" + if (freePct < 15) " – za mało, telefon zwalnia" else "",
            freePct >= 15, 15, false, "Otwórz pamięć")

        // 6. RAM
        val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val mi = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
        val ramFree = (mi.availMem * 100 / mi.totalMem).toInt()
        list += Check("ram", "Pamięć RAM",
            "Wolne $ramFree% RAM" + if (ramFree < 20) " – zamknę aplikacje w tle" else "",
            ramFree >= 20, 10, true, "Zwolnij RAM")

        // 7. Nieużywane aplikacje
        val unused = unusedApps(ctx)
        list += Check("unused", "Nieużywane aplikacje",
            when {
                !hasUsageAccess(ctx) -> "Zezwól na dostęp do użycia, żeby je znaleźć"
                unused.isEmpty() -> "Brak – wszystkie aplikacje są używane"
                else -> "${unused.size} aplikacji nieotwieranych od ponad 30 dni zajmuje miejsce i działa w tle"
            },
            hasUsageAccess(ctx) && unused.size < 3, 15, false, "Przejrzyj", unused)

        // 8. Aplikacje bez ograniczeń baterii
        val pm = ctx.getSystemService(Context.POWER_SERVICE) as PowerManager
        val unrestricted = userApps(ctx).filter { pm.isIgnoringBatteryOptimizations(it.packageName) }
            .map { AppItem(it.packageName, label(ctx, it), "Działa w tle bez ograniczeń") }
        list += Check("battopt", "Aplikacje bez limitu baterii",
            if (unrestricted.isEmpty()) "Brak – system pilnuje wszystkich aplikacji"
            else "${unrestricted.size} aplikacji może bez przerwy działać w tle",
            unrestricted.size <= 2, 10, false, "Przejrzyj", unrestricted)

        // 9. Autostart
        val boot = bootApps(ctx)
        list += Check("boot", "Aplikacje startujące z telefonem",
            "${boot.size} aplikacji uruchamia się razem z telefonem",
            boot.size <= 8, 5, false, "Przejrzyj", boot)

        // 10. Temperatura
        val b = battery(ctx)
        list += Check("temp", "Temperatura",
            "Bateria: %.1f°C".format(b.temp) + if (b.temp > 40) " – za gorąco, telefon zwalnia" else "",
            b.temp <= 40, 5, false, "Ochłodź")

        return list
    }

    fun unusedApps(ctx: Context): List<AppItem> {
        if (!hasUsageAccess(ctx)) return emptyList()
        val usm = ctx.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        val now = System.currentTimeMillis()
        val month = 30L * 24 * 3600 * 1000
        val stats = usm.queryAndAggregateUsageStats(now - 3 * month, now)
        val pm = ctx.packageManager
        return userApps(ctx).mapNotNull { ai ->
            val installed = try { pm.getPackageInfo(ai.packageName, 0).firstInstallTime } catch (e: Exception) { now }
            if (now - installed < month) return@mapNotNull null
            val last = stats[ai.packageName]?.lastTimeUsed ?: 0L
            if (now - last < month) return@mapNotNull null
            val days = if (last > 0) "${(now - last) / (24 * 3600 * 1000)} dni temu" else "dawno lub nigdy"
            AppItem(ai.packageName, label(ctx, ai), "Ostatnio użyta: $days")
        }.sortedBy { it.label.lowercase() }
    }

    fun bootApps(ctx: Context): List<AppItem> {
        val pm = ctx.packageManager
        val users = userApps(ctx).map { it.packageName }.toSet()
        val res = pm.queryBroadcastReceivers(Intent(Intent.ACTION_BOOT_COMPLETED), PackageManager.MATCH_DISABLED_COMPONENTS)
        return res.map { it.activityInfo.packageName }.distinct().filter { it in users }.mapNotNull {
            try {
                val ai = pm.getApplicationInfo(it, 0)
                AppItem(it, label(ctx, ai), "Startuje po włączeniu telefonu")
            } catch (e: Exception) { null }
        }.sortedBy { it.label.lowercase() }
    }

    // ---------- Naprawy automatyczne ----------

    fun fix(ctx: Context, id: String): Boolean {
        val cr = ctx.contentResolver
        return when (id) {
            "bright" -> canWriteSettings(ctx) && Settings.System.putInt(cr, Settings.System.SCREEN_BRIGHTNESS_MODE, Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC)
            "timeout" -> canWriteSettings(ctx) && Settings.System.putInt(cr, Settings.System.SCREEN_OFF_TIMEOUT, 30000)
            "touch" -> canWriteSettings(ctx) && Settings.System.putInt(cr, Settings.System.SOUND_EFFECTS_ENABLED, 0)
            "ram" -> { freeRam(ctx); true }
            else -> false
        }
    }

    fun freeRam(ctx: Context): Long {
        val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val before = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }.availMem
        ctx.packageManager.getInstalledApplications(0)
            .filter { it.packageName != ctx.packageName && (it.flags and ApplicationInfo.FLAG_PERSISTENT) == 0 }
            .forEach { try { am.killBackgroundProcesses(it.packageName) } catch (e: Exception) {} }
        Thread.sleep(1000)
        val after = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }.availMem
        return (after - before).coerceAtLeast(0)
    }

    fun score(list: List<Check>): Int {
        val total = list.sumOf { it.weight }
        val got = list.filter { it.ok }.sumOf { it.weight }
        return if (total == 0) 100 else got * 100 / total
    }
}
