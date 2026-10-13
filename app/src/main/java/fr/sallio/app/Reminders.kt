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
    private const val LATE_MARGIN_MIN = 15L

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

    private fun reminded(context: Context) = context.getSharedPreferences("ui", Context.MODE_PRIVATE).getString("remindedLesson", null)

    /**
     * Arms the alarm for the next lesson not reminded yet: exact, [BEFORE_MIN]
     * minutes before. Without exact alarms Android may deliver up to 10 minutes
     * late (seen on the Pixel: a 15h34 alarm allowed until 15h44, after a 15h40
     * lesson), so it is then set 15 minutes before, its window ending before the
     * lesson starts.
     */
    fun schedule(context: Context) {
        if (!isEnabled(context)) return
        val now = LocalDateTime.now()
        val done = reminded(context)
        val next = Repo.get(context).lessons()
            .filter { it.start > now.plusMinutes(1) && it.start.toString() != done }
            .minByOrNull { it.start } ?: return
        val alarms = context.getSystemService(AlarmManager::class.java)
        val exact = android.os.Build.VERSION.SDK_INT < 31 || alarms.canScheduleExactAlarms()
        val at = next.start.minusMinutes(if (exact) BEFORE_MIN else LATE_MARGIN_MIN).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
            .coerceAtLeast(System.currentTimeMillis() + 1_000)
        if (exact) alarms.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pending(context))
        else alarms.setWindow(AlarmManager.RTC_WAKEUP, at, 10 * 60_000L, pending(context))
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
        // Up to the start itself: an alarm a little late still beats no reminder.
        val lesson = Repo.get(context).lessons()
            .filter { it.start > now.minusMinutes(1) && Duration.between(now, it.start).toMinutes() <= LATE_MARGIN_MIN + 1 }
            .filter { it.start.toString() != reminded(context) }
            .minByOrNull { it.start }
        if (lesson != null) {
            context.getSharedPreferences("ui", Context.MODE_PRIVATE).edit().putString("remindedLesson", lesson.start.toString()).apply()
            channel(context)
            val minutes = Duration.between(now, lesson.start).toMinutes().coerceAtLeast(0)
            val alert = Agenda.alert(lesson)
            val title = if (lesson.canceled) "${Agenda.pretty(lesson.subject)} : ${alert ?: "cours annulé"}"
            else "${Agenda.room(lesson)} · ${Agenda.pretty(lesson.subject)}"
            val text = if (lesson.canceled) "Prévu à ${Agenda.time(lesson.start)}, il n'aura pas lieu."
            else (if (minutes == 0L) "Maintenant, à ${Agenda.time(lesson.start)}" else "Dans $minutes min, à ${Agenda.time(lesson.start)}") + (alert?.let { " · $it" } ?: "") +
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
