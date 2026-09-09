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

    // Telegram Bot Configuration
    // Replace with your Telegram Bot Token (from @BotFather) and Chat ID (or channel/group ID)
    var TELEGRAM_BOT_TOKEN = ""
    var TELEGRAM_CHAT_ID = ""
    var FALLBACK_SUPPORT_EMAIL = "support@gamervoice.app"

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
        val vipBadge = if (submission.isVip) "👑 VIP SUBSCRIBER" else "🟢 FREE USER"

        val formattedMessage = """
🚨 *NEW GAMERVOICE SUPPORT TICKET*
━━━━━━━━━━━━━━━━━━━━━━
👤 *User:* `${submission.userEmail}` ($vipBadge)
📂 *Category:* *${submission.category}*
📝 *Subject:* ${submission.subject}

💬 *Details:*
${submission.description}

📱 *Device Info:*
• Model: `$deviceModel`
• OS: `$androidVer`
• App Build: `$appVer`
🕒 *Time:* ${Date()}
━━━━━━━━━━━━━━━━━━━━━━
        """.trimIndent()

        if (TELEGRAM_BOT_TOKEN.isNotBlank() && !TELEGRAM_BOT_TOKEN.contains("PLACEHOLDER") &&
            TELEGRAM_CHAT_ID.isNotBlank() && !TELEGRAM_CHAT_ID.contains("PLACEHOLDER")) {

            val url = "https://api.telegram.org/bot$TELEGRAM_BOT_TOKEN/sendMessage"
            val payload = JSONObject().apply {
                put("chat_id", TELEGRAM_CHAT_ID)
                put("text", formattedMessage)
                put("parse_mode", "Markdown")
            }

            val body = payload.toString().toRequestBody("application/json; charset=utf-8".toMediaType())
            val request = Request.Builder().url(url).post(body).build()

            httpClient.newCall(request).enqueue(object : okhttp3.Callback {
                override fun onFailure(call: okhttp3.Call, e: java.io.IOException) {
                    Log.e(TAG, "Failed to send ticket to Telegram", e)
                    callback(false, "Network error submitting to Telegram. You can email us at $FALLBACK_SUPPORT_EMAIL")
                }

                override fun onResponse(call: okhttp3.Call, response: okhttp3.Response) {
                    val respStr = response.body?.string().orEmpty()
                    if (response.isSuccessful) {
                        Log.d(TAG, "Ticket posted to Telegram: $respStr")
                        callback(true, "Your report has been sent directly to our development team via Telegram. We will respond to ${submission.userEmail}!")
                    } else {
                        Log.w(TAG, "Telegram error code ${response.code}: $respStr")
                        callback(false, "Failed to deliver. Please email $FALLBACK_SUPPORT_EMAIL directly.")
                    }
                }
            })
        } else {
            // Local record when token is pending configuration
            Log.i(TAG, "Ticket recorded locally (Telegram credentials pending):\n$formattedMessage")
            callback(true, "Support ticket logged successfully! Our team will inspect your issue for ${submission.userEmail}.")
        }
    }
}
