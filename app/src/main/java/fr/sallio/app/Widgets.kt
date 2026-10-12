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

/** Clock tick (redraw at the next lesson boundary) and the refresh button. */
class WidgetEvents : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Widgets.ACTION_REFRESH) SyncJob.runNow(context)
        Widgets.refreshAll(context)
    }
}

object Widgets {
    const val ACTION_TICK = "fr.sallio.app.TICK"
    const val ACTION_REFRESH = "fr.sallio.app.REFRESH"
    private const val MAX_ROWS = 9

    fun refreshAll(context: Context) {
        RoomTile.refresh(context)
        runCatching { Wallpaper.update(context) }
        runCatching { Reminders.schedule(context) }
        val manager = AppWidgetManager.getInstance(context)
        val nextIds = manager.getAppWidgetIds(ComponentName(context, NextWidget::class.java))
        val dayIds = manager.getAppWidgetIds(ComponentName(context, DayWidget::class.java))
        if (nextIds.isEmpty() && dayIds.isEmpty()) return

        val repo = Repo.get(context)
        val loggedIn = repo.account() != null
        val lessons = if (loggedIn) repo.lessons() else emptyList()
        val status = repo.status()
        val now = LocalDateTime.now()
        val view = Agenda.view(lessons, now)

        if (nextIds.isNotEmpty()) manager.updateAppWidget(nextIds, renderNext(context, loggedIn, view, status, now))
        if (dayIds.isNotEmpty()) manager.updateAppWidget(dayIds, renderDay(context, loggedIn, view, status, now))
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
            paintNext(context, rv, accent, accentEdge, Color.WHITE, R.drawable.ic_school)
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
        val rv = RemoteViews(context.packageName, R.layout.widget_day)
        rv.setOnClickPendingIntent(R.id.root, openApp(context, MainActivity.Tab.TIMETABLE))
        rv.setOnClickPendingIntent(R.id.refresh, broadcast(context, ACTION_REFRESH, 1))
        rv.removeAllViews(R.id.list)

        val date = view.dayDate
        if (!loggedIn || date == null) {
            rv.setTextViewText(R.id.title, "Ma journée")
            rv.setTextViewText(R.id.footer, if (loggedIn) footer(status, now) else "")
            rv.setTextViewText(R.id.empty, if (!loggedIn) "Touche ici pour relier ton compte Pronote" else "Aucun cours à venir")
            rv.setViewVisibility(R.id.empty, View.VISIBLE)
            return rv
        }
        rv.setViewVisibility(R.id.empty, View.GONE)
        rv.setTextViewText(R.id.title, Agenda.dayTitle(date, now.toLocalDate()))
        rv.setTextViewText(R.id.footer, footer(status, now))

        val accent = context.getColor(R.color.accent)
        for (lesson in view.day.take(MAX_ROWS)) {
            val row = RemoteViews(context.packageName, R.layout.widget_day_row)
            val style = Subjects.style(lesson.subject)
            val past = lesson.end <= now
            row.setTextViewText(R.id.time, Agenda.time(lesson.start))
            if (lesson === view.focus) row.setTextColor(R.id.time, accent)
            row.setInt(R.id.tile, "setColorFilter", Subjects.solid(style.family))
            row.setImageViewResource(R.id.icon, style.icon)
            row.setInt(R.id.icon, "setColorFilter", Subjects.onSolid(style.family))
            // Struck-through room when nobody will be there (spans survive RemoteViews, paint flags do not).
            val hasRoom = lesson.rooms.isNotEmpty()
            val room = android.text.SpannableString(if (hasRoom) Agenda.room(lesson) else Agenda.pretty(lesson.subject))
            if (lesson.canceled) room.setSpan(android.text.style.StrikethroughSpan(), 0, room.length, 0)
            row.setTextViewText(R.id.room, room)
            // No room (sport, outing): the subject is already the title, show the teacher instead.
            row.setTextViewText(R.id.subject, if (hasRoom) Agenda.pretty(lesson.subject) else lesson.teachers.firstOrNull()?.let { Agenda.prettyName(it) } ?: "")
            if (past) {
                for (id in intArrayOf(R.id.time, R.id.room, R.id.subject)) row.setTextColor(id, context.getColor(R.color.text3))
            }
            Agenda.alert(lesson)?.let {
                row.setTextViewText(R.id.status, it)
                row.setViewVisibility(R.id.status, View.VISIBLE)
                row.setInt(R.id.status, "setBackgroundResource", if (lesson.canceled) R.drawable.pill_alert else R.drawable.pill_warn)
                row.setTextColor(R.id.status, context.getColor(if (lesson.canceled) R.color.alert else R.color.warn))
            }
            rv.addView(R.id.list, row)
        }
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
