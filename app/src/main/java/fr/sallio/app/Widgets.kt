package fr.sallio.app

import android.app.AlarmManager
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.view.View
import android.widget.RemoteViews
import fr.sallio.pronote.Lesson
import java.time.LocalDateTime
import java.time.ZoneId

/** "Prochaine salle": the room to walk to, big, plus the alert if any. */
class NextWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) = Widgets.refreshAll(context)
    override fun onEnabled(context: Context) = Widgets.refreshAll(context)
}

/** "Ma journée": every lesson of the day with its room. */
class DayWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) = Widgets.refreshAll(context)
    override fun onEnabled(context: Context) = Widgets.refreshAll(context)
}

/** "Demain": every lesson of the next school day, to pack the bag the evening before. */
class TomorrowWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) = Widgets.refreshAll(context)
    override fun onEnabled(context: Context) = Widgets.refreshAll(context)
}

/** "À savoir": today's and tomorrow's lessons with an absent teacher, a cancellation or a new room. */
class AlertsWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) = Widgets.refreshAll(context)
    override fun onEnabled(context: Context) = Widgets.refreshAll(context)
}

/** "Devoirs": the homework of the next school day, ticked right from the home screen. */
class HomeworkWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) = Widgets.refreshAll(context)
    override fun onEnabled(context: Context) = Widgets.refreshAll(context)
}

/** Clock tick (redraw at the next lesson boundary), the refresh button and homework ticks. */
class WidgetEvents : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Widgets.ACTION_REFRESH) SyncJob.runNow(context)
        if (intent.action == Widgets.ACTION_TICK_HOMEWORK) {
            val key = intent.getStringExtra(Widgets.EXTRA_KEY) ?: return
            val repo = Repo.get(context)
            val h = repo.homework().firstOrNull { it.key == key } ?: return Widgets.refreshAll(context)
            // Saved at once and drawn ticked; sent to PRONOTE in the background, like a tick in the app.
            repo.setDone(h, !repo.isDone(h))
            Widgets.refreshAll(context)
            val done = goAsync()
            Thread {
                try {
                    repo.pushPending()
                    if (repo.pendingTotal() > 0) SyncJob.runLater(context, repo.waitBeforeLogin(manual = true))
                    Widgets.refreshAll(context)
                } finally {
                    done.finish()
                }
            }.start()
            return
        }
        Widgets.refreshAll(context)
    }
}

object Widgets {
    const val ACTION_TICK = "fr.sallio.app.TICK"
    const val ACTION_REFRESH = "fr.sallio.app.REFRESH"
    const val ACTION_TICK_HOMEWORK = "fr.sallio.app.TICK_HOMEWORK"
    const val EXTRA_KEY = "key"
    private const val MAX_HOMEWORK = 6
    private const val MAX_ROWS = 9

    fun refreshAll(context: Context) {
        RoomTile.refresh(context)
        runCatching { Wallpaper.update(context) }
        runCatching { Reminders.schedule(context) }
        val manager = AppWidgetManager.getInstance(context)
        fun ids(cls: Class<*>) = manager.getAppWidgetIds(ComponentName(context, cls))
        val nextIds = ids(NextWidget::class.java)
        val dayIds = ids(DayWidget::class.java)
        val tomorrowIds = ids(TomorrowWidget::class.java)
        val alertIds = ids(AlertsWidget::class.java)
        val homeworkIds = ids(HomeworkWidget::class.java)
        if (listOf(nextIds, dayIds, tomorrowIds, alertIds, homeworkIds).all { it.isEmpty() }) return

        val repo = Repo.get(context)
        val loggedIn = repo.account() != null
        val lessons = if (loggedIn) repo.lessons() else emptyList()
        val status = repo.status()
        val now = LocalDateTime.now()
        val view = Agenda.view(lessons, now)

        if (nextIds.isNotEmpty()) manager.updateAppWidget(nextIds, renderNext(context, loggedIn, view, status, now))
        if (dayIds.isNotEmpty()) manager.updateAppWidget(dayIds, renderDay(context, loggedIn, view, status, now))
        if (tomorrowIds.isNotEmpty()) manager.updateAppWidget(tomorrowIds, renderTomorrow(context, loggedIn, lessons, status, now))
        if (alertIds.isNotEmpty()) manager.updateAppWidget(alertIds, renderAlerts(context, loggedIn, lessons, status, now))
        if (homeworkIds.isNotEmpty()) manager.updateAppWidget(homeworkIds, renderHomework(context, loggedIn, repo, lessons, status, now))
        scheduleTick(context, Agenda.nextChange(lessons, now))
    }

    private fun footer(status: Repo.Status, now: LocalDateTime): String = when {
        status.needsLogin -> "⚠ Reconnecte-toi dans l'app"
        status.error != null && status.lastSuccess > 0 ->
            "⚠ ${status.error} · données ${Agenda.ago(status.lastSuccess, now)}"
        status.error != null -> "⚠ ${status.error}"
        else -> "Mis à jour ${Agenda.ago(status.lastSuccess, now)}"
    }

    /** Opens the app straight on [tab] (and [inbox]'s view of Infos). Distinct request codes keep the extras apart. */
    fun openApp(context: Context, tab: MainActivity.Tab = MainActivity.Tab.HOME, inbox: InboxTab.Mode? = null): PendingIntent =
        PendingIntent.getActivity(
            context, 10 + tab.ordinal + (inbox?.let { 20 + it.ordinal } ?: 0),
            Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                .putExtra(MainActivity.EXTRA_TAB, tab.name)
                .apply { inbox?.let { putExtra(MainActivity.EXTRA_INBOX, it.name) } },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

    private fun broadcast(context: Context, action: String, code: Int): PendingIntent =
        PendingIntent.getBroadcast(
            context, code,
            Intent(context, WidgetEvents::class.java).setAction(action),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

    /** Paints the "Prochaine salle" sticker: face, shadow, watermark and every text in the right ink. */
    private fun paintNext(context: Context, rv: RemoteViews, fill: Int, edge: Int, ink: Int, icon: Int) {
        rv.setInt(R.id.face, "setColorFilter", fill)
        rv.setInt(R.id.shadow, "setColorFilter", edge)
        rv.setImageViewResource(R.id.watermark, icon)
        rv.setInt(R.id.watermark, "setColorFilter", ink)
        for (id in intArrayOf(R.id.label, R.id.room, R.id.subject, R.id.then, R.id.footer)) rv.setTextColor(id, ink)
        val pill = if (ink == Color.WHITE) R.drawable.widget_tag_light else R.drawable.widget_tag_dark
        rv.setInt(R.id.label, "setBackgroundResource", pill)
        rv.setInt(R.id.then, "setBackgroundResource", pill)
    }

    private fun renderNext(
        context: Context,
        loggedIn: Boolean,
        view: Agenda.View,
        status: Repo.Status,
        now: LocalDateTime,
    ): RemoteViews {
        val rv = RemoteViews(context.packageName, R.layout.widget_next)
        rv.setOnClickPendingIntent(R.id.root, openApp(context, MainActivity.Tab.TIMETABLE))
        rv.setViewVisibility(R.id.badge, View.GONE)
        rv.setViewVisibility(R.id.then, View.GONE)
        val accent = context.getColor(R.color.accent)
        val accentEdge = context.getColor(R.color.accent_edge)

        val focus = view.focus
        if (!loggedIn || focus == null) {
            paintNext(context, rv, accent, accentEdge, context.getColor(R.color.on_accent), R.drawable.ic_school)
            rv.setOnClickPendingIntent(R.id.root, openApp(context))
            rv.setTextViewText(R.id.label, if (!loggedIn) "Sallio" else "Rien de prévu")
            rv.setTextViewText(R.id.room, if (!loggedIn) "Connexion" else "Repos")
            rv.setTextViewText(
                R.id.subject,
                when {
                    !loggedIn -> "Touche ici pour relier ton compte Pronote"
                    status.lastSuccess == 0L -> "Ton emploi du temps arrive"
                    else -> "Pas de cours dans les 2 semaines"
                },
            )
            rv.setTextViewText(R.id.footer, if (loggedIn) footer(status, now) else "")
            return rv
        }

        val style = Subjects.style(focus.subject)
        val ink = Subjects.onSolid(style.family)
        paintNext(context, rv, Subjects.solid(style.family), Subjects.edge(context, style.family), ink, style.icon)
        rv.setTextViewText(R.id.label, Agenda.when_(focus, now))
        val hasRoom = focus.rooms.isNotEmpty()
        val room = android.text.SpannableString(if (hasRoom) Agenda.room(focus) else Agenda.pretty(focus.subject))
        if (focus.canceled) room.setSpan(android.text.style.StrikethroughSpan(), 0, room.length, 0)
        rv.setTextViewText(R.id.room, room)
        rv.setTextViewTextSize(R.id.room, android.util.TypedValue.COMPLEX_UNIT_SP, if (hasRoom) 40f else 28f)
        rv.setTextViewText(R.id.subject, if (hasRoom) describe(focus) else focus.teachers.firstOrNull()?.let { Agenda.prettyName(it) } ?: "")
        Agenda.alert(focus)?.let {
            rv.setTextViewText(R.id.badge, it)
            rv.setTextColor(R.id.badge, context.getColor(if (focus.canceled) R.color.alert else R.color.warn))
            rv.setViewVisibility(R.id.badge, View.VISIBLE)
        }
        view.then?.let { next ->
            val alert = Agenda.alert(next)?.let { " · $it" } ?: ""
            rv.setTextViewText(R.id.then, "Ensuite ${Agenda.time(next.start)} · ${Agenda.room(next)} · ${Agenda.pretty(next.subject)}$alert")
            rv.setViewVisibility(R.id.then, View.VISIBLE)
        }
        rv.setTextViewText(R.id.footer, footer(status, now))
        return rv
    }

    private fun describe(lesson: Lesson): String {
        val teacher = lesson.teachers.firstOrNull()?.let { " · ${Agenda.prettyName(it)}" } ?: ""
        return Agenda.pretty(lesson.subject) + teacher
    }

    private fun renderDay(
        context: Context,
        loggedIn: Boolean,
        view: Agenda.View,
        status: Repo.Status,
        now: LocalDateTime,
    ): RemoteViews {
        val date = view.dayDate
        if (!loggedIn || date == null) return listCard(context, "Ma journée", MainActivity.Tab.TIMETABLE, if (loggedIn) footer(status, now) else "",
            if (!loggedIn) "Touche ici pour relier ton compte Pronote" else "Aucun cours à venir")
        val rv = listCard(context, Agenda.dayTitle(date, now.toLocalDate()), MainActivity.Tab.TIMETABLE, footer(status, now), null)
        fill(context, rv, view.day.map { Row(lessonRow(context, it, now, highlight = it === view.focus)) }, openTemplate(context, MainActivity.Tab.TIMETABLE, 3))
        return rv
    }

    /** One lesson as on "Ma journée": time, subject tile, room, subject, alert pill. [prefix] goes before the subject. */
    private fun lessonRow(context: Context, lesson: Lesson, now: LocalDateTime, highlight: Boolean = false, prefix: String? = null): RemoteViews {
        val row = RemoteViews(context.packageName, R.layout.widget_day_row)
        val style = Subjects.style(lesson.subject)
        val past = lesson.end <= now
        row.setTextViewText(R.id.time, Agenda.time(lesson.start))
        if (highlight) row.setTextColor(R.id.time, context.getColor(R.color.accent_ink))
        row.setInt(R.id.tile, "setColorFilter", Subjects.solid(style.family))
        row.setImageViewResource(R.id.icon, style.icon)
        row.setInt(R.id.icon, "setColorFilter", Subjects.deep(style.family))
        // Struck-through room when nobody will be there (spans survive RemoteViews, paint flags do not).
        val hasRoom = lesson.rooms.isNotEmpty()
        val room = android.text.SpannableString(if (hasRoom) Agenda.room(lesson) else Agenda.pretty(lesson.subject))
        if (lesson.canceled) room.setSpan(android.text.style.StrikethroughSpan(), 0, room.length, 0)
        row.setTextViewText(R.id.room, room)
        // No room (sport, outing): the subject is already the title, show the teacher instead.
        val subject = if (hasRoom) Agenda.pretty(lesson.subject) else lesson.teachers.firstOrNull()?.let { Agenda.prettyName(it) } ?: ""
        row.setTextViewText(R.id.subject, listOfNotNull(prefix, subject.ifEmpty { null }).joinToString(" · "))
        if (past) {
            for (id in intArrayOf(R.id.time, R.id.room, R.id.subject)) row.setTextColor(id, context.getColor(R.color.text3))
        }
        Agenda.alert(lesson)?.let {
            row.setTextViewText(R.id.status, it)
            row.setViewVisibility(R.id.status, View.VISIBLE)
            row.setInt(R.id.status, "setBackgroundResource", if (lesson.canceled) R.drawable.pill_alert else R.drawable.pill_warn)
            row.setTextColor(R.id.status, context.getColor(if (lesson.canceled) R.color.alert else R.color.warn))
        }
        return row
    }

    /** Android 12+ widgets hold a real list, which scrolls; older ones get the fixed list. */
    private val scrolls get() = android.os.Build.VERSION.SDK_INT >= 31

    /**
     * A line of a list widget. [click] is the view that reacts and [fillIn]
     * what it adds to the list's template (Android 12+); [direct] is the
     * same click as its own PendingIntent, for the fixed list.
     */
    private class Row(val views: RemoteViews, val click: Int = R.id.row, val fillIn: Intent = Intent(), val direct: PendingIntent? = null)

    /** The card shared by the list widgets: title, footer, refresh, and the empty text when there is nothing. */
    private fun listCard(context: Context, title: String, tab: MainActivity.Tab, footer: String, empty: String?): RemoteViews {
        val rv = RemoteViews(context.packageName, if (scrolls) R.layout.widget_list else R.layout.widget_day)
        rv.setOnClickPendingIntent(R.id.root, openApp(context, tab))
        rv.setOnClickPendingIntent(R.id.refresh, broadcast(context, ACTION_REFRESH, 1))
        if (!scrolls) rv.removeAllViews(R.id.list)
        rv.setTextViewText(R.id.title, title)
        rv.setTextViewText(R.id.footer, footer)
        rv.setViewVisibility(R.id.empty, if (empty != null) View.VISIBLE else View.GONE)
        if (scrolls) rv.setViewVisibility(R.id.items, if (empty != null) View.GONE else View.VISIBLE)
        empty?.let { rv.setTextViewText(R.id.empty, it) }
        return rv
    }

    /**
     * Puts [rows] in the card: the whole list, scrolling, on Android 12+
     * (clicks go through [template]); the first [MAX_ROWS] otherwise.
     */
    private fun fill(context: Context, rv: RemoteViews, rows: List<Row>, template: PendingIntent) {
        if (android.os.Build.VERSION.SDK_INT >= 31) {
            val items = RemoteViews.RemoteCollectionItems.Builder().setViewTypeCount(2)
            rows.forEachIndexed { i, row ->
                row.views.setOnClickFillInIntent(row.click, row.fillIn)
                items.addItem(i.toLong(), row.views)
            }
            rv.setRemoteAdapter(R.id.items, items.build())
            rv.setPendingIntentTemplate(R.id.items, template)
        } else {
            for (row in rows.take(MAX_ROWS)) {
                row.direct?.let { row.views.setOnClickPendingIntent(row.click, it) }
                rv.addView(R.id.list, row.views)
            }
        }
    }

    /** Template for lines that open the app on [tab]; mutable, as a template must be. */
    private fun openTemplate(context: Context, tab: MainActivity.Tab, code: Int): PendingIntent =
        PendingIntent.getActivity(
            context, 60 + code,
            Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                .putExtra(MainActivity.EXTRA_TAB, tab.name),
            PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

    /** The next day with lessons after today (the widgets look ahead, not at today). */
    private fun nextSchoolDay(lessons: List<Lesson>, now: LocalDateTime): java.time.LocalDate? =
        lessons.map { it.start.toLocalDate() }.filter { it > now.toLocalDate() }.minOrNull()

    private fun renderTomorrow(context: Context, loggedIn: Boolean, lessons: List<Lesson>, status: Repo.Status, now: LocalDateTime): RemoteViews {
        val date = if (loggedIn) nextSchoolDay(lessons, now) else null
        if (date == null) return listCard(context, "Demain", MainActivity.Tab.TIMETABLE, if (loggedIn) footer(status, now) else "",
            if (!loggedIn) "Touche ici pour relier ton compte Pronote" else "Aucun cours à venir")
        val rv = listCard(context, Agenda.dayTitle(date, now.toLocalDate()), MainActivity.Tab.TIMETABLE, footer(status, now), null)
        val day = lessons.filter { it.start.toLocalDate() == date }.sortedBy { it.start }
        fill(context, rv, day.map { Row(lessonRow(context, it, now)) }, openTemplate(context, MainActivity.Tab.TIMETABLE, 4))
        return rv
    }

    private fun renderAlerts(context: Context, loggedIn: Boolean, lessons: List<Lesson>, status: Repo.Status, now: LocalDateTime): RemoteViews {
        val today = now.toLocalDate()
        // Same rule as "À savoir" on the home tab: what is still ahead today and tomorrow.
        val alerts = lessons.filter { it.end > now && it.start.toLocalDate() <= today.plusDays(1) && Agenda.alert(it) != null }.sortedBy { it.start }
        val empty = when {
            !loggedIn -> "Touche ici pour relier ton compte Pronote"
            alerts.isEmpty() -> "Rien à signaler aujourd'hui ni demain"
            else -> null
        }
        val rv = listCard(context, "À savoir", MainActivity.Tab.HOME, if (loggedIn) footer(status, now) else "", empty)
        fill(context, rv, alerts.map { Row(lessonRow(context, it, now, prefix = Agenda.dayTitle(it.start.toLocalDate(), today))) },
            openTemplate(context, MainActivity.Tab.HOME, 5))
        return rv
    }

    private fun renderHomework(context: Context, loggedIn: Boolean, repo: Repo, lessons: List<Lesson>, status: Repo.Status, now: LocalDateTime): RemoteViews {
        val today = now.toLocalDate()
        // Same day as "Pour demain" on the home tab.
        val date = nextSchoolDay(lessons, now) ?: today.plusDays(1)
        val due = if (loggedIn) repo.homework().filter { it.due == date } else emptyList()
        val left = due.count { !repo.isDone(it) }
        val title = "Pour " + Agenda.dayTitle(date, today).replaceFirstChar { it.lowercase() }
        val footer = if (!loggedIn) "" else listOfNotNull(
            if (due.isEmpty()) null else if (left == 0) "Tout est fait" else "$left à faire",
            footer(status, now),
        ).joinToString(" · ")
        val empty = when {
            !loggedIn -> "Touche ici pour relier ton compte Pronote"
            due.isEmpty() -> "Rien à rendre. Profites-en !"
            else -> null
        }
        val rv = listCard(context, title, MainActivity.Tab.HOMEWORK, footer, empty)
        // Not done first, then done.
        val rows = due.sortedBy { repo.isDone(it) }.take(if (scrolls) Int.MAX_VALUE else MAX_HOMEWORK).mapIndexed { i, h ->
            val done = repo.isDone(h)
            val row = RemoteViews(context.packageName, R.layout.widget_hw_row)
            val style = Subjects.style(h.subject)
            row.setImageViewResource(R.id.box, if (done) R.drawable.widget_check_on else R.drawable.widget_check_off)
            row.setInt(R.id.tile, "setColorFilter", Subjects.solid(style.family))
            row.setImageViewResource(R.id.icon, style.icon)
            row.setInt(R.id.icon, "setColorFilter", Subjects.deep(style.family))
            row.setTextViewText(R.id.subject, Agenda.pretty(h.subject))
            val text = android.text.SpannableString(h.description.ifEmpty { "(pas de consigne)" }.replace(Regex("\\s+"), " "))
            if (done) text.setSpan(android.text.style.StrikethroughSpan(), 0, text.length, 0)
            row.setTextViewText(R.id.text, text)
            row.setTextColor(R.id.text, context.getColor(if (done) R.color.text3 else R.color.text))
            // The box ticks; the data URI keeps the rows' intents apart.
            val tick = Intent().setData(android.net.Uri.parse("sallio://homework/$i")).putExtra(EXTRA_KEY, h.key)
            Row(row, R.id.box, tick, PendingIntent.getBroadcast(
                context, 100 + i,
                Intent(context, WidgetEvents::class.java).setAction(ACTION_TICK_HOMEWORK)
                    .setData(android.net.Uri.parse("sallio://homework/$i"))
                    .putExtra(EXTRA_KEY, h.key),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            ))
        }
        val template = PendingIntent.getBroadcast(
            context, 99,
            Intent(context, WidgetEvents::class.java).setAction(ACTION_TICK_HOMEWORK),
            PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        fill(context, rv, rows, template)
        return rv
    }

    /**
     * Redraws when the content changes (a lesson starts or ends). Exact when the
     * phone allows it; otherwise within a few minutes, which the widgets
     * tolerate because they always print the start time next to the room.
     */
    private fun scheduleTick(context: Context, at: LocalDateTime) {
        val alarms = context.getSystemService(AlarmManager::class.java)
        val pending = broadcast(context, ACTION_TICK, 2)
        val millis = at.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli() + 1_000
        val exact = android.os.Build.VERSION.SDK_INT < 31 || alarms.canScheduleExactAlarms()
        if (exact) {
            alarms.setExactAndAllowWhileIdle(AlarmManager.RTC, millis, pending)
        } else {
            alarms.setWindow(AlarmManager.RTC, millis, 5 * 60_000L, pending)
        }
    }

    fun cancelTick(context: Context) {
        context.getSystemService(AlarmManager::class.java).cancel(broadcast(context, ACTION_TICK, 2))
    }
}
