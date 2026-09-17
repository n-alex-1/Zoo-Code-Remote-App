package de.xilox.zooremote.app

import android.app.Application
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import de.xilox.zooremote.app.data.connection.ConnectionRepository
import de.xilox.zooremote.app.service.AskNotifier
import de.xilox.zooremote.app.service.ConnectionService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Application class acting as the minimal DI container (session 5): holds the app-scoped
 * [ConnectionRepository] so that ViewModels — and the foreground service (session 6) — share one
 * connection. Session 6 adds the app-scoped [AskNotifier] for ask-notifications.
 *
 * Session 10: a [ProcessLifecycleOwner] observer stops the [ConnectionService] as soon as every
 * activity is stopped (app in background / closed), so the "connected" notification does not sit
 * around forever, and restarts it when the app comes back to the foreground with valid settings.
 */
class ZooRemoteApp : Application() {

	private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

	override fun onCreate() {
		super.onCreate()
		app = this // static reference so data-layer classes (no Context) can resolve localized strings
		ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
			override fun onStop(owner: LifecycleOwner) {
				// All activities stopped → app in background or closed. Stop the service unless a
				// foreground start is already queued/running (e.g. right after ON_START).
				if (!ConnectionService.isRunning && !ConnectionService.hasPendingStart) return
				ConnectionService.stop(this@ZooRemoteApp)
			}

			override fun onResume(owner: LifecycleOwner) {
				scope.launch {
					val settings = connectionRepository.savedSettings()
					if (settings.isValid() && !ConnectionService.isRunning) ConnectionService.start(this@ZooRemoteApp)
				}
			}
		})
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
