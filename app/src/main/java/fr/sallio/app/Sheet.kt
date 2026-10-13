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

    /** The sheet on screen: what opens from it becomes a page inside it, not a sheet over it. */
    private var top: Host? = null

    /**
     * Opens a sheet; [build] fills the column under the handle. From an open
     * sheet (a lesson's homework, a subject's grade, a settings page), the
     * same sheet turns to a new page with a back button instead of stacking a
     * second sheet over the first. The [Dialog] given to [build] and returned
     * stands for that page: dismissing it goes back to the previous one.
     */
    fun open(activity: MainActivity, bottomInset: Int, build: (LinearLayout, Dialog) -> Unit): Dialog {
        top?.takeIf { it.activity === activity && it.isShowing && !it.closing }?.let { return it.push(build) }
        return Host(activity).also { top = it; it.start(build) }
    }

    private fun page(ctx: Context) = UI.column(ctx).apply { setPadding(ctx.dp(18), 0, ctx.dp(18), ctx.dp(22)) }

    // A full screen, non-floating theme: a floating dialog is always laid out above
    // the navigation bar, which left a strip of the page visible under the sheet.
    private class Host(val activity: MainActivity) : Dialog(activity, android.R.style.Theme_Translucent_NoTitleBar) {
        class Page(val column: LinearLayout, val handle: Dialog, var scrollY: Int = 0)

        var closing = false
        private val pages = ArrayList<Page>()
        private val scroll = ScrollView(activity).apply {
            isVerticalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
        }
        private lateinit var container: DragLayout
        private lateinit var back: View

        fun start(build: (LinearLayout, Dialog) -> Unit) {
            val ctx = activity
            val column = page(ctx)
            build(column, this)
            pages += Page(column, this)
            scroll.addView(column)

            // The handle above the scroll view: a long actualité scrolls under it, it stays in sight.
            // The back button shares its strip, so a page inside the sheet costs no height.
            back = UI.row(ctx).apply {
                gravity = Gravity.CENTER_VERTICAL
                setPadding(ctx.dp(12), ctx.dp(6), ctx.dp(14), ctx.dp(4))
                addView(UI.icon(ctx, R.drawable.ic_back, R.color.text2, 20))
                addView(UI.text(ctx, "Retour", 14f, R.color.text2, 800).apply { setPadding(ctx.dp(2), 0, 0, 0) })
                visibility = View.INVISIBLE
                contentDescription = "Retour"
                setOnClickListener { Motion.tick(this); pages.lastOrNull()?.handle?.dismiss() }
            }
            val strip = FrameLayout(ctx).apply {
                addView(View(ctx).apply { background = UI.rounded(ctx.getColor(R.color.text3), ctx.dp(3).toFloat()) },
                    FrameLayout.LayoutParams(ctx.dp(40), ctx.dp(5), Gravity.CENTER_HORIZONTAL or Gravity.TOP).apply { topMargin = ctx.dp(12) })
                addView(back, FrameLayout.LayoutParams(-2, -2, Gravity.START or Gravity.TOP).apply { topMargin = ctx.dp(4) })
            }
            val body = UI.column(ctx).apply {
                addView(strip, LinearLayout.LayoutParams(-1, ctx.dp(31)))
                addView(scroll, LinearLayout.LayoutParams(-1, -2))
            }
            container = DragLayout(ctx, scroll) { dismiss() }.apply {
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
            container.maxHeight = (ctx.resources.displayMetrics.heightPixels * 0.92f).toInt()

            // The window covers the whole screen, navigation bar included, so the sheet's
            // own colour runs to the bottom edge: nothing of the page behind shows there.
            // A tap above the sheet closes it, like a normal dialog.
            val screen = FrameLayout(ctx).apply {
                setBackgroundColor(0x73000000)
                setOnClickListener { dismiss() }
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

            setContentView(screen)
            window?.apply {
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
            show()
        }

        /** A new page inside this sheet, sliding in from the right. */
        fun push(build: (LinearLayout, Dialog) -> Unit): Dialog {
            val column = page(activity)
            val handle = PageHandle(this)
            build(column, handle)
            pages.lastOrNull()?.scrollY = scroll.scrollY
            pages += Page(column, handle)
            show(column, forward = true, y = 0)
            return handle
        }

        /** Back from [handle]'s page to the one under it. */
        fun pop(handle: PageHandle) {
            val i = pages.indexOfFirst { it.handle === handle }
            if (i <= 0) return
            pages.removeAt(i)
            handle.gone()
            if (i == pages.size) pages.last().let { show(it.column, forward = false, y = it.scrollY) }
        }

        private fun show(column: LinearLayout, forward: Boolean, y: Int) {
            back.visibility = if (pages.size > 1) View.VISIBLE else View.INVISIBLE
            val animate = android.animation.ValueAnimator.areAnimatorsEnabled() && container.isLaidOut
            // The sheet's height follows the new page smoothly instead of jumping.
            if (animate) android.transition.TransitionManager.beginDelayedTransition(container.parent as ViewGroup,
                android.transition.ChangeBounds().setDuration(240).setInterpolator(DecelerateInterpolator(2f)))
            scroll.removeAllViews()
            (column.parent as? ViewGroup)?.removeView(column)
            scroll.addView(column)
            scroll.post { scroll.scrollTo(0, y) }
            if (animate) {
                column.alpha = 0f
                column.translationX = container.width * (if (forward) 0.3f else -0.3f)
                column.animate().alpha(1f).translationX(0f).setDuration(260).setInterpolator(DecelerateInterpolator(2f)).start()
            }
        }

        @Deprecated("Dialog API")
        override fun onBackPressed() {
            val last = pages.lastOrNull()
            if (pages.size > 1 && last != null) last.handle.dismiss() else @Suppress("DEPRECATION") super.onBackPressed()
        }

        /** Closes the whole sheet: every page inside it hears it, last first. */
        override fun dismiss() {
            if (closing) return
            closing = true
            if (top === this) top = null
            pages.drop(1).reversed().forEach { (it.handle as PageHandle).gone() }
            super.dismiss()
        }
    }

    /** Stands for a page inside a sheet: dismissing it goes back; its dismiss listener hears either. */
    private class PageHandle(private val host: Host) : Dialog(host.context) {
        private var listener: android.content.DialogInterface.OnDismissListener? = null
        private var closed = false

        override fun setOnDismissListener(listener: android.content.DialogInterface.OnDismissListener?) { this.listener = listener }
        override fun dismiss() { if (!closed) host.pop(this) }
        override fun isShowing() = !closed && host.isShowing

        fun gone() {
            if (closed) return
            closed = true
            listener?.onDismiss(this)
        }
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
