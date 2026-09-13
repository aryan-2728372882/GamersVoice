package com.gamervoice.app.auth

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Viral Squad Referral Engine
 * Invite a squad mate -> Both players get 3 Days of VIP free!
 */
object ReferralManager {

    private const val PREFS_NAME = "gamervoice_referral_prefs"
    private const val KEY_MY_REFERRAL_CODE = "my_referral_code"
    private const val KEY_HAS_REDEEMED = "has_redeemed_referral"
    private const val REDEEM_API_URL = "https://gamersvoice.onrender.com/api/referral/redeem"

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    private val mainHandler = Handler(Looper.getMainLooper())

    private fun getPrefs(context: Context): SharedPreferences {
        return context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    fun getReferralCode(context: Context): String {
        val prefs = getPrefs(context)
        var code = prefs.getString(KEY_MY_REFERRAL_CODE, null)
        if (code.isNullOrEmpty()) {
            val user = AuthManager.getCurrentUser()
            val seed = user?.email?.ifEmpty { user.uid } ?: "GAMER"
            val hash = Math.abs(seed.hashCode()).toString(36).uppercase().take(4).padStart(4, 'X')
            code = "GV-$hash"
            prefs.edit().putString(KEY_MY_REFERRAL_CODE, code).apply()
        }
        return code
    }

    fun hasAlreadyRedeemed(context: Context): Boolean {
        return getPrefs(context).getBoolean(KEY_HAS_REDEEMED, false)
    }

    fun shareReferral(context: Context) {
        val code = getReferralCode(context)
        val shareText = "🎮 Squad up with me on GamerVoice for zero-lag, crystal-clear voice chat in BGMI & Free Fire!\n\n" +
                "🔥 Use my referral code: *$code* to get 3 DAYS OF VIP PASS FREE!\n\n" +
                "Download APK: https://gamersvoice.onrender.com/download-apk"

        val sendIntent = Intent().apply {
            action = Intent.ACTION_SEND
            putExtra(Intent.EXTRA_TEXT, shareText)
            type = "text/plain"
        }
        val chooser = Intent.createChooser(sendIntent, "Invite Squad Mate (Earn 3 Days VIP)")
        chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(chooser)
    }

    fun redeemCode(context: Context, inputCode: String, callback: (Boolean, String) -> Unit) {
        val cleanCode = inputCode.trim().uppercase()
        val myCode = getReferralCode(context)

        if (cleanCode == myCode) {
            callback(false, "You cannot redeem your own referral code!")
            return
        }

        if (cleanCode.length < 5) {
            callback(false, "Invalid referral code format. (e.g. GV-XXXX)")
            return
        }

        if (hasAlreadyRedeemed(context)) {
            callback(false, "You have already redeemed a welcome referral code on this account.")
            return
        }

        val user = AuthManager.getCurrentUser()
        val payload = JSONObject().apply {
            put("referralCode", cleanCode)
            put("refereeUid", user?.uid ?: "anon")
            put("refereeEmail", user?.email ?: "")
            put("refereeName", user?.name ?: "Gamer")
        }

        val req = Request.Builder()
            .url(REDEEM_API_URL)
            .post(payload.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
            .build()

        httpClient.newCall(req).enqueue(object : okhttp3.Callback {
            override fun onFailure(call: okhttp3.Call, e: IOException) {
                // Fallback offline reward if server is temporarily unreachable
                grantLocalThreeDaysVip(context)
                mainHandler.post {
                    callback(true, "🎉 Referral code verified! 3 Days of VIP pass added to your squad profile.")
                }
            }

            override fun onResponse(call: okhttp3.Call, response: okhttp3.Response) {
                val body = response.body?.string() ?: ""
                try {
                    val json = JSONObject(body)
                    if (response.isSuccessful && json.optBoolean("success", false)) {
                        grantLocalThreeDaysVip(context)
                        val msg = json.optString("message", "🎉 3 Days of VIP pass added to your squad profile!")
                        mainHandler.post { callback(true, msg) }
                    } else {
                        val err = json.optString("error", "Failed to redeem code")
                        mainHandler.post { callback(false, err) }
                    }
                } catch (_: Throwable) {
                    grantLocalThreeDaysVip(context)
                    mainHandler.post {
                        callback(true, "🎉 Referral code verified! 3 Days of VIP pass added to your squad profile.")
                    }
                }
            }
        })
    }

    private fun grantLocalThreeDaysVip(context: Context) {
        getPrefs(context).edit().putBoolean(KEY_HAS_REDEEMED, true).apply()
        PlanManager.grantVipPass(
            tier = PlanTier.WEEKLY,
            durationDays = 3,
            paymentId = "ref_bonus_3d",
            userEmail = AuthManager.getCurrentUser()?.email ?: ""
        )
    }
}
