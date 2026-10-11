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
import android.widget.ScrollView

/** The practical switches: lock screen wallpaper, reminders, vibrations. */
object SettingsSheet {

    /** [set] receives the wanted state and a callback to redraw the switch once it is applied. */
    class Option(val icon: Int, val title: String, val description: String, val isOn: () -> Boolean, val set: (Boolean, () -> Unit) -> Unit)

    fun show(activity: MainActivity, options: List<Option>, bottomInset: Int) {
        val ctx = activity
        val dialog = Dialog(ctx)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        val sheet = UI.column(ctx).apply {
            background = UI.rounded(ctx.getColor(R.color.bg), ctx.dp(30).toFloat())
            setPadding(ctx.dp(18), ctx.dp(12), ctx.dp(18), ctx.dp(22) + bottomInset)
        }
        sheet.addView(View(ctx).apply { background = UI.rounded(ctx.getColor(R.color.text3), ctx.dp(3).toFloat()) },
            LinearLayout.LayoutParams(ctx.dp(40), ctx.dp(5)).apply { gravity = Gravity.CENTER_HORIZONTAL; bottomMargin = ctx.dp(14) })
        sheet.addView(UI.title(ctx, "Réglages", 24f))
        sheet.addView(UI.text(ctx, "Pour voir ta salle encore plus vite.", 14f, R.color.text2).apply { setPadding(0, ctx.dp(4), 0, ctx.dp(8)) })

        val card = UI.card(ctx, padding = 6)
        options.forEachIndexed { i, option ->
            if (i > 0) card.addView(UI.divider(ctx).apply { (layoutParams as LinearLayout.LayoutParams).apply { marginStart = ctx.dp(12); marginEnd = ctx.dp(12) } })
            card.addView(row(activity, option), UI.matchWidth())
        }
        sheet.addView(card, UI.matchWidth().apply { topMargin = ctx.dp(8) })

        dialog.setContentView(ScrollView(ctx).apply { addView(sheet) })
        dialog.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setGravity(Gravity.BOTTOM)
            setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            setWindowAnimations(android.R.style.Animation_InputMethod)
        }
        dialog.show()
    }

    private fun row(ctx: MainActivity, option: Option): View {
        val row = UI.row(ctx).apply { setPadding(ctx.dp(12), ctx.dp(14), ctx.dp(12), ctx.dp(14)) }
        val tile = FrameLayout(ctx).apply {
            background = UI.rounded(ctx.getColor(R.color.accent_container), ctx.dp(12).toFloat())
            addView(UI.icon(ctx, option.icon, R.color.on_accent_container, 20).apply {
                layoutParams = FrameLayout.LayoutParams(ctx.dp(20), ctx.dp(20), Gravity.CENTER)
            })
        }
        row.addView(tile, LinearLayout.LayoutParams(ctx.dp(40), ctx.dp(40)))
        row.addView(UI.hgap(ctx, 12))
        val col = UI.column(ctx)
        col.addView(UI.title(ctx, option.title, 16f))
        col.addView(UI.text(ctx, option.description, 13f, R.color.text2).apply { setPadding(0, ctx.dp(3), 0, 0) })
        row.addView(col, UI.weight())
        row.addView(UI.hgap(ctx, 10))
        val switch = toggle(ctx, option.isOn())
        row.addView(switch)
        row.setOnClickListener {
            Motion.tick(row)
            // The option may ask first (preview, permission): redraw from its real state.
            option.set(!option.isOn()) { redraw(ctx, switch, option.isOn()) }
        }
        return row
    }

    /** A chunky sticker switch. */
    private fun toggle(ctx: MainActivity, on: Boolean): FrameLayout {
        val track = FrameLayout(ctx)
        val knob = View(ctx)
        track.addView(knob, FrameLayout.LayoutParams(ctx.dp(22), ctx.dp(22), Gravity.CENTER_VERTICAL))
        track.layoutParams = LinearLayout.LayoutParams(ctx.dp(52), ctx.dp(32))
        redraw(ctx, track, on, animate = false)
        return track
    }

    private fun redraw(ctx: MainActivity, track: FrameLayout, on: Boolean, animate: Boolean = true) {
        track.background = UI.rounded(ctx.getColor(if (on) R.color.accent else R.color.surface2), ctx.dp(16).toFloat(), ctx.dp(2), ctx.getColor(if (on) R.color.accent_edge else R.color.edge))
        val knob = track.getChildAt(0)
        knob.background = UI.rounded(Color.WHITE, ctx.dp(11).toFloat(), ctx.dp(2), ctx.getColor(if (on) R.color.accent_edge else R.color.edge))
        val x = if (on) ctx.dp(25).toFloat() else ctx.dp(5).toFloat()
        if (animate && android.animation.ValueAnimator.areAnimatorsEnabled()) {
            knob.animate().translationX(x).setDuration(180).setInterpolator(android.view.animation.OvershootInterpolator(2f)).start()
        } else knob.translationX = x
    }
}
