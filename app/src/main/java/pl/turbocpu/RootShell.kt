package pl.turbocpu

import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.concurrent.TimeUnit

/** Wykonuje polecenia z uprawnieniami root (su). */
object RootShell {

    fun hasRoot(): Boolean = run("id").contains("uid=0")

    /** Uruchamia polecenie jako root i zwraca wyjście (stdout + stderr). */
    fun run(cmd: String): String {
        return try {
            val p = ProcessBuilder("su", "-c", cmd).redirectErrorStream(true).start()
            val out = BufferedReader(InputStreamReader(p.inputStream)).readText()
            p.waitFor(10, TimeUnit.SECONDS)
            out.trim()
        } catch (e: Exception) {
            ""
        }
    }

    /** Odczyt pliku bez roota (sysfs często jest czytelny), z rootem jako zapas. */
    fun read(path: String): String {
        return try {
            java.io.File(path).readText().trim()
        } catch (e: Exception) {
            run("cat $path")
        }
    }
}
