package de.xilox.zooremote.app.ui.models

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import de.xilox.zooremote.app.data.api.ProfileInfo

/**
 * Session 7: list of provider profiles (name + configured model). The active profile is
 * highlighted; tapping a row activates it (`POST /api/model`) with optimistic UI and rollback.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModelScreen(
	onBack: () -> Unit,
	viewModel: ModelViewModel = viewModel(),
) {
	val state by viewModel.uiState.collectAsState()
	val snackbarHostState = remember { SnackbarHostState() }

	// Surface action errors as a snackbar (spec).
	var pendingSnackbar by remember { mutableStateOf<String?>(null) }
	if (state.actionError != null && pendingSnackbar == null) {
		pendingSnackbar = state.actionError
	}
	LaunchedEffect(pendingSnackbar) {
		val message = pendingSnackbar ?: return@LaunchedEffect
		snackbarHostState.showSnackbar(message)
		pendingSnackbar = null
	}

	Scaffold(
		topBar = { TopAppBar(title = { Text("Modelle") }, navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Zurück") } }) },
		snackbarHost = { SnackbarHost(snackbarHostState) },
	) { padding ->
		when {
			state.loading -> Box(modifier = Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
				CircularProgressIndicator()
			}

			state.loadError != null && state.profiles.isEmpty() -> Box(modifier = Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
				Text(state.loadError.orEmpty(), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(horizontal = 24.dp))
			}

			else -> LazyColumn(
				modifier = Modifier.fillMaxSize().padding(padding),
				contentPadding = PaddingValues(vertical = 8.dp),
				verticalArrangement = Arrangement.spacedBy(2.dp),
			) {
				items(state.profiles, key = { it.id }) { profile ->
					ModelRow(
						profile = profile,
						active = viewModel.isActive(profile),
						busy = state.pendingProfileId != null && state.pendingProfileId != profile.id,
						onClick = { viewModel.select(profile) },
					)
				}
			}
		}
	}
}

@Composable
private fun ModelRow(
	profile: ProfileInfo,
	active: Boolean,
	busy: Boolean,
	onClick: () -> Unit,
) {
	Surface(
		color = if (active) MaterialTheme.colorScheme.primaryContainer else Color.Transparent,
		modifier = Modifier.fillMaxWidth().clickable(enabled = !busy, onClick = onClick),
	) {
		Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
			Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
				Text(profile.name, style = MaterialTheme.typography.bodyLarge, color = if (active) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface, modifier = Modifier.weight(1f))
				if (active) {
					Icon(Icons.Filled.Check, contentDescription = "Aktiv", tint = if (busy) Color.Gray else MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
				} else if (busy) {
					CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
				}
			}
			val subtitle = buildString {
				profile.provider?.let { append(it); append(" · ") }
				append(profile.modelId ?: "(Modell nicht gesetzt)")
			}
			Text(subtitle, style = MaterialTheme.typography.bodySmall, color = if (active) MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f) else MaterialTheme.colorScheme.onSurfaceVariant)
		}
	}
}
