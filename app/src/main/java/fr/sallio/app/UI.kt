package fr.sallio.app

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
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

/** View builders shared by every screen. Plain platform views, no library. */
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

    val MEDIUM: Typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    val BOLD: Typeface = Typeface.create("sans-serif", Typeface.BOLD)

    fun color(context: Context, res: Int) = context.getColor(res)

    fun rounded(color: Int, radius: Float, stroke: Int = 0, strokeColor: Int = 0) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = radius
        if (stroke > 0) setStroke(stroke, strokeColor)
    }

    /** Background with the touch ripple, clipped to the same rounded shape. */
    fun pressable(context: Context, color: Int, radiusDp: Int): Drawable {
        val shape = rounded(color, context.dp(radiusDp).toFloat())
        val mask = rounded(Color.WHITE, context.dp(radiusDp).toFloat())
        return RippleDrawable(ColorStateList.valueOf(context.getColor(R.color.text3) and 0x33FFFFFF), shape, mask)
    }

    fun text(context: Context, value: CharSequence, size: Float, color: Int = R.color.text, weight: Typeface? = null) =
        TextView(context).apply {
            text = value
            textSize = size
            setTextColor(context.getColor(color))
            if (weight != null) typeface = weight
            includeFontPadding = true
        }

    fun oneLine(view: TextView) = view.apply {
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.END
    }

    fun title(context: Context, value: String) = text(context, value, 13f, R.color.text2, MEDIUM).apply {
        letterSpacing = 0.06f
        setPadding(context.dp(4), context.dp(22), 0, context.dp(8))
    }

    fun button(context: Context, label: String, onClick: () -> Unit) = TextView(context).apply {
        text = label
        textSize = 15f
        gravity = Gravity.CENTER
        typeface = MEDIUM
        setTextColor(context.getColor(R.color.on_accent))
        background = pressable(context, context.getColor(R.color.accent), 16)
        setPadding(dp(18), dp(15), dp(18), dp(15))
        setOnClickListener { onClick() }
    }

    fun ghostButton(context: Context, label: String, onClick: () -> Unit) = TextView(context).apply {
        text = label
        textSize = 14f
        gravity = Gravity.CENTER
        typeface = MEDIUM
        setTextColor(context.getColor(R.color.text))
        background = pressable(context, context.getColor(R.color.surface2), 16)
        setPadding(dp(16), dp(13), dp(16), dp(13))
        setOnClickListener { onClick() }
    }

    fun card(context: Context, color: Int = R.color.surface, padding: Int = 18) = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        background = rounded(context.getColor(color), context.dp(22).toFloat())
        setPadding(dp(padding), dp(padding), dp(padding), dp(padding))
    }

    fun pill(context: Context, label: String, severe: Boolean, onDark: Boolean = false) = TextView(context).apply {
        text = label
        textSize = 12f
        typeface = MEDIUM
        val fg = if (severe) R.color.alert else R.color.warn
        val bg = if (severe) R.color.alert_bg else R.color.warn_bg
        setTextColor(context.getColor(if (onDark) bg else fg))
        background = rounded(context.getColor(if (onDark) fg else bg), dp(10).toFloat())
        setPadding(dp(9), dp(4), dp(9), dp(4))
        maxLines = 1
    }

    fun icon(context: Context, res: Int, tint: Int, sizeDp: Int = 24) = ImageView(context).apply {
        setImageResource(res)
        imageTintList = ColorStateList.valueOf(context.getColor(tint))
        layoutParams = LinearLayout.LayoutParams(dp(sizeDp), dp(sizeDp))
    }

    /** Small circular button holding an icon (refresh, profile...). */
    fun roundIcon(context: Context, res: Int, onClick: () -> Unit) = FrameLayout(context).apply {
        background = pressable(context, context.getColor(R.color.surface), 22)
        layoutParams = LinearLayout.LayoutParams(dp(44), dp(44))
        addView(icon(context, res, R.color.text2, 22).apply {
            layoutParams = FrameLayout.LayoutParams(dp(22), dp(22), Gravity.CENTER)
        })
        setOnClickListener { onClick() }
    }

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

    fun matchWidth() = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)

    fun weight(w: Float = 1f) = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, w)

    fun wrap() = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)

    fun parseColor(text: String?, fallback: Int): Int =
        text?.let { runCatching { Color.parseColor(it) }.getOrNull() } ?: fallback

    /** "14.87" -> "14,87", French style, no trailing zeros. */
    fun number(value: Double?): String {
        if (value == null || value.isNaN()) return "—"
        val rounded = Math.round(value * 100) / 100.0
        val text = if (rounded == Math.floor(rounded)) rounded.toLong().toString() else rounded.toString()
        return text.replace('.', ',')
    }
}
