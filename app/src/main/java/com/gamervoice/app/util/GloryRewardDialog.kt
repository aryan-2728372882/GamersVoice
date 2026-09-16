package com.gamervoice.app.util

import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.app.Activity
import android.app.Dialog
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.view.View
import android.view.ViewGroup
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.AccelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.view.animation.LinearInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.Toast
import com.gamervoice.app.R
import com.gamervoice.app.databinding.DialogSeasonGloryRewardBinding

/**
 * AAA Free Fire Grandmaster Regional Weapon Glory style Season Loot Crate Ceremony.
 * Triggers full-screen immersive cutscene for Top 3 monthly recruiters.
 */
object GloryRewardDialog {

    fun showGloryCeremony(
        activity: Activity,
        rank: Int,
        vipDays: Int,
        recruitsCount: Int = 0,
        onClaimed: (() -> Unit)? = null
    ) {
        val dialog = Dialog(activity, android.R.style.Theme_Black_NoTitleBar_Fullscreen)
        val binding = DialogSeasonGloryRewardBinding.inflate(activity.layoutInflater)
        dialog.setContentView(binding.root)
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.BLACK))
        dialog.window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        dialog.setCancelable(false)

        // 1. Continuous rotating celestial sunburst god-rays
        val sunburstAnim = ObjectAnimator.ofFloat(binding.ivGlorySunburst, View.ROTATION, 0f, 360f).apply {
            duration = 16000L
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            start()
        }

        // Configure 3D Crest & Rank Badges
        val crestRes: Int
        val rankTitle: String
        val subtitle: String
        when (rank) {
            1 -> {
                crestRes = R.drawable.ff_glory_crest_gold
                rankTitle = "★ REGIONAL WEAPON GLORY ★"
                subtitle = "RANK 1 · GRANDMASTER CHAMPION"
            }
            2 -> {
                crestRes = R.drawable.ff_glory_crest_silver
                rankTitle = "★ REGIONAL WEAPON GLORY ★"
                subtitle = "RANK 2 · MASTER CHAMPION"
            }
            else -> {
                crestRes = R.drawable.ff_glory_crest_bronze
                rankTitle = "★ HEROIC WEAPON GLORY ★"
                subtitle = "RANK 3 · HEROIC PODIUM"
            }
        }
        binding.ivGloryCrest.setImageResource(crestRes)
        binding.tvGloryRankTitle.text = rankTitle
        binding.tvGlorySubtitle.text = subtitle
        binding.tvVipDaysBadge.text = "+$vipDays DAYS VIP UNLOCKED 👑"

        // 2. Initial Crate Slam & Ground Impact
        binding.llCrateContainer.translationY = -800f
        binding.llCrateContainer.alpha = 0f
        var hoverAnim: ObjectAnimator? = null

        binding.llCrateContainer.animate()
            .translationY(0f)
            .alpha(1f)
            .setDuration(650L)
            .setInterpolator(DecelerateInterpolator(2.2f))
            .withEndAction {
                // Heavy landing impact: screen shake & haptic feedback
                AnimationHelper.shakeView(binding.llCrateContainer, 18f)
                try {
                    binding.root.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
                } catch (_: Exception) {}

                // Ambient floating hover animation (bobbing up and down gently)
                hoverAnim = ObjectAnimator.ofFloat(binding.ivLootCrate, View.TRANSLATION_Y, 0f, -14f, 0f).apply {
                    duration = 1800L
                    repeatCount = ValueAnimator.INFINITE
                    interpolator = AccelerateDecelerateInterpolator()
                    start()
                }

                // Ambient pulsing glow on prompt
                AnimationHelper.startAmbientPulse(binding.tvCratePrompt, 0.93f, 1.07f, 1100L)
            }
            .start()

        // 3. Interactive Crate Opening Burst
        var opened = false
        val openCrateAction = View.OnClickListener {
            if (opened) return@OnClickListener
            opened = true
            hoverAnim?.cancel()

            // Strong impact haptic feedback
            try {
                binding.root.performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_TAP)
            } catch (_: Exception) {}

            // Screen trauma shake
            AnimationHelper.shakeView(binding.rlCrateStage, 24f)

            // Blinding white flash overlay
            binding.vFlashOverlay.alpha = 0f
            binding.vFlashOverlay.animate()
                .alpha(0.85f)
                .setDuration(90L)
                .withEndAction {
                    binding.vFlashOverlay.animate()
                        .alpha(0f)
                        .setDuration(220L)
                        .start()
                }
                .start()

            // Reveal Exploding Supply Crate Burst
            binding.rlCrateStage.visibility = View.GONE
            binding.ivCrateBurst.visibility = View.VISIBLE
            binding.ivCrateBurst.scaleX = 0.8f
            binding.ivCrateBurst.scaleY = 0.8f
            binding.ivCrateBurst.alpha = 1f

            // Accelerated spinning god-rays for explosive celestial victory
            sunburstAnim.duration = 4500L

            binding.ivCrateBurst.animate()
                .scaleX(1.35f)
                .scaleY(1.35f)
                .alpha(0f)
                .setDuration(400L)
                .setInterpolator(AccelerateInterpolator())
                .withEndAction {
                    binding.ivCrateBurst.visibility = View.GONE

                    // Phase 2: Glory Victory Screen Emergence
                    binding.rlRewardStage.visibility = View.VISIBLE
                    binding.ivGloryCrest.scaleX = 0.15f
                    binding.ivGloryCrest.scaleY = 0.15f
                    binding.ivGloryCrest.translationY = 250f
                    binding.ivGloryCrest.alpha = 0f

                    binding.llTopRibbon.alpha = 0f
                    binding.llTopRibbon.translationY = -60f

                    // Shoot 3D Crest up with grand overshoot
                    binding.ivGloryCrest.animate()
                        .scaleX(1.0f)
                        .scaleY(1.0f)
                        .translationY(0f)
                        .alpha(1f)
                        .setDuration(750L)
                        .setInterpolator(OvershootInterpolator(2.0f))
                        .withEndAction {
                            AnimationHelper.startAmbientPulse(binding.btnClaimGlory, 0.96f, 1.04f, 1300L)
                        }
                        .start()

                    // Slide down top title ribbon
                    binding.llTopRibbon.animate()
                        .alpha(1f)
                        .translationY(0f)
                        .setDuration(500L)
                        .setInterpolator(DecelerateInterpolator())
                        .start()
                }
                .start()
        }

        binding.ivLootCrate.setOnClickListener(openCrateAction)
        binding.tvCratePrompt.setOnClickListener(openCrateAction)

        // 4. Claim & Equip Button Action
        AnimationHelper.attachPressAnimation(binding.btnClaimGlory) {
            sunburstAnim.cancel()
            try {
                binding.root.performHapticFeedback(android.view.HapticFeedbackConstants.CONFIRM)
            } catch (_: Exception) {}

            Toast.makeText(activity, "🎉 $vipDays Days VIP Pass equipped! You are the season champion!", Toast.LENGTH_LONG).show()

            binding.rlRewardStage.animate()
                .scaleX(0.75f)
                .scaleY(0.75f)
                .alpha(0f)
                .setDuration(260L)
                .withEndAction {
                    try { dialog.dismiss() } catch (_: Exception) {}
                    onClaimed?.invoke()
                }
                .start()
        }

        dialog.show()
    }
}
