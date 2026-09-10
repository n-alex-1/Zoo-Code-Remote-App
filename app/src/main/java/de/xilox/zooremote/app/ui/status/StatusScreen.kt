package de.xilox.zooremote.app.ui.status

import android.widget.TextView
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.PlayArrow
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
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.viewmodel.compose.viewModel
import de.xilox.zooremote.app.data.api.ActivityItem
import de.xilox.zooremote.app.data.connection.ConnectionState
import io.noties.markwon.Markwon

/**
 * Session 5 status screen — chat layout per `docs/architektur.md` section 2: top bar with
 * connection dot + mode/model/context chips, a large live activity feed in the middle and an
 * always-visible input row with contextual control buttons at the bottom.
 */
@Composable
fun StatusScreen(
	onOpenSettings: () -> Unit,
	viewModel: StatusViewModel = viewModel(),
) {
	val state by viewModel.uiState.collectAsState()

	Column(modifier = Modifier.fillMaxSize()) {
		StatusTopBar(state.connectionState, modeLabel = state.lastStatus?.mode?.label.orEmpty(), modelLine = state.lastStatus?.model?.describe().orEmpty(), contextText = contextChipText(state))
		ConnectionBanner(state.connectionState, onOpenSettings)

		val items = state.activity
		ActivityFeed(items, modifier = Modifier.weight(1f).fillMaxWidth())

		// Follow-up suggestions above the input row (session 5: rendered only — tapping sends in session 6).
		val suggestions = state.lastStatus?.task?.pendingAsk?.suggestions
		if (!suggestions.isNullOrEmpty()) {
			SuggestionButtonsRow(suggestions.map { it.answer })
		}

		InputRow(state)
	}
}

/* ------------------------------------------------------------------ *
 * Top bar
 * ------------------------------------------------------------------ */

@Composable
private fun StatusTopBar(connectionState: ConnectionState, modeLabel: String, modelLine: String, contextText: String?) {
	val (dotColor, dotLabel) = when (connectionState) {
		is ConnectionState.Connected -> Color(0xFF2E7D32) to "Verbunden"
		is ConnectionState.Connecting -> Color(0xFFF9A825) to "Verbinde…"
		else -> if (connectionState is ConnectionState.Error) {
			Color(0xFFC62828) to "Fehler"
		} else {
			Color(0xFFC62828) to "Getrennt"
		}
	}

	Surface(shadowElevation = 3.dp, modifier = Modifier.fillMaxWidth()) {
		Row(
			modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
			verticalAlignment = Alignment.CenterVertically,
		) {
			Text("Zoo Remote", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
			Box(modifier = Modifier.size(10.dp).background(dotColor, CircleShape), contentAlignment = Alignment.Center) {}
			Spacer(Modifier.width(6.dp))
			Text(dotLabel, style = MaterialTheme.typography.bodySmall, color = dotColor)

			if (modeLabel.isNotEmpty() || modelLine.isNotEmpty() || contextText != null) {
				Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.horizontalScroll(rememberScrollState()).padding(start = 12.dp)) {
					if (modeLabel.isNotEmpty()) Chip(modeLabel)
					if (modelLine.isNotEmpty()) Chip(modelLine)
					contextText?.let { Chip(it, highlighted = true) }
				}
			}
		}
	}
}

@Composable
private fun Chip(text: String, highlighted: Boolean = false) {
	val background = if (highlighted) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant
	val foreground = if (highlighted) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant
	Surface(color = background, shape = CircleShape) {
		Text(
			text = text,
			color = foreground,
			style = MaterialTheme.typography.labelMedium,
			maxLines = 1,
			modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
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
		is ConnectionState.Connecting -> "Verbinde…"
		else -> "Keine Verbindung."
	}
	Surface(color = MaterialTheme.colorScheme.errorContainer, modifier = Modifier.fillMaxWidth()) {
		Row(
			modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
			verticalAlignment = Alignment.CenterVertically,
			horizontalArrangement = Arrangement.spacedBy(12.dp),
		) {
			Text(message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onErrorContainer, modifier = Modifier.weight(1f))
			OutlinedButton(onClick = onOpenSettings, contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)) {
				Text("Zu den Einstellungen", style = MaterialTheme.typography.labelSmall)
			}
		}
	}
}

/* ------------------------------------------------------------------ *
 * Activity feed (chat layout)
 * ------------------------------------------------------------------ */

@Composable
private fun ActivityFeed(items: List<ActivityItem>, modifier: Modifier = Modifier) {
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
			Text("Noch keine Aktivität — starte eine Task in VS-Code.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
		}
		return
	}

	LazyColumn(
		state = listState,
		modifier = modifier.fillMaxSize(),
		contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
		verticalArrangement = Arrangement.spacedBy(10.dp),
	) {
		items(items, key = { it.ts }) { item -> ActivityRow(item) }
	}
}

@Composable
private fun ActivityRow(item: ActivityItem) {
	when (item.category) {
		"reasoning" -> ReasoningRow(item)
		"text" -> MarkdownText(item.text.orEmpty(), modifier = Modifier.fillMaxWidth())
		"completion_result" -> CompletionRow(item)
		"tool", "command" -> ToolRow(item)
		"error" -> ErrorRow(item)

		else -> when (item.kind) {
			"ask" -> AskRow(item)
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
		Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
			Row(verticalAlignment = Alignment.CenterVertically) {
				Text(if (streaming) "Thinking…" else "Gedanken", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
				Icon(
					imageVector = if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
					contentDescription = if (expanded) "Einklappen" else "Aufklappen",
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
			Text("Task Completed", style = MaterialTheme.typography.titleSmall)
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
			val label = shortToolLabel(item).let { if (it.length > 80) it.take(79) + "…" else it }
			Text(label, style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace), color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
		}
	}
}

private fun shortToolLabel(item: ActivityItem): String {
	val text = item.text?.trim().orEmpty()
	if (text.isEmpty()) return if (item.category == "command") "Befehl" else "Werkzeug"
	return text.lineSequence().firstOrNull { it.isNotBlank() } ?: item.category
}

@Composable
private fun ErrorRow(item: ActivityItem) {
	Column(modifier = Modifier.fillMaxWidth()) {
		Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
			Icon(Icons.Filled.Error, contentDescription = null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(18.dp))
			Text("Fehler", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.error)
		}
		if (!item.text.isNullOrBlank()) {
			Text(item.text.orEmpty(), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 2.dp))
		}
	}
}

/** Pending asks highlighted; answered ones dimmed. */
@Composable
private fun AskRow(item: ActivityItem) {
	val answered = item.answered == true
	Surface(
		color = if (answered) MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f) else MaterialTheme.colorScheme.secondaryContainer,
		shape = RoundedCornerShape(8.dp),
		modifier = Modifier.fillMaxWidth().alpha(if (answered) 0.65f else 1f),
	) {
		Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
			Text("Eingabe erforderlich (${item.category})", style = MaterialTheme.typography.labelMedium, color = if (answered) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSecondaryContainer)
			val question = askQuestionText(item)
			if (!question.isNullOrBlank()) {
				Text(question, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 4.dp))
			}
		}
	}
}

/** Extracts the human-readable question from a followup ask's JSON text; falls back to raw text. */
private fun askQuestionText(item: ActivityItem): String? {
	val text = item.text ?: return null
	if (item.category == "followup") {
		runCatching {
			org.json.JSONObject(text).optString("question").takeIf { it.isNotBlank() }
		}.getOrNull()?.let { return it }
	}
	return if (text.length > 300) text.take(299) + "…" else text
}

@Composable
private fun GenericRow(item: ActivityItem) {
	Text(item.category, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
	if (!item.text.isNullOrBlank()) {
		MarkdownText(item.text.orEmpty(), modifier = Modifier.padding(top = 2.dp).fillMaxWidth())
	}
}

/* ------------------------------------------------------------------ *
 * Suggestions + input row (always visible)
 * ------------------------------------------------------------------ */

@Composable
private fun SuggestionButtonsRow(suggestions: List<String>) {
	Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 4.dp)) {
		Row(
			modifier = Modifier.horizontalScroll(rememberScrollState()),
			horizontalArrangement = Arrangement.spacedBy(8.dp),
		) {
			suggestions.forEach { answer ->
				OutlinedButton(onClick = {}, enabled = false, contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)) {
					Text(answer.take(60), style = MaterialTheme.typography.labelMedium) // session 6: tap sends.
				}
			}
		}
		Text("Vorschläge werden in Session 6 aktiv.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
	}
}

@Composable
private fun InputRow(state: StatusUiState) {
	val task = state.lastStatus?.task
	val ask = task?.pendingAsk
	var input by remember { mutableStateOf("") }

	Surface(shadowElevation = 3.dp, modifier = Modifier.fillMaxWidth()) {
		Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
			if (ask != null && ask.canApprove) {
				Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(bottom = 4.dp)) {
					Button(onClick = {}, enabled = false, modifier = Modifier.weight(1f)) { Text("Genehmigen") } // session 6.
					OutlinedButton(onClick = {}, enabled = false, modifier = Modifier.weight(1f)) { Text("Ablehnen") } // session 6.
				}
			} else if (task != null) {
				Text(taskStatusLabel(task.state), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
			}

			Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 6.dp)) {
				OutlinedTextField(
					value = input,
					onValueChange = { input = it },
					placeholder = { Text("Antwort…") },
					singleLine = true,
					keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
					modifier = Modifier.weight(1f),
				)
				Spacer(Modifier.width(8.dp))
				Button(onClick = {}, enabled = false) { Text("Senden") } // session 6.
			}
			Text("Senden wird in Session 6 aktiviert.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 2.dp))
		}
	}
}

private fun taskStatusLabel(state: String): String = when (state) {
	"running" -> "Läuft…"
	"waiting_for_input" -> "Wartet auf Eingabe"
	"completed" -> "Fertig"
	"error" -> "Fehler"
	else -> "Idle"
}

/* ------------------------------------------------------------------ *
 * Markdown rendering (Markwon)
 * ------------------------------------------------------------------ */

@Composable
private fun MarkdownText(markdown: String, modifier: Modifier = Modifier) {
	val context = LocalContext.current
	val markwon = remember(context) { Markwon.create(context) }
	// Captured in composable scope — the factory lambda below is not @Composable.
	val textColorInt = MaterialTheme.colorScheme.onSurface.value.toInt()
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
