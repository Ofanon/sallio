package fr.sallio.app

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.app.Notification
import java.time.Duration
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * "Maths dans 5 min · B204": a notification a few minutes before each lesson,
 * from the stored timetable, so the end of the break never needs PRONOTE.
 */
object Reminders {

    private const val PREF = "reminders"
    private const val CHANNEL = "rooms"
    private const val BEFORE_MIN = 5L

    fun isEnabled(context: Context) = context.getSharedPreferences("ui", Context.MODE_PRIVATE).getBoolean(PREF, false)

    fun setEnabled(context: Context, on: Boolean) {
        context.getSharedPreferences("ui", Context.MODE_PRIVATE).edit().putBoolean(PREF, on).apply()
        if (on) schedule(context) else cancel(context)
    }

    private fun pending(context: Context) = PendingIntent.getBroadcast(
        context, 40, Intent(context, ReminderReceiver::class.java),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    fun cancel(context: Context) {
        context.getSystemService(AlarmManager::class.java).cancel(pending(context))
        context.getSystemService(NotificationManager::class.java).cancel(1)
    }

    /** Arms the alarm for the next lesson start (inexact, within a few minutes). */
    fun schedule(context: Context) {
        if (!isEnabled(context)) return
        val now = LocalDateTime.now()
        val next = Repo.get(context).lessons()
            .filter { it.start.minusMinutes(BEFORE_MIN) > now }
            .minByOrNull { it.start } ?: return
        val at = next.start.minusMinutes(BEFORE_MIN + 1).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        context.getSystemService(AlarmManager::class.java)
            .setWindow(AlarmManager.RTC_WAKEUP, at, 2 * 60_000L, pending(context))
    }

    fun channel(context: Context) {
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, "Rappels de salle", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Quelques minutes avant chaque cours, la salle où aller."
                setShowBadge(false)
            }
        )
    }

    internal fun notifyNext(context: Context) {
        val now = LocalDateTime.now()
        val lesson = Repo.get(context).lessons()
            .filter { it.start > now && Duration.between(now, it.start).toMinutes() <= BEFORE_MIN + 3 }
            .minByOrNull { it.start }
        if (lesson != null) {
            channel(context)
            val minutes = Duration.between(now, lesson.start).toMinutes().coerceAtLeast(1)
            val alert = Agenda.alert(lesson)
            val title = if (lesson.canceled) "${Agenda.pretty(lesson.subject)} : ${alert ?: "cours annulé"}"
            else "${Agenda.room(lesson)} · ${Agenda.pretty(lesson.subject)}"
            val text = if (lesson.canceled) "Prévu à ${Agenda.time(lesson.start)}, il n'aura pas lieu."
            else "Dans $minutes min, à ${Agenda.time(lesson.start)}" + (alert?.let { " · $it" } ?: "") +
                (lesson.teachers.firstOrNull()?.let { " · ${Agenda.prettyName(it)}" } ?: "")
            val notification = Notification.Builder(context, CHANNEL)
                .setSmallIcon(Subjects.style(lesson.subject).icon)
                .setColor(Subjects.solid(Subjects.style(lesson.subject).family))
                .setContentTitle(title)
                .setContentText(text)
                .setCategory(Notification.CATEGORY_REMINDER)
                .setAutoCancel(true)
                .setTimeoutAfter(Duration.between(now, lesson.end).toMillis().coerceAtLeast(60_000L))
                .setContentIntent(Widgets.openApp(context, MainActivity.Tab.TIMETABLE))
                .build()
            runCatching { context.getSystemService(NotificationManager::class.java).notify(1, notification) }
        }
        schedule(context)
    }
}

class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) = Reminders.notifyNext(context)
}
