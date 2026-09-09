package com.gamervoice.app.util

object LegalDocsHelper {

    val PRIVACY_POLICY = """
        GAMERVOICE PRIVACY POLICY
        (Compliant with Digital Personal Data Protection Act, 2023 & IT Act, 2000)
        Last Updated: September 2026
        Version: 1.0.0-beta

        1. INTRODUCTION & SCOPE
        GamerVoice ("we", "our", or "the App") is committed to safeguarding the digital privacy of users in compliance with the Digital Personal Data Protection Act, 2023 (DPDP Act 2023) of the Republic of India, the Information Technology Act, 2000, and applicable international privacy frameworks. This document outlines how your data is processed, secured, and respected.

        2. ZERO AUDIO SURVEILLANCE & ZERO LOGGING GUARANTEE
        - Pure Peer-to-Peer Real-Time Audio: Voice packets are streamed directly between squad room participants using WebRTC with industry-standard DTLS-SRTP 128-bit encryption.
        - Strict Zero Storage: Microphone audio is NEVER recorded, NEVER eavesdropped, NEVER transcribed, NEVER analyzed for advertising, and NEVER stored on our servers or third-party cloud buckets. Section 43A and Section 72A of the IT Act, 2000 (Protection of Sensitive Personal Data) are strictly adhered to.
        - When an active call ends or you mute your microphone, hardware audio access is instantly severed by the Android operating system.

        3. PERSONAL DATA PROCESSED
        We adhere to the principle of strict data minimization under the DPDP Act 2023:
        - Account Credentials: User email address and gamer display name (provided via Google Sign-In or manual registration) to identify you to squad members in your rooms.
        - Room Signaling & Session Tokens: Ephemeral session identifiers exchanged over secure WebSockets (WSS with TLS 1.3) solely to establish peer connections. These are discarded upon room teardown.
        - No Ad Tracking: GamerVoice contains 0 third-party ad tracking SDKs, 0 data broker beacons, and 0 behavioral profiling software. We do not sell or monetize personal data.

        4. RIGHTS OF DATA PRINCIPALS (INDIA DPDP ACT 2023)
        As a Data Principal under Indian law, you possess:
        - Right to Access & Summary of Personal Data processed.
        - Right to Correction, Updating, and Complete Erasure of your account data ("Right to be Forgotten").
        - Right to Grievance Redressal through our designated statutory officer.
        To exercise any of these rights, email us directly at supportgamersvoice@gmail.com or use the in-app Support ticket desk.

        5. STATUTORY GRIEVANCE REDRESSAL MECHANISM (RULE 3(2) IT RULES, 2021)
        In accordance with Rule 3(2) of the Information Technology (Intermediary Guidelines and Digital Media Ethics Code) Rules, 2021:
        • Designated Grievance Officer: Legal & Grievance Cell, GamerVoice Core Engineering
        • Official Contact Email: supportgamersvoice@gmail.com
        • Statutory Turnaround: Acknowledgment within 24 hours; complete resolution within 15 days from receipt.
        • Jurisdiction: New Delhi, Republic of India.
    """.trimIndent()

    val REFUND_POLICY = """
        GAMERVOICE PAYMENT & REFUND POLICY
        (Compliant with Consumer Protection (E-Commerce) Rules, 2020)
        Last Updated: September 2026
        Version: 1.0.0-beta

        1. STRICT NO-REFUND POLICY FOR DIGITAL PASSES
        All payments made for GamerVoice VIP memberships (Weekly, Monthly, or Lifetime VIP Pass) are FINAL and NON-REFUNDABLE once processed. VIP access unlocks digital entitlements instantaneously—including the AI Ultra-Silent Noise Filter (< 10% Noise), Unlimited Squad Rooms, and Autonomous Background RAM Purging. Once these digital privileges are activated on your account, transactions cannot be reversed, canceled, or refunded.

        2. RBI & STATUTORY E-COMMERCE COMPLIANCE
        - Payments are processed via authorized payment aggregators (Razorpay) compliant with the Reserve Bank of India (RBI) regulations and the Payment and Settlement Systems Act, 2007.
        - All transactions are billed in Indian Rupees (INR ₹) inclusive of applicable GST.

        3. FAILED TRANSACTIONS & BILLING DISCREPANCIES
        If you experience any transaction anomaly, including:
        - Amount debited from your bank/UPI but VIP status not unlocked in app
        - Accidental double charge by your banking provider
        - Payment gateway timeout
        
        DO NOT initiate chargebacks or disputes through third parties without contacting us first. Submit a priority ticket through the in-app "Contact Support" form or email supportgamersvoice@gmail.com with your Razorpay Payment ID. Our engineering team resolves verified payment discrepancies within 24–48 hours.

        4. CONSUMER REDRESSAL CONTACT
        For all billing inquiries and payment disputes:
        • Official Support Email: supportgamersvoice@gmail.com
        • Response Window: Priority VIP resolution within 24 hours.
    """.trimIndent()

    val TERMS_OF_SERVICE = """
        GAMERVOICE TERMS OF SERVICE & EULA
        (Compliant with Information Technology Act, 2000 & IT Rules, 2021)
        Last Updated: September 2026
        Version: 1.0.0-beta

        1. ACCEPTANCE & APPLICABILITY
        By downloading, installing, accessing, or using GamerVoice ("the App"), you enter into a legally binding agreement governed by the Laws of the Republic of India. If you do not agree with these Terms, you must immediately cease using and uninstall the App.

        2. STATUTORY INTERMEDIARY STATUS
        GamerVoice operates as an intermediary under Section 2(1)(w) of the Information Technology Act, 2000. We provide real-time peer-to-peer transmission infrastructure and do not initiate, select, or modify the voice audio transmitted between room participants.

        3. USER CODE OF CONDUCT & PROHIBITED ACTS
        Under Rule 3(1)(b) of the IT Rules 2021, users shall NOT host, display, transmit, or share any audio or content that:
        - Belongs to another person without authorization;
        - Is defamatory, obscene, pornographic, pedophilic, invasive of another's privacy, or racially/ethnically objectionable;
        - Threatens the unity, integrity, defense, security, or sovereignty of India, friendly relations with foreign states, or public order;
        - Causes incitement to the commission of any cognizable offense;
        - Transmits abusive, toxic, or cyberbullying speech to squad members.
        Violation of this code results in immediate room ban and account termination.

        4. DIGITAL VIP LICENSES & STRICT NO-REFUND
        VIP passes grant a personal, non-transferable, revocable digital license. Digital access is delivered instantly; all purchases are strictly non-refundable as detailed in our Payment & Refund Policy.

        5. DISCLAIMER OF WARRANTIES & LIMITATION OF LIABILITY
        - The service is provided on an "AS IS" and "AS AVAILABLE" basis. Voice transmission quality depends on your ISP, local cellular reception, and mobile hardware.
        - To the fullest extent permitted by Indian law, GamerVoice and its developers shall not be liable for any direct, indirect, incidental, or consequential damages resulting from service interruption.

        6. GOVERNING LAW & JURISDICTION
        These Terms shall be governed by and construed in accordance with the Laws of India. Any legal action, dispute, or proceeding arising out of or related to GamerVoice shall be subject to the exclusive jurisdiction of the competent courts in New Delhi, India.

        7. LEGAL NOTICES & GRIEVANCES
        All legal notices and regulatory inquiries must be directed to:
        • Email: supportgamersvoice@gmail.com
        • Attention: Legal Compliance & Grievance Officer
    """.trimIndent()

    val INDIAN_GOVT_COMPLIANCE = """
        INDIAN GOVERNMENT STATUTORY COMPLIANCE & LEGAL NOTICE
        Republic of India • IT Act 2000 & DPDP Act 2023 Disclosure

        1. REGULATORY COMPLIANCE OVERVIEW
        GamerVoice is engineered in full compliance with the statutory regulations enacted by the Government of India and the Ministry of Electronics and Information Technology (MeitY):
        • Information Technology Act, 2000 (Act No. 21 of 2000)
        • IT (Intermediary Guidelines and Digital Media Ethics Code) Rules, 2021
        • Digital Personal Data Protection Act, 2023 (DPDP Act 2023)
        • Consumer Protection (E-Commerce) Rules, 2020
        • CERT-In Cyber Security Directions (Section 70B IT Act)

        2. DESIGNATED GRIEVANCE OFFICER (RULE 3(2) IT RULES 2021)
        Users may submit legal complaints, privacy concerns, or intermediary notices to:
        • Designation: Grievance Redressal Officer
        • App: GamerVoice Squad Audio Comms
        • Official Legal Email: supportgamersvoice@gmail.com
        • Statutory Timeline:
          - Acknowledgment of complaint: Within 24 hours
          - Redressal / Disposal: Within 15 days of receipt

        3. DATA RESIDENCY & ZERO AUDIO RETENTION
        In accordance with Section 43A and Section 72A of the IT Act, voice communication in GamerVoice is strictly ephemeral WebRTC peer-to-peer audio. No user audio is retained, wiretapped, stored, or processed on external cloud storage.

        4. CYBERSECURITY INCIDENT REPORTING
        In accordance with CERT-In directives under Section 70B of the IT Act, security vulnerabilities or cyber incident reports may be submitted directly to our engineering desk via supportgamersvoice@gmail.com.
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
        Any user violating community safety or fair play may have their room access or account suspended. Contact supportgamersvoice@gmail.com to report persistent misconduct.
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
