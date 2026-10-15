package fr.sallio.app

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.view.animation.LinearInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.FrameLayout
import android.widget.LinearLayout
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

/** The first minutes in the app: the animated welcome and the "it's ready" celebration. */
object Welcome {

    private fun animations() = ValueAnimator.areAnimatorsEnabled()

    private val FLOATING = listOf(
        Subjects.CORAL to R.drawable.ic_maths,
        Subjects.SKY to R.drawable.ic_flask,
        Subjects.PINK to R.drawable.ic_language,
        Subjects.GREEN to R.drawable.ic_leaf,
        Subjects.AMBER to R.drawable.ic_world,
        Subjects.VIOLET to R.drawable.ic_book,
        Subjects.TEAL to R.drawable.ic_code,
        Subjects.ORANGE to R.drawable.ic_run,
    )

    // Relative positions (x, y in 0..1) and sizes of the floating tiles around the logo.
    private val SPOTS = listOf(
        Triple(0.06f, 0.10f, 52), Triple(0.80f, 0.04f, 46), Triple(0.30f, 0.00f, 38), Triple(0.88f, 0.52f, 54),
        Triple(0.02f, 0.62f, 44), Triple(0.74f, 0.90f, 40), Triple(0.18f, 0.86f, 36), Triple(0.56f, 0.02f, 42),
    )

    /**
     * The top of the welcome screen: subject tiles floating around the Sallio
     * door. [animate] is false when the screen is redrawn (search results...).
     */
    fun hero(ctx: Context, animate: Boolean): View {
        val frame = FrameLayout(ctx).apply { clipChildren = false; clipToPadding = false }
        val height = ctx.dp(230)
        frame.layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, height)
        val width = ctx.resources.displayMetrics.widthPixels - ctx.dp(44)

        SPOTS.forEachIndexed { i, (fx, fy, size) ->
            val (family, icon) = FLOATING[i % FLOATING.size]
            val tile = FrameLayout(ctx).apply {
                background = UI.sticker(ctx, Subjects.tint(ctx, family), if (Subjects.isDark(ctx)) ctx.getColor(R.color.edge) else (family.edge.toInt() and 0x00FFFFFF) or 0x55000000, size * 3 / 10, 3)
                setPadding(0, 0, 0, ctx.dp(3))
                addView(UI.icon(ctx, icon, Subjects.ink(ctx, family), size / 2, tintIsColor = true).apply {
                    layoutParams = FrameLayout.LayoutParams(ctx.dp(size / 2), ctx.dp(size / 2), Gravity.CENTER)
                })
                rotation = listOf(-12f, 8f, -6f, 10f, -9f, 7f, -4f, 12f)[i]
            }
            frame.addView(tile, FrameLayout.LayoutParams(ctx.dp(size), ctx.dp(size + 3)).apply {
                leftMargin = (fx * (width - ctx.dp(size))).toInt()
                topMargin = (fy * (height - ctx.dp(size))).toInt()
            })
            if (animations()) {
                // Bob forever, each tile on its own rhythm.
                ValueAnimator.ofFloat(0f, (2 * Math.PI).toFloat()).apply {
                    duration = 3200L + i * 370L
                    repeatCount = ValueAnimator.INFINITE
                    interpolator = LinearInterpolator()
                    val phase = i * 0.9f
                    val amp = ctx.dp(6).toFloat()
                    addUpdateListener { a ->
                        val t = a.animatedValue as Float + phase
                        tile.translationY = sin(t) * amp
                        tile.translationX = cos(t * 0.7f) * amp * 0.5f
                    }
                    start()
                }
                if (animate) {
                    tile.alpha = 0f
                    tile.scaleX = 0.4f
                    tile.scaleY = 0.4f
                    tile.animate().alpha(1f).scaleX(1f).scaleY(1f)
                        .setStartDelay(250L + i * 60L).setDuration(450).setInterpolator(OvershootInterpolator(2f)).start()
                }
            }
        }

        // The logo, centered: the door plate on the launcher icon's own yellow.
        val badge = FrameLayout(ctx).apply {
            background = UI.sticker(ctx, ctx.getColor(R.color.sun), ctx.getColor(R.color.sun_edge), 32, 5)
            setPadding(0, 0, 0, ctx.dp(5))
            addView(android.widget.ImageView(ctx).apply { setImageResource(R.drawable.ic_launcher_fg) }, FrameLayout.LayoutParams(-1, -1))
        }
        frame.addView(badge, FrameLayout.LayoutParams(ctx.dp(108), ctx.dp(113), Gravity.CENTER))
        if (animate && animations()) {
            badge.scaleX = 0f
            badge.scaleY = 0f
            badge.animate().scaleX(1f).scaleY(1f).setStartDelay(80).setDuration(520).setInterpolator(OvershootInterpolator(3f))
                .withEndAction {
                    // A little "hello" wiggle once it has landed.
                    ValueAnimator.ofFloat(0f, 1f).apply {
                        duration = 700
                        addUpdateListener { a -> badge.rotation = sin((a.animatedValue as Float) * 3 * Math.PI).toFloat() * 9f * (1f - a.animatedFraction) }
                        start()
                    }
                }.start()
        }
        return frame
    }

    /** Lets the welcome texts and card rise in, after the hero. */
    fun riseIn(views: List<View>, startDelay: Long = 450) {
        if (!animations()) return
        views.forEachIndexed { i, v ->
            v.alpha = 0f
            v.translationY = v.dp(24).toFloat()
            v.animate().alpha(1f).translationY(0f).setStartDelay(startDelay + i * 90L).setDuration(480)
                .setInterpolator(DecelerateInterpolator(2.2f)).start()
        }
    }

    // ---- Celebration ---------------------------------------------------------------

    /** Full screen "it's ready" moment after the very first login. */
    fun celebrate(activity: MainActivity, s: Snapshot, root: FrameLayout, topInset: Int, bottomInset: Int, onGone: () -> Unit, onDone: () -> Unit): View {
        val ctx = activity
        val overlay = FrameLayout(ctx).apply {
            setBackgroundColor(ctx.getColor(R.color.bg))
            isClickable = true
        }
        val confetti = ConfettiView(ctx)
        overlay.addView(confetti, FrameLayout.LayoutParams(-1, -1))

        val col = UI.column(ctx).apply {
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(ctx.dp(24), topInset + ctx.dp(40), ctx.dp(24), bottomInset + ctx.dp(24))
        }
        val first = MainActivity.firstName(s.account.studentName)
        val wave = UI.title(ctx, "👋", 64f).apply { gravity = Gravity.CENTER }
        col.addView(wave, UI.matchWidth())
        val title = UI.title(ctx, if (first.isNotEmpty()) "Bienvenue, $first !" else "Bienvenue !", 32f).apply { gravity = Gravity.CENTER; setPadding(0, ctx.dp(10), 0, 0) }
        col.addView(title, UI.matchWidth())
        val subtitle = UI.text(ctx, "Tout est prêt. Ton emploi du temps, tes devoirs et tes notes sont sur ton téléphone.", 16f, R.color.text2).apply { gravity = Gravity.CENTER; setPadding(0, ctx.dp(8), 0, ctx.dp(22)) }
        col.addView(subtitle, UI.matchWidth())

        // The next room, as a teaser of the home screen.
        val focus = Agenda.view(s.lessons, s.now).focus
        val card: View = if (focus != null) {
            val style = Subjects.style(focus.subject)
            val ink = Subjects.onSolid(style.family)
            UI.column(ctx).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                background = UI.sticker(ctx, Subjects.solid(style.family), Subjects.edge(ctx, style.family), 26, 5)
                UI.stickerPadding(this, 18, 16, 5)
                addView(UI.text(ctx, "TA PROCHAINE SALLE · ${Agenda.when_(focus, s.now).uppercase()}", 12f, weight = 900).apply { setTextColor(ink); alpha = 0.85f; gravity = Gravity.CENTER }, UI.matchWidth())
                addView(UI.title(ctx, if (focus.rooms.isNotEmpty()) Agenda.room(focus) else Agenda.pretty(focus.subject), 54f).apply { setTextColor(ink); gravity = Gravity.CENTER; setPadding(0, ctx.dp(6), 0, ctx.dp(2)) }, UI.matchWidth())
                addView(UI.text(ctx, Agenda.pretty(focus.subject), 15f, weight = 800).apply { setTextColor(ink); gravity = Gravity.CENTER }, UI.matchWidth())
            }
        } else UI.card(ctx).apply {
            addView(UI.text(ctx, "Ton emploi du temps arrive dans un instant.", 15f, R.color.text2, 800).apply { gravity = Gravity.CENTER }, UI.matchWidth())
        }
        col.addView(card, UI.matchWidth())
        col.addView(View(ctx), LinearLayout.LayoutParams(1, 0, 1f))

        // Three short steps (Oscar, 2026-10-15): welcome, the notifications worth having, the
        // fastest ways in. Each can be skipped; the dots say where you are.
        val pages = FrameLayout(ctx)
        overlay.addView(pages, FrameLayout.LayoutParams(-1, -1))
        pages.addView(col, FrameLayout.LayoutParams(-1, -1))
        var step = 0
        fun leave() {
            if (overlay.tag != null) return
            overlay.tag = "leaving"
            onDone()
            overlay.animate().alpha(0f).setDuration(320).withEndAction {
                confetti.stop()
                (overlay.parent as? ViewGroup)?.removeView(overlay)
                onGone()
            }.start()
        }
        lateinit var show: (Int) -> Unit
        fun footer(page: LinearLayout, index: Int, primary: String, onPrimary: () -> Unit, secondary: String?, onSecondary: () -> Unit) {
            page.addView(dots(ctx, index, 3), UI.matchWidth().apply { bottomMargin = ctx.dp(16) })
            secondary?.let { page.addView(UI.ghostButton(ctx, it) { onSecondary() }, UI.matchWidth().apply { bottomMargin = ctx.dp(10) }) }
            page.addView(UI.button(ctx, primary) { onPrimary() }, UI.matchWidth())
        }
        footer(col, 0, "Continuer", { show(1) }, null) {}

        show = { next ->
            val old = pages.getChildAt(0)
            val page = when (next) {
                1 -> notificationsPage(activity, topInset, bottomInset).also { p ->
                    footer(p, 1, "Activer", {
                        activity.enableEverySchoolNotification { show(2) }
                    }, "Plus tard") { show(2) }
                }
                else -> fasterPage(activity, topInset, bottomInset).also { p ->
                    footer(p, 2, "C'est parti", { leave() }, null) {}
                }
            }
            step = next
            pages.addView(page, FrameLayout.LayoutParams(-1, -1))
            if (animations()) {
                old.animate().alpha(0f).translationX(-ctx.dp(40).toFloat()).setDuration(220).withEndAction { pages.removeView(old) }.start()
                page.alpha = 0f
                page.translationX = ctx.dp(40).toFloat()
                page.animate().alpha(1f).translationX(0f).setStartDelay(120).setDuration(320).setInterpolator(DecelerateInterpolator(2f)).start()
            } else pages.removeView(old)
        }
        root.addView(overlay, FrameLayout.LayoutParams(-1, -1))

        // The login screen stays quiet: this is the one vibration of a first login.
        Motion.tap(root)
        if (animations()) {
            wave.scaleX = 0f; wave.scaleY = 0f
            wave.animate().scaleX(1f).scaleY(1f).setDuration(500).setInterpolator(OvershootInterpolator(3f)).withEndAction {
                ValueAnimator.ofFloat(0f, 1f).apply {
                    duration = 900
                    wave.pivotX = wave.width * 0.7f
                    wave.pivotY = wave.height * 0.9f
                    addUpdateListener { a -> wave.rotation = sin((a.animatedValue as Float) * 4 * Math.PI).toFloat() * 16f * (1f - a.animatedFraction) }
                    start()
                }
            }.start()
            riseIn(listOf(title, subtitle), 200)
            card.scaleX = 0.6f; card.scaleY = 0.6f; card.alpha = 0f
            card.animate().scaleX(1f).scaleY(1f).alpha(1f).setStartDelay(650).setDuration(520).setInterpolator(OvershootInterpolator(2.2f)).start()
            confetti.post { confetti.burst() }
        }
        return overlay
    }

    /** "● ━ ●": which of the [count] steps is on screen. */
    private fun dots(ctx: Context, index: Int, count: Int) = UI.row(ctx).apply {
        gravity = Gravity.CENTER
        for (i in 0 until count) addView(View(ctx).apply {
            background = UI.rounded(ctx.getColor(if (i == index) R.color.accent_ink else R.color.edge), ctx.dp(4).toFloat())
        }, LinearLayout.LayoutParams(ctx.dp(if (i == index) 18 else 8), ctx.dp(8)).apply { marginStart = ctx.dp(3); marginEnd = ctx.dp(3) })
    }

    private fun page(ctx: Context, topInset: Int, bottomInset: Int, emoji: String, title: String, text: String) = UI.column(ctx).apply {
        gravity = Gravity.CENTER_HORIZONTAL
        setPadding(ctx.dp(24), topInset + ctx.dp(40), ctx.dp(24), bottomInset + ctx.dp(24))
        addView(UI.title(ctx, emoji, 56f).apply { gravity = Gravity.CENTER }, UI.matchWidth())
        addView(UI.title(ctx, title, 28f).apply { gravity = Gravity.CENTER; setPadding(0, ctx.dp(10), 0, 0) }, UI.matchWidth())
        addView(UI.text(ctx, text, 16f, R.color.text2).apply { gravity = Gravity.CENTER; setPadding(0, ctx.dp(8), 0, ctx.dp(20)) }, UI.matchWidth())
    }

    /** One line of a step: an icon on its tile, a title and what it does. */
    private fun feature(ctx: Context, icon: Int, title: String, text: String) = UI.row(ctx).apply {
        gravity = Gravity.CENTER_VERTICAL
        setPadding(0, ctx.dp(8), 0, ctx.dp(8))
        addView(FrameLayout(ctx).apply {
            background = UI.rounded(ctx.getColor(R.color.accent_container), ctx.dp(12).toFloat())
            addView(UI.icon(ctx, icon, R.color.on_accent_container, 20).apply {
                layoutParams = FrameLayout.LayoutParams(ctx.dp(20), ctx.dp(20), Gravity.CENTER)
            })
        }, LinearLayout.LayoutParams(ctx.dp(42), ctx.dp(42)))
        addView(UI.hgap(ctx, 14))
        addView(UI.column(ctx).apply {
            addView(UI.title(ctx, title, 16f))
            addView(UI.text(ctx, text, 13f, R.color.text2).apply { setPadding(0, ctx.dp(2), 0, 0) })
        }, UI.weight())
    }

    private fun notificationsPage(activity: MainActivity, topInset: Int, bottomInset: Int): LinearLayout {
        val ctx = activity
        val page = page(ctx, topInset, bottomInset, "🔔", "Ne rate rien", "Sallio te prévient seulement quand c'est utile. Tu changes tout ça quand tu veux dans Réglages.")
        val card = UI.card(ctx, padding = 14)
        card.addView(feature(ctx, R.drawable.ic_warning, "Changements", "Prof absent, cours annulé, salle changée, nouvelle note."), UI.matchWidth())
        card.addView(feature(ctx, R.drawable.ic_clock, "5 min avant chaque cours", "La salle où aller, sans ouvrir l'app."), UI.matchWidth())
        card.addView(feature(ctx, R.drawable.ic_briefcase, "Cartable la veille", "À 19 h : les matières et les devoirs pas finis."), UI.matchWidth())
        page.addView(card, UI.matchWidth())
        page.addView(View(ctx), LinearLayout.LayoutParams(1, 0, 1f))
        riseIn(listOf(card), 150)
        return page
    }

    private fun fasterPage(activity: MainActivity, topInset: Int, bottomInset: Int): LinearLayout {
        val ctx = activity
        val page = page(ctx, topInset, bottomInset, "⚡", "Encore plus vite", "Ta salle sans même ouvrir l'app.")
        val actions = UI.column(ctx)
        actions.addView(UI.ghostButton(ctx, "Poser le widget sur l'accueil") { activity.pinWidget() }, UI.matchWidth().apply { bottomMargin = ctx.dp(10) })
        if (!Canteen.get(ctx).connected()) actions.addView(UI.ghostButton(ctx, "Relier ma cantine (Turboself)") {
            CanteenUI.showLogin(activity) { activity.refreshScreen() }
        }, UI.matchWidth())
        page.addView(actions, UI.matchWidth())
        page.addView(UI.text(ctx, "Astuce : reste appuyé sur une carte de l'accueil pour poser son widget.", 13f, R.color.text3).apply {
            gravity = Gravity.CENTER
            setPadding(0, ctx.dp(16), 0, 0)
        }, UI.matchWidth())
        page.addView(View(ctx), LinearLayout.LayoutParams(1, 0, 1f))
        riseIn(listOf(actions), 150)
        return page
    }

    /** Pieces of paper in the subject colours, thrown from the top and falling with gravity. */
    private class ConfettiView(context: Context) : View(context) {
        private class Piece(var x: Float, var y: Float, var vx: Float, var vy: Float, var rot: Float, val vr: Float, val w: Float, val h: Float, val color: Int, val round: Boolean)

        private val pieces = mutableListOf<Piece>()
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val rect = RectF()
        private var animator: ValueAnimator? = null

        fun burst() {
            val colors = listOf(Subjects.CORAL, Subjects.SKY, Subjects.GREEN, Subjects.VIOLET, Subjects.AMBER, Subjects.PINK, Subjects.TEAL)
                .map { it.solid.toInt() } + context.getColor(R.color.accent) + 0xFFFFD66B.toInt()
            val d = resources.displayMetrics.density
            repeat(90) {
                val fromLeft = it % 2 == 0
                pieces += Piece(
                    x = if (fromLeft) -10f * d else width + 10f * d,
                    y = height * (0.15f + Random.nextFloat() * 0.25f),
                    vx = (if (fromLeft) 1 else -1) * (4f + Random.nextFloat() * 9f) * d,
                    vy = -(6f + Random.nextFloat() * 10f) * d,
                    rot = Random.nextFloat() * 360f,
                    vr = (Random.nextFloat() - 0.5f) * 20f,
                    w = (6f + Random.nextFloat() * 6f) * d,
                    h = (9f + Random.nextFloat() * 9f) * d,
                    color = colors[Random.nextInt(colors.size)],
                    round = Random.nextInt(4) == 0,
                )
            }
            animator = ValueAnimator.ofFloat(0f, 1f).apply {
                duration = 3200
                interpolator = LinearInterpolator()
                addUpdateListener {
                    val g = 0.42f * d
                    for (p in pieces) {
                        p.vy += g
                        p.vx *= 0.985f
                        p.x += p.vx
                        p.y += p.vy
                        p.rot += p.vr
                    }
                    alpha = if (it.animatedFraction > 0.8f) (1f - it.animatedFraction) / 0.2f else 1f
                    invalidate()
                }
                start()
            }
        }

        override fun onDraw(canvas: Canvas) {
            for (p in pieces) {
                paint.color = p.color
                canvas.save()
                canvas.rotate(p.rot, p.x, p.y)
                rect.set(p.x - p.w / 2, p.y - p.h / 2, p.x + p.w / 2, p.y + p.h / 2)
                if (p.round) canvas.drawOval(rect, paint) else canvas.drawRoundRect(rect, p.w / 4, p.w / 4, paint)
                canvas.restore()
            }
        }

        fun stop() {
            animator?.cancel()
        }
    }
}
