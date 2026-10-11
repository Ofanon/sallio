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
 * Bottom sheet shared by every panel: a handle, a scrolling body, and a swipe
 * down (from anywhere once the content is scrolled to the top) to close it.
 */
object Sheet {

    /** Opens a sheet; [build] fills the column under the handle. */
    fun open(activity: MainActivity, bottomInset: Int, build: (LinearLayout, Dialog) -> Unit): Dialog {
        val ctx = activity
        val dialog = Dialog(ctx)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)

        val column = UI.column(ctx).apply {
            setPadding(ctx.dp(18), ctx.dp(12), ctx.dp(18), ctx.dp(22))
        }
        column.addView(View(ctx).apply { background = UI.rounded(ctx.getColor(R.color.text3), ctx.dp(3).toFloat()) },
            LinearLayout.LayoutParams(ctx.dp(40), ctx.dp(5)).apply { gravity = Gravity.CENTER_HORIZONTAL; bottomMargin = ctx.dp(14) })
        build(column, dialog)

        val scroll = ScrollView(ctx).apply {
            isVerticalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            addView(column)
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
            addView(scroll, FrameLayout.LayoutParams(-1, -2))
        }
        val maxHeight = (ctx.resources.displayMetrics.heightPixels * 0.92f).toInt()
        container.maxHeight = maxHeight

        dialog.setContentView(container)
        dialog.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setGravity(Gravity.BOTTOM)
            setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            setWindowAnimations(android.R.style.Animation_InputMethod)
            setDimAmount(0.45f)
            // The sheet stops above the navigation bar: paint that bar in the sheet colour.
            addFlags(android.view.WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS)
            @Suppress("DEPRECATION")
            navigationBarColor = ctx.getColor(R.color.bg)
        }
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
        private var dragging = false
        private var velocity: VelocityTracker? = null

        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(maxHeight, MeasureSpec.AT_MOST))
        }

        override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> { downY = ev.rawY; dragging = false }
                MotionEvent.ACTION_MOVE -> {
                    val dy = ev.rawY - downY
                    if (!dragging && dy > slop && !scroll.canScrollVertically(-1)) {
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
