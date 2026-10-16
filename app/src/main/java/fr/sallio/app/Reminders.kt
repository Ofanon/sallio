package fr.sallio.app

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.app.Notification
import fr.sallio.pronote.Lesson
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
        val all = Repo.get(context).lessons()
        if (lesson != null) {
            context.getSharedPreferences("ui", Context.MODE_PRIVATE).edit().putString("remindedLesson", lesson.start.toString()).apply()
            val secondHour = all.any { it !== lesson && Agenda.continues(it, lesson) }
            if (!secondHour) show(context, Agenda.blocks(all.filter { it.start.toLocalDate() == lesson.start.toLocalDate() })
                .firstOrNull { it.start == lesson.start && it.subject == lesson.subject } ?: lesson, lesson.start, now)
        }
        schedule(context)
    }

    /**
     * The reminder as it looks [BEFORE_MIN] minutes before the next lesson, in
     * [TEST_DELAY_S] seconds, through the same alarm and receiver as the real
     * one: proves both the alarm and the look. Nothing is marked as reminded.
     */
    fun test(context: Context) {
        val alarms = context.getSystemService(AlarmManager::class.java)
        val at = System.currentTimeMillis() + TEST_DELAY_S * 1000
        val intent = PendingIntent.getBroadcast(
            context, 41, Intent(context, ReminderReceiver::class.java).putExtra(EXTRA_TEST, true),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        if (android.os.Build.VERSION.SDK_INT < 31 || alarms.canScheduleExactAlarms()) alarms.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, intent)
        else alarms.set(AlarmManager.RTC_WAKEUP, at, intent)
    }

    internal fun notifyTest(context: Context) {
        val now = LocalDateTime.now()
        val lesson = Repo.get(context).lessons().filter { it.start > now }.minByOrNull { it.start } ?: return
        // Shown as if it started in five minutes, counting down to that moment.
        show(context, lesson, now.plusMinutes(BEFORE_MIN), now)
    }

    private const val TEST_DELAY_S = 10L
    internal const val EXTRA_TEST = "test"
    internal const val EXTRA_REPOST = "repost"

    /**
     * "B204" big, the subject on its coloured tile, a live countdown to [start]
     * in the header, and the teacher or the change in the text.
     */
    private fun show(context: Context, lesson: Lesson, start: LocalDateTime, now: LocalDateTime) {
        channel(context)
        // Kept until a few minutes into the lesson: late arrivals still find their room.
        val until = start.plusMinutes(10)
        val style = Subjects.style(lesson.subject)
        val alert = Agenda.alert(lesson)
        val subject = Agenda.pretty(lesson.subject)
        // The header line also holds the countdown: the room alone fits, the subject goes below.
        // No room (sport, an outing): the subject is the title, not a dash.
        val hasRoom = lesson.rooms.isNotEmpty()
        val title = if (lesson.canceled) "${alert ?: "Cours annulé"}" else if (hasRoom) Agenda.room(lesson) else subject
        val details = listOfNotNull(
            subject.takeIf { hasRoom || lesson.canceled },
            if (lesson.canceled) "prévu à ${Agenda.time(lesson.start)}, il n'aura pas lieu" else "à ${Agenda.time(lesson.start)}",
            alert?.takeIf { !lesson.canceled },
            lesson.teachers.firstOrNull()?.let { Agenda.prettyName(it) },
        ).joinToString(" · ") + LessonNotes.of(context, lesson).joinToString("") { "\n📌 $it" }
        val startMs = start.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val notification = Notification.Builder(context, CHANNEL)
            .setSmallIcon(style.icon)
            .setColor(Subjects.solid(style.family))
            .setLargeIcon(NotificationLook.subjectTile(context, lesson.subject))
            .setContentTitle(title)
            .setContentText(details)
            .setStyle(Notification.BigTextStyle().bigText(details))
            // The header counts down to the start: "4:59", "4:58"…
            .setWhen(startMs)
            .setShowWhen(true)
            .setUsesChronometer(!lesson.canceled)
            .setChronometerCountDown(true)
            .setCategory(Notification.CATEGORY_REMINDER)
            // Stays until the lesson has started: a swipe by mistake must not lose the room
            // (Oscar, 2026-10-16). Android 14 lets some be swiped anyway: then it comes back.
            .setOngoing(true)
            .setAutoCancel(false)
            .setOnlyAlertOnce(true)
            .setTimeoutAfter(Duration.between(now, until).toMillis().coerceAtLeast(60_000L))
            .setDeleteIntent(PendingIntent.getBroadcast(
                context, 44,
                Intent(context, ReminderReceiver::class.java).putExtra(EXTRA_REPOST, true),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            ))
            .setContentIntent(Widgets.openApp(context, MainActivity.Tab.TIMETABLE))
            .build()
        context.getSharedPreferences("ui", Context.MODE_PRIVATE).edit()
            .putString("shownLesson", lesson.start.toString() + "|" + lesson.subject)
            .putString("shownStart", start.toString())
            .putLong("shownUntil", until.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli())
            .apply()
        runCatching { context.getSystemService(NotificationManager::class.java).notify(1, notification) }
    }

    /** The reminder was swiped away before its lesson: shown again, quietly. */
    internal fun repost(context: Context) {
        val prefs = context.getSharedPreferences("ui", Context.MODE_PRIVATE)
        if (System.currentTimeMillis() >= prefs.getLong("shownUntil", 0)) return
        val (start, subject) = prefs.getString("shownLesson", null)?.split("|", limit = 2)?.takeIf { it.size == 2 } ?: return
        val lesson = Repo.get(context).lessons().firstOrNull { it.start.toString() == start && it.subject == subject } ?: return
        val shownStart = prefs.getString("shownStart", null)?.let { runCatching { LocalDateTime.parse(it) }.getOrNull() } ?: lesson.start
        show(context, lesson, shownStart, LocalDateTime.now())
    }
}

class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) = when {
        intent.getBooleanExtra(Reminders.EXTRA_REPOST, false) -> Reminders.repost(context)
        intent.getBooleanExtra(Reminders.EXTRA_TEST, false) -> Reminders.notifyTest(context)
        else -> Reminders.notifyNext(context)
    }
}
