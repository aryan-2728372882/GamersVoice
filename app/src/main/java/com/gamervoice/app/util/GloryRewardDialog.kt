package com.gamervoice.app.util

import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.app.Activity
import android.app.Dialog
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.view.View
import android.view.ViewGroup
import android.view.animation.AccelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.view.animation.LinearInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.Toast
import com.gamervoice.app.R
import com.gamervoice.app.databinding.DialogSeasonGloryRewardBinding

/**
 * Free Fire / Regional Esports Weapon Glory style Loot Crate Opening Ceremony
 * Triggered on Season Reset (31st day) for Top 3 recruiters.
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
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        dialog.window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        dialog.setCancelable(false)

        // 1. Continuous rotating sunburst god-rays
        val sunburstAnim = ObjectAnimator.ofFloat(binding.ivGlorySunburst, View.ROTATION, 0f, 360f).apply {
            duration = 18000L
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            start()
        }

        // Configure Crest & Text according to rank
        val crestRes: Int
        val rankTitle: String
        val subtitle: String
        when (rank) {
            1 -> {
                crestRes = R.drawable.ic_glory_crest_gold
                rankTitle = "🥇 REGIONAL CHAMPION"
                subtitle = "1st Place · Monthly Recruiter Victory"
            }
            2 -> {
                crestRes = R.drawable.ic_glory_crest_silver
                rankTitle = "🥈 ELITE RUNNER-UP"
                subtitle = "2nd Place · Monthly Recruiter Podium"
            }
            else -> {
                crestRes = R.drawable.ic_glory_crest_bronze
                rankTitle = "🥉 SQUAD BRONZE LEGEND"
                subtitle = "3rd Place · Monthly Recruiter Podium"
            }
        }
        binding.ivGloryCrest.setImageResource(crestRes)
        binding.tvGloryRankTitle.text = rankTitle
        binding.tvGlorySubtitle.text = subtitle
        binding.tvVipDaysBadge.text = "+$vipDays DAYS VIP UNLOCKED 👑"

        // 2. Initial Crate Drop Animation
        binding.llCrateStage.translationY = -600f
        binding.llCrateStage.alpha = 0f
        binding.llCrateStage.animate()
            .translationY(0f)
            .alpha(1f)
            .setDuration(600L)
            .setInterpolator(DecelerateInterpolator(2f))
            .withEndAction {
                // Heavy landing impact: haptic shake
                AnimationHelper.shakeView(binding.llCrateStage, 16f)
                try {
                    binding.root.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
                } catch (_: Exception) {}

                // Ambient breathing glow on tap prompt
                AnimationHelper.startAmbientPulse(binding.tvCratePrompt, 0.94f, 1.06f, 1200L)
            }
            .start()

        // 3. Interactive Crate Opening Burst
        var opened = false
        val openCrateAction = View.OnClickListener {
            if (opened) return@OnClickListener
            opened = true

            // Trigger strong haptic buzz
            try {
                binding.root.performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_TAP)
            } catch (_: Exception) {}

            // Crate violent shake before exploding open
            AnimationHelper.shakeView(binding.ivLootCrate, 22f)

            binding.llCrateStage.animate()
                .scaleX(1.25f)
                .scaleY(1.25f)
                .alpha(0f)
                .setDuration(350L)
                .setInterpolator(AccelerateInterpolator())
                .withEndAction {
                    binding.llCrateStage.visibility = View.GONE

                    // Phase 2: Glory Stage Emergence
                    binding.llRewardStage.visibility = View.VISIBLE
                    binding.llRewardStage.scaleX = 0.2f
                    binding.llRewardStage.scaleY = 0.2f
                    binding.llRewardStage.alpha = 0f

                    binding.llRewardStage.animate()
                        .scaleX(1.0f)
                        .scaleY(1.0f)
                        .alpha(1f)
                        .setDuration(650L)
                        .setInterpolator(OvershootInterpolator(2.2f))
                        .withEndAction {
                            AnimationHelper.popView(binding.ivGloryCrest, 1.15f)
                            AnimationHelper.startAmbientPulse(binding.btnClaimGlory, 0.97f, 1.04f, 1500L)
                        }
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

            // Dismiss animation
            binding.llRewardStage.animate()
                .scaleX(0.7f)
                .scaleY(0.7f)
                .alpha(0f)
                .setDuration(250L)
                .withEndAction {
                    try { dialog.dismiss() } catch (_: Exception) {}
                    onClaimed?.invoke()
                }
                .start()
        }

        dialog.show()
    }
}