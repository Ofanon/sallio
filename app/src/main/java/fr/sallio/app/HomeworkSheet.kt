package fr.sallio.app

import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.view.Gravity
import android.widget.FrameLayout
import android.widget.LinearLayout
import fr.sallio.pronote.Homework
import fr.sallio.pronote.Schoolwork
import java.time.format.TextStyle
import java.util.Locale

/** Everything about one homework: the full instructions, who gave it, when, and its documents. */
object HomeworkSheet {

    fun show(activity: MainActivity, h: Homework, s: Snapshot, bottomInset: Int) {
        val ctx = activity
        val fr = Locale.FRANCE
        val style = Subjects.style(h.subject)
        Sheet.open(activity, bottomInset) { sheet, _ ->
            val head = UI.row(ctx)
            head.addView(UI.subjectTile(ctx, h.subject, 48))
            head.addView(UI.hgap(ctx, 14))
            val titles = UI.column(ctx)
            titles.addView(UI.oneLine(UI.title(ctx, Agenda.pretty(h.subject), 21f)))
            val due = Agenda.dayTitle(h.due, s.now.toLocalDate()).let { d ->
                if (d == "Aujourd'hui" || d == "Demain") "$d, ${h.due.dayOfMonth} ${h.due.month.getDisplayName(TextStyle.FULL, fr)}" else d
            }
            titles.addView(UI.text(ctx, "Pour $due".replace("Pour Aujourd", "Pour aujourd").replace("Pour Demain", "Pour demain"), 13f, R.color.text2).apply { setPadding(0, ctx.dp(4), 0, 0) })
            head.addView(titles, UI.weight())
            sheet.addView(head, UI.matchWidth())

            // Done, ticked here and on PRONOTE.
            val tick = UI.card(ctx, padding = 12)
            tick.addView(UI.homeworkCheck(ctx, h.copy(description = "Marquer comme fait"), { activity.isHomeworkDone(h) }) { done ->
                activity.setHomeworkDone(h, done)
            }, UI.matchWidth())
            sheet.addView(tick, UI.matchWidth().apply { topMargin = ctx.dp(16) })

            // The instructions, in full.
            sheet.addView(UI.section(ctx, "Consigne"))
            val text = UI.column(ctx).apply {
                background = UI.sticker(ctx, Subjects.tint(ctx, style.family), Subjects.softEdge(ctx, style.family))
                UI.stickerPadding(this, 16, 14)
            }
            text.addView(UI.text(ctx, h.description.ifEmpty { "Pas de consigne écrite." }, 16f).apply {
                setTextColor(Subjects.ink(ctx, style.family))
                setLineSpacing(0f, 1.15f)
                UI.linkify(this)
            })
            sheet.addView(text, UI.matchWidth())

            // Documents. Old caches only know how many files there are, not their names.
            if (h.files.isNotEmpty() || h.attachments > 0) {
                sheet.addView(UI.section(ctx, if (maxOf(h.files.size, h.attachments) > 1) "Pièces jointes" else "Pièce jointe"))
                val files = h.files.ifEmpty { List(h.attachments) { Homework.Attachment("Document ${it + 1}", null) } }
                sheet.addView(Attachments.card(activity, files, "hw|${h.key}", known = h.files.isNotEmpty()) { logged, name ->
                    Schoolwork.attachmentUrl(logged, h, name)
                }, UI.matchWidth())
            }

            // Facts.
            val facts = buildList {
                h.given?.let { add("Donné le" to "${Agenda.dayName(it)} ${it.dayOfMonth} ${it.month.getDisplayName(TextStyle.SHORT, fr)}") }
                if (h.teachers.isNotEmpty()) add((if (h.teachers.size > 1) "Profs" else "Prof") to h.teachers.joinToString(", ") { Agenda.prettyName(it) })
                h.minutes?.let { add("Temps estimé" to if (it >= 60) "${it / 60} h ${(it % 60).toString().padStart(2, '0')}" else "$it min") }
                s.lessons.firstOrNull { it.start.toLocalDate() == h.due && GradesSheets.fold(it.subject) == GradesSheets.fold(h.subject) }?.let {
                    add("Cours ce jour-là" to "${Agenda.time(it.start)} · ${Agenda.room(it)}")
                }
            }
            if (facts.isNotEmpty()) {
                sheet.addView(UI.section(ctx, "Infos"))
                val card = UI.card(ctx, padding = 14)
                facts.forEachIndexed { i, (label, value) ->
                    if (i > 0) card.addView(UI.divider(ctx))
                    card.addView(UI.row(ctx).apply {
                        setPadding(0, ctx.dp(10), 0, ctx.dp(10))
                        addView(UI.text(ctx, label, 14f, R.color.text2), UI.weight())
                        addView(UI.title(ctx, value, 16f))
                    }, UI.matchWidth())
                }
                sheet.addView(card, UI.matchWidth())
            }
        }
    }
}
