package com.gamervoice.app.util

object LegalDocsHelper {

    val PRIVACY_POLICY = """
        GAMERVOICE PRIVACY POLICY
        Last Updated: September 2026
        Version: 1.0.0-beta

        1. INTRODUCTION & PHILOSOPHY
        GamerVoice ("we", "our", or "the App") is committed to protecting your privacy while delivering ultra-low-latency voice chat for competitive gaming squads. This Privacy Policy details how we handle user information.

        2. ZERO AUDIO RECORDING / ZERO SURVEILLANCE
        - Real-Time Peer-to-Peer Audio: Voice communication is streamed in real time between room participants using WebRTC with industry-standard DTLS-SRTP encryption.
        - No Audio Logging: Your microphone audio is NEVER recorded, NEVER saved, NEVER processed for advertising, and NEVER stored on our servers or third-party storage. When you speak, audio packets travel directly to your connected squad peers and are played instantly.

        3. MICROPHONE ACCESS
        - We require access to the device microphone (android.permission.RECORD_AUDIO) solely to capture voice during active squad rooms and local mic tests.
        - When a room is closed or muted, the microphone input is immediately disabled.

        4. ACCOUNT INFORMATION & PROFILE DATA
        - Authentication: When signing in with Google or Email/Password, we receive your email, display name, and profile picture (avatar URL).
        - Storage: User profiles are securely stored in Cloud Firestore to render your gamer name and avatar to other players in your voice room.
        - No Tracking or Ad Networks: GamerVoice contains 0 ad trackers, 0 behavioral profiling SDKs, and 0 third-party data broker integrations.

        5. DATA SECURITY & ENCRYPTION
        - All signaling data (room codes, peer negotiation) is transmitted over secure WebSockets (WSS) using TLS 1.3 encryption.
        - Peer-to-peer audio uses WebRTC AES-128 DTLS-SRTP encryption.

        6. CHILDREN'S PRIVACY & GDPR / COPPA
        GamerVoice complies with global privacy regulations. We do not knowingly collect personal information from individuals under the minimum age required by applicable local law without parental consent.

        7. CONTACT & DATA DELETION
        To delete your stored profile data or reach our team, submit a ticket through the in-app Contact Support form or email support@gamervoice.app.
    """.trimIndent()

    val REFUND_POLICY = """
        GAMERVOICE PAYMENT & REFUND POLICY
        Last Updated: September 2026
        Version: 1.0.0-beta

        1. STRICT NO-REFUND POLICY
        All payments made for GamerVoice VIP memberships (Weekly, Monthly, or Lifetime Pass) are final and NON-REFUNDABLE once processed. Because VIP access unlocks digital privileges immediately (including AI Ultra-Silent Noise Filter, Unlimited Cloud Squad Rooms, and Autonomous Background RAM Purging), transactions cannot be reversed or refunded after activation.

        2. TECHNICAL TROUBLESHOOTING & SUPPORT
        If you experience any difficulties, including:
        - Payment processed but VIP status not showing as active
        - Double charge by payment provider
        - Voice connection or audio issues
        
        DO NOT dispute through third parties without contacting us first. You can submit your issue directly through the in-app "Contact Us & Support" form. Our engineering team resolves all verified account discrepancies within 24–48 hours.

        3. CANCELLATION
        VIP passes are prepaid digital access licenses. You will retain access until the exact expiration date displayed on your Profile dashboard.
    """.trimIndent()

    val TERMS_OF_SERVICE = """
        GAMERVOICE TERMS OF SERVICE & EULA
        Last Updated: September 2026
        Version: 1.0.0-beta

        1. ACCEPTANCE OF TERMS
        By downloading, installing, or using GamerVoice, you agree to be bound by these Terms of Service and our strict Payment & Refund Policy. If you do not agree, do not use the application.

        2. LICENSE GRANT
        GamerVoice grants you a personal, non-exclusive, non-transferable, revocable license to use the app for non-commercial personal squad voice communication.

        3. VIP PURCHASES & STRICT NO-REFUND POLICY
        VIP privileges are digital goods delivered instantly upon payment verification via Razorpay. All purchases are strictly non-refundable. Any technical discrepancy or billing concern must be reported via our in-app Support ticket system.

        4. SQUAD CODE OF CONDUCT
        You agree to use GamerVoice responsibly. Prohibited behaviors include:
        - Harassment, hate speech, threats, or severe toxicity towards squad teammates.
        - Transmitting intentionally disruptive noises, audio blasts, or audio spam.
        - Attempting to reverse engineer, disrupt, overload, or compromise the signaling servers or peer connections.
        - Impersonating other players or entities.

        5. SERVICE AVAILABILITY & DISCLAIMER
        - The service is provided on an "AS IS" and "AS AVAILABLE" basis. While we optimize for 3G networks and weak devices, connection quality depends on your cellular network and internet service provider.
        - GamerVoice does not warrant that the service will be 100% error-free or uninterrupted.

        6. LIMITATION OF LIABILITY
        To the maximum extent permitted by applicable law, GamerVoice and its developers shall not be liable for any indirect, incidental, or consequential damages arising from the use or inability to use the service.

        7. DISPUTES & CONTACT
        Any complaints or disputes must be submitted through our in-app Contact Support form for prompt resolution.
    """.trimIndent()

    val COMMUNITY_GUIDELINES = """
        GAMERVOICE COMMUNITY & FAIR PLAY GUIDELINES
        
        Squad voice chat thrives on communication, respect, and victory.

        1. RESPECT YOUR SQUAD
        Every gamer plays to win and have fun. Avoid griefing, racial slurs, personal insults, or destructive behavior in room calls.

        2. MIC HYGIENE & NOISE CONTROL
        - Use Push-To-Talk (PTT) when playing in noisy environments (fans, street noise, crowded rooms).
        - Keep the microphone calibrated so squad members hear clear callouts without clipping.

        3. REPORTING & ENFORCEMENT
        Any user violating community safety or fair play may have their room access or account suspended.
    """.trimIndent()

    val OPEN_SOURCE_LICENSES = """
        OPEN SOURCE LICENSES & ATTRIBUTIONS

        GamerVoice is made possible by the following open-source technologies:

        1. WebRTC Android SDK (io.github.webrtc-sdk:android)
           Copyright (c) 2011, The WebRTC project authors.
           Licensed under the BSD 3-Clause License.

        2. OkHttp & Okio (com.squareup.okhttp3)
           Copyright 2019 Square, Inc.
           Licensed under the Apache License, Version 2.0.

        3. Google Play Services Auth
           Copyright Google LLC.
           Licensed under the Apache License, Version 2.0.

        4. AndroidX & Google Material Components
           Copyright (C) The Android Open Source Project.
           Licensed under the Apache License, Version 2.0.
    """.trimIndent()
}
