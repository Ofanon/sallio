package fr.sallio.app

import android.graphics.Color
import android.graphics.Paint
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import fr.sallio.pronote.Grade
import fr.sallio.pronote.Homework
import fr.sallio.pronote.Lesson
import fr.sallio.pronote.Report
import fr.sallio.pronote.SubjectAverage
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.TextStyle
import java.util.Locale

/** Everything the screens show, read once from the phone's storage. */
class Snapshot(
    val account: Repo.Account,
    val lessons: List<Lesson>,
    val homework: List<Homework>,
    val report: Report?,
    val status: Repo.Status,
    val unseenGrades: Set<String>,
    val now: LocalDateTime,
)

/** Builds the content of each tab, in the "pastel stickers" style. */
class Tabs(private val activity: MainActivity, private val repo: Repo) {

    private val ctx = activity

    /** Coral of the "new" badges and alert dots, taken from the subject palette. */
    private val NEW_BADGE = 0xFFD9705A.toInt()
    private val fr = Locale.FRANCE
    private fun color(res: Int) = ctx.getColor(res)

    // ---- Accueil -----------------------------------------------------------------

    /** The snapshot the current views were built from, for their click handlers. */
    private var lastSnapshot: Snapshot? = null

    private val MAX_ALERTS = 3

    /** The days whose homework the student unfolded on the home tab: they stay open across redraws. */
    private val unfoldedDays = mutableSetOf<java.time.LocalDate>()

    fun home(s: Snapshot): View {
        lastSnapshot = s
        val root = UI.column(ctx)
        val view = Agenda.view(s.lessons, s.now)
        val today = s.now.toLocalDate()

        // A long press on a card offers the real widget it looks like.
        root.addView(hero(view.focus, s).also { activity.offerWidget(it, NextWidget::class.java) }, UI.matchWidth())
        view.then?.let { root.addView(thenCard(it).also { c -> activity.offerWidget(c, DayWidget::class.java) }, UI.matchWidth().apply { topMargin = ctx.dp(12) }) }

        // What changed today and tomorrow: absent teachers, canceled lessons, room changes.
        val alerts = s.lessons.filter {
            it.end > s.now && it.start.toLocalDate() <= today.plusDays(1) && Agenda.alert(it) != null && it !== view.focus
        }
        // The home tab keeps four things only (Oscar, 2026-10-13): the next lesson, what changed,
        // one day of homework and the canteen in one line. Grades and the rest live in their tabs.
        if (alerts.isNotEmpty()) {
            root.addView(UI.section(ctx, "À savoir"))
            alerts.take(MAX_ALERTS).forEachIndexed { i, lesson ->
                root.addView(alertBanner(lesson, today).also { activity.offerWidget(it, AlertsWidget::class.java) }, UI.matchWidth().apply { if (i > 0) topMargin = ctx.dp(8) })
            }
            if (alerts.size > MAX_ALERTS) root.addView(UI.text(ctx, "+ ${alerts.size - MAX_ALERTS} autre${if (alerts.size - MAX_ALERTS > 1) "s" else ""} dans Cours", 13f, R.color.accent_ink, 800).apply {
                setPadding(ctx.dp(4), ctx.dp(8), 0, 0)
                setOnClickListener { Motion.tap(this); activity.openTab(MainActivity.Tab.TIMETABLE) }
            })
        }

        // One day of homework: today's while some is left to do, else the next school day's.
        val dueToday = s.homework.filter { it.due == today }
        val nextDay = s.lessons.map { it.start.toLocalDate() }.filter { it > today }.minOrNull() ?: today.plusDays(1)
        if (dueToday.any { !activity.isHomeworkDone(it) }) homeworkBlock(root, today, today, dueToday)
        else homeworkBlock(root, nextDay, today, s.homework.filter { it.due == nextDay })

        CanteenUI.homeCard(activity, s.now)?.let {
            activity.offerWidget(it, CanteenWidget::class.java)
            root.addView(it, UI.matchWidth().apply { topMargin = ctx.dp(14) })
        }

        root.addView(footer(s))
        return root
    }

    /** "demain", "mardi 13 oct." */
    private fun dayLabel(date: LocalDate, today: LocalDate) =
        Agenda.dayTitle(date, today).replaceFirstChar { it.lowercase(fr) }

    private fun hero(focus: Lesson?, s: Snapshot): View {
        if (focus == null) {
            val card = UI.card(ctx, R.color.accent, R.color.accent_edge, padding = 20)
            card.addView(UI.title(ctx, if (s.status.lastSuccess == 0L) "Chargement…" else "Pas de cours", 34f, R.color.on_accent))
            card.addView(UI.text(ctx, if (s.status.lastSuccess == 0L) "Ton emploi du temps arrive." else "Rien dans les deux prochaines semaines. Profite !", 15f, R.color.on_accent).apply { setPadding(0, dp(6), 0, 0) })
            return card
        }
        val style = Subjects.style(focus.subject)
        val ink = Subjects.onSolid(style.family)
        val frame = FrameLayout(ctx).apply {
            background = UI.sticker(ctx, Subjects.solid(style.family), Subjects.edge(ctx, style.family), 26, 5)
            clipToOutline = true
        }
        // Big subject icon in the corner, like a watermark.
        frame.addView(UI.icon(ctx, style.icon, (ink and 0x00FFFFFF) or 0x38000000, 118, tintIsColor = true).apply {
            layoutParams = FrameLayout.LayoutParams(dp(118), dp(118), Gravity.BOTTOM or Gravity.END).apply { rightMargin = dp(4); bottomMargin = dp(10) }
        })
        val col = UI.column(ctx).apply { setPadding(dp(18), dp(16), dp(18), dp(16 + 5)) }
        val tag = UI.row(ctx).apply {
            background = UI.rounded((ink and 0x00FFFFFF) or 0x24000000, dp(99).toFloat())
            setPadding(dp(10), dp(4), dp(12), dp(5))
            addView(UI.icon(ctx, R.drawable.ic_clock, ink, 14, tintIsColor = true))
            addView(UI.hgap(ctx, 5))
            addView(UI.text(ctx, Agenda.when_(focus, s.now), 12f, R.color.on_accent, 800).apply { setTextColor(ink) })
        }
        col.addView(tag, UI.wrap())
        val hasRoom = focus.rooms.isNotEmpty()
        val big = UI.oneLine(UI.title(ctx, if (hasRoom) Agenda.room(focus) else Agenda.pretty(focus.subject), if (hasRoom) 56f else 34f)).apply {
            setPadding(0, dp(10), 0, dp(4))
            setTextColor(ink)
        }
        if (focus.canceled) big.paintFlags = big.paintFlags or Paint.STRIKE_THRU_TEXT_FLAG
        col.addView(big)
        val teacher = focus.teachers.firstOrNull()?.let { Agenda.prettyName(it) }
        val subtitle = if (hasRoom) listOfNotNull(Agenda.pretty(focus.subject), teacher).joinToString(" · ") else teacher.orEmpty()
        if (subtitle.isNotEmpty()) col.addView(UI.oneLine(UI.text(ctx, subtitle, 16f, weight = 800)).apply { setTextColor(ink) })
        col.addView(UI.text(ctx, "${Agenda.time(focus.start)} – ${Agenda.time(focus.end)}", 13f).apply { setTextColor(ink); alpha = 0.85f })
        LessonNotes.line(ctx, focus, ink, 14f)?.let { col.addView(it, UI.matchWidth()) }
        Agenda.alert(focus)?.let {
            col.addView(UI.gap(ctx, 10))
            col.addView(UI.chip(ctx, it, Color.WHITE, if (focus.canceled) 0xFFB3423A.toInt() else 0xFF9A6212.toInt(), 13f), UI.wrap())
        }
        frame.addView(col, FrameLayout.LayoutParams(-1, -2))
        frame.setOnClickListener { activity.showLesson(focus, s) }
        Motion.pressable(frame, 5, haptic = false)
        return frame
    }

    private fun thenCard(next: Lesson): View {
        val card = UI.row(ctx).apply {
            background = UI.sticker(ctx, color(R.color.surface), color(R.color.edge))
            UI.stickerPadding(this, 12, 10)
        }
        card.addView(UI.subjectTile(ctx, next.subject))
        card.addView(UI.hgap(ctx, 12))
        val col = UI.column(ctx)
        col.addView(UI.oneLine(UI.title(ctx, "Ensuite · ${Agenda.room(next)}", 17f)))
        col.addView(UI.oneLine(UI.text(ctx, "${Agenda.time(next.start)} · ${Agenda.pretty(next.subject)}", 13f, R.color.text2)).apply { setPadding(0, dp(3), 0, 0) })
        card.addView(col, UI.weight())
        Agenda.alert(next)?.let { card.addView(UI.alertChip(ctx, it, next.canceled)) }
        card.setOnClickListener { activity.showLesson(next, lastSnapshot!!) }
        Motion.pressable(card, haptic = false)
        return card
    }

    private fun alertBanner(lesson: Lesson, today: LocalDate): View {
        val severe = lesson.canceled
        val row = UI.row(ctx).apply {
            background = UI.sticker(ctx, color(if (severe) R.color.alert_bg else R.color.warn_bg), color(if (severe) R.color.alert_edge else R.color.warn_edge), 18)
            UI.stickerPadding(this, 12, 10)
        }
        row.addView(UI.icon(ctx, if (lesson.teacherAbsent) R.drawable.ic_absent else R.drawable.ic_warning, if (severe) R.color.alert else R.color.warn, 20))
        row.addView(UI.hgap(ctx, 10))
        val day = Agenda.dayTitle(lesson.start.toLocalDate(), today).let { if (it == "Aujourd'hui") "" else "${it.lowercase(fr)} " }
        val text = "${Agenda.pretty(lesson.subject)} ${day}${Agenda.time(lesson.start)} : ${Agenda.alert(lesson)!!.replaceFirstChar { it.lowercase(fr) }}"
        row.addView(UI.text(ctx, text, 14f, if (severe) R.color.alert else R.color.warn, 800), UI.weight())
        row.setOnClickListener { activity.showLesson(lesson, lastSnapshot!!) }
        Motion.pressable(row, haptic = false)
        return row
    }

    private fun gradeTile(g: Grade, isNew: Boolean): View {
        val tile = UI.column(ctx).apply {
            gravity = Gravity.CENTER_HORIZONTAL
            background = UI.sticker(ctx, color(R.color.surface), color(R.color.edge), 20, 4, pressable = true)
            UI.stickerPadding(this, 6, 10)
            setOnClickListener { activity.showGrade(g) }
            Motion.pressable(this)
        }
        val value = UI.row(ctx).apply { gravity = Gravity.BOTTOM }
        value.addView(UI.title(ctx, g.value.replace('.', ','), 24f))
        if (g.points != null && g.outOf != null) value.addView(UI.text(ctx, "/${UI.number(g.outOf)}", 12f, R.color.text2).apply { setPadding(0, 0, 0, dp(1)) })
        tile.addView(value, UI.wrap())
        tile.addView(UI.oneLine(UI.text(ctx, Agenda.pretty(g.subject), 11f, R.color.text2, 800)).apply { gravity = Gravity.CENTER; setPadding(dp(2), dp(2), dp(2), 0) }, UI.matchWidth())
        if (isNew) tile.addView(UI.chip(ctx, "NOUVEAU", NEW_BADGE, Color.WHITE, 9f), UI.wrap().apply { topMargin = ctx.dp(5) })
        return tile
    }

    private fun footer(s: Snapshot): View {
        val col = UI.column(ctx).apply { gravity = Gravity.CENTER_HORIZONTAL; setPadding(0, dp(28), 0, dp(8)) }
        col.addView(UI.text(ctx, "Enregistré sur ton téléphone · mis à jour ${Agenda.ago(s.status.lastSuccess, s.now)}", 12f, R.color.text3).apply { gravity = Gravity.CENTER }, UI.matchWidth())
        return col
    }

    // ---- Cours -------------------------------------------------------------------

    fun timetable(s: Snapshot, selected: LocalDate?, onSelect: (LocalDate) -> Unit): View {
        lastSnapshot = s
        val root = UI.column(ctx)
        val today = s.now.toLocalDate()
        val days = (0L..13L).map { today.plusDays(it) }.filter { d ->
            d.dayOfWeek != DayOfWeek.SUNDAY && (d.dayOfWeek != DayOfWeek.SATURDAY || s.lessons.any { it.start.toLocalDate() == d })
        }
        val focusDay = Agenda.view(s.lessons, s.now).dayDate
        val day = selected?.takeIf { it in days } ?: focusDay?.takeIf { it in days } ?: today

        val strip = UI.row(ctx).apply { setPadding(0, dp(2), dp(8), dp(2)) }
        for (d in days) strip.addView(dayChip(d, d == day, s, onSelect))
        val scroller = HorizontalScrollView(ctx).apply {
            isHorizontalScrollBarEnabled = false
            addView(strip)
        }
        root.addView(scroller, UI.matchWidth())
        // Back to where the strip was, at once (no visible jump), then nudge only if
        // the selected day is off screen.
        // The previous strip may still be finishing its own smooth scroll after it was
        // replaced: only the strip on screen records its position, so the new one
        // never starts from a stale value and slide the wrong way.
        val restore = activity.stripScroll
        activity.stripOwner = scroller
        scroller.setOnScrollChangeListener { v, x, _, _, _ -> if (activity.stripOwner === v) activity.stripScroll = x }
        scroller.viewTreeObserver.addOnPreDrawListener(object : android.view.ViewTreeObserver.OnPreDrawListener {
            override fun onPreDraw(): Boolean {
                scroller.viewTreeObserver.removeOnPreDrawListener(this)
                val chip = strip.getChildAt(days.indexOf(day)) ?: return true
                val max = (strip.width - scroller.width).coerceAtLeast(0)
                val centered = (chip.left - (scroller.width - chip.width) / 2).coerceIn(0, max)
                if (restore < 0) {
                    scroller.scrollTo(centered, 0)
                    // Recorded even when it did not move (first days, at 0): otherwise the
                    // first tap on a day found no position and jumped without sliding.
                    activity.stripScroll = centered
                } else {
                    scroller.scrollTo(restore.coerceIn(0, max), 0)
                    if (kotlin.math.abs(centered - restore) > ctx.dp(4)) scroller.smoothScrollTo(centered, 0)
                }
                return true
            }
        })

        val title = Agenda.dayTitle(day, today).let { if (day <= today.plusDays(1)) "$it · ${day.dayOfMonth} ${day.month.getDisplayName(TextStyle.FULL, fr)}" else it }
        root.addView(UI.section(ctx, title))
        // The next school day: its bag, to pack the evening before.
        Bag.entry(activity, s, day)?.let { root.addView(it, UI.matchWidth().apply { bottomMargin = ctx.dp(10) }) }
        val lessons = s.lessons.filter { it.start.toLocalDate() == day }
        if (lessons.isEmpty()) {
            root.addView(emptyCard(R.drawable.ic_sparkles, "Pas cours ce jour-là."), UI.matchWidth())
        } else {
            val focus = Agenda.view(s.lessons, s.now).focus
            lessons.forEachIndexed { i, lesson ->
                root.addView(lessonCard(lesson, lesson === focus, s.now), UI.matchWidth().apply { if (i > 0) topMargin = ctx.dp(10) })
            }
        }
        val dayHomework = s.homework.filter { it.due == day }
        if (dayHomework.isNotEmpty()) {
            root.addView(UI.section(ctx, "À rendre ce jour-là"))
            val card = UI.card(ctx, padding = 14)
            dayHomework.forEachIndexed { i, h ->
                if (i > 0) card.addView(UI.divider(ctx))
                card.addView(homeworkRow(h, compact = true), UI.matchWidth())
            }
            root.addView(card, UI.matchWidth())
        }
        return root
    }

    private fun dayChip(d: LocalDate, selected: Boolean, s: Snapshot, onSelect: (LocalDate) -> Unit): View {
        val chip = UI.column(ctx).apply {
            gravity = Gravity.CENTER_HORIZONTAL
            background = if (selected) UI.sticker(ctx, color(R.color.accent), color(R.color.accent_edge), 16, 4, true)
            else UI.sticker(ctx, color(R.color.surface), color(R.color.edge), 16, 4, true)
            setPadding(0, dp(8), 0, dp(7 + 4))
            setOnClickListener { Motion.tick(this); onSelect(d) }
            Motion.pressable(this, haptic = false)
            if (selected && activity.entering != MainActivity.Enter.NONE) post { Motion.pop(this, 0.9f) }
        }
        chip.addView(UI.text(ctx, d.dayOfWeek.getDisplayName(TextStyle.SHORT, fr).trimEnd('.').uppercase(fr), 11f, if (selected) R.color.on_accent else R.color.text2, 800).apply { gravity = Gravity.CENTER }, UI.matchWidth())
        chip.addView(UI.title(ctx, d.dayOfMonth.toString(), 21f, if (selected) R.color.on_accent else R.color.text).apply { gravity = Gravity.CENTER; setPadding(0, dp(2), 0, 0) }, UI.matchWidth())
        val hasAlert = s.lessons.any { it.start.toLocalDate() == d && Agenda.alert(it) != null }
        chip.addView(View(ctx).apply {
            background = UI.rounded(if (selected) color(R.color.on_accent) else NEW_BADGE, dp(3).toFloat())
            visibility = if (hasAlert) View.VISIBLE else View.INVISIBLE
        }, LinearLayout.LayoutParams(ctx.dp(6), ctx.dp(6)).apply { topMargin = ctx.dp(4) })
        chip.layoutParams = LinearLayout.LayoutParams(ctx.dp(52), LinearLayout.LayoutParams.WRAP_CONTENT).apply { marginEnd = ctx.dp(8) }
        return chip
    }

    private fun lessonCard(lesson: Lesson, isFocus: Boolean, now: LocalDateTime): View {
        val style = Subjects.style(lesson.subject)
        val card = UI.row(ctx).apply {
            background = if (isFocus) UI.sticker(ctx, Subjects.tint(ctx, style.family), Subjects.edge(ctx, style.family))
            else UI.sticker(ctx, color(R.color.surface), color(R.color.edge))
            UI.stickerPadding(this, 12, 11)
            if (lesson.end <= now) alpha = 0.5f
        }
        val times = UI.column(ctx)
        times.addView(UI.title(ctx, Agenda.time(lesson.start), 16f))
        times.addView(UI.text(ctx, Agenda.time(lesson.end), 11f, R.color.text2).apply { setPadding(0, dp(2), 0, 0) })
        card.addView(times, LinearLayout.LayoutParams(ctx.dp(50), LinearLayout.LayoutParams.WRAP_CONTENT))
        card.addView(UI.subjectTile(ctx, lesson.subject))
        card.addView(UI.hgap(ctx, 12))
        val col = UI.column(ctx)
        val hasRoom = lesson.rooms.isNotEmpty()
        val room = UI.oneLine(UI.title(ctx, if (hasRoom) Agenda.room(lesson) else Agenda.pretty(lesson.subject), if (hasRoom) 21f else 18f))
        if (lesson.canceled) room.paintFlags = room.paintFlags or Paint.STRIKE_THRU_TEXT_FLAG
        col.addView(room)
        val teacher = lesson.teachers.firstOrNull()?.let { Agenda.prettyName(it) }
        val subtitle = if (hasRoom) listOfNotNull(Agenda.pretty(lesson.subject), teacher).joinToString(" · ") else teacher ?: "Pas de salle indiquée"
        col.addView(UI.oneLine(UI.text(ctx, subtitle, 12f, R.color.text2)).apply { setPadding(0, dp(3), 0, 0) })
        LessonNotes.line(ctx, lesson, color(R.color.accent_ink))?.let { col.addView(it, UI.matchWidth()) }
        card.addView(col, UI.weight())
        Agenda.alert(lesson)?.let { card.addView(UI.alertChip(ctx, it, lesson.canceled), UI.wrap().apply { marginStart = ctx.dp(6) }) }
        card.setOnClickListener { activity.showLesson(lesson, lastSnapshot!!) }
        Motion.pressable(card, haptic = false)
        return card
    }

    // ---- Devoirs -----------------------------------------------------------------

    fun homework(s: Snapshot): View {
        lastSnapshot = s
        val root = UI.column(ctx)
        val today = s.now.toLocalDate()
        val upcoming = s.homework.filter { it.due >= today }
        if (upcoming.isEmpty()) {
            root.addView(emptyCard(R.drawable.ic_sparkles, if (s.status.lastSuccess == 0L) "Les devoirs arrivent à la prochaine mise à jour." else "Aucun devoir pour les deux prochaines semaines !"), UI.matchWidth())
            return root
        }
        for ((date, items) in upcoming.groupBy { it.due }.toSortedMap()) {
            root.addView(UI.section(ctx, "Pour ${dayLabel(date, today)}"))
            val card = UI.card(ctx, padding = 14)
            items.forEachIndexed { i, h ->
                if (i > 0) card.addView(UI.divider(ctx))
                card.addView(homeworkRow(h, compact = false), UI.matchWidth())
            }
            root.addView(card, UI.matchWidth())
        }
        val waiting = repo.pendingCount()
        val note = if (waiting == 0) "Ce que tu coches ici est aussi coché sur Pronote."
        else "$waiting coche${if (waiting > 1) "s" else ""} en attente d'envoi à Pronote…"
        root.addView(UI.text(ctx, note, 12f, if (waiting == 0) R.color.text3 else R.color.warn, 800).apply {
            gravity = Gravity.CENTER
            setPadding(0, dp(22), 0, 0)
        }, UI.matchWidth())
        return root
    }

    /** "4 à faire · 1 fait", for the page header. */
    fun homeworkSummary(s: Snapshot): String {
        val upcoming = s.homework.filter { it.due >= s.now.toLocalDate() }
        val done = upcoming.count { repo.isDone(it) }
        val left = upcoming.size - done
        return when {
            upcoming.isEmpty() -> "Rien à faire"
            left == 0 -> "Tout est fait, bravo !"
            done == 0 -> "$left à faire"
            else -> "$left à faire · $done fait${if (done > 1) "s" else ""}"
        }
    }

    private fun homeworkRow(h: Homework, compact: Boolean): View {
        val done = repo.isDone(h)
        val row = UI.row(ctx).apply {
            gravity = Gravity.TOP
            setPadding(0, dp(10), 0, dp(10))
        }
        // A spinner while the tick travels to PRONOTE.
        val box = UI.homeworkBox(ctx, done, activity.isHomeworkSending(h)) { activity.setHomeworkDone(h, !done) }
        row.addView(box, LinearLayout.LayoutParams(ctx.dp(28), ctx.dp(28)).apply { topMargin = ctx.dp(2) })
        row.addView(UI.hgap(ctx, 12))
        val col = UI.column(ctx).apply { if (done) alpha = 0.45f }
        val head = UI.row(ctx)
        head.addView(UI.subjectChip(ctx, h.subject), UI.wrap())
        head.addView(View(ctx), UI.weight())
        if (h.attachments > 0) head.addView(UI.icon(ctx, R.drawable.ic_attach, R.color.text3, 16))
        col.addView(head, UI.matchWidth())
        val description = UI.text(ctx, h.description.ifEmpty { "(pas de consigne)" }, 15f).apply {
            maxLines = if (compact) 2 else if (h.id in activity.expanded) Int.MAX_VALUE else 4
            ellipsize = android.text.TextUtils.TruncateAt.END
            setPadding(0, dp(5), 0, 0)
        }
        if (done) description.paintFlags = description.paintFlags or Paint.STRIKE_THRU_TEXT_FLAG
        col.addView(description)
        col.setOnClickListener {
            Motion.tap(col)
            lastSnapshot?.let { HomeworkSheet.show(activity, h, it, activity.bottomInsetPx) }
        }
        row.addView(col, UI.weight())
        return row
    }

    // ---- Notes -------------------------------------------------------------------

    private var lastReport: Report? = null

    fun grades(s: Snapshot, bySubject: Boolean, onMode: (Boolean) -> Unit): View {
        val root = UI.column(ctx)
        val report = s.report
        lastReport = report
        if (report == null || (report.grades.isEmpty() && report.subjects.isEmpty())) {
            root.addView(emptyCard(R.drawable.ic_star, if (s.status.lastSuccess == 0L) "Tes notes arrivent à la prochaine mise à jour." else "Pas encore de note sur cette période."), UI.matchWidth())
            return root
        }

        val overview = UI.card(ctx, R.color.accent, R.color.accent_edge, padding = 18)
        overview.addView(UI.text(ctx, "MOYENNE GÉNÉRALE", 12f, R.color.on_accent, 800).apply { alpha = 0.85f; letterSpacing = 0.04f })
        val line = UI.row(ctx).apply { gravity = Gravity.BOTTOM }
        val average = UI.title(ctx, UI.number(report.overall), 52f, R.color.on_accent).apply { setPadding(0, dp(6), 0, 0) }
        line.addView(average)
        if (activity.entering != MainActivity.Enter.NONE) report.overall?.let { target -> Motion.countUp(average, target) { UI.number(it) } }
        line.addView(UI.title(ctx, " /20", 18f, R.color.on_accent).apply { alpha = 0.8f; setPadding(0, 0, 0, dp(6)) })
        overview.addView(line)
        report.classOverall?.let { overview.addView(UI.text(ctx, "Classe : ${UI.number(it)}", 13f, R.color.on_accent, 800).apply { alpha = 0.85f; setPadding(0, dp(6), 0, 0) }) }
        overview.addView(UI.row(ctx).apply {
            setPadding(0, dp(10), 0, 0)
            addView(UI.text(ctx, "Voir mes statistiques", 13f, R.color.on_accent, 900))
            addView(UI.icon(ctx, R.drawable.ic_chart, R.color.on_accent, 16).apply { (layoutParams as LinearLayout.LayoutParams).marginStart = dp(6) })
        })
        overview.setOnClickListener { Motion.tap(overview); GradesSheets.showOverview(activity, report, activity.bottomInsetPx) }
        Motion.pressable(overview, haptic = false)
        activity.offerWidget(overview, AverageWidget::class.java)
        root.addView(overview, UI.matchWidth())

        val seg = UI.row(ctx).apply {
            background = UI.rounded(color(R.color.surface2), dp(16).toFloat())
            setPadding(dp(4), dp(4), dp(4), dp(4))
        }
        for ((label, mode) in listOf("Dernières notes" to false, "Par matière" to true)) {
            val on = mode == bySubject
            seg.addView(UI.text(ctx, label, 14f, if (on) R.color.text else R.color.text2, 800).apply {
                gravity = Gravity.CENTER
                if (on) {
                    background = UI.sticker(ctx, color(R.color.surface), color(R.color.edge), 12, 2)
                    setPadding(0, dp(8), 0, dp(10))
                } else setPadding(0, dp(8), 0, dp(8))
                setOnClickListener { if (!on) { Motion.tick(this); onMode(mode) } }
            }, UI.weight())
        }
        root.addView(seg, UI.matchWidth().apply { topMargin = ctx.dp(16); bottomMargin = ctx.dp(12) })

        val card = UI.card(ctx, padding = 14)
        if (!bySubject) {
            report.grades.forEachIndexed { i, g ->
                if (i > 0) card.addView(UI.divider(ctx))
                card.addView(gradeRow(g, g.key in s.unseenGrades), UI.matchWidth())
            }
        } else {
            report.subjects.forEachIndexed { i, sub ->
                if (i > 0) card.addView(UI.divider(ctx))
                card.addView(subjectRow(sub), UI.matchWidth())
            }
        }
        root.addView(card, UI.matchWidth())
        return root
    }

    private fun gradeRow(g: Grade, isNew: Boolean): View {
        val row = UI.row(ctx).apply {
            setPadding(0, dp(10), 0, dp(10))
            foreground = android.graphics.drawable.RippleDrawable(android.content.res.ColorStateList.valueOf(0x22000000), null, UI.rounded(Color.WHITE, dp(14).toFloat()))
            setOnClickListener { Motion.tap(this); activity.showGrade(g) }
        }
        row.addView(UI.subjectTile(ctx, g.subject))
        row.addView(UI.hgap(ctx, 12))
        val col = UI.column(ctx)
        val top = UI.row(ctx)
        top.addView(UI.oneLine(UI.title(ctx, Agenda.pretty(g.subject), 16f)), LinearLayout.LayoutParams(0, -2, 1f))
        if (isNew) top.addView(UI.chip(ctx, "NOUVEAU", NEW_BADGE, Color.WHITE, 9f), UI.wrap().apply { marginStart = ctx.dp(6) })
        col.addView(top, UI.matchWidth())
        val details = buildList {
            add("${g.date.dayOfMonth} ${g.date.month.getDisplayName(TextStyle.SHORT, fr)}")
            g.classAverage?.let { add("classe ${UI.number(it)}") }
            g.coefficient?.takeIf { it != 1.0 }?.let { add("coef ${UI.number(it)}") }
        }.joinToString(" · ")
        col.addView(UI.oneLine(UI.text(ctx, details, 12f, R.color.text2)).apply { setPadding(0, dp(3), 0, 0) })
        g.comment?.let { col.addView(UI.oneLine(UI.text(ctx, "« $it »", 12f, R.color.text3))) }
        row.addView(col, UI.weight())
        row.addView(UI.hgap(ctx, 10))
        row.addView(gradeValue(g))
        return row
    }

    /** Green sticker above the class average; neutral otherwise. Never red for a grade. */
    private fun gradeValue(g: Grade): View {
        val on20 = if (g.points != null && g.outOf != null && g.outOf > 0) g.points / g.outOf * 20 else null
        val class20 = if (g.classAverage != null && g.outOf != null && g.outOf > 0) g.classAverage / g.outOf * 20 else null
        val good = on20 != null && class20 != null && on20 >= class20
        val value = UI.row(ctx).apply {
            gravity = Gravity.BOTTOM
            background = if (good) UI.sticker(ctx, color(R.color.success_bg), color(R.color.success_edge), 12, 2)
            else UI.sticker(ctx, color(R.color.surface2), color(R.color.edge), 12, 2)
            setPadding(dp(10), dp(3), dp(10), dp(5))
        }
        value.addView(UI.title(ctx, g.value.replace('.', ','), 19f, if (good) R.color.success else R.color.text))
        if (g.points != null && g.outOf != null) value.addView(UI.text(ctx, "/${UI.number(g.outOf)}", 11f, if (good) R.color.success else R.color.text2).apply { alpha = 0.75f; setPadding(0, 0, 0, dp(1)) })
        return value
    }

    private fun subjectRow(sub: SubjectAverage): View {
        val family = Subjects.style(sub.subject).family
        val row = UI.row(ctx).apply {
            setPadding(0, dp(10), 0, dp(10))
            foreground = android.graphics.drawable.RippleDrawable(android.content.res.ColorStateList.valueOf(0x22000000), null, UI.rounded(Color.WHITE, dp(14).toFloat()))
            setOnClickListener {
                Motion.tap(this)
                lastReport?.let { GradesSheets.showSubject(activity, sub, it, activity.bottomInsetPx) }
            }
        }
        row.addView(UI.subjectTile(ctx, sub.subject))
        row.addView(UI.hgap(ctx, 12))
        val col = UI.column(ctx)
        val top = UI.row(ctx)
        top.addView(UI.oneLine(UI.title(ctx, Agenda.pretty(sub.subject), 16f)), UI.weight())
        top.addView(UI.title(ctx, UI.number(sub.student), 19f))
        col.addView(top, UI.matchWidth())
        // The student's average out of 20, in the subject's colour.
        val track = FrameLayout(ctx).apply { background = UI.rounded(color(R.color.surface2), dp(5).toFloat()) }
        val fraction = ((sub.student ?: 0.0) / 20.0).coerceIn(0.0, 1.0)
        val fill = View(ctx).apply { background = UI.rounded(Subjects.solid(family), dp(5).toFloat()) }
        track.addView(fill, FrameLayout.LayoutParams(0, ctx.dp(10)))
        val grow = activity.entering != MainActivity.Enter.NONE
        track.post {
            fill.layoutParams = FrameLayout.LayoutParams((track.width * fraction).toInt(), ctx.dp(10))
            fill.requestLayout()
            if (grow && android.animation.ValueAnimator.areAnimatorsEnabled()) {
                fill.pivotX = 0f
                fill.scaleX = 0f
                fill.animate().scaleX(1f).setStartDelay(150).setDuration(600).setInterpolator(android.view.animation.DecelerateInterpolator(2f)).start()
            }
        }
        col.addView(track, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, ctx.dp(10)).apply { topMargin = ctx.dp(6) })
        sub.classAverage?.let { col.addView(UI.text(ctx, "Classe : ${UI.number(it)}", 12f, R.color.text2).apply { setPadding(0, dp(4), 0, 0) }) }
        row.addView(col, UI.weight())
        return row
    }

    /** "Pour demain" (or another day): its homework, the first four, the rest unfolding in place. */
    private fun homeworkBlock(root: LinearLayout, day: java.time.LocalDate, today: java.time.LocalDate, due: List<Homework>) {
        root.addView(sectionWithLink("Pour ${dayLabel(day, today)}", "Tout voir") { activity.openTab(MainActivity.Tab.HOMEWORK) })
        if (due.isEmpty()) {
            root.addView(emptyCard(R.drawable.ic_sparkles, "Rien à rendre. Profites-en !"), UI.matchWidth())
            return
        }
        val card = UI.card(ctx, padding = 14)
        val shown = if (day in unfoldedDays) due.size else 4
        due.take(shown).forEachIndexed { i, h ->
            if (i > 0) card.addView(UI.divider(ctx))
            card.addView(homeworkRow(h, compact = true), UI.matchWidth())
        }
        if (due.size > shown) {
            // Unfolds the rest right here: the student stays on the day they were looking at.
            val more = UI.text(ctx, "+ ${due.size - 4} autre${if (due.size > 5) "s" else ""}  ▾", 14f, R.color.accent_ink, 800).apply {
                gravity = Gravity.CENTER
                setPadding(0, dp(12), 0, dp(4))
                contentDescription = "Voir les ${due.size - 4} autres devoirs"
            }
            more.setOnClickListener {
                Motion.tap(more)
                unfoldedDays += day
                val at = card.indexOfChild(more)
                card.removeView(more)
                due.drop(4).forEachIndexed { i, h ->
                    card.addView(UI.divider(ctx), at + 2 * i)
                    val row = homeworkRow(h, compact = true)
                    card.addView(row, at + 2 * i + 1, UI.matchWidth())
                    Motion.fadeIn(row, 40L * i)
                }
            }
            card.addView(more, UI.matchWidth())
        }
        root.addView(card, UI.matchWidth())
    }

    // ---- Shared ------------------------------------------------------------------

    private fun emptyCard(icon: Int, text: String) = UI.card(ctx, padding = 18).apply {
        gravity = Gravity.CENTER_HORIZONTAL
        addView(UI.icon(ctx, icon, R.color.accent_ink, 28))
        addView(UI.text(ctx, text, 15f, R.color.text2, 800).apply { gravity = Gravity.CENTER; setPadding(0, dp(8), 0, 0) }, UI.matchWidth())
    }

    private fun sectionWithLink(title: String, link: String, onClick: () -> Unit): View {
        val row = UI.row(ctx).apply { gravity = Gravity.BOTTOM }
        row.addView(UI.section(ctx, title), UI.weight())
        row.addView(UI.text(ctx, link, 13f, R.color.accent_ink, 800).apply {
            setPadding(dp(8), dp(22), dp(4), dp(12))
            setOnClickListener { onClick() }
        })
        return row
    }
}
