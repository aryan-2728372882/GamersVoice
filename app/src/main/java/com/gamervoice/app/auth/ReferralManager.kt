package com.gamervoice.app.auth

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import android.util.Log
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
 * Cryptographically secured with Firebase ID Token & server-side Firestore state.
 */
object ReferralManager {

    private const val TAG = "ReferralManager"
    private const val PREFS_NAME = "gamervoice_referral_prefs"
    private const val KEY_MY_REFERRAL_CODE = "my_referral_code"
    private const val KEY_HAS_REDEEMED = "has_redeemed_referral"
    private const val REDEEM_API_URL = "https://gamersvoice.onrender.com/api/referral/redeem"
    private const val REGISTER_API_URL = "https://gamersvoice.onrender.com/api/referral/register-code"

    private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    private val mainHandler = Handler(Looper.getMainLooper())

    private fun getPrefs(context: Context): SharedPreferences {
        return context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    fun getReferralCode(context: Context): String {
        val user = AuthManager.getCurrentUser()
        if (user == null || user.uid.isEmpty()) {
            return "GV-DEMO"
        }
        val userKey = "${KEY_MY_REFERRAL_CODE}_${user.uid}"
        val prefs = getPrefs(context)
        var code = prefs.getString(userKey, null)
        if (code.isNullOrEmpty()) {
            val seed = user.email.ifEmpty { user.uid }
            val hash = Math.abs(seed.hashCode()).toString(36).uppercase().take(4).padStart(4, 'X')
            code = "GV-$hash"
            prefs.edit().putString(userKey, code).apply()
        }
        // Register code on server in background if user is logged in
        registerCodeWithServer(context, code)
        return code
    }

    fun clearCache(context: Context) {
        try {
            getPrefs(context).edit().clear().apply()
            Log.i(TAG, "Cleared referral code cache")
        } catch (e: Exception) {
            Log.w(TAG, "Error clearing referral cache", e)
        }
    }

    fun registerCodeWithServer(context: Context, code: String? = null) {
        val finalCode = code ?: getReferralCode(context)
        val user = AuthManager.getCurrentUser() ?: return

        AuthManager.getValidIdToken { token ->
            if (token.isNullOrEmpty()) return@getValidIdToken
            try {
                val payload = JSONObject().apply {
                    put("referralCode", finalCode)
                }
                val req = Request.Builder()
                    .url(REGISTER_API_URL)
                    .addHeader("Authorization", "Bearer $token")
                    .post(payload.toString().toRequestBody(JSON_MEDIA))
                    .build()

                httpClient.newCall(req).enqueue(object : okhttp3.Callback {
                    override fun onFailure(call: okhttp3.Call, e: IOException) {
                        Log.w(TAG, "Failed registering referral code with server: ${e.message}")
                    }

                    override fun onResponse(call: okhttp3.Call, response: okhttp3.Response) {
                        response.close()
                    }
                })
            } catch (t: Throwable) {
                Log.w(TAG, "Error initiating referral code registration", t)
            }
        }
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

    fun shareReferralCode(context: Context) {
        shareReferral(context)
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
        if (user == null) {
            callback(false, "Please sign in with Google or Email before redeeming referral rewards.")
            return
        }

        AuthManager.getValidIdToken { token ->
            if (token.isNullOrEmpty()) {
                mainHandler.post {
                    callback(false, "Authentication token expired. Please re-sign in.")
                }
                return@getValidIdToken
            }

            val payload = JSONObject().apply {
                put("referralCode", cleanCode)
            }

            val req = Request.Builder()
                .url(REDEEM_API_URL)
                .addHeader("Authorization", "Bearer $token")
                .post(payload.toString().toRequestBody(JSON_MEDIA))
                .build()

            httpClient.newCall(req).enqueue(object : okhttp3.Callback {
                override fun onFailure(call: okhttp3.Call, e: IOException) {
                    // Strictly NO local VIP grant on failure!
                    mainHandler.post {
                        callback(false, "Network error: Unable to verify referral with server. Please try again.")
                    }
                }

                override fun onResponse(call: okhttp3.Call, response: okhttp3.Response) {
                    val body = response.body?.string() ?: ""
                    response.close()
                    try {
                        val json = JSONObject(body)
                        if (response.isSuccessful && json.optBoolean("success", false)) {
                            getPrefs(context).edit().putBoolean(KEY_HAS_REDEEMED, true).apply()
                            // Enforce Cloud Firestore state locally to sync the server-granted VIP timestamps
                            PlanManager.enforceValidPaidStatus {
                                val msg = json.optString("message", "🎉 3 Days of VIP pass added to your squad profile!")
                                mainHandler.post { callback(true, msg) }
                            }
                        } else {
                            val err = json.optString("error", "Failed to redeem code")
                            mainHandler.post { callback(false, err) }
                        }
                    } catch (_: Throwable) {
                        // Strictly NO local VIP grant on parse error!
                        mainHandler.post {
                            callback(false, "Verification error from server. Please try again later.")
                        }
                    }
                }
            })
        }
    }
}
