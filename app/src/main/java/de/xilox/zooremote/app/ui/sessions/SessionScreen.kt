package de.xilox.zooremote.app.ui.sessions

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import de.xilox.zooremote.app.R
import de.xilox.zooremote.app.data.api.RemoteTaskInfo
import de.xilox.zooremote.app.data.api.WorkspaceInfo
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Session 9: session picker. Lists the task history (`GET /api/tasks`, newest first) — tapping a
 * row restores that session in VS Code (`POST /api/task/open`). The "Neue Session" button starts a
 * fresh session with free text (`POST /api/task/start`). Below, recently used workspaces
 * (`GET /api/workspaces`) can be opened in a new VS Code window.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SessionScreen(
	onBack: () -> Unit,
	viewModel: SessionViewModel = viewModel(),
) {
	val state by viewModel.uiState.collectAsState()
	val snackbarHostState = remember { SnackbarHostState() }

	// Surface action errors as a snackbar.
	var pendingSnackbar by remember { mutableStateOf<String?>(null) }
	if (state.actionError != null && pendingSnackbar == null) {
		pendingSnackbar = state.actionError
	}
	LaunchedEffect(pendingSnackbar) {
		val message = pendingSnackbar ?: return@LaunchedEffect
		snackbarHostState.showSnackbar(message)
		pendingSnackbar = null
	}

	var showNewSessionDialog by remember { mutableStateOf(false) }
	val busy = state.pendingTaskId != null || state.pendingWorkspacePath != null || state.startingNew

	Scaffold(
		topBar = {
			TopAppBar(
				title = { Text(stringResource(R.string.sessions_title)) },
				navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.cd_back)) } },
				actions = {
					// Session 9: start a new session with free text.
					Button(onClick = { showNewSessionDialog = true }, enabled = !state.loading && !busy) {
						Text(stringResource(R.string.btn_new_session), style = MaterialTheme.typography.labelLarge)
					}
				},
			)
		},
		snackbarHost = { SnackbarHost(snackbarHostState) },
	) { padding ->
		when {
			state.loading -> Box(modifier = Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
				CircularProgressIndicator()
			}

			state.loadError != null && state.tasks.isEmpty() -> Box(modifier = Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
				Text(state.loadError.orEmpty(), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(horizontal = 24.dp))
			}

			else -> LazyColumn(
				modifier = Modifier.fillMaxSize().padding(padding),
				contentPadding = PaddingValues(vertical = 8.dp),
				verticalArrangement = Arrangement.spacedBy(2.dp),
			) {
				if (state.tasks.isEmpty()) {
					item(key = "empty") {
						Text(stringResource(R.string.sessions_empty), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 16.dp, vertical = 24.dp))
					}
				} else {
					items(state.tasks.size, key = { index -> state.tasks[index].taskId }) { index ->
						val task = state.tasks[index]
						SessionRow(
							task = task,
							disabled = busy && state.pendingTaskId != task.taskId,
							showSpinner = state.pendingTaskId == task.taskId,
							onClick = { viewModel.openTask(task.taskId) { onBack() } },
						)
					}
				}

				if (state.workspaces.isNotEmpty()) {
					item(key = "workspaces-header") {
						Text(
							stringResource(R.string.workspaces_header),
							style = MaterialTheme.typography.labelLarge,
							color = MaterialTheme.colorScheme.onSurfaceVariant,
							modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
						)
					}
					items(state.workspaces.size, key = { index -> "ws-" + state.workspaces[index].path }) { index ->
						val workspace = state.workspaces[index]
						WorkspaceRow(
							workspace = workspace,
							disabled = busy && state.pendingWorkspacePath != workspace.path,
							showSpinner = state.pendingWorkspacePath == workspace.path,
							onClick = { viewModel.openWorkspace(workspace.path) },
						)
					}
				}
			}
		}
	}

	if (showNewSessionDialog) {
		NewSessionDialog(
			busy = state.startingNew,
			onDismiss = { showNewSessionDialog = false },
			onStart = { text ->
				showNewSessionDialog = false
				viewModel.startNew(text) { onBack() }
			},
		)
	}
}

/** One history entry: title + timestamp, mode/status as a small caption line. */
@Composable
private fun SessionRow(
	task: RemoteTaskInfo,
	disabled: Boolean,
	showSpinner: Boolean,
	onClick: () -> Unit,
) {
	Surface(color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f), modifier = Modifier.fillMaxWidth().clickable(enabled = !disabled, onClick = onClick)) {
		Row(
			modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
			verticalAlignment = Alignment.CenterVertically,
			horizontalArrangement = Arrangement.spacedBy(12.dp),
		) {
			Column(modifier = Modifier.weight(1f)) {
				Text(task.task.ifBlank { stringResource(R.string.task_untitled) }, style = MaterialTheme.typography.bodyLarge, maxLines = 2)
				val workspaceName = task.workspace?.takeIf { it.isNotBlank() }?.substringAfterLast('/')
				val caption = buildString {
					append(formatTimestamp(task.ts))
					workspaceName?.let { append(" · "); append(it) }
					task.mode?.let { append(" · "); append(it) }
					task.status?.let { append(" · "); append(statusLabel(it, LocalContext.current)) }
				}
				if (caption.isNotEmpty()) {
					Text(caption, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
				}
			}
			if (showSpinner) {
				CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
			}
		}
	}
}

@Composable
private fun WorkspaceRow(workspace: WorkspaceInfo, disabled: Boolean, showSpinner: Boolean, onClick: () -> Unit) {
	Surface(color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.25f), modifier = Modifier.fillMaxWidth().clickable(enabled = !disabled, onClick = onClick)) {
		Row(
			modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
			verticalAlignment = Alignment.CenterVertically,
			horizontalArrangement = Arrangement.spacedBy(12.dp),
		) {
			Column(modifier = Modifier.weight(1f)) {
				Text(workspace.name ?: workspace.path.substringAfterLast('/'), style = MaterialTheme.typography.bodyMedium, maxLines = 1)
				Text(workspace.path, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
			}
			if (showSpinner) {
				CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
			}
		}
	}
}

/** "Neue Session" dialog with a free-text field (session 9). */
@Composable
private fun NewSessionDialog(busy: Boolean, onDismiss: () -> Unit, onStart: (String) -> Unit) {
	var text by remember { mutableStateOf("") }
	AlertDialog(
		onDismissRequest = { if (!busy) onDismiss() },
		title = { Text(stringResource(R.string.dialog_new_session_title)) },
		text = {
			Column {
				Text(stringResource(R.string.dialog_new_session_text), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
				Spacer(Modifier.height(12.dp))
				OutlinedTextField(
					value = text,
					onValueChange = { text = it },
					placeholder = { Text(stringResource(R.string.dialog_new_session_placeholder)) },
					minLines = 2,
					maxLines = 4,
					keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
					keyboardActions = KeyboardActions(onDone = { if (text.isNotBlank() && !busy) onStart(text.trim()) }),
					modifier = Modifier.fillMaxWidth(),
				)
			}
		},
		confirmButton = {
			Button(onClick = { onStart(text.trim()) }, enabled = text.isNotBlank() && !busy) {
				Text(stringResource(R.string.btn_start))
			}
		},
		dismissButton = {
			// OutlinedButton/TextButton keep the dialog's M3 styling consistent.
			if (busy) {
				CircularProgressIndicator(modifier = Modifier.size(20.dp))
			} else {
				TextButton(onClick = onDismiss) { Text(stringResource(R.string.btn_cancel)) }
			}
		},
	)
}

/** Localized (device-locale) date + short time, e.g. "Sep 12, 2026, 2:35 PM" / "12.09.2026, 14:35". */
private val TIMESTAMP_FORMATTER: DateTimeFormatter = java.time.format.DateTimeFormatterBuilder()
	.appendLocalized(java.time.format.FormatStyle.MEDIUM, java.time.format.FormatStyle.SHORT)
	.toFormatter()

/** Local time of the task's start timestamp; blank when unknown. */
private fun formatTimestamp(ts: Long): String {
	if (ts <= 0) return ""
	return try {
		TIMESTAMP_FORMATTER.withZone(ZoneId.systemDefault()).format(Instant.ofEpochMilli(ts))
	} catch (e: Exception) {
		""
	}
}

private fun statusLabel(status: String, context: android.content.Context): String = when (status) {
	"active" -> context.getString(R.string.hist_active)
	"completed" -> context.getString(R.string.hist_completed)
	"delegated" -> context.getString(R.string.hist_delegated)
	"interrupted" -> context.getString(R.string.hist_interrupted)
	else -> status
}
