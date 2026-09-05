package com.gamervoice.app

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.IBinder
import android.view.KeyEvent
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.gamervoice.app.databinding.ActivityHomeBinding
import com.gamervoice.app.service.VoiceService

class HomeActivity : AppCompatActivity(), VoiceService.VoiceServiceListener {

    private lateinit var binding: ActivityHomeBinding

    private var voiceService: VoiceService? = null
    private var isServiceBound = false

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as VoiceService.LocalBinder
            val svc = binder.getService()
            voiceService = svc
            svc.listener = this@HomeActivity
            isServiceBound = true

            // Restore state if returning to active room
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
        binding = ActivityHomeBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupUI()

        // Start and bind VoiceService
        val serviceIntent = Intent(this, VoiceService::class.java)
        startService(serviceIntent)
        bindService(serviceIntent, serviceConnection, Context.BIND_AUTO_CREATE)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        voiceService?.currentRoomCode?.let { roomCode ->
            showConnectedRoomView(roomCode)
        }
    }

    private fun setupUI() {
        binding.btnCreateRoom.setOnClickListener {
            binding.tvServerStatus.text = "Status: Creating room..."
            voiceService?.createRoom()
        }

        binding.btnJoinRoom.setOnClickListener {
            showJoinInputView()
        }

        binding.btnSubmitJoin.setOnClickListener {
            val code = binding.etJoinRoomCode.text.toString().trim().uppercase()
            if (code.length == 5) {
                binding.pbConnecting.visibility = View.VISIBLE
                binding.btnSubmitJoin.isEnabled = false
                voiceService?.joinRoom(code)
            } else {
                Toast.makeText(this, "Room code must be 5 characters", Toast.LENGTH_SHORT).show()
            }
        }

        binding.btnBackToHome.setOnClickListener {
            showHomeView()
        }

        binding.btnCopyCode.setOnClickListener {
            val code = binding.tvDisplayRoomCode.text.toString()
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
            val clip = android.content.ClipData.newPlainText("GamerVoice Room Code", code)
            clipboard.setPrimaryClip(clip)
            Toast.makeText(this, getString(R.string.code_copied), Toast.LENGTH_SHORT).show()
        }

        binding.btnToggleMicMode.setOnClickListener {
            voiceService?.toggleMicMode()
        }

        binding.btnContinue.setOnClickListener {
            Toast.makeText(this, "GamerVoice running in background. Enjoy your game!", Toast.LENGTH_SHORT).show()
            moveTaskToBack(true)
        }

        binding.btnLeaveRoom.setOnClickListener {
            voiceService?.leaveRoom()
            showHomeView()
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_VOLUME_DOWN && (voiceService?.isPttModeEnabled() == true)) {
            voiceService?.setPttTransmitting(true)
            binding.tvPttStatus.text = getString(R.string.ptt_speaking_hint)
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_VOLUME_DOWN && (voiceService?.isPttModeEnabled() == true)) {
            voiceService?.setPttTransmitting(false)
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
            binding.btnSubmitJoin.isEnabled = true
            binding.tvServerStatus.text = "Status: Ready"
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
            binding.llHomeActions.visibility = View.GONE
            binding.llJoinInputSection.visibility = View.GONE
            binding.llConnectedRoomSection.visibility = View.VISIBLE
            binding.pbConnecting.visibility = View.GONE
            binding.btnSubmitJoin.isEnabled = true
            binding.tvDisplayRoomCode.text = roomCode
            updateMemberCountUI(voiceService?.getMemberCount() ?: 1)
            voiceService?.isPttModeEnabled()?.let { updateMicModeUI(it) }
        }
    }

    private fun updateMemberCountUI(count: Int) {
        runOnUiThread {
            binding.tvMemberCount.text = "$count/5 connected"
        }
    }

    private fun updateMicModeUI(isPtt: Boolean) {
        runOnUiThread {
            if (isPtt) {
                binding.btnToggleMicMode.text = getString(R.string.mode_ptt)
                binding.tvPttStatus.visibility = View.VISIBLE
                binding.tvPttStatus.text = getString(R.string.ptt_muted_hint)
            } else {
                binding.btnToggleMicMode.text = getString(R.string.mode_always_on)
                binding.tvPttStatus.visibility = View.GONE
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
            binding.tvServerStatus.text = "Status: Connected to Room $roomCode"
        }
        showConnectedRoomView(roomCode)
    }

    override fun onRoomJoined(roomCode: String, myPeerId: String, existingPeers: List<String>) {
        runOnUiThread {
            binding.tvServerStatus.text = "Status: Connected to Room $roomCode"
        }
        showConnectedRoomView(roomCode)
    }

    override fun onMemberCountUpdated(count: Int) {
        updateMemberCountUI(count)
    }

    override fun onMicModeChanged(isPtt: Boolean) {
        updateMicModeUI(isPtt)
    }

    override fun onError(message: String) {
        runOnUiThread {
            binding.pbConnecting.visibility = View.GONE
            binding.btnSubmitJoin.isEnabled = true
            Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (isServiceBound) {
            voiceService?.listener = null
            unbindService(serviceConnection)
            isServiceBound = false
        }
    }
}
