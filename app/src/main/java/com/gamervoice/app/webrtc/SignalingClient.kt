package com.gamervoice.app.webrtc

import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class SignalingClient(private val listener: SignalingListener) {

    companion object {
        private const val TAG = "SignalingClient"
        private const val SIGNALING_URL = "wss://gamervoice-signaling.onrender.com"
    }

    interface SignalingListener {
        fun onConnected()
        fun onDisconnected()
        fun onRoomCreated(roomCode: String, myPeerId: String)
        fun onRoomJoined(roomCode: String, myPeerId: String, existingPeers: List<String>)
        fun onPeerJoined(peerId: String)
        fun onOfferReceived(senderPeerId: String, sdp: String)
        fun onAnswerReceived(senderPeerId: String, sdp: String)
        fun onIceCandidateReceived(senderPeerId: String, candidate: String, sdpMid: String, sdpMLineIndex: Int)
        fun onPeerLeft(peerId: String)
        fun onError(message: String)
    }

    private val client = OkHttpClient.Builder()
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .pingInterval(15, TimeUnit.SECONDS)
        .build()

    private var webSocket: WebSocket? = null
    var myPeerId: String? = null
        private set
    var currentRoomCode: String? = null
        private set

    fun connect() {
        if (webSocket != null) return
        val request = Request.Builder().url(SIGNALING_URL).build()
        webSocket = client.newWebSocket(request, createWebSocketListener())
    }

    fun disconnect() {
        webSocket?.close(1000, "User disconnected")
        webSocket = null
        myPeerId = null
        currentRoomCode = null
    }

    fun createRoom() {
        val payload = JSONObject().apply {
            put("type", "create-room")
        }
        send(payload.toString())
    }

    fun joinRoom(roomCode: String) {
        val payload = JSONObject().apply {
            put("type", "join-room")
            put("roomCode", roomCode.trim().uppercase())
        }
        send(payload.toString())
    }

    fun sendOffer(targetPeerId: String, sdp: String) {
        val payload = JSONObject().apply {
            put("type", "offer")
            put("targetPeerId", targetPeerId)
            put("sdp", sdp)
        }
        send(payload.toString())
    }

    fun sendAnswer(targetPeerId: String, sdp: String) {
        val payload = JSONObject().apply {
            put("type", "answer")
            put("targetPeerId", targetPeerId)
            put("sdp", sdp)
        }
        send(payload.toString())
    }

    fun sendIceCandidate(targetPeerId: String, candidate: String, sdpMid: String, sdpMLineIndex: Int) {
        val payload = JSONObject().apply {
            put("type", "ice-candidate")
            put("targetPeerId", targetPeerId)
            put("candidate", candidate)
            put("sdpMid", sdpMid)
            put("sdpMLineIndex", sdpMLineIndex)
        }
        send(payload.toString())
    }

    fun leaveRoom() {
        val payload = JSONObject().apply {
            put("type", "leave-room")
        }
        send(payload.toString())
        currentRoomCode = null
    }

    private fun send(message: String) {
        webSocket?.send(message) ?: Log.e(TAG, "Cannot send, WebSocket is null")
    }

    private fun createWebSocketListener() = object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            Log.d(TAG, "WebSocket Opened")
            listener.onConnected()
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            Log.d(TAG, "Incoming: $text")
            try {
                val json = JSONObject(text)
                when (json.optString("type")) {
                    "room-created" -> {
                        val roomCode = json.getString("roomCode")
                        val peerId = json.getString("peerId")
                        myPeerId = peerId
                        currentRoomCode = roomCode
                        listener.onRoomCreated(roomCode, peerId)
                    }

                    "room-joined" -> {
                        val roomCode = json.getString("roomCode")
                        val peerId = json.getString("peerId")
                        val peersArray = json.optJSONArray("peers") ?: JSONArray()
                        val peers = mutableListOf<String>()
                        for (i in 0 until peersArray.length()) {
                            peers.add(peersArray.getString(i))
                        }
                        myPeerId = peerId
                        currentRoomCode = roomCode
                        listener.onRoomJoined(roomCode, peerId, peers)
                    }

                    "peer-joined" -> {
                        val peerId = json.getString("peerId")
                        listener.onPeerJoined(peerId)
                    }

                    "offer" -> {
                        val senderPeerId = json.getString("senderPeerId")
                        val sdp = json.getString("sdp")
                        listener.onOfferReceived(senderPeerId, sdp)
                    }

                    "answer" -> {
                        val senderPeerId = json.getString("senderPeerId")
                        val sdp = json.getString("sdp")
                        listener.onAnswerReceived(senderPeerId, sdp)
                    }

                    "ice-candidate" -> {
                        val senderPeerId = json.getString("senderPeerId")
                        val candidate = json.getString("candidate")
                        val sdpMid = json.getString("sdpMid")
                        val sdpMLineIndex = json.getInt("sdpMLineIndex")
                        listener.onIceCandidateReceived(senderPeerId, candidate, sdpMid, sdpMLineIndex)
                    }

                    "peer-left" -> {
                        val peerId = json.getString("peerId")
                        listener.onPeerLeft(peerId)
                    }

                    "error" -> {
                        val errorMsg = json.optString("message", "Unknown error")
                        listener.onError(errorMsg)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error parsing message", e)
            }
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            Log.d(TAG, "WebSocket Closing: $reason")
            listener.onDisconnected()
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            Log.d(TAG, "WebSocket Closed")
            listener.onDisconnected()
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            Log.e(TAG, "WebSocket Failure: ${t.message}", t)
            this@SignalingClient.webSocket = null
            listener.onDisconnected()
            listener.onError("Connection failed: ${t.message}")
        }
    }
}
