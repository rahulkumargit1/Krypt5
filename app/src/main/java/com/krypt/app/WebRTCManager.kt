package com.krypt.app

import android.content.Context
import android.util.Log
import org.webrtc.*
import org.webrtc.PeerConnection.RTCConfiguration
import org.webrtc.PeerConnection.IceServer

/**
 * Audio-only WebRTC manager.
 * Removing video eliminates all SurfaceViewRenderer lifecycle crashes.
 */
class WebRTCManager(
    private val context: Context,
    private val localUuid: String,
    private val remoteUuid: String,
    private val onIceCandidate: (IceCandidate) -> Unit,
    private val onLocalSdp: (SessionDescription) -> Unit,
    private val onCallEnded: () -> Unit,
    private val onConnectionStateChange: (PeerConnection.IceConnectionState) -> Unit = {}
) {
    private val TAG = "WebRTCManager"

    private var peerConnectionFactory: PeerConnectionFactory? = null
    private var peerConnection: PeerConnection? = null
    private var localAudioTrack: AudioTrack? = null
    private var audioSource: AudioSource? = null

    @Volatile private var isDisposed = false

    companion object {
        @Volatile private var factoryInitialized = false
        fun initGlobal(context: Context) {
            if (!factoryInitialized) {
                PeerConnectionFactory.initialize(
                    PeerConnectionFactory.InitializationOptions.builder(context.applicationContext)
                        .setEnableInternalTracer(false)
                        .createInitializationOptions()
                )
                factoryInitialized = true
            }
        }
    }

    init {
        try {
            initGlobal(context)
            initFactory()
            initLocalAudio()
            createPeerConnection()
        } catch (e: Exception) {
            Log.e(TAG, "Init failed", e)
            safeDispose()
            onCallEnded()
        }
    }

    private fun initFactory() {
        peerConnectionFactory = PeerConnectionFactory.builder()
            .setAudioDeviceModule(JavaAudioDeviceModule.builder(context).createAudioDeviceModule())
            .createPeerConnectionFactory()
    }

    private fun initLocalAudio() {
        val factory = peerConnectionFactory ?: return
        val constraints = MediaConstraints().apply {
            mandatory.add(MediaConstraints.KeyValuePair("googEchoCancellation", "true"))
            mandatory.add(MediaConstraints.KeyValuePair("googNoiseSuppression", "true"))
            mandatory.add(MediaConstraints.KeyValuePair("googAutoGainControl", "true"))
        }
        audioSource = factory.createAudioSource(constraints)
        localAudioTrack = factory.createAudioTrack("audio_local", audioSource)
        localAudioTrack?.setEnabled(true)
    }

    private fun createPeerConnection() {
        val factory = peerConnectionFactory ?: return
        val iceServers = listOf(
            IceServer.builder("stun:stun.l.google.com:19302").createIceServer(),
            IceServer.builder("stun:stun1.l.google.com:19302").createIceServer(),
            IceServer.builder("turn:openrelay.metered.ca:80")
                .setUsername("openrelayproject").setPassword("openrelayproject").createIceServer(),
            IceServer.builder("turn:openrelay.metered.ca:443")
                .setUsername("openrelayproject").setPassword("openrelayproject").createIceServer()
        )
        val rtcConfig = RTCConfiguration(iceServers).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
            bundlePolicy = PeerConnection.BundlePolicy.MAXBUNDLE
            rtcpMuxPolicy = PeerConnection.RtcpMuxPolicy.REQUIRE
        }

        peerConnection = factory.createPeerConnection(rtcConfig, object : PeerConnection.Observer {
            override fun onIceCandidate(candidate: IceCandidate) {
                if (!isDisposed) onIceCandidate(candidate)
            }
            override fun onIceConnectionChange(state: PeerConnection.IceConnectionState) {
                Log.d(TAG, "ICE state: $state")
                if (!isDisposed) {
                    onConnectionStateChange(state)
                    if (state == PeerConnection.IceConnectionState.DISCONNECTED ||
                        state == PeerConnection.IceConnectionState.FAILED ||
                        state == PeerConnection.IceConnectionState.CLOSED) {
                        onCallEnded()
                    }
                }
            }
            override fun onSignalingChange(p0: PeerConnection.SignalingState?) {}
            override fun onIceConnectionReceivingChange(p0: Boolean) {}
            override fun onIceGatheringChange(p0: PeerConnection.IceGatheringState?) {}
            override fun onIceCandidatesRemoved(p0: Array<out IceCandidate>?) {}
            override fun onAddStream(p0: MediaStream?) {}
            override fun onRemoveStream(p0: MediaStream?) {}
            override fun onDataChannel(p0: DataChannel?) {}
            override fun onRenegotiationNeeded() {}
            override fun onTrack(transceiver: RtpTransceiver) {}
            override fun onAddTrack(p0: RtpReceiver?, p1: Array<out MediaStream>?) {}
        }) ?: run { onCallEnded(); return }

        localAudioTrack?.let { peerConnection?.addTrack(it, listOf("local_stream")) }
    }

    fun createOffer() {
        if (isDisposed) return
        val constraints = MediaConstraints().apply {
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true"))
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveVideo", "false"))
        }
        peerConnection?.createOffer(sdpObserverForCreate { sdp ->
            peerConnection?.setLocalDescription(sdpObserverForSet {
                if (!isDisposed) onLocalSdp(sdp)
            }, sdp)
        }, constraints)
    }

    fun createAnswer(offerSdp: SessionDescription) {
        if (isDisposed) return
        peerConnection?.setRemoteDescription(sdpObserverForSet {
            val constraints = MediaConstraints().apply {
                mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true"))
                mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveVideo", "false"))
            }
            peerConnection?.createAnswer(sdpObserverForCreate { sdp ->
                peerConnection?.setLocalDescription(sdpObserverForSet {
                    if (!isDisposed) onLocalSdp(sdp)
                }, sdp)
            }, constraints)
        }, offerSdp)
    }

    fun setRemoteAnswer(answerSdp: SessionDescription) {
        if (isDisposed) return
        peerConnection?.setRemoteDescription(sdpObserverForSet {}, answerSdp)
    }

    fun addIceCandidate(candidate: IceCandidate) {
        if (!isDisposed) {
            try { peerConnection?.addIceCandidate(candidate) } catch (e: Exception) {
                Log.e(TAG, "addIceCandidate failed", e)
            }
        }
    }

    fun toggleMute(mute: Boolean) { localAudioTrack?.setEnabled(!mute) }

    fun endCall() {
        if (!isDisposed) safeDispose()
    }

    private fun safeDispose() {
        if (isDisposed) return
        isDisposed = true
        try { peerConnection?.close() } catch (_: Exception) {}
        try { peerConnection?.dispose() } catch (_: Exception) {}
        try { localAudioTrack?.dispose() } catch (_: Exception) {}
        try { audioSource?.dispose() } catch (_: Exception) {}
        try { peerConnectionFactory?.dispose() } catch (_: Exception) {}
        peerConnection = null; peerConnectionFactory = null
        localAudioTrack = null; audioSource = null
    }

    // ─── SDP helpers ─────────────────────────────────────────────────────────

    private fun sdpObserverForCreate(onSuccess: (SessionDescription) -> Unit) = object : SdpObserver {
        override fun onCreateSuccess(sdp: SessionDescription) { onSuccess(sdp) }
        override fun onSetSuccess() {}
        override fun onCreateFailure(error: String?) { Log.e(TAG, "SDP create fail: $error") }
        override fun onSetFailure(error: String?) {}
    }

    private fun sdpObserverForSet(onSuccess: () -> Unit) = object : SdpObserver {
        override fun onCreateSuccess(sdp: SessionDescription?) {}
        override fun onSetSuccess() { onSuccess() }
        override fun onCreateFailure(error: String?) {}
        override fun onSetFailure(error: String?) { Log.e(TAG, "SDP set fail: $error") }
    }
}
