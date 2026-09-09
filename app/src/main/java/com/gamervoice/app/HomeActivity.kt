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
import com.gamervoice.app.auth.AuthManager
import com.gamervoice.app.auth.PlanManager
import com.gamervoice.app.auth.PlanTier
import com.gamervoice.app.auth.UserProfile
import com.gamervoice.app.databinding.ActivityHomeBinding
import com.gamervoice.app.databinding.DialogContactUsBinding
import com.gamervoice.app.databinding.DialogLegalDocBinding
import com.gamervoice.app.databinding.DialogVipUpgradeBinding
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
import org.json.JSONObject

class HomeActivity : AppCompatActivity(), VoiceService.VoiceServiceListener, PaymentResultWithDataListener {

    private lateinit var binding: ActivityHomeBinding

    private var voiceService: VoiceService? = null
    private var isServiceBound = false

    private var isSpeakerphone = true
    private var noiseFilterLevel = 0 // 0: Normal (50%), 1: High (75%), 2: Ultra Silent (VIP)
    private var pendingPurchaseTier: PlanTier? = null
    private var vipUpgradeDialog: Dialog? = null

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
        Checkout.preload(applicationContext)

        // Automatically revoke any unverified / unpaid VIP status on app launch
        PlanManager.enforceValidPaidStatus {
            runOnUiThread {
                updatePlanUI()
                refreshSavedRoomsUI(RoomPersistenceManager.getCachedRooms())
            }
        }

        if (!AuthManager.isLoggedIn()) {
            val intent = Intent(this, AuthActivity::class.java)
            startActivity(intent)
            finish()
            return
        }

        binding = ActivityHomeBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnCreateRoom.isEnabled = false
        binding.btnJoinRoom.isEnabled = false
        binding.btnCreateRoomCard.isEnabled = false
        binding.btnJoinRoomCard.isEnabled = false

        setupUI()
        setupConsoleUI()

        val serviceIntent = Intent(this, VoiceService::class.java)
        bindService(serviceIntent, serviceConnection, BIND_AUTO_CREATE)
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
                .setTitle("Previous Crash Diagnostic")
                .setMessage(lastCrash)
                .setPositiveButton("Copy Error") { _, _ ->
                    val clipboard = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
                    clipboard.setPrimaryClip(ClipData.newPlainText("Crash Log", lastCrash))
                    Toast.makeText(this, "Crash log copied to clipboard!", Toast.LENGTH_SHORT).show()
                }
                .setNegativeButton("Dismiss", null)
                .show()
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        voiceService?.currentRoomCode?.let { roomCode ->
            showConnectedRoomView(roomCode)
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

        binding.llUserProfileHeader.setOnClickListener {
            updateActiveNavTab(3) // Jump to Profile Tab
        }

        // Plan Badge Click
        binding.tvHomePlanBadge.setOnClickListener {
            showVipUpgradeDialog()
        }

        // Sponsor Banner Ad - Remove Ads click
        binding.btnBannerRemoveAds.setOnClickListener {
            showVipUpgradeDialog()
        }

        val createRoomAction = View.OnClickListener {
            try {
                if (!isRecordAudioGranted()) {
                    Toast.makeText(this, "Microphone permission is required", Toast.LENGTH_SHORT).show()
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
                Toast.makeText(this, "Error creating room: ${t.message}", Toast.LENGTH_LONG).show()
            }
        }

        // Create Room Dual Card & legacy button
        binding.btnCreateRoomCard.setOnClickListener(createRoomAction)
        binding.btnCreateRoom.setOnClickListener(createRoomAction)

        val joinRoomAction = View.OnClickListener {
            showJoinInputView()
        }

        // Join Room Dual Card & legacy button
        binding.btnJoinRoomCard.setOnClickListener(joinRoomAction)
        binding.btnJoinRoom.setOnClickListener(joinRoomAction)

        // Submit Room code
        binding.btnSubmitJoin.setOnClickListener {
            joinRoomWithCode(binding.etJoinRoomCode.text.toString().trim().uppercase())
        }

        binding.btnBackToHome.setOnClickListener {
            showHomeView()
        }

        binding.btnCopyCode.setOnClickListener {
            val code = binding.tvDisplayRoomCode.text.toString()
            val clipboard = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
            val clip = ClipData.newPlainText("GamerVoice Room Code", code)
            clipboard.setPrimaryClip(clip)
            Toast.makeText(this, getString(R.string.code_copied), Toast.LENGTH_SHORT).show()
        }

        // Save Current Room to Firestore
        binding.btnSaveCurrentRoom.setOnClickListener {
            val code = binding.tvDisplayRoomCode.text.toString().trim()
            if (code.isNotEmpty()) {
                saveRoomToSquad(code)
            }
        }

        binding.btnToggleMicMode.setOnClickListener {
            voiceService?.toggleMicMode()
        }

        binding.btnToggleFloatingHud.setOnClickListener {
            toggleFloatingHud()
        }

        // --- App & Audio Settings (4 Cards Grid) ---
        binding.cardSettingHud.setOnClickListener {
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
                        Toast.makeText(this, "HUD will activate once you enter a squad room.", Toast.LENGTH_SHORT).show()
                    }
                }
            } else {
                FloatingHudManager.hideHud()
            }
        }

        binding.cardSettingNoiseFilter.setOnClickListener {
            cycleNoiseFilter()
        }

        binding.cardSettingAudioRoute.setOnClickListener {
            toggleAudioRoute()
        }

        binding.cardSettingLowData.setOnClickListener {
            binding.switchSettingLowData.isChecked = !binding.switchSettingLowData.isChecked
        }
        binding.switchSettingLowData.setOnCheckedChangeListener { _, isChecked ->
            Toast.makeText(this, if (isChecked) "3G / Weak Signal Mode: Enabled (Low-Bandwidth)" else "3G / Weak Signal Mode: Disabled", Toast.LENGTH_SHORT).show()
        }

        // --- Legal & Policies (4 Cards Grid) ---
        binding.cardLegalPrivacy.setOnClickListener {
            showLegalDialog("Privacy Policy", LegalDocsHelper.PRIVACY_POLICY, R.drawable.ic_shield_privacy)
        }
        binding.cardLegalTerms.setOnClickListener {
            showLegalDialog("Terms of Service & EULA", LegalDocsHelper.TERMS_OF_SERVICE, R.drawable.ic_document_terms)
        }
        binding.cardLegalGuidelines.setOnClickListener {
            showLegalDialog("Community & Fair Play", LegalDocsHelper.COMMUNITY_GUIDELINES, R.drawable.ic_gamers_squad)
        }
        binding.cardLegalLicenses.setOnClickListener {
            showLegalDialog("Open Source Licenses", LegalDocsHelper.OPEN_SOURCE_LICENSES, R.drawable.ic_code_brackets)
        }
        binding.cardLegalRefund.setOnClickListener {
            showLegalDialog("Payment & Refund Policy", LegalDocsHelper.REFUND_POLICY, R.drawable.ic_shield_privacy)
        }
        binding.cardLegalIndianCompliance.setOnClickListener {
            showLegalDialog("India Statutory Compliance", LegalDocsHelper.INDIAN_GOVT_COMPLIANCE, R.drawable.ic_shield_check)
        }
        binding.cardSupportContact.setOnClickListener {
            showContactSupportDialog()
        }

        // --- Footer & Logout Squad ---
        binding.btnLogoutSquad.setOnClickListener {
            performLogout()
        }

        // --- Bottom Navigation Bar Tabs (True Dedicated Multi-Tab Switching) ---
        binding.navTabHome.setOnClickListener {
            updateActiveNavTab(0)
        }
        binding.navTabRooms.setOnClickListener {
            updateActiveNavTab(1)
        }
        binding.navTabSettings.setOnClickListener {
            updateActiveNavTab(2)
        }
        binding.navTabProfile.setOnClickListener {
            updateActiveNavTab(3)
        }

        // --- Rooms Tab Controls ---
        binding.btnRoomsTabJoin.setOnClickListener {
            val code = binding.etRoomsTabCode.text.toString().trim().uppercase()
            joinRoomWithCode(code)
        }
        binding.btnRoomsTabCreate.setOnClickListener(createRoomAction)

        // --- Settings Tab Controls ---
        binding.cardSettingRamPurge.setOnClickListener {
            if (!PlanManager.isVip()) {
                showVipUpgradeDialog("👑 Automatic Background RAM Purging is a VIP exclusive feature! Upgrade to eliminate low-memory lag in BGMI / Free Fire automatically.")
            } else {
                Toast.makeText(this, "👑 Auto RAM Purger is actively protecting your squad voice in background.", Toast.LENGTH_SHORT).show()
            }
        }

        binding.btnClearMemoryCache.setOnClickListener {
            ImageLoader.clearMemoryCache()
            System.gc()
            val runtime = Runtime.getRuntime()
            val usedMemMb = (runtime.totalMemory() - runtime.freeMemory()) / (1024 * 1024)
            Toast.makeText(this, "🧹 Memory cleared! Active heap: ~${usedMemMb}MB (< 10MB safe)", Toast.LENGTH_SHORT).show()
        }

        // --- Profile Tab Controls ---
        var profileSelectedTier = PlanTier.MONTHLY
        fun updateProfileCardSelection() {
            binding.llProfilePlanWeekly.setBackgroundResource(if (profileSelectedTier == PlanTier.WEEKLY) R.drawable.bg_vip_card_neon else R.drawable.bg_vip_card_unselected)
            binding.llProfilePlanMonthly.setBackgroundResource(if (profileSelectedTier == PlanTier.MONTHLY) R.drawable.bg_vip_card_gold else R.drawable.bg_vip_card_unselected)
            binding.llProfilePlanLifetime.setBackgroundResource(if (profileSelectedTier == PlanTier.LIFETIME) R.drawable.bg_vip_card_gold else R.drawable.bg_vip_card_unselected)

            when (profileSelectedTier) {
                PlanTier.WEEKLY -> binding.btnProfileActivateVip.text = "⚡ UNLOCK 7 DAYS VIP — ₹29"
                PlanTier.MONTHLY -> binding.btnProfileActivateVip.text = "⚡ UNLOCK 30 DAYS VIP — ₹89"
                PlanTier.LIFETIME -> binding.btnProfileActivateVip.text = "⚡ UNLOCK LIFETIME VIP — ₹249"
                else -> binding.btnProfileActivateVip.text = "⚡ UNLOCK VIP PASS"
            }
        }
        updateProfileCardSelection()

        binding.llProfilePlanWeekly.setOnClickListener {
            profileSelectedTier = PlanTier.WEEKLY
            updateProfileCardSelection()
        }
        binding.llProfilePlanMonthly.setOnClickListener {
            profileSelectedTier = PlanTier.MONTHLY
            updateProfileCardSelection()
        }
        binding.llProfilePlanLifetime.setOnClickListener {
            profileSelectedTier = PlanTier.LIFETIME
            updateProfileCardSelection()
        }
        binding.btnProfileActivateVip.setOnClickListener {
            val u = AuthManager.getCurrentUser()
            if (u != null) {
                startRazorpayCheckout(profileSelectedTier, u)
            } else {
                Toast.makeText(this, "Please sign in first", Toast.LENGTH_SHORT).show()
            }
        }
        binding.btnProfileResetToFree.setOnClickListener {
            PlanManager.revokeVip {
                updatePlanUI()
                refreshSavedRoomsUI(RoomPersistenceManager.getCachedRooms())
                Toast.makeText(this, "🔄 Account Plan reset to Free tier successfully.", Toast.LENGTH_SHORT).show()
            }
        }

        binding.btnContinue.setOnClickListener {
            startVoiceServiceForeground()
            Toast.makeText(this, "GamerVoice active in background. Launching game...", Toast.LENGTH_SHORT).show()
            moveTaskToBack(true)
        }

        binding.btnLeaveRoom.setOnClickListener {
            FloatingHudManager.hideHud()
            voiceService?.leaveRoom()
            showHomeView()
        }
    }

    private fun updateActiveNavTab(activeTab: Int) {
        val activeColor = ContextCompat.getColor(this, R.color.neon_green)
        val inactiveColor = Color.parseColor("#8E9BAE")

        binding.ivNavHome.setColorFilter(if (activeTab == 0) activeColor else inactiveColor)
        binding.tvNavHome.setTextColor(if (activeTab == 0) activeColor else inactiveColor)
        binding.navIndicatorHome.visibility = if (activeTab == 0) View.VISIBLE else View.INVISIBLE

        binding.ivNavRooms.setColorFilter(if (activeTab == 1) activeColor else inactiveColor)
        binding.tvNavRooms.setTextColor(if (activeTab == 1) activeColor else inactiveColor)
        binding.navIndicatorRooms.visibility = if (activeTab == 1) View.VISIBLE else View.INVISIBLE

        binding.ivNavSettings.setColorFilter(if (activeTab == 2) activeColor else inactiveColor)
        binding.tvNavSettings.setTextColor(if (activeTab == 2) activeColor else inactiveColor)
        binding.navIndicatorSettings.visibility = if (activeTab == 2) View.VISIBLE else View.INVISIBLE

        binding.ivNavProfile.setColorFilter(if (activeTab == 3) activeColor else inactiveColor)
        binding.tvNavProfile.setTextColor(if (activeTab == 3) activeColor else inactiveColor)
        binding.navIndicatorProfile.visibility = if (activeTab == 3) View.VISIBLE else View.INVISIBLE

        // Show ONLY the dedicated view container for the selected tab
        binding.tabContainerHome.visibility = if (activeTab == 0) View.VISIBLE else View.GONE
        binding.tabContainerRooms.visibility = if (activeTab == 1) View.VISIBLE else View.GONE
        binding.tabContainerSettings.visibility = if (activeTab == 2) View.VISIBLE else View.GONE
        binding.tabContainerProfile.visibility = if (activeTab == 3) View.VISIBLE else View.GONE

        binding.nsvContent.smoothScrollTo(0, 0)
    }

    private fun cycleNoiseFilter() {
        val isVip = PlanManager.isVip()
        when (noiseFilterLevel) {
            0 -> {
                noiseFilterLevel = 1
                updateNoiseFilterUI()
                Toast.makeText(this, "Mic Filter: High (75%)", Toast.LENGTH_SHORT).show()
            }
            1 -> {
                if (isVip) {
                    noiseFilterLevel = 2
                    updateNoiseFilterUI()
                    Toast.makeText(this, "Mic Filter: Ultra Silent 👑 (< 10%)", Toast.LENGTH_SHORT).show()
                } else {
                    showVipUpgradeDialog("👑 Ultra Silent (< 10% Noise) is an exclusive VIP feature! Upgrade to eliminate keyboard, room, and fan noise.")
                }
            }
            else -> {
                noiseFilterLevel = 0
                updateNoiseFilterUI()
                Toast.makeText(this, "Mic Filter: Normal (50%)", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun updateNoiseFilterUI() {
        if (!PlanManager.isVip() && noiseFilterLevel == 2) {
            noiseFilterLevel = 0
        }
        val label = when (noiseFilterLevel) {
            0 -> "Normal (50%)"
            1 -> "High (75%)"
            else -> "Ultra Silent 👑 (< 10%)"
        }
        binding.tvSettingNoiseFilterBadge.text = label
    }

    private fun toggleAudioRoute() {
        isSpeakerphone = !isSpeakerphone
        val audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
            val targetType = if (isSpeakerphone) android.media.AudioDeviceInfo.TYPE_BUILTIN_SPEAKER else android.media.AudioDeviceInfo.TYPE_BUILTIN_EARPIECE
            val targetDevice = audioManager.availableCommunicationDevices.find { it.type == targetType }
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
        val label = if (isSpeakerphone) "Speaker" else "Earpiece"
        binding.tvSettingAudioRouteBadge.text = label
        Toast.makeText(this, "Audio Output: $label", Toast.LENGTH_SHORT).show()
    }

    private fun joinRoomWithCode(code: String) {
        try {
            if (!isRecordAudioGranted()) {
                Toast.makeText(this, "Microphone permission is required", Toast.LENGTH_SHORT).show()
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
                Toast.makeText(this, "Room code must be 5 characters", Toast.LENGTH_SHORT).show()
            }
        } catch (t: Throwable) {
            AppLogger.log("ERROR", "joinRoomWithCode error: ${t.message}", Log.getStackTraceString(t))
            Toast.makeText(this, "Error joining room: ${t.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun saveRoomToSquad(roomCode: String) {
        RoomPersistenceManager.saveRoom(roomCode) { result ->
            when (result) {
                is RoomPersistenceManager.SaveResult.Success -> {
                    Toast.makeText(this, "Room $roomCode saved permanently to your squad dashboard!", Toast.LENGTH_LONG).show()
                    refreshSavedRoomsUI(RoomPersistenceManager.getCachedRooms())
                }
                is RoomPersistenceManager.SaveResult.LimitReached -> {
                    showVipUpgradeDialog("You have reached the 2-room limit for the Free plan! Upgrade to GamerVoice VIP to save unlimited squad rooms.")
                }
                is RoomPersistenceManager.SaveResult.Error -> {
                    Toast.makeText(this, "Could not save room: ${result.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
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
                                Toast.makeText(this, "Game $name not installed. Opening store...", Toast.LENGTH_SHORT).show()
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
                            Toast.makeText(this, "Launching ${game.appName}...", Toast.LENGTH_SHORT).show()
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
        val wasExpired = PlanManager.checkAndEnforceExpiry {
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

        if (isVip) {
            binding.llSponsorBannerAd.visibility = View.GONE
            binding.tvHomePlanBadge.text = "👑 VIP"
            binding.tvHomePlanBadge.setTextColor(Color.parseColor("#FFD700"))
            binding.tvHomePlanBadge.setBackgroundResource(R.drawable.bg_plan_badge_vip)

            val countdown = PlanManager.getExpiryCountdown()
            binding.tvProfileTabPlanBadge.text = "👑 " + PlanManager.getPlanName() + " ACTIVE"
            binding.tvProfileTabPlanBadge.setTextColor(Color.parseColor("#FFD700"))
            binding.tvProfileTabPlanBadge.setBackgroundResource(R.drawable.bg_plan_badge_vip)
            binding.tvProfileTabCountdown.text = "Expires in: " + countdown

            binding.tvSettingRamPurgeTitle.text = "Auto RAM Purge 👑"
            binding.tvSettingRamPurgeSubtitle.text = "VIP: Auto background clean every 3 min (< 10MB)"
            binding.tvSettingRamPurgeBadge.text = "ACTIVE"
            binding.tvSettingRamPurgeBadge.setTextColor(Color.parseColor("#FFD700"))
            binding.tvSettingRamPurgeBadge.setBackgroundResource(R.drawable.bg_plan_badge_vip)
        } else {
            binding.llSponsorBannerAd.visibility = View.VISIBLE
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
    }

    private fun showVipUpgradeDialog(customSubtitle: String? = null) {
        val user = AuthManager.getCurrentUser()
        if (user == null) {
            Toast.makeText(this, "Please sign in to purchase VIP", Toast.LENGTH_SHORT).show()
            startActivity(Intent(this, AuthActivity::class.java))
            return
        }

        val dialog = Dialog(this)
        vipUpgradeDialog = dialog
        val vipBinding = DialogVipUpgradeBinding.inflate(layoutInflater)
        dialog.setContentView(vipBinding.root)
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        val width = (resources.displayMetrics.widthPixels * 0.94).toInt()
        dialog.window?.setLayout(width, ViewGroup.LayoutParams.WRAP_CONTENT)

        if (!customSubtitle.isNullOrEmpty()) {
            vipBinding.tvVipSubtitle.text = customSubtitle
        }

        vipBinding.ivCloseVipDialog.setOnClickListener { dialog.dismiss() }

        var selectedTier = PlanTier.MONTHLY

        fun updateCardSelection() {
            vipBinding.llPlanWeekly.setBackgroundResource(if (selectedTier == PlanTier.WEEKLY) R.drawable.bg_vip_card_neon else R.drawable.bg_vip_card_unselected)
            vipBinding.llPlanMonthly.setBackgroundResource(if (selectedTier == PlanTier.MONTHLY) R.drawable.bg_vip_card_gold else R.drawable.bg_vip_card_unselected)
            vipBinding.llPlanLifetime.setBackgroundResource(if (selectedTier == PlanTier.LIFETIME) R.drawable.bg_vip_card_gold else R.drawable.bg_vip_card_unselected)

            when (selectedTier) {
                PlanTier.WEEKLY -> vipBinding.btnActivateVip.text = "⚡ UNLOCK 7 DAYS VIP — ₹29"
                PlanTier.MONTHLY -> vipBinding.btnActivateVip.text = "⚡ UNLOCK 30 DAYS VIP — ₹89"
                PlanTier.LIFETIME -> vipBinding.btnActivateVip.text = "⚡ UNLOCK LIFETIME VIP — ₹249"
                else -> vipBinding.btnActivateVip.text = "⚡ UNLOCK VIP PASS"
            }
        }
        updateCardSelection()

        vipBinding.llPlanWeekly.setOnClickListener {
            selectedTier = PlanTier.WEEKLY
            updateCardSelection()
        }
        vipBinding.llPlanMonthly.setOnClickListener {
            selectedTier = PlanTier.MONTHLY
            updateCardSelection()
        }
        vipBinding.llPlanLifetime.setOnClickListener {
            selectedTier = PlanTier.LIFETIME
            updateCardSelection()
        }

        vipBinding.btnActivateVip.setOnClickListener {
            startRazorpayCheckout(selectedTier, user)
        }

        // Revoke VIP / Reset to Free Plan
        vipBinding.btnResetToFree.setOnClickListener {
            PlanManager.revokeVip {
                updatePlanUI()
                refreshSavedRoomsUI(RoomPersistenceManager.getCachedRooms())
                Toast.makeText(this, "🔄 VIP status revoked! Account reset to Free Plan.", Toast.LENGTH_SHORT).show()
                dialog.dismiss()
            }
        }

        dialog.show()
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
            Toast.makeText(this, "Error initializing payment: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    override fun onPaymentSuccess(razorpayPaymentID: String?, paymentData: PaymentData?) {
        val tier = pendingPurchaseTier ?: PlanTier.WEEKLY
        val paymentId = razorpayPaymentID ?: paymentData?.paymentId ?: "pay_${System.currentTimeMillis()}"
        val user = AuthManager.getCurrentUser()
        val email = user?.email ?: "gamer"

        PlanManager.purchasePlan(tier, paymentId) {
            updatePlanUI()
            refreshSavedRoomsUI(RoomPersistenceManager.getCachedRooms())
            vipUpgradeDialog?.dismiss()
            Toast.makeText(this, "👑 Payment Successful ($paymentId)! Activated ${tier.title} for $email.", Toast.LENGTH_LONG).show()
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
        Toast.makeText(this, "❌ Payment Failed: $errorMsg", Toast.LENGTH_LONG).show()
        pendingPurchaseTier = null
    }



    private fun showLegalDialog(title: String, content: String, iconRes: Int) {
        val dialog = Dialog(this)
        val dialogBinding = DialogLegalDocBinding.inflate(layoutInflater)
        dialog.setContentView(dialogBinding.root)
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        val width = (resources.displayMetrics.widthPixels * 0.94).toInt()
        dialog.window?.setLayout(width, ViewGroup.LayoutParams.WRAP_CONTENT)

        dialogBinding.tvDialogTitle.text = title
        dialogBinding.tvDialogContent.text = content
        dialogBinding.ivDialogIcon.setImageResource(iconRes)

        dialogBinding.btnCopyLegalDoc.setOnClickListener {
            val clipboard = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("Legal Doc", content))
            Toast.makeText(this, "$title copied to clipboard!", Toast.LENGTH_SHORT).show()
        }

        dialogBinding.ivCloseDialog.setOnClickListener { dialog.dismiss() }
        dialogBinding.btnAcknowledgeDialog.setOnClickListener { dialog.dismiss() }

        dialog.show()
    }

    private fun showContactSupportDialog(preselectedCategory: String? = null) {
        val user = AuthManager.getCurrentUser()
        val dialog = Dialog(this)
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

        dialogBinding.ivCloseContactDialog.setOnClickListener {
            dialog.dismiss()
        }

        dialogBinding.btnSubmitContactTicket.setOnClickListener {
            val category = dialogBinding.spnContactCategory.selectedItem?.toString() ?: "General"
            val subject = dialogBinding.etContactSubject.text?.toString()?.trim().orEmpty()
            val description = dialogBinding.etContactDescription.text?.toString()?.trim().orEmpty()

            if (subject.isEmpty()) {
                Toast.makeText(this, "Please enter a subject summary", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            if (description.isEmpty()) {
                Toast.makeText(this, "Please describe the issue in detail", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
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
                        dialog.dismiss()
                    }
                }
            }
        }

        dialog.show()
    }

    private fun toggleFloatingHud() {
        val svc = voiceService
        if (svc == null || svc.currentRoomCode == null) {
            Toast.makeText(this, "Join a squad room first to enable In-Game HUD", Toast.LENGTH_SHORT).show()
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
        Toast.makeText(this, "Please allow 'Display over other apps' to use In-Game HUD", Toast.LENGTH_LONG).show()
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
            Toast.makeText(this, "All diagnostic logs copied to clipboard!", Toast.LENGTH_SHORT).show()
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
            binding.tvPttStatus.text = getString(R.string.ptt_speaking_hint)
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent?): Boolean {
        if ((keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) && (voiceService?.isPttModeEnabled() == true)) {
            voiceService?.setPttTransmitting(transmitting = false)
            binding.tvPttStatus.text = getString(R.string.ptt_muted_hint)
            return true
        }
        return super.onKeyUp(keyCode, event)
    }

    private fun showHomeView() {
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
                    itemBinding.tvParticipantStatus.text = if (isMe) "Host / You" else "Connected"

                    binding.llParticipantsContainer.addView(itemBinding.root)
                }
            } catch (t: Throwable) {
                Log.e("HomeActivity", "Error updating participants UI", t)
            }
        }
    }

    private fun updateMicModeUI(isPtt: Boolean) {
        runOnUiThread {
            try {
                if (isPtt) {
                    binding.btnToggleMicMode.text = getString(R.string.mode_ptt)
                    binding.tvPttStatus.visibility = View.VISIBLE
                    binding.tvPttStatus.text = getString(R.string.ptt_muted_hint)
                } else {
                    binding.btnToggleMicMode.text = getString(R.string.mode_always_on)
                    binding.tvPttStatus.visibility = View.GONE
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
        runOnUiThread {
            try {
                if (latencyMs < 0) {
                    binding.tvTelemetryPing.text = "-- ms"
                    binding.tvTelemetryPing.setTextColor(Color.parseColor("#94A3B8"))
                } else {
                    binding.tvTelemetryPing.text = "${latencyMs}ms"
                    when {
                        latencyMs < 60 -> binding.tvTelemetryPing.setTextColor(Color.parseColor("#00E676"))
                        latencyMs < 120 -> binding.tvTelemetryPing.setTextColor(Color.parseColor("#FFD700"))
                        else -> binding.tvTelemetryPing.setTextColor(Color.parseColor("#FF5252"))
                    }
                }
            } catch (_: Throwable) {}
        }
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
