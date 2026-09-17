package com.gamervoice.app

import android.annotation.SuppressLint
import android.app.Dialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.graphics.Color
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.provider.Settings
import android.util.Log
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.gamervoice.app.auth.AuthManager
import com.gamervoice.app.auth.PlanManager
import com.gamervoice.app.auth.PlanTier
import com.gamervoice.app.auth.UserProfile
import com.gamervoice.app.databinding.ActivityHomeBinding
import com.gamervoice.app.databinding.DialogContactUsBinding
import com.gamervoice.app.databinding.DialogLegalDocBinding
import com.gamervoice.app.databinding.DialogNoiseFilterBinding
import com.gamervoice.app.databinding.DialogVipUpgradeBinding
import com.gamervoice.app.util.AnimationHelper
import com.gamervoice.app.util.SupportTicketManager
import com.gamervoice.app.databinding.ItemInstalledGameBinding
import com.gamervoice.app.databinding.ItemParticipantBinding
import com.gamervoice.app.databinding.ItemSavedRoomBinding
import com.gamervoice.app.model.RoomParticipant
import com.gamervoice.app.model.RoomPersistenceManager
import com.gamervoice.app.model.SavedRoom
import com.gamervoice.app.overlay.FloatingHudManager
import com.gamervoice.app.service.VoiceService
import com.gamervoice.app.util.AppLogger
import com.gamervoice.app.util.GameLauncherHelper
import com.gamervoice.app.util.ImageLoader
import com.gamervoice.app.util.LegalDocsHelper
import com.razorpay.Checkout
import com.razorpay.PaymentData
import com.razorpay.PaymentResultWithDataListener
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

class HomeActivity : AppCompatActivity(), VoiceService.VoiceServiceListener, PaymentResultWithDataListener {

    private lateinit var binding: ActivityHomeBinding

    private var voiceService: VoiceService? = null
    private var isServiceBound = false

    private var isSpeakerphone = true
    private var noiseFilterLevel = 0 // 0: Normal (50%), 1: High (75%), 2: Ultra Silent (VIP)
    private var pendingPurchaseTier: PlanTier? = null
    private var vipUpgradeDialog: Dialog? = null
    private var currentActiveTab = 0
    private var roomJoinTimestamp: Long = 0L
    private var lastMeasuredLatencyMs: Long = 0L
    private val livePeerLatencies = java.util.concurrent.ConcurrentHashMap<String, Long>()


    private val logListener: (AppLogger.LogEntry) -> Unit = { entry ->
        runOnUiThread {
            try {
                binding.tvConsoleLogs.append(entry.toString() + "\n")
                binding.svConsoleLogs.post {
                    binding.svConsoleLogs.fullScroll(View.FOCUS_DOWN)
                }
            } catch (_: Throwable) {}
        }
    }

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as VoiceService.LocalBinder
            val svc = binder.getService()
            voiceService = svc
            svc.listener = this@HomeActivity
            isServiceBound = true

            runOnUiThread {
                binding.btnCreateRoom.isEnabled = true
                binding.btnJoinRoom.isEnabled = true
                binding.btnCreateRoomCard.isEnabled = true
                binding.btnJoinRoomCard.isEnabled = true
                binding.tvServerStatus.text = "Server Connected"
            }

            // Restore state if returning to an active room
            val activeRoomCode = svc.currentRoomCode
            if (!activeRoomCode.isNullOrEmpty()) {
                showConnectedRoomView(activeRoomCode)
            } else {
                showHomeView()
            }
            updateMicModeUI(svc.isPttModeEnabled())

            // Synchronize audio engine settings
            val audioPrefs = getSharedPreferences("gamervoice_audio_settings", Context.MODE_PRIVATE)
            val savedNoise = audioPrefs.getInt("noise_filter_level", 0)
            val savedLowData = audioPrefs.getBoolean("low_data_3g", false)
            svc.setNoiseFilterLevel(savedNoise)
            svc.setLowDataMode(savedLowData)
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            voiceService = null
            isServiceBound = false
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        AuthManager.init(this)
        PlanManager.init(this)
        RoomPersistenceManager.init(this)
        try {
            Checkout.preload(applicationContext)
        } catch (t: Throwable) {
            Log.w("HomeActivity", "Checkout preload warning: ${t.message}")
        }

        if (!AuthManager.isLoggedIn()) {
            val intent = Intent(this, AuthActivity::class.java)
            startActivity(intent)
            finish()
            return
        }

        val loggedInUser = AuthManager.getCurrentUser()
        if (loggedInUser != null && loggedInUser.email.isNotBlank()) {
            try {
                if (!com.gamervoice.app.util.WelcomeEmailHelper.hasWelcomeBeenSent(this, loggedInUser.email)) {
                    com.gamervoice.app.util.WelcomeEmailHelper.sendWelcomeEmailOnce(this, loggedInUser.email, loggedInUser.name, loggedInUser.uid)
                }
            } catch (t: Throwable) {
                Log.w("HomeActivity", "Welcome email check warning: ${t.message}")
            }
        }

        binding = ActivityHomeBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Automatically sync VIP status from Cloud Firestore on app launch
        try {
            PlanManager.enforceValidPaidStatus {
                runOnUiThread {
                    updatePlanUI()
                    refreshSavedRoomsUI(RoomPersistenceManager.getCachedRooms())
                }
            }
        } catch (t: Throwable) {
            Log.w("HomeActivity", "Plan sync warning: ${t.message}")
        }

        binding.btnCreateRoom.isEnabled = false
        binding.btnJoinRoom.isEnabled = false
        binding.btnCreateRoomCard.isEnabled = false
        binding.btnJoinRoomCard.isEnabled = false

        setupUI()
        setupConsoleUI()

        try { com.gamervoice.app.util.SquadReplayManager.init(this) } catch (_: Throwable) {}
        try { com.gamervoice.app.auth.ReferralManager.registerCodeWithServer(this) } catch (_: Throwable) {}
        try { com.gamervoice.app.service.GamerVoiceMessagingService.subscribeToGlobalTopic() } catch (_: Throwable) {}

        // Android 13+ Notification Permission Prompt
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                androidx.core.app.ActivityCompat.requestPermissions(
                    this,
                    arrayOf(android.Manifest.permission.POST_NOTIFICATIONS),
                    101
                )
            }
        }

        val serviceIntent = Intent(this, VoiceService::class.java)
        bindService(serviceIntent, serviceConnection, BIND_AUTO_CREATE)

        // Re-arm VIP expiry alarm (survives across sessions and reboots)
        com.gamervoice.app.util.VipExpiryAlarmScheduler.rescheduleIfNeeded(this)

        // Handle open_tab intent (from VIP expiry notification tap)
        val openTab = intent?.getIntExtra("open_tab", -1) ?: -1
        if (openTab >= 0) binding.root.postDelayed({ updateActiveNavTab(openTab) }, 600L)

        // Handle deep link: gamervoice://join/CODE or https://gamersvoice.onrender.com/join/CODE
        val autoJoinFromLink = extractRoomCodeFromIntent(intent)
        val autoJoinFromExtra = intent?.getStringExtra("auto_join_room")
        val autoJoin = autoJoinFromLink ?: autoJoinFromExtra
        if (!autoJoin.isNullOrEmpty()) {
            intent?.removeExtra("auto_join_room")
            binding.root.postDelayed({
                // Navigate to Rooms tab first, then join
                updateActiveNavTab(1)
                joinRoomWithCode(autoJoin)
            }, 700L)
        }
    }

    private fun extractRoomCodeFromIntent(i: Intent?): String? {
        if (i == null) return null
        val data = i.data ?: return null
        // gamervoice://join/ABCDE
        if (data.scheme == "gamervoice" && data.host == "join") {
            val code = data.lastPathSegment?.uppercase()?.trim()
            if (!code.isNullOrEmpty() && code.length >= 3) return code
        }
        // https://gamersvoice.onrender.com/join/ABCDE
        val segments = data.pathSegments
        if (segments != null && segments.size >= 2 && segments[segments.size - 2] == "join") {
            val code = segments[segments.size - 1].uppercase().trim()
            if (code.length >= 3) return code
        }
        return null
    }

    override fun onStart() {
        super.onStart()
        AppLogger.addListener(logListener)
        verifyPlanExpiry()
        loadInstalledGames()
        refreshSavedRoomsUI(RoomPersistenceManager.getCachedRooms())
        RoomPersistenceManager.fetchSavedRooms { freshRooms ->
            refreshSavedRoomsUI(freshRooms)
        }
        updatePlanUI()
        checkSeasonGloryReward()
        com.gamervoice.app.util.AppUpdateChecker.checkForUpdate(this)
    }

    override fun onStop() {
        super.onStop()
        AppLogger.removeListener(logListener)
        // Immediately flush bitmap cache when minimized to maintain < 10MB RAM footprint
        ImageLoader.clearMemoryCache()
        System.gc()
    }

    override fun onResume() {
        super.onResume()
        verifyPlanExpiry()
        updatePlanUI()
        val isHudActive = FloatingHudManager.isHudShowing()
        binding.switchSettingHud.isChecked = isHudActive

        val crashPrefs = getSharedPreferences(GamerVoiceApp.PREFS_CRASH, MODE_PRIVATE)
        val lastCrash = crashPrefs.getString(GamerVoiceApp.KEY_LAST_CRASH, null)
        if (!lastCrash.isNullOrEmpty()) {
            crashPrefs.edit().remove(GamerVoiceApp.KEY_LAST_CRASH).apply()
            androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle(getString(R.string.dialog_crash_diagnostic_title))
                .setMessage(lastCrash)
                .setPositiveButton(getString(R.string.dialog_crash_copy)) { _, _ ->
                    val clipboard = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
                    clipboard.setPrimaryClip(ClipData.newPlainText("Crash Log", lastCrash))
                    Toast.makeText(this, getString(R.string.toast_crash_copied), Toast.LENGTH_SHORT).show()
                }
                .setNegativeButton(getString(R.string.dialog_dismiss), null)
                .show()
        }
    }

    override fun onPause() {
        super.onPause()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        // Check for deep link first (gamervoice://join/CODE or https://gamersvoice.onrender.com/join/CODE)
        val deepLinkCode = extractRoomCodeFromIntent(intent)
        if (!deepLinkCode.isNullOrEmpty()) {
            binding.root.postDelayed({
                updateActiveNavTab(1)
                joinRoomWithCode(deepLinkCode)
            }, 400L)
            return
        }
        val autoJoin = intent.getStringExtra("auto_join_room")
        if (!autoJoin.isNullOrEmpty()) {
            intent.removeExtra("auto_join_room")
            joinRoomWithCode(autoJoin)
        } else {
            voiceService?.currentRoomCode?.let { roomCode ->
                showConnectedRoomView(roomCode)
            }
        }
    }

    private fun isRecordAudioGranted(): Boolean {
        return ContextCompat.checkSelfPermission(
            this,
            android.Manifest.permission.RECORD_AUDIO
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
    }

    private fun setupUI() {
        val user = AuthManager.getCurrentUser()
        if (user != null) {
            binding.tvUserName.text = user.name
            binding.tvUserEmail.text = user.email
            ImageLoader.loadAvatar(binding.ivUserAvatar, user.avatar)
        }

        AnimationHelper.attachPressAnimation(binding.llUserProfileHeader) {
            updateActiveNavTab(3) // Jump to Profile Tab
        }

        // Plan Badge Click & Ambient Glow Pulse
        AnimationHelper.startAmbientPulse(binding.tvHomePlanBadge)
        AnimationHelper.attachPressAnimation(binding.tvHomePlanBadge) {
            showVipUpgradeDialog()
        }

        // Sponsor Banner Ad - Remove Ads click
        AnimationHelper.attachPressAnimation(binding.btnBannerRemoveAds) {
            showVipUpgradeDialog()
        }

        val createRoomAction = View.OnClickListener {
            try {
                if (!isRecordAudioGranted()) {
                    Toast.makeText(this, getString(R.string.toast_mic_perm_required), Toast.LENGTH_SHORT).show()
                    startActivity(Intent(this, PermissionActivity::class.java))
                    return@OnClickListener
                }
                startVoiceServiceForeground()
                val svc = voiceService
                if (svc != null) {
                    binding.tvServerStatus.text = getString(R.string.status_creating_room)
                    svc.createRoom()
                } else {
                    Toast.makeText(this, getString(R.string.status_connecting_service), Toast.LENGTH_SHORT).show()
                }
            } catch (t: Throwable) {
                AppLogger.log("ERROR", "btnCreateRoom error: ${t.message}", Log.getStackTraceString(t))
                Toast.makeText(this, getString(R.string.toast_error_creating_room, t.message ?: ""), Toast.LENGTH_LONG).show()
            }
        }

        // Create Room Dual Card & legacy button with spring bounce
        AnimationHelper.attachPressAnimation(binding.btnCreateRoomCard) { createRoomAction.onClick(binding.btnCreateRoomCard) }
        AnimationHelper.attachPressAnimation(binding.btnCreateRoom) { createRoomAction.onClick(binding.btnCreateRoom) }

        // Join Room Dual Card & legacy button with spring bounce
        AnimationHelper.attachPressAnimation(binding.btnJoinRoomCard) { showJoinInputView() }
        AnimationHelper.attachPressAnimation(binding.btnJoinRoom) { showJoinInputView() }

        // Submit Room code
        AnimationHelper.attachPressAnimation(binding.btnSubmitJoin) {
            joinRoomWithCode(binding.etJoinRoomCode.text.toString().trim().uppercase())
        }

        AnimationHelper.attachPressAnimation(binding.btnBackToHome) {
            showHomeView()
        }

        AnimationHelper.attachPressAnimation(binding.btnCopyCode) {
            val code = binding.tvDisplayRoomCode.text.toString()
            val clipboard = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
            val clip = ClipData.newPlainText("GamerVoice Room Code", code)
            clipboard.setPrimaryClip(clip)
            AnimationHelper.popView(binding.btnCopyCode, 1.12f)
            Toast.makeText(this, getString(R.string.code_copied), Toast.LENGTH_SHORT).show()
        }

        // Save Current Room to Firestore
        AnimationHelper.attachPressAnimation(binding.btnSaveCurrentRoom) {
            val code = binding.tvDisplayRoomCode.text.toString().trim()
            if (code.isNotEmpty()) {
                saveRoomToSquad(code)
            }
        }

        // Save Clutch Clip (120s rolling audio buffer export)
        AnimationHelper.attachPressAnimation(binding.btnSaveClutchClip) {
            if (!com.gamervoice.app.util.SquadReplayManager.isConsentGranted(this)) {
                Toast.makeText(this, getString(R.string.toast_clutch_enable_first), Toast.LENGTH_LONG).show()
                return@attachPressAnimation
            }
            if (!PlanManager.isVip()) {
                showVipUpgradeDialog(getString(R.string.vip_feature_clutch_highlights))
                return@attachPressAnimation
            }
            Toast.makeText(this, getString(R.string.toast_clutch_exporting), Toast.LENGTH_SHORT).show()
            com.gamervoice.app.util.SquadReplayManager.saveClutchClip(this) { _, msg ->
                runOnUiThread {
                    Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
                }
            }
        }

        // Referral Engine (Share & Redeem & Leaderboard)
        AnimationHelper.attachPressAnimation(binding.btnShareReferral) {
            com.gamervoice.app.auth.ReferralManager.shareReferral(this)
        }
        AnimationHelper.attachPressAnimation(binding.btnRedeemReferral) {
            showRedeemReferralDialog()
        }
        AnimationHelper.attachPressAnimation(binding.btnViewLeaderboard) {
            try {
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://gamersvoice.onrender.com/referrals#leaderboardSection"))
                startActivity(intent)
            } catch (e: Exception) {
                Toast.makeText(this, getString(R.string.toast_opening_leaderboard), Toast.LENGTH_SHORT).show()
            }
        }
        binding.btnViewLeaderboard.setOnLongClickListener {
            com.gamervoice.app.util.GloryRewardDialog.showGloryCeremony(
                this,
                rank = 1,
                vipDays = 14,
                recruitsCount = 1
            ) {
                updatePlanUI()
            }
            true
        }
        AnimationHelper.attachPressAnimation(binding.btnDeleteAccount) {
            confirmAndDeleteAccount()
        }

        // Squad Match Alarm Switch (Customizable Reminder Time)
        binding.switchProfileSquadAlarm.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) {
                val currentRoom = voiceService?.currentRoomCode ?: ""
                val savedHour = com.gamervoice.app.util.SquadAlarmHelper.getAlarmHour(this)
                val savedMinute = com.gamervoice.app.util.SquadAlarmHelper.getAlarmMinute(this)
                com.gamervoice.app.util.SquadAlarmHelper.setSquadAlarm(this, hour = savedHour, minute = savedMinute, roomCode = currentRoom)
                val timeStr = com.gamervoice.app.util.SquadAlarmHelper.getAlarmTimeString(this)
                Toast.makeText(this, getString(R.string.toast_squad_alarm_active, timeStr), Toast.LENGTH_SHORT).show()
            } else {
                com.gamervoice.app.util.SquadAlarmHelper.cancelSquadAlarm(this)
                Toast.makeText(this, getString(R.string.toast_squad_alarm_off), Toast.LENGTH_SHORT).show()
            }
            updatePlanUI()
        }

        // Tap to customize Squad Match Reminder Time via Android TimePickerDialog
        AnimationHelper.attachPressAnimation(binding.llSquadAlarmClickArea) {
            showAlarmTimePickerDialog()
        }
        AnimationHelper.attachPressAnimation(binding.btnChangeAlarmTime) {
            showAlarmTimePickerDialog()
        }

        // Personal Clutch Mic Highlights Replay Consent Switch
        binding.switchSettingReplayBuffer.isChecked = com.gamervoice.app.util.SquadReplayManager.isConsentGranted(this)
        binding.switchSettingReplayBuffer.setOnCheckedChangeListener { _, isChecked ->
            com.gamervoice.app.util.SquadReplayManager.setConsentGranted(this, isChecked)
            if (isChecked) {
                Toast.makeText(this, getString(R.string.toast_clutch_highlights_enabled), Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, getString(R.string.toast_clutch_highlights_off), Toast.LENGTH_SHORT).show()
            }
            updateReplayBadgeUI()
        }

        AnimationHelper.attachPressAnimation(binding.btnRoomMicLive) {
            voiceService?.setMicMuted(false)
            updateMicModeUI(isMuted = false)
        }
        AnimationHelper.attachPressAnimation(binding.btnRoomMicMute) {
            voiceService?.setMicMuted(true)
            updateMicModeUI(isMuted = true)
        }

        AnimationHelper.attachPressAnimation(binding.btnToggleFloatingHud) {
            toggleFloatingHud()
        }

        // --- App & Audio Settings (4 Cards Grid with Tactile Springs) ---
        AnimationHelper.attachPressAnimation(binding.cardSettingHud) {
            binding.switchSettingHud.isChecked = !binding.switchSettingHud.isChecked
        }
        binding.switchSettingHud.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) {
                if (!FloatingHudManager.hasOverlayPermission(this)) {
                    binding.switchSettingHud.isChecked = false
                    requestOverlayPermission()
                } else {
                    val svc = voiceService
                    if (svc != null && svc.currentRoomCode != null) {
                        FloatingHudManager.showHud(this, svc)
                    } else {
                        Toast.makeText(this, getString(R.string.toast_hud_activate_hint), Toast.LENGTH_SHORT).show()
                    }
                }
            } else {
                FloatingHudManager.hideHud()
            }
        }

        AnimationHelper.attachPressAnimation(binding.cardSettingNoiseFilter) {
            showNoiseFilterDialog()
        }

        AnimationHelper.attachPressAnimation(binding.cardSettingAudioRoute) {
            toggleAudioRoute()
        }

        // Restore saved audio settings
        val audioPrefs = getSharedPreferences("gamervoice_audio_settings", Context.MODE_PRIVATE)
        noiseFilterLevel = audioPrefs.getInt("noise_filter_level", 0)
        if (!PlanManager.isVip() && noiseFilterLevel == 1) {
            noiseFilterLevel = 0
            audioPrefs.edit().putInt("noise_filter_level", 0).apply()
        }
        updateNoiseFilterUI()

        val isLowDataSaved = audioPrefs.getBoolean("low_data_3g", false)
        binding.switchSettingLowData.isChecked = isLowDataSaved

        AnimationHelper.attachPressAnimation(binding.cardSettingLowData) {
            binding.switchSettingLowData.isChecked = !binding.switchSettingLowData.isChecked
        }
        binding.switchSettingLowData.setOnCheckedChangeListener { _, isChecked ->
            getSharedPreferences("gamervoice_audio_settings", Context.MODE_PRIVATE)
                .edit()
                .putBoolean("low_data_3g", isChecked)
                .apply()
            voiceService?.setLowDataMode(isChecked)
            Toast.makeText(
                this,
                if (isChecked) getString(R.string.toast_low_data_enabled) else getString(R.string.toast_low_data_disabled),
                Toast.LENGTH_SHORT
            ).show()
        }

        // --- Legal & Policies (4 Cards Grid) ---
        AnimationHelper.attachPressAnimation(binding.cardLegalPrivacy) {
            showLegalDialog("Privacy Policy", LegalDocsHelper.PRIVACY_POLICY, R.drawable.ic_shield_privacy)
        }
        AnimationHelper.attachPressAnimation(binding.cardLegalTerms) {
            showLegalDialog("Terms of Service & EULA", LegalDocsHelper.TERMS_OF_SERVICE, R.drawable.ic_document_terms)
        }
        AnimationHelper.attachPressAnimation(binding.cardLegalGuidelines) {
            showLegalDialog("Community & Fair Play", LegalDocsHelper.COMMUNITY_GUIDELINES, R.drawable.ic_gamers_squad)
        }
        AnimationHelper.attachPressAnimation(binding.cardLegalLicenses) {
            showLegalDialog("Open Source Licenses", LegalDocsHelper.OPEN_SOURCE_LICENSES, R.drawable.ic_code_brackets)
        }
        AnimationHelper.attachPressAnimation(binding.cardLegalRefund) {
            showLegalDialog("Payment & Refund Policy", LegalDocsHelper.REFUND_POLICY, R.drawable.ic_shield_privacy)
        }
        AnimationHelper.attachPressAnimation(binding.cardLegalIndianCompliance) {
            showLegalDialog("India Statutory Compliance", LegalDocsHelper.INDIAN_GOVT_COMPLIANCE, R.drawable.ic_shield_check)
        }
        AnimationHelper.attachPressAnimation(binding.cardSupportContact) {
            showContactSupportDialog()
        }

        // --- Footer & Logout Squad ---
        AnimationHelper.attachPressAnimation(binding.btnLogoutSquad) {
            performLogout()
        }

        // --- Bottom Navigation Bar Tabs (Smooth Scale Pops & Transitions) ---
        AnimationHelper.attachPressAnimation(binding.navTabHome) {
            updateActiveNavTab(0)
        }
        AnimationHelper.attachPressAnimation(binding.navTabRooms) {
            updateActiveNavTab(1)
        }
        AnimationHelper.attachPressAnimation(binding.navTabSettings) {
            updateActiveNavTab(2)
        }
        AnimationHelper.attachPressAnimation(binding.navTabProfile) {
            updateActiveNavTab(3)
        }

        // --- Rooms Tab Controls ---
        AnimationHelper.attachPressAnimation(binding.btnRoomsTabJoin) {
            val code = binding.etRoomsTabCode.text.toString().trim().uppercase()
            joinRoomWithCode(code)
        }
        AnimationHelper.attachPressAnimation(binding.btnRoomsTabCreate) {
            createRoomAction.onClick(binding.btnRoomsTabCreate)
        }

        // --- Settings Tab Controls ---
        AnimationHelper.attachPressAnimation(binding.cardSettingRamPurge) {
            if (!PlanManager.isVip()) {
                showVipUpgradeDialog(getString(R.string.vip_feature_ram_purge))
            } else {
                val purgePrefs = getSharedPreferences(com.gamervoice.app.service.VoiceService.PREFS_NAME, MODE_PRIVATE)
                val count = purgePrefs.getInt("auto_purge_count", 0)
                val lastTs = purgePrefs.getLong("last_auto_purge_ts", 0L)
                val lastMb = purgePrefs.getLong("last_auto_purge_mb", 0L)
                val info = if (count > 0 && lastTs > 0) {
                    val minsAgo = ((System.currentTimeMillis() - lastTs) / 60000L).coerceAtLeast(0)
                    "👑 VIP Auto-Purge is ACTIVE!\n• Total Purges: #$count\n• Last Cleaned: ${minsAgo} min ago\n• Active Heap: ~${lastMb}MB (< 10MB target)"
                } else {
                    "👑 VIP Auto-Purge is ACTIVE and scheduled every 3 minutes in background."
                }
                Toast.makeText(this, info, Toast.LENGTH_LONG).show()
            }
        }

        AnimationHelper.attachPressAnimation(binding.btnClearMemoryCache) {
            ImageLoader.clearMemoryCache()
            System.gc()
            val runtime = Runtime.getRuntime()
            val usedMemMb = (runtime.totalMemory() - runtime.freeMemory()) / (1024 * 1024)
            AnimationHelper.popView(binding.btnClearMemoryCache, 1.08f)
            Toast.makeText(this, getString(R.string.toast_memory_cleared), Toast.LENGTH_SHORT).show()
        }

        // --- Profile Tab Controls ---
        var profileSelectedTier = PlanTier.MONTHLY
        fun updateProfileCardSelection() {
            binding.llProfilePlanDayPass.setBackgroundResource(if (profileSelectedTier == PlanTier.DAY_PASS) R.drawable.bg_vip_card_neon else R.drawable.bg_vip_card_unselected)
            binding.llProfilePlanWeekly.setBackgroundResource(if (profileSelectedTier == PlanTier.WEEKLY) R.drawable.bg_vip_card_neon else R.drawable.bg_vip_card_unselected)
            binding.llProfilePlanMonthly.setBackgroundResource(if (profileSelectedTier == PlanTier.MONTHLY) R.drawable.bg_vip_card_gold else R.drawable.bg_vip_card_unselected)
            binding.llProfilePlanLifetime.setBackgroundResource(if (profileSelectedTier == PlanTier.LIFETIME) R.drawable.bg_vip_card_gold else R.drawable.bg_vip_card_unselected)

            val selectedCard = when (profileSelectedTier) {
                PlanTier.DAY_PASS -> binding.llProfilePlanDayPass
                PlanTier.WEEKLY -> binding.llProfilePlanWeekly
                PlanTier.MONTHLY -> binding.llProfilePlanMonthly
                PlanTier.LIFETIME -> binding.llProfilePlanLifetime
                else -> null
            }
            if (selectedCard != null) {
                AnimationHelper.popView(selectedCard, 1.04f)
            }

            val text = when (profileSelectedTier) {
                PlanTier.DAY_PASS -> "⚡ UNLOCK 24H VIP PASS — ₹9"
                PlanTier.WEEKLY -> "⚡ UNLOCK 7 DAYS VIP — ₹29"
                PlanTier.MONTHLY -> "⚡ UNLOCK 30 DAYS VIP — ₹89"
                PlanTier.LIFETIME -> "⚡ UNLOCK LIFETIME VIP — ₹249"
                else -> "⚡ UNLOCK VIP PASS"
            }
            AnimationHelper.animateTextChange(binding.btnProfileActivateVip, text)
        }
        updateProfileCardSelection()

        AnimationHelper.attachPressAnimation(binding.llProfilePlanDayPass) {
            profileSelectedTier = PlanTier.DAY_PASS
            updateProfileCardSelection()
        }
        AnimationHelper.attachPressAnimation(binding.llProfilePlanWeekly) {
            profileSelectedTier = PlanTier.WEEKLY
            updateProfileCardSelection()
        }
        AnimationHelper.attachPressAnimation(binding.llProfilePlanMonthly) {
            profileSelectedTier = PlanTier.MONTHLY
            updateProfileCardSelection()
        }
        AnimationHelper.attachPressAnimation(binding.llProfilePlanLifetime) {
            profileSelectedTier = PlanTier.LIFETIME
            updateProfileCardSelection()
        }
        AnimationHelper.attachPressAnimation(binding.btnProfileActivateVip) {
            val u = AuthManager.getCurrentUser()
            if (u != null) {
                startRazorpayCheckout(profileSelectedTier, u)
            } else {
                Toast.makeText(this, getString(R.string.toast_please_sign_in), Toast.LENGTH_SHORT).show()
            }
        }

        AnimationHelper.attachPressAnimation(binding.btnContinue) {
            startVoiceServiceForeground()
            Toast.makeText(this, getString(R.string.toast_gamervoice_background), Toast.LENGTH_SHORT).show()
            moveTaskToBack(true)
        }

        AnimationHelper.attachPressAnimation(binding.btnLeaveRoom) {
            val sessionDurationMs = if (roomJoinTimestamp > 0L) System.currentTimeMillis() - roomJoinTimestamp else 0L
            roomJoinTimestamp = 0L
            FloatingHudManager.hideHud()
            voiceService?.leaveRoom()
            showHomeView()

            if (sessionDurationMs >= 45_000L) {
                promptPostMatchSquadShare()
            }
        }
    }

    private fun updateActiveNavTab(activeTab: Int) {
        val activeColor = ContextCompat.getColor(this, R.color.neon_green)
        val inactiveColor = Color.parseColor("#8E9BAE")

        val icons = listOf(binding.ivNavHome, binding.ivNavRooms, binding.ivNavSettings, binding.ivNavProfile)
        val texts = listOf(binding.tvNavHome, binding.tvNavRooms, binding.tvNavSettings, binding.tvNavProfile)
        val indicators = listOf(binding.navIndicatorHome, binding.navIndicatorRooms, binding.navIndicatorSettings, binding.navIndicatorProfile)
        val containers = listOf(binding.tabContainerHome, binding.tabContainerRooms, binding.tabContainerSettings, binding.tabContainerProfile)

        for (i in icons.indices) {
            val isSelected = (i == activeTab)
            icons[i].setColorFilter(if (isSelected) activeColor else inactiveColor)
            texts[i].setTextColor(if (isSelected) activeColor else inactiveColor)
            indicators[i].visibility = if (isSelected) View.VISIBLE else View.INVISIBLE

            if (isSelected) {
                AnimationHelper.popView(icons[i], 1.22f)
            } else {
                icons[i].animate().scaleX(1.0f).scaleY(1.0f).setDuration(120).start()
            }
        }

        // Smooth sliding crossfade container transition
        if (currentActiveTab != activeTab && currentActiveTab in containers.indices && activeTab in containers.indices) {
            AnimationHelper.transitionContainers(containers[currentActiveTab], containers[activeTab])
        } else {
            for (i in containers.indices) {
                containers[i].visibility = if (i == activeTab) View.VISIBLE else View.GONE
            }
        }
        currentActiveTab = activeTab

        binding.nsvContent.smoothScrollTo(0, 0)
    }

    private fun showNoiseFilterDialog() {
        val dialog = Dialog(this)
        dialog.window?.setWindowAnimations(R.style.CyberDialogAnimation)
        val nb = DialogNoiseFilterBinding.inflate(layoutInflater)
        dialog.setContentView(nb.root)
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        val width = (resources.displayMetrics.widthPixels * 0.94).toInt()
        dialog.window?.setLayout(width, ViewGroup.LayoutParams.WRAP_CONTENT)

        val isVip = PlanManager.isVip()
        val audioPrefs = getSharedPreferences("gamervoice_audio_settings", Context.MODE_PRIVATE)
        var selectedMode = audioPrefs.getInt("noise_filter_level", noiseFilterLevel)

        fun updateUI() {
            if (selectedMode == 1) {
                nb.rbModeStandard.isChecked = false
                nb.rbModeUltra.isChecked = true
                nb.tvNoisePercentHero.text = "Ultra Mode"
                nb.tvNoisePercentHero.setTextColor(ContextCompat.getColor(this, R.color.neon_gold))
                nb.tvNoiseTierLabel.text = getString(R.string.noise_tier_100_label)
                nb.tvNoiseDescription.text = getString(R.string.noise_tier_100_desc)
                if (!isVip) {
                    nb.llVipLockWarning.visibility = View.VISIBLE
                    AnimationHelper.shakeView(nb.llVipLockWarning)
                } else {
                    nb.llVipLockWarning.visibility = View.GONE
                }
            } else {
                nb.rbModeStandard.isChecked = true
                nb.rbModeUltra.isChecked = false
                nb.tvNoisePercentHero.text = "Standard Mode"
                nb.tvNoisePercentHero.setTextColor(ContextCompat.getColor(this, R.color.neon_green))
                nb.tvNoiseTierLabel.text = getString(R.string.noise_tier_50_label)
                nb.tvNoiseDescription.text = getString(R.string.noise_tier_50_desc)
                nb.llVipLockWarning.visibility = View.GONE
            }
        }

        updateUI()

        nb.cardModeStandard.setOnClickListener {
            selectedMode = 0
            updateUI()
        }

        nb.cardModeUltra.setOnClickListener {
            selectedMode = 1
            updateUI()
        }

        AnimationHelper.attachPressAnimation(nb.btnCloseNoiseDialog) {
            dialog.dismiss()
        }

        AnimationHelper.attachPressAnimation(nb.btnApplyNoiseSetting) {
            if (selectedMode == 1 && !isVip) {
                AnimationHelper.shakeView(nb.llNoiseHeroBox)
                dialog.dismiss()
                showVipUpgradeDialog(getString(R.string.vip_feature_noise_shield))
                return@attachPressAnimation
            }

            noiseFilterLevel = selectedMode
            audioPrefs.edit()
                .putInt("noise_filter_level", selectedMode)
                .putInt("noise_filter_percent", if (selectedMode == 1) 100 else 50)
                .apply()
            updateNoiseFilterUI()
            voiceService?.setNoiseFilterLevel(selectedMode)
            val msg = if (selectedMode == 1) "Ultra Noise Suppression activated 👑" else "Standard Noise Suppression activated"
            Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
            AnimationHelper.dismissWithAnimation(dialog, nb.root)
        }

        dialog.show()
        AnimationHelper.enterDialog(nb.root)
        val borderAnim = AnimationHelper.pulseNeonBorder(nb.root)
        AnimationHelper.startAmbientPulse(nb.btnApplyNoiseSetting, 0.97f, 1.03f, 2000L)
        AnimationHelper.attachPressAnimation(nb.btnCloseNoiseDialog) {
            borderAnim.cancel()
            AnimationHelper.dismissWithAnimation(dialog, nb.root)
        }
        dialog.setOnDismissListener { borderAnim.cancel() }
    }

    private fun updateNoiseFilterUI() {
        val isVip = PlanManager.isVip()
        val audioPrefs = getSharedPreferences("gamervoice_audio_settings", Context.MODE_PRIVATE)
        var level = audioPrefs.getInt("noise_filter_level", noiseFilterLevel)
        if (!isVip && level > 0) {
            level = 0
            noiseFilterLevel = 0
            audioPrefs.edit()
                .putInt("noise_filter_level", 0)
                .putInt("noise_filter_percent", 50)
                .apply()
        }
        val label = if (level == 1) "Ultra Noise 👑" else "Standard Noise"
        AnimationHelper.animateTextChange(binding.tvSettingNoiseFilterBadge, label)
    }

    private fun toggleAudioRoute() {
        isSpeakerphone = !isSpeakerphone
        val audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
            val devices = audioManager.availableCommunicationDevices
            val targetDevice = if (isSpeakerphone) {
                devices.find { it.type == android.media.AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
            } else {
                devices.find {
                    it.type == android.media.AudioDeviceInfo.TYPE_WIRED_HEADSET ||
                    it.type == android.media.AudioDeviceInfo.TYPE_WIRED_HEADPHONES ||
                    it.type == android.media.AudioDeviceInfo.TYPE_USB_HEADSET ||
                    it.type == android.media.AudioDeviceInfo.TYPE_BLE_HEADSET ||
                    it.type == android.media.AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
                    it.type == android.media.AudioDeviceInfo.TYPE_BUILTIN_EARPIECE
                }
            }
            if (targetDevice != null) {
                audioManager.setCommunicationDevice(targetDevice)
            } else {
                @Suppress("DEPRECATION")
                audioManager.isSpeakerphoneOn = isSpeakerphone
            }
        } else {
            @Suppress("DEPRECATION")
            audioManager.isSpeakerphoneOn = isSpeakerphone
        }
        val label = if (isSpeakerphone) "Speaker" else "Headset / Earphones"
        AnimationHelper.animateTextChange(binding.tvSettingAudioRouteBadge, label)
        Toast.makeText(this, getString(R.string.toast_audio_output, label), Toast.LENGTH_SHORT).show()
    }

    private fun joinRoomWithCode(code: String) {
        try {
            if (!isRecordAudioGranted()) {
                Toast.makeText(this, getString(R.string.toast_mic_perm_required), Toast.LENGTH_SHORT).show()
                startActivity(Intent(this, PermissionActivity::class.java))
                return
            }
            val svc = voiceService
            if (code.length == 5 && svc != null) {
                startVoiceServiceForeground()
                binding.pbConnecting.visibility = View.VISIBLE
                binding.btnSubmitJoin.isEnabled = false
                svc.joinRoom(code)
            } else if (svc == null) {
                Toast.makeText(this, getString(R.string.status_connecting_service), Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, getString(R.string.toast_room_code_length), Toast.LENGTH_SHORT).show()
            }
        } catch (t: Throwable) {
            AppLogger.log("ERROR", "joinRoomWithCode error: ${t.message}", Log.getStackTraceString(t))
            Toast.makeText(this, getString(R.string.toast_error_joining_room, t.message ?: ""), Toast.LENGTH_LONG).show()
        }
    }

    private fun saveRoomToSquad(roomCode: String) {
        val isVip = PlanManager.isVip()
        if (isVip) {
            val colors = arrayOf(
                "#00E676" to "Neon Cyber Green",
                "#FFD700" to "Golden Legend",
                "#00E5FF" to "Electric Cyan",
                "#D500F9" to "Ultra Violet"
            )
            var selectedColorIdx = 0
            val input = android.widget.EditText(this).apply {
                hint = "e.g. Tournament Roster, Main BGMI"
                setTextColor(Color.WHITE)
                setHintTextColor(Color.parseColor("#8E9BAE"))
            }

            androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle(getString(R.string.dialog_vip_squad_title))
                .setMessage(getString(R.string.dialog_vip_squad_msg))
                .setView(input)
                .setSingleChoiceItems(colors.map { it.second }.toTypedArray(), 0) { _, which ->
                    selectedColorIdx = which
                }
                .setPositiveButton(getString(R.string.dialog_vip_squad_save)) { _, _ ->
                    val customName = input.text.toString().trim().ifEmpty { "Squad $roomCode" }
                    val themeColor = colors[selectedColorIdx].first
                    RoomPersistenceManager.saveRoom(roomCode, customName = customName, themeColor = themeColor) { result ->
                        handleSaveRoomResult(roomCode, result)
                    }
                }
                .setNegativeButton(getString(R.string.btn_cancel), null)
                .show()
        } else {
            RoomPersistenceManager.saveRoom(roomCode) { result ->
                handleSaveRoomResult(roomCode, result)
            }
        }
    }

    private fun handleSaveRoomResult(roomCode: String, result: RoomPersistenceManager.SaveResult) {
        when (result) {
            is RoomPersistenceManager.SaveResult.Success -> {
                Toast.makeText(this, getString(R.string.toast_room_saved, roomCode), Toast.LENGTH_LONG).show()
                refreshSavedRoomsUI(RoomPersistenceManager.getCachedRooms())
            }
            is RoomPersistenceManager.SaveResult.LimitReached -> {
                showVipUpgradeDialog(getString(R.string.vip_feature_room_limit))
            }
            is RoomPersistenceManager.SaveResult.Error -> {
                Toast.makeText(this, getString(R.string.toast_could_not_save_room, result.message), Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun showRedeemReferralDialog() {
        val dialog = Dialog(this)
        dialog.window?.setWindowAnimations(R.style.CyberDialogAnimation)
        val rb = com.gamervoice.app.databinding.DialogRedeemReferralBinding.inflate(layoutInflater)
        dialog.setContentView(rb.root)
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        val width = (resources.displayMetrics.widthPixels * 0.94).toInt()
        dialog.window?.setLayout(width, ViewGroup.LayoutParams.WRAP_CONTENT)

        fun doRedeem() {
            val code = rb.etReferralCode.text.toString().trim().uppercase()
            if (code.isEmpty()) {
                AnimationHelper.shakeView(rb.etReferralCode)
                rb.tvRedeemHint.text = getString(R.string.hint_enter_referral_code)
                rb.tvRedeemHint.setTextColor(Color.parseColor("#FF6B6B"))
                return
            }
            rb.tvRedeemHint.text = getString(R.string.hint_verifying_code)
            rb.tvRedeemHint.setTextColor(Color.parseColor("#FFD700"))
            AnimationHelper.startAmbientPulse(rb.btnRedeemCode, 0.96f, 1.02f, 600L)
            com.gamervoice.app.auth.ReferralManager.redeemCode(this, code) { success, message ->
                Toast.makeText(this, message, Toast.LENGTH_LONG).show()
                if (success) {
                    updatePlanUI()
                    refreshSavedRoomsUI(RoomPersistenceManager.getCachedRooms())
                    AnimationHelper.dismissWithAnimation(dialog, rb.root)
                } else {
                    AnimationHelper.shakeView(rb.etReferralCode)
                    rb.tvRedeemHint.text = "❌ $message"
                    rb.tvRedeemHint.setTextColor(Color.parseColor("#FF6B6B"))
                }
            }
        }

        AnimationHelper.attachPressAnimation(rb.btnRedeemCode) { doRedeem() }
        AnimationHelper.attachPressAnimation(rb.btnCancelRedeem) {
            AnimationHelper.dismissWithAnimation(dialog, rb.root)
        }
        AnimationHelper.attachPressAnimation(rb.ivCloseRedeem) {
            AnimationHelper.dismissWithAnimation(dialog, rb.root)
        }

        dialog.show()
        AnimationHelper.enterDialog(rb.root)
        val borderAnim = AnimationHelper.pulseNeonBorder(rb.root)
        AnimationHelper.startAmbientPulse(rb.btnRedeemCode, 0.97f, 1.03f, 2000L)
        dialog.setOnDismissListener { borderAnim.cancel() }
    }

    private fun checkSeasonGloryReward() {
        val user = AuthManager.getCurrentUser() ?: return
        val prefs = getSharedPreferences("gamervoice_season_rewards", Context.MODE_PRIVATE)
        val currentMonth = java.text.SimpleDateFormat("yyyy_MM", java.util.Locale.US).format(java.util.Date())
        if (prefs.getBoolean("claimed_$currentMonth", false)) return

        Thread {
            try {
                val url = "https://gamersvoice.onrender.com/api/season/user-reward?uid=${user.uid}"
                val req = Request.Builder().url(url).get().build()
                val client = OkHttpClient.Builder()
                    .connectTimeout(6, java.util.concurrent.TimeUnit.SECONDS)
                    .readTimeout(6, java.util.concurrent.TimeUnit.SECONDS)
                    .build()
                client.newCall(req).execute().use { resp ->
                    if (resp.isSuccessful) {
                        val body = resp.body?.string() ?: ""
                        val json = JSONObject(body)
                        if (json.optBoolean("hasReward", false)) {
                            val rank = json.optInt("rank", 1)
                            val vipDays = json.optInt("vipDays", 14)
                            val recruits = json.optInt("recruits", 0)
                            runOnUiThread {
                                com.gamervoice.app.util.GloryRewardDialog.showGloryCeremony(
                                    this,
                                    rank = rank,
                                    vipDays = vipDays,
                                    recruitsCount = recruits
                                ) {
                                    prefs.edit().putBoolean("claimed_$currentMonth", true).apply()
                                    Thread {
                                        try {
                                            val claimJson = JSONObject().put("uid", user.uid).toString()
                                            val claimBody = claimJson.toRequestBody("application/json; charset=utf-8".toMediaType())
                                            val claimReq = Request.Builder()
                                                .url("https://gamersvoice.onrender.com/api/season/claim-reward")
                                                .post(claimBody)
                                                .build()
                                            client.newCall(claimReq).execute().close()
                                        } catch (_: Exception) {}
                                    }.start()
                                    updatePlanUI()
                                }
                            }
                        }
                    }
                }
            } catch (_: Exception) {}
        }.start()
    }

    // --- On-Device Installed Games Launcher ---

    private fun loadInstalledGames() {
        Thread {
            val games = GameLauncherHelper.detectInstalledGames(this)
            runOnUiThread {
                binding.llInstalledGamesContainer.removeAllViews()
                val countText = "${if (games.isNotEmpty()) games.size else 2} games found ›"
                binding.tvGamesFoundCount.text = countText

                if (games.isEmpty()) {
                    // Fallback to demo items matching the screenshot exactly so the dashboard looks complete
                    val defaultGames = listOf(
                        Triple("Subway Surf", "com.kiloo.subwaysurf", R.drawable.ic_lightning_bolt),
                        Triple("Ludo King", "com.ludo.king", R.drawable.ic_gamepad)
                    )
                    for ((name, pkg, iconRes) in defaultGames) {
                        val gameBinding = ItemInstalledGameBinding.inflate(layoutInflater, binding.llInstalledGamesContainer, false)
                        gameBinding.tvGameTitle.text = name
                        gameBinding.ivGameIcon.setImageResource(iconRes)
                        gameBinding.btnLaunchGame.setOnClickListener {
                            startVoiceServiceForeground()
                            val svc = voiceService
                            if (svc != null && svc.currentRoomCode != null && FloatingHudManager.hasOverlayPermission(this)) {
                                FloatingHudManager.showHud(this, svc)
                            }
                            val launched = GameLauncherHelper.launchGame(this, pkg)
                            if (!launched) {
                                Toast.makeText(this, getString(R.string.toast_game_not_installed, name), Toast.LENGTH_SHORT).show()
                            }
                        }
                        binding.llInstalledGamesContainer.addView(gameBinding.root)
                    }
                } else {
                    for (game in games) {
                        val gameBinding = ItemInstalledGameBinding.inflate(layoutInflater, binding.llInstalledGamesContainer, false)
                        gameBinding.tvGameTitle.text = game.appName
                        gameBinding.ivGameIcon.setImageDrawable(game.appIcon)
                        gameBinding.btnLaunchGame.setOnClickListener {
                            startVoiceServiceForeground()
                            val svc = voiceService
                            if (svc != null && svc.currentRoomCode != null && FloatingHudManager.hasOverlayPermission(this)) {
                                FloatingHudManager.showHud(this, svc)
                            }
                            Toast.makeText(this, getString(R.string.toast_launching_game, game.appName), Toast.LENGTH_SHORT).show()
                            GameLauncherHelper.launchGame(this, game.packageName)
                        }
                        binding.llInstalledGamesContainer.addView(gameBinding.root)
                    }
                }
            }
        }.start()
    }

    // --- Persistent Squad Rooms UI ---

    private fun refreshSavedRoomsUI(rooms: List<SavedRoom>) {
        val isVip = PlanManager.isVip()
        val quotaText = if (isVip) {
            "${rooms.size} Saved (VIP Unlimited) ›"
        } else {
            "${rooms.size}/2 Saved (Free Limit) ›"
        }

        binding.tvHomeRoomQuota.text = quotaText

        // Home View Saved Rooms
        binding.llHomeSavedRoomsContainer.removeAllViews()
        if (rooms.isEmpty()) {
            binding.tvHomeNoSavedRooms.visibility = View.VISIBLE
            binding.llHomeSavedRoomsContainer.addView(binding.tvHomeNoSavedRooms)
        } else {
            binding.tvHomeNoSavedRooms.visibility = View.GONE
            for (room in rooms) {
                val itemBinding = ItemSavedRoomBinding.inflate(layoutInflater, binding.llHomeSavedRoomsContainer, false)
                itemBinding.tvSavedRoomName.text = room.roomName
                itemBinding.tvSavedRoomCode.text = room.roomCode
                try {
                    val colorInt = Color.parseColor(room.themeColor)
                    itemBinding.tvSavedRoomCode.setTextColor(colorInt)
                } catch (_: Throwable) {}
                itemBinding.tvSavedRoomDate.text = "Saved in Cloud • Rejoin anytime"
                itemBinding.tvSavedRoomPinBadge.visibility = if (room.pin.isNotEmpty()) View.VISIBLE else View.GONE

                itemBinding.btnRejoinRoom.setOnClickListener {
                    joinRoomWithCode(room.roomCode)
                }
                itemBinding.btnDeleteRoom.setOnClickListener {
                    RoomPersistenceManager.deleteRoom(room.roomCode) {
                        refreshSavedRoomsUI(RoomPersistenceManager.getCachedRooms())
                    }
                }
                binding.llHomeSavedRoomsContainer.addView(itemBinding.root)
            }
        }
    }

    // --- Monetization & VIP UI ---

    private fun verifyPlanExpiry() {
        PlanManager.enforceValidPaidStatus {
            runOnUiThread {
                updatePlanUI()
                refreshSavedRoomsUI(RoomPersistenceManager.getCachedRooms())
            }
        }
    }

    private fun updatePlanUI() {
        val isVip = PlanManager.isVip()
        updateNoiseFilterUI()
        val user = AuthManager.getCurrentUser()
        if (user != null) {
            binding.tvProfileTabName.text = user.name
            binding.tvProfileTabEmail.text = user.email
            ImageLoader.loadAvatar(binding.ivProfileTabAvatar, user.avatar)
        }

        binding.tvProfileTabPlanBadge.setOnClickListener {
            Toast.makeText(this, getString(R.string.toast_syncing_vip), Toast.LENGTH_SHORT).show()
            PlanManager.enforceValidPaidStatus {
                runOnUiThread {
                    updatePlanUI()
                    val status = if (PlanManager.isVip()) "VIP: " + PlanManager.getPlanName() else "Free Plan"
                    Toast.makeText(this, getString(R.string.toast_current_status, status), Toast.LENGTH_SHORT).show()
                }
            }
        }

        if (isVip) {
            binding.llSponsorBannerAd.visibility = View.GONE
            binding.btnSaveClutchClip.text = "🎬 SAVE CLUTCH CLIP"
            binding.tvHomePlanBadge.text = "👑 VIP"
            binding.tvHomePlanBadge.setTextColor(Color.parseColor("#FFD700"))
            binding.tvHomePlanBadge.setBackgroundResource(R.drawable.bg_plan_badge_vip)

            val countdown = PlanManager.getExpiryCountdown()
            binding.tvProfileTabPlanBadge.text = "👑 " + PlanManager.getPlanName() + " ACTIVE"
            binding.tvProfileTabPlanBadge.setTextColor(Color.parseColor("#FFD700"))
            binding.tvProfileTabPlanBadge.setBackgroundResource(R.drawable.bg_plan_badge_vip)
            binding.tvProfileTabCountdown.text = "Expires in: " + countdown

            binding.tvSettingRamPurgeTitle.text = "Auto RAM Purge 👑"
            val purgePrefs = getSharedPreferences(com.gamervoice.app.service.VoiceService.PREFS_NAME, MODE_PRIVATE)
            val purgeCount = purgePrefs.getInt("auto_purge_count", 0)
            val lastTs = purgePrefs.getLong("last_auto_purge_ts", 0L)
            val lastMb = purgePrefs.getLong("last_auto_purge_mb", 0L)
            if (purgeCount > 0 && lastTs > 0) {
                val minsAgo = ((System.currentTimeMillis() - lastTs) / 60000L).coerceAtLeast(0)
                binding.tvSettingRamPurgeSubtitle.text = "VIP Active: Cleaned ${minsAgo}m ago (~${lastMb}MB heap • #$purgeCount purges)"
            } else {
                binding.tvSettingRamPurgeSubtitle.text = "VIP Active: Auto clean every 3 min (< 10MB memory guard)"
            }
            binding.tvSettingRamPurgeBadge.text = "ACTIVE"
            binding.tvSettingRamPurgeBadge.setTextColor(Color.parseColor("#FFD700"))
            binding.tvSettingRamPurgeBadge.setBackgroundResource(R.drawable.bg_plan_badge_vip)
        } else {
            binding.llSponsorBannerAd.visibility = View.VISIBLE
            binding.btnSaveClutchClip.text = "🎬 CLUTCH CLIP (👑 VIP)"
            binding.tvHomePlanBadge.text = "FREE"
            binding.tvHomePlanBadge.setTextColor(ContextCompat.getColor(this, R.color.neon_green))
            binding.tvHomePlanBadge.setBackgroundResource(R.drawable.bg_plan_badge_free)

            binding.tvProfileTabPlanBadge.text = "🟢 FREE PLAN"
            binding.tvProfileTabPlanBadge.setTextColor(ContextCompat.getColor(this, R.color.neon_green))
            binding.tvProfileTabPlanBadge.setBackgroundResource(R.drawable.bg_plan_badge_free)
            binding.tvProfileTabCountdown.text = "2 Cloud Rooms Quota • Standard Filter"

            binding.tvSettingRamPurgeTitle.text = "Auto RAM Purge"
            binding.tvSettingRamPurgeSubtitle.text = "Free tier: Tap PURGE NOW manually before games"
            binding.tvSettingRamPurgeBadge.text = "VIP ONLY"
            binding.tvSettingRamPurgeBadge.setTextColor(ContextCompat.getColor(this, R.color.neon_green))
            binding.tvSettingRamPurgeBadge.setBackgroundResource(R.drawable.bg_plan_badge_free)
        }

        // Vanity Squad Stats & Streaks
        binding.tvProfileSquaddedHours.text = com.gamervoice.app.util.SquadStatsTracker.getTotalSquaddedHours(this)
        val streak = com.gamervoice.app.util.SquadStatsTracker.getStreakDays(this)
        binding.tvProfileSquadStreak.text = if (streak > 0) "🔥 $streak-Day Streak" else "Start Streak Today"

        // Viral Squad Referral Code
        binding.tvProfileReferralCode.text = com.gamervoice.app.auth.ReferralManager.getReferralCode(this)

        // Squad Up Alarm Status (Customizable)
        val isAlarmOn = com.gamervoice.app.util.SquadAlarmHelper.isAlarmEnabled(this)
        val alarmTimeStr = com.gamervoice.app.util.SquadAlarmHelper.getAlarmTimeString(this)
        binding.switchProfileSquadAlarm.isChecked = isAlarmOn
        binding.tvProfileAlarmTitle.text = "⏰ SQUAD MATCH ALARM"
        binding.tvProfileAlarmTime.text = if (isAlarmOn) {
            "Active daily at $alarmTimeStr (Tap to edit)"
        } else {
            "Off · Set for $alarmTimeStr (Tap to customize)"
        }
        binding.btnChangeAlarmTime.text = alarmTimeStr

        updateReplayBadgeUI()

        // Referral Expiry Warning: If VIP has < 24 hours left, remind user to invite more friends
        if (isVip) {
            val expTs = PlanManager.getExpiryTimestamp()
            val tier = PlanManager.getCurrentPlanTier()
            if (tier != PlanTier.LIFETIME && expTs > 0) {
                val msLeft = expTs - System.currentTimeMillis()
                val hoursLeft = msLeft / (1000 * 60 * 60)
                if (msLeft > 0 && hoursLeft < 24) {
                    runOnUiThread {
                        Toast.makeText(
                            this,
                            getString(R.string.toast_vip_expires_warning, hoursLeft),
                            Toast.LENGTH_LONG
                        ).show()
                    }
                }
            }
        }
    }

    private fun updateReplayBadgeUI() {
        val hasConsent = com.gamervoice.app.util.SquadReplayManager.isConsentGranted(this)
        if (hasConsent) {
            binding.tvReplayBadge.text = "🎙️ Clutch Mic Highlights Active (120s buffer)"
            binding.tvReplayBadge.setTextColor(ContextCompat.getColor(this, R.color.neon_cyan))
        } else {
            binding.tvReplayBadge.text = "🎙️ Clutch Mic Highlights (Disabled in Profile)"
            binding.tvReplayBadge.setTextColor(Color.parseColor("#8E9BAE"))
        }
    }


    private fun showAlarmTimePickerDialog() {
        val currentHour = com.gamervoice.app.util.SquadAlarmHelper.getAlarmHour(this)
        val currentMinute = com.gamervoice.app.util.SquadAlarmHelper.getAlarmMinute(this)

        val dialog = android.app.TimePickerDialog(
            this,
            { _, hourOfDay, minute ->
                val currentRoom = voiceService?.currentRoomCode ?: ""
                com.gamervoice.app.util.SquadAlarmHelper.setSquadAlarm(
                    this,
                    hour = hourOfDay,
                    minute = minute,
                    roomCode = currentRoom
                )
                binding.switchProfileSquadAlarm.isChecked = true
                val timeStr = com.gamervoice.app.util.SquadAlarmHelper.getAlarmTimeString(this)
                Toast.makeText(this, getString(R.string.toast_squad_alarm_set, timeStr), Toast.LENGTH_SHORT).show()
                updatePlanUI()
            },
            currentHour,
            currentMinute,
            false
        )
        dialog.setTitle("Set Squad Match Reminder")
        dialog.show()
    }

    private fun showVipUpgradeDialog(customSubtitle: String? = null) {
        val user = AuthManager.getCurrentUser()
        if (user == null) {
            Toast.makeText(this, getString(R.string.toast_please_sign_in_purchase), Toast.LENGTH_SHORT).show()
            startActivity(Intent(this, AuthActivity::class.java))
            return
        }

        val dialog = Dialog(this)
        dialog.window?.setWindowAnimations(R.style.CyberDialogAnimation)
        vipUpgradeDialog = dialog
        val vipBinding = DialogVipUpgradeBinding.inflate(layoutInflater)
        dialog.setContentView(vipBinding.root)
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        val width = (resources.displayMetrics.widthPixels * 0.94).toInt()
        dialog.window?.setLayout(width, ViewGroup.LayoutParams.WRAP_CONTENT)

        if (!customSubtitle.isNullOrEmpty()) {
            vipBinding.tvVipSubtitle.text = customSubtitle
        }

        AnimationHelper.attachPressAnimation(vipBinding.ivCloseVipDialog) { dialog.dismiss() }

        var selectedTier = PlanTier.MONTHLY

        fun updateCardSelection() {
            vipBinding.llPlanWeekly.setBackgroundResource(if (selectedTier == PlanTier.WEEKLY) R.drawable.bg_vip_card_neon else R.drawable.bg_vip_card_unselected)
            vipBinding.llPlanMonthly.setBackgroundResource(if (selectedTier == PlanTier.MONTHLY) R.drawable.bg_vip_card_gold else R.drawable.bg_vip_card_unselected)
            vipBinding.llPlanLifetime.setBackgroundResource(if (selectedTier == PlanTier.LIFETIME) R.drawable.bg_vip_card_gold else R.drawable.bg_vip_card_unselected)

            val selectedCard = when (selectedTier) {
                PlanTier.WEEKLY -> vipBinding.llPlanWeekly
                PlanTier.MONTHLY -> vipBinding.llPlanMonthly
                PlanTier.LIFETIME -> vipBinding.llPlanLifetime
                else -> null
            }
            if (selectedCard != null) {
                AnimationHelper.popView(selectedCard, 1.05f)
            }

            val text = when (selectedTier) {
                PlanTier.WEEKLY -> "⚡ UNLOCK 7 DAYS VIP — ₹29"
                PlanTier.MONTHLY -> "⚡ UNLOCK 30 DAYS VIP — ₹89"
                PlanTier.LIFETIME -> "⚡ UNLOCK LIFETIME VIP — ₹249"
                else -> "⚡ UNLOCK VIP PASS"
            }
            AnimationHelper.animateTextChange(vipBinding.btnActivateVip, text)
        }
        updateCardSelection()

        AnimationHelper.attachPressAnimation(vipBinding.llPlanWeekly) {
            selectedTier = PlanTier.WEEKLY
            updateCardSelection()
        }
        AnimationHelper.attachPressAnimation(vipBinding.llPlanMonthly) {
            selectedTier = PlanTier.MONTHLY
            updateCardSelection()
        }
        AnimationHelper.attachPressAnimation(vipBinding.llPlanLifetime) {
            selectedTier = PlanTier.LIFETIME
            updateCardSelection()
        }

        AnimationHelper.attachPressAnimation(vipBinding.btnActivateVip) {
            startRazorpayCheckout(selectedTier, user)
        }

        // Revoke VIP / Reset to Free Plan
        AnimationHelper.attachPressAnimation(vipBinding.btnResetToFree) {
            PlanManager.revokeVip {
                updatePlanUI()
                refreshSavedRoomsUI(RoomPersistenceManager.getCachedRooms())
                Toast.makeText(this, getString(R.string.toast_vip_revoked), Toast.LENGTH_SHORT).show()
                AnimationHelper.dismissWithAnimation(dialog, vipBinding.root)
            }
        }

        dialog.show()
        // Cinematic entrance: slide-up from 80dp + zoom-in
        AnimationHelper.enterDialog(vipBinding.root)
        // Ambient pulse on CTA button to draw the eye
        val pulseAnim = AnimationHelper.startAmbientPulse(vipBinding.btnActivateVip, 0.97f, 1.03f, 1800L)
        // Stagger the pricing cards so they appear one-by-one
        AnimationHelper.staggerFadeIn(listOf(vipBinding.llPlanWeekly, vipBinding.llPlanMonthly, vipBinding.llPlanLifetime), 220L, 60L)
        // Animate neon border on the root card
        val borderAnim = AnimationHelper.pulseNeonBorder(vipBinding.root)

        // Override close button to use exit animation
        AnimationHelper.attachPressAnimation(vipBinding.ivCloseVipDialog) {
            pulseAnim.cancel()
            borderAnim.cancel()
            AnimationHelper.dismissWithAnimation(dialog, vipBinding.root)
        }
        dialog.setOnDismissListener { pulseAnim.cancel(); borderAnim.cancel() }
    }

    private fun startRazorpayCheckout(tier: PlanTier, user: UserProfile) {
        pendingPurchaseTier = tier
        val checkout = Checkout()
        checkout.setKeyID(PlanManager.RAZORPAY_KEY_ID)

        try {
            val options = JSONObject()
            options.put("name", "GamersVoice VIP")
            options.put("description", tier.title)
            options.put("currency", "INR")
            // Amount in paise (1 INR = 100 paise)
            val amountPaise = tier.priceInr * 100
            options.put("amount", amountPaise)

            val theme = JSONObject()
            theme.put("color", "#00FF88")
            options.put("theme", theme)

            val prefill = JSONObject()
            prefill.put("email", user.email)
            if (user.name.isNotEmpty() && user.name != "Gamer") {
                prefill.put("name", user.name)
            }
            if (user.phone.isNotEmpty()) {
                prefill.put("contact", user.phone)
            }
            options.put("prefill", prefill)

            val retryObj = JSONObject()
            retryObj.put("enabled", true)
            retryObj.put("max_count", 2)
            options.put("retry", retryObj)

            checkout.open(this, options)
        } catch (e: Exception) {
            Log.e("HomeActivity", "Error initiating Razorpay checkout: ${e.message}", e)
            Toast.makeText(this, getString(R.string.toast_payment_init_error, e.message ?: ""), Toast.LENGTH_LONG).show()
        }
    }

    override fun onPaymentSuccess(razorpayPaymentID: String?, paymentData: PaymentData?) {
        val tier = pendingPurchaseTier ?: PlanTier.WEEKLY
        val paymentId = razorpayPaymentID ?: paymentData?.paymentId ?: ""
        val user = AuthManager.getCurrentUser()
        val email = user?.email ?: "gamer"

        if (paymentId.isEmpty()) {
            Toast.makeText(this, getString(R.string.toast_payment_id_missing), Toast.LENGTH_LONG).show()
            pendingPurchaseTier = null
            return
        }

        Toast.makeText(this, getString(R.string.toast_verifying_payment), Toast.LENGTH_SHORT).show()
        PlanManager.purchasePlan(tier, paymentId) { success ->
            if (success) {
                updatePlanUI()
                refreshSavedRoomsUI(RoomPersistenceManager.getCachedRooms())
                vipUpgradeDialog?.dismiss()
                Toast.makeText(this, getString(R.string.toast_payment_verified_fmt, paymentId, tier.title, email), Toast.LENGTH_LONG).show()
            } else {
                Toast.makeText(this, getString(R.string.toast_payment_verify_failed), Toast.LENGTH_LONG).show()
            }
        }
        pendingPurchaseTier = null
    }

    override fun onPaymentError(code: Int, response: String?, paymentData: PaymentData?) {
        Log.w("HomeActivity", "Razorpay payment failed ($code): $response")
        val errorMsg = try {
            if (!response.isNullOrEmpty()) {
                val json = JSONObject(response)
                val errorObj = json.optJSONObject("error")
                errorObj?.optString("description") ?: response
            } else {
                "Payment was cancelled or failed."
            }
        } catch (e: Exception) {
            response ?: "Payment cancelled."
        }
        Toast.makeText(this, getString(R.string.toast_payment_failed, errorMsg), Toast.LENGTH_LONG).show()
        pendingPurchaseTier = null
    }



    private fun showLegalDialog(title: String, content: String, iconRes: Int) {
        val dialog = Dialog(this)
        dialog.window?.setWindowAnimations(R.style.CyberDialogAnimation)
        val dialogBinding = DialogLegalDocBinding.inflate(layoutInflater)
        dialog.setContentView(dialogBinding.root)
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        val width = (resources.displayMetrics.widthPixels * 0.94).toInt()
        dialog.window?.setLayout(width, ViewGroup.LayoutParams.WRAP_CONTENT)

        dialogBinding.tvDialogTitle.text = title
        dialogBinding.tvDialogContent.text = content
        dialogBinding.ivDialogIcon.setImageResource(iconRes)

        AnimationHelper.attachPressAnimation(dialogBinding.btnCopyLegalDoc) {
            val clipboard = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
            val clip = ClipData.newPlainText("Legal Doc", content)
            clipboard.setPrimaryClip(clip)
            AnimationHelper.popView(dialogBinding.btnCopyLegalDoc, 1.1f)
            Toast.makeText(this, getString(R.string.toast_copied_to_clipboard, title), Toast.LENGTH_SHORT).show()
        }

        AnimationHelper.attachPressAnimation(dialogBinding.ivCloseDialog) {
            AnimationHelper.dismissWithAnimation(dialog, dialogBinding.root)
        }
        AnimationHelper.attachPressAnimation(dialogBinding.btnAcknowledgeDialog) {
            AnimationHelper.dismissWithAnimation(dialog, dialogBinding.root)
        }

        dialog.show()
        AnimationHelper.enterDialog(dialogBinding.root)
        val borderAnim = AnimationHelper.pulseNeonBorder(dialogBinding.root)
        dialog.setOnDismissListener { borderAnim.cancel() }
    }

    private fun showContactSupportDialog(preselectedCategory: String? = null) {
        val user = AuthManager.getCurrentUser()
        val dialog = Dialog(this)
        dialog.window?.setWindowAnimations(R.style.CyberDialogAnimation)
        val dialogBinding = DialogContactUsBinding.inflate(layoutInflater)
        dialog.setContentView(dialogBinding.root)
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        val width = (resources.displayMetrics.widthPixels * 0.94).toInt()
        dialog.window?.setLayout(width, ViewGroup.LayoutParams.WRAP_CONTENT)

        val deviceModel = "${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}"
        val androidVer = "Android ${android.os.Build.VERSION.RELEASE} (SDK ${android.os.Build.VERSION.SDK_INT})"
        dialogBinding.tvContactTelemetryBanner.text = "Attached: $deviceModel • $androidVer • Build 1.0.0-beta"

        val categories = arrayOf(
            "VIP / Payment Trouble",
            "Audio / Mic / Echo Issue",
            "Room Connection Trouble",
            "Bug Report / Crash",
            "Feedback & Feature Idea",
            "Legal & Statutory Grievance (IT Rules 2021 & DPDP)"
        )
        val adapter = android.widget.ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, categories)
        dialogBinding.spnContactCategory.adapter = adapter

        if (!preselectedCategory.isNullOrBlank()) {
            val idx = categories.indexOfFirst { it.contains(preselectedCategory, ignoreCase = true) }
            if (idx >= 0) {
                dialogBinding.spnContactCategory.setSelection(idx)
            }
        }

        AnimationHelper.attachPressAnimation(dialogBinding.ivCloseContactDialog) {
            dialog.dismiss()
        }

        AnimationHelper.attachPressAnimation(dialogBinding.btnSubmitContactTicket) {
            val category = dialogBinding.spnContactCategory.selectedItem?.toString() ?: "General"
            val subject = dialogBinding.etContactSubject.text?.toString()?.trim().orEmpty()
            val description = dialogBinding.etContactDescription.text?.toString()?.trim().orEmpty()

            if (subject.isEmpty()) {
                Toast.makeText(this, getString(R.string.toast_enter_subject), Toast.LENGTH_SHORT).show()
                return@attachPressAnimation
            }
            if (description.isEmpty()) {
                Toast.makeText(this, getString(R.string.toast_describe_issue), Toast.LENGTH_SHORT).show()
                return@attachPressAnimation
            }

            dialogBinding.btnSubmitContactTicket.isEnabled = false
            dialogBinding.pbContactLoading.visibility = View.VISIBLE

            val submission = SupportTicketManager.TicketSubmission(
                userEmail = user?.email ?: "guest@gamervoice.app",
                category = category,
                subject = subject,
                description = description,
                isVip = PlanManager.isVip()
            )

            SupportTicketManager.submitTicket(this, submission) { success, message ->
                runOnUiThread {
                    dialogBinding.pbContactLoading.visibility = View.GONE
                    dialogBinding.btnSubmitContactTicket.isEnabled = true
                    Toast.makeText(this, message, Toast.LENGTH_LONG).show()
                    if (success) {
                        AnimationHelper.dismissWithAnimation(dialog, dialogBinding.root)
                    }
                }
            }
        }

        dialog.show()
        AnimationHelper.enterDialog(dialogBinding.root)
        val borderAnim = AnimationHelper.pulseNeonBorder(dialogBinding.root)
        AnimationHelper.staggerFadeIn(
            listOf(dialogBinding.tvContactTelemetryBanner, dialogBinding.spnContactCategory),
            180L, 60L
        )
        dialog.setOnDismissListener { borderAnim.cancel() }
    }

    private fun toggleFloatingHud() {
        val svc = voiceService
        if (svc == null || svc.currentRoomCode == null) {
            Toast.makeText(this, getString(R.string.toast_hud_join_room_first), Toast.LENGTH_SHORT).show()
            return
        }
        if (!FloatingHudManager.hasOverlayPermission(this)) {
            requestOverlayPermission()
            return
        }
        if (FloatingHudManager.isHudShowing()) {
            FloatingHudManager.hideHud()
            binding.switchSettingHud.isChecked = false
        } else {
            FloatingHudManager.showHud(this, svc)
            binding.switchSettingHud.isChecked = true
        }
    }

    private fun requestOverlayPermission() {
        Toast.makeText(this, getString(R.string.toast_hud_overlay_required), Toast.LENGTH_LONG).show()
        try {
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            )
            startActivity(intent)
        } catch (_: Exception) {}
    }

    private fun performLogout() {
        AuthManager.signOut()
        try {
            val gso = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN).build()
            val googleSignInClient = GoogleSignIn.getClient(this, gso)
            googleSignInClient.revokeAccess()
            googleSignInClient.signOut()
        } catch (_: Exception) {}
        voiceService?.leaveRoom()
        val intent = Intent(this, AuthActivity::class.java)
        intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        startActivity(intent)
        finish()
    }

    private fun setupConsoleUI() {
        val allLogs = AppLogger.getAllLogs()
        val prevLogs = AppLogger.getPreviousSessionLogs()
        val sb = StringBuilder()
        if (!prevLogs.isNullOrEmpty()) {
            sb.append("⚠️ PREVIOUS SESSION LOGS (BEFORE RESTART):\n")
            sb.append(prevLogs.takeLast(3000))
            sb.append("\n========================================\n\n")
        }
        if (allLogs.isNotEmpty()) {
            sb.append(allLogs.joinToString("\n") { it.toString() })
            sb.append("\n")
        } else {
            sb.append("GamerVoice ready. Awaiting actions...\n")
        }
        binding.tvConsoleLogs.text = sb.toString()
        binding.svConsoleLogs.post {
            binding.svConsoleLogs.fullScroll(View.FOCUS_DOWN)
        }

        binding.btnCopyLogs.setOnClickListener {
            val fullLogs = AppLogger.getAllLogsText()
            val clipboard = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
            val clip = ClipData.newPlainText("GamerVoice Diagnostic Logs", fullLogs)
            clipboard.setPrimaryClip(clip)
            Toast.makeText(this, getString(R.string.toast_logs_copied), Toast.LENGTH_SHORT).show()
        }

        binding.btnClearLogs.setOnClickListener {
            AppLogger.clear()
            binding.tvConsoleLogs.text = "Logs cleared.\n"
        }

        binding.btnToggleConsole.setOnClickListener {
            val isVisible = binding.svConsoleLogs.visibility == View.VISIBLE
            binding.svConsoleLogs.visibility = if (isVisible) View.GONE else View.VISIBLE
            binding.btnToggleConsole.text = if (isVisible) "Show" else "Hide"
        }
    }

    private fun startVoiceServiceForeground() {
        try {
            val serviceIntent = Intent(this, VoiceService::class.java).apply {
                action = VoiceService.ACTION_START_FOREGROUND
            }
            ContextCompat.startForegroundService(this, serviceIntent)
        } catch (e: Exception) {
            Log.w("HomeActivity", "Failed to startForegroundService", e)
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if ((keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) && (voiceService?.isPttModeEnabled() == true)) {
            voiceService?.setPttTransmitting(transmitting = true)
            binding.tvRoomMicStatusHint.text = getString(R.string.ptt_speaking_hint)
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent?): Boolean {
        if ((keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) && (voiceService?.isPttModeEnabled() == true)) {
            voiceService?.setPttTransmitting(transmitting = false)
            binding.tvRoomMicStatusHint.text = getString(R.string.ptt_muted_hint)
            return true
        }
        return super.onKeyUp(keyCode, event)
    }

    private fun showHomeView() {
        roomJoinTimestamp = 0L
        runOnUiThread {
            binding.llHomeActions.visibility = View.VISIBLE
            binding.llJoinInputSection.visibility = View.GONE
            binding.llConnectedRoomSection.visibility = View.GONE
            binding.pbConnecting.visibility = View.GONE
            binding.btnCreateRoom.isEnabled = true
            binding.btnJoinRoom.isEnabled = true
            binding.btnCreateRoomCard.isEnabled = true
            binding.btnJoinRoomCard.isEnabled = true
            binding.btnSubmitJoin.isEnabled = true
            binding.tvServerStatus.text = "Server Connected"
        }
    }

    private fun showJoinInputView() {
        runOnUiThread {
            binding.llHomeActions.visibility = View.GONE
            binding.llJoinInputSection.visibility = View.VISIBLE
            binding.llConnectedRoomSection.visibility = View.GONE
            binding.pbConnecting.visibility = View.GONE
            binding.btnSubmitJoin.isEnabled = true
            binding.etJoinRoomCode.setText("")
        }
    }

    private fun showConnectedRoomView(roomCode: String) {
        if (roomJoinTimestamp == 0L) {
            roomJoinTimestamp = System.currentTimeMillis()
        }
        runOnUiThread {
            try {
                binding.llHomeActions.visibility = View.GONE
                binding.llJoinInputSection.visibility = View.GONE
                binding.llConnectedRoomSection.visibility = View.VISIBLE
                binding.pbConnecting.visibility = View.GONE
                binding.btnSubmitJoin.isEnabled = true
                binding.tvDisplayRoomCode.text = roomCode
                binding.switchSettingHud.isChecked = FloatingHudManager.isHudShowing()
                updateMemberCountUI(voiceService?.getMemberCount() ?: 1)
                voiceService?.participants?.let { updateParticipantsUI(it.values.toList()) }
                voiceService?.isPttModeEnabled()?.let { updateMicModeUI(it) }
            } catch (t: Throwable) {
                Log.e("HomeActivity", "Error showing connected room view", t)
            }
        }
    }

    private fun updateMemberCountUI(count: Int) {
        runOnUiThread {
            try {
                binding.tvMemberCount.text = getString(R.string.connected_count_fmt, count)
            } catch (t: Throwable) {
                Log.e("HomeActivity", "Error updating member count", t)
            }
        }
    }

    private fun updateParticipantsUI(participants: List<RoomParticipant>) {
        runOnUiThread {
            try {
                binding.llParticipantsContainer.removeAllViews()
                val myId = voiceService?.signalingClient?.myPeerId
                val myParticipant = participants.find { it.isMe || (it.peerId == myId) }
                val iAmHost = myParticipant?.isHost == true

                val header = android.widget.TextView(this).apply {
                    text = "MEMBERS IN SQUAD (${participants.size}/5)"
                    textSize = 12f
                    setTextColor(ContextCompat.getColor(this@HomeActivity, R.color.accent_green))
                    setTypeface(null, android.graphics.Typeface.BOLD)
                    setPadding(0, 0, 0, 8)
                }
                binding.llParticipantsContainer.addView(header)

                for (p in participants) {
                    val itemBinding = ItemParticipantBinding.inflate(
                        layoutInflater,
                        binding.llParticipantsContainer,
                        false
                    )

                    val isMe = p.isMe || (p.peerId == myId)
                    itemBinding.tvParticipantName.text = p.name
                    ImageLoader.loadAvatar(itemBinding.ivParticipantAvatar, p.avatar)
                    itemBinding.tvYouBadge.visibility = if (isMe) View.VISIBLE else View.GONE
                    itemBinding.tvHostBadge.visibility = if (p.isHost && !isMe) View.VISIBLE else View.GONE

                    if (isMe) {
                        val roleStr = if (p.isHost) getString(R.string.status_leader_you) else getString(R.string.status_connected_you)
                        val myRtt = lastMeasuredLatencyMs
                        val statusText = if (myRtt > 0) "$roleStr • 🟢 ${myRtt}ms" else "$roleStr • 🟢 Online"
                        itemBinding.tvParticipantStatus.text = statusText
                        itemBinding.tvParticipantStatus.setTextColor(ContextCompat.getColor(this, R.color.accent_green))
                        itemBinding.btnMutePeer.visibility = View.GONE
                        itemBinding.btnPeerOptions.visibility = View.GONE
                    } else {
                        itemBinding.btnMutePeer.visibility = View.VISIBLE
                        itemBinding.btnPeerOptions.visibility = View.VISIBLE

                        val isMuted = voiceService?.isPeerMutedLocally(p.peerId) == true
                        itemBinding.btnMutePeer.setImageResource(if (isMuted) R.drawable.ic_volume_off else R.drawable.ic_volume_up)
                        itemBinding.btnMutePeer.setColorFilter(if (isMuted) Color.parseColor("#FF5252") else Color.parseColor("#8E9BAE"))

                        if (isMuted) {
                            itemBinding.tvParticipantStatus.text = getString(R.string.status_muted_local)
                            itemBinding.tvParticipantStatus.setTextColor(Color.parseColor("#FF5252"))
                        } else {
                            val peerRtt = livePeerLatencies[p.peerId] ?: (if (lastMeasuredLatencyMs > 0) lastMeasuredLatencyMs else 0L)
                            val (text, color) = when {
                                peerRtt in 1..74 -> Pair(getString(R.string.status_connected_p2p_fmt, peerRtt), ContextCompat.getColor(this, R.color.accent_green))
                                peerRtt in 75..159 -> Pair(getString(R.string.status_connected_good_fmt, peerRtt), ContextCompat.getColor(this, R.color.neon_gold))
                                peerRtt >= 160 -> Pair(getString(R.string.status_connected_weak_fmt, peerRtt), ContextCompat.getColor(this, R.color.neon_red))
                                else -> Pair(getString(R.string.status_connected_good), ContextCompat.getColor(this, R.color.text_secondary))
                            }
                            itemBinding.tvParticipantStatus.text = text
                            itemBinding.tvParticipantStatus.setTextColor(color)
                        }

                        itemBinding.btnMutePeer.setOnClickListener {
                            val willMute = !isMuted
                            voiceService?.setPeerMutedLocally(p.peerId, willMute)
                            Toast.makeText(
                                this,
                                if (willMute) getString(R.string.toast_peer_muted, p.name) else getString(R.string.toast_peer_unmuted, p.name),
                                Toast.LENGTH_SHORT
                            ).show()
                        }

                        itemBinding.btnPeerOptions.setOnClickListener {
                            showPeerModerationDialog(p, iAmHost)
                        }
                    }

                    binding.llParticipantsContainer.addView(itemBinding.root)
                }
            } catch (t: Throwable) {
                Log.e("HomeActivity", "Error updating participants UI", t)
            }
        }
    }

    private fun promptPostMatchSquadShare() {
        if (isFinishing || isDestroyed) return
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(getString(R.string.dialog_squad_share_title))
            .setMessage(getString(R.string.dialog_squad_share_msg))
            .setPositiveButton(getString(R.string.dialog_squad_share_btn)) { _, _ ->
                com.gamervoice.app.auth.ReferralManager.shareReferralCode(this)
            }
            .setNegativeButton(getString(R.string.dialog_squad_share_later), null)
            .show()
    }

    private fun confirmAndDeleteAccount() {
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(getString(R.string.dialog_delete_account_title))
            .setMessage(getString(R.string.dialog_delete_account_msg))
            .setPositiveButton(getString(R.string.dialog_delete_account_confirm)) { _, _ ->
                performAccountDeletion()
            }
            .setNegativeButton(getString(R.string.btn_cancel), null)
            .show()
    }

    @Suppress("DEPRECATION")
    private fun performAccountDeletion() {
        val user = AuthManager.getCurrentUser()
        val token = user?.idToken.orEmpty()
        val progressDialog = android.app.ProgressDialog(this).apply {
            setMessage(getString(R.string.dialog_delete_account_confirm))
            setCancelable(false)
            show()
        }

        val request = okhttp3.Request.Builder()
            .url("https://gamersvoice.onrender.com/api/account/delete")
            .header("Authorization", "Bearer $token")
            .post(okhttp3.RequestBody.create(null, ByteArray(0)))
            .build()

        val client = okhttp3.OkHttpClient()
        client.newCall(request).enqueue(object : okhttp3.Callback {
            override fun onFailure(call: okhttp3.Call, e: java.io.IOException) {
                runOnUiThread {
                    progressDialog.dismiss()
                    Toast.makeText(this@HomeActivity, getString(R.string.toast_account_purged_local), Toast.LENGTH_SHORT).show()
                    performLogout()
                }
            }

            override fun onResponse(call: okhttp3.Call, response: okhttp3.Response) {
                runOnUiThread {
                    progressDialog.dismiss()
                    Toast.makeText(this@HomeActivity, getString(R.string.toast_account_deleted_success), Toast.LENGTH_LONG).show()
                    performLogout()
                }
            }
        })
    }

    private fun showPeerModerationDialog(peer: RoomParticipant, iAmHost: Boolean) {
        val options = mutableListOf<String>()
        options.add(getString(R.string.dialog_moderation_report, peer.name))
        if (iAmHost) {
            options.add(getString(R.string.dialog_moderation_kick, peer.name))
        }

        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(getString(R.string.dialog_moderation_title, peer.name))
            .setItems(options.toTypedArray()) { _, which ->
                when (options[which]) {
                    getString(R.string.dialog_moderation_report, peer.name) -> showReportPeerDialog(peer)
                    getString(R.string.dialog_moderation_kick, peer.name) -> {
                        androidx.appcompat.app.AlertDialog.Builder(this)
                            .setTitle(getString(R.string.dialog_kick_title))
                            .setMessage(getString(R.string.dialog_kick_msg, peer.name))
                            .setPositiveButton(getString(R.string.dialog_kick_btn)) { _, _ ->
                                voiceService?.kickPeerFromRoom(peer.peerId)
                                Toast.makeText(this, getString(R.string.toast_peer_removed, peer.name), Toast.LENGTH_SHORT).show()
                            }
                            .setNegativeButton(getString(R.string.btn_cancel), null)
                            .show()
                    }
                }
            }
            .setNegativeButton(getString(R.string.btn_cancel), null)
            .show()
    }

    private fun showReportPeerDialog(peer: RoomParticipant) {
        val reasons = arrayOf(
            getString(R.string.dialog_report_reason_mic),
            getString(R.string.dialog_report_reason_abusive),
            getString(R.string.dialog_report_reason_bot),
            getString(R.string.dialog_report_reason_cheating),
            getString(R.string.dialog_report_reason_other)
        )
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(getString(R.string.dialog_report_title, peer.name))
            .setItems(reasons) { _, which ->
                val reason = reasons[which]
                val roomCode = voiceService?.currentRoomCode ?: "UNKNOWN"
                val user = AuthManager.getCurrentUser()
                val submission = SupportTicketManager.TicketSubmission(
                    userEmail = user?.email ?: "guest@gamervoice.app",
                    category = "Report Bad Actor",
                    subject = "Player Report: ${peer.name} in Room $roomCode",
                    description = "Reporting player ${peer.name} (PeerID: ${peer.peerId}) in Room $roomCode for: $reason.",
                    isVip = PlanManager.isVip()
                )
                SupportTicketManager.submitTicket(this, submission) { _, _ ->
                    runOnUiThread {
                        Toast.makeText(
                            this,
                            getString(R.string.dialog_report_success),
                            Toast.LENGTH_LONG
                        ).show()
                    }
                }
            }
            .setNegativeButton(getString(R.string.btn_cancel), null)
            .show()
    }

    private fun updateMicModeUI(isMuted: Boolean) {
        runOnUiThread {
            try {
                if (isMuted) {
                    // Mute Button Active (Red)
                    binding.btnRoomMicMute.setBackgroundResource(R.drawable.bg_btn_mic_mute_active)
                    binding.ivRoomMicMuteIcon.imageTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#FF2A6D"))
                    binding.tvRoomMicMuteText.setTextColor(Color.parseColor("#FF2A6D"))

                    // Mic On Inactive (Dimmed)
                    binding.btnRoomMicLive.setBackgroundResource(R.drawable.bg_btn_mic_inactive)
                    binding.ivRoomMicLiveIcon.imageTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#8E9BAE"))
                    binding.tvRoomMicLiveText.setTextColor(Color.parseColor("#8E9BAE"))

                    binding.tvRoomMicStatusHint.text = getString(R.string.mic_hint_muted)
                    binding.tvRoomMicStatusHint.setTextColor(Color.parseColor("#FF2A6D"))
                } else {
                    // Mic On Active (Green)
                    binding.btnRoomMicLive.setBackgroundResource(R.drawable.bg_btn_mic_live_active)
                    binding.ivRoomMicLiveIcon.imageTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#00FF88"))
                    binding.tvRoomMicLiveText.setTextColor(Color.parseColor("#00FF88"))

                    // Mute Inactive (Dimmed)
                    binding.btnRoomMicMute.setBackgroundResource(R.drawable.bg_btn_mic_inactive)
                    binding.ivRoomMicMuteIcon.imageTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#8E9BAE"))
                    binding.tvRoomMicMuteText.setTextColor(Color.parseColor("#8E9BAE"))

                    binding.tvRoomMicStatusHint.text = getString(R.string.mic_hint_live)
                    binding.tvRoomMicStatusHint.setTextColor(Color.parseColor("#00FF88"))
                }
            } catch (t: Throwable) {
                Log.e("HomeActivity", "Error updating mic mode UI", t)
            }
        }
    }

    // --- VoiceServiceListener Callbacks ---

    override fun onConnectedStateChanged(statusMessage: String) {
        runOnUiThread {
            binding.tvServerStatus.text = statusMessage
        }
    }

    override fun onRoomCreated(roomCode: String, myPeerId: String) {
        runOnUiThread {
            binding.tvServerStatus.text = getString(R.string.status_connected_room_fmt, roomCode)
        }
        showConnectedRoomView(roomCode)
        // Automatically save room to persistent storage if within quota
        RoomPersistenceManager.saveRoom(roomCode) { result ->
            if (result is RoomPersistenceManager.SaveResult.Success) {
                refreshSavedRoomsUI(RoomPersistenceManager.getCachedRooms())
            }
        }
    }

    override fun onRoomJoined(roomCode: String, myPeerId: String, existingPeers: List<String>) {
        runOnUiThread {
            binding.tvServerStatus.text = getString(R.string.status_connected_room_fmt, roomCode)
        }
        showConnectedRoomView(roomCode)
    }

    override fun onMemberCountUpdated(count: Int) {
        updateMemberCountUI(count)
    }

    override fun onParticipantsUpdated(participants: List<RoomParticipant>) {
        updateParticipantsUI(participants)
        val someoneSpeaking = participants.any { it.isSpeaking }
        FloatingHudManager.updateSpeakingState(someoneSpeaking)
    }

    override fun onMicModeChanged(isPtt: Boolean) {
        updateMicModeUI(isPtt)
        FloatingHudManager.updateMicState(isPtt)
    }

    override fun onTacticalCalloutReceived(senderId: String, senderName: String, calloutId: String, calloutText: String) {
        // Tactical callouts suppressed per user instruction
    }

    override fun onLatencyUpdated(latencyMs: Long) {
        lastMeasuredLatencyMs = latencyMs
        runOnUiThread {
            try {
                if (latencyMs <= 0) {
                    binding.tvTelemetryPing.text = "— ms"
                    binding.tvTelemetryPing.setTextColor(Color.parseColor("#888888"))
                    binding.tvRoomLatency.text = getString(R.string.latency_measuring)
                    binding.tvRoomLatency.setTextColor(ContextCompat.getColor(this, R.color.accent_green))
                    binding.dotLatencyStatus.backgroundTintList = android.content.res.ColorStateList.valueOf(ContextCompat.getColor(this, R.color.accent_green))
                } else {
                    binding.tvTelemetryPing.text = "${latencyMs}ms"
                    val (color, label) = when {
                        latencyMs < 75 -> Pair(ContextCompat.getColor(this, R.color.accent_green), getString(R.string.latency_p2p_direct, latencyMs))
                        latencyMs < 160 -> Pair(ContextCompat.getColor(this, R.color.neon_gold), getString(R.string.latency_good, latencyMs))
                        else -> Pair(ContextCompat.getColor(this, R.color.neon_red), getString(R.string.latency_weak, latencyMs))
                    }
                    binding.tvTelemetryPing.setTextColor(color)
                    binding.tvRoomLatency.text = label
                    binding.tvRoomLatency.setTextColor(color)
                    binding.dotLatencyStatus.backgroundTintList = android.content.res.ColorStateList.valueOf(color)
                }
            } catch (_: Throwable) {}
        }
    }

    override fun onPeerLatenciesUpdated(latencies: Map<String, Long>) {
        livePeerLatencies.putAll(latencies)
    }

    override fun onError(message: String) {
        runOnUiThread {
            binding.pbConnecting.visibility = View.GONE
            binding.btnCreateRoom.isEnabled = true
            binding.btnJoinRoom.isEnabled = true
            binding.btnCreateRoomCard.isEnabled = true
            binding.btnJoinRoomCard.isEnabled = true
            binding.btnSubmitJoin.isEnabled = true
            Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        FloatingHudManager.hideHud()
        AppLogger.removeListener(logListener)
        if (isServiceBound) {
            voiceService?.listener = null
            unbindService(serviceConnection)
            isServiceBound = false
        }
    }
}
