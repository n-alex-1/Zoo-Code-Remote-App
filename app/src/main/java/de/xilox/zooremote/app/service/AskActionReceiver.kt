package de.xilox.zooremote.app.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import de.xilox.zooremote.app.R
import de.xilox.zooremote.app.ZooRemoteApp
import de.xilox.zooremote.app.data.SettingsRepository
import de.xilox.zooremote.app.data.api.TlsTrust
import de.xilox.zooremote.app.data.api.ZooApi
import de.xilox.zooremote.app.data.api.ZooApiException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Receives the "Genehmigen" / "Ablehnen" actions from the ask notification (session 6).
 * Runs `POST /api/ask/respond` on an IO thread via [goAsync] and reports the result with a
 * transient notification (success clears the ask notification; failure shows a short error).
 */
class AskActionReceiver : BroadcastReceiver() {

	companion object {
		const val EXTRA_RESPONSE = "response" // "yesButtonClicked" | "noButtonClicked"

		fun intent(context: Context, response: String): Intent =
			Intent(context, AskActionReceiver::class.java).putExtra(EXTRA_RESPONSE, response)
	}

	override fun onReceive(context: Context, intent: Intent) {
		val pendingResult = goAsync()
		val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
		scope.launch {
			val response = intent.getStringExtra(EXTRA_RESPONSE).orEmpty()
			var ok = false
			var errorText: String? = null
			try {
				val settings = SettingsRepository(context.applicationContext).observe().first()
				if (!settings.isValid()) {
					errorText = "Keine gültigen Verbindungseinstellungen — bitte neu pairen."
				} else {
					val client = TlsTrust.client(settings.certFingerprint)
					val api = ZooApi(client, settings.baseUrl(), settings.token)
					api.respondToAsk(response)
					ok = true
				}
			} catch (e: ZooApiException) {
				errorText = when (e.httpCode) {
					409 -> "Anfrage bereits beantwortet."
					401 -> "Falscher Token — bitte neu pairen."
					else -> e.message ?: "HTTP-Fehler"
				}
			} catch (e: Exception) {
				errorText = e.message ?: e::class.java.simpleName
			}

			if (ok) {
				(context.applicationContext as ZooRemoteApp).askNotifier.clear()
			} else {
				showResult(context, errorText ?: "Unbekannter Fehler")
			}
			pendingResult.finish()
			scope.cancel()
		}
	}

	private fun showResult(context: Context, text: String) {
		val notification = NotificationCompat.Builder(context, ConnectionService.CHANNEL_ASK)
			.setSmallIcon(R.drawable.ic_notification)
			.setContentTitle("Zoo Remote — Fehler")
			.setContentText(text)
			.setAutoCancel(true)
			.setTimeoutAfter(5_000L)
			.build()
		context.getSystemService(android.app.NotificationManager::class.java).notify(ConnectionService.NOTIFICATION_ID_ACTION_RESULT, notification)
	}
}
