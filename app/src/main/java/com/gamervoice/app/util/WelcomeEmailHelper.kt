package com.gamervoice.app.util

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
    private val executor = Executors.newSingleThreadExecutor()

    fun sendWelcomeEmail(recipientEmail: String, recipientName: String) {
        if (recipientEmail.isBlank() || !recipientEmail.contains("@")) return

        executor.execute {
            try {
                val displayName = if (recipientName.isNotBlank()) recipientName else "Gamer"
                val subject = "Welcome to the GamerVoice squad! (A personal note from the team)"
                val messageContent = buildWelcomeLetter(displayName)

                Log.d(TAG, "Preparing welcome email for $recipientEmail...")

                if (SmtpConfig.SMTP_USERNAME.isNotBlank() && SmtpConfig.SMTP_PASSWORD.isNotBlank()) {
                    deliverViaSmtp(recipientEmail, subject, messageContent)
                    Log.i(TAG, "Welcome email successfully dispatched to $recipientEmail via SMTP!")
                } else {
                    Log.i(TAG, "SMTP credentials pending in SmtpConfig.kt. Welcome email prepared:\n$messageContent")
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

    private fun deliverViaSmtp(toEmail: String, subject: String, body: String) {
        val socketFactory = SSLSocketFactory.getDefault()
        val socket = socketFactory.createSocket(SmtpConfig.SMTP_HOST, SmtpConfig.SMTP_PORT) as SSLSocket
        socket.soTimeout = 15000

        val reader = BufferedReader(InputStreamReader(socket.inputStream))
        val writer = PrintWriter(OutputStreamWriter(socket.outputStream), true)

        fun readResponse(): String {
            val line = reader.readLine() ?: ""
            return line
        }

        fun sendCommand(cmd: String) {
            writer.print("$cmd\r\n")
            writer.flush()
        }

        readResponse() // Greeting 220

        sendCommand("EHLO localhost")
        while (true) {
            val l = readResponse()
            if (l.length >= 4 && l[3] == ' ') break
        }

        sendCommand("AUTH LOGIN")
        readResponse() // 334

        sendCommand(Base64.encodeToString(SmtpConfig.SMTP_USERNAME.toByteArray(), Base64.NO_WRAP))
        readResponse() // 334

        sendCommand(Base64.encodeToString(SmtpConfig.SMTP_PASSWORD.toByteArray(), Base64.NO_WRAP))
        val authResult = readResponse()
        if (!authResult.startsWith("235")) {
            throw RuntimeException("SMTP Authentication failed: $authResult")
        }

        val sender = if (SmtpConfig.SENDER_EMAIL.isNotBlank()) SmtpConfig.SENDER_EMAIL else SmtpConfig.SMTP_USERNAME
        sendCommand("MAIL FROM:<$sender>")
        readResponse()

        sendCommand("RCPT TO:<$toEmail>")
        readResponse()

        sendCommand("DATA")
        readResponse()

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
        readResponse()

        sendCommand("QUIT")
        socket.close()
    }
}
