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
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel

/**
 * Session 4 setup screen: host, port, token, certificate fingerprint and a "Verbinden"
 * button that runs `GET /api/health` + `GET /api/status` against the pinned server.
 */
@Composable
fun SetupScreen(viewModel: SetupViewModel = viewModel()) {
	val host by viewModel.host.collectAsState()
	val portText by viewModel.portText.collectAsState()
	val token by viewModel.token.collectAsState()
	val fingerprint by viewModel.fingerprint.collectAsState()
	val phase by viewModel.phase.collectAsState()

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
			text = "Daten aus VS-Code: Einstellungen > \"Remote Control\" (Token + Fingerprint) bzw. OutputChannel \"Zoo Remote\". Emulator erreicht den Host ueber 10.0.2.2, ein echtes Gerat ueber die LAN-IP.",
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

		OutlinedTextField(
			value = token,
			onValueChange = { viewModel.token.value = it },
			label = { Text("Token") },
			singleLine = true,
			keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
			modifier = Modifier.fillMaxWidth(),
		)

		OutlinedTextField(
			value = fingerprint,
			onValueChange = { viewModel.fingerprint.value = it },
			label = { Text("Zertifikats-Fingerprint (SHA-256)") },
			singleLine = true,
			textStyle = MaterialTheme.typography.bodyLarge.copy(fontFamily = FontFamily.Monospace),
			keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
			modifier = Modifier.fillMaxWidth(),
		)

		when (val p = phase) {
			is SetupPhase.Testing -> Column(
				horizontalAlignment = Alignment.CenterHorizontally,
				modifier = Modifier.fillMaxWidth(),
			) { CircularProgressIndicator() }

			is SetupPhase.Success -> ResultCard(green = true, text = p.statusLine)
			is SetupPhase.Failure -> ResultCard(green = false, text = p.message)
			SetupPhase.Idle -> Unit
		}

		Button(
			onClick = { viewModel.testConnection() },
			enabled = phase !is SetupPhase.Testing,
			modifier = Modifier.fillMaxWidth(),
		) {
			Text(if (phase is SetupPhase.Testing) "Verbinde..." else "Verbinden")
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
