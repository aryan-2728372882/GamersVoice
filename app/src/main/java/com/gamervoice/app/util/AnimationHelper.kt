package com.gamervoice.app.util

import android.animation.ObjectAnimator
import android.animation.PropertyValuesHolder
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.view.MotionEvent
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.TextView

object AnimationHelper {

    /**
     * Attaches an ultra-responsive spring press animation to any View.
     * On touch down: smoothly scales to pressScale (default 0.94f) with DecelerateInterpolator.
     * On touch release: springs back to 1.0f with OvershootInterpolator before triggering onClick.
     * On finger drag cancel: smoothly reverts without triggering click.
     */
    @SuppressLint("ClickableViewAccessibility")
    fun attachPressAnimation(
        view: View,
        pressScale: Float = 0.94f,
        hapticFeedback: Boolean = true,
        onClick: (() -> Unit)? = null
    ) {
        view.setOnTouchListener { v, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    if (hapticFeedback) {
                        try {
                            v.performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_TAP)
                        } catch (_: Exception) {}
                    }
                    v.animate()
                        .scaleX(pressScale)
                        .scaleY(pressScale)
                        .setDuration(70)
                        .setInterpolator(DecelerateInterpolator())
                        .start()
                    true
                }
                MotionEvent.ACTION_UP -> {
                    v.animate()
                        .scaleX(1.0f)
                        .scaleY(1.0f)
                        .setDuration(160)
                        .setInterpolator(OvershootInterpolator(2.2f))
                        .withEndAction {
                            onClick?.invoke()
                        }
                        .start()
                    true
                }
                MotionEvent.ACTION_CANCEL -> {
                    v.animate()
                        .scaleX(1.0f)
                        .scaleY(1.0f)
                        .setDuration(100)
                        .setInterpolator(DecelerateInterpolator())
                        .start()
                    true
                }
                else -> false
            }
        }
    }

    /**
     * Performs a single crisp pop/pulse animation on a View (e.g. badge update, tab click).
     */
    fun popView(view: View, targetScale: Float = 1.15f, durationMs: Long = 180L, onComplete: (() -> Unit)? = null) {
        view.animate()
            .scaleX(targetScale)
            .scaleY(targetScale)
            .setDuration(durationMs / 2)
            .setInterpolator(DecelerateInterpolator())
            .withEndAction {
                view.animate()
                    .scaleX(1.0f)
                    .scaleY(1.0f)
                    .setDuration(durationMs / 2)
                    .setInterpolator(OvershootInterpolator(2.0f))
                    .withEndAction { onComplete?.invoke() }
                    .start()
            }
            .start()
    }

    /**
     * Adds an ambient breathing pulse to VIP elements (crowns, upgrade buttons, glowing badges).
     */
    fun startAmbientPulse(view: View, minScale: Float = 0.98f, maxScale: Float = 1.05f, cycleDurationMs: Long = 2000L): ObjectAnimator {
        val scaleX = PropertyValuesHolder.ofFloat(View.SCALE_X, minScale, maxScale)
        val scaleY = PropertyValuesHolder.ofFloat(View.SCALE_Y, minScale, maxScale)
        val animator = ObjectAnimator.ofPropertyValuesHolder(view, scaleX, scaleY).apply {
            duration = cycleDurationMs / 2
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.REVERSE
            interpolator = AccelerateDecelerateInterpolator()
            start()
        }
        return animator
    }

    /**
     * Smoothly shakes a view horizontally to indicate error or locked VIP feature.
     */
    fun shakeView(view: View, distance: Float = 14f) {
        val animator = ObjectAnimator.ofFloat(
            view,
            View.TRANSLATION_X,
            0f, -distance, distance, -distance / 2, distance / 2, 0f
        )
        animator.duration = 380
        animator.interpolator = DecelerateInterpolator()
        animator.start()
    }

    /**
     * Transitions between two tab views smoothly with slide and crossfade instead of instant snapping.
     */
    fun transitionContainers(outgoingView: View, incomingView: View) {
        if (outgoingView == incomingView) return

        outgoingView.animate()
            .alpha(0f)
            .translationY(-12f)
            .setDuration(120)
            .setInterpolator(DecelerateInterpolator())
            .withEndAction {
                outgoingView.visibility = View.GONE
                outgoingView.alpha = 1f
                outgoingView.translationY = 0f

                incomingView.alpha = 0f
                incomingView.translationY = 16f
                incomingView.visibility = View.VISIBLE
                incomingView.animate()
                    .alpha(1f)
                    .translationY(0f)
                    .setDuration(180)
                    .setInterpolator(DecelerateInterpolator())
                    .start()
            }
            .start()
    }

    /**
     * Smoothly animates a text change with a flip/scale fade.
     */
    fun animateTextChange(textView: TextView, newText: String) {
        if (textView.text == newText) return
        textView.animate()
            .alpha(0f)
            .scaleY(0.7f)
            .setDuration(90)
            .withEndAction {
                textView.text = newText
                textView.animate()
                    .alpha(1f)
                    .scaleY(1.0f)
                    .setDuration(140)
                    .setInterpolator(OvershootInterpolator(1.5f))
                    .start()
            }
            .start()
    }
}
