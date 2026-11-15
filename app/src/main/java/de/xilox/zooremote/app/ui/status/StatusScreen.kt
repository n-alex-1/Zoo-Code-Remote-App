package de.xilox.zooremote.app.ui.status

import android.content.Context
import android.view.ContextThemeWrapper
import android.widget.TextView
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Article
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Checklist
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.ManageSearch
import androidx.compose.material.icons.filled.NoteAdd
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.RocketLaunch
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
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
import androidx.compose.ui.draw.scale
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
import de.xilox.zooremote.app.data.api.ToolText
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

	// Cross-fade the pill color on state changes instead of snapping (session 10 redesign).
	val animatedColor by animateColorAsState(targetValue = dotColor, animationSpec = tween(350), label = "statusPillColor")
	val hasChips = modeLabel.isNotEmpty() || modelLine.isNotEmpty() || contextText != null

	Surface(shadowElevation = 4.dp, modifier = Modifier.fillMaxWidth()) {
		Column(modifier = Modifier.padding(start = 16.dp, end = 8.dp, bottom = if (hasChips) 0.dp else 2.dp)) {
			// Row 1: title + animated status pill + icon buttons.
			Row(
				modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
				verticalAlignment = Alignment.CenterVertically,
			) {
				// Full app name in the header — "Zoo" alone looked truncated; the row still has room.
				Text(stringResource(R.string.app_name), style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
				StatusPill(color = animatedColor, label = dotLabel, pulsing = connectionState is ConnectionState.Connecting)
				TopBarIconButton(Icons.Filled.History, "Sessions", onClick = onOpenSessions)
				TopBarIconButton(Icons.Filled.Settings, stringResource(R.string.icon_settings), onClick = onOpenSettings)
			}

			// Row 2: mode/model/context chips — slides down when the server sends data. The model
			// name moved here (session 10) and carries the highlighted style; the context chip is secondary.
			AnimatedVisibility(
				visible = hasChips,
				enter = fadeIn(tween(250)) + slideInVertically(tween(350), initialOffsetY = { -it / 4 }),
				exit = fadeOut(tween(150)) + slideOutVertically(tween(250), targetOffsetY = { -it / 4 }),
			) {
				Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.horizontalScroll(rememberScrollState()).padding(top = 8.dp, bottom = 10.dp)) {
					if (modeLabel.isNotEmpty()) Chip(modeLabel, onClick = onModeClick)
					if (modelLine.isNotEmpty()) Chip(modelLine, highlighted = true, onClick = onModelClick)
					contextText?.let { Chip(it) }
				}
			}
		}
	}
}

/** Tinted status pill: spring pop-in when the label changes + sonar ping while connecting. */
@Composable
private fun StatusPill(color: Color, label: String, pulsing: Boolean) {
	key(label) {
		// Flips after first composition so the pill animates in (0.5 → 1.0) instead of appearing at full scale.
		var popped by remember { mutableStateOf(false) }
		LaunchedEffect(Unit) { popped = true }
		val pop by animateFloatAsState(
			targetValue = if (popped) 1f else 0.5f,
			animationSpec = spring(dampingRatio = Spring.DampingRatioLowBouncy, stiffness = Spring.StiffnessMedium),
			label = "statusPillPop",
		)
		Surface(color = color.copy(alpha = 0.12f), shape = CircleShape) {
			Row(
				modifier = Modifier.scale(pop).padding(horizontal = 10.dp, vertical = 5.dp),
				verticalAlignment = Alignment.CenterVertically,
			) {
				StatusDot(color = color, pulsing = pulsing)
				Spacer(Modifier.width(6.dp))
				Text(label, style = MaterialTheme.typography.labelMedium, color = color, maxLines = 1)
			}
		}
	}
}

/** Connection dot; while [pulsing] an expanding ring radiates behind it (sonar effect). */
@Composable
private fun StatusDot(color: Color, pulsing: Boolean) {
	val transition = rememberInfiniteTransition(label = "statusPing")
	val pingScale by transition.animateFloat(
		initialValue = 1f,
		targetValue = 2.6f,
		animationSpec = infiniteRepeatable(tween(1400), RepeatMode.Restart),
		label = "pingScale",
	)
	val pingAlpha by transition.animateFloat(
		initialValue = 0.45f,
		targetValue = 0f,
		animationSpec = infiniteRepeatable(tween(1400), RepeatMode.Restart),
		label = "pingAlpha",
	)

	Box(contentAlignment = Alignment.Center) {
		if (pulsing) {
			Box(modifier = Modifier.size(9.dp).scale(pingScale).alpha(pingAlpha).background(color, CircleShape))
		}
		Box(modifier = Modifier.size(9.dp).background(color, CircleShape))
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

/** Modern top-bar icon button (session 10): transparent with the standard M3 ripple instead of a filled circle. */
@Composable
private fun TopBarIconButton(imageVector: androidx.compose.ui.graphics.vector.ImageVector, contentDescription: String, onClick: () -> Unit) {
	IconButton(onClick = onClick) {
		Icon(
			imageVector = imageVector,
			contentDescription = contentDescription,
			tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
			modifier = Modifier.size(22.dp),
		)
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

	// Session 11 feed redesign: consecutive identical tool calls collapse into one chip with a
	// "×N" badge, which turns long read/search chains into a few scannable lines.
	val groups = remember(items) { ToolText.group(items) }

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
		verticalArrangement = Arrangement.spacedBy(8.dp),
	) {
		items(groups, key = { it.items.first().ts }) { group -> ActivityRow(group, onPickSuggestion) }
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
private fun ActivityRow(group: ToolText.ToolGroup, onPickSuggestion: (RemoteSuggestion) -> Unit = {}) {
	val item = group.representative
	when (item.category) {
		"reasoning" -> ReasoningRow(item)
		"text" -> MarkdownText(item.text.orEmpty(), modifier = Modifier.fillMaxWidth())
		"completion_result" -> CompletionRow(item)
		"tool", "command" -> ToolRow(group)
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
		color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
		shape = RoundedCornerShape(10.dp),
		modifier = Modifier.fillMaxWidth(),
	) {
		// Session 8b UI fix: the whole row (chevron included) toggles expansion on tap.
		Column(modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp).clickable { expanded = !expanded }) {
			Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
				Icon(
					imageVector = Icons.Filled.Psychology,
					contentDescription = null,
					tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
					modifier = Modifier.size(15.dp),
				)
				Text(if (streaming) stringResource(R.string.reasoning_thinking) else stringResource(R.string.reasoning_done), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
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

/**
 * Tool/command chip (session 11 redesign): a color-coded icon, a short human label ("Datei lesen ·
 * placeadd.php") and — when the call carries extra context — a muted detail line (regex, pattern,
 * diff stats …). Long JSON payloads are gone; the raw payload stays reachable by tapping the chip,
 * and runs of identical calls collapse into one chip with an "×N" badge.
 */
@Composable
private fun ToolRow(group: ToolText.ToolGroup) {
	val item = group.representative
	val parsed = remember(item) { ToolText.parse(item.text) }
	val isCommand = item.category == "command"
	val context = LocalContext.current

	val (icon, tint) = toolIconAndTint(parsed, isCommand)
	val label = toolLabel(parsed, item.category, context)
	val detail = toolDetail(parsed, label.substringAfter(" · ", ""), context)
	val raw = item.text?.trim().orEmpty()
	// The raw payload (full JSON / multi-line script) stays reachable behind a tap.
	val expandable = raw.isNotEmpty() && (parsed?.tool != null || '\n' in raw)

	var expanded by remember(item.ts) { mutableStateOf(false) }

	Surface(
		color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
		shape = RoundedCornerShape(10.dp),
		modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Min),
	) {
		Row {
			// Thin accent bar in the tool category's color — makes read/search/edit chains scannable at a glance.
			Box(modifier = Modifier.width(3.dp).fillMaxHeight().background(tint.copy(alpha = 0.85f), shape = RoundedCornerShape(topStart = 10.dp, bottomStart = 10.dp)))
			Column(
				modifier = Modifier.weight(1f).padding(start = 10.dp, end = 10.dp, top = 7.dp, bottom = 7.dp)
					.then(if (expandable) Modifier.clickable { expanded = !expanded } else Modifier),
			) {
				Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
					Icon(imageVector = icon, contentDescription = null, tint = tint, modifier = Modifier.size(15.dp))
					Text(
						text = buildString {
							append(label)
							if (group.count > 1) append("  ·  ×").append(group.count)
						},
						style = MaterialTheme.typography.bodySmall,
						color = MaterialTheme.colorScheme.onSurface,
						maxLines = 1,
						overflow = TextOverflow.Ellipsis,
						modifier = Modifier.weight(1f, fill = false),
					)
					if (parsed?.isOutsideWorkspace == true) {
						Text(
							stringResource(R.string.tool_outside_workspace),
							style = MaterialTheme.typography.labelSmall,
							color = MaterialTheme.colorScheme.tertiary,
							maxLines = 1,
						)
					}
					if (expandable) {
						Icon(
							imageVector = if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
							contentDescription = stringResource(if (expanded) R.string.cd_collapse else R.string.cd_expand),
							tint = MaterialTheme.colorScheme.onSurfaceVariant,
							modifier = Modifier.size(16.dp),
						)
					}
				}
				if (!detail.isNullOrBlank()) {
					Text(
						detail,
						style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
						color = MaterialTheme.colorScheme.onSurfaceVariant,
						maxLines = if (expanded) 4 else 1,
						overflow = TextOverflow.Ellipsis,
						modifier = Modifier.padding(top = 2.dp, start = 23.dp),
					)
				}
				if (expanded) {
					Text(
						raw,
						style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
						color = MaterialTheme.colorScheme.onSurfaceVariant,
						maxLines = 8,
						overflow = TextOverflow.Ellipsis,
						modifier = Modifier.padding(top = 4.dp, start = 23.dp),
					)
				}
			}
		}
	}
}

/** Tool-specific icon + accent color: read/search/edit/command groups are recognizable at a glance. */
@Composable
private fun toolIconAndTint(parsed: ToolText.Parsed?, isCommand: Boolean): Pair<androidx.compose.ui.graphics.vector.ImageVector, Color> {
	if (isCommand) return Icons.Filled.Terminal to Color(0xFF26A69A)
	return when (parsed?.tool) {
		"readFile", "readCommandOutput" -> Icons.Filled.Article to Color(0xFF42A5F5)
		"listFilesTopLevel", "listFilesRecursive" -> Icons.Filled.FolderOpen to Color(0xFF7E57C2)
		"searchFiles" -> Icons.Filled.Search to Color(0xFF9CCC65)
		"codebaseSearch" -> Icons.Filled.ManageSearch to Color(0xFFD4A017)
		"editedExistingFile", "appliedDiff" -> Icons.Filled.Edit to Color(0xFFFF7043)
		"newFileCreated" -> Icons.Filled.NoteAdd to Color(0xFF66BB6A)
		"switchMode" -> Icons.Filled.SwapHoriz to Color(0xFFAB47BC)
		"newTask" -> Icons.Filled.RocketLaunch to Color(0xFF5C6BC0)
		"finishTask" -> Icons.Filled.Flag to Color(0xFF26A69A)
		"generateImage", "imageGenerated" -> Icons.Filled.AutoAwesome to Color(0xFFEC407A)
		"runSlashCommand" -> Icons.Filled.Bolt to Color(0xFFD4A017)
		"updateTodoList" -> Icons.Filled.Checklist to Color(0xFF66BB6A)
		"skill" -> Icons.Filled.Psychology to Color(0xFF7E57C2)
		else -> Icons.Filled.Build to MaterialTheme.colorScheme.onSurfaceVariant
	}
}

/** Human-readable, localized verb for the tool ("Datei lesen"); falls back to raw first line. */
@Composable
private fun toolLabel(parsed: ToolText.Parsed?, category: String, context: Context): String {
	val key = when (parsed?.tool) {
		"readFile" -> R.string.tool_read
		"readCommandOutput" -> R.string.tool_read_output
		"listFilesTopLevel" -> R.string.tool_list
		"listFilesRecursive" -> R.string.tool_list_recursive
		"searchFiles" -> R.string.tool_search
		"codebaseSearch" -> R.string.tool_codebase_search
		"editedExistingFile", "appliedDiff" -> R.string.tool_edit
		"newFileCreated" -> R.string.tool_new_file
		"switchMode" -> R.string.tool_switch_mode
		"newTask" -> R.string.tool_new_task
		"finishTask" -> R.string.tool_finish_task
		"generateImage", "imageGenerated" -> R.string.tool_image
		"runSlashCommand" -> R.string.tool_slash_command
		"updateTodoList" -> R.string.tool_todos
		"skill" -> R.string.tool_skill
		else -> null
	}
	val verb = key?.let { context.getString(it) } ?: context.getString(if (category == "command") R.string.tool_label_command else R.string.tool_label_tool)
	// The object of the verb: file name, mode slug, command … (keeps the line informative, not just "Werkzeug").
	val objectText = when {
		parsed == null -> ""
		category == "command" -> parsed.rawFirstLine.orEmpty()
		else -> sequenceOf(parsed.path?.let { ToolText.fileName(it) }, parsed.mode, parsed.command, parsed.skill, parsed.query, parsed.rawFirstLine)
			.firstOrNull { !it.isNullOrBlank() }.orEmpty()
	}
	return if (objectText.isBlank()) verb else "$verb · $objectText"
}

/**
 * Muted second line with the remaining parameters — the info the old JSON dump carried. The
 * [labelObject] (the verb's object already shown on the first line) is filtered out to avoid
 * repeating the same value twice.
 */
@Composable
private fun toolDetail(parsed: ToolText.Parsed?, labelObject: String, context: Context): String? {
	if (parsed == null) return null
	val parts = mutableListOf<String>()
	parsed.path?.let { path ->
		val dir = path.replace('\\', '/').trimEnd('/').substringBeforeLast('/', "").replace('/', '·')
		if (dir.isNotBlank()) parts += dir
	}
	parsed.regex?.let { parts += "/${it}/" }
	parsed.filePattern?.let { parts += context.getString(R.string.tool_detail_pattern, it) }
	when {
		parsed.startLine > 0 -> parts += context.getString(R.string.tool_detail_line, parsed.startLine)
		parsed.lineNumber > 0 -> parts += context.getString(R.string.tool_detail_line, parsed.lineNumber)
	}
	(parsed.matchCount.takeIf { it > 0 })?.let { parts += context.getString(R.string.tool_detail_matches, it) }
	parsed.query?.let { parts += "\"${it}\"" }
	parsed.args?.let { parts += it }
	if (parsed.linesAdded > 0 || parsed.linesRemoved > 0) parts += context.getString(R.string.tool_detail_diff, parsed.linesAdded, parsed.linesRemoved)
	if (parsed.todoTotal > 0) parts += context.getString(R.string.tool_detail_todos, parsed.todoDone, parsed.todoTotal)
	(parsed.additionalFileCount.takeIf { it > 0 })?.let { parts += context.getString(R.string.tool_detail_more_files, it) }
	parsed.description?.let { parts += it }
	// Drop the piece already shown on the label line so the detail adds info instead of repeating it.
	return parts.filter { it.isNotBlank() && it != labelObject && it != "\"$labelObject\"" }.distinct().joinToString("  ·  ").takeIf { it.isNotBlank() }
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
