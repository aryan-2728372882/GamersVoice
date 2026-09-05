package com.gamervoice.app

import android.os.Bundle
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
        appendLog("Connecting to Signaling Server...")
        signalingClient.connect()
    }

    private fun setupUI() {
        binding.btnCreateRoom.setOnClickListener {
            appendLog("Requesting Create Room...")
            signalingClient.createRoom()
        }

        binding.btnJoinRoom.setOnClickListener {
            val code = binding.etRoomCode.text.toString().trim()
            if (code.length == 5) {
                appendLog("Requesting Join Room $code...")
                signalingClient.joinRoom(code)
            } else {
                Toast.makeText(this, "Please enter a valid 5-character Room Code", Toast.LENGTH_SHORT).show()
            }
        }

        binding.btnLeaveRoom.setOnClickListener {
            appendLog("Leaving Room...")
            signalingClient.leaveRoom()
            peerConnectionManager.closeAll()
            peerConnectionManager.init()
            binding.tvStatus.text = "Status: Connected (Not in Room)"
        }
    }

    private fun appendLog(message: String) {
        runOnUiThread {
            val currentText = binding.tvLog.text.toString()
            binding.tvLog.text = "$currentText\n> $message"
            binding.svLog.post {
                binding.svLog.fullScroll(android.view.View.FOCUS_DOWN)
            }
        }
    }

    // --- SignalingListener Callbacks ---

    override fun onConnected() {
        runOnUiThread {
            binding.tvStatus.text = "Status: Connected to Server"
        }
        appendLog("Connected to Signaling Server")
    }

    override fun onDisconnected() {
        runOnUiThread {
            binding.tvStatus.text = "Status: Disconnected"
        }
        appendLog("Disconnected from Signaling Server")
    }

    override fun onRoomCreated(roomCode: String, myPeerId: String) {
        runOnUiThread {
            binding.etRoomCode.setText(roomCode)
            binding.tvStatus.text = "Status: In Room $roomCode"
        }
        appendLog("Room Created: $roomCode | Peer ID: ${myPeerId.take(8)}...")
    }

    override fun onRoomJoined(roomCode: String, myPeerId: String, existingPeers: List<String>) {
        runOnUiThread {
            binding.tvStatus.text = "Status: In Room $roomCode (${existingPeers.size + 1}/5 members)"
        }
        appendLog("Joined Room: $roomCode | Existing Peers: ${existingPeers.size}")

        // Connect to each existing peer in the room
        for (peerId in existingPeers) {
            peerConnectionManager.connectToPeer(peerId)
        }
    }

    override fun onPeerJoined(peerId: String) {
        appendLog("New Peer Joined Room: ${peerId.take(8)}...")
        // Wait for offer from joining peer, or initiate connection
    }

    override fun onOfferReceived(senderPeerId: String, sdp: String) {
        appendLog("Received Offer from ${senderPeerId.take(8)}...")
        peerConnectionManager.handleOffer(senderPeerId, sdp)
    }

    override fun onAnswerReceived(senderPeerId: String, sdp: String) {
        appendLog("Received Answer from ${senderPeerId.take(8)}...")
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
        appendLog("Peer Left: ${peerId.take(8)}...")
        peerConnectionManager.removePeer(peerId)
    }

    override fun onError(message: String) {
        appendLog("ERROR: $message")
        runOnUiThread {
            Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
        }
    }

    // --- PeerConnectionListener Callbacks ---

    override fun onIceConnectionStateChanged(peerId: String, newState: PeerConnection.IceConnectionState) {
        appendLog("ICE State [${peerId.take(8)}...]: $newState")
        if (newState == PeerConnection.IceConnectionState.CONNECTED ||
            newState == PeerConnection.IceConnectionState.COMPLETED) {
            appendLog("🟢 AUDIO CONNECTED WITH PEER ${peerId.take(8)}!")
        }
    }

    override fun onLog(message: String) {
        appendLog(message)
    }

    override fun onDestroy() {
        super.onDestroy()
        peerConnectionManager.closeAll()
        signalingClient.disconnect()
    }
}
