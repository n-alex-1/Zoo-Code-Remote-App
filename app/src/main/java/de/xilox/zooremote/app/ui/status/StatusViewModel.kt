package de.xilox.zooremote.app.ui.status

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import de.xilox.zooremote.app.ZooRemoteApp
import de.xilox.zooremote.app.data.api.ActivityItem
import de.xilox.zooremote.app.data.api.RemoteStatus
import de.xilox.zooremote.app.data.connection.ConnectionRepository
import de.xilox.zooremote.app.data.connection.ConnectionState
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** UI state of the status screen (session 5). */
data class StatusUiState(
	val connectionState: ConnectionState = ConnectionState.Disconnected,
	/** Latest `GET /api/status` / WS `status` snapshot. */
	val lastStatus: RemoteStatus? = null,
	/** Chat-style activity feed; entries with the same ts replace each other (streaming). */
	val activity: List<ActivityItem> = emptyList(),
)

class StatusViewModel(application: Application) : AndroidViewModel(application) {

	private val repository: ConnectionRepository = ZooRemoteApp.from(application).connectionRepository

	/** Combined, hoisted connection state — the single source of truth for [StatusScreen]. */
	val uiState: StateFlow<StatusUiState> = combine(
		repository.connectionState,
		repository.status,
		repository.activity,
	) { connection, status, activity ->
		StatusUiState(connectionState = connection, lastStatus = status, activity = activity)
	}.stateIn(viewModelScope, SharingStarted.Eagerly, StatusUiState())

	init {
		// Auto-connect on start when valid settings were saved (e.g. after the setup flow).
		viewModelScope.launch {
			val settings = repository.savedSettings()
			if (settings.isValid()) repository.connect(settings)
		}
	}
}
