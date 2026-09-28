package com.stampshot.app.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.stampshot.app.stamp.PrivacyLevel
import com.stampshot.app.stamp.StampStyle
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import org.json.JSONArray
import org.json.JSONObject

private val Context.settingsStore by preferencesDataStore(name = "stampshot_settings")

data class AppSettings(
    val style: StampStyle = StampStyle.MINIMAL_CORNER,
    val showAddress: Boolean = true,
    val showGps: Boolean = false,
    val note: String = "",
    val sessions: List<String> = listOf(DEFAULT_SESSION),
    val currentSession: String = DEFAULT_SESSION,
    val counters: Map<String, Int> = mapOf(DEFAULT_SESSION to 0),
    val flashMode: Int = FLASH_OFF,
    val lensFacingBack: Boolean = true,
    val shareLevel: PrivacyLevel = PrivacyLevel.FULL,
    val keepOriginals: Boolean = true,
) {
    fun nextNumber(): Int = (counters[currentSession] ?: 0) + 1

    companion object {
        const val DEFAULT_SESSION = "General"
        const val FLASH_OFF = 0
        const val FLASH_ON = 1
        const val FLASH_AUTO = 2
    }
}

class SettingsRepository(private val context: Context) {

    private object K {
        val STYLE = stringPreferencesKey("stamp_style")
        val SHOW_ADDRESS = booleanPreferencesKey("show_address")
        val SHOW_GPS = booleanPreferencesKey("show_gps")
        val NOTE = stringPreferencesKey("note")
        val SESSIONS = stringPreferencesKey("sessions_json")
        val CURRENT_SESSION = stringPreferencesKey("current_session")
        val COUNTERS = stringPreferencesKey("counters_json")
        val FLASH = intPreferencesKey("flash_mode")
        val LENS_BACK = booleanPreferencesKey("lens_back")
        val SHARE_LEVEL = stringPreferencesKey("share_level")
        val KEEP_ORIGINALS = booleanPreferencesKey("keep_originals")
    }

    val settings: Flow<AppSettings> = context.settingsStore.data.map { p ->
        AppSettings(
            style = p[K.STYLE]?.let { runCatching { StampStyle.valueOf(it) }.getOrNull() }
                ?: StampStyle.MINIMAL_CORNER,
            showAddress = p[K.SHOW_ADDRESS] ?: true,
            showGps = p[K.SHOW_GPS] ?: false,
            note = p[K.NOTE] ?: "",
            sessions = parseStringArray(p[K.SESSIONS]).ifEmpty { listOf(AppSettings.DEFAULT_SESSION) },
            currentSession = p[K.CURRENT_SESSION] ?: AppSettings.DEFAULT_SESSION,
            counters = parseCounterMap(p[K.COUNTERS]),
            flashMode = p[K.FLASH] ?: AppSettings.FLASH_OFF,
            lensFacingBack = p[K.LENS_BACK] ?: true,
            shareLevel = p[K.SHARE_LEVEL]?.let { runCatching { PrivacyLevel.valueOf(it) }.getOrNull() }
                ?: PrivacyLevel.FULL,
            keepOriginals = p[K.KEEP_ORIGINALS] ?: true,
        )
    }

    suspend fun current(): AppSettings = settings.first()

    suspend fun setStyle(style: StampStyle) = put(K.STYLE, style.name)
    suspend fun setShowAddress(v: Boolean) = put(K.SHOW_ADDRESS, v)
    suspend fun setShowGps(v: Boolean) = put(K.SHOW_GPS, v)
    suspend fun setNote(v: String) = put(K.NOTE, v.trim())
    suspend fun setFlashMode(v: Int) = put(K.FLASH, v)
    suspend fun setLensFacingBack(v: Boolean) = put(K.LENS_BACK, v)
    suspend fun setShareLevel(v: PrivacyLevel) = put(K.SHARE_LEVEL, v.name)
    suspend fun setKeepOriginals(v: Boolean) = put(K.KEEP_ORIGINALS, v)

    suspend fun setSession(name: String) {
        val clean = name.trim().ifBlank { AppSettings.DEFAULT_SESSION }
        context.settingsStore.edit { p ->
            val sessions = parseStringArray(p[K.SESSIONS]).toMutableList()
            if (clean !in sessions) sessions.add(clean)
            p[K.SESSIONS] = JSONArray(sessions).toString()
            p[K.CURRENT_SESSION] = clean
        }
    }

    /** Atomically increments and returns the next photo number for a session. */
    suspend fun nextNumber(session: String): Int {
        var next = 1
        context.settingsStore.edit { p ->
            val counters = parseCounterMap(p[K.COUNTERS]).toMutableMap()
            next = (counters[session] ?: 0) + 1
            counters[session] = next
            p[K.COUNTERS] = JSONObject(counters.mapValues { it.value }).toString()
        }
        return next
    }

    suspend fun renameSessionCountersIfNeeded(oldName: String, newName: String) {
        context.settingsStore.edit { p ->
            val counters = parseCounterMap(p[K.COUNTERS]).toMutableMap()
            counters[oldName]?.let { counters[newName] = it; counters.remove(oldName) }
            p[K.COUNTERS] = JSONObject(counters.mapValues { it.value }).toString()
        }
    }

    private suspend fun <T> put(key: androidx.datastore.preferences.core.Preferences.Key<T>, value: T) {
        context.settingsStore.edit { it[key] = value }
    }

    private fun parseStringArray(json: String?): List<String> {
        if (json.isNullOrBlank()) return emptyList()
        return runCatching {
            val arr = JSONArray(json)
            (0 until arr.length()).map { arr.getString(it) }
        }.getOrDefault(emptyList())
    }

    private fun parseCounterMap(json: String?): Map<String, Int> {
        if (json.isNullOrBlank()) return emptyMap()
        return runCatching {
            val obj = JSONObject(json)
            obj.keys().asSequence().associateWith { obj.getInt(it) }
        }.getOrDefault(emptyMap())
    }
}
