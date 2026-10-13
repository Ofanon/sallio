package fr.sallio.app

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import java.time.LocalDate

/**
 * "Réserver" on the "Pas encore réservé pour demain midi" notification: books
 * the lunch on TurboSelf without opening the app, then says how it went in the
 * same notification. Only on that tap, never on its own: a lunch costs money.
 */
class CanteenActions : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val date = intent.getStringExtra(EXTRA_DATE)?.let { runCatching { LocalDate.parse(it) }.getOrNull() } ?: return
        val id = intent.getIntExtra(EXTRA_NOTIFICATION, 0)
        val app = context.applicationContext
        val result = goAsync()
        show(app, id, date, "Réservation en cours…", null, ongoing = true)
        Thread {
            try {
                val canteen = Canteen.get(app)
                val day = canteen.data()?.day(date)
                val error = when {
                    day == null -> "Ce jour n'est plus proposé par Turboself."
                    day.booking.booked > 0 -> null
                    else -> canteen.book(day, true)
                }
                Analytics.event("cantine_reservation_notification", "resultat" to if (error == null) "ok" else "erreur")
                if (error == null) show(app, id, date, "C'est réservé ✓", canteen.data()?.balance?.let { "Solde : ${Canteen.money(it.estimatedAmount)}" }, done = true)
                else show(app, id, date, "Pas réservé", error)
                Widgets.refreshAll(app)
            } finally {
                result.finish()
            }
        }.start()
    }

    private fun show(context: Context, id: Int, date: LocalDate, title: String, text: String?, ongoing: Boolean = false, done: Boolean = false) {
        val day = if (date == LocalDate.now().plusDays(1)) "demain midi" else "${Agenda.dayName(date).lowercase()} midi"
        val builder = Notification.Builder(context, "canteen")
            .setSmallIcon(R.drawable.ic_coin)
            .setLargeIcon(NotificationLook.appTile(context, R.drawable.ic_coin))
            .setContentTitle("$title · $day")
            .setOnlyAlertOnce(true)
            .setOngoing(ongoing)
            .setAutoCancel(!ongoing)
            .setContentIntent(Widgets.openSheet(context, MainActivity.OPEN_CANTEEN, 71))
        text?.let { builder.setContentText(it).setStyle(Notification.BigTextStyle().bigText(it)) }
        if (ongoing) builder.setProgress(0, 0, true)
        // Booked: the good news stays a moment, then leaves.
        if (done) builder.setTimeoutAfter(8_000)
        runCatching { context.getSystemService(NotificationManager::class.java).notify(id, builder.build()) }
    }

    companion object {
        private const val EXTRA_DATE = "date"
        private const val EXTRA_NOTIFICATION = "notification"

        /** The "Réserver" button for a lunch on [date], in the notification [id]. */
        fun book(context: Context, date: LocalDate, id: Int): Notification.Action = Notification.Action.Builder(
            android.graphics.drawable.Icon.createWithResource(context, R.drawable.ic_check), "Réserver",
            PendingIntent.getBroadcast(
                context, 80,
                Intent(context, CanteenActions::class.java).putExtra(EXTRA_DATE, date.toString()).putExtra(EXTRA_NOTIFICATION, id),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            ),
        ).build()
    }
}
