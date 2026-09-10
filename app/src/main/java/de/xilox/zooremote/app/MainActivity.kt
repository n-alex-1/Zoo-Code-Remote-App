package de.xilox.zooremote.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import de.xilox.zooremote.app.data.ConnectionSettings
import de.xilox.zooremote.app.data.SettingsRepository
import de.xilox.zooremote.app.ui.setup.SetupScreen
import de.xilox.zooremote.app.ui.status.StatusScreen
import kotlinx.coroutines.flow.first

/**
 * Single-activity host. Session 5 adds the Status destination: Setup ↔ Status navigation; when
 * valid saved settings exist, the app starts on Status (see docs/architektur.md section 2).
 */
class MainActivity : ComponentActivity() {

	override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)
		setContent {
			val dark = isSystemInDarkTheme()
			MaterialTheme(colorScheme = if (dark) darkColorScheme() else lightColorScheme()) {
				Surface(modifier = Modifier.fillMaxSize()) {
					ZooRemoteNavHost(settingsRepository = SettingsRepository(this))
				}
			}
		}
	}
}

@Composable
private fun ZooRemoteNavHost(
	settingsRepository: SettingsRepository,
	navController: NavHostController = rememberNavController(),
) {
	// Wait for the DataStore's first emission (always available immediately) to decide the start
	// destination without blocking the main thread with runBlocking.
	var savedSettings by remember { mutableStateOf<ConnectionSettings?>(null) }
	LaunchedEffect(settingsRepository) {
		savedSettings = settingsRepository.observe().first()
	}

	if (savedSettings == null) return

	val startOnStatus = savedSettings?.isValid() == true
	val startDestination = if (startOnStatus) "status" else "setup"
	NavHost(navController = navController, startDestination = startDestination) {
		composable("setup") {
			SetupScreen(
				onSuccess = {
					navController.navigate("status") { popUpTo("setup") { inclusive = true } }
				},
			)
		}
		composable("status") {
			StatusScreen(onOpenSettings = { navController.navigate("setup") })
		}
		// Session 7: composable("mode"), composable("model") — chips become clickable.
	}
}
