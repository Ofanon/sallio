package fr.masalle.app

import android.content.Context
import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.widget.LinearLayout
import android.widget.TextView

/** The few view builders the two screens share. Plain platform views, no library. */
internal fun Context.dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

internal fun View.dp(value: Int): Int = context.dp(value)

/** Edge-to-edge is forced from Android 15: keep content out of the bars. */
internal fun View.fitsSystemWindowsCompat() {
    val start = paddingLeft
    val top = paddingTop
    val end = paddingRight
    val bottom = paddingBottom
    setOnApplyWindowInsetsListener { v, insets ->
        val bars = if (android.os.Build.VERSION.SDK_INT >= 30) {
            insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.ime())
                .let { intArrayOf(it.left, it.top, it.right, it.bottom) }
        } else {
            @Suppress("DEPRECATION")
            intArrayOf(insets.systemWindowInsetLeft, insets.systemWindowInsetTop, insets.systemWindowInsetRight, insets.systemWindowInsetBottom)
        }
        v.setPadding(start + bars[0], top + bars[1], end + bars[2], bottom + bars[3])
        insets
    }
}

internal object UI {

    fun text(context: Context, value: CharSequence, size: Float, color: Int = R.color.text, bold: Boolean = false) =
        TextView(context).apply {
            text = value
            textSize = size
            setTextColor(context.getColor(color))
            if (bold) typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        }

    fun button(context: Context, label: String, onClick: () -> Unit) = TextView(context).apply {
        text = label
        textSize = 15f
        gravity = Gravity.CENTER
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        setTextColor(context.getColor(R.color.on_accent))
        setBackgroundResource(R.drawable.button)
        setPadding(dp(18), dp(13), dp(18), dp(13))
        isClickable = true
        isFocusable = true
        setOnClickListener { onClick() }
    }

    fun ghostButton(context: Context, label: String, onClick: () -> Unit) = TextView(context).apply {
        text = label
        textSize = 14f
        gravity = Gravity.CENTER
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        setTextColor(context.getColor(R.color.text))
        setBackgroundResource(R.drawable.button_ghost)
        setPadding(dp(14), dp(10), dp(14), dp(10))
        isClickable = true
        isFocusable = true
        setOnClickListener { onClick() }
    }

    fun card(context: Context) = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setBackgroundResource(R.drawable.card)
        setPadding(dp(18), dp(16), dp(18), dp(16))
    }

    fun gap(context: Context, height: Int) = View(context).apply {
        layoutParams = LinearLayout.LayoutParams(1, context.dp(height))
    }

    fun matchWidth() = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
}
