package pl.turbocpu

import java.io.File

/**
 * Sterowanie procesorem przez sysfs (/sys/devices/system/cpu).
 * Wymaga roota. Częstotliwości ponad fabryczne maksimum są możliwe
 * tylko z kernelem, który ma odblokowane wyższe stopnie (custom kernel).
 */
object CpuManager {

    private const val CPU_DIR = "/sys/devices/system/cpu"

    enum class Profile(val label: String) {
        SAVE("Oszczędny"),
        BALANCED("Zrównoważony"),
        PERFORMANCE("Wydajność"),
        MAX("Turbo (maks.)")
    }

    val cores: List<Int> by lazy {
        File(CPU_DIR).listFiles()
            ?.mapNotNull { Regex("^cpu(\\d+)$").find(it.name)?.groupValues?.get(1)?.toInt() }
            ?.sorted() ?: emptyList()
    }

    private fun freqPath(cpu: Int, file: String) = "$CPU_DIR/cpu$cpu/cpufreq/$file"

    fun curFreq(cpu: Int): Long = RootShell.read(freqPath(cpu, "scaling_cur_freq")).toLongOrNull() ?: 0
    fun hwMax(cpu: Int): Long = RootShell.read(freqPath(cpu, "cpuinfo_max_freq")).toLongOrNull() ?: 0
    fun hwMin(cpu: Int): Long = RootShell.read(freqPath(cpu, "cpuinfo_min_freq")).toLongOrNull() ?: 0
    fun governor(cpu: Int): String = RootShell.read(freqPath(cpu, "scaling_governor"))

    fun availableFreqs(cpu: Int): List<Long> =
        RootShell.read(freqPath(cpu, "scaling_available_frequencies"))
            .split(" ").mapNotNull { it.trim().toLongOrNull() }.sorted()

    fun availableGovernors(cpu: Int): List<String> =
        RootShell.read(freqPath(cpu, "scaling_available_governors"))
            .split(" ").filter { it.isNotBlank() }

    /** Najwyższa temperatura spośród stref termicznych, w °C. */
    fun temperature(): Float {
        val zones = File("/sys/class/thermal").listFiles { f -> f.name.startsWith("thermal_zone") } ?: return 0f
        var max = 0f
        for (z in zones) {
            val raw = RootShell.read("${z.path}/temp").toFloatOrNull() ?: continue
            val c = if (raw > 1000) raw / 1000f else raw
            if (c in 1f..150f && c > max) max = c
        }
        return max
    }

    /** Ustawia profil na wszystkich rdzeniach. maxPercent: 50–100 % fabrycznego maksimum. */
    fun applyProfile(profile: Profile, maxPercent: Int = 100): Boolean {
        val sb = StringBuilder()
        for (cpu in cores) {
            sb.append("echo 1 > $CPU_DIR/cpu$cpu/online 2>/dev/null; ")
            val govs = availableGovernors(cpu)
            val gov = when (profile) {
                Profile.SAVE -> pick(govs, "powersave", "conservative", "schedutil")
                Profile.BALANCED -> pick(govs, "schedutil", "interactive", "ondemand")
                Profile.PERFORMANCE -> pick(govs, "schedutil", "interactive", "ondemand")
                Profile.MAX -> pick(govs, "performance", "schedutil")
            }
            val max = hwMax(cpu)
            val min = hwMin(cpu)
            val target = when (profile) {
                Profile.SAVE -> max * 60 / 100
                else -> max * maxPercent.coerceIn(50, 100) / 100
            }
            val maxFreq = nearestFreq(cpu, target, max)
            val minFreq = when (profile) {
                Profile.PERFORMANCE -> nearestFreq(cpu, max * 50 / 100, max)
                Profile.MAX -> maxFreq
                else -> min
            }
            if (gov != null) sb.append("echo $gov > ${freqPath(cpu, "scaling_governor")}; ")
            sb.append("echo $maxFreq > ${freqPath(cpu, "scaling_max_freq")}; ")
            sb.append("echo $minFreq > ${freqPath(cpu, "scaling_min_freq")}; ")
        }
        RootShell.run(sb.toString())
        return true
    }

    private fun pick(list: List<String>, vararg pref: String): String? =
        pref.firstOrNull { it in list } ?: list.firstOrNull()

    private fun nearestFreq(cpu: Int, target: Long, fallback: Long): Long {
        val freqs = availableFreqs(cpu)
        if (freqs.isEmpty()) return if (target > 0) target else fallback
        return freqs.lastOrNull { it <= target } ?: freqs.first()
    }

    fun fmt(khz: Long): String = if (khz >= 1_000_000) "%.2f GHz".format(khz / 1_000_000f) else "${khz / 1000} MHz"
}
