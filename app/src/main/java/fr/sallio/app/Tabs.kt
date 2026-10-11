package fr.sallio.app

import android.graphics.Paint
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import fr.sallio.pronote.Grade
import fr.sallio.pronote.Homework
import fr.sallio.pronote.Lesson
import fr.sallio.pronote.Report
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

/** Builds the content of each tab. Stateless apart from what [MainActivity] keeps. */
class Tabs(private val activity: MainActivity, private val repo: Repo) {

    private val ctx = activity
    private val fr = Locale.FRANCE

    // ---- Accueil -----------------------------------------------------------------

    fun home(s: Snapshot): View {
        val root = UI.column(ctx)
        val view = Agenda.view(s.lessons, s.now)

        root.addView(hero(view.focus, view.then, s), UI.matchWidth())

        // What changed: absent teachers, canceled lessons, room changes, today and tomorrow.
        val today = s.now.toLocalDate()
        val alerts = s.lessons.filter {
            it.end > s.now && it.start.toLocalDate() <= today.plusDays(1) && Agenda.alert(it) != null && it !== view.focus
        }
        if (alerts.isNotEmpty()) {
            root.addView(UI.title(ctx, "À SAVOIR"))
            val card = UI.card(ctx, padding = 6)
            alerts.forEachIndexed { i, lesson ->
                if (i > 0) card.addView(divider())
                card.addView(alertRow(lesson, s.now), UI.matchWidth())
            }
            root.addView(card, UI.matchWidth())
        }

        // Homework for the next school day.
        val nextDay = s.lessons.map { it.start.toLocalDate() }.filter { it > today }.minOrNull() ?: today.plusDays(1)
        val due = s.homework.filter { it.due == nextDay }
        root.addView(sectionWithLink("À FAIRE POUR ${Agenda.dayTitle(nextDay, today).uppercase(fr)}", "Tout voir") { activity.openTab(MainActivity.Tab.HOMEWORK) })
        if (due.isEmpty()) {
            root.addView(emptyCard("Rien à rendre. Profites-en."), UI.matchWidth())
        } else {
            val card = UI.card(ctx, padding = 6)
            due.take(4).forEachIndexed { i, h ->
                if (i > 0) card.addView(divider())
                card.addView(homeworkRow(h, compact = true), UI.matchWidth())
            }
            if (due.size > 4) card.addView(UI.text(ctx, "+ ${due.size - 4} autres", 13f, R.color.text3).apply { setPadding(dp(14), dp(4), 0, dp(10)) })
            root.addView(card, UI.matchWidth())
        }

        // Latest grades.
        val grades = s.report?.grades.orEmpty().take(3)
        if (grades.isNotEmpty()) {
            root.addView(sectionWithLink("DERNIÈRES NOTES", "Tout voir") { activity.openTab(MainActivity.Tab.GRADES) })
            val card = UI.card(ctx, padding = 6)
            grades.forEachIndexed { i, g ->
                if (i > 0) card.addView(divider())
                card.addView(gradeRow(g, g.id in s.unseenGrades), UI.matchWidth())
            }
            root.addView(card, UI.matchWidth())
        }

        root.addView(footer(s))
        return root
    }

    private fun hero(focus: Lesson?, then: Lesson?, s: Snapshot): View {
        val card = UI.card(ctx, R.color.accent, padding = 22)
        if (focus == null) {
            card.addView(UI.text(ctx, "AUCUN COURS À VENIR", 12f, R.color.on_accent, UI.MEDIUM).apply { letterSpacing = 0.08f; alpha = 0.8f })
            card.addView(UI.gap(ctx, 6))
            card.addView(UI.text(ctx, if (s.status.lastSuccess == 0L) "Chargement…" else "Repos", 40f, R.color.on_accent, UI.BOLD))
            card.addView(UI.text(ctx, if (s.status.lastSuccess == 0L) "Ton emploi du temps arrive." else "Rien dans les deux prochaines semaines.", 15f, R.color.on_accent).apply { alpha = 0.85f })
            return card
        }
        card.addView(UI.text(ctx, Agenda.when_(focus, s.now).uppercase(fr), 12f, R.color.on_accent, UI.MEDIUM).apply { letterSpacing = 0.08f; alpha = 0.85f })
        // No room (sport, outing): the subject takes the big line instead of a lonely dash.
        val hasRoom = focus.rooms.isNotEmpty()
        val big = if (hasRoom) Agenda.room(focus) else Agenda.pretty(focus.subject)
        val room = UI.oneLine(UI.text(ctx, big, if (hasRoom) 54f else 34f, R.color.on_accent, UI.BOLD)).apply { includeFontPadding = false; setPadding(0, dp(6), 0, dp(4)) }
        if (focus.canceled) room.paintFlags = room.paintFlags or Paint.STRIKE_THRU_TEXT_FLAG
        card.addView(room)
        val teacher = focus.teachers.firstOrNull()?.let { Agenda.prettyName(it) }
        val subtitle = if (hasRoom) listOfNotNull(Agenda.pretty(focus.subject), teacher).joinToString(" · ") else teacher ?: ""
        if (subtitle.isNotEmpty()) card.addView(UI.oneLine(UI.text(ctx, subtitle, 16f, R.color.on_accent, UI.MEDIUM)))
        card.addView(UI.text(ctx, "${Agenda.time(focus.start)} – ${Agenda.time(focus.end)}", 14f, R.color.on_accent).apply { alpha = 0.8f })
        Agenda.alert(focus)?.let {
            card.addView(UI.gap(ctx, 10))
            card.addView(UI.pill(ctx, it, focus.canceled, onDark = true), UI.wrap())
        }
        then?.let { next ->
            val strip = UI.row(ctx).apply {
                background = UI.rounded(0x26FFFFFF, dp(14).toFloat())
                setPadding(dp(12), dp(10), dp(12), dp(10))
            }
            strip.addView(UI.text(ctx, "Ensuite", 13f, R.color.on_accent).apply { alpha = 0.75f })
            strip.addView(UI.hgap(ctx, 10))
            val alert = Agenda.alert(next)?.let { " · $it" } ?: ""
            strip.addView(UI.oneLine(UI.text(ctx, "${Agenda.time(next.start)}  ${Agenda.room(next)}  ${Agenda.pretty(next.subject)}$alert", 14f, R.color.on_accent, UI.MEDIUM)), UI.weight())
            card.addView(UI.gap(ctx, 14))
            card.addView(strip, UI.matchWidth())
        }
        return card
    }

    private fun alertRow(lesson: Lesson, now: LocalDateTime): View {
        val row = UI.row(ctx).apply { setPadding(dp(12), dp(12), dp(12), dp(12)) }
        row.addView(UI.icon(ctx, R.drawable.ic_warning, if (lesson.canceled) R.color.alert else R.color.warn, 20))
        row.addView(UI.hgap(ctx, 12))
        val col = UI.column(ctx)
        col.addView(UI.oneLine(UI.text(ctx, Agenda.pretty(lesson.subject), 15f, weight = UI.MEDIUM)))
        val day = Agenda.dayTitle(lesson.start.toLocalDate(), now.toLocalDate())
        col.addView(UI.text(ctx, "$day ${Agenda.time(lesson.start)} · ${Agenda.room(lesson)}", 13f, R.color.text2))
        row.addView(col, UI.weight())
        row.addView(UI.pill(ctx, Agenda.alert(lesson)!!, lesson.canceled))
        return row
    }

    private fun footer(s: Snapshot): View {
        val col = UI.column(ctx).apply { gravity = Gravity.CENTER_HORIZONTAL; setPadding(0, dp(26), 0, dp(8)) }
        col.addView(UI.text(ctx, "Enregistré sur ton téléphone · mis à jour ${Agenda.ago(s.status.lastSuccess, s.now)}", 12f, R.color.text3).apply { gravity = Gravity.CENTER })
        col.addView(UI.gap(ctx, 10))
        col.addView(UI.row(ctx).apply {
            background = UI.pressable(ctx, ctx.getColor(R.color.surface), 18)
            setPadding(dp(16), dp(10), dp(16), dp(10))
            addView(UI.icon(ctx, R.drawable.ic_open, R.color.accent, 18))
            addView(UI.hgap(ctx, 8))
            addView(UI.text(ctx, "Ouvrir Pronote", 14f, R.color.accent, UI.MEDIUM))
            setOnClickListener { activity.openPronote() }
        }, UI.wrap())
        return col
    }

    // ---- Cours -------------------------------------------------------------------

    fun timetable(s: Snapshot, selected: LocalDate?, onSelect: (LocalDate) -> Unit): View {
        val root = UI.column(ctx)
        val today = s.now.toLocalDate()
        val days = (0L..13L).map { today.plusDays(it) }.filter { d ->
            d.dayOfWeek != DayOfWeek.SUNDAY && (d.dayOfWeek != DayOfWeek.SATURDAY || s.lessons.any { it.start.toLocalDate() == d })
        }
        val focusDay = Agenda.view(s.lessons, s.now).dayDate
        val day = selected?.takeIf { it in days } ?: focusDay?.takeIf { it in days } ?: today

        val strip = UI.row(ctx).apply { setPadding(0, 0, 0, dp(4)) }
        for (d in days) strip.addView(dayChip(d, d == day, s, onSelect))
        val scroller = HorizontalScrollView(ctx).apply {
            isHorizontalScrollBarEnabled = false
            clipToPadding = false
            addView(strip)
        }
        root.addView(scroller, UI.matchWidth())
        // Keep the selected day in view.
        scroller.post {
            val index = days.indexOf(day)
            strip.getChildAt(index)?.let { scroller.smoothScrollTo((it.left - ctx.dp(24)).coerceAtLeast(0), 0) }
        }

        root.addView(UI.title(ctx, Agenda.dayTitle(day, today).uppercase(fr) + if (day.isAfter(today.plusDays(1))) "" else " · ${day.dayOfMonth} ${day.month.getDisplayName(TextStyle.FULL, fr)}".uppercase(fr)))
        val lessons = s.lessons.filter { it.start.toLocalDate() == day }
        if (lessons.isEmpty()) {
            root.addView(emptyCard(if (day.dayOfWeek == DayOfWeek.SATURDAY || day.dayOfWeek == DayOfWeek.SUNDAY) "Week-end." else "Pas cours ce jour-là."), UI.matchWidth())
        } else {
            val focus = Agenda.view(s.lessons, s.now).focus
            for (lesson in lessons) {
                root.addView(lessonCard(lesson, lesson === focus, s.now), UI.matchWidth().apply { bottomMargin = ctx.dp(8) })
            }
        }
        val dayHomework = s.homework.filter { it.due == day }
        if (dayHomework.isNotEmpty()) {
            root.addView(UI.title(ctx, "À RENDRE CE JOUR-LÀ"))
            val card = UI.card(ctx, padding = 6)
            dayHomework.forEachIndexed { i, h ->
                if (i > 0) card.addView(divider())
                card.addView(homeworkRow(h, compact = true), UI.matchWidth())
            }
            root.addView(card, UI.matchWidth())
        }
        return root
    }

    private fun dayChip(d: LocalDate, selected: Boolean, s: Snapshot, onSelect: (LocalDate) -> Unit): View {
        val chip = UI.column(ctx).apply {
            gravity = Gravity.CENTER
            background = UI.pressable(ctx, ctx.getColor(if (selected) R.color.accent else R.color.surface), 18)
            setPadding(0, dp(10), 0, dp(10))
            setOnClickListener { onSelect(d) }
        }
        val fg = if (selected) R.color.on_accent else R.color.text
        // Vertical LinearLayout children default to full width: centre the text itself.
        chip.addView(UI.text(ctx, d.dayOfWeek.getDisplayName(TextStyle.SHORT, fr).trimEnd('.').uppercase(fr), 11f, if (selected) R.color.on_accent else R.color.text3, UI.MEDIUM).apply { gravity = Gravity.CENTER })
        chip.addView(UI.text(ctx, d.dayOfMonth.toString(), 20f, fg, UI.BOLD).apply { gravity = Gravity.CENTER })
        val hasAlert = s.lessons.any { it.start.toLocalDate() == d && Agenda.alert(it) != null }
        chip.addView(View(ctx).apply {
            background = UI.rounded(ctx.getColor(if (selected) R.color.on_accent else R.color.alert), dp(3).toFloat())
            visibility = if (hasAlert) View.VISIBLE else View.INVISIBLE
        }, LinearLayout.LayoutParams(ctx.dp(6), ctx.dp(6)).apply { topMargin = ctx.dp(4) })
        chip.layoutParams = LinearLayout.LayoutParams(ctx.dp(54), LinearLayout.LayoutParams.WRAP_CONTENT).apply { marginEnd = ctx.dp(8) }
        return chip
    }

    private fun lessonCard(lesson: Lesson, isFocus: Boolean, now: LocalDateTime): View {
        val card = UI.row(ctx).apply {
            background = UI.rounded(ctx.getColor(if (isFocus) R.color.accent_container else R.color.surface), dp(20).toFloat())
            setPadding(dp(14), dp(14), dp(16), dp(14))
            gravity = Gravity.TOP
            if (lesson.end <= now) alpha = 0.5f
        }
        val times = UI.column(ctx)
        times.addView(UI.text(ctx, Agenda.time(lesson.start), 15f, if (isFocus) R.color.on_accent_container else R.color.text, UI.MEDIUM))
        times.addView(UI.text(ctx, Agenda.time(lesson.end), 12f, R.color.text3))
        card.addView(times, LinearLayout.LayoutParams(ctx.dp(54), LinearLayout.LayoutParams.WRAP_CONTENT))
        card.addView(View(ctx).apply {
            background = UI.rounded(UI.parseColor(lesson.color, ctx.getColor(R.color.accent)), dp(2).toFloat())
        }, LinearLayout.LayoutParams(ctx.dp(4), ctx.dp(52)).apply { marginEnd = ctx.dp(14) })
        val col = UI.column(ctx)
        val hasRoom = lesson.rooms.isNotEmpty()
        val room = UI.oneLine(UI.text(ctx, if (hasRoom) Agenda.room(lesson) else Agenda.pretty(lesson.subject), if (hasRoom) 22f else 19f, if (isFocus) R.color.on_accent_container else R.color.text, UI.BOLD))
        if (lesson.canceled) room.paintFlags = room.paintFlags or Paint.STRIKE_THRU_TEXT_FLAG
        col.addView(room)
        val teacher = lesson.teachers.firstOrNull()?.let { Agenda.prettyName(it) }
        val subtitle = if (hasRoom) listOfNotNull(Agenda.pretty(lesson.subject), teacher).joinToString(" · ") else teacher ?: "Pas de salle indiquée"
        col.addView(UI.oneLine(UI.text(ctx, subtitle, 14f, R.color.text2)))
        Agenda.alert(lesson)?.let {
            col.addView(UI.gap(ctx, 8))
            col.addView(UI.pill(ctx, it, lesson.canceled), UI.wrap())
        }
        card.addView(col, UI.weight())
        return card
    }

    // ---- Devoirs -----------------------------------------------------------------

    fun homework(s: Snapshot): View {
        val root = UI.column(ctx)
        val today = s.now.toLocalDate()
        val upcoming = s.homework.filter { it.due >= today }
        if (upcoming.isEmpty()) {
            root.addView(UI.gap(ctx, 8))
            root.addView(emptyCard(if (s.status.lastSuccess == 0L) "Les devoirs arrivent à la prochaine mise à jour." else "Aucun devoir pour les deux prochaines semaines."), UI.matchWidth())
            return root
        }
        val left = upcoming.count { !repo.isDone(it) }
        root.addView(UI.text(ctx, if (left == 0) "Tout est fait. Bravo." else "$left devoir${if (left > 1) "s" else ""} à faire", 15f, R.color.text2).apply { setPadding(dp(4), dp(4), 0, 0) })
        for ((date, items) in upcoming.groupBy { it.due }.toSortedMap()) {
            root.addView(UI.title(ctx, "POUR ${Agenda.dayTitle(date, today).uppercase(fr)}"))
            val card = UI.card(ctx, padding = 6)
            items.forEachIndexed { i, h ->
                if (i > 0) card.addView(divider())
                card.addView(homeworkRow(h, compact = false), UI.matchWidth())
            }
            root.addView(card, UI.matchWidth())
        }
        root.addView(UI.text(ctx, "Cocher un devoir ici ne change rien sur Pronote.", 12f, R.color.text3).apply {
            gravity = Gravity.CENTER
            setPadding(0, dp(20), 0, 0)
        }, UI.matchWidth())
        return root
    }

    private fun homeworkRow(h: Homework, compact: Boolean): View {
        val done = repo.isDone(h)
        val row = UI.row(ctx).apply {
            gravity = Gravity.TOP
            setPadding(dp(12), dp(12), dp(14), dp(12))
        }
        val box = FrameLayout(ctx).apply {
            background = if (done) UI.rounded(ctx.getColor(R.color.accent), dp(14).toFloat())
            else UI.rounded(0, dp(14).toFloat(), dp(2), ctx.getColor(R.color.text3))
            if (done) addView(UI.icon(ctx, R.drawable.ic_check, R.color.on_accent, 18).apply {
                layoutParams = FrameLayout.LayoutParams(dp(18), dp(18), Gravity.CENTER)
            })
            setOnClickListener {
                repo.setDone(h, !done)
                activity.render()
            }
        }
        row.addView(box, LinearLayout.LayoutParams(ctx.dp(28), ctx.dp(28)))
        row.addView(UI.hgap(ctx, 12))
        val col = UI.column(ctx).apply { if (done) alpha = 0.5f }
        val subject = UI.row(ctx)
        subject.addView(View(ctx).apply { background = UI.rounded(UI.parseColor(h.color, ctx.getColor(R.color.accent)), dp(4).toFloat()) },
            LinearLayout.LayoutParams(ctx.dp(8), ctx.dp(8)).apply { marginEnd = ctx.dp(6) })
        subject.addView(UI.oneLine(UI.text(ctx, Agenda.pretty(h.subject), 13f, R.color.text2, UI.MEDIUM)), UI.weight())
        if (h.attachments > 0) {
            subject.addView(UI.icon(ctx, R.drawable.ic_attach, R.color.text3, 16))
            subject.addView(UI.text(ctx, h.attachments.toString(), 12f, R.color.text3))
        }
        col.addView(subject, UI.matchWidth())
        val description = UI.text(ctx, h.description.ifEmpty { "(pas de consigne)" }, 15f).apply {
            maxLines = if (compact) 2 else if (h.id in activity.expanded) Int.MAX_VALUE else 4
            ellipsize = android.text.TextUtils.TruncateAt.END
            setPadding(0, dp(3), 0, 0)
        }
        if (done) description.paintFlags = description.paintFlags or Paint.STRIKE_THRU_TEXT_FLAG
        col.addView(description)
        if (!compact) col.setOnClickListener {
            if (!activity.expanded.remove(h.id)) activity.expanded.add(h.id)
            activity.render()
        } else col.setOnClickListener { activity.openTab(MainActivity.Tab.HOMEWORK) }
        row.addView(col, UI.weight())
        return row
    }

    // ---- Notes -------------------------------------------------------------------

    fun grades(s: Snapshot, bySubject: Boolean, onMode: (Boolean) -> Unit): View {
        val root = UI.column(ctx)
        val report = s.report
        if (report == null || (report.grades.isEmpty() && report.subjects.isEmpty())) {
            root.addView(UI.gap(ctx, 8))
            root.addView(emptyCard(if (s.status.lastSuccess == 0L) "Tes notes arrivent à la prochaine mise à jour." else "Pas encore de note sur cette période."), UI.matchWidth())
            return root
        }

        val overview = UI.card(ctx, R.color.accent_container, padding = 22)
        overview.addView(UI.text(ctx, "MOYENNE GÉNÉRALE · ${report.periodName.uppercase(fr)}", 12f, R.color.on_accent_container, UI.MEDIUM).apply { letterSpacing = 0.06f; alpha = 0.8f })
        val line = UI.row(ctx).apply { gravity = Gravity.BOTTOM }
        line.addView(UI.text(ctx, UI.number(report.overall), 48f, R.color.on_accent_container, UI.BOLD).apply { includeFontPadding = false })
        line.addView(UI.text(ctx, " /20", 18f, R.color.on_accent_container).apply { alpha = 0.7f; setPadding(0, 0, 0, dp(6)) })
        overview.addView(line)
        report.classOverall?.let { overview.addView(UI.text(ctx, "Classe : ${UI.number(it)}", 14f, R.color.on_accent_container).apply { alpha = 0.8f }) }
        root.addView(overview, UI.matchWidth())

        // Segmented switch.
        val seg = UI.row(ctx).apply {
            background = UI.rounded(ctx.getColor(R.color.surface2), dp(16).toFloat())
            setPadding(dp(4), dp(4), dp(4), dp(4))
        }
        for ((label, mode) in listOf("Dernières notes" to false, "Par matière" to true)) {
            seg.addView(UI.text(ctx, label, 14f, if (mode == bySubject) R.color.text else R.color.text2, UI.MEDIUM).apply {
                gravity = Gravity.CENTER
                setPadding(0, dp(9), 0, dp(9))
                if (mode == bySubject) background = UI.rounded(ctx.getColor(R.color.surface), dp(12).toFloat())
                setOnClickListener { onMode(mode) }
            }, UI.weight())
        }
        root.addView(seg, UI.matchWidth().apply { topMargin = ctx.dp(16); bottomMargin = ctx.dp(12) })

        val card = UI.card(ctx, padding = 6)
        if (!bySubject) {
            report.grades.forEachIndexed { i, g ->
                if (i > 0) card.addView(divider())
                card.addView(gradeRow(g, g.id in s.unseenGrades), UI.matchWidth())
            }
        } else {
            report.subjects.forEachIndexed { i, sub ->
                if (i > 0) card.addView(divider())
                card.addView(subjectRow(sub), UI.matchWidth())
            }
        }
        root.addView(card, UI.matchWidth())
        return root
    }

    private fun gradeRow(g: Grade, isNew: Boolean): View {
        val row = UI.row(ctx).apply { setPadding(dp(14), dp(12), dp(14), dp(12)) }
        val col = UI.column(ctx)
        val top = UI.row(ctx)
        top.addView(UI.oneLine(UI.text(ctx, Agenda.pretty(g.subject), 15f, weight = UI.MEDIUM)), UI.weight())
        if (isNew) top.addView(UI.text(ctx, "Nouveau", 11f, R.color.on_accent, UI.MEDIUM).apply {
            background = UI.rounded(ctx.getColor(R.color.accent), dp(8).toFloat())
            setPadding(dp(7), dp(2), dp(7), dp(2))
        })
        col.addView(top, UI.matchWidth())
        val details = buildList {
            add("${g.date.dayOfMonth} ${g.date.month.getDisplayName(TextStyle.SHORT, fr)}")
            g.classAverage?.let { add("classe ${UI.number(it)}") }
            g.coefficient?.takeIf { it != 1.0 }?.let { add("coef ${UI.number(it)}") }
        }.joinToString(" · ")
        col.addView(UI.oneLine(UI.text(ctx, details, 13f, R.color.text2)))
        g.comment?.let { col.addView(UI.oneLine(UI.text(ctx, "« $it »", 13f, R.color.text3))) }
        row.addView(col, UI.weight())
        row.addView(UI.hgap(ctx, 12))

        // Above the class average reads green, the rest stays neutral: no red for a grade.
        val on20 = if (g.points != null && g.outOf != null && g.outOf > 0) g.points / g.outOf * 20 else null
        val class20 = if (g.classAverage != null && g.outOf != null && g.outOf > 0) g.classAverage / g.outOf * 20 else null
        val good = on20 != null && class20 != null && on20 >= class20
        val value = UI.row(ctx).apply {
            gravity = Gravity.BOTTOM
            background = UI.rounded(ctx.getColor(if (good) R.color.success_bg else R.color.surface2), dp(14).toFloat())
            setPadding(dp(12), dp(6), dp(12), dp(6))
        }
        value.addView(UI.text(ctx, g.value.replace('.', ','), 20f, if (good) R.color.success else R.color.text, UI.BOLD))
        if (g.points != null && g.outOf != null) value.addView(UI.text(ctx, "/${UI.number(g.outOf)}", 12f, R.color.text2).apply { setPadding(0, 0, 0, dp(3)) })
        row.addView(value)
        return row
    }

    private fun subjectRow(sub: fr.sallio.pronote.SubjectAverage): View {
        val row = UI.column(ctx).apply { setPadding(dp(14), dp(12), dp(14), dp(12)) }
        val top = UI.row(ctx)
        top.addView(UI.oneLine(UI.text(ctx, Agenda.pretty(sub.subject), 15f, weight = UI.MEDIUM)), UI.weight())
        top.addView(UI.text(ctx, UI.number(sub.student), 18f, weight = UI.BOLD))
        row.addView(top, UI.matchWidth())
        // Bar: the student's average over 20, with the class average as a tick.
        val track = FrameLayout(ctx).apply { background = UI.rounded(ctx.getColor(R.color.surface2), dp(4).toFloat()) }
        val fraction = ((sub.student ?: 0.0) / 20.0).coerceIn(0.0, 1.0)
        val fill = View(ctx).apply { background = UI.rounded(UI.parseColor(sub.color, ctx.getColor(R.color.accent)), dp(4).toFloat()) }
        track.addView(fill, FrameLayout.LayoutParams(0, ctx.dp(8)))
        track.post { fill.layoutParams = FrameLayout.LayoutParams((track.width * fraction).toInt(), ctx.dp(8)); fill.requestLayout() }
        row.addView(track, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, ctx.dp(8)).apply { topMargin = ctx.dp(8) })
        sub.classAverage?.let { row.addView(UI.text(ctx, "Classe : ${UI.number(it)}", 12f, R.color.text3).apply { setPadding(0, dp(4), 0, 0) }) }
        return row
    }

    // ---- Shared ------------------------------------------------------------------

    private fun divider() = View(ctx).apply {
        setBackgroundColor(ctx.getColor(R.color.line))
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1).apply {
            marginStart = ctx.dp(14); marginEnd = ctx.dp(14)
        }
    }

    private fun emptyCard(text: String) = UI.card(ctx).apply {
        gravity = Gravity.CENTER
        addView(UI.text(ctx, text, 15f, R.color.text2).apply { gravity = Gravity.CENTER }, UI.matchWidth())
    }

    private fun sectionWithLink(title: String, link: String, onClick: () -> Unit): View {
        val row = UI.row(ctx)
        row.addView(UI.title(ctx, title), UI.weight())
        row.addView(UI.text(ctx, link, 13f, R.color.accent, UI.MEDIUM).apply {
            setPadding(dp(8), dp(22), dp(4), dp(8))
            setOnClickListener { onClick() }
        })
        return row
    }

}
