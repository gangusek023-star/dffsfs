package pl.turboclean

import android.app.AppOpsManager
import android.app.usage.StorageStatsManager
import android.content.Context
import android.content.pm.ApplicationInfo
import android.os.Build
import android.os.Environment
import android.os.Process
import android.os.storage.StorageManager
import java.io.File

class Category(
    val id: String,
    val title: String,
    var desc: String,
    var checked: Boolean,
) {
    val files = mutableListOf<File>()
    var size = 0L
    fun add(f: File, s: Long) { files.add(f); size += s }
}

data class AppCache(val pkg: String, val label: String, val bytes: Long)

/** Skanuje pamięć współdzieloną w poszukiwaniu zbędnych plików. */
object JunkScanner {

    private val protectedTopDirs = setOf(
        "Android", "DCIM", "Download", "Pictures", "Music", "Movies", "Documents",
        "Alarms", "Notifications", "Podcasts", "Ringtones", "Recordings", "Audiobooks"
    )
    private const val LARGE = 100L * 1024 * 1024

    fun scan(onProgress: (String) -> Unit): List<Category> {
        val tmp = Category("tmp", "Pliki tymczasowe i logi", "Pliki .tmp, .temp, .log zostawione przez aplikacje", true)
        val thumbs = Category("thumbs", "Miniatury", "Foldery .thumbnails – odtworzą się same w razie potrzeby", true)
        val apks = Category("apk", "Pliki instalacyjne APK", "Instalatory aplikacji, które już nie są potrzebne", true)
        val empty = Category("empty", "Puste foldery", "Foldery bez żadnych plików", true)
        val trash = Category("trash", "Kosz", "Usunięte zdjęcia i filmy czekające 30 dni na skasowanie", false)
        val large = Category("large", "Duże pliki (ponad 100 MB)", "", false)

        val root = Environment.getExternalStorageDirectory()
        var count = 0

        fun walk(dir: File, depth: Int): Boolean { // zwraca true, jeśli folder jest pusty
            if (depth > 14) return false
            if (depth == 1 && dir.name == "Android") return false
            val children = dir.listFiles() ?: return false
            var allEmpty = true
            for (f in children) {
                if (f.isDirectory) {
                    if (f.name == ".thumbnails") {
                        val s = dirSize(f)
                        if (s > 0) thumbs.add(f, s)
                        allEmpty = false
                        continue
                    }
                    val isEmpty = walk(f, depth + 1)
                    if (isEmpty && !(depth == 0 && f.name in protectedTopDirs)) {
                        empty.add(f, 0)
                    } else allEmpty = false
                } else {
                    allEmpty = false
                    val n = f.name.lowercase()
                    val s = f.length()
                    when {
                        n.startsWith(".trashed-") -> trash.add(f, s)
                        n.endsWith(".tmp") || n.endsWith(".temp") || n.endsWith(".log") -> tmp.add(f, s)
                        n.endsWith(".apk") -> apks.add(f, s)
                        s >= LARGE -> large.add(f, s)
                    }
                    if (++count % 300 == 0) onProgress("Przeskanowano $count plików…")
                }
            }
            return allEmpty
        }
        walk(root, 0)

        large.files.sortByDescending { it.length() }
        large.desc = if (large.files.isEmpty()) "Brak" else
            large.files.take(5).joinToString("\n") { "• ${it.name} (${fmt(it.length())})" } +
                if (large.files.size > 5) "\n…i ${large.files.size - 5} więcej" else ""

        return listOf(tmp, thumbs, apks, empty, trash, large)
    }

    fun delete(cats: List<Category>): Long {
        var freed = 0L
        for (c in cats.filter { it.checked }) {
            for (f in c.files) {
                val s = if (f.isDirectory) dirSize(f) else f.length()
                val ok = if (f.isDirectory) f.deleteRecursively() else f.delete()
                if (ok) freed += s
            }
        }
        return freed
    }

    fun dirSize(d: File): Long = d.walkTopDown().filter { it.isFile }.sumOf { it.length() }

    // ---------- Pamięć podręczna aplikacji ----------

    fun hasUsageAccess(ctx: Context): Boolean {
        val ops = ctx.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        val mode = if (Build.VERSION.SDK_INT >= 29)
            ops.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), ctx.packageName)
        else @Suppress("DEPRECATION")
            ops.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), ctx.packageName)
        return mode == AppOpsManager.MODE_ALLOWED
    }

    fun appCaches(ctx: Context): List<AppCache> {
        if (!hasUsageAccess(ctx)) return emptyList()
        val ssm = ctx.getSystemService(Context.STORAGE_STATS_SERVICE) as StorageStatsManager
        val pm = ctx.packageManager
        val user = Process.myUserHandle()
        return pm.getInstalledApplications(0)
            .filter { it.packageName != ctx.packageName }
            .filter { (it.flags and ApplicationInfo.FLAG_SYSTEM) == 0 || pm.getLaunchIntentForPackage(it.packageName) != null }
            .mapNotNull {
                try {
                    val st = ssm.queryStatsForPackage(StorageManager.UUID_DEFAULT, it.packageName, user)
                    AppCache(it.packageName, pm.getApplicationLabel(it).toString(), st.cacheBytes)
                } catch (e: Exception) { null }
            }
            .filter { it.bytes > 512 * 1024 }
            .sortedByDescending { it.bytes }
    }

    fun fmt(b: Long): String = when {
        b >= 1L shl 30 -> "%.2f GB".format(b / (1L shl 30).toFloat())
        b >= 1L shl 20 -> "%.1f MB".format(b / (1L shl 20).toFloat())
        b >= 1024 -> "${b / 1024} KB"
        else -> "$b B"
    }
}
