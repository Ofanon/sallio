package fr.sallio.app

import android.content.Context
import android.graphics.drawable.LayerDrawable
import android.view.Gravity
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout

/**
 * Small scenes for the welcome steps, built from the app's own pieces (stickers,
 * subject tiles, the room card) so they show what the step is about instead of
 * an emoji (Oscar, 2026-10-15). Each rises in, softly.
 */
internal object Illustrations {

    private fun animations() = android.animation.ValueAnimator.areAnimatorsEnabled()

    private fun rise(views: List<View>, start: Long) {
        if (!animations()) return
        views.forEachIndexed { i, v ->
            v.alpha = 0f
            v.translationY = v.dp(16).toFloat()
            v.animate().alpha(1f).translationY(0f).setStartDelay(start + i * 140L).setDuration(420)
                .setInterpolator(DecelerateInterpolator(2f)).start()
        }
    }

    /** The app's own icon, as on the home screen. */
    fun logo(ctx: Context): View {
        val icon = ImageView(ctx).apply {
            setImageDrawable(LayerDrawable(arrayOf(ctx.getDrawable(R.drawable.ic_launcher_bg)!!, ctx.getDrawable(R.drawable.ic_launcher_fg)!!)))
            background = UI.rounded(0, ctx.dp(28).toFloat())
            clipToOutline = true
            elevation = ctx.dp(6).toFloat()
        }
        return FrameLayout(ctx).apply {
            addView(icon, FrameLayout.LayoutParams(ctx.dp(96), ctx.dp(96), Gravity.CENTER))
            if (animations()) {
                icon.scaleX = 0.6f; icon.scaleY = 0.6f; icon.alpha = 0f
                icon.animate().scaleX(1f).scaleY(1f).alpha(1f).setDuration(520)
                    .setInterpolator(android.view.animation.OvershootInterpolator(2f)).start()
            }
        }
    }

    /** A notification of the shade, in miniature: the subject's tile, a bold line, a soft line. */
    private fun notification(ctx: Context, subject: String, title: String, text: String, time: String) = UI.row(ctx).apply {
        gravity = Gravity.CENTER_VERTICAL
        background = UI.sticker(ctx, ctx.getColor(R.color.surface), ctx.getColor(R.color.edge), 18, 3)
        UI.stickerPadding(this, 12, 10, 3)
        addView(UI.subjectTile(ctx, subject, 34))
        addView(UI.hgap(ctx, 10))
        addView(UI.column(ctx).apply {
            addView(UI.row(ctx).apply {
                addView(UI.oneLine(UI.title(ctx, title, 14f)), UI.weight())
                addView(UI.text(ctx, time, 11f, R.color.text3, 800))
            }, UI.matchWidth())
            addView(UI.oneLine(UI.text(ctx, text, 12f, R.color.text2)))
        }, UI.weight())
    }

    /** Two lessons of the student to show, the next one first; examples when none is known. */
    private fun sample(s: Snapshot): Pair<fr.sallio.pronote.Lesson?, fr.sallio.pronote.Lesson?> {
        // A lesson with a room reads best ("B204"): sport or an outing only when there is nothing else.
        val coming = s.lessons.filter { it.start > s.now && !it.canceled }.sortedBy { it.start }
        val next = coming.firstOrNull { it.rooms.isNotEmpty() } ?: coming.firstOrNull()
        val other = coming.firstOrNull { it.subject != next?.subject && it.rooms.isNotEmpty() } ?: coming.firstOrNull { it.subject != next?.subject }
        return next to other
    }

    private fun teacher(l: fr.sallio.pronote.Lesson) = l.teachers.firstOrNull()?.let { Agenda.prettyName(it) }

    /** Three notifications, as Sallio would send them to this student (Oscar, 2026-10-16). */
    fun notifications(ctx: Context, s: Snapshot): View {
        val col = UI.column(ctx)
        col.clipChildren = false
        col.clipToPadding = false
        val (next, other) = sample(s)
        val a = next?.let { l ->
            val title = if (l.rooms.isNotEmpty()) "${Agenda.room(l)} · ${Agenda.pretty(l.subject)}" else Agenda.pretty(l.subject)
            notification(ctx, l.subject, title, listOfNotNull("Dans 5 min", teacher(l)).joinToString(" · "), Agenda.time(l.start.minusMinutes(5)))
        } ?: notification(ctx, "MATHEMATIQUES", "B204 · Mathématiques", "Dans 5 min · M. Gallet", "8h25")
        val b = other?.let { l ->
            notification(ctx, l.subject, "Prof absent · ${Agenda.pretty(l.subject)}", "Cours annulé (exemple)", "7h02")
        } ?: notification(ctx, "ANGLAIS", "Prof absent · Anglais", "Cours de 10h annulé", "7h02")
        val day = Bag.nextDay(s.lessons, s.now.toLocalDate())
        val subjects = day?.let { Bag.subjects(s.lessons, it) }.orEmpty()
        val c = if (day != null && subjects.isNotEmpty()) notification(ctx, subjects.first(),
            "${Bag.title(day, s.now.toLocalDate())} : ${subjects.size} matière${if (subjects.size > 1) "s" else ""}",
            subjects.take(3).joinToString(", ") { Agenda.pretty(it) } + if (subjects.size > 3) "…" else "", "19h00")
        else notification(ctx, "HISTOIRE-GEOGRAPHIE", "Demain : 4 cours", "Histoire-géo, Anglais, Maths…", "19h00")
        col.addView(a, UI.matchWidth())
        col.addView(b, UI.matchWidth().apply { topMargin = ctx.dp(8) })
        col.addView(c, UI.matchWidth().apply { topMargin = ctx.dp(8) })
        rise(listOf(a, b, c), 150)
        return col
    }

    /** A home screen in miniature: app icons around Sallio's room widget, with the student's next room. */
    fun homeScreen(ctx: Context, s: Snapshot): View {
        val next = sample(s).first
        val phone = UI.column(ctx).apply {
            background = UI.rounded(ctx.getColor(R.color.surface2), ctx.dp(26).toFloat(), ctx.dp(3), ctx.getColor(R.color.edge))
            setPadding(ctx.dp(14), ctx.dp(16), ctx.dp(14), ctx.dp(16))
        }
        fun icons(colors: List<Int>) = UI.row(ctx).apply {
            for ((i, c) in colors.withIndex()) addView(View(ctx).apply { background = UI.rounded(c, ctx.dp(10).toFloat()) },
                LinearLayout.LayoutParams(0, ctx.dp(34), 1f).apply { if (i > 0) marginStart = ctx.dp(10) })
        }
        // Other apps, in the soft tints of the subjects.
        val tints = listOf(Subjects.SKY, Subjects.GREEN, Subjects.PINK, Subjects.VIOLET, Subjects.CORAL, Subjects.TEAL, Subjects.AMBER, Subjects.ORANGE)
            .map { Subjects.tint(ctx, it) }
        phone.addView(icons(tints.take(4)), UI.matchWidth())
        // The widget as it will look: the subject's colour, the student's next room.
        val family = next?.let { Subjects.style(it.subject).family }
        val fill = family?.let { Subjects.solid(it) } ?: ctx.getColor(R.color.accent)
        val edge = family?.let { Subjects.edge(ctx, it) } ?: ctx.getColor(R.color.accent_edge)
        val ink = family?.let { Subjects.onSolid(it) } ?: ctx.getColor(R.color.on_accent)
        val widget = UI.column(ctx).apply {
            background = UI.sticker(ctx, fill, edge, 18, 4)
            UI.stickerPadding(this, 12, 8, 4)
            addView(UI.text(ctx, next?.let { Agenda.when_(it, s.now) } ?: "Dans 3 min", 10f, weight = 900).apply { setTextColor(ink); alpha = 0.8f })
            addView(UI.oneLine(UI.title(ctx, next?.let { if (it.rooms.isNotEmpty()) Agenda.room(it) else Agenda.pretty(it.subject) } ?: "B204", 26f)).apply { setTextColor(ink) })
            addView(UI.oneLine(UI.text(ctx, next?.let { Agenda.pretty(it.subject) } ?: "Mathématiques", 11f, weight = 800)).apply { setTextColor(ink) })
        }
        phone.addView(widget, UI.matchWidth().apply { topMargin = ctx.dp(12); bottomMargin = ctx.dp(12) })
        phone.addView(icons(tints.drop(4)), UI.matchWidth())
        val frame = FrameLayout(ctx).apply {
            addView(phone, FrameLayout.LayoutParams(ctx.dp(210), -2, Gravity.CENTER_HORIZONTAL))
        }
        rise(listOf(phone), 100)
        if (animations()) {
            widget.scaleX = 0.8f; widget.scaleY = 0.8f
            widget.animate().scaleX(1f).scaleY(1f).setStartDelay(450).setDuration(420)
                .setInterpolator(android.view.animation.OvershootInterpolator(2f)).start()
        }
        return frame
    }
}
