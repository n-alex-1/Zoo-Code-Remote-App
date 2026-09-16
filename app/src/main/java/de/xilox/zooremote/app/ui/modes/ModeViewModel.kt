package de.xilox.zooremote.app.ui.modes

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import de.xilox.zooremote.app.ZooRemoteApp
import de.xilox.zooremote.app.R
import de.xilox.zooremote.app.data.api.ModeInfo
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

/** UI state of the mode list (session 7). */
data class ModeUiState(
	/** All modes from `GET /api/modes`. */
	val modes: List<ModeInfo> = emptyList(),
	val loading: Boolean = true,
	/** Error while loading the mode list. */
	val loadError: String? = null,
	/** Active mode slug — live from the WebSocket status (authoritative after success). */
	val currentSlug: String? = null,
	/** Optimistic selection in flight; the row renders as active until confirmed or rolled back. */
	val pendingSlug: String? = null,
	/** User-facing error of the last failed switch (auto-clears; shown via snackbar). */
	val actionError: String? = null,
)

class ModeViewModel(application: Application) : AndroidViewModel(application) {

	private val repository: ConnectionRepository = ZooRemoteApp.from(application).connectionRepository

	/** Locale-aware string lookup (errors are built on IO threads, not composables). */
	private fun tr(@androidx.annotation.StringRes resId: Int): String = getApplication<Application>().getString(resId)

	private val modesFlow = MutableStateFlow<List<ModeInfo>>(emptyList())
	private val loadingFlow = MutableStateFlow(true)
	private val loadErrorFlow = MutableStateFlow<String?>(null)
	private val pendingFlow = MutableStateFlow<String?>(null)
	private val errorFlow = MutableStateFlow<String?>(null)
	private var errorClearJob: Job? = null

	/** Current mode slug as seen through the shared live status (null while unknown). */
	private val currentSlugFlow: Flow<String?> = repository.status.map { it?.mode?.current?.takeIf(String::isNotBlank) }

	// Two-stage combine — kotlinx.coroutines.combine has no overload for more than five flows.
	val uiState: StateFlow<ModeUiState> = combine(
		combine(modesFlow, loadingFlow, loadErrorFlow) { modes, loading, loadError -> Triple(modes, loading, loadError) },
		currentSlugFlow,
		pendingFlow,
		errorFlow,
	) { (modes, loading, loadError), currentSlug, pendingSlug, error ->
		ModeUiState(modes = modes, loading = loading, loadError = loadError, currentSlug = currentSlug, pendingSlug = pendingSlug, actionError = error)
	}.stateIn(viewModelScope, SharingStarted.Eagerly, ModeUiState())

	init {
		loadModes()
	}

	private fun loadModes() {
		viewModelScope.launch(Dispatchers.IO) {
			loadingFlow.value = true
			loadErrorFlow.value = null
			try {
				val settings = repository.savedSettings()
				if (!settings.isValid()) throw ZooApiException(tr(R.string.err_invalid_settings))
				modesFlow.value = ZooApi(TlsTrust.client(settings.certFingerprint), settings.baseUrl(), settings.token).getModes().modes
			} catch (e: ZooApiException) {
				val message = if (e.httpCode == 401) tr(R.string.err_wrong_token) else e.message ?: tr(R.string.err_load_modes_failed)
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
	 * Switches to [slug] with optimistic UI: the row is highlighted immediately, rolled back on
	 * error. On success the server's fresh `RemoteStatus` (contract) is adopted so every screen
	 * updates instantly; the WebSocket confirms shortly afterwards.
	 */
	fun select(slug: String) {
		if (pendingFlow.value != null) return
		viewModelScope.launch(Dispatchers.IO) {
			pendingFlow.value = slug
			errorClearJob?.cancel()
			errorFlow.value = null
			try {
				val settings = repository.savedSettings()
				if (!settings.isValid()) throw ZooApiException(tr(R.string.err_invalid_settings))
				val fresh = ZooApi(TlsTrust.client(settings.certFingerprint), settings.baseUrl(), settings.token).setMode(slug)
				fresh?.let { repository.adoptStatus(it) }
			} catch (e: ZooApiException) {
				val message = if (e.httpCode == 401) tr(R.string.err_wrong_token) else e.message ?: tr(R.string.err_mode_switch_failed)
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
}
