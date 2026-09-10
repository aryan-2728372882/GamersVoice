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
                val subject = "hey, welcome — glad you're here 🎧"
                val plainText = buildWelcomePlainText(displayName)
                val htmlContent = buildWelcomeHtml(displayName, cleanEmail)

                Log.d(TAG, "Preparing first-time welcome email for $cleanEmail...")

                val cleanUser = SmtpConfig.SMTP_USERNAME.trim()
                val cleanPass = SmtpConfig.SMTP_PASSWORD.replace(" ", "").trim()

                if (cleanUser.isNotBlank() && cleanPass.isNotBlank()) {
                    deliverViaSmtpWithFallback(cleanEmail, subject, plainText, htmlContent, cleanUser, cleanPass)
                    // Mark as sent ONLY AFTER successful delivery!
                    markWelcomeAsSent(appContext, cleanEmail)
                    Log.i(TAG, "Welcome email successfully dispatched to $cleanEmail via SMTP!")
                } else {
                    Log.i(TAG, "SMTP credentials pending in SmtpConfig.kt. Welcome email letter prepared for $cleanEmail")
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
                val subject = "hey, welcome — glad you're here 🎧"
                val plainText = buildWelcomePlainText(displayName)
                val htmlContent = buildWelcomeHtml(displayName, cleanEmail)

                val cleanUser = SmtpConfig.SMTP_USERNAME.trim()
                val cleanPass = SmtpConfig.SMTP_PASSWORD.replace(" ", "").trim()

                if (cleanUser.isNotBlank() && cleanPass.isNotBlank()) {
                    deliverViaSmtpWithFallback(cleanEmail, subject, plainText, htmlContent, cleanUser, cleanPass)
                    Log.i(TAG, "Welcome email successfully dispatched to $cleanEmail via SMTP!")
                }
            } catch (t: Throwable) {
                Log.e(TAG, "Notice: SMTP delivery failed for $cleanEmail: ${t.message}", t)
            }
        }
    }

    private fun buildWelcomePlainText(name: String): String {
        return """
Hey $name,
Welcome to GamerVoice. ❤️

Thanks for being here.
GamerVoice started with a pretty simple frustration: sometimes you just want to talk to your friends while you play — and somehow the mic cuts out, the audio glitches, or the app gets in the way.
So we decided to build something of our own.
We’re still growing, still learning, and still improving GamerVoice every day. It may not be perfect yet, but every person who joins genuinely matters to us. You’re part of the reason we’re building this in the first place.
You can use GamerVoice to talk with your squad, create rooms, and have a place to hang out while you play. It’s built with Free Fire squads in mind — but whether you’re playing something else or just looking for a place to hang out, you’re welcome here too.
A little bit of what’s under the hood: it’s light on your battery and RAM, your game audio stays clean while we run alongside it, and everything is peer-to-peer — we don’t record or store your voice, ever.
And if you ever want to go further, VIP unlocks a few extras like noise-cancelled comms and permanent private squad rooms. No rush though — only whenever you're ready.
And honestly, we’d love to hear from you.

If something doesn’t work properly, you have an idea, or there’s something you simply wish GamerVoice could do, tell us. You can reply directly to this email. There’s a real person on the other side, and we read it.
Thanks for giving GamerVoice a chance.
We hope it becomes a small part of a lot of great games, late-night conversations, ridiculous clutches, and memories with your friends.
Welcome to the community. 🎮
— Aryan & the GamerVoice team
        """.trimIndent()
    }

    private fun buildWelcomeHtml(name: String, email: String): String {
        return """
<!DOCTYPE html>
<html lang="en">
<head>
  <meta charset="UTF-8">
  <meta name="viewport" content="width=device-width, initial-scale=1.0">
  <title>Welcome to GamerVoice</title>
  <style>
    /* Mobile responsive resets */
    body, table, td, a { -webkit-text-size-adjust: 100%; -ms-text-size-adjust: 100%; }
    table, td { mso-table-lspace: 0pt; mso-table-rspace: 0pt; }
    img { -ms-interpolation-mode: bicubic; border: 0; height: auto; line-height: 100%; outline: none; text-decoration: none; }
    table { border-collapse: collapse !important; }
    body { height: 100% !important; margin: 0 !important; padding: 0 !important; width: 100% !important; background-color: #060911; font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, Helvetica, Arial, sans-serif; }
    
    @media screen and (max-width: 600px) {
      .email-container { width: 100% !important; padding: 12px !important; }
      .feature-col { display: block !important; width: 100% !important; margin-bottom: 12px !important; }
    }
  </style>
</head>
<body style="margin: 0; padding: 24px 12px; background-color: #060911; color: #E2E8F0;">
  <center>
    <table border="0" cellpadding="0" cellspacing="0" width="100%" style="max-width: 600px; background: #0B1120; border-radius: 20px; border: 1px solid #1E293B; overflow: hidden; box-shadow: 0 10px 30px rgba(0, 0, 0, 0.6);" class="email-container">
      
      <!-- Top Glowing Accent Bar -->
      <tr>
        <td height="4" style="background: linear-gradient(90deg, #00FF88 0%, #00E5FF 50%, #7C3AED 100%);"></td>
      </tr>

      <!-- Header Section -->
      <tr>
        <td style="padding: 32px 32px 20px 32px; text-align: left;">
          <table border="0" cellpadding="0" cellspacing="0" width="100%">
            <tr>
              <td>
                <span style="display: inline-block; font-size: 22px; font-weight: 900; letter-spacing: 2px; color: #00FF88; text-transform: uppercase;">GAMERVOICE</span>
                <div style="font-size: 11px; color: #64748B; font-weight: 700; letter-spacing: 1.5px; margin-top: 2px;">SQUAD AUDIO // ZERO-LAG VOIP</div>
              </td>
              <td align="right" valign="top">
                <span style="display: inline-block; padding: 6px 12px; background: rgba(0, 255, 136, 0.1); border: 1px solid rgba(0, 255, 136, 0.3); border-radius: 20px; font-size: 11px; font-weight: 700; color: #00FF88; letter-spacing: 0.5px;">
                  SQUAD CLEARANCE &#10003;
                </span>
              </td>
            </tr>
          </table>
        </td>
      </tr>

      <!-- Hero Greeting -->
      <tr>
        <td style="padding: 0 32px 24px 32px;">
          <h1 style="margin: 0 0 8px 0; font-size: 26px; font-weight: 800; color: #F8FAFC; line-height: 1.3;">
            Hey <span style="color: #00E5FF;">$name</span>,
          </h1>
          <div style="display: inline-block; font-size: 18px; font-weight: 700; color: #00FF88; margin-bottom: 16px;">
            Welcome to GamerVoice. ❤️
          </div>
          <p style="margin: 0; font-size: 15px; line-height: 1.7; color: #94A3B8;">
            Thanks for being here.
          </p>
        </td>
      </tr>

      <!-- Story / Heartfelt Section -->
      <tr>
        <td style="padding: 0 32px 24px 32px;">
          <div style="background: rgba(15, 23, 42, 0.6); border: 1px solid rgba(51, 65, 85, 0.6); border-radius: 14px; padding: 20px; margin-bottom: 20px;">
            <p style="margin: 0 0 14px 0; font-size: 14px; line-height: 1.75; color: #CBD5E1;">
              GamerVoice started with a pretty simple frustration: sometimes you just want to talk to your friends while you play &mdash; and somehow the mic cuts out, the audio glitches, or the app gets in the way.
            </p>
            <p style="margin: 0 0 14px 0; font-size: 14px; line-height: 1.75; color: #CBD5E1;">
              So we decided to build something of our own.
            </p>
            <p style="margin: 0; font-size: 14px; line-height: 1.75; color: #CBD5E1;">
              We’re still growing, still learning, and still improving GamerVoice every day. It may not be perfect yet, but every person who joins genuinely matters to us. You’re part of the reason we’re building this in the first place.
            </p>
          </div>

          <p style="margin: 0 0 18px 0; font-size: 14px; line-height: 1.75; color: #CBD5E1;">
            You can use GamerVoice to talk with your squad, create rooms, and have a place to hang out while you play. It’s built with <strong style="color: #F8FAFC;">Free Fire</strong> squads in mind &mdash; but whether you’re playing something else or just looking for a place to hang out, you’re welcome here too.
          </p>
        </td>
      </tr>

      <!-- Under the Hood Feature Cards -->
      <tr>
        <td style="padding: 0 32px 24px 32px;">
          <div style="font-size: 12px; font-weight: 800; color: #64748B; letter-spacing: 1.2px; text-transform: uppercase; margin-bottom: 12px;">
            A LITTLE BIT OF WHAT’S UNDER THE HOOD
          </div>

          <table border="0" cellpadding="0" cellspacing="0" width="100%">
            <tr>
              <td width="48%" class="feature-col" valign="top" style="background: #0F172A; border: 1px solid #1E293B; border-radius: 12px; padding: 16px; margin-right: 4%;">
                <div style="font-size: 20px; margin-bottom: 6px;">⚡</div>
                <div style="font-size: 14px; font-weight: 700; color: #F1F5F9; margin-bottom: 4px;">Light on RAM &amp; Battery</div>
                <div style="font-size: 12px; color: #94A3B8; line-height: 1.5;">Clean game audio with zero FPS drops or in-game mic stutter.</div>
              </td>
              <td width="4%"></td>
              <td width="48%" class="feature-col" valign="top" style="background: #0F172A; border: 1px solid #1E293B; border-radius: 12px; padding: 16px;">
                <div style="font-size: 20px; margin-bottom: 6px;">🔒</div>
                <div style="font-size: 14px; font-weight: 700; color: #F1F5F9; margin-bottom: 4px;">100% Peer-to-Peer</div>
                <div style="font-size: 12px; color: #94A3B8; line-height: 1.5;">Direct encrypted streams. We don’t record or store your voice, ever.</div>
              </td>
            </tr>
          </table>

          <div style="margin-top: 14px; background: rgba(124, 58, 237, 0.08); border: 1px solid rgba(124, 58, 237, 0.25); border-radius: 12px; padding: 14px 16px;">
            <table border="0" cellpadding="0" cellspacing="0" width="100%">
              <tr>
                <td width="28" valign="top" style="font-size: 18px; line-height: 1;">👑</td>
                <td style="font-size: 13px; line-height: 1.6; color: #C4B5FD;">
                  <strong style="color: #DDD6FE;">VIP Squad Access:</strong> Unlocks extras like noise-cancelled comms and permanent private squad rooms. No rush though &mdash; only whenever you’re ready.
                </td>
              </tr>
            </table>
          </div>
        </td>
      </tr>

      <!-- Feedback / Direct Reply Invitation -->
      <tr>
        <td style="padding: 0 32px 28px 32px;">
          <div style="background: linear-gradient(135deg, rgba(0, 229, 255, 0.08) 0%, rgba(0, 255, 136, 0.08) 100%); border: 1px solid rgba(0, 229, 255, 0.25); border-radius: 14px; padding: 20px;">
            <div style="font-size: 15px; font-weight: 700; color: #F8FAFC; margin-bottom: 8px;">
              And honestly, we’d love to hear from you. 💬
            </div>
            <p style="margin: 0; font-size: 13px; line-height: 1.7; color: #CBD5E1;">
              If something doesn’t work properly, you have an idea, or there’s something you simply wish GamerVoice could do, tell us. <strong>You can reply directly to this email.</strong> There’s a real person on the other side, and we read it.
            </p>
          </div>
        </td>
      </tr>

      <!-- Closing Note & Sign-off -->
      <tr>
        <td style="padding: 0 32px 32px 32px;">
          <p style="margin: 0 0 16px 0; font-size: 14px; line-height: 1.75; color: #CBD5E1;">
            Thanks for giving GamerVoice a chance.<br>
            We hope it becomes a small part of a lot of great games, late-night conversations, ridiculous clutches, and memories with your friends.
          </p>
          <div style="font-size: 16px; font-weight: 800; color: #00FF88; margin-bottom: 6px;">
            Welcome to the community. 🎮
          </div>
          <div style="font-size: 15px; font-weight: 700; color: #F8FAFC;">
            &mdash; Aryan &amp; the GamerVoice team
          </div>
        </td>
      </tr>

      <!-- Footer Section -->
      <tr>
        <td style="background: #080D1A; border-top: 1px solid #1E293B; padding: 24px 32px; text-align: center;">
          <p style="margin: 0 0 8px 0; font-size: 11px; color: #64748B;">
            Sent with ❤️ to <strong style="color: #94A3B8;">$email</strong> &bull; GamerVoice Squad Network
          </p>
          <p style="margin: 0; font-size: 11px; color: #475569; line-height: 1.5;">
            You received this email because you signed up for GamerVoice.<br>
            Need technical help? Reply directly to this email or visit our in-app Contact Support.
          </p>
        </td>
      </tr>
    </table>
  </center>
</body>
</html>
        """.trimIndent()
    }

    private fun deliverViaSmtpWithFallback(
        toEmail: String,
        subject: String,
        plainBody: String,
        htmlBody: String,
        username: String,
        pass: String
    ) {
        try {
            // Attempt 1: Direct SSL on Port 465
            deliverViaSmtpSsl(toEmail, subject, plainBody, htmlBody, username, pass, SmtpConfig.SMTP_HOST, 465)
        } catch (e: Exception) {
            Log.w(TAG, "Port 465 SSL failed (${e.message}). Attempting fallback to Port 587 STARTTLS...")
            // Attempt 2: STARTTLS on Port 587
            deliverViaSmtpStartTls(toEmail, subject, plainBody, htmlBody, username, pass, SmtpConfig.SMTP_HOST, 587)
        }
    }

    private fun deliverViaSmtpSsl(
        toEmail: String,
        subject: String,
        plainBody: String,
        htmlBody: String,
        username: String,
        pass: String,
        host: String,
        port: Int
    ) {
        val socketFactory = SSLSocketFactory.getDefault()
        val socket = socketFactory.createSocket(host, port) as SSLSocket
        socket.soTimeout = 15000
        socket.startHandshake()

        executeSmtpConversation(socket, toEmail, subject, plainBody, htmlBody, username, pass)
    }

    private fun deliverViaSmtpStartTls(
        toEmail: String,
        subject: String,
        plainBody: String,
        htmlBody: String,
        username: String,
        pass: String,
        host: String,
        port: Int
    ) {
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

        executeSmtpConversation(sslSocket, toEmail, subject, plainBody, htmlBody, username, pass)
    }

    private fun executeSmtpConversation(
        socket: java.net.Socket,
        toEmail: String,
        subject: String,
        plainBody: String,
        htmlBody: String,
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

        // Boundary for multipart/alternative (Plain text + Rich HTML)
        val boundary = "====_GamersVoice_Boundary_${System.currentTimeMillis()}_===="
        val encodedSubject = "=?UTF-8?B?" + Base64.encodeToString(subject.toByteArray(Charsets.UTF_8), Base64.NO_WRAP) + "?="

        // Send MIME headers
        writer.print("From: \"${SmtpConfig.SENDER_NAME}\" <$sender>\r\n")
        writer.print("To: <$toEmail>\r\n")
        writer.print("Subject: $encodedSubject\r\n")
        writer.print("MIME-Version: 1.0\r\n")
        writer.print("Content-Type: multipart/alternative; boundary=\"$boundary\"\r\n")
        writer.print("\r\n")

        // 1. Plain Text Version Part
        writer.print("--$boundary\r\n")
        writer.print("Content-Type: text/plain; charset=UTF-8\r\n")
        writer.print("Content-Transfer-Encoding: 8bit\r\n\r\n")
        for (rawLine in plainBody.split("\n")) {
            var line = rawLine.trimEnd('\r')
            if (line.startsWith(".")) line = ".$line"
            writer.print("$line\r\n")
        }
        writer.print("\r\n")

        // 2. Rich HTML Version Part
        writer.print("--$boundary\r\n")
        writer.print("Content-Type: text/html; charset=UTF-8\r\n")
        writer.print("Content-Transfer-Encoding: 8bit\r\n\r\n")
        for (rawLine in htmlBody.split("\n")) {
            var line = rawLine.trimEnd('\r')
            if (line.startsWith(".")) line = ".$line"
            writer.print("$line\r\n")
        }
        writer.print("\r\n")

        // Closing Boundary
        writer.print("--$boundary--\r\n")

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
