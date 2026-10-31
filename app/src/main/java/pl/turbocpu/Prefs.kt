package pl.turbocpu

import android.content.Context

class Prefs(ctx: Context) {
    private val sp = ctx.getSharedPreferences("turbocpu", Context.MODE_PRIVATE)

    var protectedApps: Set<String>
        get() = sp.getStringSet("protected", emptySet()) ?: emptySet()
        set(v) = sp.edit().putStringSet("protected", v).apply()

    var boostApps: Set<String>
        get() = sp.getStringSet("boost", emptySet()) ?: emptySet()
        set(v) = sp.edit().putStringSet("boost", v).apply()

    var autoRestart: Boolean
        get() = sp.getBoolean("autoRestart", true)
        set(v) = sp.edit().putBoolean("autoRestart", v).apply()

    var maxPercent: Int
        get() = sp.getInt("maxPercent", 100)
        set(v) = sp.edit().putInt("maxPercent", v).apply()

    var tempLimit: Int
        get() = sp.getInt("tempLimit", 75)
        set(v) = sp.edit().putInt("tempLimit", v).apply()

    var defaultProfile: String
        get() = sp.getString("defaultProfile", CpuManager.Profile.BALANCED.name)!!
        set(v) = sp.edit().putString("defaultProfile", v).apply()

    var guardEnabled: Boolean
        get() = sp.getBoolean("guard", false)
        set(v) = sp.edit().putBoolean("guard", v).apply()
}
