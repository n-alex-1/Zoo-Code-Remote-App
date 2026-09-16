package de.xilox.zooremote.app.ui.status

import android.content.Context
import android.view.ContextThemeWrapper
import android.widget.TextView
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.viewmodel.compose.viewModel
import de.xilox.zooremote.app.R
import de.xilox.zooremote.app.data.api.ActivityItem
import de.xilox.zooremote.app.data.api.AskText
import de.xilox.zooremote.app.data.api.RemoteSuggestion
import de.xilox.zooremote.app.data.connection.ConnectionState
import io.noties.markwon.Markwon
import kotlinx.coroutines.launch

/**
 * Session 5 status screen — chat layout per `docs/architektur.md` section 2: top bar with
 * connection dot + mode/model/context chips, a large live activity feed in the middle and an
 * always-visible input row with contextual control buttons at the bottom.
 *
 * Session 7: the mode/model chips navigate to their picker screens; the feed supports
 * pull-to-refresh (`GET /api/status` fallback) and distinguishes "never connected" from a
 * connected-but-empty feed.
 */
@Composable
fun StatusScreen(
	onOpenSettings: () -> Unit,
	onOpenModes: () -> Unit = {},
	onOpenModels: () -> Unit = {},
	onOpenSessions: () -> Unit = {},
	viewModel: StatusViewModel = viewModel(),
) {
	val state by viewModel.uiState.collectAsState()

	Column(modifier = Modifier.fillMaxSize()) {
		StatusTopBar(
			connectionState = state.connectionState,
			modeLabel = state.lastStatus?.mode?.label.orEmpty(),
			modelLine = state.lastStatus?.model?.describe().orEmpty(),
			contextText = contextChipText(state),
			onModeClick = onOpenModes,
			onModelClick = onOpenModels,
			// Session 9: settings & sessions are reachable at all times (not only via the offline banner).
			onOpenSettings = onOpenSettings,
			onOpenSessions = onOpenSessions,
		)
		ConnectionBanner(state.connectionState, onOpenSettings)

		val items = state.activity
		val neverConnected = state.lastStatus == null && state.activity.isEmpty() && (state.connectionState is ConnectionState.Disconnected || state.connectionState is ConnectionState.Error)
		ActivityFeed(
			items = items,
			modifier = Modifier.weight(1f).fillMaxWidth(),
			neverConnected = neverConnected,
			onPullToRefresh = { viewModel.pullToRefresh() },
			// Session 8b UI fix: follow-up suggestions are tappable in the feed as well.
			onPickSuggestion = { viewModel.tapSuggestion(it) },
		)

		if (state.actionError != null) {
			ActionErrorBanner(state.actionError.orEmpty())
		}

		// Follow-up suggestions above the input row; tapping sends messageResponse (+ mode switch if carried).
		val suggestions = state.lastStatus?.task?.pendingAsk?.suggestions
		if (!suggestions.isNullOrEmpty()) {
			SuggestionButtonsRow(suggestions, busy = state.busy) { viewModel.tapSuggestion(it) }
		}

		InputRow(state, viewModel = viewModel)
	}
}

/* ------------------------------------------------------------------ *
 * Top bar
 * ------------------------------------------------------------------ */

@Composable
private fun StatusTopBar(
	connectionState: ConnectionState,
	modeLabel: String,
	modelLine: String,
	contextText: String?,
	onModeClick: () -> Unit,
	onModelClick: () -> Unit,
	onOpenSettings: () -> Unit = {},
	onOpenSessions: () -> Unit = {},
) {
	val (dotColor, dotLabel) = when (connectionState) {
		is ConnectionState.Connected -> Color(0xFF2E7D32) to stringResource(R.string.conn_connected)
		is ConnectionState.Connecting -> Color(0xFFF9A825) to stringResource(R.string.conn_connecting)
		else -> if (connectionState is ConnectionState.Error) {
			Color(0xFFC62828) to stringResource(R.string.conn_error)
		} else {
			Color(0xFFC62828) to stringResource(R.string.conn_disconnected)
		}
	}

	Surface(shadowElevation = 3.dp, modifier = Modifier.fillMaxWidth()) {
		Row(
			modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
			verticalAlignment = Alignment.CenterVertically,
		) {
			// Session 9: short title so it never truncates next to the chips and icons.
			Text("Zoo", style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
			Box(modifier = Modifier.size(10.dp).background(dotColor, CircleShape), contentAlignment = Alignment.Center) {}
			Spacer(Modifier.width(6.dp))
			Text(dotLabel, style = MaterialTheme.typography.bodySmall, color = dotColor)

			if (modeLabel.isNotEmpty() || modelLine.isNotEmpty() || contextText != null) {
				Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.horizontalScroll(rememberScrollState()).padding(start = 12.dp)) {
					// Session 7: chips are clickable and open the mode/model picker screens.
					if (modeLabel.isNotEmpty()) Chip(modeLabel, onClick = onModeClick)
					if (modelLine.isNotEmpty()) Chip(modelLine, onClick = onModelClick)
					contextText?.let { Chip(it, highlighted = true) }
				}
			}

			// Session 9: sessions & settings icons — always reachable, also while connected.
			Spacer(Modifier.width(8.dp))
			TopBarIconButton(Icons.Filled.History, "Sessions", onClick = onOpenSessions)
			TopBarIconButton(Icons.Filled.Settings, stringResource(R.string.icon_settings), onClick = onOpenSettings)
		}
	}
}

@Composable
private fun Chip(
	text: String,
	highlighted: Boolean = false,
	onClick: (() -> Unit)? = null,
) {
	val background = if (highlighted) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant
	val foreground = if (highlighted) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant
	Surface(
		color = background,
		shape = CircleShape,
		modifier = Modifier.then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier),
	) {
		Text(
			text = text,
			color = foreground,
			style = MaterialTheme.typography.labelMedium,
			maxLines = 1,
			modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
		)
	}
}

/** Session 9: top-bar icon button (sessions / settings). */
@Composable
private fun TopBarIconButton(imageVector: androidx.compose.ui.graphics.vector.ImageVector, contentDescription: String, onClick: () -> Unit) {
	Surface(
		color = MaterialTheme.colorScheme.surfaceVariant,
		shape = CircleShape,
		modifier = Modifier.size(32.dp).clickable(onClick = onClick),
	) {
		// 7dp padding centers the 18dp icon inside the 32dp circle (M3 Surface has no contentAlignment here — session 5 tooling note).
		Icon(imageVector, contentDescription = contentDescription, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp).padding(7.dp))
	}
}

/** "42%" or "183.5k/200k" — only when the server sent context data (session 3b). */
private fun contextChipText(state: StatusUiState): String? {
	val cw = state.lastStatus?.task?.contextWindow ?: return null
	return if (cw.percent != null) {
		formatPercent(cw.percent) + "%"
	} else if ((cw.limit ?: 0L) > 0) {
		"${formatTokens(cw.used)}/${formatTokens(cw.limit!!)}"
	} else {
		formatTokens(cw.used)
	}
}

private fun formatPercent(p: Double): String = "%.1f".format(p).trimEnd('0', '.')

/** 183500 → "183.5k", 2_000_000 → "2M", 950 → "950". */
private fun formatTokens(n: Long): String = when {
	n >= 1_000_000 -> "%.1fM".format(n / 1_000_000.0).trimEnd('0', '.')
	n >= 1_000 -> "%.1fk".format(n / 1_000.0).trimEnd('0', '.')
	else -> n.toString()
}

@Composable
private fun ConnectionBanner(connectionState: ConnectionState, onOpenSettings: () -> Unit) {
	if (connectionState is ConnectionState.Connected) return
	val message = when (connectionState) {
		is ConnectionState.Error -> connectionState.message
		is ConnectionState.Connecting -> stringResource(R.string.conn_connecting)
		else -> stringResource(R.string.banner_no_connection)
	}
	Surface(color = MaterialTheme.colorScheme.errorContainer, modifier = Modifier.fillMaxWidth()) {
		Row(
			modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
			verticalAlignment = Alignment.CenterVertically,
			horizontalArrangement = Arrangement.spacedBy(12.dp),
		) {
			Text(message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onErrorContainer, modifier = Modifier.weight(1f))
			OutlinedButton(onClick = onOpenSettings, contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)) {
				Text(stringResource(R.string.btn_open_settings), style = MaterialTheme.typography.labelSmall)
			}
		}
	}
}

/* ------------------------------------------------------------------ *
 * Activity feed (chat layout)
 * ------------------------------------------------------------------ */

@Composable
private fun ActivityFeed(
	items: List<ActivityItem>,
	modifier: Modifier = Modifier,
	neverConnected: Boolean = false,
	onPullToRefresh: () -> Unit = {},
	onPickSuggestion: (RemoteSuggestion) -> Unit = {},
) {
	val listState = rememberLazyListState()
	var stickToBottom by remember { mutableStateOf(true) }

	// Follow the bottom while new entries arrive; release when the user scrolls up.
	LaunchedEffect(listState) {
		snapshotFlow {
			val info = listState.layoutInfo
			if (info.totalItemsCount == 0) return@snapshotFlow true
			val lastVisible = info.visibleItemsInfo.lastOrNull()?.index ?: 0
			lastVisible >= info.totalItemsCount - 2
		}.collect { stickToBottom = it }
	}

	LaunchedEffect(items.size, items.lastOrNull()?.ts) {
		if (stickToBottom && items.isNotEmpty()) {
			listState.animateScrollToItem(items.size - 1)
		}
	}

	if (items.isEmpty()) {
		Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
			Text(
				text = if (neverConnected) stringResource(R.string.feed_empty_never_connected) else stringResource(R.string.feed_empty_no_activity),
				style = MaterialTheme.typography.bodyMedium,
				color = MaterialTheme.colorScheme.onSurfaceVariant,
				modifier = Modifier.padding(horizontal = 24.dp),
			)
		}
		return
	}

	val scope = rememberCoroutineScope()

	LazyColumn(
		state = listState,
		modifier = modifier.fillMaxSize().then(
			if (items.size > 1) {
				Modifier.pointerInput(Unit) {
					awaitEachGesture {
						val down = awaitFirstDown(requireUnconsumed = false)
						// Pull from the very top: refresh via GET /api/status (session 7 fallback).
						if (down.position.y < 48f && listState.firstVisibleItemIndex == 0) {
							scope.launch { onPullToRefresh() }
						}
					}
				}
			} else Modifier,
		),
		contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
		verticalArrangement = Arrangement.spacedBy(10.dp),
	) {
		items(items, key = { it.ts }) { item -> ActivityRow(item, onPickSuggestion) }
	}
}

/** Consistent task-state badge (session 7): color + label for every state. */
@Composable
fun TaskStateBadge(state: String, modifier: Modifier = Modifier) {
	val (color, label) = when (state) {
		"running" -> Color(0xFFF9A825) to stringResource(R.string.task_running)
		"waiting_for_input" -> Color(0xFF1565C0) to stringResource(R.string.task_waiting)
		"completed" -> Color(0xFF2E7D32) to stringResource(R.string.task_completed)
		"error" -> Color(0xFFC62828) to stringResource(R.string.conn_error)
		else -> MaterialTheme.colorScheme.onSurfaceVariant to "Idle"
	}
	Surface(color = color.copy(alpha = 0.15f), shape = CircleShape, modifier = modifier) {
		Text(label, style = MaterialTheme.typography.labelMedium, color = color, maxLines = 1, modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp))
	}
}

@Composable
private fun ActivityRow(item: ActivityItem, onPickSuggestion: (RemoteSuggestion) -> Unit = {}) {
	when (item.category) {
		"reasoning" -> ReasoningRow(item)
		"text" -> MarkdownText(item.text.orEmpty(), modifier = Modifier.fillMaxWidth())
		"completion_result" -> CompletionRow(item)
		"tool", "command" -> ToolRow(item)
		"error" -> ErrorRow(item)

		else -> when (item.kind) {
			"ask" -> AskRow(item, onPickSuggestion)
			else -> GenericRow(item)
		}
	}
}

/** Collapsible reasoning line: "Thinking…" + expandable streaming text. */
@Composable
private fun ReasoningRow(item: ActivityItem) {
	var expanded by remember(item.ts) { mutableStateOf(false) }
	val streaming = item.partial == true && !expanded

	Surface(
		color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
		shape = RoundedCornerShape(8.dp),
		modifier = Modifier.fillMaxWidth(),
	) {
		// Session 8b UI fix: the whole row (chevron included) toggles expansion on tap.
		Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp).clickable { expanded = !expanded }) {
			Row(verticalAlignment = Alignment.CenterVertically) {
				Text(if (streaming) stringResource(R.string.reasoning_thinking) else stringResource(R.string.reasoning_done), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
				Icon(
					imageVector = if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
					contentDescription = if (expanded) stringResource(R.string.cd_collapse) else stringResource(R.string.cd_expand),
					tint = MaterialTheme.colorScheme.onSurfaceVariant,
					modifier = Modifier.size(18.dp),
				)
			}
			if (expanded && !item.text.isNullOrBlank()) {
				Text(item.text.orEmpty(), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp))
			}
		}
	}
}

/** Green "✓ Task Completed" header + markdown body. */
@Composable
private fun CompletionRow(item: ActivityItem) {
	Column(modifier = Modifier.fillMaxWidth()) {
		Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
			Icon(Icons.Filled.CheckCircle, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
			Text(stringResource(R.string.completion_header), style = MaterialTheme.typography.titleSmall)
		}
		if (!item.text.isNullOrBlank()) {
			MarkdownText(item.text.orEmpty(), modifier = Modifier.padding(top = 4.dp).fillMaxWidth())
		}
	}
}

/** Compact one-line tool/command entry: icon + short label (no JSON rendering). */
@Composable
private fun ToolRow(item: ActivityItem) {
	val isCommand = item.category == "command"
	Surface(
		color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
		shape = RoundedCornerShape(8.dp),
		modifier = Modifier.fillMaxWidth(),
	) {
		Row(modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
			Icon(
				imageVector = if (isCommand) Icons.Filled.PlayArrow else Icons.Filled.Build,
				contentDescription = null,
				tint = MaterialTheme.colorScheme.onSurfaceVariant,
				modifier = Modifier.size(16.dp),
			)
			val label = shortToolLabel(item, LocalContext.current).let { if (it.length > 80) it.take(79) + "…" else it }
			Text(label, style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace), color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
		}
	}
}

private fun shortToolLabel(item: ActivityItem, context: Context): String {
	val text = item.text?.trim().orEmpty()
	if (text.isEmpty()) return context.getString(if (item.category == "command") R.string.tool_label_command else R.string.tool_label_tool)
	return text.lineSequence().firstOrNull { it.isNotBlank() } ?: item.category
}

@Composable
private fun ErrorRow(item: ActivityItem) {
	Column(modifier = Modifier.fillMaxWidth()) {
		Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
			Icon(Icons.Filled.Error, contentDescription = null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(18.dp))
			Text(stringResource(R.string.conn_error), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.error)
		}
		if (!item.text.isNullOrBlank()) {
			Text(item.text.orEmpty(), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 2.dp))
		}
	}
}

/** Pending asks highlighted; answered ones dimmed. */
@Composable
private fun AskRow(item: ActivityItem, onPickSuggestion: (RemoteSuggestion) -> Unit = {}) {
	val answered = item.answered == true
	Surface(
		color = if (answered) MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f) else MaterialTheme.colorScheme.secondaryContainer,
		shape = RoundedCornerShape(8.dp),
		modifier = Modifier.fillMaxWidth().alpha(if (answered) 0.65f else 1f),
	) {
		Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
			Text(stringResource(R.string.ask_header, item.category), style = MaterialTheme.typography.labelMedium, color = if (answered) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSecondaryContainer)
			val question = askQuestionText(item)
			if (!question.isNullOrBlank()) {
				Text(question, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 4.dp))
			}
			// Session 8b UI fix: follow-up suggestions are tappable right in the feed (same payload as
			// the bottom bar's SuggestionButtonsRow).
			if (!answered) {
				val chips = AskText.suggestions(item.category, item.text)
				if (chips.isNotEmpty()) {
					SuggestionChipsColumn(chips, onPickSuggestion, modifier = Modifier.padding(top = 8.dp))
				}
			}
		}
	}
}

/** Extracts the human-readable question from a followup ask's JSON text; falls back to raw text. */
private fun askQuestionText(item: ActivityItem): String? = AskText.question(item.category, item.text)

@Composable
private fun GenericRow(item: ActivityItem) {
	// Session 8b UI fix: user feedback (and other plain-text says) render as visible markdown — the
	// previous label-only rendering swallowed the text.
	if (!item.text.isNullOrBlank()) {
		MarkdownText(item.text.orEmpty(), modifier = Modifier.fillMaxWidth())
	} else {
		Text(item.category, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
	}
}

/** Vertical list of tappable suggestion chips (feed variant; the bottom bar keeps its own row). */
@Composable
private fun SuggestionChipsColumn(suggestions: List<RemoteSuggestion>, onPick: (RemoteSuggestion) -> Unit, modifier: Modifier = Modifier) {
	Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
		suggestions.forEach { suggestion ->
			val label = buildString {
				append(suggestion.answer.take(80))
				suggestion.mode?.let { append("  →  $it") }
			}
			Surface(
				color = MaterialTheme.colorScheme.surfaceVariant,
				shape = RoundedCornerShape(6.dp),
				modifier = Modifier.fillMaxWidth().clickable(onClick = { onPick(suggestion) }),
			) {
				Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp))
			}
		}
	}
}

/* ------------------------------------------------------------------ *
 * Suggestions + input row (always visible)
 * ------------------------------------------------------------------ */

@Composable
private fun SuggestionButtonsRow(suggestions: List<RemoteSuggestion>, busy: Boolean, onPick: (RemoteSuggestion) -> Unit) {
	Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 4.dp)) {
		Row(
			modifier = Modifier.horizontalScroll(rememberScrollState()),
			horizontalArrangement = Arrangement.spacedBy(8.dp),
		) {
			suggestions.forEach { suggestion ->
				val label = buildString {
					append(suggestion.answer.take(60))
					suggestion.mode?.let { append("  →  $it") }
				}
				OutlinedButton(onClick = { onPick(suggestion) }, enabled = !busy, contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)) {
					Text(label, style = MaterialTheme.typography.labelMedium)
				}
			}
		}
	}
}

@Composable
private fun ActionErrorBanner(message: String) {
	Surface(color = MaterialTheme.colorScheme.errorContainer, modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 4.dp)) {
		Text(
			text = message,
			style = MaterialTheme.typography.bodySmall,
			color = MaterialTheme.colorScheme.onErrorContainer,
			modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
		)
	}
}

@Composable
private fun InputRow(state: StatusUiState, viewModel: StatusViewModel) {
	val task = state.lastStatus?.task
	val ask = task?.pendingAsk
	var input by remember { mutableStateOf("") }

	// Session 9 fix: the input row is ALWAYS sendable. The ViewModel routes the text depending on
	// the current status (answer a pending ask / continue or queue into the active session / start
	// a new session) — previously `canSend` was locked while no text-ask was pending, which left
	// the user stuck after a 409 ("already answered").
	val canSend = !input.isBlank() && !state.busy

	// Session 9: suggestion taps prefill the input row (user can still edit before sending).
	LaunchedEffect(state.prefillText) {
		state.prefillText?.let { input = it }
	}

	Surface(shadowElevation = 3.dp, modifier = Modifier.fillMaxWidth()) {
		Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
			if (ask != null && ask.canApprove) {
				Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(bottom = 4.dp)) {
					Button(onClick = { viewModel.approve() }, enabled = !state.busy, modifier = Modifier.weight(1f)) { Text(stringResource(R.string.btn_approve)) }
					OutlinedButton(onClick = { viewModel.deny() }, enabled = !state.busy, modifier = Modifier.weight(1f)) { Text(stringResource(R.string.btn_deny)) }
				}
			}

			// Session 9: stop the current task at any time while it is running or waiting for input.
			val canStop = !state.busy && task != null && (task.state == "running" || task.state == "waiting_for_input")
			if (task != null) {
				Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 4.dp)) {
					TaskStateBadge(task.state)
					if (canStop) {
						Spacer(Modifier.weight(1f))
						OutlinedButton(onClick = { viewModel.stopTask() }, contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)) {
							Text(stringResource(R.string.btn_stop), style = MaterialTheme.typography.labelMedium)
						}
					}
				}
			}

			Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 6.dp)) {
				OutlinedTextField(
					value = input,
					onValueChange = { input = it },
					placeholder = {
						Text(
							when {
								ask != null && ask.expectsText -> stringResource(R.string.input_placeholder_answer)
								task?.taskId != null -> stringResource(R.string.input_placeholder_message)
								else -> stringResource(R.string.input_placeholder_new_session)
							}
						)
					},
					singleLine = true,
					keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
					keyboardActions = KeyboardActions(onSend = {
						if (input.isNotBlank() && !state.busy) {
							viewModel.sendText(input)
							input = ""
						}
					}),
					modifier = Modifier.weight(1f),
				)
				Spacer(Modifier.width(8.dp))
				if (state.busy) {
					CircularProgressIndicator(modifier = Modifier.size(24.dp))
				} else {
					Button(onClick = { viewModel.sendText(input); input = "" }, enabled = canSend) { Text(stringResource(R.string.btn_send)) }
				}
			}
		}
	}
}

/* ------------------------------------------------------------------ *
 * Markdown rendering (Markwon)
 * ------------------------------------------------------------------ */

@Composable
private fun MarkdownText(markdown: String, modifier: Modifier = Modifier) {
	val context = LocalContext.current
	val dark = isSystemInDarkTheme()
	// Session 8b UI fix: Markwon resolves its body color from the *Android* theme of the given
	// context — with the activity's light base theme that produced black text on the Compose dark
	// background. Wrap in a matching Material (dark/light) theme so spans resolve correctly.
	val themedContext = remember(context, dark) {
		ContextThemeWrapper(
			context,
			if (dark) android.R.style.Theme_Material_NoActionBar else android.R.style.Theme_Material_Light_NoActionBar,
		)
	}
	val markwon = remember(themedContext) { Markwon.create(themedContext) }
	// Captured in composable scope — the factory lambda below is not @Composable.
	// toArgb(), nicht value.toInt(): value ist ULong mit ColorSpace-ID in den unteren 32 Bit (= 0 = transparent).
	val textColorInt = MaterialTheme.colorScheme.onSurface.toArgb()
	AndroidView(
		factory = { ctx ->
			TextView(ctx).apply {
				textSize = 14f
				setTextColor(textColorInt)
			}
		},
		update = { view -> markwon.setMarkdown(view, markdown) },
		modifier = modifier,
	)
}
