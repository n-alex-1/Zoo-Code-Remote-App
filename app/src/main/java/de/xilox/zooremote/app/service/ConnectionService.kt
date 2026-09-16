package de.xilox.zooremote.app.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import de.xilox.zooremote.app.MainActivity
import de.xilox.zooremote.app.R
import de.xilox.zooremote.app.ZooRemoteApp
import de.xilox.zooremote.app.data.connection.ConnectionState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * Foreground service (session 6) that keeps the status socket alive with a persistent,
 * low-priority notification so ask-notifications arrive even when the app is closed.
 *
 * - `START_STICKY`: after process death Android restarts it; the repository reconnects on its own.
 * - [ACTION_DISCONNECT]: clean shutdown (socket closed, notifications removed).
 * - View models observe the shared [de.xilox.zooremote.app.data.connection.ConnectionRepository]
 *   state flows — the service only guarantees that a connection exists and owns the notification
 *   side-effects ([AskNotifier]).
 */
class ConnectionService : Service() {

	companion object {
		const val ACTION_DISCONNECT = "de.xilox.zooremote.app.action.DISCONNECT"

		const val CHANNEL_CONNECTION = "connection"
		const val CHANNEL_ASK = "ask"

		const val NOTIFICATION_ID_CONNECTION = 1
		const val NOTIFICATION_ID_ASK = 2
		const val NOTIFICATION_ID_ACTION_RESULT = 3

		fun start(context: Context) {
			val intent = Intent(context, ConnectionService::class.java)
			if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(intent) else context.startService(intent)
		}
	}

	private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
	private lateinit var app: ZooRemoteApp

	override fun onCreate() {
		super.onCreate()
		app = ZooRemoteApp.from(application)
		createChannels()
		scope.launch {
			// Keep the persistent notification's text in sync with the connection state.
			app.connectionRepository.connectionState.collectLatest { state ->
				val manager = getSystemService(NotificationManager::class.java)
				manager.notify(NOTIFICATION_ID_CONNECTION, persistentNotification(textFor(state)))
			}
		}
	}

	override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
		if (intent?.action == ACTION_DISCONNECT) {
			app.connectionRepository.disconnect()
			stopForeground(STOP_FOREGROUND_REMOVE)
			stopSelf()
			return START_NOT_STICKY
		}

		// Must happen promptly after onStartCommand — post the basic notification synchronously.
		startForeground(NOTIFICATION_ID_CONNECTION, persistentNotification(getString(R.string.notif_connected)))

		scope.launch {
			// The service is authoritative for keeping a connection alive; only connect when none
			// exists yet (e.g. first start or sticky restart after process death).
			if (!app.connectionRepository.isActive()) {
				val settings = app.connectionRepository.savedSettings()
				if (settings.isValid()) app.connectionRepository.connect(settings)
			}
		}

		app.askNotifier.start(scope)
		return START_STICKY
	}

	override fun onDestroy() {
		app.askNotifier.stop()
		scope.cancel()
		super.onDestroy()
	}

	override fun onBind(intent: Intent?): IBinder? = null

	private fun createChannels() {
		if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
		val manager = getSystemService(NotificationManager::class.java)
		manager.createNotificationChannel(
			NotificationChannel(CHANNEL_CONNECTION, getString(R.string.channel_connection_name), NotificationManager.IMPORTANCE_LOW).apply {
				description = getString(R.string.channel_connection_desc)
			},
		)
		manager.createNotificationChannel(
			NotificationChannel(CHANNEL_ASK, getString(R.string.channel_ask_name), NotificationManager.IMPORTANCE_HIGH).apply {
				description = getString(R.string.channel_ask_desc)
			},
		)
	}

	private fun persistentNotification(text: String): android.app.Notification {
		val contentIntent = PendingIntent.getActivity(
			this, 0, Intent(this, MainActivity::class.java),
			PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
		)
		return NotificationCompat.Builder(this, CHANNEL_CONNECTION)
			.setSmallIcon(R.drawable.ic_notification)
			.setContentTitle(getString(R.string.app_name))
			.setContentText(text)
			.setStyle(NotificationCompat.BigTextStyle().bigText(text))
			.setContentIntent(contentIntent)
			.setOngoing(true)
			.setPriority(NotificationCompat.PRIORITY_LOW)
			.build()
	}

	private fun textFor(state: ConnectionState): String = when (state) {
		is ConnectionState.Connected -> getString(R.string.notif_connected)
		is ConnectionState.Connecting -> getString(R.string.notif_connecting)
		is ConnectionState.Error -> getString(R.string.notif_error, state.message)
		else -> getString(R.string.notif_disconnected)
	}
}
