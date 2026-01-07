package com.example.streaming

import android.content.Context
import android.util.Log
import android.view.SurfaceView
import com.pedro.rtmp.utils.ConnectCheckerRtmp
import com.pedro.rtplibrary.rtmp.RtmpCamera2
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * StreamManager: encapsula RtmpCamera2 y expone estados/errores/estadísticas vía StateFlow.
 * Diseñado para usarse desde una Activity con SurfaceView.
 */
class StreamManager(private val context: Context) : ConnectCheckerRtmp {

    companion object {
        private const val TAG = "StreamManager"
    }

    private var rtmpCamera: RtmpCamera2? = null

    private val _streamState = MutableStateFlow(StreamState.IDLE)
    val streamState: StateFlow<StreamState> = _streamState.asStateFlow()

    private val _streamStats = MutableStateFlow(StreamStats())
    val streamStats: StateFlow<StreamStats> = _streamStats.asStateFlow()

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    private var currentConfig: StreamConfig = StreamConfig.HIGH_QUALITY
    private var streamStartTime: Long = 0L
    private var isFrontCamera = false

    // ---------------- ConnectCheckerRtmp callbacks ----------------
    override fun onConnectionStartedRtmp(rtmpUrl: String) {
        Log.d(TAG, "onConnectionStartedRtmp: $rtmpUrl")
        _streamState.value = StreamState.PREPARING
    }

    override fun onConnectionSuccessRtmp() {
        Log.d(TAG, "onConnectionSuccessRtmp")
        _streamState.value = StreamState.STREAMING
        streamStartTime = System.currentTimeMillis()
        _streamStats.value = _streamStats.value.copy(isConnected = true)
    }

    override fun onConnectionFailedRtmp(reason: String) {
        Log.e(TAG, "onConnectionFailedRtmp: $reason")
        _streamState.value = StreamState.ERROR
        _errorMessage.value = "Conexión fallida: $reason"
        _streamStats.value = _streamStats.value.copy(isConnected = false)
    }

    override fun onNewBitrateRtmp(bitrate: Long) {
        // bitrate viene en bps
        _streamStats.value = _streamStats.value.copy(bitrate = bitrate)
    }

    override fun onDisconnectRtmp() {
        Log.d(TAG, "onDisconnectRtmp")
        _streamState.value = StreamState.STOPPED
        _streamStats.value = _streamStats.value.copy(isConnected = false)
    }

    override fun onAuthErrorRtmp() {
        Log.e(TAG, "onAuthErrorRtmp")
        _streamState.value = StreamState.ERROR
        _errorMessage.value = "Error de autenticación"
    }

    override fun onAuthSuccessRtmp() {
        Log.d(TAG, "onAuthSuccessRtmp")
    }

    // ---------------- Public API ----------------

    fun initialize(surfaceView: SurfaceView) {
        try {
            rtmpCamera = RtmpCamera2(surfaceView, this)
            Log.d(TAG, "StreamManager inicializado")
        } catch (e: Exception) {
            Log.e(TAG, "Error inicializando RtmpCamera2: ${e.message}")
            _errorMessage.value = "Error inicializando cámara: ${e.message}"
        }
    }

    fun configure(config: StreamConfig) {
        currentConfig = config
        Log.d(TAG, "Configuración aplicada: ${config.videoWidth}x${config.videoHeight}@${config.videoFps}fps")
    }

    fun startPreview() {
        try {
            rtmpCamera?.let {
                if (!it.isOnPreview) {
                    it.startPreview()
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error startPreview: ${e.message}")
            _errorMessage.value = "Error al iniciar preview: ${e.message}"
        }
    }

    fun stopPreview() {
        try {
            rtmpCamera?.let {
                if (it.isOnPreview) it.stopPreview()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error stopPreview: ${e.message}")
        }
    }

    fun startStreaming(rtmpUrl: String? = null, streamKey: String? = null): Boolean {
        val camera = rtmpCamera ?: run {
            _errorMessage.value = "Cámara no inicializada"
            return false
        }

        if (camera.isStreaming) {
            Log.w(TAG, "Ya está transmitiendo")
            return true
        }

        val prepared = camera.prepareVideo(
            currentConfig.videoWidth,
            currentConfig.videoHeight,
            currentConfig.videoFps,
            currentConfig.videoBitrate,
            0
        ) && camera.prepareAudio(
            currentConfig.audioBitrate,
            currentConfig.audioSampleRate,
            currentConfig.audioIsStereo
        )

        if (!prepared) {
            _errorMessage.value = "No se pudo preparar encoder video/audio"
            return false
        }

        val url = rtmpUrl ?: currentConfig.rtmpUrl
        val key = streamKey ?: currentConfig.streamKey
        val fullUrl = "$url/$key"

        Log.d(TAG, "Iniciando streaming a $fullUrl")
        _streamState.value = StreamState.PREPARING

        return try {
            camera.startStream(fullUrl)
            true
        } catch (e: Exception) {
            Log.e(TAG, "Error al iniciar stream: ${e.message}")
            _errorMessage.value = "Error al conectar: ${e.message}"
            _streamState.value = StreamState.ERROR
            false
        }
    }

    fun stopStreaming() {
        rtmpCamera?.let {
            if (it.isStreaming) {
                it.stopStream()
                _streamState.value = StreamState.STOPPED
                _streamStats.value = StreamStats()
                Log.d(TAG, "Streaming detenido")
            }
        }
    }

    fun switchCamera() {
        rtmpCamera?.let {
            try {
                it.switchCamera()
                isFrontCamera = !isFrontCamera
                Log.d(TAG, "Cámara cambiada")
            } catch (e: Exception) {
                Log.e(TAG, "Error switchCamera: ${e.message}")
            }
        }
    }

    fun toggleMute(): Boolean {
        rtmpCamera?.let {
            return if (it.isAudioMuted) {
                it.enableAudio()
                false
            } else {
                it.disableAudio()
                true
            }
        }
        return false
    }

    fun isStreaming(): Boolean = rtmpCamera?.isStreaming == true
    fun isOnPreview(): Boolean = rtmpCamera?.isOnPreview == true
    fun isAudioMuted(): Boolean = rtmpCamera?.isAudioMuted == true

    fun getStreamDuration(): Long {
        return if (streamStartTime > 0 && isStreaming()) {
            (System.currentTimeMillis() - streamStartTime) / 1000
        } else 0L
    }

    fun clearError() {
        _errorMessage.value = null
    }

    fun release() {
        try {
            stopStreaming()
            stopPreview()
            _streamState.value = StreamState.IDLE
            Log.d(TAG, "Streaming detenido (cámara preservada)")
        } catch (e: Exception) {
            Log.e(TAG, "Error release: ${e.message}")
        }
    }
}
