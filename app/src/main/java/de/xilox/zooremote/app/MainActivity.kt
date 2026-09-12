package de.xilox.zooremote.app

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.navigation.compose.currentBackStackEntryAsState
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
import de.xilox.zooremote.app.service.ConnectionService
import de.xilox.zooremote.app.ui.models.ModelScreen
import de.xilox.zooremote.app.ui.modes.ModeScreen
import de.xilox.zooremote.app.ui.setup.SetupScreen
import de.xilox.zooremote.app.ui.status.StatusScreen
import kotlinx.coroutines.flow.first

/**
 * Single-activity host. Session 5 added the Status destination (Setup ↔ Status navigation, start
 * on Status when valid settings exist). Session 6 starts the foreground [ConnectionService] and
 * requests the Android 13+ notification permission so ask-notifications can appear.
 */
class MainActivity : ComponentActivity() {

	override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)
		setContent {
			val dark = isSystemInDarkTheme()
			MaterialTheme(colorScheme = if (dark) darkColorScheme() else lightColorScheme()) {
				Surface(modifier = Modifier.fillMaxSize()) {
					ZooRemoteNavHost(settingsRepository = SettingsRepository(this), activity = this)
				}
			}
		}
	}
}

@Composable
private fun ZooRemoteNavHost(
	settingsRepository: SettingsRepository,
	activity: ComponentActivity,
	app: ZooRemoteApp = (activity.applicationContext as ZooRemoteApp),
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

	// Session 6: keep the connection alive in a foreground service and make sure notifications
	// are allowed (Android 13+ runtime permission).
	if (savedSettings!!.isValid()) {
		ConnectionService.start(activity.applicationContext)
	}
	RequestNotificationPermissionIfNeeded(activity, savedSettings!!.isValid())

	// Session 8b: a REST-level auth failure (HTTP 401 / wrong token) sends the user back to the
	// setup screen with the error message — retrying any action cannot succeed until re-pairing.
	val authError by app.connectionRepository.authError.collectAsState()
	val backStackEntry by navController.currentBackStackEntryAsState()
	LaunchedEffect(authError, backStackEntry?.destination?.route) {
		if (authError != null && backStackEntry?.destination?.route != "setup") {
			navController.navigate("setup") { popUpTo(navController.graph.startDestinationId) }
		}
	}

	NavHost(navController = navController, startDestination = startDestination) {
		composable("setup") {
			SetupScreen(
				authError = authError,
				onSuccess = {
					app.connectionRepository.clearAuthError()
					navController.navigate("status") { popUpTo("setup") { inclusive = true } }
				},
			)
		}
		composable("status") {
			StatusScreen(
				onOpenSettings = { navController.navigate("setup") },
				onOpenModes = { navController.navigate("modes") },
				onOpenModels = { navController.navigate("models") },
			)
		}
		// Session 7: mode & model pickers, reachable from the status screen's chips.
		composable("modes") {
			ModeScreen(onBack = { navController.popBackStack() })
		}
		composable("models") {
			ModelScreen(onBack = { navController.popBackStack() })
		}
	}
}

@Composable
private fun RequestNotificationPermissionIfNeeded(activity: ComponentActivity, needed: Boolean) {
	if (!needed || Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
	var granted by remember { mutableStateOf(ContextCompat.checkSelfPermission(activity, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) }
	val launcher = androidx.activity.compose.rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { result -> granted = result }
	LaunchedEffect(Unit) { if (!granted) launcher.launch(Manifest.permission.POST_NOTIFICATIONS) }
}
