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
import androidx.core.view.GravityCompat
import com.gamervoice.app.auth.AuthManager
import com.gamervoice.app.auth.PlanManager
import com.gamervoice.app.auth.PlanTier
import com.gamervoice.app.auth.UserProfile
import com.gamervoice.app.databinding.ActivityHomeBinding
import com.gamervoice.app.databinding.DialogLegalDocBinding
import com.gamervoice.app.databinding.DialogVipUpgradeBinding
import com.gamervoice.app.databinding.ItemInstalledGameBinding
import com.gamervoice.app.databinding.ItemParticipantBinding
import com.gamervoice.app.databinding.ItemSavedRoomBinding
import com.gamervoice.app.databinding.LayoutNavigationSliderBinding
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
    private lateinit var sliderBinding: LayoutNavigationSliderBinding

    private var voiceService: VoiceService? = null
    private var isServiceBound = false

    private var isSpeakerphone = true
    private var noiseFilterLevel = 2 // 0: Standard, 1: High, 2: Aggressive
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
                binding.tvServerStatus.text = getString(R.string.status_ready)
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

        if (!AuthManager.isLoggedIn()) {
            val intent = Intent(this, AuthActivity::class.java)
            startActivity(intent)
            finish()
            return
        }

        binding = ActivityHomeBinding.inflate(layoutInflater)
        setContentView(binding.root)
        sliderBinding = LayoutNavigationSliderBinding.bind(binding.navSlider.root)

        binding.btnCreateRoom.isEnabled = false
        binding.btnJoinRoom.isEnabled = false

        setupUI()
        setupNavigationSlider()
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
        sliderBinding.switchDrawerHud.isChecked = FloatingHudManager.isHudShowing()

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

        binding.btnOpenMenu.setOnClickListener {
            binding.drawerLayout.openDrawer(GravityCompat.START)
        }
        binding.llUserProfileHeader.setOnClickListener {
            binding.drawerLayout.openDrawer(GravityCompat.START)
        }

        // Plan Badge Click
        binding.tvHomePlanBadge.setOnClickListener {
            showVipUpgradeDialog()
        }

        // Sponsor Banner Ad - Remove Ads click
        binding.btnBannerRemoveAds.setOnClickListener {
            showVipUpgradeDialog()
        }

        // Create Room
        binding.btnCreateRoom.setOnClickListener {
            try {
                if (!isRecordAudioGranted()) {
                    Toast.makeText(this, "Microphone permission is required", Toast.LENGTH_SHORT).show()
                    startActivity(Intent(this, PermissionActivity::class.java))
                    return@setOnClickListener
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

        // Join Room button
        binding.btnJoinRoom.setOnClickListener {
            showJoinInputView()
        }

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
                if (games.isEmpty()) {
                    val emptyTv = android.widget.TextView(this).apply {
                        text = "🎮 No games detected. Install Free Fire or BGMI to launch directly!"
                        setTextColor(Color.parseColor("#A0AEC0"))
                        textSize = 11f
                        setPadding(12, 12, 12, 12)
                    }
                    binding.llInstalledGamesContainer.addView(emptyTv)
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
            "${rooms.size} Saved (VIP Unlimited)"
        } else {
            "${rooms.size}/2 Saved (Free Limit)"
        }

        binding.tvHomeRoomQuota.text = quotaText
        sliderBinding.tvDrawerRoomQuota.text = quotaText

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

        // Drawer View Saved Rooms
        sliderBinding.llDrawerSavedRoomsContainer.removeAllViews()
        if (rooms.isEmpty()) {
            sliderBinding.tvDrawerNoRooms.visibility = View.VISIBLE
            sliderBinding.llDrawerSavedRoomsContainer.addView(sliderBinding.tvDrawerNoRooms)
        } else {
            sliderBinding.tvDrawerNoRooms.visibility = View.GONE
            for (room in rooms) {
                val itemBinding = ItemSavedRoomBinding.inflate(layoutInflater, sliderBinding.llDrawerSavedRoomsContainer, false)
                itemBinding.tvSavedRoomName.text = room.roomName
                itemBinding.tvSavedRoomCode.text = room.roomCode
                itemBinding.tvSavedRoomDate.text = "Permanent Squad Room"
                itemBinding.tvSavedRoomPinBadge.visibility = if (room.pin.isNotEmpty()) View.VISIBLE else View.GONE

                itemBinding.btnRejoinRoom.setOnClickListener {
                    binding.drawerLayout.closeDrawer(GravityCompat.START)
                    joinRoomWithCode(room.roomCode)
                }
                itemBinding.btnDeleteRoom.setOnClickListener {
                    RoomPersistenceManager.deleteRoom(room.roomCode) {
                        refreshSavedRoomsUI(RoomPersistenceManager.getCachedRooms())
                    }
                }
                sliderBinding.llDrawerSavedRoomsContainer.addView(itemBinding.root)
            }
        }
    }

    // --- Monetization & VIP UI ---

    private fun verifyPlanExpiry() {
        val wasExpired = PlanManager.checkAndEnforceExpiry {
            runOnUiThread {
                updatePlanUI()
                refreshSavedRoomsUI(RoomPersistenceManager.getCachedRooms())
                Toast.makeText(this, "⚠️ Your VIP Pass has expired. Reverted to Free Plan.", Toast.LENGTH_LONG).show()
            }
        }
        if (wasExpired) {
            updatePlanUI()
            refreshSavedRoomsUI(RoomPersistenceManager.getCachedRooms())
        }
    }

    private fun updatePlanUI() {
        val isVip = PlanManager.isVip()
        if (isVip) {
            binding.llSponsorBannerAd.visibility = View.GONE
            binding.tvHomePlanBadge.text = "👑 VIP"
            binding.tvHomePlanBadge.setTextColor(Color.parseColor("#FFD700"))
            binding.tvHomePlanBadge.setBackgroundColor(Color.parseColor("#26FFD700"))

            val countdown = PlanManager.getExpiryCountdown()
            sliderBinding.tvDrawerPlanBadge.text = "👑 " + PlanManager.getPlanName() + "\n" + countdown
            sliderBinding.tvDrawerPlanBadge.setTextColor(Color.parseColor("#FFD700"))
            sliderBinding.tvDrawerPlanBadge.setBackgroundColor(Color.parseColor("#26FFD700"))
            sliderBinding.llDrawerVipBanner.visibility = View.GONE
        } else {
            binding.llSponsorBannerAd.visibility = View.VISIBLE
            binding.tvHomePlanBadge.text = "FREE"
            binding.tvHomePlanBadge.setTextColor(ContextCompat.getColor(this, R.color.accent_green))
            binding.tvHomePlanBadge.setBackgroundColor(Color.parseColor("#1A00E676"))

            sliderBinding.tvDrawerPlanBadge.text = "🟢 FREE PLAN (2 Rooms)"
            sliderBinding.tvDrawerPlanBadge.setTextColor(ContextCompat.getColor(this, R.color.accent_green))
            sliderBinding.tvDrawerPlanBadge.setBackgroundColor(Color.parseColor("#1A00E676"))
            sliderBinding.llDrawerVipBanner.visibility = View.VISIBLE
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
            vipBinding.llPlanWeekly.setBackgroundResource(if (selectedTier == PlanTier.WEEKLY) R.drawable.bg_terminal_box else R.drawable.bg_code_input)
            vipBinding.llPlanMonthly.setBackgroundResource(if (selectedTier == PlanTier.MONTHLY) R.drawable.bg_terminal_box else R.drawable.bg_code_input)
            vipBinding.llPlanLifetime.setBackgroundResource(if (selectedTier == PlanTier.LIFETIME) R.drawable.bg_terminal_box else R.drawable.bg_code_input)
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

        vipBinding.btnTestWeekly.setOnClickListener {
            PlanManager.activateTestTier(PlanTier.WEEKLY) {
                updatePlanUI()
                refreshSavedRoomsUI(RoomPersistenceManager.getCachedRooms())
                Toast.makeText(this, "👑 7-Day Weekly Pass Activated! Expires in 7 days.", Toast.LENGTH_SHORT).show()
                dialog.dismiss()
            }
        }

        vipBinding.btnTestMonthly.setOnClickListener {
            PlanManager.activateTestTier(PlanTier.MONTHLY) {
                updatePlanUI()
                refreshSavedRoomsUI(RoomPersistenceManager.getCachedRooms())
                Toast.makeText(this, "👑 30-Day Monthly Pass Activated! Expires in 30 days.", Toast.LENGTH_SHORT).show()
                dialog.dismiss()
            }
        }

        vipBinding.btnTestLifetime.setOnClickListener {
            PlanManager.activateTestTier(PlanTier.LIFETIME) {
                updatePlanUI()
                refreshSavedRoomsUI(RoomPersistenceManager.getCachedRooms())
                Toast.makeText(this, "👑 Lifetime Legend Pass Activated! Never expires (N/A).", Toast.LENGTH_SHORT).show()
                dialog.dismiss()
            }
        }

        vipBinding.btnTestExpire.setOnClickListener {
            PlanManager.simulateExpiry {
                updatePlanUI()
                refreshSavedRoomsUI(RoomPersistenceManager.getCachedRooms())
                Toast.makeText(this, "⏰ Expiration simulated! Reverted to Free Plan in Cloud Firestore.", Toast.LENGTH_SHORT).show()
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

    // --- Navigation Slider Drawer & Settings ---

    private fun setupNavigationSlider() {
        val user = AuthManager.getCurrentUser()
        if (user != null) {
            sliderBinding.tvDrawerName.text = user.name
            sliderBinding.tvDrawerEmail.text = user.email
            ImageLoader.loadAvatar(sliderBinding.ivDrawerAvatar, user.avatar)
        }

        sliderBinding.btnDrawerUpgradeVip.setOnClickListener {
            binding.drawerLayout.closeDrawer(GravityCompat.START)
            showVipUpgradeDialog()
        }

        // Floating HUD toggle switch
        sliderBinding.switchDrawerHud.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) {
                if (!FloatingHudManager.hasOverlayPermission(this)) {
                    sliderBinding.switchDrawerHud.isChecked = false
                    requestOverlayPermission()
                } else {
                    val svc = voiceService
                    if (svc != null && svc.currentRoomCode != null) {
                        FloatingHudManager.showHud(this, svc)
                    } else {
                        Toast.makeText(this, "Floating HUD will activate automatically when you enter a room.", Toast.LENGTH_SHORT).show()
                    }
                }
            } else {
                FloatingHudManager.hideHud()
            }
        }

        // Noise Filter Sensitivity
        sliderBinding.tvDrawerNoiseFilter.setOnClickListener {
            noiseFilterLevel = (noiseFilterLevel + 1) % 3
            val label = when (noiseFilterLevel) {
                0 -> "Standard (Low)"
                1 -> "High (Fan Filter)"
                else -> "Aggressive (Fan Off)"
            }
            sliderBinding.tvDrawerNoiseFilter.text = label
            Toast.makeText(this, "Mic Filter: $label", Toast.LENGTH_SHORT).show()
        }

        // Audio Output Routing (Speakerphone vs Earpiece)
        sliderBinding.tvDrawerAudioRoute.setOnClickListener {
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
            sliderBinding.tvDrawerAudioRoute.text = if (isSpeakerphone) "Speakerphone 🔊" else "Earpiece 👂"
            Toast.makeText(this, "Audio Output: " + (if (isSpeakerphone) "Speakerphone" else "Earpiece"), Toast.LENGTH_SHORT).show()
        }

        // Clear Cache Button
        sliderBinding.btnDrawerClearCache.setOnClickListener {
            ImageLoader.clearMemoryCache()
            System.gc()
            val runtime = Runtime.getRuntime()
            val usedMemMb = (runtime.totalMemory() - runtime.freeMemory()) / (1024 * 1024)
            Toast.makeText(this, "🧹 Cache evicted! Current Heap: ~${usedMemMb}MB (< 10MB safe)", Toast.LENGTH_LONG).show()
        }

        // Legal Docs
        sliderBinding.btnDrawerPrivacy.setOnClickListener {
            binding.drawerLayout.closeDrawer(GravityCompat.START)
            showLegalDialog("Privacy Policy", LegalDocsHelper.PRIVACY_POLICY, R.drawable.ic_shield_privacy)
        }

        sliderBinding.btnDrawerTerms.setOnClickListener {
            binding.drawerLayout.closeDrawer(GravityCompat.START)
            showLegalDialog("Terms of Service & EULA", LegalDocsHelper.TERMS_OF_SERVICE, R.drawable.ic_document_terms)
        }

        sliderBinding.btnDrawerGuidelines.setOnClickListener {
            binding.drawerLayout.closeDrawer(GravityCompat.START)
            showLegalDialog("Community & Fair Play", LegalDocsHelper.COMMUNITY_GUIDELINES, R.drawable.ic_info_circle)
        }

        sliderBinding.btnDrawerLicenses.setOnClickListener {
            binding.drawerLayout.closeDrawer(GravityCompat.START)
            showLegalDialog("Open Source Licenses", LegalDocsHelper.OPEN_SOURCE_LICENSES, R.drawable.ic_open_source)
        }

        sliderBinding.btnDrawerLogout.setOnClickListener {
            binding.drawerLayout.closeDrawer(GravityCompat.START)
            performLogout()
        }
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
            binding.btnToggleFloatingHud.text = "🎮 FLOATING IN-GAME HUD"
            sliderBinding.switchDrawerHud.isChecked = false
        } else {
            FloatingHudManager.showHud(this, svc)
            binding.btnToggleFloatingHud.text = "❌ HIDE IN-GAME HUD"
            sliderBinding.switchDrawerHud.isChecked = true
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
            binding.btnSubmitJoin.isEnabled = true
            binding.tvServerStatus.text = getString(R.string.status_ready)
            binding.btnToggleFloatingHud.text = "🎮 FLOATING IN-GAME HUD"
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
                binding.btnToggleFloatingHud.text = if (FloatingHudManager.isHudShowing()) "❌ HIDE IN-GAME HUD" else "🎮 FLOATING IN-GAME HUD"
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
        // Latency ping UI suppressed per user instruction
    }

    override fun onError(message: String) {
        runOnUiThread {
            binding.pbConnecting.visibility = View.GONE
            binding.btnCreateRoom.isEnabled = true
            binding.btnJoinRoom.isEnabled = true
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
