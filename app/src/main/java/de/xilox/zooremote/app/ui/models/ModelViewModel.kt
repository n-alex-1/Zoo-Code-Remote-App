package de.xilox.zooremote.app.ui.models

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import de.xilox.zooremote.app.ZooRemoteApp
import de.xilox.zooremote.app.data.api.ProfileInfo
import de.xilox.zooremote.app.data.api.TlsTrust
import de.xilox.zooremote.app.data.api.ZooApi
import de.xilox.zooremote.app.data.api.ZooApiException
import de.xilox.zooremote.app.data.connection.ConnectionRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** UI state of the provider-profile list (session 7). */
data class ModelUiState(
	/** Provider profiles from `GET /api/models`. */
	val profiles: List<ProfileInfo> = emptyList(),
	val loading: Boolean = true,
	/** Error while loading the profile list. */
	val loadError: String? = null,
	/** Name of the active provider profile — live from the WebSocket status. */
	val currentProfileName: String? = null,
	/** Model id of the active configuration — live from the WebSocket status (fallback highlight). */
	val currentModelId: String? = null,
	/** Optimistic selection in flight; rolled back on error. */
	val pendingProfileId: String? = null,
	/** User-facing error of the last failed switch (auto-clears; shown via snackbar). */
	val actionError: String? = null,
)

class ModelViewModel(application: Application) : AndroidViewModel(application) {

	private val repository: ConnectionRepository = ZooRemoteApp.from(application).connectionRepository

	private val profilesFlow = MutableStateFlow<List<ProfileInfo>>(emptyList())
	private val loadingFlow = MutableStateFlow(true)
	private val loadErrorFlow = MutableStateFlow<String?>(null)
	private val pendingFlow = MutableStateFlow<String?>(null)
	private val errorFlow = MutableStateFlow<String?>(null)
	private var errorClearJob: Job? = null

	/** Active profile name as seen through the shared live status (null while unknown). */
	private val currentProfileNameFlow: Flow<String?> = repository.status.map { it?.model?.profileName?.takeIf(String::isNotBlank) }
	/** Active model id — used to highlight a row when no profile name is reported. */
	private val currentModelIdFlow: Flow<String?> = repository.status.map { it?.model?.modelId?.takeIf(String::isNotBlank) }

	// Two-stage combine — kotlinx.coroutines.combine has no overload for more than five flows.
	val uiState: StateFlow<ModelUiState> = combine(
		combine(
			combine(profilesFlow, loadingFlow, loadErrorFlow) { profiles, loading, loadError -> Triple(profiles, loading, loadError) },
			currentProfileNameFlow,
			currentModelIdFlow,
		) { (profiles, loading, loadError), currentProfileName, currentModelId ->
			ModelUiState(
				profiles = profiles,
				loading = loading,
				loadError = loadError,
				currentProfileName = currentProfileName,
				currentModelId = currentModelId,
			)
		},
		pendingFlow,
		errorFlow,
	) { base, pendingProfileId, error ->
		base.copy(pendingProfileId = pendingProfileId, actionError = error)
	}.stateIn(viewModelScope, SharingStarted.Eagerly, ModelUiState())

	init {
		loadProfiles()
	}

	private fun loadProfiles() {
		viewModelScope.launch(Dispatchers.IO) {
			loadingFlow.value = true
			loadErrorFlow.value = null
			try {
				val settings = repository.savedSettings()
				if (!settings.isValid()) throw ZooApiException("Keine gültigen Verbindungseinstellungen.")
				profilesFlow.value = ZooApi(TlsTrust.client(settings.certFingerprint), settings.baseUrl(), settings.token).getModels().profiles
			} catch (e: ZooApiException) {
				val message = when (e.httpCode) {
					401 -> "Falscher Token (HTTP 401). Einstellungen prüfen und neu pairen."
					else -> e.message ?: "Profile konnten nicht geladen werden"
				}
				loadErrorFlow.value = message
				if (e.httpCode == 401) repository.reportAuthFailure(message) // session 8b: back to setup
			} catch (e: Exception) {
				loadErrorFlow.value = e.message ?: e::class.java.simpleName
			} finally {
				loadingFlow.value = false
			}
		}
	}

	/**
	 * Activates [profile] (`POST /api/model` with its id; the server applies the profile's stored
	 * configuration). Optimistic UI: the row is highlighted immediately, rolled back on error. On
	 * success the fresh `RemoteStatus` (contract) is adopted so all screens update instantly.
	 */
	fun select(profile: ProfileInfo) {
		if (pendingFlow.value != null) return
		viewModelScope.launch(Dispatchers.IO) {
			pendingFlow.value = profile.id
			errorClearJob?.cancel()
			errorFlow.value = null
			try {
				val settings = repository.savedSettings()
				if (!settings.isValid()) throw ZooApiException("Keine gültigen Verbindungseinstellungen.")
				val fresh = ZooApi(TlsTrust.client(settings.certFingerprint), settings.baseUrl(), settings.token).setModel(profile.id)
				fresh?.let { repository.adoptStatus(it) }
			} catch (e: ZooApiException) {
				val message = when (e.httpCode) {
					401 -> "Falscher Token (HTTP 401). Einstellungen prüfen und neu pairen."
					else -> e.message ?: "Profilwechsel fehlgeschlagen"
				}
				failAction(message)
				if (e.httpCode == 401) repository.reportAuthFailure(message) // session 8b: back to setup
			} catch (e: Exception) {
				failAction(e.message ?: e::class.java.simpleName)
			} finally {
				pendingFlow.value = null
			}
		}
	}

	private fun failAction(message: String) {
		errorFlow.value = message
		errorClearJob?.cancel()
		errorClearJob = viewModelScope.launch {
			delay(6_000L)
			if (errorFlow.value == message) errorFlow.value = null
		}
	}

	/** True when [profile] is the active one — by profile name, else by model id fallback. */
	fun isActive(profile: ProfileInfo): Boolean {
		val state = uiState.value
		return if (state.pendingProfileId != null) {
			state.pendingProfileId == profile.id || isActiveLive(state.currentProfileName, state.currentModelId, profile)
		} else {
			isActiveLive(state.currentProfileName, state.currentModelId, profile)
		}
	}

	private fun isActiveLive(currentProfileName: String?, currentModelId: String?, profile: ProfileInfo): Boolean =
		currentProfileName != null && currentProfileName == profile.name ||
			(currentProfileName == null && currentModelId != null && currentModelId == profile.modelId)
}
