package fr.sallio.app

import android.app.Dialog
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.widget.FrameLayout
import android.widget.LinearLayout
import fr.sallio.pronote.Grade
import fr.sallio.pronote.Report
import java.time.format.TextStyle
import java.util.Locale

/** Everything PRONOTE says about one grade, in a bottom sheet. */
object GradeSheet {

    fun show(activity: MainActivity, g: Grade, report: Report?, bottomInset: Int) {
        val ctx = activity
        val fr = Locale.FRANCE
        val style = Subjects.style(g.subject)
        val dialog = Dialog(ctx)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)

        val sheet = UI.column(ctx).apply {
            background = UI.rounded(ctx.getColor(R.color.bg), ctx.dp(30).toFloat())
            setPadding(ctx.dp(18), ctx.dp(12), ctx.dp(18), ctx.dp(22) + bottomInset)
        }
        sheet.addView(View(ctx).apply { background = UI.rounded(ctx.getColor(R.color.text3), ctx.dp(3).toFloat()) },
            LinearLayout.LayoutParams(ctx.dp(40), ctx.dp(5)).apply { gravity = Gravity.CENTER_HORIZONTAL; bottomMargin = ctx.dp(16) })

        // Header: subject tile, subject, date.
        val head = UI.row(ctx)
        head.addView(UI.subjectTile(ctx, g.subject, 48))
        head.addView(UI.hgap(ctx, 14))
        val titles = UI.column(ctx)
        titles.addView(UI.title(ctx, Agenda.pretty(g.subject), 21f))
        val day = "${Agenda.dayName(g.date)} ${g.date.dayOfMonth} ${g.date.month.getDisplayName(TextStyle.FULL, fr)}"
        titles.addView(UI.text(ctx, day, 13f, R.color.text2).apply { setPadding(0, ctx.dp(4), 0, 0) })
        head.addView(titles, UI.weight())
        sheet.addView(head, UI.matchWidth())

        // The grade itself, big, on the subject colour.
        val hero = UI.column(ctx).apply {
            gravity = Gravity.CENTER_HORIZONTAL
            background = UI.sticker(ctx, Subjects.solid(style.family), Subjects.edge(ctx, style.family), 24, 5)
            UI.stickerPadding(this, 16, 16, 5)
        }
        val ink = Subjects.onSolid(style.family)
        val line = UI.row(ctx).apply { gravity = Gravity.BOTTOM }
        val big = UI.title(ctx, g.value.replace('.', ','), 58f).apply { setTextColor(ink) }
        line.addView(big)
        if (g.points != null && g.outOf != null) line.addView(UI.title(ctx, " /${UI.number(g.outOf)}", 22f).apply { setTextColor(ink); alpha = 0.75f; setPadding(0, 0, 0, ctx.dp(8)) })
        hero.addView(line, UI.wrap())
        val on20 = if (g.points != null && g.outOf != null && g.outOf > 0 && g.outOf != 20.0) g.points / g.outOf * 20 else null
        on20?.let { hero.addView(UI.text(ctx, "soit ${UI.number(it)} sur 20", 14f, weight = 800).apply { setTextColor(ink); alpha = 0.85f }) }
        g.points?.let { target -> Motion.countUp(big, target) { UI.number(it) } }
        sheet.addView(hero, UI.matchWidth().apply { topMargin = ctx.dp(16) })

        // Where the grade sits in the class: min, class average, max, and you.
        if (g.points != null && g.outOf != null && g.outOf > 0 && (g.classAverage != null || g.min != null || g.max != null)) {
            sheet.addView(UI.section(ctx, "Par rapport à la classe"))
            val card = UI.card(ctx, padding = 16)
            val diff = g.classAverage?.let { g.points - it }
            val verdict = when {
                diff == null -> null
                diff > 0.05 -> "${UI.number(diff)} point${if (diff >= 2) "s" else ""} au-dessus de la moyenne"
                diff < -0.05 -> "${UI.number(-diff)} point${if (-diff >= 2) "s" else ""} sous la moyenne"
                else -> "Pile dans la moyenne"
            }
            verdict?.let { card.addView(UI.title(ctx, it, 17f, if (diff!! > 0.05) R.color.success else R.color.text)) }
            card.addView(scale(activity, g, style), UI.matchWidth().apply { topMargin = ctx.dp(16) })
            card.addView(statsRow(activity, g), UI.matchWidth().apply { topMargin = ctx.dp(10) })
            sheet.addView(card, UI.matchWidth())
        }

        // Details.
        val details = buildList {
            g.coefficient?.let { add("Coefficient" to UI.number(it)) }
            report?.subjects?.firstOrNull { it.subject == g.subject }?.student?.let { add("Ta moyenne dans la matière" to UI.number(it)) }
            add("Période" to (report?.periodName ?: "—"))
        }
        if (g.comment != null || details.isNotEmpty()) {
            sheet.addView(UI.section(ctx, "Détails"))
            val card = UI.card(ctx, padding = 14)
            g.comment?.let {
                card.addView(UI.text(ctx, "« $it »", 15f, weight = 800).apply { setPadding(0, ctx.dp(2), 0, ctx.dp(10)) })
            }
            details.forEachIndexed { i, (label, value) ->
                if (i > 0 || g.comment != null) card.addView(UI.divider(ctx))
                card.addView(UI.row(ctx).apply {
                    setPadding(0, ctx.dp(10), 0, ctx.dp(10))
                    addView(UI.text(ctx, label, 14f, R.color.text2), UI.weight())
                    addView(UI.title(ctx, value, 16f))
                }, UI.matchWidth())
            }
            sheet.addView(card, UI.matchWidth())
        }

        val scroll = android.widget.ScrollView(ctx).apply { addView(sheet) }
        dialog.setContentView(scroll)
        dialog.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setGravity(Gravity.BOTTOM)
            setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            setWindowAnimations(android.R.style.Animation_InputMethod)
        }
        dialog.show()
    }

    /** A track from 0 to the scale, with min-max shaded, the class average and the student marked. */
    private fun scale(ctx: MainActivity, g: Grade, style: Subjects.Style): View {
        val outOf = g.outOf ?: 20.0
        fun frac(v: Double) = (v / outOf).coerceIn(0.0, 1.0)
        val frame = FrameLayout(ctx)
        val trackHeight = ctx.dp(12)
        val markerTop = ctx.dp(22)
        frame.addView(View(ctx).apply { background = UI.rounded(ctx.getColor(R.color.surface2), ctx.dp(6).toFloat()) },
            FrameLayout.LayoutParams(-1, trackHeight).apply { topMargin = markerTop })
        val range = View(ctx).apply { background = UI.rounded(Subjects.tint(ctx, style.family), ctx.dp(6).toFloat()) }
        frame.addView(range, FrameLayout.LayoutParams(0, trackHeight).apply { topMargin = markerTop })
        val avgMark = View(ctx).apply { background = UI.rounded(ctx.getColor(R.color.text2), ctx.dp(2).toFloat()) }
        frame.addView(avgMark, FrameLayout.LayoutParams(ctx.dp(4), trackHeight + ctx.dp(8)).apply { topMargin = markerTop - ctx.dp(4) })
        val you = UI.text(ctx, "toi", 11f, weight = 900).apply {
            gravity = Gravity.CENTER
            setTextColor(Subjects.onSolid(style.family))
            background = UI.rounded(Subjects.solid(style.family), ctx.dp(9).toFloat(), ctx.dp(2), Subjects.edge(ctx, style.family))
            setPadding(ctx.dp(6), 0, ctx.dp(6), ctx.dp(1))
        }
        frame.addView(you, FrameLayout.LayoutParams(-2, -2))
        frame.post {
            val w = frame.width
            val lo = frac(g.min ?: g.points!!)
            val hi = frac(g.max ?: g.points!!)
            range.layoutParams = (range.layoutParams as FrameLayout.LayoutParams).apply { leftMargin = (w * lo).toInt(); width = ((hi - lo) * w).toInt().coerceAtLeast(ctx.dp(12)) }
            g.classAverage?.let { avg -> avgMark.layoutParams = (avgMark.layoutParams as FrameLayout.LayoutParams).apply { leftMargin = (w * frac(avg)).toInt() - ctx.dp(2) } }
                ?: run { avgMark.visibility = View.GONE }
            val x = (w * frac(g.points!!)).toInt() - you.width / 2
            you.layoutParams = (you.layoutParams as FrameLayout.LayoutParams).apply { leftMargin = x.coerceIn(0, w - you.width) }
            frame.requestLayout()
            if (android.animation.ValueAnimator.areAnimatorsEnabled()) {
                you.translationX = -x.toFloat()
                you.animate().translationX(0f).setDuration(650).setInterpolator(android.view.animation.DecelerateInterpolator(2f)).start()
            }
        }
        frame.layoutParams = LinearLayout.LayoutParams(-1, markerTop + trackHeight + ctx.dp(6))
        return frame
    }

    private fun statsRow(ctx: MainActivity, g: Grade): View {
        val row = UI.row(ctx)
        for ((label, value) in listOf("Plus basse" to g.min, "Classe" to g.classAverage, "Plus haute" to g.max)) {
            val col = UI.column(ctx).apply { gravity = Gravity.CENTER_HORIZONTAL }
            col.addView(UI.title(ctx, UI.number(value), 18f).apply { gravity = Gravity.CENTER }, UI.matchWidth())
            col.addView(UI.text(ctx, label, 12f, R.color.text2).apply { gravity = Gravity.CENTER }, UI.matchWidth())
            row.addView(col, UI.weight())
        }
        return row
    }
}
