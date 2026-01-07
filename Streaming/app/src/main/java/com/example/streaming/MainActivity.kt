package com.example.streaming

import android.Manifest
import android.content.pm.ActivityInfo
import android.os.Bundle
import android.view.SurfaceView
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.launch

/**
 * Activity basada en Views (SurfaceView + botones).
 * Integra StreamManager para encapsular la lógica de streaming RTMP.
 *
 * Mejora UX:
 * - Habilita/deshabilita botones según estado
 * - Cambia texto del botón Start cuando transmite
 * - Muestra estado legible usando strings del resources
 */
class MainActivity : ComponentActivity() {

    private lateinit var surfaceView: SurfaceView
    private lateinit var btnStart: Button
    private lateinit var btnStop: Button
    private lateinit var btnSwitch: Button
    private lateinit var btnMute: Button
    private lateinit var btnRelease: Button

    private lateinit var tvState: TextView
    private lateinit var tvStats: TextView

    private lateinit var streamManager: StreamManager

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { perms ->
            val ok = (perms[Manifest.permission.CAMERA] == true) &&
                    (perms[Manifest.permission.RECORD_AUDIO] == true)
            if (!ok) {
                Toast.makeText(
                    this,
                    getString(R.string.permission_required),
                    Toast.LENGTH_LONG
                ).show()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE

        setContentView(R.layout.activity_main)

        // Bind views
        surfaceView = findViewById(R.id.surfaceView)
        btnStart = findViewById(R.id.btnStart)
        btnStop = findViewById(R.id.btnStop)
        btnSwitch = findViewById(R.id.btnSwitch)
        btnMute = findViewById(R.id.btnMute)
        btnRelease = findViewById(R.id.btnRelease)

        tvState = findViewById(R.id.tvState)
        tvStats = findViewById(R.id.tvStats)

        // Pedir permisos
        permissionLauncher.launch(
            arrayOf(
                Manifest.permission.CAMERA,
                Manifest.permission.RECORD_AUDIO
            )
        )

        // Inicializa StreamManager y configuración por defecto (HIGH)
        streamManager = StreamManager(this)
        streamManager.initialize(surfaceView)
        streamManager.configure(StreamConfig.HIGH_QUALITY)

        // Inicial UI state
        updateUiForState(StreamState.IDLE)

        // Observadores (StateFlow -> UI)
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {

                // Estado
                launch {
                    streamManager.streamState.collect { state ->
                        // Actualiza texto legible de estado y habilita/deshabilita botones
                        tvState.text = when (state) {
                            StreamState.IDLE -> getString(R.string.state_idle)
                            StreamState.PREPARING -> "Preparando..."
                            StreamState.STREAMING -> getString(R.string.state_streaming)
                            StreamState.PAUSED -> "Pausado"
                            StreamState.STOPPED -> "Detenido"
                            StreamState.ERROR -> "Error"
                        }
                        updateUiForState(state)
                    }
                }

                // Estadísticas
                launch {
                    streamManager.streamStats.collect { stats ->
                        tvStats.text =
                            "FPS: ${String.format("%.1f", stats.fps)} | " +
                                    "Bitrate: ${stats.bitrate / 1000} kbps"
                    }
                }

                // Errores
                launch {
                    streamManager.errorMessage.collect { err ->
                        err?.let {
                            Toast.makeText(
                                this@MainActivity,
                                it,
                                Toast.LENGTH_LONG
                            ).show()
                            streamManager.clearError()
                        }
                    }
                }
            }
        }

        // Botones - acciones
        btnStart.setOnClickListener {
            if (!streamManager.isOnPreview()) {
                streamManager.startPreview()
            }
            val started = streamManager.startStreaming() // usa URL del StreamConfig por defecto
            if (!started) {
                Toast.makeText(this, getString(R.string.stream_error), Toast.LENGTH_SHORT).show()
            }
        }

        btnStop.setOnClickListener {
            streamManager.stopStreaming()
        }

        btnSwitch.setOnClickListener {
            streamManager.switchCamera()
        }

        btnMute.setOnClickListener {
            val muted = streamManager.toggleMute()
            btnMute.text = if (muted) getString(R.string.unmute) else getString(R.string.mute)
            // apariencia leve para indicar cambio
            btnMute.alpha = if (muted) 0.6f else 1.0f
        }

        btnRelease.setOnClickListener {
            streamManager.release()
            tvState.text = getString(R.string.state_idle)
            tvStats.text = ""
            updateUiForState(StreamState.IDLE)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        streamManager.release()
    }

    /**
     * Actualiza la UI (habilita/deshabilita botones y ajusta texto/alpha)
     * según el estado del streaming.
     */
    private fun updateUiForState(state: StreamState) {
        when (state) {
            StreamState.IDLE, StreamState.STOPPED -> {
                btnStart.isEnabled = true
                btnStop.isEnabled = false
                btnSwitch.isEnabled = true
                btnMute.isEnabled = false
                btnRelease.isEnabled = true

                btnStart.text = getString(R.string.start_stream)
                btnStart.alpha = 1.0f
                btnStop.alpha = 0.6f
            }

            StreamState.PREPARING -> {
                btnStart.isEnabled = false
                btnStop.isEnabled = false
                btnSwitch.isEnabled = false
                btnMute.isEnabled = false
                btnRelease.isEnabled = true

                btnStart.text = "Preparando..."
                btnStart.alpha = 0.6f
                btnStop.alpha = 0.6f
            }

            StreamState.STREAMING -> {
                btnStart.isEnabled = false
                btnStop.isEnabled = true
                btnSwitch.isEnabled = true
                btnMute.isEnabled = true
                btnRelease.isEnabled = true

                btnStart.text = "Transmitiendo"
                btnStart.alpha = 0.6f
                btnStop.alpha = 1.0f
            }

            StreamState.PAUSED -> {
                // Permitir reanudar
                btnStart.isEnabled = true
                btnStop.isEnabled = true
                btnSwitch.isEnabled = true
                btnMute.isEnabled = true
                btnRelease.isEnabled = true

                btnStart.text = getString(R.string.start_stream)
                btnStart.alpha = 1.0f
                btnStop.alpha = 1.0f
            }

            StreamState.ERROR -> {
                // En caso de error, permitir reinicio
                btnStart.isEnabled = true
                btnStop.isEnabled = false
                btnSwitch.isEnabled = true
                btnMute.isEnabled = false
                btnRelease.isEnabled = true

                btnStart.text = getString(R.string.start_stream)
                btnStart.alpha = 1.0f
                btnStop.alpha = 0.6f
            }
        }

        // Ajuste visual extra (colores) si se definen en resources
        try {
            val primaryColor = ContextCompat.getColor(this, R.color.colorPrimaryAction)
            val stopColor = ContextCompat.getColor(this, R.color.colorStopAction)
            val secondaryColor = ContextCompat.getColor(this, R.color.colorSecondaryAction)
            // Aplicar tints básicos solo si los botones usan background tint
            btnStart.backgroundTintList = android.content.res.ColorStateList.valueOf(primaryColor)
            btnStop.backgroundTintList = android.content.res.ColorStateList.valueOf(stopColor)
            btnSwitch.backgroundTintList = android.content.res.ColorStateList.valueOf(secondaryColor)
            btnMute.backgroundTintList = android.content.res.ColorStateList.valueOf(secondaryColor)
        } catch (e: Exception) {
            // Ignorar si no hay colores definidos o si los backgrounds son drawables fijos
        }
    }
}
