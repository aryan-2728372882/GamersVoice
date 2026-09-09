package com.gamervoice.app.util

object SmtpConfig {
    // Replace with your SMTP server details (e.g. Gmail App Password, Hostinger, SendGrid, Amazon SES)
    var SMTP_HOST = "smtp.gmail.com"
    var SMTP_PORT = 465 // SSL Port (or 587 for STARTTLS)
    var SMTP_USERNAME = "" // Your email e.g. "team.gamervoice@gmail.com"
    var SMTP_PASSWORD = "" // Your 16-character App Password
    var SENDER_NAME = "GamerVoice Core Team"
    var SENDER_EMAIL = "devs@gamervoice.app"
}
