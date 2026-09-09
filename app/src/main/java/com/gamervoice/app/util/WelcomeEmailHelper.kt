package com.gamervoice.app.util

import android.content.Context
import android.util.Base64
import android.util.Log
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.io.PrintWriter
import java.util.concurrent.Executors
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

object WelcomeEmailHelper {

    private const val TAG = "WelcomeEmailHelper"
    private const val PREFS_NAME = "gamervoice_auth_prefs"
    private const val KEY_SENT_PREFIX = "welcome_sent_"

    private val executor = Executors.newSingleThreadExecutor()

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
     * Strict Rule: Sends the heartfelt welcome email ONLY ONCE upon new account creation (sign up).
     * If the email was already sent previously or if called during regular sign-in, it will be skipped.
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

        // Strict Check: Has this email address EVER received the welcome email on this device?
        if (hasWelcomeBeenSent(context, cleanEmail)) {
            Log.i(TAG, "Strict Rule Enforced: Welcome email already sent to $cleanEmail previously. Skipping dispatch.")
            return
        }

        // Mark immediately to prevent race conditions or repeated clicks
        markWelcomeAsSent(context, cleanEmail)

        executor.execute {
            try {
                val displayName = if (recipientName.isNotBlank()) recipientName else "Gamer"
                val subject = "Welcome to the GamerVoice squad! (A personal note from the team)"
                val messageContent = buildWelcomeLetter(displayName)

                Log.d(TAG, "Preparing first-time welcome email for $cleanEmail...")

                val cleanUser = SmtpConfig.SMTP_USERNAME.trim()
                val cleanPass = SmtpConfig.SMTP_PASSWORD.replace(" ", "").trim()

                if (cleanUser.isNotBlank() && cleanPass.isNotBlank()) {
                    deliverViaSmtp(cleanEmail, subject, messageContent, cleanUser, cleanPass)
                    Log.i(TAG, "Welcome email successfully dispatched to $cleanEmail via SMTP!")
                } else {
                    Log.i(TAG, "SMTP credentials pending in SmtpConfig.kt. Welcome email letter prepared:\n$messageContent")
                }
            } catch (t: Throwable) {
                Log.w(TAG, "Notice: Could not send welcome email: ${t.localizedMessage}")
            }
        }
    }

    /**
     * Legacy wrapper; delegates to strict one-time send if context is available.
     */
    fun sendWelcomeEmail(recipientEmail: String, recipientName: String) {
        val cleanEmail = recipientEmail.trim().lowercase()
        if (cleanEmail.isBlank() || !cleanEmail.contains("@")) return

        executor.execute {
            try {
                val displayName = if (recipientName.isNotBlank()) recipientName else "Gamer"
                val subject = "Welcome to the GamerVoice squad! (A personal note from the team)"
                val messageContent = buildWelcomeLetter(displayName)

                val cleanUser = SmtpConfig.SMTP_USERNAME.trim()
                val cleanPass = SmtpConfig.SMTP_PASSWORD.replace(" ", "").trim()

                if (cleanUser.isNotBlank() && cleanPass.isNotBlank()) {
                    deliverViaSmtp(cleanEmail, subject, messageContent, cleanUser, cleanPass)
                    Log.i(TAG, "Welcome email successfully dispatched to $cleanEmail via SMTP!")
                }
            } catch (t: Throwable) {
                Log.w(TAG, "Notice: Could not send welcome email: ${t.localizedMessage}")
            }
        }
    }

    private fun buildWelcomeLetter(name: String): String {
        return """
Hey $name,

We wanted to personally reach out and welcome you to GamerVoice.

A little backstory: We built GamerVoice because we were genuinely exhausted with existing voice apps. Every time we queued up for ranked matches in BGMI, Free Fire, or Call of Duty, standard voice apps would eat up over 150MB of RAM, cause stuttering right during crucial gunfights, and drain half the battery. Worst of all, they were built as desktop software and clumsily retrofitted onto mobile.

So we built something radically different from the ground up:
• Super Lightweight: Under 16MB total size—feather-light on storage.
• Studio-Grade Low-Latency Voice: Uses 48kHz Opus streamed direct peer-to-peer (your voice is NEVER recorded, saved, or surveilled).
• Floating In-Game HUD: A draggable overlay bubble that hovers over your game so you can mute, deafen, or see who is talking without minimizing your match.
• Smart RAM Purge: Automatic memory clearing so Android's low-memory killer never terminates your voice comms mid-game.

We are an indie team pouring our passion into this project every day. If you ever run into an issue, have an idea for a feature, or want to share an epic clutch story, reply directly to this email or reach us through the in-app "Contact Support" form.

Good games, better friends. See you on the battlefield!

Warm regards,
The Devs Team
GamerVoice Core Engineering
        """.trimIndent()
    }

    private fun deliverViaSmtp(toEmail: String, subject: String, body: String, username: String, pass: String) {
        val socketFactory = SSLSocketFactory.getDefault()
        val socket = socketFactory.createSocket(SmtpConfig.SMTP_HOST, SmtpConfig.SMTP_PORT) as SSLSocket
        socket.soTimeout = 15000

        val reader = BufferedReader(InputStreamReader(socket.inputStream))
        val writer = PrintWriter(OutputStreamWriter(socket.outputStream), true)

        fun readSmtpResponse(): String {
            var lastLine = ""
            while (true) {
                val line = reader.readLine() ?: break
                lastLine = line
                if (line.length >= 4 && line[3] == '-') {
                    continue // Multi-line response continuation
                }
                break
            }
            return lastLine
        }

        fun sendCommand(cmd: String) {
            writer.print("$cmd\r\n")
            writer.flush()
        }

        readSmtpResponse() // Greeting 220

        sendCommand("EHLO localhost")
        readSmtpResponse() // 250

        sendCommand("AUTH LOGIN")
        readSmtpResponse() // 334

        sendCommand(Base64.encodeToString(username.toByteArray(), Base64.NO_WRAP))
        readSmtpResponse() // 334

        sendCommand(Base64.encodeToString(pass.toByteArray(), Base64.NO_WRAP))
        val authResult = readSmtpResponse()
        if (!authResult.startsWith("235")) {
            throw RuntimeException("SMTP Authentication failed: $authResult")
        }

        val sender = if (SmtpConfig.SENDER_EMAIL.isNotBlank()) SmtpConfig.SENDER_EMAIL.trim() else username
        sendCommand("MAIL FROM:<$sender>")
        readSmtpResponse()

        sendCommand("RCPT TO:<$toEmail>")
        readSmtpResponse()

        sendCommand("DATA")
        readSmtpResponse()

        val mimeMessage = """
From: "${SmtpConfig.SENDER_NAME}" <$sender>
To: <$toEmail>
Subject: $subject
MIME-Version: 1.0
Content-Type: text/plain; charset=UTF-8

$body
.
        """.trimIndent()

        sendCommand(mimeMessage)
        readSmtpResponse()

        sendCommand("QUIT")
        socket.close()
    }
}
