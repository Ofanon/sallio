package fr.sallio.app

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import android.net.Uri

/**
 * Buttons on Sallio's notifications (Oscar, 2026-10-16): "J'ai vu" on a new
 * actualité, "Recharger" on the canteen alerts, "C'est prêt" on the evening
 * bag. Each does its thing without opening the app, then the notification goes.
 */
class NotificationActions : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext
        val id = intent.getIntExtra(EXTRA_ID, 0)
        when (intent.action) {
            ACTION_NEWS_READ -> {
                val key = intent.getStringExtra(EXTRA_KEY) ?: return
                val result = goAsync()
                Thread {
                    try {
                        val repo = Repo.get(app)
                        repo.news().firstOrNull { it.key == key }?.let { repo.setRead(it) }
                        app.getSystemService(NotificationManager::class.java).cancel(id)
                        Analytics.event("notification_bouton", "bouton" to "jai_vu")
                        // Sent now if a session is fresh, else with the next sync.
                        repo.pushPending()
                        if (repo.pendingTotal() > 0) SyncJob.runLater(app, repo.waitBeforeLogin(manual = true))
                        Widgets.refreshAll(app)
                    } finally {
                        result.finish()
                    }
                }.start()
            }
            ACTION_BAG_READY -> {
                Bag.packAll(app)
                app.getSystemService(NotificationManager::class.java).cancel(id)
                Analytics.event("notification_bouton", "bouton" to "sac_pret")
            }
        }
    }

    companion object {
        private const val ACTION_NEWS_READ = "fr.sallio.app.NEWS_READ"
        private const val ACTION_BAG_READY = "fr.sallio.app.BAG_READY"
        private const val EXTRA_KEY = "key"
        private const val EXTRA_ID = "id"

        private fun broadcast(context: Context, action: String, code: Int, extras: Intent.() -> Unit) = PendingIntent.getBroadcast(
            context, code, Intent(context, NotificationActions::class.java).setAction(action).apply(extras),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        fun newsRead(context: Context, newsKey: String, id: Int): Notification.Action = Notification.Action.Builder(
            Icon.createWithResource(context, R.drawable.ic_check), "J'ai vu",
            broadcast(context, ACTION_NEWS_READ, id) { putExtra(EXTRA_KEY, newsKey); putExtra(EXTRA_ID, id) },
        ).build()

        fun bagReady(context: Context, id: Int): Notification.Action = Notification.Action.Builder(
            Icon.createWithResource(context, R.drawable.ic_check), "C'est prêt",
            broadcast(context, ACTION_BAG_READY, 90) { putExtra(EXTRA_ID, id) },
        ).build()

        /** Opens TurboSelf's site, where the balance is topped up. */
        fun recharge(context: Context): Notification.Action = Notification.Action.Builder(
            Icon.createWithResource(context, R.drawable.ic_coin), "Recharger",
            PendingIntent.getActivity(context, 91, Intent(Intent.ACTION_VIEW, Uri.parse(CanteenUI.ESPACE)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT),
        ).build()
    }
}
