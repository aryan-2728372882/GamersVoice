package com.gamervoice.app.webrtc

import android.content.Context
import android.util.Log
import org.webrtc.AudioSource
import org.webrtc.AudioTrack
import org.webrtc.DataChannel
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.RtpReceiver
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import org.webrtc.audio.JavaAudioDeviceModule
import java.util.concurrent.ConcurrentHashMap

class PeerConnectionManager(
    private val context: Context,
    private val signalingClient: SignalingClient,
    private val listener: PeerConnectionListener
) {

    companion object {
        private const val TAG = "PeerConnectionManager"
    }

    interface PeerConnectionListener {
        fun onIceConnectionStateChanged(peerId: String, newState: PeerConnection.IceConnectionState)
        fun onLog(message: String)
    }

    private var factory: PeerConnectionFactory? = null
    private var audioSource: AudioSource? = null
    private var localAudioTrack: AudioTrack? = null

    // Map of targetPeerId -> PeerConnection
    private val peerConnections = ConcurrentHashMap<String, PeerConnection>()

    private val iceServers = listOf(
        PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer(),
        PeerConnection.IceServer.builder("stun:stun1.l.google.com:19302").createIceServer(),
        PeerConnection.IceServer.builder("stun:stun2.l.google.com:19302").createIceServer(),
        PeerConnection.IceServer.builder("stun:stun3.l.google.com:19302").createIceServer(),
        PeerConnection.IceServer.builder("stun:stun4.l.google.com:19302").createIceServer()
    )

    private val rtcConfig = PeerConnection.RTCConfiguration(iceServers).apply {
        sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
        continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
    }

    fun init() {
        PeerConnectionFactory.initialize(
            PeerConnectionFactory.InitializationOptions.builder(context.applicationContext)
                .setEnableInternalTracer(false)
                .createInitializationOptions()
        )

        val audioDeviceModule = JavaAudioDeviceModule.builder(context.applicationContext)
            .setUseHardwareAcousticEchoCanceler(true)
            .setUseHardwareNoiseSuppressor(true)
            .createAudioDeviceModule()

        factory = PeerConnectionFactory.builder()
            .setAudioDeviceModule(audioDeviceModule)
            .createPeerConnectionFactory()

        val audioConstraints = MediaConstraints()
        audioSource = factory?.createAudioSource(audioConstraints)
        localAudioTrack = factory?.createAudioTrack("ARDAMSa0", audioSource)
        localAudioTrack?.setEnabled(true)

        listener.onLog("WebRTC Audio Factory Initialized")
    }

    fun connectToPeer(targetPeerId: String) {
        listener.onLog("Initiating WebRTC connection to peer $targetPeerId")
        val pc = getOrCreatePeerConnection(targetPeerId) ?: return

        val mediaConstraints = MediaConstraints().apply {
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true"))
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveVideo", "false"))
        }

        pc.createOffer(object : SimpleSdpObserver() {
            override fun onCreateSuccess(desc: SessionDescription) {
                pc.setLocalDescription(object : SimpleSdpObserver() {
                    override fun onSetSuccess() {
                        listener.onLog("Offer created for peer $targetPeerId")
                        signalingClient.sendOffer(targetPeerId, desc.description)
                    }
                }, desc)
            }
        }, mediaConstraints)
    }

    fun handleOffer(senderPeerId: String, sdp: String) {
        listener.onLog("Handling Offer from $senderPeerId")
        val pc = getOrCreatePeerConnection(senderPeerId) ?: return

        val remoteDescription = SessionDescription(SessionDescription.Type.OFFER, sdp)
        pc.setRemoteDescription(object : SimpleSdpObserver() {
            override fun onSetSuccess() {
                val mediaConstraints = MediaConstraints().apply {
                    mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true"))
                    mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveVideo", "false"))
                }

                pc.createAnswer(object : SimpleSdpObserver() {
                    override fun onCreateSuccess(desc: SessionDescription) {
                        pc.setLocalDescription(object : SimpleSdpObserver() {
                            override fun onSetSuccess() {
                                listener.onLog("Answer created for peer $senderPeerId")
                                signalingClient.sendAnswer(senderPeerId, desc.description)
                            }
                        }, desc)
                    }
                }, mediaConstraints)
            }
        }, remoteDescription)
    }

    fun handleAnswer(senderPeerId: String, sdp: String) {
        listener.onLog("Handling Answer from $senderPeerId")
        val pc = peerConnections[senderPeerId] ?: return
        val remoteDescription = SessionDescription(SessionDescription.Type.ANSWER, sdp)
        pc.setRemoteDescription(object : SimpleSdpObserver() {
            override fun onSetSuccess() {
                listener.onLog("Remote Description (Answer) set for $senderPeerId")
            }
        }, remoteDescription)
    }

    fun handleIceCandidate(senderPeerId: String, candidate: String, sdpMid: String, sdpMLineIndex: Int) {
        val pc = peerConnections[senderPeerId] ?: return
        val iceCandidate = IceCandidate(sdpMid, sdpMLineIndex, candidate)
        pc.addIceCandidate(iceCandidate)
    }

    fun removePeer(peerId: String) {
        peerConnections[peerId]?.apply {
            close()
        }
        peerConnections.remove(peerId)
        listener.onLog("Peer $peerId removed")
    }

    fun closeAll() {
        peerConnections.forEach { (_, pc) ->
            pc.close()
        }
        peerConnections.clear()

        localAudioTrack?.dispose()
        localAudioTrack = null
        audioSource?.dispose()
        audioSource = null
        factory?.dispose()
        factory = null

        listener.onLog("WebRTC PeerConnectionManager closed")
    }

    private fun getOrCreatePeerConnection(targetPeerId: String): PeerConnection? {
        peerConnections[targetPeerId]?.let { return it }

        val pc = factory?.createPeerConnection(rtcConfig, object : PeerConnection.Observer {
            override fun onIceCandidate(candidate: IceCandidate) {
                signalingClient.sendIceCandidate(
                    targetPeerId,
                    candidate.sdp,
                    candidate.sdpMid,
                    candidate.sdpMLineIndex
                )
            }

            override fun onIceConnectionChange(newState: PeerConnection.IceConnectionState) {
                listener.onLog("ICE State [$targetPeerId]: $newState")
                listener.onIceConnectionStateChanged(targetPeerId, newState)
            }

            override fun onSignalingChange(newState: PeerConnection.SignalingState) {}
            override fun onIceConnectionReceivingChange(receiving: Boolean) {}
            override fun onIceGatheringChange(newState: PeerConnection.IceGatheringState) {}
            override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>?) {}
            override fun onAddStream(stream: MediaStream?) {}
            override fun onRemoveStream(stream: MediaStream?) {}
            override fun onDataChannel(dataChannel: DataChannel?) {}
            override fun onRenegotiationNeeded() {}
            override fun onAddTrack(receiver: RtpReceiver?, streams: Array<out MediaStream>?) {
                listener.onLog("Remote Audio Track Received from $targetPeerId!")
            }
        })

        if (pc != null) {
            localAudioTrack?.let { track ->
                pc.addTrack(track, listOf("ARDAMS"))
            }
            peerConnections[targetPeerId] = pc
        } else {
            listener.onLog("Failed to create PeerConnection for $targetPeerId")
        }

        return pc
    }

    open class SimpleSdpObserver : SdpObserver {
        override fun onCreateSuccess(desc: SessionDescription) {}
        override fun onSetSuccess() {}
        override fun onCreateFailure(reason: String) {
            Log.e(TAG, "SDP Create Failure: $reason")
        }

        override fun onSetFailure(reason: String) {
            Log.e(TAG, "SDP Set Failure: $reason")
        }
    }
}
