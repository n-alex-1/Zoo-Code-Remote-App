package de.xilox.zooremote.app

import android.app.Application
import de.xilox.zooremote.app.data.connection.ConnectionRepository

/**
 * Application class acting as the minimal DI container (session 5 spec): holds the app-scoped
 * [ConnectionRepository] so that ViewModels — and later the foreground service (session 6) —
 * share one connection.
 */
class ZooRemoteApp : Application() {

	val connectionRepository: ConnectionRepository by lazy { ConnectionRepository(this) }

	companion object {
		fun from(application: Application): ZooRemoteApp = application as ZooRemoteApp
	}
}
