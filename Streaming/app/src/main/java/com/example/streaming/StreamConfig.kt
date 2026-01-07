package com.example.streaming

/**
 * Configuración del stream y presets
 *
 * NOTA: rtmpUrl por defecto apuntando al servidor AWS que configuraste.
 */
data class StreamConfig(
    val rtmpUrl: String = "rtmp://3.18.220.198:1935/live",
    val streamKey: String = "cam1",

    // Video
    val videoWidth: Int = 1280,
    val videoHeight: Int = 720,
    val videoBitrate: Int = 2_500_000,
    val videoFps: Int = 30,

    // Audio
    val audioBitrate: Int = 128_000,
    val audioSampleRate: Int = 44100,
    val audioIsStereo: Boolean = true
) {
    /** Retorna la URL completa del RTMP (ej: rtmp://host:1935/live/cam1) */
    fun getFullRtmpUrl(): String = "$rtmpUrl/$streamKey"

    /** Aspect ratio (width / height) */
    fun aspectRatio(): Double = if (videoHeight != 0) videoWidth.toDouble() / videoHeight else 16.0 / 9.0

    /** Crea una copia con otro streamKey (útil para UI) */
    fun withStreamKey(key: String): StreamConfig = this.copy(streamKey = key)

    companion object {
        val LOW_QUALITY = StreamConfig(
            videoWidth = 854, videoHeight = 480, videoBitrate = 500_000, videoFps = 15,
            audioBitrate = 64_000, audioSampleRate = 22050, audioIsStereo = false
        )

        val MEDIUM_QUALITY = StreamConfig(
            videoWidth = 1280, videoHeight = 720, videoBitrate = 1_500_000, videoFps = 24,
            audioBitrate = 96_000, audioSampleRate = 44100, audioIsStereo = true
        )

        val HIGH_QUALITY = StreamConfig(
            videoWidth = 1280, videoHeight = 720, videoBitrate = 2_500_000, videoFps = 30,
            audioBitrate = 128_000, audioSampleRate = 44100, audioIsStereo = true
        )

        val ULTRA_QUALITY = StreamConfig(
            videoWidth = 1920, videoHeight = 1080, videoBitrate = 4_000_000, videoFps = 30,
            audioBitrate = 128_000, audioSampleRate = 48000, audioIsStereo = true
        )
    }
}

enum class StreamState {
    IDLE, PREPARING, STREAMING, PAUSED, STOPPED, ERROR
}

data class StreamStats(
    val fps: Double = 0.0,
    val bitrate: Long = 0L,
    val droppedFrames: Int = 0,
    val duration: Long = 0L,
    val isConnected: Boolean = false
)
