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

    private fun openApp(context: Context): PendingIntent =
        PendingIntent.getActivity(
            context, 0,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

    private fun broadcast(context: Context, action: String, code: Int): PendingIntent =
        PendingIntent.getBroadcast(
            context, code,
            Intent(context, WidgetEvents::class.java).setAction(action),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

    private fun renderNext(
        context: Context,
        loggedIn: Boolean,
        view: Agenda.View,
        status: Repo.Status,
        now: LocalDateTime,
    ): RemoteViews {
        val rv = RemoteViews(context.packageName, R.layout.widget_next)
        rv.setOnClickPendingIntent(R.id.root, openApp(context))
        rv.setViewVisibility(R.id.badge, View.GONE)
        rv.setViewVisibility(R.id.then, View.GONE)

        val focus = view.focus
        when {
            !loggedIn -> {
                rv.setTextViewText(R.id.label, "SALLIO")
                rv.setTextViewText(R.id.room, "Connexion")
                rv.setTextViewText(R.id.subject, "Touche ici pour relier ton compte Pronote")
                rv.setTextViewText(R.id.footer, "")
                return rv
            }
            focus == null -> {
                rv.setTextViewText(R.id.label, "RIEN DE PRÉVU")
                rv.setTextViewText(R.id.room, "—")
                rv.setTextViewText(R.id.subject, if (status.lastSuccess == 0L) "Emploi du temps pas encore chargé" else "Pas de cours dans les 2 semaines")
                rv.setTextViewText(R.id.footer, footer(status, now))
                return rv
            }
        }
        focus!!
        rv.setTextViewText(R.id.label, Agenda.when_(focus, now).uppercase())
        rv.setTextViewText(R.id.room, Agenda.room(focus))
        rv.setTextViewText(R.id.subject, describe(focus))
        Agenda.alert(focus)?.let {
            rv.setTextViewText(R.id.badge, it)
            rv.setViewVisibility(R.id.badge, View.VISIBLE)
            rv.setInt(R.id.badge, "setBackgroundResource", if (focus.canceled) R.drawable.pill_alert else R.drawable.pill_warn)
            rv.setTextColor(R.id.badge, context.getColor(if (focus.canceled) R.color.alert else R.color.warn))
        }
        view.then?.let { next ->
            val alert = Agenda.alert(next)?.let { " · $it" } ?: ""
            rv.setTextViewText(
                R.id.then,
                "Puis ${Agenda.time(next.start)} · ${Agenda.room(next)} · ${Agenda.pretty(next.subject)}$alert",
            )
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
        rv.setOnClickPendingIntent(R.id.root, openApp(context))
        rv.setOnClickPendingIntent(R.id.refresh, broadcast(context, ACTION_REFRESH, 1))
        rv.removeAllViews(R.id.list)

        val date = view.dayDate
        if (!loggedIn || date == null) {
            rv.setTextViewText(R.id.title, "Ma journée")
            rv.setTextViewText(R.id.footer, if (loggedIn) footer(status, now) else "")
            rv.setTextViewText(
                R.id.empty,
                if (!loggedIn) "Touche ici pour relier ton compte Pronote" else "Aucun cours à venir",
            )
            rv.setViewVisibility(R.id.empty, View.VISIBLE)
            return rv
        }
        rv.setViewVisibility(R.id.empty, View.GONE)
        rv.setTextViewText(R.id.title, Agenda.dayTitle(date, now.toLocalDate()))
        rv.setTextViewText(R.id.footer, footer(status, now))

        val text = context.getColor(R.color.text)
        val text3 = context.getColor(R.color.text3)
        val accent = context.getColor(R.color.accent)
        for (lesson in view.day.take(MAX_ROWS)) {
            val row = RemoteViews(context.packageName, R.layout.widget_day_row)
            val past = lesson.end <= now
            val isFocus = lesson === view.focus
            row.setTextViewText(R.id.time, Agenda.time(lesson.start))
            // Struck-through room when nobody will be there (spans survive RemoteViews, paint flags do not).
            val room = android.text.SpannableString(Agenda.room(lesson))
            if (lesson.canceled) room.setSpan(android.text.style.StrikethroughSpan(), 0, room.length, 0)
            row.setTextViewText(R.id.room, room)
            row.setTextViewText(R.id.subject, Agenda.pretty(lesson.subject))
            row.setTextColor(R.id.room, if (past || lesson.canceled) text3 else text)
            row.setInt(R.id.stripe, "setBackgroundColor", stripeColor(lesson, accent))
            row.setViewVisibility(R.id.stripe, if (isFocus || !past) View.VISIBLE else View.INVISIBLE)
            if (isFocus) row.setTextColor(R.id.time, accent)
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

    private fun stripeColor(lesson: Lesson, fallback: Int): Int =
        lesson.color?.let { runCatching { Color.parseColor(it) }.getOrNull() } ?: fallback

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
