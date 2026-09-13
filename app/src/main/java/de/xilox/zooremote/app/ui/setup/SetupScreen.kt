package de.xilox.zooremote.app.ui.setup

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel

/**
 * Setup screen (session 9b: pairing-first).
 *
 * Primary path: enter host/IP + port, press "Pairing" — token and certificate fingerprint are
 * fetched from the plugin's one-shot window (`POST /api/pair`) and stored automatically. The
 * manual fields remain available in the "Manuelle Eingabe (Expert)" section for re-pairing after
 * a reset or when an older plugin version is running.
 *
 * Session 8b: [authError] surfaces a REST-level auth failure (HTTP 401) from another screen —
 * the user landed here because re-pairing is required; it stays visible until "Verbinden" is
 * pressed again and either succeeds or fails with its own message.
 */
@Composable
fun SetupScreen(
	authError: String? = null,
	onSuccess: () -> Unit,
	viewModel: SetupViewModel = viewModel(),
) {
	val host by viewModel.host.collectAsState()
	val portText by viewModel.portText.collectAsState()
	val token by viewModel.token.collectAsState()
	val fingerprint by viewModel.fingerprint.collectAsState()
	val pairedFingerprint by viewModel.pairedFingerprint.collectAsState()
	val phase by viewModel.phase.collectAsState()

	// Session 5: navigate to the status screen once the connection test succeeded.
	LaunchedEffect(phase) {
		if (phase is SetupPhase.Success) onSuccess()
	}

	Column(
		modifier = Modifier
			.fillMaxSize()
			.padding(24.dp)
			.verticalScroll(rememberScrollState()),
		verticalArrangement = Arrangement.spacedBy(16.dp),
		horizontalAlignment = Alignment.CenterHorizontally,
	) {
		Text("Zoo Remote - Verbindung", style = MaterialTheme.typography.headlineSmall)
		Text(
			text = "In VS-Code: Einstellungen > \"Remote Control\" > \"Pairing starten\" (120 s). Dann hier Host/IP + Port eingeben und auf \"Pairing\" tippen. Emulator erreicht den Host ueber 10.0.2.2, ein echtes Gerat ueber die LAN-IP.",
			style = MaterialTheme.typography.bodySmall,
		)

		OutlinedTextField(
			value = host,
			onValueChange = { viewModel.host.value = it },
			label = { Text("Host / IP") },
			singleLine = true,
			keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
			modifier = Modifier.fillMaxWidth(),
		)

		OutlinedTextField(
			value = portText,
			onValueChange = { viewModel.portText.value = it },
			label = { Text("Port") },
			singleLine = true,
			keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
			modifier = Modifier.fillMaxWidth(),
		)

		Button(
			onClick = { viewModel.startPairing() },
			enabled = phase !is SetupPhase.Testing,
			modifier = Modifier.fillMaxWidth(),
		) {
			Text(if (phase is SetupPhase.Testing) "Verbinde..." else "Pairing")
		}

		when (val p = phase) {
			is SetupPhase.Testing -> Column(
				horizontalAlignment = Alignment.CenterHorizontally,
				modifier = Modifier.fillMaxWidth(),
			) { CircularProgressIndicator() }

			is SetupPhase.Success -> ResultCard(green = true, text = p.statusLine)
			is SetupPhase.Failure -> ResultCard(green = false, text = p.message)

			// Session 8b: landed here via a REST-level auth failure (HTTP 401 / wrong token).
			SetupPhase.Idle -> if (!authError.isNullOrBlank()) {
				ResultCard(green = false, text = authError)
			}
		}

		// TOFU transparency: show what the server presented during pairing so it can be compared
		// with the fingerprint printed in VS Code (Settings -> Remote Control / OutputChannel).
		if (!pairedFingerprint.isNullOrEmpty() && phase !is SetupPhase.Success) {
			Text(
				text = "Server-Zertifikat beim Pairing: $pairedFingerprint",
				style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
				color = MaterialTheme.colorScheme.primary,
			)
		}

		ManualSection(
			token = token,
			fingerprint = fingerprint,
			onTokenChange = { viewModel.token.value = it },
			onFingerprintChange = { viewModel.fingerprint.value = it },
			onConnect = { viewModel.testConnection() },
			disabled = phase is SetupPhase.Testing,
		)
	}
}

/** Collapsed expert section: manual token + fingerprint for re-pairing / older plugin versions. */
@Composable
private fun ManualSection(
	token: String,
	fingerprint: String,
	onTokenChange: (String) -> Unit,
	onFingerprintChange: (String) -> Unit,
	onConnect: () -> Unit,
	disabled: Boolean,
) {
	var expanded by remember { mutableStateOf(false) }

	Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
		Text("Manuelle Eingabe (Expert)", style = MaterialTheme.typography.titleSmall)
		if (!expanded) {
			OutlinedButton(onClick = { expanded = true }, modifier = Modifier.fillMaxWidth()) {
				Text("Aufklappen")
			}
		} else {
			Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
				OutlinedTextField(
					value = token,
					onValueChange = onTokenChange,
					label = { Text("Token") },
					singleLine = true,
					keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
					modifier = Modifier.fillMaxWidth(),
				)
				OutlinedTextField(
					value = fingerprint,
					onValueChange = onFingerprintChange,
					label = { Text("Zertifikats-Fingerprint (SHA-256)") },
					singleLine = true,
					textStyle = MaterialTheme.typography.bodyLarge.copy(fontFamily = FontFamily.Monospace),
					keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
					modifier = Modifier.fillMaxWidth(),
				)
				Button(onClick = onConnect, enabled = !disabled, modifier = Modifier.fillMaxWidth()) {
					Text("Verbinden")
				}
			}
		}
	}
}

@Composable
private fun ResultCard(green: Boolean, text: String) {
	val color = if (green) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
	Text(
		text = text,
		color = color,
		style = MaterialTheme.typography.bodyMedium,
		textAlign = TextAlign.Center,
		modifier = Modifier.fillMaxWidth(),
	)
}
