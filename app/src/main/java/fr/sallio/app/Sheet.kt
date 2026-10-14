package fr.sallio.app

import android.annotation.SuppressLint
import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.view.Gravity
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.Window
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView

/**
 * Bottom sheet shared by every panel: a handle fixed at the top, a scrolling
 * body under it, and a swipe down (on the handle, or anywhere once the content
 * is scrolled to the top) to close it.
 */
object Sheet {

    /** Sheets on screen, to close them when a widget or a notification opens something else. */
    private val open = java.util.Collections.newSetFromMap(java.util.WeakHashMap<Dialog, Boolean>())

    /** Closes every sheet still open (a widget tap brings its own page). */
    fun closeAll() = open.toList().forEach { runCatching { it.dismiss() } }

    /** Opens a sheet; [build] fills the column under the handle. */
    fun open(activity: MainActivity, bottomInset: Int, build: (LinearLayout, Dialog) -> Unit): Dialog {
        val ctx = activity
        // A full screen, non-floating theme: a floating dialog is always laid out above
        // the navigation bar, which left a strip of the page visible under the sheet.
        val dialog = Dialog(ctx, android.R.style.Theme_Translucent_NoTitleBar)

        val column = UI.column(ctx).apply {
            setPadding(ctx.dp(18), 0, ctx.dp(18), ctx.dp(22))
        }
        build(column, dialog)

        val scroll = ScrollView(ctx).apply {
            isVerticalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            addView(column)
        }
        // The handle above the scroll view: a long actualité scrolls under it, it stays in sight.
        val body = UI.column(ctx).apply {
            addView(View(ctx).apply { background = UI.rounded(ctx.getColor(R.color.text3), ctx.dp(3).toFloat()) },
                LinearLayout.LayoutParams(ctx.dp(40), ctx.dp(5)).apply { gravity = Gravity.CENTER_HORIZONTAL; topMargin = ctx.dp(12); bottomMargin = ctx.dp(14) })
            addView(scroll, LinearLayout.LayoutParams(-1, -2))
        }
        val container = DragLayout(ctx, scroll) { dialog.dismiss() }.apply {
            // Rounded on top only: the sheet sits on the bottom edge of the screen.
            val r = ctx.dp(30).toFloat()
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(ctx.getColor(R.color.bg))
                cornerRadii = floatArrayOf(r, r, r, r, 0f, 0f, 0f, 0f)
            }
            outlineProvider = android.view.ViewOutlineProvider.BACKGROUND
            clipToOutline = true
            addView(body, FrameLayout.LayoutParams(-1, -2))
        }
        val maxHeight = (ctx.resources.displayMetrics.heightPixels * 0.92f).toInt()
        container.maxHeight = maxHeight

        // The window covers the whole screen, navigation bar included, so the sheet's
        // own colour runs to the bottom edge: nothing of the page behind shows there.
        // A tap above the sheet closes it, like a normal dialog.
        val screen = FrameLayout(ctx).apply {
            setBackgroundColor(0x73000000)
            setOnClickListener { dialog.dismiss() }
            addView(container, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM))
        }
        container.isClickable = true
        container.setOnApplyWindowInsetsListener { v, insets ->
            val nav = if (android.os.Build.VERSION.SDK_INT >= 30) {
                insets.getInsets(android.view.WindowInsets.Type.navigationBars() or android.view.WindowInsets.Type.ime()).bottom
            } else @Suppress("DEPRECATION") insets.systemWindowInsetBottom
            v.setPadding(0, 0, 0, nav)
            insets
        }

        dialog.setContentView(screen)
        dialog.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            setWindowAnimations(android.R.style.Animation_InputMethod)
            if (android.os.Build.VERSION.SDK_INT >= 30) setDecorFitsSystemWindows(false)
            @Suppress("DEPRECATION")
            addFlags(android.view.WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS)
            @Suppress("DEPRECATION")
            navigationBarColor = Color.TRANSPARENT
            @Suppress("DEPRECATION")
            statusBarColor = Color.TRANSPARENT
        }
        open += dialog
        dialog.show()
        return dialog
    }

    /** Header used by most sheets: big title and an optional subtitle. */
    fun title(column: LinearLayout, title: String, subtitle: String? = null) {
        val ctx = column.context
        column.addView(UI.title(ctx, title, 24f))
        subtitle?.let { column.addView(UI.text(ctx, it, 14f, R.color.text2).apply { setPadding(0, ctx.dp(4), 0, ctx.dp(4)) }) }
    }

    /** Follows a downward drag, and dismisses past a third of the height or on a fling. */
    @SuppressLint("ViewConstructor")
    private class DragLayout(context: Context, private val scroll: ScrollView, private val onDismiss: () -> Unit) : FrameLayout(context) {
        var maxHeight = Int.MAX_VALUE
        private val slop = ViewConfiguration.get(context).scaledTouchSlop
        private var downY = 0f
        /** The finger came down on the handle strip: dragging it always pulls the sheet. */
        private var onHandle = false
        private var dragging = false
        private var velocity: VelocityTracker? = null

        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(maxHeight, MeasureSpec.AT_MOST))
        }

        override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> { downY = ev.rawY; onHandle = ev.y < 40 * resources.displayMetrics.density; dragging = false }
                MotionEvent.ACTION_MOVE -> {
                    val dy = ev.rawY - downY
                    if (!dragging && dy > slop && (onHandle || !scroll.canScrollVertically(-1))) {
                        dragging = true
                        velocity = VelocityTracker.obtain()
                        return true
                    }
                }
            }
            return false
        }

        @SuppressLint("ClickableViewAccessibility")
        override fun onTouchEvent(ev: MotionEvent): Boolean {
            if (!dragging) {
                if (ev.actionMasked == MotionEvent.ACTION_DOWN) { downY = ev.rawY; return true }
                if (ev.actionMasked == MotionEvent.ACTION_MOVE && ev.rawY - downY > slop) {
                    dragging = true
                    velocity = VelocityTracker.obtain()
                } else return true
            }
            velocity?.addMovement(ev)
            val dy = (ev.rawY - downY).coerceAtLeast(0f)
            when (ev.actionMasked) {
                MotionEvent.ACTION_MOVE -> translationY = dy
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    velocity?.computeCurrentVelocity(1000)
                    val fling = (velocity?.yVelocity ?: 0f) > 1400f
                    velocity?.recycle(); velocity = null
                    dragging = false
                    if (dy > height / 3f || (fling && dy > slop)) {
                        animate().translationY(height.toFloat()).setDuration(180).withEndAction { onDismiss() }.start()
                    } else {
                        animate().translationY(0f).setDuration(220).setInterpolator(DecelerateInterpolator(2f)).start()
                    }
                }
            }
            return true
        }
    }
}
