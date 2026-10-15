package io.github.jqssun.btgps

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.jqssun.btgps.iap2.IdentificationInformation
import io.github.jqssun.btgps.iap2.CustomLocation
import io.github.jqssun.btgps.iap2.FixDetail
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

private val Context.dataStore by preferencesDataStore(name = "settings")

const val DEFAULT_SOFT_AUTH_APK_URL = "https://diplay.org/download/official/"

fun softAuthApkUrl(text: String): String = text.trim().ifEmpty { DEFAULT_SOFT_AUTH_APK_URL }

@Singleton
class SettingsStore @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    val mac: Flow<String> = context.dataStore.data.map { it[MAC] ?: "" }

    val identification: Flow<IdentificationInformation> = context.dataStore.data.map { prefs ->
        prefs[IDENTIFICATION]?.let { json.decodeFromString<IdentificationInformation>(it) } ?: IdentificationInformation()
    }

    val source: Flow<LocationSource> = context.dataStore.data.map { prefs ->
        LocationSource.entries.firstOrNull { it.name == prefs[SOURCE] } ?: LocationSource.Synthesize
    }

    val customLocation: Flow<CustomLocation> = context.dataStore.data.map { prefs ->
        prefs[CUSTOM_LOCATION]?.let { json.decodeFromString<CustomLocation>(it) } ?: CustomLocation()
    }

    val fixDetail: Flow<FixDetail> = context.dataStore.data.map { prefs ->
        prefs[FIX_DETAIL]?.let { json.decodeFromString<FixDetail>(it) } ?: FixDetail()
    }

    val nmeaLogging: Flow<Boolean> = context.dataStore.data.map { it[NMEA_LOGGING] ?: false }

    val developer: Flow<Boolean> = context.dataStore.data.map { it[DEVELOPER] ?: false }

    val softAuthApkUrl: Flow<String> = context.dataStore.data.map { it[SOFT_AUTH_APK_URL] ?: DEFAULT_SOFT_AUTH_APK_URL }

    suspend fun setIdentification(value: IdentificationInformation, mac: String) {
        context.dataStore.edit {
            it[IDENTIFICATION] = json.encodeToString(value)
            it[MAC] = mac.trim()
        }
    }

    suspend fun setSource(value: LocationSource) {
        context.dataStore.edit { it[SOURCE] = value.name }
    }

    suspend fun setLocation(custom: CustomLocation, detail: FixDetail) {
        context.dataStore.edit {
            it[CUSTOM_LOCATION] = json.encodeToString(custom)
            it[FIX_DETAIL] = json.encodeToString(detail)
        }
    }

    suspend fun setDeveloper(value: Boolean) {
        context.dataStore.edit { it[DEVELOPER] = value }
    }

    suspend fun setNmeaLogging(value: Boolean) {
        context.dataStore.edit { it[NMEA_LOGGING] = value }
    }

    suspend fun setSoftAuthApkUrl(value: String) {
        val url = softAuthApkUrl(value)
        context.dataStore.edit { if (url == DEFAULT_SOFT_AUTH_APK_URL) it.remove(SOFT_AUTH_APK_URL) else it[SOFT_AUTH_APK_URL] = url }
    }

    private companion object {
        val MAC = stringPreferencesKey("bluetooth_mac")
        val IDENTIFICATION = stringPreferencesKey("identification_information")
        val SOURCE = stringPreferencesKey("location_source")
        val CUSTOM_LOCATION = stringPreferencesKey("custom_location")
        val FIX_DETAIL = stringPreferencesKey("fix_detail")
        val SOFT_AUTH_APK_URL = stringPreferencesKey("soft_auth_apk_url")
        val NMEA_LOGGING = booleanPreferencesKey("logging")
        val DEVELOPER = booleanPreferencesKey("developer_options")

        /* fields left at default follow code defaults */
        val json = Json { encodeDefaults = false; ignoreUnknownKeys = true }
    }
}

fun parseMac(text: String): ByteArray? {
    val hex = text.filter { it.isDigit() || it in 'a'..'f' || it in 'A'..'F' }
    if (hex.length != 12) return null
    return hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
}
