package fr.sallio.app

import android.app.Dialog
import android.graphics.Color
import android.graphics.Paint
import android.graphics.drawable.ColorDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import fr.sallio.pronote.Lesson
import java.text.Normalizer
import java.time.Duration
import java.time.format.TextStyle
import java.util.Locale

/** Everything useful about one lesson: where, who, what to hand in, when it comes back. */
object LessonSheet {

    private fun fold(text: String) =
        Normalizer.normalize(text, Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "").lowercase().replace(Regex("[^a-z0-9]"), "")

    fun show(activity: MainActivity, lesson: Lesson, s: Snapshot, bottomInset: Int) {
        Analytics.event("fiche_ouverte", "fiche" to "cours")
        val ctx = activity
        val fr = Locale.FRANCE
        val style = Subjects.style(lesson.subject)
        Sheet.open(activity, bottomInset) { sheet, _ ->

            // Header.
            val head = UI.row(ctx)
            head.addView(UI.subjectTile(ctx, lesson.subject, 48))
            head.addView(UI.hgap(ctx, 14))
            val titles = UI.column(ctx)
            titles.addView(UI.oneLine(UI.title(ctx, Agenda.pretty(lesson.subject), 21f)))
            val date = lesson.start.toLocalDate()
            val day = Agenda.dayTitle(date, s.now.toLocalDate()).let { if (it == "Aujourd'hui" || it == "Demain") "$it, ${date.dayOfMonth} ${date.month.getDisplayName(TextStyle.FULL, fr)}" else it }
            titles.addView(UI.text(ctx, "$day · ${Agenda.time(lesson.start)} – ${Agenda.time(lesson.end)}", 13f, R.color.text2).apply { setPadding(0, ctx.dp(4), 0, 0) })
            head.addView(titles, UI.weight())
            sheet.addView(head, UI.matchWidth())

            // The room, big, on the subject colour.
            val ink = Subjects.onSolid(style.family)
            val hero = FrameLayout(ctx).apply {
                background = UI.sticker(ctx, Subjects.solid(style.family), Subjects.edge(ctx, style.family), 24, 5)
                clipToOutline = true
            }
            hero.addView(UI.icon(ctx, style.icon, (ink and 0x00FFFFFF) or 0x30000000, 96, tintIsColor = true).apply {
                layoutParams = FrameLayout.LayoutParams(ctx.dp(96), ctx.dp(96), Gravity.END or Gravity.CENTER_VERTICAL).apply { rightMargin = ctx.dp(10) }
            })
            val heroCol = UI.column(ctx).apply { setPadding(ctx.dp(18), ctx.dp(14), ctx.dp(18), ctx.dp(19)) }
            heroCol.addView(UI.text(ctx, if (lesson.rooms.size > 1) "SALLES" else "SALLE", 12f, weight = 900).apply { setTextColor(ink); alpha = 0.8f; letterSpacing = 0.06f })
            val room = UI.title(ctx, Agenda.room(lesson), 46f).apply { setTextColor(ink); setPadding(0, ctx.dp(4), 0, ctx.dp(2)) }
            if (lesson.canceled) room.paintFlags = room.paintFlags or Paint.STRIKE_THRU_TEXT_FLAG
            heroCol.addView(room)
            lesson.teachers.takeIf { it.isNotEmpty() }?.let { t ->
                heroCol.addView(UI.text(ctx, t.joinToString(", ") { Agenda.prettyName(it) }, 15f, weight = 800).apply { setTextColor(ink) })
            }
            Agenda.alert(lesson)?.let {
                heroCol.addView(UI.gap(ctx, 10))
                heroCol.addView(UI.chip(ctx, it, Color.WHITE, if (lesson.canceled) 0xFFB3423A.toInt() else 0xFF9A6212.toInt(), 13f), UI.wrap())
            }
            hero.addView(heroCol, FrameLayout.LayoutParams(-1, -2))
            sheet.addView(hero, UI.matchWidth().apply { topMargin = ctx.dp(16) })

            // Facts.
            val facts = buildList {
                val minutes = Duration.between(lesson.start, lesson.end).toMinutes()
                add("Durée" to if (minutes >= 60) "${minutes / 60} h ${(minutes % 60).toString().padStart(2, '0')}" else "$minutes min")
                if (lesson.groups.isNotEmpty()) add("Groupe" to lesson.groups.joinToString(", ") { it.trim('[', ']') })
                lesson.status?.let { add("Statut" to it) }
                when (lesson.kind) {
                    Lesson.Kind.DETENTION -> add("Type" to "Retenue")
                    Lesson.Kind.ACTIVITY -> add("Type" to "Sortie pédagogique")
                    Lesson.Kind.LESSON -> Unit
                }
                s.report?.subjects?.firstOrNull { fold(it.subject) == fold(lesson.subject) }?.student?.let { add("Ta moyenne" to UI.number(it)) }
            }
            sheet.addView(UI.section(ctx, "Infos"))
            sheet.addView(factsCard(activity, facts), UI.matchWidth())

            // Homework for this subject, due on this lesson's day.
            val homework = s.homework.filter { it.due == date && fold(it.subject) == fold(lesson.subject) }
            if (homework.isNotEmpty()) {
                sheet.addView(UI.section(ctx, "À rendre pour ce cours"))
                val card = UI.card(ctx, padding = 14)
                homework.forEachIndexed { i, h ->
                    if (i > 0) card.addView(UI.divider(ctx))
                    card.addView(UI.homeworkCheck(ctx, h, { activity.isHomeworkDone(h) }, { HomeworkSheet.show(activity, h, s, bottomInset) }) { done -> activity.setHomeworkDone(h, done) }, UI.matchWidth())
                }
                sheet.addView(card, UI.matchWidth())
            }

            // When the subject comes back.
            val next = s.lessons.filter { it.start > lesson.start && fold(it.subject) == fold(lesson.subject) }.take(3)
            if (next.isNotEmpty()) {
                sheet.addView(UI.section(ctx, "Prochains cours"))
                val card = UI.card(ctx, padding = 14)
                next.forEachIndexed { i, n ->
                    if (i > 0) card.addView(UI.divider(ctx))
                    card.addView(UI.row(ctx).apply {
                        setPadding(0, ctx.dp(10), 0, ctx.dp(10))
                        val label = Agenda.dayTitle(n.start.toLocalDate(), s.now.toLocalDate())
                        addView(UI.text(ctx, "$label · ${Agenda.time(n.start)}", 14f, R.color.text2), UI.weight())
                        addView(UI.title(ctx, Agenda.room(n), 16f))
                        Agenda.alert(n)?.let { a -> addView(UI.alertChip(ctx, a, n.canceled), UI.wrap().apply { marginStart = ctx.dp(8) }) }
                    }, UI.matchWidth())
                }
                sheet.addView(card, UI.matchWidth())
            }

        }
    }

    private fun factsCard(ctx: MainActivity, facts: List<Pair<String, String>>): View {
        val card = UI.card(ctx, padding = 14)
        facts.forEachIndexed { i, (label, value) ->
            if (i > 0) card.addView(UI.divider(ctx))
            card.addView(UI.row(ctx).apply {
                setPadding(0, ctx.dp(10), 0, ctx.dp(10))
                addView(UI.text(ctx, label, 14f, R.color.text2), UI.weight())
                addView(UI.title(ctx, value, 16f))
            }, UI.matchWidth())
        }
        return card
    }
}
