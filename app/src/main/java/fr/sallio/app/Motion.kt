package fr.sallio.app

import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.graphics.drawable.LayerDrawable
import android.media.AudioAttributes
import android.os.Build
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.Vibrator
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.view.animation.LinearInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.TextView

/**
 * Small, quick motion and haptics. Everything respects the system: no
 * animation when "Remove animations" is on, and vibrations go through the
 * touch usage, which Android mutes or scales with the haptics settings.
 */
internal object Motion {

    private fun enabled() = ValueAnimator.areAnimatorsEnabled()

    // ---- Haptics -------------------------------------------------------------------

    /**
     * Real vibrations (the system "touch feedback" ticks are too faint to be
     * felt on most phones). Played with the touch usage, so Android still
     * applies the user's haptic intensity setting and silences them when it is off.
     */
    fun hapticsOn(context: android.content.Context) =
        context.getSharedPreferences("ui", android.content.Context.MODE_PRIVATE).getBoolean("haptics", true)

    private fun vibrate(view: View, effect: () -> VibrationEffect?) {
        if (!hapticsOn(view.context)) return
        val vibrator = view.context.getSystemService(Vibrator::class.java) ?: return
        if (!vibrator.hasVibrator()) return
        val e = runCatching { effect() }.getOrNull() ?: return
        if (Build.VERSION.SDK_INT >= 33) {
            vibrator.vibrate(e, VibrationAttributes.createForUsage(VibrationAttributes.USAGE_TOUCH))
        } else {
            @Suppress("DEPRECATION")
            vibrator.vibrate(e, AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION).build())
        }
    }

    /** Rich "primitives" when the motor supports them, a predefined effect otherwise. */
    private fun primitives(view: View, vararg steps: Pair<Int, Float>, fallback: Int): VibrationEffect {
        val vibrator = view.context.getSystemService(Vibrator::class.java)
        if (Build.VERSION.SDK_INT >= 30 && vibrator != null && vibrator.areAllPrimitivesSupported(*steps.map { it.first }.toIntArray())) {
            val composition = VibrationEffect.startComposition()
            steps.forEachIndexed { i, (primitive, scale) -> composition.addPrimitive(primitive, scale, if (i == 0) 0 else 70) }
            return composition.compose()
        }
        return VibrationEffect.createPredefined(fallback)
    }

    // The system scales touch vibrations with the "touch feedback" intensity
    // (x0.51 when it is set to low), so only full-strength clicks stay noticeable.

    /** Switching tab or day. */
    fun tick(view: View) = vibrate(view) {
        primitives(view, VibrationEffect.Composition.PRIMITIVE_CLICK to 0.9f, fallback = VibrationEffect.EFFECT_CLICK)
    }

    /** Pressing a button. */
    fun tap(view: View) = vibrate(view) {
        primitives(view, VibrationEffect.Composition.PRIMITIVE_CLICK to 1f, fallback = VibrationEffect.EFFECT_HEAVY_CLICK)
    }

    /** Something done: homework ticked, refresh succeeded. */
    fun success(view: View) = vibrate(view) {
        primitives(
            view,
            VibrationEffect.Composition.PRIMITIVE_CLICK to 1f,
            VibrationEffect.Composition.PRIMITIVE_QUICK_RISE to 1f,
            fallback = VibrationEffect.EFFECT_DOUBLE_CLICK,
        )
    }

    /** Something failed: Pronote down, wrong address. */
    fun failure(view: View) = vibrate(view) {
        primitives(
            view,
            VibrationEffect.Composition.PRIMITIVE_THUD to 1f,
            VibrationEffect.Composition.PRIMITIVE_THUD to 0.8f,
            fallback = VibrationEffect.EFFECT_HEAVY_CLICK,
        )
    }

    // ---- Stickers ------------------------------------------------------------------

    /**
     * The sticker press: the face sinks onto its shadow while the finger is
     * down, then springs back. Click listeners still fire as usual.
     */
    @SuppressLint("ClickableViewAccessibility")
    fun pressable(view: View, depthDp: Int = 4, haptic: Boolean = true) {
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
                    if (haptic && event.actionMasked == MotionEvent.ACTION_UP) tap(v)
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

    /** What already made its entrance: a notice appears once, not at every redraw. */
    private val appeared = HashSet<String>()

    /**
     * A notice making its entrance (an update, a new actualité, a refusal):
     * fades in while settling from slightly above and slightly smaller. Soft,
     * no overshoot. Once per [key] while the app runs.
     */
    fun appear(view: View, key: String, startDelay: Long = 120) {
        if (!appeared.add(key) || !enabled()) return
        view.alpha = 0f
        view.translationY = -view.dp(14).toFloat()
        view.scaleX = 0.96f
        view.scaleY = 0.96f
        view.animate().alpha(1f).translationY(0f).scaleX(1f).scaleY(1f)
            .setStartDelay(startDelay).setDuration(420)
            .setInterpolator(DecelerateInterpolator(2.4f)).start()
    }

    /**
     * A card leaving after [delay]: it slides right and fades, then the space it
     * took closes smoothly; [then] runs at the end (or at once without animations).
     */
    fun leave(view: View, delay: Long = 0, then: () -> Unit) {
        if (!enabled()) return then()
        view.animate().setStartDelay(delay).alpha(0f).translationX(view.width * 0.25f).setDuration(220)
            .setInterpolator(android.view.animation.AccelerateInterpolator(1.5f))
            .withEndAction {
                val start = view.height
                val params = view.layoutParams
                val margin = (params as? ViewGroup.MarginLayoutParams)?.bottomMargin ?: 0
                ValueAnimator.ofFloat(1f, 0f).apply {
                    duration = 240
                    interpolator = DecelerateInterpolator(1.5f)
                    addUpdateListener {
                        val f = it.animatedValue as Float
                        params.height = (start * f).toInt()
                        (params as? ViewGroup.MarginLayoutParams)?.bottomMargin = (margin * f).toInt()
                        view.layoutParams = params
                    }
                    addListener(object : android.animation.AnimatorListenerAdapter() {
                        override fun onAnimationEnd(animation: android.animation.Animator) { then() }
                    })
                    start()
                }
            }.start()
    }

    /**
     * Lets [view] grow past its row and its card while it bounces: the boxes sit near
     * the card's padding, which cut the bounce (Oscar, 2026-10-15). A few levels up only;
     * a card that clips to its rounded outline still does.
     */
    private fun unclip(view: View) {
        var p = view.parent
        repeat(4) {
            val g = p as? ViewGroup ?: return
            g.clipChildren = false
            g.clipToPadding = false
            p = g.parent
        }
    }

    /** One view rising in, for content that unfolds after a tap. */
    fun fadeIn(view: View, startDelay: Long = 0) {
        if (!enabled()) return
        view.alpha = 0f
        view.translationY = view.dp(10).toFloat()
        view.animate().alpha(1f).translationY(0f).setStartDelay(startDelay).setDuration(260)
            .setInterpolator(DecelerateInterpolator(2f)).start()
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
        unclip(view)
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
