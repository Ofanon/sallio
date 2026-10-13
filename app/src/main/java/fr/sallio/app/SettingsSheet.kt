package fr.sallio.app

import android.graphics.Color
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout

/**
 * Everything behind the avatar: a short hub (the student, then one row per
 * topic), each topic opening its own page on top. Back closes the page and
 * returns to the hub.
 */
object SettingsSheet {

    sealed class Item(val icon: Int, val title: String, val description: String?) {
        /** A switch. [set] receives the wanted state and a callback once it is applied. */
        class Toggle(icon: Int, title: String, description: String, val isOn: () -> Boolean, val set: (Boolean, () -> Unit) -> Unit) :
            Item(icon, title, description)

        /** Opens its own page of [sections] over the hub; [summary] tells the state at a glance. */
        class Page(icon: Int, title: String, val summary: String, val subtitle: String, val sections: () -> List<Section>) :
            Item(icon, title, summary)

        /** A button; [badge] like "Ajouté" shows on the right. */
        class Action(icon: Int, title: String, description: String? = null, val badge: String? = null, val danger: Boolean = false, val closes: Boolean = true, val run: () -> Unit) :
            Item(icon, title, description)
    }

    class Section(val title: String, val items: List<Item>)

    fun show(activity: MainActivity, header: View, sections: List<Section>, footer: String, onFooter: () -> Unit, bottomInset: Int) {
        val ctx = activity
        Sheet.open(activity, bottomInset) { sheet, dialog ->
            sheet.addView(header, UI.matchWidth())
            addSections(activity, sheet, sections, bottomInset) { dialog.dismiss() }
            sheet.addView(UI.text(ctx, footer, 12f, R.color.text3).apply {
                gravity = Gravity.CENTER
                setPadding(0, ctx.dp(18), 0, 0)
                setOnClickListener { Motion.tap(this); onFooter() }
            }, UI.matchWidth())
        }
    }

    private fun addSections(activity: MainActivity, sheet: LinearLayout, sections: List<Section>, bottomInset: Int, close: () -> Unit) {
        val ctx = activity
        for (section in sections) {
            if (section.items.isEmpty()) continue
            if (section.title.isNotEmpty()) sheet.addView(UI.section(ctx, section.title))
            else sheet.addView(UI.gap(ctx, 14))
            val card = UI.card(ctx, padding = 6)
            section.items.forEachIndexed { i, item ->
                if (i > 0) card.addView(UI.divider(ctx).apply { (layoutParams as LinearLayout.LayoutParams).apply { marginStart = ctx.dp(12); marginEnd = ctx.dp(12) } })
                card.addView(row(activity, item, bottomInset, close), UI.matchWidth())
            }
            sheet.addView(card, UI.matchWidth())
        }
    }

    /** A topic's page, over the hub. Its actions that leave the settings close both. */
    private fun openPage(activity: MainActivity, page: Item.Page, bottomInset: Int, closeHub: () -> Unit) {
        Analytics.event("fiche_ouverte", "fiche" to "reglages", "page" to page.title)
        Sheet.open(activity, bottomInset) { sheet, dialog ->
            Sheet.title(sheet, page.title, page.subtitle)
            addSections(activity, sheet, page.sections(), bottomInset) { dialog.dismiss(); closeHub() }
        }
    }

    private fun row(ctx: MainActivity, item: Item, bottomInset: Int, close: () -> Unit): View {
        val row = UI.row(ctx).apply {
            setPadding(ctx.dp(12), ctx.dp(13), ctx.dp(12), ctx.dp(13))
            foreground = android.graphics.drawable.RippleDrawable(android.content.res.ColorStateList.valueOf(0x22000000), null, UI.rounded(Color.WHITE, ctx.dp(16).toFloat()))
        }
        val danger = item is Item.Action && item.danger
        val tile = if (item.icon in UI.LOGOS) UI.logo(ctx, item.icon, 40) else FrameLayout(ctx).apply {
            background = UI.rounded(ctx.getColor(if (danger) R.color.alert_bg else R.color.accent_container), ctx.dp(12).toFloat())
            addView(UI.icon(ctx, item.icon, if (danger) R.color.alert else R.color.on_accent_container, 20).apply {
                layoutParams = FrameLayout.LayoutParams(ctx.dp(20), ctx.dp(20), Gravity.CENTER)
            })
        }
        row.addView(tile, LinearLayout.LayoutParams(ctx.dp(40), ctx.dp(40)))
        row.addView(UI.hgap(ctx, 12))
        val col = UI.column(ctx)
        col.addView(UI.title(ctx, item.title, 16f, if (danger) R.color.alert else R.color.text))
        item.description?.let { col.addView(UI.text(ctx, it, 13f, R.color.text2).apply { setPadding(0, ctx.dp(3), 0, 0) }) }
        row.addView(col, UI.weight())
        row.addView(UI.hgap(ctx, 10))

        when (item) {
            is Item.Toggle -> {
                val switch = toggle(ctx, item.isOn())
                row.addView(switch)
                row.setOnClickListener {
                    val wanted = !item.isOn()
                    if (wanted) Motion.success(row) else Motion.tick(row)
                    redraw(ctx, switch, wanted)
                    // The option may ask first (preview, permission): redraw from its real state.
                    item.set(wanted) { redraw(ctx, switch, item.isOn()) }
                }
            }
            is Item.Page -> {
                row.addView(UI.text(ctx, "›", 26f, R.color.text3, 700).apply { setPadding(ctx.dp(4), 0, ctx.dp(2), ctx.dp(4)) })
                row.setOnClickListener {
                    Motion.tap(row)
                    openPage(ctx, item, bottomInset, close)
                }
            }
            is Item.Action -> {
                item.badge?.let { row.addView(UI.chip(ctx, it, ctx.getColor(R.color.success_bg), ctx.getColor(R.color.success), 11f)) }
                    ?: row.addView(UI.icon(ctx, R.drawable.ic_open, R.color.text3, 16).apply { visibility = if (item.danger) View.GONE else View.VISIBLE })
                row.setOnClickListener {
                    Motion.tap(row)
                    if (item.closes) close()
                    item.run()
                }
            }
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
