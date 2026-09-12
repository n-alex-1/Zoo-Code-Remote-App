package de.xilox.zooremote.app.ui.sessions

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import de.xilox.zooremote.app.ZooRemoteApp
import de.xilox.zooremote.app.data.api.RemoteTaskInfo
import de.xilox.zooremote.app.data.api.TlsTrust
import de.xilox.zooremote.app.data.api.WorkspaceInfo
import de.xilox.zooremote.app.data.api.ZooApi
import de.xilox.zooremote.app.data.api.ZooApiException
import de.xilox.zooremote.app.data.connection.ConnectionRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** UI state of the sessions screen (session 9). */
data class SessionUiState(
	/** Task history from `GET /api/tasks` — newest first. */
	val tasks: List<RemoteTaskInfo> = emptyList(),
	/** Recently used workspaces from `GET /api/workspaces`. */
	val workspaces: List<WorkspaceInfo> = emptyList(),
	val loading: Boolean = true,
	/** Error while loading the lists (auto-clears). */
	val loadError: String? = null,
	/** Task id currently being opened/restored. */
	val pendingTaskId: String? = null,
	/** Workspace path currently being opened (session 9). */
	val pendingWorkspacePath: String? = null,
	/** True while "new session" is in flight. */
	val startingNew: Boolean = false,
	/** User-facing error of the last failed action (auto-clears; shown via snackbar). */
	val actionError: String? = null,
)

class SessionViewModel(application: Application) : AndroidViewModel(application) {

	private val repository: ConnectionRepository = ZooRemoteApp.from(application).connectionRepository

	private val tasksFlow = MutableStateFlow<List<RemoteTaskInfo>>(emptyList())
	private val workspacesFlow = MutableStateFlow<List<WorkspaceInfo>>(emptyList())
	private val loadingFlow = MutableStateFlow(true)
	private val loadErrorFlow = MutableStateFlow<String?>(null)
	private val pendingTaskFlow = MutableStateFlow<String?>(null)
	private val pendingWorkspaceFlow = MutableStateFlow<String?>(null)
	private val startingNewFlow = MutableStateFlow(false)
	private val errorFlow = MutableStateFlow<String?>(null)
	private var errorClearJob: Job? = null

	val uiState: StateFlow<SessionUiState> = combine(
		tasksFlow,
		workspacesFlow,
		pendingTaskFlow,
		pendingWorkspaceFlow,
		startingNewFlow,
	) { tasks, workspaces, pendingTaskId, pendingWorkspacePath, startingNew ->
		SessionUiState(tasks = tasks, workspaces = workspaces, pendingTaskId = pendingTaskId, pendingWorkspacePath = pendingWorkspacePath, startingNew = startingNew)
	}.combine(combine(loadingFlow, loadErrorFlow) { loading, loadError -> Pair(loading, loadError) }) { base, (loading, loadError) ->
		base.copy(loading = loading, loadError = loadError)
	}.combine(errorFlow) { base, error -> base.copy(actionError = error) }
		.stateIn(viewModelScope, SharingStarted.Eagerly, SessionUiState())

	init {
		loadAll(repository.activeWorkspace.value)
		// Session 9: the extension host's workspace is only known once a status arrived — when it
		// shows up while this screen is open, re-fetch the per-workspace history.
		viewModelScope.launch {
			repository.activeWorkspace.collect { workspace ->
				if (workspace != loadedWorkspace) {
					loadedWorkspace = workspace
					loadAll(workspace)
				}
			}
		}
	}

	@Volatile
	private var loadedWorkspace: String? = null

	private fun loadAll(workspace: String?) {
		viewModelScope.launch(Dispatchers.IO) {
			loadingFlow.value = true
			loadErrorFlow.value = null
			try {
				val settings = repository.savedSettings()
				if (!settings.isValid()) throw ZooApiException("Keine gültigen Verbindungseinstellungen.")
				val api = ZooApi(TlsTrust.client(settings.certFingerprint), settings.baseUrl(), settings.token)
				// Per-workspace history (same comparison as the webview's own list); null = global.
				tasksFlow.value = api.getTasks(workspace)
				workspacesFlow.value = api.getWorkspaces()
			} catch (e: ZooApiException) {
				loadErrorFlow.value = when (e.httpCode) {
					401 -> "Falscher Token (HTTP 401). Einstellungen prüfen und neu pairen."
					else -> e.message ?: "Sessions konnten nicht geladen werden"
				}
				if (e.httpCode == 401) repository.reportAuthFailure(loadErrorFlow.value.orEmpty()) // session 8b: back to setup
			} catch (e: Exception) {
				loadErrorFlow.value = e.message ?: e::class.java.simpleName
			} finally {
				loadingFlow.value = false
			}
		}
	}

	private fun isBusy(): Boolean = pendingTaskFlow.value != null || pendingWorkspaceFlow.value != null || startingNewFlow.value

	private fun runSessionAction(
		onStart: () -> Unit,
		finish: (Boolean) -> Unit,
		action: (ZooApi) -> Unit,
	) {
		viewModelScope.launch(Dispatchers.IO) {
			if (isBusy()) return@launch
			onStart()
			errorClearJob?.cancel()
			errorFlow.value = null
			var success = false
			try {
				val settings = repository.savedSettings()
				if (!settings.isValid()) throw ZooApiException("Keine gültigen Verbindungseinstellungen.")
				action(ZooApi(TlsTrust.client(settings.certFingerprint), settings.baseUrl(), settings.token))
				success = true
			} catch (e: ZooApiException) {
				val message = when (e.httpCode) {
					401 -> "Falscher Token (HTTP 401). Einstellungen prüfen und neu pairen."
					else -> e.message ?: "Aktion fehlgeschlagen"
				}
				failAction(message)
				if (e.httpCode == 401) repository.reportAuthFailure(message) // session 8b: back to setup
			} catch (e: Exception) {
				failAction(e.message ?: e::class.java.simpleName)
			} finally {
				// `finish` may navigate (popBackStack) — that needs the UI thread, this runs on IO.
				withContext(Dispatchers.Main.immediate) { finish(success) }
			}
		}
	}

	/** Restores a session from history (`POST /api/task/open`); the caller navigates back on success. */
	fun openTask(taskId: String, onSuccess: () -> Unit = {}) {
		runSessionAction(
			onStart = { pendingTaskFlow.value = taskId },
			finish = { success ->
				pendingTaskFlow.value = null
				if (success) onSuccess()
			},
		) { api ->
			val fresh = api.openTask(taskId)
			fresh?.let(repository::adoptStatus)
		}
	}

	/** Starts a new session with the given text (`POST /api/task/start`). */
	fun startNew(text: String, onSuccess: () -> Unit = {}) {
		runSessionAction(
			onStart = { startingNewFlow.value = true },
			finish = { success ->
				startingNewFlow.value = false
				if (success) onSuccess()
			},
		) { api ->
			val fresh = api.startTask(text)
			fresh?.let(repository::adoptStatus)
		}
	}

	/** Opens a workspace in a new VS Code window (`POST /api/workspace/open`). */
	fun openWorkspace(workspacePath: String, onSuccess: () -> Unit = {}) {
		runSessionAction(
			onStart = { pendingWorkspaceFlow.value = workspacePath },
			finish = { success ->
				pendingWorkspaceFlow.value = null
				if (success) onSuccess()
			},
		) { api -> api.openWorkspace(workspacePath) }
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
