package fr.sallio.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import java.time.Duration
import java.time.LocalDateTime

/**
 * Notifications for what a sync found changed: a teacher absent, a lesson
 * cancelled or moved, a new grade, a new message. One channel per kind, so
 * the student can mute grades and keep the timetable from Android's settings.
 */
object Alerts {

    private const val PREF = "alerts"

    private class Channel(val id: String, val name: String, val description: String, val tab: MainActivity.Tab, val icon: Int)

    private val CHANNELS = mapOf(
        Changes.Kind.LESSON to Channel("lesson_changes", "Changements d'emploi du temps",
            "Prof absent, cours annulé ou déplacé, changement de salle.", MainActivity.Tab.TIMETABLE, R.drawable.ic_calendar),
        Changes.Kind.GRADE to Channel("new_grades", "Nouvelles notes",
            "Une note qui arrive sur Pronote. Elle n'est pas affichée sur l'écran verrouillé.", MainActivity.Tab.GRADES, R.drawable.ic_star),
        Changes.Kind.INBOX to Channel("inbox", "Messages et actualités",
            "Un nouveau message ou une nouvelle actualité.", MainActivity.Tab.INBOX, R.drawable.ic_news),
        Changes.Kind.SCHOOL_LIFE to Channel("school_life", "Vie scolaire",
            "Une absence, un retard, une punition ou une dispense enregistrés sur Pronote.", MainActivity.Tab.INBOX, R.drawable.ic_absent),
        Changes.Kind.CANTEEN to Channel("canteen", "Cantine",
            "Solde Turboself bas, ou repas de demain pas encore réservé.", MainActivity.Tab.HOME, R.drawable.ic_coin),
    )

    /** On unless the student turned them off; Android's permission still decides. */
    fun isEnabled(context: Context) = context.getSharedPreferences("ui", Context.MODE_PRIVATE).getBoolean(PREF, true)

    fun setEnabled(context: Context, on: Boolean) {
        context.getSharedPreferences("ui", Context.MODE_PRIVATE).edit().putBoolean(PREF, on).apply()
        if (!on) context.getSystemService(NotificationManager::class.java).let { nm ->
            nm.activeNotifications.filter { it.notification.group?.startsWith(GROUP) == true }.forEach { nm.cancel(it.id) }
        }
    }

    private const val GROUP = "changes."

    /** Every channel at once, so Android's settings list them all before the first alert. */
    fun createChannels(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java)
        for (channel in CHANNELS.values) {
            nm.createNotificationChannel(NotificationChannel(channel.id, channel.name, NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = channel.description
            })
        }
        Reminders.channel(context)
    }

    fun post(context: Context, items: List<Changes.Item>, now: LocalDateTime = LocalDateTime.now()) {
        if (items.isEmpty() || !isEnabled(context)) return
        val nm = context.getSystemService(NotificationManager::class.java)
        if (!nm.areNotificationsEnabled()) return
        for ((kind, list) in items.groupBy { it.kind }) {
            val channel = CHANNELS.getValue(kind)
            nm.createNotificationChannel(NotificationChannel(channel.id, channel.name, NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = channel.description
            })
            val open = Widgets.openApp(context, channel.tab)
            fun openFor(item: Changes.Item) = when {
                kind == Changes.Kind.SCHOOL_LIFE -> Widgets.openApp(context, channel.tab, InboxTab.Mode.LIFE)
                kind == Changes.Kind.CANTEEN -> Widgets.openSheet(context, MainActivity.OPEN_CANTEEN, 73)
                item.key.startsWith("message|") -> Widgets.openApp(context, channel.tab, InboxTab.Mode.MESSAGES)
                item.key.startsWith("news|") -> Widgets.openApp(context, channel.tab, InboxTab.Mode.NEWS)
                else -> open
            }
            val shown = nm.activeNotifications.filter { it.notification.group == GROUP + kind.name && !it.notification.isGroupSummary() }.map { it.id }
            for (item in list) {
                val style = item.subject?.let { Subjects.style(it) }
                val builder = Notification.Builder(context, channel.id)
                    .setSmallIcon(style?.icon ?: channel.icon)
                    .setLargeIcon(item.subject?.let { NotificationLook.subjectTile(context, it) } ?: NotificationLook.appTile(context, channel.icon))
                    .setContentTitle(item.title)
                    .setContentText(item.text)
                    .setStyle(Notification.BigTextStyle().bigText(item.text))
                    .setGroup(GROUP + kind.name)
                    .setAutoCancel(true)
                    .setContentIntent(openFor(item))
                style?.let { builder.setColor(Subjects.solid(it.family)) }
                item.until?.let { builder.setTimeoutAfter(Duration.between(now, it).toMillis().coerceAtLeast(60_000L)) }
                item.publicTitle?.let { title ->
                    builder.setVisibility(Notification.VISIBILITY_PRIVATE)
                    builder.setPublicVersion(Notification.Builder(context, channel.id)
                        .setSmallIcon(style?.icon ?: channel.icon)
                        .setContentTitle(title)
                        .build())
                }
                // "Pas encore réservé pour demain midi": book from the notification itself.
                if (item.key.startsWith("canteen|book|") && Canteen.get(context).canAfford()) runCatching {
                    builder.addAction(CanteenActions.book(context, java.time.LocalDate.parse(item.key.substringAfterLast('|')), item.key.hashCode()))
                }
                runCatching { nm.notify(item.key.hashCode(), builder.build()) }
            }
            // Android bundles a group only under a summary.
            if ((shown + list.map { it.key.hashCode() }).toSet().size > 1) {
                runCatching {
                    nm.notify(SUMMARY_ID + kind.ordinal, Notification.Builder(context, channel.id)
                        .setSmallIcon(channel.icon)
                        .setContentTitle(channel.name)
                        .setGroup(GROUP + kind.name)
                        .setGroupSummary(true)
                        .setAutoCancel(true)
                        .setContentIntent(open)
                        .build())
                }
            }
        }
    }

    /**
     * Debug builds: invents changes on the stored data (next lesson cancelled,
     * the one after in another room, last grade and message as new) and posts
     * them. Nothing is sent to PRONOTE.
     */
    fun demo(context: Context) {
        val repo = Repo.get(context)
        val now = LocalDateTime.now()
        val lessons = repo.lessons()
        val upcoming = lessons.filter { it.end > now }.sortedBy { it.start }.take(2)
        val after = lessons.map { l ->
            when (l) {
                upcoming.getOrNull(0) -> l.copy(status = "Prof. absent", canceled = true, modified = true)
                upcoming.getOrNull(1) -> l.copy(rooms = listOf("TEST 001"), modified = true)
                else -> l
            }
        }
        val grades = repo.report()?.grades.orEmpty()
        val discussions = repo.discussions()
        val items = Changes.lessons(lessons, after, now) +
            Changes.grades(grades.drop(1), grades) +
            Changes.messages(discussions.take(1).map { it.copy(unread = it.unread - 1) }, discussions.take(1).map { it.copy(count = it.count + 1) })
        post(context, items, now)
    }

    private fun Notification.isGroupSummary() = flags and Notification.FLAG_GROUP_SUMMARY != 0

    private const val SUMMARY_ID = 7_000
}
