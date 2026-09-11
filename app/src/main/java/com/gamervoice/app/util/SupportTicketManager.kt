package com.gamervoice.app.util

import android.content.Context
import android.os.Build
import android.util.Log
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.Date
import java.util.concurrent.TimeUnit

object SupportTicketManager {

    private const val TAG = "SupportTicketManager"
    var FALLBACK_SUPPORT_EMAIL = "supportgamersvoice@gmail.com"

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    data class TicketSubmission(
        val userEmail: String,
        val category: String,
        val subject: String,
        val description: String,
        val isVip: Boolean
    )

    fun submitTicket(
        context: Context,
        submission: TicketSubmission,
        callback: (success: Boolean, message: String) -> Unit
    ) {
        val deviceModel = "${Build.MANUFACTURER} ${Build.MODEL}"
        val androidVer = "Android ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})"
        val appVer = "1.0.0-beta"

        val payload = JSONObject().apply {
            put("category", submission.category)
            put("subject", submission.subject)
            put("description", submission.description)
            put("userEmail", submission.userEmail)
            put("isVip", submission.isVip)
            put("deviceModel", deviceModel)
            put("osVersion", androidVer)
            put("appVersion", appVer)
        }

        val body = payload.toString().toRequestBody("application/json; charset=utf-8".toMediaType())
        val request = Request.Builder()
            .url(SmtpConfig.SUPPORT_RELAY_URL)
            .post(body)
            .build()

        httpClient.newCall(request).enqueue(object : okhttp3.Callback {
            override fun onFailure(call: okhttp3.Call, e: java.io.IOException) {
                Log.e(TAG, "Failed to send ticket to server relay", e)
                callback(false, "Network connection error. You can also email us at $FALLBACK_SUPPORT_EMAIL")
            }

            override fun onResponse(call: okhttp3.Call, response: okhttp3.Response) {
                response.use {
                    if (response.isSuccessful) {
                        Log.d(TAG, "Ticket posted to server relay successfully")
                        callback(true, "Your report has been submitted to the engineering team. We will contact you at ${submission.userEmail}!")
                    } else {
                        Log.w(TAG, "Server error code ${response.code}")
                        callback(false, "Failed to deliver. Please email $FALLBACK_SUPPORT_EMAIL directly.")
                    }
                }
            }
        })
    }
}
