package fr.sallio.app

import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.graphics.drawable.LayerDrawable
import android.os.Build
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.view.animation.LinearInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.TextView

/**
 * Small, quick motion and haptics. Everything respects the system: no
 * animation when "Remove animations" is on, no vibration when touch
 * feedback is off (performHapticFeedback already honours it).
 */
internal object Motion {

    private fun enabled() = ValueAnimator.areAnimatorsEnabled()

    // ---- Haptics -------------------------------------------------------------------

    /** Light tick: switching tab or day. */
    fun tick(view: View) {
        view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
    }

    /** Pressing a button. */
    fun tap(view: View) {
        view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
    }

    /** Something done: homework ticked, refresh succeeded. */
    fun success(view: View) {
        view.performHapticFeedback(
            if (Build.VERSION.SDK_INT >= 30) HapticFeedbackConstants.CONFIRM else HapticFeedbackConstants.VIRTUAL_KEY
        )
    }

    /** Something failed: Pronote down, wrong address. */
    fun failure(view: View) {
        view.performHapticFeedback(
            if (Build.VERSION.SDK_INT >= 30) HapticFeedbackConstants.REJECT else HapticFeedbackConstants.LONG_PRESS
        )
    }

    // ---- Stickers ------------------------------------------------------------------

    /**
     * The sticker press: the face sinks onto its shadow while the finger is
     * down, then springs back. Click listeners still fire as usual.
     */
    @SuppressLint("ClickableViewAccessibility")
    fun pressable(view: View, depthDp: Int = 4) {
        val depth = view.dp(depthDp).toFloat()
        view.setOnTouchListener { v, event ->
            val shadow = (v.background as? LayerDrawable)?.takeIf { it.numberOfLayers == 2 }?.getDrawable(0)
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    shadow?.alpha = 0
                    v.animate().cancel()
                    v.translationY = depth
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    // Vibrate on release only: a scroll that starts on a card cancels instead.
                    if (event.actionMasked == MotionEvent.ACTION_UP) tap(v)
                    shadow?.alpha = 255
                    if (enabled()) v.animate().translationY(0f).setDuration(220).setInterpolator(OvershootInterpolator(3f)).start()
                    else v.translationY = 0f
                }
            }
            false
        }
    }

    // ---- Entrances -----------------------------------------------------------------

    /** Children rise and fade in one after the other, when a screen opens. */
    fun cascade(container: ViewGroup, startDelay: Long = 0) {
        if (!enabled()) return
        val rise = container.dp(18).toFloat()
        for (i in 0 until container.childCount) {
            val child = container.getChildAt(i)
            child.alpha = 0f
            child.translationY = rise
            child.animate()
                .alpha(1f).translationY(0f)
                .setStartDelay(startDelay + i * 40L)
                .setDuration(320)
                .setInterpolator(DecelerateInterpolator(2f))
                .start()
        }
    }

    /** A small pop, for a freshly selected item. */
    fun pop(view: View, from: Float = 0.85f) {
        if (!enabled()) return
        view.scaleX = from
        view.scaleY = from
        view.animate().scaleX(1f).scaleY(1f).setDuration(260).setInterpolator(OvershootInterpolator(2.5f)).start()
    }

    /** Bounce of the checkbox, then [then] once the gesture has been seen. */
    fun bounce(view: View, then: () -> Unit) {
        if (!enabled()) return then()
        view.animate().scaleX(1.25f).scaleY(1.25f).setDuration(90).withEndAction {
            view.animate().scaleX(1f).scaleY(1f).setDuration(140).setInterpolator(OvershootInterpolator(3f)).withEndAction(then).start()
        }.start()
    }

    /** Counts a number up ("0" -> "17,99"), formatted by [format]. */
    fun countUp(view: TextView, target: Double, format: (Double) -> String) {
        if (!enabled()) return
        ValueAnimator.ofFloat(0f, target.toFloat()).apply {
            duration = 700
            interpolator = DecelerateInterpolator(2f)
            addUpdateListener { view.text = format((it.animatedValue as Float).toDouble()) }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) { view.text = format(target) }
            })
            start()
        }
    }

    /** Endless spin, for the refresh icon while syncing. */
    fun spin(view: View): ObjectAnimator? {
        if (!enabled()) return null
        return ObjectAnimator.ofFloat(view, View.ROTATION, 0f, 360f).apply {
            duration = 900
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            start()
        }
    }
}
