package com.gamervoice.app.util

object SmtpConfig {
    // Server-side secure relay endpoints (Credentials live safely in server environment variables, NEVER inside the APK)
    const val EMAIL_RELAY_URL = "https://gamervoice-signaling.onrender.com/api/send-welcome-email"
    const val SUPPORT_RELAY_URL = "https://gamervoice-signaling.onrender.com/api/submit-support-ticket"
    const val PAYMENT_VERIFY_URL = "https://gamervoice-signaling.onrender.com/api/verify-payment"
    const val SENDER_NAME = "GamerVoice Core Team"
    const val SENDER_EMAIL = "supportgamersvoice@gmail.com"
}
