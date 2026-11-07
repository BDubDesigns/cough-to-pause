package dev.harness.coughtopause.platform

import android.content.Context
import dev.harness.coughtopause.core.CalibrationCodec
import dev.harness.coughtopause.core.CalibrationResult
import dev.harness.coughtopause.core.DetectorSettings
import dev.harness.coughtopause.core.PersistedSession
import dev.harness.coughtopause.core.SessionCodec
import dev.harness.coughtopause.core.SettingsCodec

/**
 * SharedPreferences-backed persistence. The checkpoint must survive process death, and the
 * harness deliberately persists operator counters too so a two-hour playtest is not lost when
 * Android kills the service.
 */
class SessionStore(context: Context) {

    private val prefs = context.applicationContext.getSharedPreferences("cough_to_pause", Context.MODE_PRIVATE)

    fun saveSession(session: PersistedSession) {
        prefs.edit().putString(KEY_SESSION, SessionCodec.encode(session)).apply()
    }

    fun loadSession(): PersistedSession? = SessionCodec.decode(prefs.getString(KEY_SESSION, null))

    fun clearSession() {
        prefs.edit().remove(KEY_SESSION).apply()
    }

    fun saveSettings(settings: DetectorSettings) {
        prefs.edit().putString(KEY_SETTINGS, SettingsCodec.encode(settings)).apply()
    }

    fun loadSettings(): DetectorSettings =
        SettingsCodec.decode(prefs.getString(KEY_SETTINGS, null)) ?: DetectorSettings()

    fun saveCalibration(result: CalibrationResult) {
        prefs.edit().putString(KEY_CALIBRATION, CalibrationCodec.encode(result)).apply()
    }

    fun loadCalibration(): CalibrationResult? = CalibrationCodec.decode(prefs.getString(KEY_CALIBRATION, null))

    fun saveOperatorCounters(falsePositives: Int, missedCoughs: Int) {
        prefs.edit().putInt(KEY_FALSE_POSITIVES, falsePositives).putInt(KEY_MISSED, missedCoughs).apply()
    }

    fun falsePositives(): Int = prefs.getInt(KEY_FALSE_POSITIVES, 0)

    fun missedCoughs(): Int = prefs.getInt(KEY_MISSED, 0)

    private companion object {
        const val KEY_SESSION = "session"
        const val KEY_SETTINGS = "settings"
        const val KEY_CALIBRATION = "calibration"
        const val KEY_FALSE_POSITIVES = "false_positives"
        const val KEY_MISSED = "missed_coughs"
    }
}
