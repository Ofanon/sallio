package fr.sallio.app

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import fr.sallio.pronote.Grade
import fr.sallio.pronote.Report
import fr.sallio.pronote.SubjectAverage
import java.text.Normalizer
import java.time.format.TextStyle
import java.util.Locale

/** The "moyenne générale" statistics and the per-subject sheet. */
object GradesSheets {

    private val fr = Locale.FRANCE

    fun fold(text: String) =
        Normalizer.normalize(text, Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "").lowercase().replace(Regex("[^a-z0-9]"), "")

    /** A grade brought to /20, or null when it is not a number. */
    private fun on20(g: Grade): Double? =
        if (g.points != null && g.outOf != null && g.outOf > 0) g.points / g.outOf * 20 else null

    private fun classOn20(g: Grade): Double? =
        if (g.classAverage != null && g.outOf != null && g.outOf > 0) g.classAverage / g.outOf * 20 else null

    /**
     * One point of a curve: the student's average after [grade], given on
     * [date], and the class's average over the same tests ([classValue], null
     * until a test has a class average).
     */
    class Point(val value: Double, val date: java.time.LocalDate, val grade: Grade, val classValue: Double? = null)

    /**
     * Running weighted averages after each grade, oldest first: the curves of
     * the period. The class curve uses the class average of each test, with
     * the same coefficients, so both lines move for the same reasons.
     */
    internal fun runningAverage(grades: List<Grade>): List<Point> {
        var sum = 0.0
        var weight = 0.0
        var classSum = 0.0
        var classWeight = 0.0
        return grades.sortedBy { it.date }.mapNotNull { g ->
            val v = on20(g) ?: return@mapNotNull null
            val w = g.coefficient?.takeIf { it > 0 } ?: 1.0
            sum += v * w
            weight += w
            classOn20(g)?.let { c -> classSum += c * w; classWeight += w }
            Point(sum / weight, g.date, g, if (classWeight > 0) classSum / classWeight else null)
        }
    }

    // ---- Moyenne générale --------------------------------------------------------

    fun showOverview(activity: MainActivity, report: Report, bottomInset: Int) {
        val ctx = activity
        Sheet.open(activity, bottomInset) { sheet, _ ->
            Sheet.title(sheet, "Tes statistiques", report.periodName)

            // Overall vs class.
            val hero = UI.card(ctx, R.color.accent, R.color.accent_edge, padding = 18)
            val line = UI.row(ctx).apply { gravity = Gravity.BOTTOM }
            val big = UI.title(ctx, UI.number(report.overall), 52f, R.color.on_accent)
            line.addView(big)
            line.addView(UI.title(ctx, " /20", 18f, R.color.on_accent).apply { alpha = 0.8f; setPadding(0, 0, 0, ctx.dp(8)) })
            hero.addView(line)
            val diff = if (report.overall != null && report.classOverall != null) report.overall - report.classOverall else null
            hero.addView(UI.text(ctx, when {
                diff == null -> "Moyenne générale"
                diff > 0.05 -> "${UI.number(diff)} point${if (diff >= 2) "s" else ""} au-dessus de la classe (${UI.number(report.classOverall)})"
                diff < -0.05 -> "${UI.number(-diff)} point${if (-diff >= 2) "s" else ""} sous la classe (${UI.number(report.classOverall)})"
                else -> "Pile la moyenne de la classe"
            }, 14f, R.color.on_accent, 800).apply { alpha = 0.9f; setPadding(0, ctx.dp(4), 0, 0) })
            report.overall?.let { t -> Motion.countUp(big, t) { UI.number(it) } }
            sheet.addView(hero, UI.matchWidth().apply { topMargin = ctx.dp(12) })

            // Key numbers.
            val numbers = report.grades.mapNotNull { on20(it) }
            val above = report.subjects.count { it.student != null && it.classAverage != null && it.student >= it.classAverage }
            val withAvg = report.subjects.count { it.student != null && it.classAverage != null }
            val tiles = UI.row(ctx).apply { gravity = Gravity.TOP }
            for ((i, pair) in listOf(
                "${report.grades.size}" to "notes",
                (numbers.maxOrNull()?.let { UI.number(it) } ?: "—") to "meilleure /20",
                "$above/$withAvg" to "matières au-dessus",
            ).withIndex()) {
                tiles.addView(UI.column(ctx).apply {
                    gravity = Gravity.CENTER_HORIZONTAL
                    background = UI.sticker(ctx, ctx.getColor(R.color.surface), ctx.getColor(R.color.edge), 18, 3)
                    UI.stickerPadding(this, 4, 10, 3)
                    addView(UI.title(ctx, pair.first, 22f).apply { gravity = Gravity.CENTER }, UI.matchWidth())
                    addView(UI.text(ctx, pair.second, 11f, R.color.text2, 800).apply { gravity = Gravity.CENTER }, UI.matchWidth())
                }, UI.weight().apply { if (i > 0) marginStart = ctx.dp(8) })
            }
            sheet.addView(tiles, UI.matchWidth().apply { topMargin = ctx.dp(12) })

            // Curve.
            val curve = runningAverage(report.grades)
            if (curve.size >= 2) {
                sheet.addView(UI.section(ctx, "Évolution"))
                val card = UI.card(ctx, padding = 14)
                card.addView(LineChart(ctx, curve, report.classOverall, ctx.getColor(R.color.accent_ink)), LinearLayout.LayoutParams(-1, ctx.dp(170)))
                card.addView(legend(ctx, ctx.getColor(R.color.accent_ink), curve.any { it.classValue != null }), UI.matchWidth().apply { topMargin = ctx.dp(8) })
                card.addView(UI.text(ctx, "Touche ou glisse sur la courbe pour voir les deux moyennes à chaque note.", 12f, R.color.text2).apply { setPadding(0, ctx.dp(6), 0, 0) })
                sheet.addView(card, UI.matchWidth())
            }

            // Distribution.
            if (numbers.size >= 3) {
                sheet.addView(UI.section(ctx, "Répartition de tes notes"))
                val card = UI.card(ctx, padding = 14)
                val buckets = listOf("< 8" to (0.0..7.99), "8-10" to (8.0..9.99), "10-12" to (10.0..11.99), "12-14" to (12.0..13.99), "14-16" to (14.0..15.99), "16+" to (16.0..20.0))
                card.addView(BarChart(ctx, buckets.map { (label, range) -> label to numbers.count { it in range } }, ctx.getColor(R.color.accent_ink)), LinearLayout.LayoutParams(-1, ctx.dp(130)))
                sheet.addView(card, UI.matchWidth())
            }

            // Best and to watch.
            val ranked = report.subjects.filter { it.student != null }.sortedByDescending { it.student }
            if (ranked.size >= 2) {
                sheet.addView(UI.section(ctx, "Tes points forts"))
                sheet.addView(subjectList(activity, ranked.take(3), report, bottomInset), UI.matchWidth())
                val watch = report.subjects.filter { it.student != null && it.classAverage != null && it.student < it.classAverage }
                    .sortedBy { it.student!! - it.classAverage!! }.take(3)
                if (watch.isNotEmpty()) {
                    sheet.addView(UI.section(ctx, "À surveiller"))
                    sheet.addView(subjectList(activity, watch, report, bottomInset), UI.matchWidth())
                }
            }
            sheet.addView(UI.text(ctx, "Calculs faits par Sallio à partir de tes notes. La moyenne officielle reste celle de Pronote.", 12f, R.color.text3).apply {
                gravity = Gravity.CENTER
                setPadding(0, ctx.dp(18), 0, 0)
            }, UI.matchWidth())
        }
    }

    private fun subjectList(activity: MainActivity, subjects: List<SubjectAverage>, report: Report, bottomInset: Int): View {
        val ctx = activity
        val card = UI.card(ctx, padding = 12)
        subjects.forEachIndexed { i, sub ->
            if (i > 0) card.addView(UI.divider(ctx))
            card.addView(UI.row(ctx).apply {
                setPadding(0, ctx.dp(8), 0, ctx.dp(8))
                addView(UI.subjectTile(ctx, sub.subject, 36))
                addView(UI.hgap(ctx, 12))
                addView(UI.oneLine(UI.title(ctx, Agenda.pretty(sub.subject), 15f)), UI.weight())
                addView(UI.title(ctx, UI.number(sub.student), 17f))
                setOnClickListener { Motion.tap(this); showSubject(activity, sub, report, bottomInset) }
            }, UI.matchWidth())
        }
        return card
    }

    // ---- Une matière -------------------------------------------------------------

    fun showSubject(activity: MainActivity, sub: SubjectAverage, report: Report, bottomInset: Int) {
        val ctx = activity
        val style = Subjects.style(sub.subject)
        // Grades of sub-services ("Allemand LV2 > ecrit", "> oral") count for their subject.
        val grades = report.grades.filter { fold(it.subject) == fold(sub.subject) || fold(it.subject.substringBefore(" > ")) == fold(sub.subject) }
            .sortedByDescending { it.date }
        Sheet.open(activity, bottomInset) { sheet, _ ->
            val head = UI.row(ctx)
            head.addView(UI.subjectTile(ctx, sub.subject, 48))
            head.addView(UI.hgap(ctx, 14))
            val titles = UI.column(ctx)
            titles.addView(UI.oneLine(UI.title(ctx, Agenda.pretty(sub.subject), 21f)))
            titles.addView(UI.text(ctx, "${grades.size} note${if (grades.size > 1) "s" else ""} · ${report.periodName}", 13f, R.color.text2).apply { setPadding(0, ctx.dp(4), 0, 0) })
            head.addView(titles, UI.weight())
            sheet.addView(head, UI.matchWidth())

            // Average on the subject colour.
            val ink = Subjects.onSolid(style.family)
            val hero = UI.column(ctx).apply {
                background = UI.sticker(ctx, Subjects.solid(style.family), Subjects.edge(ctx, style.family), 24, 5)
                UI.stickerPadding(this, 18, 14, 5)
            }
            hero.addView(UI.text(ctx, "TA MOYENNE", 12f, weight = 900).apply { setTextColor(ink); alpha = 0.8f; letterSpacing = 0.06f })
            val line = UI.row(ctx).apply { gravity = Gravity.BOTTOM }
            val big = UI.title(ctx, UI.number(sub.student), 50f).apply { setTextColor(ink) }
            line.addView(big)
            line.addView(UI.title(ctx, " /20", 18f).apply { setTextColor(ink); alpha = 0.75f; setPadding(0, 0, 0, ctx.dp(8)) })
            hero.addView(line)
            sub.classAverage?.let { c ->
                val d = (sub.student ?: c) - c
                val text = when {
                    sub.student == null -> "Classe : ${UI.number(c)}"
                    d > 0.05 -> "Classe : ${UI.number(c)} · tu as ${UI.number(d)} de plus"
                    d < -0.05 -> "Classe : ${UI.number(c)} · ${UI.number(-d)} de moins"
                    else -> "Classe : ${UI.number(c)} · pile pareil"
                }
                hero.addView(UI.text(ctx, text, 14f, weight = 800).apply { setTextColor(ink); alpha = 0.9f })
            }
            sub.student?.let { t -> Motion.countUp(big, t) { UI.number(it) } }
            sheet.addView(hero, UI.matchWidth().apply { topMargin = ctx.dp(16) })

            // Curve of the subject.
            val curve = runningAverage(grades)
            if (curve.size >= 2) {
                sheet.addView(UI.section(ctx, "Évolution"))
                val card = UI.card(ctx, padding = 14)
                card.addView(LineChart(ctx, curve, sub.classAverage, Subjects.solid(style.family)), LinearLayout.LayoutParams(-1, ctx.dp(160)))
                card.addView(legend(ctx, Subjects.solid(style.family), curve.any { it.classValue != null }), UI.matchWidth().apply { topMargin = ctx.dp(8) })
                sheet.addView(card, UI.matchWidth())
            }

            // Every grade, each opening its own sheet.
            if (grades.isNotEmpty()) {
                sheet.addView(UI.section(ctx, "Tes notes"))
                val card = UI.card(ctx, padding = 12)
                grades.forEachIndexed { i, g ->
                    if (i > 0) card.addView(UI.divider(ctx))
                    card.addView(UI.row(ctx).apply {
                        setPadding(0, ctx.dp(10), 0, ctx.dp(10))
                        val col = UI.column(ctx)
                        col.addView(UI.oneLine(UI.text(ctx, g.comment ?: "Évaluation", 15f, weight = 800)))
                        val details = buildList {
                            add("${g.date.dayOfMonth} ${g.date.month.getDisplayName(TextStyle.SHORT, fr)}")
                            g.classAverage?.let { add("classe ${UI.number(it)}") }
                            g.coefficient?.takeIf { it != 1.0 }?.let { add("coef ${UI.number(it)}") }
                        }.joinToString(" · ")
                        col.addView(UI.oneLine(UI.text(ctx, details, 12f, R.color.text2)))
                        addView(col, UI.weight())
                        val good = on20(g)?.let { v -> classOn20(g)?.let { v >= it } } == true
                        addView(UI.row(ctx).apply {
                            gravity = Gravity.BOTTOM
                            background = if (good) UI.sticker(ctx, ctx.getColor(R.color.success_bg), ctx.getColor(R.color.success_edge), 12, 2)
                            else UI.sticker(ctx, ctx.getColor(R.color.surface2), ctx.getColor(R.color.edge), 12, 2)
                            setPadding(ctx.dp(10), ctx.dp(3), ctx.dp(10), ctx.dp(5))
                            addView(UI.title(ctx, g.value.replace('.', ','), 18f, if (good) R.color.success else R.color.text))
                            if (g.points != null && g.outOf != null) addView(UI.text(ctx, "/${UI.number(g.outOf)}", 11f, R.color.text2))
                        })
                        setOnClickListener { Motion.tap(this); activity.showGrade(g) }
                    }, UI.matchWidth())
                }
                sheet.addView(card, UI.matchWidth())
            }
        }
    }

    // ---- Charts ------------------------------------------------------------------

    /** "— Toi  - - La classe", under a curve. */
    private fun legend(ctx: Context, tint: Int, withClass: Boolean): View {
        val row = UI.row(ctx)
        fun key(color: Int, dashed: Boolean, label: String) {
            row.addView(object : View(ctx) {
                private val d = resources.displayMetrics.density
                private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    this.color = color; strokeWidth = (if (dashed) 2.5f else 3.5f) * d; strokeCap = Paint.Cap.ROUND
                    if (dashed) pathEffect = DashPathEffect(floatArrayOf(5 * d, 4 * d), 0f)
                }
                override fun onDraw(canvas: Canvas) = canvas.drawLine(2 * d, height / 2f, width - 2 * d, height / 2f, paint)
            }, LinearLayout.LayoutParams(ctx.dp(22), ctx.dp(12)))
            row.addView(UI.text(ctx, label, 12f, R.color.text2, 800).apply { setPadding(ctx.dp(6), 0, ctx.dp(16), 0) })
        }
        key(tint, false, "Toi")
        key(ctx.getColor(R.color.text3), true, if (withClass) "La classe" else "Moyenne de la classe")
        return row
    }

    /** Values out of 20 as a smooth line with dots; the class average dashed. */
    @SuppressLint("ViewConstructor")
    private class LineChart(context: Context, private val points: List<Point>, private val reference: Double?, private val tint: Int) : View(context) {
        private val values = points.map { it.value }
        private val d = resources.displayMetrics.density
        private val bubble = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = tint }
        private val bubbleText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Subjects.onSolidColor(tint); textSize = 13 * d; typeface = UI.display(context); textAlign = Paint.Align.CENTER
        }
        private val bubbleSmall = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Subjects.onSolidColor(tint); textSize = 10.5f * d; typeface = UI.body(context, 800); textAlign = Paint.Align.CENTER
        }
        private val guide = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = (tint and 0x00FFFFFF) or 0x66000000; strokeWidth = 1.5f * d }
        /** Point under the finger, or -1. Stays selected after the finger lifts. */
        private var selected = -1

        override fun onTouchEvent(event: android.view.MotionEvent): Boolean {
            if (values.isEmpty()) return false
            val left = 22 * d
            val right = width - 8 * d
            val i = Math.round((event.x - left) / ((right - left) / (values.size - 1).coerceAtLeast(1))).coerceIn(0, values.size - 1)
            when (event.actionMasked) {
                android.view.MotionEvent.ACTION_DOWN -> parent?.requestDisallowInterceptTouchEvent(true)
                android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL -> parent?.requestDisallowInterceptTouchEvent(false)
            }
            if (i != selected) {
                selected = i
                Motion.tick(this)
                invalidate()
            }
            return true
        }
        private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 3.5f * d; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND; color = tint }
        private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = (tint and 0x00FFFFFF) or 0x26000000 }
        private val dot = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = tint }
        private val dotIn = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = context.getColor(R.color.surface) }
        private val ref = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 2.5f * d; strokeCap = Paint.Cap.ROUND; this.color = context.getColor(R.color.text3); pathEffect = DashPathEffect(floatArrayOf(5 * d, 4 * d), 0f) }
        private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = context.getColor(R.color.text3); textSize = 10 * d; typeface = UI.body(context, 800) }
        private var progress = 0f

        init {
            if (android.animation.ValueAnimator.areAnimatorsEnabled()) {
                android.animation.ValueAnimator.ofFloat(0f, 1f).apply {
                    // Drawn by sweeping a clip from left to right: every grade is on the
                    // curve, the pen just travels at an even pace to the last one.
                    duration = 380L + 45L * values.size.coerceAtMost(20)
                    startDelay = 150
                    interpolator = android.view.animation.AccelerateDecelerateInterpolator()
                    addUpdateListener { progress = it.animatedValue as Float; invalidate() }
                    start()
                }
            } else progress = 1f
        }

        override fun onDraw(canvas: Canvas) {
            val classValues = points.mapNotNull { it.classValue }
            val lo = (minOf(values.min(), classValues.minOrNull() ?: 20.0).coerceAtMost(reference ?: 20.0) - 1.5).coerceAtLeast(0.0)
            val hi = (maxOf(values.max(), classValues.maxOrNull() ?: 0.0).coerceAtLeast(reference ?: 0.0) + 1.5).coerceAtMost(20.0)
            val left = 22 * d
            val top = 8 * d
            val bottom = height - 8 * d
            val right = width - 8 * d
            fun x(i: Int) = left + (right - left) * i / (values.size - 1).coerceAtLeast(1)
            fun y(v: Double) = (bottom - (bottom - top) * ((v - lo) / (hi - lo).coerceAtLeast(0.1))).toFloat()

            if (selected >= 0) canvas.drawLine(x(selected), top, x(selected), bottom, guide)
            canvas.drawText(UI.number(hi).substringBefore(','), 0f, top + 8 * d, label)
            canvas.drawText(UI.number(lo).substringBefore(','), 0f, bottom, label)
            // The class: its own curve when the tests carry a class average, else today's flat line.
            if (classValues.size >= 2) {
                val classPath = Path()
                var started = false
                var prev = -1
                for (i in points.indices) {
                    val c = points[i].classValue ?: continue
                    if (!started) { classPath.moveTo(x(i), y(c)); started = true }
                    else {
                        val cx = (x(prev) + x(i)) / 2
                        classPath.cubicTo(cx, y(points[prev].classValue!!), cx, y(c), x(i), y(c))
                    }
                    prev = i
                }
                canvas.save()
                canvas.clipRect(0f, 0f, left + (right - left + 8 * d) * progress, height.toFloat())
                canvas.drawPath(classPath, ref)
                canvas.restore()
            } else reference?.let { r -> canvas.drawLine(left, y(r), right, y(r), ref) }

            val path = Path()
            val area = Path()
            for (i in values.indices) {
                val px = x(i)
                val py = y(values[i])
                if (i == 0) { path.moveTo(px, py); area.moveTo(px, bottom); area.lineTo(px, py) }
                else {
                    val cx = (x(i - 1) + px) / 2
                    path.cubicTo(cx, y(values[i - 1]), cx, py, px, py)
                    area.cubicTo(cx, y(values[i - 1]), cx, py, px, py)
                }
            }
            area.lineTo(x(values.size - 1), bottom)
            area.close()
            // The pen's position; a little margin so the last dot is whole at the end.
            val pen = left + (right - left + 8 * d) * progress
            canvas.save()
            canvas.clipRect(0f, 0f, pen, height.toFloat())
            canvas.drawPath(area, fill)
            canvas.drawPath(path, line)
            canvas.restore()
            for (i in values.indices) {
                // Each dot pops as the pen reaches it.
                val grow = ((pen - x(i)) / (10 * d) + 0.5f).coerceIn(0f, 1f)
                if (grow <= 0f) continue
                val r = (if (i == selected) 7.5f else 4.5f) * grow
                canvas.drawCircle(x(i), y(values[i]), r * d, dot)
                canvas.drawCircle(x(i), y(values[i]), r * 0.45f * d, dotIn)
            }

            // Bubble of the selected point: the average then, the grade and its date.
            if (selected in points.indices) {
                val pt = points[selected]
                val main = "Toi ${UI.number(pt.value)}" + (pt.classValue?.let { " · classe ${UI.number(it)}" } ?: "")
                val sub = "${pt.date.dayOfMonth}/${pt.date.monthValue} · ${pt.grade.value.replace('.', ',')}${pt.grade.outOf?.let { "/" + UI.number(it) } ?: ""}"
                val w = maxOf(bubbleText.measureText(main), bubbleSmall.measureText(sub)) + 20 * d
                val h = 40 * d
                val cx = x(selected).coerceIn(w / 2, width - w / 2)
                val py = y(values[selected])
                val above = py - h - 14 * d > 0
                val by = if (above) py - h - 12 * d else py + 12 * d
                canvas.drawRoundRect(cx - w / 2, by, cx + w / 2, by + h, 12 * d, 12 * d, bubble)
                canvas.drawText(main, cx, by + 17 * d, bubbleText)
                canvas.drawText(sub, cx, by + 32 * d, bubbleSmall)
            }
        }
    }

    /** Count per range, as rounded bars growing from the bottom. */
    @SuppressLint("ViewConstructor")
    private class BarChart(context: Context, private val bars: List<Pair<String, Int>>, private val tint: Int) : View(context) {
        private val d = resources.displayMetrics.density
        private val bar = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = tint }
        private val empty = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = context.getColor(R.color.surface2) }
        private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = context.getColor(R.color.text2); textSize = 11 * d; textAlign = Paint.Align.CENTER; typeface = UI.body(context, 800) }
        private val count = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = context.getColor(R.color.text); textSize = 13 * d; textAlign = Paint.Align.CENTER; typeface = UI.display(context) }
        private var progress = if (android.animation.ValueAnimator.areAnimatorsEnabled()) 0f else 1f

        init {
            if (progress == 0f) android.animation.ValueAnimator.ofFloat(0f, 1f).apply {
                duration = 600
                startDelay = 200
                interpolator = android.view.animation.OvershootInterpolator(1.4f)
                addUpdateListener { progress = it.animatedValue as Float; invalidate() }
                start()
            }
        }

        override fun onDraw(canvas: Canvas) {
            val max = bars.maxOf { it.second }.coerceAtLeast(1)
            val slot = width / bars.size.toFloat()
            val barW = slot * 0.56f
            val bottom = height - 18 * d
            val top = 18 * d
            bars.forEachIndexed { i, (name, n) ->
                val cx = slot * i + slot / 2
                val h = (bottom - top) * n / max * progress
                val r = 8 * d
                canvas.drawRoundRect(cx - barW / 2, top, cx + barW / 2, bottom, r, r, empty)
                if (n > 0) canvas.drawRoundRect(cx - barW / 2, bottom - h.coerceAtLeast(2 * r), cx + barW / 2, bottom, r, r, bar)
                canvas.drawText(name, cx, height - 3 * d, label)
                if (n > 0) canvas.drawText(n.toString(), cx, (bottom - h - 5 * d).coerceAtLeast(13 * d), count)
            }
        }
    }
}
