package de.xilox.zooremote.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import de.xilox.zooremote.app.ui.setup.SetupScreen

/**
 * Single-activity host. Session 4 only contains the Setup destination; later sessions add
 * Status / Mode / Model screens to this NavGraph (see docs/architektur.md section 2).
 */
class MainActivity : ComponentActivity() {

	override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)
		setContent {
			val dark = isSystemInDarkTheme()
			MaterialTheme(colorScheme = if (dark) darkColorScheme() else lightColorScheme()) {
				Surface(modifier = Modifier.fillMaxSize()) {
					ZooRemoteNavHost()
				}
			}
		}
	}
}

@androidx.compose.runtime.Composable
private fun ZooRemoteNavHost() {
	val navController = rememberNavController()
	NavHost(navController = navController, startDestination = "setup") {
		composable("setup") { SetupScreen() }
		// Session 5+: composable("status") { StatusScreen(...) }, composable("mode"), composable("model")
	}
}
