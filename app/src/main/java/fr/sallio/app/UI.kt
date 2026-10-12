package fr.sallio.app

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.graphics.drawable.RippleDrawable
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView

/**
 * The "pastel stickers" look: soft colours, rounded type, and cards drawn
 * like stickers (an outline plus a solid shadow offset downwards).
 * Plain platform views and drawables, no library.
 */
internal fun Context.dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

internal fun View.dp(value: Int): Int = context.dp(value)

/** Edge-to-edge is forced from Android 15: keep content out of the bars. */
internal fun View.fitsSystemWindowsCompat(top: Boolean = true, bottom: Boolean = true) {
    val l = paddingLeft
    val t = paddingTop
    val r = paddingRight
    val b = paddingBottom
    setOnApplyWindowInsetsListener { v, insets ->
        val bars = if (android.os.Build.VERSION.SDK_INT >= 30) {
            insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.ime())
                .let { intArrayOf(it.left, it.top, it.right, it.bottom) }
        } else {
            @Suppress("DEPRECATION")
            intArrayOf(insets.systemWindowInsetLeft, insets.systemWindowInsetTop, insets.systemWindowInsetRight, insets.systemWindowInsetBottom)
        }
        v.setPadding(l + bars[0], t + if (top) bars[1] else 0, r + bars[2], b + if (bottom) bars[3] else 0)
        insets
    }
}

internal object UI {

    // ---- Type ----------------------------------------------------------------------

    private val fonts = HashMap<String, Typeface>()

    /** Variable fonts shipped in assets (SIL OFL), instanced at the wanted weight. */
    private fun font(context: Context, file: String, weight: Int): Typeface = fonts.getOrPut("$file$weight") {
        runCatching {
            Typeface.Builder(context.assets, "fonts/$file").setFontVariationSettings("'wght' $weight").build()
        }.getOrNull() ?: Typeface.create("sans-serif", if (weight >= 600) Typeface.BOLD else Typeface.NORMAL)
    }

    /** Fredoka: titles, rooms, numbers. */
    fun display(context: Context, weight: Int = 600) = font(context, "Fredoka.ttf", weight)

    /** Nunito: everything else. */
    fun body(context: Context, weight: Int = 700) = font(context, "Nunito.ttf", weight)

    // ---- Shapes --------------------------------------------------------------------

    fun rounded(color: Int, radius: Float, stroke: Int = 0, strokeColor: Int = 0) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = radius
        if (stroke > 0) setStroke(stroke, strokeColor)
    }

    /**
     * A sticker: an outlined face sitting on a solid shadow [depth] lower.
     * Views using it need [depth] extra bottom padding (see [stickerPadding]).
     */
    fun sticker(context: Context, fill: Int, edge: Int, radiusDp: Int = 22, depthDp: Int = 4, pressable: Boolean = false): Drawable {
        val r = context.dp(radiusDp).toFloat()
        val depth = context.dp(depthDp)
        val shadow = rounded(edge, r)
        val face = rounded(fill, r, context.dp(2), edge)
        val faceLayer: Drawable = if (pressable) {
            RippleDrawable(ColorStateList.valueOf(0x22000000), face, rounded(Color.WHITE, r))
        } else face
        return LayerDrawable(arrayOf(shadow, faceLayer)).apply {
            setLayerInset(0, 0, depth, 0, 0)
            setLayerInset(1, 0, 0, 0, depth)
        }
    }

    fun stickerPadding(view: View, h: Int, v: Int, depthDp: Int = 4) {
        view.setPadding(view.dp(h), view.dp(v), view.dp(h), view.dp(v + depthDp))
    }

    // ---- Text ----------------------------------------------------------------------

    fun text(context: Context, value: CharSequence, size: Float, color: Int = R.color.text, weight: Int = 700) =
        TextView(context).apply {
            text = value
            textSize = size
            setTextColor(context.getColor(color))
            typeface = body(context, weight)
        }

    fun title(context: Context, value: CharSequence, size: Float, color: Int = R.color.text) =
        TextView(context).apply {
            text = value
            textSize = size
            setTextColor(context.getColor(color))
            typeface = display(context)
            includeFontPadding = false
        }

    /** Web addresses and e-mails of a text become tappable, in the accent colour. */
    fun linkify(view: TextView) {
        android.text.util.Linkify.addLinks(view, android.text.util.Linkify.WEB_URLS or android.text.util.Linkify.EMAIL_ADDRESSES)
        view.setLinkTextColor(view.context.getColor(R.color.accent))
        view.movementMethod = android.text.method.LinkMovementMethod.getInstance()
    }

    fun oneLine(view: TextView) = view.apply {
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.END
    }

    /** Section heading ("À savoir", "Pour demain"). */
    fun section(context: Context, value: String) = title(context, value, 18f).apply {
        setPadding(context.dp(2), context.dp(22), 0, context.dp(10))
    }

    // ---- Controls ------------------------------------------------------------------

    fun button(context: Context, label: String, icon: Int? = null, onClick: () -> Unit): View = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER
        background = sticker(context, context.getColor(R.color.accent), context.getColor(R.color.accent_edge), 18, 4, pressable = true)
        stickerPadding(this, 18, 13)
        icon?.let {
            addView(icon(context, it, R.color.on_accent, 20))
            addView(hgap(context, 8))
        }
        addView(TextView(context).apply {
            text = label
            textSize = 16f
            typeface = display(context)
            setTextColor(context.getColor(R.color.on_accent))
        })
        setOnClickListener { onClick() }
        Motion.pressable(this)
    }

    fun ghostButton(context: Context, label: String, onClick: () -> Unit): View = TextView(context).apply {
        text = label
        textSize = 15f
        gravity = Gravity.CENTER
        typeface = display(context, 500)
        setTextColor(context.getColor(R.color.text))
        background = sticker(context, context.getColor(R.color.surface), context.getColor(R.color.edge), 18, 4, pressable = true)
        stickerPadding(this, 16, 12)
        setOnClickListener { onClick() }
        Motion.pressable(this)
    }

    fun card(context: Context, fill: Int = R.color.surface, edge: Int = R.color.edge, padding: Int = 16) = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        background = sticker(context, context.getColor(fill), context.getColor(edge))
        stickerPadding(this, padding, padding - 2)
    }

    /** Coloured label: "Prof absent", "Nouveau", a subject chip. */
    fun chip(context: Context, label: String, bg: Int, fg: Int, size: Float = 12f) = TextView(context).apply {
        text = label
        textSize = size
        typeface = body(context, 800)
        setTextColor(fg)
        background = rounded(bg, dp(9).toFloat())
        setPadding(dp(8), dp(2), dp(8), dp(3))
        maxLines = 1
    }

    fun alertChip(context: Context, label: String, severe: Boolean) = chip(
        context, label,
        context.getColor(if (severe) R.color.alert_bg else R.color.warn_bg),
        context.getColor(if (severe) R.color.alert else R.color.warn),
    )

    fun icon(context: Context, res: Int, tint: Int, sizeDp: Int = 24, tintIsColor: Boolean = false) = ImageView(context).apply {
        setImageResource(res)
        imageTintList = ColorStateList.valueOf(if (tintIsColor) tint else context.getColor(tint))
        layoutParams = LinearLayout.LayoutParams(dp(sizeDp), dp(sizeDp))
    }

    /** Rounded square holding a subject icon. */
    fun subjectTile(context: Context, subject: String, sizeDp: Int = 40): View {
        val style = Subjects.style(subject)
        return FrameLayout(context).apply {
            background = rounded(Subjects.tint(context, style.family), dp(sizeDp * 13 / 40).toFloat())
            addView(icon(context, style.icon, Subjects.ink(context, style.family), sizeDp / 2 + 2, tintIsColor = true).apply {
                layoutParams = FrameLayout.LayoutParams(dp(sizeDp / 2 + 2), dp(sizeDp / 2 + 2), Gravity.CENTER)
            })
            layoutParams = LinearLayout.LayoutParams(dp(sizeDp), dp(sizeDp))
        }
    }

    fun subjectChip(context: Context, subject: String): TextView {
        val family = Subjects.style(subject).family
        return chip(context, Agenda.pretty(subject), Subjects.tint(context, family), Subjects.ink(context, family), 11f)
    }

    /** [squareButton] with colours rather than colour resources (the avatar's own colour). */
    fun squareButtonColors(context: Context, fill: Int, edge: Int, content: View, onClick: () -> Unit) = FrameLayout(context).apply {
        background = sticker(context, fill, edge, 15, 3, pressable = true)
        setPadding(0, 0, 0, dp(3))
        addView(content, (content.layoutParams as? FrameLayout.LayoutParams) ?: FrameLayout.LayoutParams(-2, -2, Gravity.CENTER))
        layoutParams = LinearLayout.LayoutParams(dp(46), dp(49))
        setOnClickListener { onClick() }
        Motion.pressable(this, 3)
    }

    /** Square sticker button holding an icon (refresh) or a letter (profile). */
    fun squareButton(context: Context, fill: Int, edge: Int, content: View, onClick: () -> Unit) = FrameLayout(context).apply {
        background = sticker(context, context.getColor(fill), context.getColor(edge), 15, 3, pressable = true)
        setPadding(0, 0, 0, dp(3))
        addView(content, (content.layoutParams as? FrameLayout.LayoutParams) ?: FrameLayout.LayoutParams(-2, -2, Gravity.CENTER))
        layoutParams = LinearLayout.LayoutParams(dp(46), dp(49))
        setOnClickListener { onClick() }
        Motion.pressable(this, 3)
    }

    /**
     * A homework line with its sticker checkbox. [isDone] / [toggle] come from the
     * repository; the row redraws itself, so it works inside any sheet.
     */
    fun homeworkCheck(context: Context, h: fr.sallio.pronote.Homework, isDone: () -> Boolean, onOpen: (() -> Unit)? = null, toggle: (Boolean) -> Unit): View =
        check(context, h.description.ifEmpty { "(pas de consigne)" }, isDone, strike = true, onOpen = onOpen, toggle = toggle)

    /**
     * The sticker checkbox and its label: homework done, actualité seen.
     * [strike] crosses the label out once ticked (homework).
     */
    fun check(context: Context, label: String, isOn: () -> Boolean, strike: Boolean = false, size: Float = 15f, onOpen: (() -> Unit)? = null, toggle: (Boolean) -> Unit): View {
        val row = row(context).apply {
            gravity = Gravity.TOP
            setPadding(0, dp(10), 0, dp(10))
        }
        val box = FrameLayout(context)
        val text = text(context, label, size).apply { setPadding(0, dp(2), 0, 0) }
        fun paint(done: Boolean) {
            box.removeAllViews()
            box.background = if (done) sticker(context, context.getColor(R.color.accent), context.getColor(R.color.accent_edge), 9, 2)
            else rounded(0, context.dp(9).toFloat(), context.dp(3), context.getColor(R.color.box_edge))
            if (done) box.addView(icon(context, R.drawable.ic_check, R.color.on_accent, 16).apply {
                layoutParams = FrameLayout.LayoutParams(context.dp(16), context.dp(16), Gravity.CENTER).apply { bottomMargin = context.dp(2) }
            })
            if (!strike) return
            text.alpha = if (done) 0.45f else 1f
            text.paintFlags = if (done) text.paintFlags or android.graphics.Paint.STRIKE_THRU_TEXT_FLAG
            else text.paintFlags and android.graphics.Paint.STRIKE_THRU_TEXT_FLAG.inv()
        }
        paint(isOn())
        val click = View.OnClickListener {
            val done = !isOn()
            if (done) Motion.success(box) else Motion.tick(box)
            Motion.bounce(box) {
                toggle(done)
                paint(done)
            }
        }
        box.setOnClickListener(click)
        text.setOnClickListener(if (onOpen != null) View.OnClickListener { Motion.tap(text); onOpen() } else click)
        row.addView(box, LinearLayout.LayoutParams(context.dp(28), context.dp(28)))
        row.addView(hgap(context, 12))
        row.addView(text, weight())
        return row
    }

    /**
     * A sticker button with the checkbox inside, for a one-way action ("J'ai
     * vu"): neutral with an empty box, then green with the box ticked and
     * [onLabel]. Once on it no longer reacts: PRONOTE cannot undo it.
     */
    fun checkButton(context: Context, label: String, onLabel: String, isOn: Boolean, onTap: () -> Unit): View {
        val button = row(context).apply { gravity = Gravity.CENTER }
        val box = FrameLayout(context)
        val text = TextView(context).apply { textSize = 16f; typeface = display(context) }
        fun paint(on: Boolean) {
            button.background = if (on) sticker(context, context.getColor(R.color.success_bg), context.getColor(R.color.success_edge), 18, 4)
            else sticker(context, context.getColor(R.color.surface), context.getColor(R.color.edge), 18, 4, pressable = true)
            stickerPadding(button, 16, 12)
            box.removeAllViews()
            box.background = if (on) rounded(context.getColor(R.color.success), context.dp(8).toFloat())
            else rounded(0, context.dp(8).toFloat(), context.dp(3), context.getColor(R.color.box_edge))
            if (on) box.addView(icon(context, R.drawable.ic_check, R.color.success_bg, 15).apply {
                layoutParams = FrameLayout.LayoutParams(context.dp(15), context.dp(15), Gravity.CENTER)
            })
            text.text = if (on) onLabel else label
            text.setTextColor(context.getColor(if (on) R.color.success else R.color.text))
            button.isClickable = !on
        }
        button.addView(box, LinearLayout.LayoutParams(context.dp(24), context.dp(24)))
        button.addView(hgap(context, 10))
        button.addView(text)
        paint(isOn)
        if (!isOn) {
            button.setOnClickListener {
                button.setOnClickListener(null)
                Motion.success(box)
                paint(true)
                Motion.bounce(box) { onTap() }
            }
            Motion.pressable(button)
        }
        return button
    }

    // ---- Layout --------------------------------------------------------------------

    fun row(context: Context) = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
    }

    fun column(context: Context) = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }

    fun gap(context: Context, height: Int) = View(context).apply {
        layoutParams = LinearLayout.LayoutParams(1, context.dp(height))
    }

    fun hgap(context: Context, width: Int) = View(context).apply {
        layoutParams = LinearLayout.LayoutParams(context.dp(width), 1)
    }

    /** Dashed-looking separator inside list cards (solid, in the soft line colour). */
    fun divider(context: Context) = View(context).apply {
        setBackgroundColor(context.getColor(R.color.line))
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, context.dp(2))
    }

    fun matchWidth() = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)

    fun weight(w: Float = 1f) = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, w)

    fun wrap() = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)

    /** "14.87" -> "14,87", French style, no trailing zeros. */
    fun number(value: Double?): String {
        if (value == null || value.isNaN()) return "—"
        val rounded = Math.round(value * 100) / 100.0
        val text = if (rounded == Math.floor(rounded)) rounded.toLong().toString() else rounded.toString()
        return text.replace('.', ',')
    }
}
