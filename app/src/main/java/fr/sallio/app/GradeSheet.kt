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
        Analytics.event("fiche_ouverte", "fiche" to "note")
        val ctx = activity
        val fr = Locale.FRANCE
        val style = Subjects.style(g.subject)
        Sheet.open(activity, bottomInset) { sheet, _ ->

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
                card.addView(scale(activity, g, style), LinearLayout.LayoutParams(-1, ctx.dp(104)).apply { topMargin = ctx.dp(8) })
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

        }
    }

    /** A track from 0 to the scale, with min-max shaded, the class average and the student marked. */
    private fun scale(ctx: MainActivity, g: Grade, style: Subjects.Style): View =
        ClassScale(ctx, g, style).apply { layoutParams = LinearLayout.LayoutParams(-1, ctx.dp(104)) }

    /**
     * The class from 0 to the scale: grey track, the class spread (lowest to
     * highest) in the subject tint, the class average as a tick, and the
     * student as a big pin with a "Toi · 16,5" bubble, sliding in.
     */
    @android.annotation.SuppressLint("ViewConstructor")
    private class ClassScale(ctx: MainActivity, private val g: Grade, style: Subjects.Style) : View(ctx) {
        private val d = resources.displayMetrics.density
        private val outOf = g.outOf ?: 20.0
        private val solid = Subjects.solid(style.family)
        private val edge = Subjects.edge(ctx, style.family)
        private val ink = Subjects.onSolid(style.family)
        private val track = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { color = ctx.getColor(R.color.surface2) }
        private val spread = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { color = Subjects.tint(ctx, style.family) }
        private val tick = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { color = ctx.getColor(R.color.text2) }
        private val pin = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { color = solid }
        private val pinEdge = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { color = edge }
        private val pinCore = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { color = android.graphics.Color.WHITE }
        private val bubbleText = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            color = ink; textSize = 14 * d; typeface = UI.display(ctx); textAlign = android.graphics.Paint.Align.CENTER
        }
        private val small = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            color = ctx.getColor(R.color.text2); textSize = 11 * d; typeface = UI.body(ctx, 800); textAlign = android.graphics.Paint.Align.CENTER
        }
        private var progress = if (android.animation.ValueAnimator.areAnimatorsEnabled()) 0f else 1f

        init {
            if (progress == 0f) android.animation.ValueAnimator.ofFloat(0f, 1f).apply {
                duration = 800
                startDelay = 250
                interpolator = android.view.animation.OvershootInterpolator(1.3f)
                addUpdateListener { progress = it.animatedValue as Float; invalidate() }
                start()
            }
        }

        override fun onDraw(c: android.graphics.Canvas) {
            val pad = 14 * d
            val left = pad
            val right = width - pad
            val trackY = 62 * d
            val th = 12 * d
            fun x(v: Double) = (left + (right - left) * (v / outOf).coerceIn(0.0, 1.0)).toFloat()

            c.drawRoundRect(left, trackY - th / 2, right, trackY + th / 2, th / 2, th / 2, track)
            val lo = g.min ?: g.points!!
            val hi = g.max ?: g.points!!
            c.drawRoundRect(x(lo) - th / 2, trackY - th / 2, x(hi) + th / 2, trackY + th / 2, th / 2, th / 2, spread)
            g.classAverage?.let { avg ->
                val ax = x(avg)
                c.drawRoundRect(ax - 2 * d, trackY - th, ax + 2 * d, trackY + th, 2 * d, 2 * d, tick)
                c.drawText("classe", ax, trackY + th + 16 * d, small)
            }
            c.drawText("0", left, trackY + th + 16 * d, small.apply { alpha = 160 })
            c.drawText(UI.number(outOf), right, trackY + th + 16 * d, small)
            small.alpha = 255

            // The student: a pin on the track, a stem, and a bubble above.
            val px = x(g.points!! * progress.coerceAtMost(1.04f).toDouble().coerceAtMost(1.0)).coerceIn(left, right)
            val r = 11 * d
            c.drawCircle(px, trackY + 1.5f * d, r, pinEdge)
            c.drawCircle(px, trackY, r, pin)
            c.drawCircle(px, trackY, r * 0.42f, pinCore)
            val label = "Toi · ${g.value.replace('.', ',')}"
            val bw = bubbleText.measureText(label) + 20 * d
            val bh = 28 * d
            val bx = (px - bw / 2).coerceIn(0f, width - bw)
            val by = trackY - r - 10 * d - bh
            c.drawRoundRect(bx, by + 3 * d, bx + bw, by + bh + 3 * d, 10 * d, 10 * d, pinEdge)
            c.drawRoundRect(bx, by, bx + bw, by + bh, 10 * d, 10 * d, pin)
            val tri = android.graphics.Path().apply {
                moveTo(px - 7 * d, by + bh - 1)
                lineTo(px + 7 * d, by + bh - 1)
                lineTo(px, by + bh + 8 * d)
                close()
            }
            c.drawPath(tri, pin)
            c.drawText(label, bx + bw / 2, by + bh / 2 + 5 * d, bubbleText)
        }
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
