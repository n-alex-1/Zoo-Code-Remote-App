package de.xilox.zooremote.app

import android.app.Application
import de.xilox.zooremote.app.data.connection.ConnectionRepository
import de.xilox.zooremote.app.service.AskNotifier

/**
 * Application class acting as the minimal DI container (session 5): holds the app-scoped
 * [ConnectionRepository] so that ViewModels — and the foreground service (session 6) — share one
 * connection. Session 6 adds the app-scoped [AskNotifier] for ask-notifications.
 */
class ZooRemoteApp : Application() {

	val connectionRepository: ConnectionRepository by lazy { ConnectionRepository(this) }

	/** App-scoped notifier; its start/stop is driven by [de.xilox.zooremote.app.service.ConnectionService]. */
	val askNotifier: AskNotifier by lazy { AskNotifier(this, connectionRepository) }

	companion object {
		fun from(application: Application): ZooRemoteApp = application as ZooRemoteApp
	}
}
