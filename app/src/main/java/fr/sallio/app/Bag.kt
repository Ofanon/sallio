package fr.sallio.app

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.view.Gravity
import android.view.View
import fr.sallio.pronote.Lesson
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.TextStyle
import java.util.Locale

/**
 * "Cartable du lendemain": the evening before a school day, the subjects of
 * that day and the homework not done yet, and a sheet to tick each subject
 * once it is in the bag. Everything comes from the stored timetable and
 * homework; the ticks stay on the phone (nothing is sent to PRONOTE).
 */
object Bag {

    private const val PREF = "bag"
    private const val CHANNEL = "bag"
    private const val HOUR = 19
    private const val NOTIFICATION_ID = 2

    /** On unless the student turned it off; Android's permission still decides. */
    fun isEnabled(context: Context) = context.getSharedPreferences("ui", Context.MODE_PRIVATE).getBoolean(PREF, true)

    fun setEnabled(context: Context, on: Boolean) {
        context.getSharedPreferences("ui", Context.MODE_PRIVATE).edit().putBoolean(PREF, on).apply()
        if (on) schedule(context) else {
            context.getSystemService(AlarmManager::class.java).cancel(pending(context, test = false))
            context.getSystemService(NotificationManager::class.java).cancel(NOTIFICATION_ID)
        }
    }

    // ---- What goes in the bag --------------------------------------------------------

    /** The next day with lessons after [today]. */
    fun nextDay(lessons: List<Lesson>, today: LocalDate): LocalDate? =
        lessons.filter { !it.canceled && it.start.toLocalDate() > today }.minOfOrNull { it.start.toLocalDate() }

    /** That day's subjects, once each, in the order of the day; a cancelled lesson needs nothing. */
    fun subjects(lessons: List<Lesson>, day: LocalDate): List<String> =
        lessons.filter { it.start.toLocalDate() == day && !it.canceled }.sortedBy { it.start }.map { it.subject }.distinct()

    private fun store(context: Context) = context.getSharedPreferences("bag", Context.MODE_PRIVATE)

    fun packed(context: Context, day: LocalDate): Set<String> = store(context).getStringSet(day.toString(), emptySet())!!

    fun setPacked(context: Context, day: LocalDate, subject: String, inBag: Boolean) {
        val prefs = store(context)
        val now = packed(context, day).let { if (inBag) it + subject else it - subject }
        // Only the days still to come are kept.
        val edit = prefs.edit()
        prefs.all.keys.filter { runCatching { LocalDate.parse(it) < LocalDate.now() }.getOrDefault(true) }.forEach { edit.remove(it) }
        edit.putStringSet(day.toString(), now).apply()
    }

    // ---- The evening notification ------------------------------------------------------

    private fun pending(context: Context, test: Boolean) = PendingIntent.getBroadcast(
        context, if (test) 43 else 42, Intent(context, BagReceiver::class.java).putExtra(EXTRA_TEST, test),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    /** Next 19 h; a quarter of an hour of leeway lets Android group it with other wake-ups. */
    fun schedule(context: Context) {
        if (!isEnabled(context)) return
        val now = LocalDateTime.now()
        val today = now.toLocalDate().atTime(HOUR, 0)
        val at = (if (now < today) today else today.plusDays(1)).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        context.getSystemService(AlarmManager::class.java).setWindow(AlarmManager.RTC_WAKEUP, at, 15 * 60_000L, pending(context, test = false))
    }

    /** The notification in a few seconds, for the next school day whatever the hour. */
    fun test(context: Context) {
        context.getSystemService(AlarmManager::class.java)
            .set(AlarmManager.RTC_WAKEUP, System.currentTimeMillis() + 5_000, pending(context, test = true))
    }

    internal fun onAlarm(context: Context, test: Boolean) {
        if (!test) schedule(context)
        if (!test && !isEnabled(context)) return
        val repo = Repo.get(context)
        if (repo.account() == null) return
        val lessons = repo.lessons()
        val today = LocalDate.now()
        // The evening before a school day only: Friday says nothing, Sunday speaks for Monday.
        val day = if (test) nextDay(lessons, today) else today.plusDays(1).takeIf { subjects(lessons, it).isNotEmpty() }
        day ?: return
        notify(context, repo, lessons, day)
    }

    private fun notify(context: Context, repo: Repo, lessons: List<Lesson>, day: LocalDate) {
        val nm = context.getSystemService(NotificationManager::class.java)
        if (!nm.areNotificationsEnabled()) return
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Cartable du lendemain", NotificationManager.IMPORTANCE_DEFAULT).apply {
            description = "La veille d'un jour de cours, à 19 h : les matières de demain et les devoirs pas finis."
        })
        val subjects = subjects(lessons, day)
        val dayLessons = lessons.filter { it.start.toLocalDate() == day && !it.canceled }.sortedBy { it.start }
        val todo = repo.homework().filter { it.due == day && !repo.isDone(it) }
        val notes = LessonNotes.ofDay(context, lessons, day)
        // "Demain" alone: collapsed, the title shares its line with the time and the tile.
        val title = title(day, LocalDate.now())
        val list = subjects.joinToString(", ") { Agenda.pretty(it) }
        val summary = "${dayLessons.size} cours · $list"
        val hours = "${dayLessons.size} cours, de ${Agenda.time(dayLessons.first().start)} à ${Agenda.time(dayLessons.last().end)}"
        val reminders = notes.joinToString("") { (l, n) -> "\n📌 $n (${Agenda.pretty(l.subject)})" }
        val text = (if (todo.isEmpty()) "$hours\n$list"
        else "$hours\n$list\n${todo.size} devoir${if (todo.size > 1) "s" else ""} pas fini${if (todo.size > 1) "s" else ""} : ${todo.map { Agenda.pretty(it.subject) }.distinct().joinToString(", ")}") + reminders
        val notification = Notification.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_briefcase)
            .setColor(context.getColor(R.color.accent))
            .setLargeIcon(NotificationLook.appTile(context, R.drawable.ic_briefcase))
            .setContentTitle(title)
            .setContentText(summary)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setAutoCancel(true)
            // Gone the next morning: the bag is packed or it is too late.
            .setTimeoutAfter(java.time.Duration.between(LocalDateTime.now(), day.atTime(8, 0)).toMillis().coerceAtLeast(60_000L))
            .setContentIntent(Widgets.openSheet(context, MainActivity.OPEN_BAG, 70))
            .build()
        runCatching { nm.notify(NOTIFICATION_ID, notification) }
        Analytics.event("cartable_notification", "matieres" to subjects.size, "devoirs" to todo.size, "notes" to notes.size)
    }

    /** "Demain", or "Lundi" from a Saturday. */
    fun title(day: LocalDate, today: LocalDate) =
        if (day == today.plusDays(1)) "Demain" else day.dayOfWeek.getDisplayName(TextStyle.FULL, Locale.FRANCE).replaceFirstChar { it.uppercase() }

    internal const val EXTRA_TEST = "test"

    // ---- The sheet -------------------------------------------------------------------

    /** One check per subject of the next school day, then the homework still to do for it. */
    fun showSheet(activity: MainActivity, s: Snapshot) {
        val ctx = activity
        val today = s.now.toLocalDate()
        val day = nextDay(s.lessons, today) ?: return
        Analytics.event("fiche_ouverte", "fiche" to "cartable")
        val subjects = subjects(s.lessons, day)
        val dayLessons = s.lessons.filter { it.start.toLocalDate() == day && !it.canceled }.sortedBy { it.start }
        Sheet.open(activity, activity.bottomInsetPx) { sheet, _ ->
            Sheet.title(sheet, "Cartable · ${title(day, today).lowercase()}",
                "${Agenda.dayName(day)} ${day.dayOfMonth} ${day.month.getDisplayName(TextStyle.FULL, Locale.FRANCE)} · ${dayLessons.size} cours, ${Agenda.time(dayLessons.first().start)} – ${Agenda.time(dayLessons.last().end)}")
            val progress = UI.text(ctx, "", 14f, R.color.text2, 800).apply { setPadding(0, ctx.dp(10), 0, ctx.dp(8)) }
            fun count() {
                val n = packed(ctx, day).count { it in subjects }
                progress.text = if (n == subjects.size) "Sac prêt ✓" else "$n sur ${subjects.size} dans le sac"
                progress.setTextColor(ctx.getColor(if (n == subjects.size) R.color.success else R.color.text2))
            }
            count()
            sheet.addView(progress)
            val card = UI.card(ctx, padding = 12)
            subjects.forEachIndexed { i, subject ->
                if (i > 0) card.addView(UI.divider(ctx))
                card.addView(UI.row(ctx).apply {
                    gravity = Gravity.CENTER_VERTICAL
                    addView(UI.subjectTile(ctx, subject, 30))
                    addView(UI.hgap(ctx, 10))
                    addView(UI.check(ctx, Agenda.pretty(subject), { subject in packed(ctx, day) }) { inBag ->
                        setPacked(ctx, day, subject, inBag)
                        val all = packed(ctx, day).containsAll(subjects)
                        count()
                        if (all) { Motion.success(progress); Analytics.event("cartable_pret", "matieres" to subjects.size) }
                    }, UI.weight())
                }, UI.matchWidth())
            }
            sheet.addView(card, UI.matchWidth())
            // The student's own notes on that day's lessons: what is easy to forget.
            val notes = LessonNotes.ofDay(ctx, s.lessons, day)
            if (notes.isNotEmpty()) {
                sheet.addView(UI.section(ctx, "À ne pas oublier"))
                val box = UI.card(ctx, padding = 12)
                notes.forEachIndexed { i, (lesson, note) ->
                    if (i > 0) box.addView(UI.divider(ctx))
                    box.addView(UI.row(ctx).apply {
                        gravity = Gravity.CENTER_VERTICAL
                        setPadding(0, ctx.dp(8), 0, ctx.dp(8))
                        addView(UI.icon(ctx, R.drawable.ic_pin, R.color.accent_ink, 18))
                        addView(UI.hgap(ctx, 10))
                        addView(UI.text(ctx, note, 15f, R.color.text, 800), UI.weight())
                        addView(UI.subjectChip(ctx, lesson.subject))
                    }, UI.matchWidth())
                }
                sheet.addView(box, UI.matchWidth())
            }
            val due = s.homework.filter { it.due == day }
            if (due.isNotEmpty()) {
                sheet.addView(UI.section(ctx, "À rendre ${title(day, today).lowercase()}"))
                val hw = UI.card(ctx, padding = 14)
                // Grouped under their subject's chip, in the order of the day.
                due.groupBy { it.subject }.entries.sortedBy { (subject, _) -> subjects.indexOf(subject).let { if (it < 0) Int.MAX_VALUE else it } }
                    .forEachIndexed { i, (subject, list) ->
                        if (i > 0) hw.addView(UI.divider(ctx))
                        hw.addView(UI.subjectChip(ctx, subject), UI.wrap().apply { topMargin = ctx.dp(6) })
                        for (h in list) hw.addView(UI.homeworkCheck(ctx, h, { activity.isHomeworkDone(h) }) { done -> activity.setHomeworkDone(h, done) }, UI.matchWidth())
                    }
                sheet.addView(hw, UI.matchWidth())
            }
            sheet.addView(UI.text(ctx, "Les cases restent sur ton téléphone, rien n'est envoyé à Pronote.", 12f, R.color.text3).apply {
                gravity = Gravity.CENTER
                setPadding(0, ctx.dp(16), 0, 0)
            }, UI.matchWidth())
        }.setOnDismissListener { activity.refreshScreen() }
    }

    /** The entry in Cours, on the next school day: "Préparer mon cartable · 2 sur 6". */
    fun entry(activity: MainActivity, s: Snapshot, day: LocalDate): View? {
        if (day != nextDay(s.lessons, s.now.toLocalDate())) return null
        val ctx = activity
        val subjects = subjects(s.lessons, day)
        if (subjects.isEmpty()) return null
        val n = packed(ctx, day).count { it in subjects }
        val card = UI.card(ctx, R.color.accent_container, R.color.accent_container_edge, padding = 12)
        card.addView(UI.row(ctx).apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(UI.icon(ctx, R.drawable.ic_briefcase, R.color.on_accent_container, 22))
            addView(UI.hgap(ctx, 10))
            addView(UI.text(ctx, "Préparer mon cartable", 15f, R.color.on_accent_container, 800), UI.weight())
            addView(UI.text(ctx, if (n == subjects.size) "prêt ✓" else "$n sur ${subjects.size}", 13f, R.color.on_accent_container, 800))
        }, UI.matchWidth())
        card.setOnClickListener { Motion.tap(card); showSheet(activity, s) }
        return card
    }
}

class BagReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) = Bag.onAlarm(context, intent.getBooleanExtra(Bag.EXTRA_TEST, false))
}
