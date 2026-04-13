package moe.reimu.catshare

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import moe.reimu.catshare.utils.DeviceName

class AppSettings(private val context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("app", Context.MODE_PRIVATE)

    var deviceName: String
        get() = prefs.getString("deviceName", null)
            ?: DeviceName.get()
        set(value) {
            prefs.edit { putString("deviceName", value) }
        }

    var verbose: Boolean
        get() = prefs.getBoolean("verbose", false)
        set(value) {
            prefs.edit { putBoolean("verbose", value) }
        }

    var autoAccept: Boolean
        get() = prefs.getBoolean("autoAccept", false)
        set(value) {
            prefs.edit { putBoolean("autoAccept", value) }
        }

    var autoShutdownMode: Int
        get() = prefs.getInt("autoShutdownMode", 0)
        set(value) {
            prefs.edit { putInt("autoShutdownMode", value) }
        }

    var autoShutdownMinutes: Int
        get() = prefs.getInt("autoShutdownMinutes", 30)
        set(value) {
            prefs.edit { putInt("autoShutdownMinutes", value) }
        }

    var autoShutdownCount: Int
        get() = prefs.getInt("autoShutdownCount", 10)
        set(value) {
            prefs.edit { putInt("autoShutdownCount", value) }
        }
}