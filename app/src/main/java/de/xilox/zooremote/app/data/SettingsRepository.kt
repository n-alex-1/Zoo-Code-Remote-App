package de.xilox.zooremote.app.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.settingsDataStore by preferencesDataStore(name = "connection")

/** DataStore-backed persistence for [ConnectionSettings]. */
class SettingsRepository(private val context: Context) {

	private object Keys {
		val HOST = stringPreferencesKey("host")
		val PORT = intPreferencesKey("port")
		val TOKEN = stringPreferencesKey("token")
		val FINGERPRINT = stringPreferencesKey("cert_fingerprint")
	}

	fun observe(): Flow<ConnectionSettings> = context.settingsDataStore.data.map { prefs ->
		ConnectionSettings(
			host = prefs[Keys.HOST].orEmpty(),
			port = prefs[Keys.PORT] ?: 8999,
			token = prefs[Keys.TOKEN].orEmpty(),
			certFingerprint = prefs[Keys.FINGERPRINT].orEmpty(),
		)
	}

	suspend fun save(settings: ConnectionSettings) {
		context.settingsDataStore.edit { prefs ->
			prefs[Keys.HOST] = settings.host.trim()
			prefs[Keys.PORT] = settings.port
			prefs[Keys.TOKEN] = settings.token.trim()
			prefs[Keys.FINGERPRINT] = settings.certFingerprint.trim().lowercase()
		}
	}
}
