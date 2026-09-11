package com.gamervoice.app.overlay

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import androidx.core.content.ContextCompat
import com.gamervoice.app.R
import com.gamervoice.app.auth.PlanManager
import com.gamervoice.app.databinding.LayoutFloatingHudBinding
import com.gamervoice.app.service.VoiceService

object FloatingHudManager {

    private const val TAG = "FloatingHudManager"

    private var windowManager: WindowManager? = null
    private var binding: LayoutFloatingHudBinding? = null
    private var layoutParams: WindowManager.LayoutParams? = null

    private val mainHandler = Handler(Looper.getMainLooper())
    private var isMuted = false

    fun hasOverlayPermission(context: Context): Boolean {
        return Settings.canDrawOverlays(context)
    }

    @SuppressLint("ClickableViewAccessibility")
    fun showHud(context: Context, service: VoiceService) {
        if (!hasOverlayPermission(context)) {
            Log.w(TAG, "Cannot show HUD without SYSTEM_ALERT_WINDOW permission")
            return
        }

        if (binding != null) {
            return
        }

        try {
            val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            windowManager = wm

            val inflater = LayoutInflater.from(context)
            val b = LayoutFloatingHudBinding.inflate(inflater)
            binding = b

            val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE
            }

            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                type,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                x = 24
                y = 260
            }
            layoutParams = params

            val displayMetrics = context.resources.displayMetrics
            val screenWidth = displayMetrics.widthPixels

            // Magnetic Drag and Drop physics
            var initialX = 0
            var initialY = 0
            var initialTouchX = 0f
            var initialTouchY = 0f

            b.llHudRoot.setOnTouchListener { _, event ->
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        initialX = params.x
                        initialY = params.y
                        initialTouchX = event.rawX
                        initialTouchY = event.rawY
                        b.llHudRoot.animate().scaleX(1.04f).scaleY(1.04f).setDuration(80).start()
                        true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val dx = (event.rawX - initialTouchX).toInt()
                        val dy = (event.rawY - initialTouchY).toInt()
                        params.x = initialX + dx
                        params.y = initialY + dy
                        try {
                            wm.updateViewLayout(b.root, params)
                        } catch (_: Exception) {}
                        true
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        b.llHudRoot.animate().scaleX(1.0f).scaleY(1.0f).setDuration(120).start()
                        val hudWidth = b.root.width.takeIf { it > 0 } ?: 220
                        val targetX = if (params.x + hudWidth / 2 < screenWidth / 2) {
                            16
                        } else {
                            screenWidth - hudWidth - 16
                        }
                        val startX = params.x
                        val animator = android.animation.ValueAnimator.ofInt(startX, targetX)
                        animator.duration = 240
                        animator.interpolator = android.view.animation.OvershootInterpolator(1.5f)
                        animator.addUpdateListener { va ->
                            params.x = va.animatedValue as Int
                            try {
                                wm.updateViewLayout(b.root, params)
                            } catch (_: Exception) {}
                        }
                        animator.start()
                        true
                    }
                    else -> false
                }
            }

            // Mic Toggle with smooth bounce
            com.gamervoice.app.util.AnimationHelper.attachPressAnimation(b.flHudMicButton) {
                service.toggleMicMode()
                val isPtt = service.isPttModeEnabled()
                updateMicState(isPtt)
            }

            // Dismiss Button with smooth bounce
            com.gamervoice.app.util.AnimationHelper.attachPressAnimation(b.btnDismissHud) {
                hideHud()
            }

            // Initial state
            updateMicState(service.isPttModeEnabled())

            wm.addView(b.root, params)
            Log.i(TAG, "Floating In-Game HUD displayed successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Error displaying floating HUD", e)
            binding = null
        }
    }

    fun isHudShowing(): Boolean = binding != null

    fun updateMicState(isMutedOrPtt: Boolean) {
        val b = binding ?: return
        isMuted = isMutedOrPtt
        mainHandler.post {
            val color = if (isMutedOrPtt) {
                ContextCompat.getColor(b.root.context, R.color.text_secondary)
            } else {
                ContextCompat.getColor(b.root.context, R.color.accent_green)
            }
            b.ivHudMicIcon.animate()
                .scaleX(0.75f)
                .scaleY(0.75f)
                .setDuration(70)
                .withEndAction {
                    b.ivHudMicIcon.setColorFilter(color)
                    b.ivHudMicIcon.animate()
                        .scaleX(1.0f)
                        .scaleY(1.0f)
                        .setDuration(120)
                        .setInterpolator(android.view.animation.OvershootInterpolator(2.0f))
                        .start()
                }
                .start()

            val isVip = PlanManager.isVip()
            val statusText = if (isMutedOrPtt) {
                "MIC MUTED"
            } else if (isVip) {
                "👑 VIP NOISE SHIELD"
            } else {
                "VOICE ACTIVE"
            }
            b.tvHudStatus.text = statusText
            b.tvHudStatus.setTextColor(color)
        }
    }

    fun updateSpeakingState(isSpeaking: Boolean) {
        val b = binding ?: return
        mainHandler.post {
            if (isSpeaking) {
                b.ivHudSpeakingGlow.visibility = View.VISIBLE
                b.ivHudSpeakingGlow.animate().alpha(1f).scaleX(1.2f).scaleY(1.2f).setDuration(120).start()
            } else {
                b.ivHudSpeakingGlow.animate().alpha(0f).scaleX(1.0f).scaleY(1.0f).setDuration(150).withEndAction {
                    b.ivHudSpeakingGlow.visibility = View.INVISIBLE
                }.start()
            }
        }
    }

    fun hideHud() {
        val b = binding ?: return
        val wm = windowManager ?: return

        try {
            wm.removeView(b.root)
            Log.i(TAG, "Floating HUD removed")
        } catch (_: Exception) {}

        binding = null
        windowManager = null
        layoutParams = null
    }
}
