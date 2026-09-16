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

	override fun onCreate() {
		super.onCreate()
		app = this // static reference so data-layer classes (no Context) can resolve localized strings
	}

	val connectionRepository: ConnectionRepository by lazy { ConnectionRepository(this) }

	/** App-scoped notifier; its start/stop is driven by [de.xilox.zooremote.app.service.ConnectionService]. */
	val askNotifier: AskNotifier by lazy { AskNotifier(this, connectionRepository) }

	companion object {
		/** Set in [onCreate]; null only before app start (e.g. plain unit tests). */
		var app: ZooRemoteApp? = null

		fun from(application: Application): ZooRemoteApp = application as ZooRemoteApp

		/** Locale-aware string lookup for non-Android classes; falls back to the res id name when no app exists yet. */
		fun tr(@androidx.annotation.StringRes resId: Int, vararg args: Any): String {
			val a = app ?: return "str_$resId"
			return if (args.isEmpty()) a.getString(resId) else a.getString(resId, *args)
		}
	}
}
