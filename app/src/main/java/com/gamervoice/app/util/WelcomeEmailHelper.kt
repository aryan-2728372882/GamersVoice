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
     * If the email was already sent successfully or if called during regular sign-in, it will be skipped.
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
                val subject = "Welcome to the GamerVoice squad! (A personal note from the team)"
                val messageContent = buildWelcomeLetter(displayName)

                Log.d(TAG, "Preparing first-time welcome email for $cleanEmail...")

                val cleanUser = SmtpConfig.SMTP_USERNAME.trim()
                val cleanPass = SmtpConfig.SMTP_PASSWORD.replace(" ", "").trim()

                if (cleanUser.isNotBlank() && cleanPass.isNotBlank()) {
                    deliverViaSmtpWithFallback(cleanEmail, subject, messageContent, cleanUser, cleanPass)
                    // Mark as sent ONLY AFTER successful delivery!
                    markWelcomeAsSent(appContext, cleanEmail)
                    Log.i(TAG, "Welcome email successfully dispatched to $cleanEmail via SMTP!")
                } else {
                    Log.i(TAG, "SMTP credentials pending in SmtpConfig.kt. Welcome email letter prepared:\n$messageContent")
                }
            } catch (t: Throwable) {
                Log.e(TAG, "Notice: SMTP delivery failed for $cleanEmail: ${t.message}", t)
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
                    deliverViaSmtpWithFallback(cleanEmail, subject, messageContent, cleanUser, cleanPass)
                    Log.i(TAG, "Welcome email successfully dispatched to $cleanEmail via SMTP!")
                }
            } catch (t: Throwable) {
                Log.e(TAG, "Notice: SMTP delivery failed for $cleanEmail: ${t.message}", t)
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

    private fun deliverViaSmtpWithFallback(toEmail: String, subject: String, body: String, username: String, pass: String) {
        try {
            // Attempt 1: Direct SSL on Port 465
            deliverViaSmtpSsl(toEmail, subject, body, username, pass, SmtpConfig.SMTP_HOST, 465)
        } catch (e: Exception) {
            Log.w(TAG, "Port 465 SSL failed (${e.message}). Attempting fallback to Port 587 STARTTLS...")
            // Attempt 2: STARTTLS on Port 587
            deliverViaSmtpStartTls(toEmail, subject, body, username, pass, SmtpConfig.SMTP_HOST, 587)
        }
    }

    private fun deliverViaSmtpSsl(toEmail: String, subject: String, body: String, username: String, pass: String, host: String, port: Int) {
        val socketFactory = SSLSocketFactory.getDefault()
        val socket = socketFactory.createSocket(host, port) as SSLSocket
        socket.soTimeout = 15000
        socket.startHandshake()

        executeSmtpConversation(socket, toEmail, subject, body, username, pass)
    }

    private fun deliverViaSmtpStartTls(toEmail: String, subject: String, body: String, username: String, pass: String, host: String, port: Int) {
        val plainSocket = java.net.Socket(host, port)
        plainSocket.soTimeout = 15000

        val reader = BufferedReader(InputStreamReader(plainSocket.getInputStream(), Charsets.UTF_8))
        val writer = PrintWriter(OutputStreamWriter(plainSocket.getOutputStream(), Charsets.UTF_8), true)

        fun readResponse(): String {
            var lastLine = ""
            while (true) {
                val line = reader.readLine() ?: break
                lastLine = line
                if (line.length >= 4 && line[3] == '-') continue
                break
            }
            return lastLine
        }

        fun sendCmd(cmd: String) {
            writer.print("$cmd\r\n")
            writer.flush()
        }

        val greeting = readResponse()
        if (!greeting.startsWith("220")) throw RuntimeException("SMTP greeting failed: $greeting")

        sendCmd("EHLO [127.0.0.1]")
        readResponse()

        sendCmd("STARTTLS")
        val startTlsResp = readResponse()
        if (!startTlsResp.startsWith("220")) throw RuntimeException("STARTTLS rejected: $startTlsResp")

        val sslSocket = (SSLSocketFactory.getDefault() as SSLSocketFactory).createSocket(plainSocket, host, port, true) as SSLSocket
        sslSocket.soTimeout = 15000
        sslSocket.startHandshake()

        executeSmtpConversation(sslSocket, toEmail, subject, body, username, pass)
    }

    private fun executeSmtpConversation(
        socket: java.net.Socket,
        toEmail: String,
        subject: String,
        body: String,
        username: String,
        pass: String
    ) {
        val reader = BufferedReader(InputStreamReader(socket.getInputStream(), Charsets.UTF_8))
        val writer = PrintWriter(OutputStreamWriter(socket.getOutputStream(), Charsets.UTF_8), true)

        fun readResponse(): String {
            var lastLine = ""
            while (true) {
                val line = reader.readLine() ?: break
                lastLine = line
                if (line.length >= 4 && line[3] == '-') continue
                break
            }
            return lastLine
        }

        fun sendCmd(cmd: String) {
            writer.print("$cmd\r\n")
            writer.flush()
        }

        val greeting = readResponse()
        if (!greeting.startsWith("220")) {
            throw RuntimeException("SMTP Greeting failed: $greeting")
        }

        sendCmd("EHLO [127.0.0.1]")
        readResponse()

        sendCmd("AUTH LOGIN")
        val authPrompt = readResponse()
        if (!authPrompt.startsWith("334")) {
            throw RuntimeException("AUTH LOGIN rejected: $authPrompt")
        }

        sendCmd(Base64.encodeToString(username.toByteArray(), Base64.NO_WRAP))
        val userPrompt = readResponse()
        if (!userPrompt.startsWith("334")) {
            throw RuntimeException("SMTP Username rejected: $userPrompt")
        }

        sendCmd(Base64.encodeToString(pass.toByteArray(), Base64.NO_WRAP))
        val authResult = readResponse()
        if (!authResult.startsWith("235")) {
            throw RuntimeException("SMTP Authentication failed: $authResult")
        }

        val sender = if (SmtpConfig.SENDER_EMAIL.isNotBlank()) SmtpConfig.SENDER_EMAIL.trim() else username
        sendCmd("MAIL FROM:<$sender>")
        val mailFromResp = readResponse()
        if (!mailFromResp.startsWith("250")) {
            throw RuntimeException("MAIL FROM rejected: $mailFromResp")
        }

        sendCmd("RCPT TO:<$toEmail>")
        val rcptResp = readResponse()
        if (!rcptResp.startsWith("250")) {
            throw RuntimeException("RCPT TO rejected: $rcptResp")
        }

        sendCmd("DATA")
        val dataResp = readResponse()
        if (!dataResp.startsWith("354")) {
            throw RuntimeException("DATA command rejected: $dataResp")
        }

        // Send headers
        writer.print("From: \"${SmtpConfig.SENDER_NAME}\" <$sender>\r\n")
        writer.print("To: <$toEmail>\r\n")
        writer.print("Subject: $subject\r\n")
        writer.print("MIME-Version: 1.0\r\n")
        writer.print("Content-Type: text/plain; charset=UTF-8\r\n")
        writer.print("Content-Transfer-Encoding: 8bit\r\n")
        writer.print("\r\n")

        // Send body line by line with proper RFC 5321 dot-stuffing and CRLF
        val lines = body.split("\n")
        for (rawLine in lines) {
            var line = rawLine.trimEnd('\r')
            if (line.startsWith(".")) {
                line = ".$line"
            }
            writer.print("$line\r\n")
        }

        // End of DATA stream
        writer.print(".\r\n")
        writer.flush()

        val sendResult = readResponse()
        if (!sendResult.startsWith("250")) {
            throw RuntimeException("Message transmission rejected: $sendResult")
        }

        sendCmd("QUIT")
        try {
            socket.close()
        } catch (_: Exception) {}
    }
}
