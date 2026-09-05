package com.gamervoice.app

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.gamervoice.app.databinding.ActivityHomeBinding
import com.gamervoice.app.webrtc.PeerConnectionManager
import com.gamervoice.app.webrtc.SignalingClient
import org.webrtc.PeerConnection

class HomeActivity : AppCompatActivity(),
    SignalingClient.SignalingListener,
    PeerConnectionManager.PeerConnectionListener {

    private lateinit var binding: ActivityHomeBinding
    private lateinit var signalingClient: SignalingClient
    private lateinit var peerConnectionManager: PeerConnectionManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityHomeBinding.inflate(layoutInflater)
        setContentView(binding.root)

        signalingClient = SignalingClient(this)
        peerConnectionManager = PeerConnectionManager(this, signalingClient, this)

        peerConnectionManager.init()

        setupUI()
        showHomeView()
        signalingClient.connect()
    }

    private fun setupUI() {
        // Home view actions
        binding.btnCreateRoom.setOnClickListener {
            binding.tvServerStatus.text = "Status: Creating room..."
            signalingClient.createRoom()
        }

        binding.btnJoinRoom.setOnClickListener {
            showJoinInputView()
        }

        // Join input view actions
        binding.btnSubmitJoin.setOnClickListener {
            val code = binding.etJoinRoomCode.text.toString().trim().uppercase()
            if (code.length == 5) {
                binding.pbConnecting.visibility = View.VISIBLE
                binding.btnSubmitJoin.isEnabled = false
                signalingClient.joinRoom(code)
            } else {
                Toast.makeText(this, "Room code must be 5 characters", Toast.LENGTH_SHORT).show()
            }
        }

        binding.btnBackToHome.setOnClickListener {
            showHomeView()
        }

        // Connected room view actions
        binding.btnCopyCode.setOnClickListener {
            val code = binding.tvDisplayRoomCode.text.toString()
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clip = ClipData.newPlainText("GamerVoice Room Code", code)
            clipboard.setPrimaryClip(clip)
            Toast.makeText(this, getString(R.string.code_copied), Toast.LENGTH_SHORT).show()
        }

        binding.btnContinue.setOnClickListener {
            Toast.makeText(this, "GamerVoice running in background. Enjoy your game!", Toast.LENGTH_SHORT).show()
            // Minimize app to background so user can open/switch to their game
            moveTaskToBack(true)
        }

        binding.btnLeaveRoom.setOnClickListener {
            signalingClient.leaveRoom()
            peerConnectionManager.closeAll()
            peerConnectionManager.init()
            showHomeView()
        }
    }

    private fun showHomeView() {
        runOnUiThread {
            binding.llHomeActions.visibility = View.VISIBLE
            binding.llJoinInputSection.visibility = View.GONE
            binding.llConnectedRoomSection.visibility = View.GONE
            binding.pbConnecting.visibility = View.GONE
            binding.btnSubmitJoin.isEnabled = true
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
            updateMemberCountUI()
        }
    }

    private fun updateMemberCountUI() {
        val totalMembers = peerConnectionManager.getActivePeerCount() + 1
        runOnUiThread {
            binding.tvMemberCount.text = "$totalMembers/5 connected"
        }
    }

    // --- SignalingListener Callbacks ---

    override fun onConnected() {
        runOnUiThread {
            binding.tvServerStatus.text = "Status: Server Connected"
        }
    }

    override fun onDisconnected() {
        runOnUiThread {
            binding.tvServerStatus.text = "Status: Disconnected from Server"
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

        for (peerId in existingPeers) {
            peerConnectionManager.connectToPeer(peerId)
        }
    }

    override fun onPeerJoined(peerId: String) {
        updateMemberCountUI()
    }

    override fun onOfferReceived(senderPeerId: String, sdp: String) {
        peerConnectionManager.handleOffer(senderPeerId, sdp)
    }

    override fun onAnswerReceived(senderPeerId: String, sdp: String) {
        peerConnectionManager.handleAnswer(senderPeerId, sdp)
    }

    override fun onIceCandidateReceived(
        senderPeerId: String,
        candidate: String,
        sdpMid: String,
        sdpMLineIndex: Int
    ) {
        peerConnectionManager.handleIceCandidate(senderPeerId, candidate, sdpMid, sdpMLineIndex)
    }

    override fun onPeerLeft(peerId: String) {
        peerConnectionManager.removePeer(peerId)
        updateMemberCountUI()
    }

    override fun onError(message: String) {
        runOnUiThread {
            binding.pbConnecting.visibility = View.GONE
            binding.btnSubmitJoin.isEnabled = true
            Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
        }
    }

    // --- PeerConnectionListener Callbacks ---

    override fun onIceConnectionStateChanged(peerId: String, newState: PeerConnection.IceConnectionState) {
        updateMemberCountUI()
    }

    override fun onLog(message: String) {
        // Internal logging handled silently in production home view
    }

    override fun onDestroy() {
        super.onDestroy()
        if (isFinishing) {
            peerConnectionManager.closeAll()
            signalingClient.disconnect()
        }
    }
}
