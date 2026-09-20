package se.iteca.eneotaltilltext.audio

import android.media.AudioAttributes
import android.media.MediaPlayer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File

class AudioPlayer {
    private var player: MediaPlayer? = null

    private val _isPlaying = MutableStateFlow(false)
    val isPlaying: StateFlow<Boolean> = _isPlaying.asStateFlow()

    private val _position = MutableStateFlow(0.0)
    val position: StateFlow<Double> = _position.asStateFlow()

    private val _duration = MutableStateFlow(0.0)
    val duration: StateFlow<Double> = _duration.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    fun loadDuration(file: File, fallback: Double) {
        if (_duration.value <= 0) {
            _duration.value = fallback
        }
        if (!file.exists()) return
        runCatching {
            MediaPlayer().apply {
                setDataSource(file.absolutePath)
                prepare()
                _duration.value = duration / 1000.0
                release()
            }
        }
    }

    fun toggle(file: File) {
        _error.value = null
        if (_isPlaying.value) {
            stop(resetPosition = false)
            return
        }
        if (!file.exists()) {
            _error.value = "Ljudfilen saknas."
            return
        }
        try {
            val mediaPlayer = player ?: MediaPlayer().apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .build(),
                )
                setDataSource(file.absolutePath)
                prepare()
                setOnCompletionListener {
                    stop(resetPosition = true)
                }
            }
            val seekMs = (_position.value * 1000).toInt().coerceIn(0, mediaPlayer.duration)
            mediaPlayer.seekTo(seekMs)
            mediaPlayer.start()
            player = mediaPlayer
            _duration.value = mediaPlayer.duration / 1000.0
            _isPlaying.value = true
        } catch (error: Exception) {
            stop(resetPosition = false)
            _error.value = error.localizedMessage ?: error.toString()
        }
    }

    fun clearError() {
        _error.value = null
    }

    fun seek(seconds: Double) {
        val clamped = seconds.coerceAtLeast(0.0)
        _position.value = clamped
        player?.seekTo((clamped * 1000).toInt())
    }

    fun pollPosition() {
        val mediaPlayer = player ?: return
        if (_isPlaying.value && mediaPlayer.isPlaying) {
            _position.value = mediaPlayer.currentPosition / 1000.0
            _duration.value = mediaPlayer.duration / 1000.0
        }
    }

    fun stop(resetPosition: Boolean = false) {
        runCatching { player?.stop() }
        runCatching { player?.release() }
        player = null
        _isPlaying.value = false
        if (resetPosition) _position.value = 0.0
    }
}
