package de.xilox.zooremote.app.service

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import de.xilox.zooremote.app.MainActivity
import de.xilox.zooremote.app.R
import de.xilox.zooremote.app.data.api.AskText
import de.xilox.zooremote.app.data.api.PendingAsk
import de.xilox.zooremote.app.data.api.RemoteStatus
import de.xilox.zooremote.app.data.connection.ConnectionRepository
import de.xilox.zooremote.app.data.connection.ConnectionState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * Shows the "Eingabe erforderlich" notification when the task state switches to
 * `waiting_for_input` (session 6), and removes it as soon as the state leaves that value.
 *
 * Dedupe: a pendingAsk is only notified once — identified by taskId + askType + question hash, so
 * re-renders of the same status do not stack notifications while a new question does.
 */
class AskNotifier(
	private val context: Context,
	private val repository: ConnectionRepository,
) {

	private var job: Job? = null

	/** Identity of the currently notified ask (null = none shown). */
	@Volatile
	private var notifiedKey: String? = null

	fun start(scope: CoroutineScope) {
		if (job?.isActive == true) return
		job = scope.launch {
			repository.status.collectLatest { status ->
				handleStatus(status, repository.connectionState.value)
			}
		}
	}

	fun stop() {
		job?.cancel()
		job = null
		removeAskNotification()
	}

	private fun handleStatus(status: RemoteStatus?, connectionState: ConnectionState) {
		val ask = status?.task?.pendingAsk
		if (status != null && status.task.state == "waiting_for_input" && ask != null && connectionState is ConnectionState.Connected) {
			val key = "${status.task.taskId ?: "-"}|${ask.askType}|${(AskText.question(ask.askType, ask.question)?.hashCode() ?: 0)}"
			if (key != notifiedKey) {
				notifiedKey = key
				showNotification(ask)
			}
		} else if (notifiedKey != null) {
			removeAskNotification()
		}
	}

	private fun showNotification(ask: PendingAsk) {
		val question = AskText.question(ask.askType, ask.question).orEmpty()
		val contentIntent = PendingIntent.getActivity(
			context, 0, Intent(context, MainActivity::class.java),
			PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
		)
		val builder = NotificationCompat.Builder(context, ConnectionService.CHANNEL_ASK)
			.setSmallIcon(R.drawable.ic_notification)
			.setContentTitle("Zoo Remote — Eingabe erforderlich")
			.setContentText(question.ifEmpty { "Task wartet auf ${ask.askType}" })
			.setStyle(NotificationCompat.BigTextStyle().bigText(question))
			.setAutoCancel(false)
			.setOngoing(true)
			.setContentIntent(contentIntent)

		if (ask.canApprove) {
			builder.addAction(
				NotificationCompat.Action.Builder(0, "Genehmigen", actionPendingIntent("yesButtonClicked")).build(),
			)
			builder.addAction(
				NotificationCompat.Action.Builder(1, "Ablehnen", actionPendingIntent("noButtonClicked")).build(),
			)
		}

		context.getSystemService(NotificationManager::class.java).notify(ConnectionService.NOTIFICATION_ID_ASK, builder.build())
	}

	private fun actionPendingIntent(response: String): PendingIntent =
		PendingIntent.getBroadcast(context, response.hashCode() and 0x7fffffff, AskActionReceiver.intent(context, response), PendingIntent.FLAG_IMMUTABLE)

	fun clear() {
		removeAskNotification()
	}

	private fun removeAskNotification() {
		notifiedKey = null
		context.getSystemService(NotificationManager::class.java).cancel(ConnectionService.NOTIFICATION_ID_ASK)
	}
}
