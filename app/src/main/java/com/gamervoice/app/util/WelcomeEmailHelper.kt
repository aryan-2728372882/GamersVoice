package com.gamervoice.app.util

import android.content.Context
import android.util.Log
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

object WelcomeEmailHelper {

    private const val TAG = "WelcomeEmailHelper"
    private const val PREFS_NAME = "gamervoice_auth_prefs"
    private const val KEY_SENT_PREFIX = "welcome_sent_"

    private val executor = Executors.newSingleThreadExecutor()
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    /**
     * Checks whether a welcome email has already been dispatched to this email address.
     */
    fun hasWelcomeBeenSent(context: Context, email: String): Boolean {
        val cleanEmail = email.trim().lowercase()
        if (cleanEmail.isBlank()) return true
        val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getBoolean(KEY_SENT_PREFIX + cleanEmail, false)
    }

    /**
     * Persistently marks that a welcome email has been dispatched to this email.
     */
    fun markWelcomeAsSent(context: Context, email: String) {
        val cleanEmail = email.trim().lowercase()
        if (cleanEmail.isBlank()) return
        val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putBoolean(KEY_SENT_PREFIX + cleanEmail, true).apply()
    }

    /**
     * Resets the welcome email status for a specific email address (useful for retrying if credentials failed).
     */
    fun resetWelcomeSentStatus(context: Context, email: String) {
        val cleanEmail = email.trim().lowercase()
        if (cleanEmail.isBlank()) return
        val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().remove(KEY_SENT_PREFIX + cleanEmail).apply()
        Log.i(TAG, "Reset welcome email sent flag for $cleanEmail")
    }

    /**
     * Strict Rule: Sends the heartfelt welcome email ONLY ONCE upon new account creation (sign up).
     * Dispatches securely via the server relay so credentials are NEVER baked into the APK.
     */
    fun sendWelcomeEmailOnce(
        context: Context,
        recipientEmail: String,
        recipientName: String,
        uid: String? = null
    ) {
        val cleanEmail = recipientEmail.trim().lowercase()
        if (cleanEmail.isBlank() || !cleanEmail.contains("@")) {
            Log.w(TAG, "Cannot send welcome email: Invalid address '$recipientEmail'")
            return
        }

        val appContext = context.applicationContext

        // Strict Check: Has this email address EVER received the welcome email on this device?
        if (hasWelcomeBeenSent(appContext, cleanEmail)) {
            Log.i(TAG, "Strict Rule Enforced: Welcome email already sent to $cleanEmail previously. Skipping dispatch.")
            return
        }

        executor.execute {
            try {
                val displayName = if (recipientName.isNotBlank()) recipientName else "Gamer"
                Log.d(TAG, "Dispatching first-time welcome email for $cleanEmail via secure server relay...")

                val json = JSONObject().apply {
                    put("email", cleanEmail)
                    put("name", displayName)
                }

                val body = json.toString().toRequestBody("application/json; charset=utf-8".toMediaType())
                val request = Request.Builder()
                    .url(SmtpConfig.EMAIL_RELAY_URL)
                    .post(body)
                    .build()

                httpClient.newCall(request).enqueue(object : okhttp3.Callback {
                    override fun onFailure(call: okhttp3.Call, e: IOException) {
                        Log.w(TAG, "Server email relay connection failed: ${e.message}")
                    }

                    override fun onResponse(call: okhttp3.Call, response: okhttp3.Response) {
                        response.use {
                            if (response.isSuccessful) {
                                markWelcomeAsSent(appContext, cleanEmail)
                                Log.i(TAG, "Welcome email successfully dispatched to $cleanEmail via secure server relay!")
                            } else {
                                Log.w(TAG, "Server email relay returned status: ${response.code}")
                            }
                        }
                    }
                })
            } catch (t: Throwable) {
                Log.e(TAG, "Notice: Email relay failed for $cleanEmail: ${t.message}", t)
            }
        }
    }

    /**
     * Legacy wrapper; delegates to server relay if context is available.
     */
    fun sendWelcomeEmail(recipientEmail: String, recipientName: String) {
        val cleanEmail = recipientEmail.trim().lowercase()
        if (cleanEmail.isBlank() || !cleanEmail.contains("@")) return

        executor.execute {
            try {
                val displayName = if (recipientName.isNotBlank()) recipientName else "Gamer"
                val json = JSONObject().apply {
                    put("email", cleanEmail)
                    put("name", displayName)
                }
                val body = json.toString().toRequestBody("application/json; charset=utf-8".toMediaType())
                val request = Request.Builder()
                    .url(SmtpConfig.EMAIL_RELAY_URL)
                    .post(body)
                    .build()

                httpClient.newCall(request).enqueue(object : okhttp3.Callback {
                    override fun onFailure(call: okhttp3.Call, e: IOException) {
                        Log.w(TAG, "Email relay call failed: ${e.message}")
                    }
                    override fun onResponse(call: okhttp3.Call, response: okhttp3.Response) {
                        response.close()
                    }
                })
            } catch (t: Throwable) {
                Log.e(TAG, "Email dispatch error: ${t.message}", t)
            }
        }
    }
}
