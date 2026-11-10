package se.iteca.eneotaltilltext.audio

import android.content.Context
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaRecorder
import android.os.SystemClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.log10
import se.iteca.eneotaltilltext.data.RecordingEntity
import java.io.File

enum class RecordingState {
    Idle,
    Recording,
    Paused,
}

class AudioRecorder(context: Context) {
    companion object {
        private const val SAMPLE_RATE_HZ = 48_000
        private const val BIT_RATE = 128_000
        private const val AUTOSAVE_INTERVAL_MS = 30_000L
    }

    private val appContext = context.applicationContext
    private val audioManager = appContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val _state = MutableStateFlow(RecordingState.Idle)
    val state: StateFlow<RecordingState> = _state.asStateFlow()

    private val _recordingTime = MutableStateFlow(0.0)
    val recordingTime: StateFlow<Double> = _recordingTime.asStateFlow()

    private val _audioLevels = MutableStateFlow<List<Float>>(emptyList())
    val audioLevels: StateFlow<List<Float>> = _audioLevels.asStateFlow()

    private val _wasInterrupted = MutableStateFlow(false)
    val wasInterrupted: StateFlow<Boolean> = _wasInterrupted.asStateFlow()

    private val _reachedMaxDuration = MutableStateFlow(false)
    val reachedMaxDuration: StateFlow<Boolean> = _reachedMaxDuration.asStateFlow()

    /**
     * True from stop until the final audio file has been written. The UI must
     * not start a new session or dismiss while this is set.
     */
    private val _isFinalizing = MutableStateFlow(false)
    val isFinalizing: StateFlow<Boolean> = _isFinalizing.asStateFlow()

    /** Final target file (`recording_<epoch>.m4a`) after session stop/concat. */
    var sessionFinalFile: File? = null
        private set

    val currentFile: File?
        get() = sessionFinalFile

    var lastCompletedDuration: Double = 0.0
        private set

    /** Invoked after each finalized segment (and after pause finalize) with total session duration so far. */
    var onSegmentFinalized: ((totalDurationSeconds: Double) -> Unit)? = null

    private var segmentRecorder: GainedAacSegmentRecorder? = null
    private var meterJob: Job? = null
    private var rotateJob: Job? = null
    private var sessionDirectory: File? = null
    private var sessionBaseName: String? = null
    private var nextPartIndex = 1
    private val completedParts = mutableListOf<File>()
    private var completedDurationSeconds = 0.0
    private var segmentStartedAtElapsed = 0L
    private var sessionStartedAtElapsed = 0L
    private var pausedAccumulated = 0L
    private var pauseStartedElapsed = 0L
    private var shouldResumeAfterInterruption = false
    private var focusRequest: AudioFocusRequest? = null
    private val stopMutex = Mutex()
    private val discardRequested = AtomicBoolean(false)

    val isNearMaxDuration: Boolean
        get() = _recordingTime.value >= RecordingEntity.MAX_RECORDING_DURATION_SECONDS - 60

    /**
     * Starts a recording session. [finalFile] is the eventual merged output
     * (`recording_<epoch>.m4a`); audio is written to `_p001`, `_p002`, … until stop.
     */
    fun start(finalFile: File) {
        if (_isFinalizing.value) return
        if (_state.value != RecordingState.Idle) return
        val directory = finalFile.parentFile ?: return
        directory.mkdirs()
        val baseName = RecordingSegmentNaming.baseNameFromFinalFileName(finalFile.name)

        resetSessionState()
        sessionDirectory = directory
        sessionBaseName = baseName
        sessionFinalFile = RecordingSegmentNaming.finalFile(directory, baseName)
        nextPartIndex = 1
        sessionStartedAtElapsed = SystemClock.elapsedRealtime()

        startNextSegment()
        requestAudioFocus()
        _state.value = RecordingState.Recording
        startMetering()
        startAutosaveTimer()
    }

    fun pause() {
        if (_isFinalizing.value) return
        if (_state.value != RecordingState.Recording) return
        stopAutosaveTimer()
        stopMetering()
        finalizeCurrentSegment(addToCompleted = true)
        pauseStartedElapsed = SystemClock.elapsedRealtime()
        _recordingTime.value = totalElapsedSeconds()
        _state.value = RecordingState.Paused
        notifySegmentFinalized()
    }

    fun resume() {
        if (_isFinalizing.value) return
        if (_state.value != RecordingState.Paused) return
        pausedAccumulated += SystemClock.elapsedRealtime() - pauseStartedElapsed
        pauseStartedElapsed = 0L
        try {
            startNextSegment()
        } catch (_: Exception) {
            // Still paused: keep counting paused time so the clock stays right.
            pauseStartedElapsed = SystemClock.elapsedRealtime()
            return
        }
        _wasInterrupted.value = false
        shouldResumeAfterInterruption = false
        _state.value = RecordingState.Recording
        startMetering()
        startAutosaveTimer()
    }

    /**
     * Stops capture and merges segments. The merge runs off the main thread and
     * does not delete parts when it fails, so a later recovery can try again.
     * State stays non-idle until the merge finishes, which keeps the foreground
     * service alive. [isFinalizing] lets the UI hide controls and block a new
     * session. A second stop while the first merge is still running must not
     * re-run the merge.
     */
    suspend fun stop(): Double = withContext(NonCancellable) {
        stopMutex.withLock {
            if (_isFinalizing.value) {
                return@withLock lastCompletedDuration
            }
            if (_state.value == RecordingState.Idle && segmentRecorder == null && completedParts.isEmpty()) {
                return@withLock lastCompletedDuration
            }
            _isFinalizing.value = true
            try {
                stopAutosaveTimer()
                stopMetering()
                if (segmentRecorder != null) {
                    finalizeCurrentSegment(addToCompleted = true)
                }
                val duration = totalElapsedSeconds().coerceAtMost(RecordingEntity.MAX_RECORDING_DURATION_SECONDS)
                lastCompletedDuration = duration
                abandonAudioFocus()

                val finalFile = sessionFinalFile
                val parts = completedParts.toList()
                val merged = if (finalFile != null && parts.isNotEmpty() && !discardRequested.get()) {
                    withContext(Dispatchers.IO) {
                        M4aSegmentConcatenator.concatenate(parts, finalFile)
                    }
                } else {
                    false
                }
                if (discardRequested.getAndSet(false)) {
                    parts.forEach { part ->
                        if (finalFile == null || part.absolutePath != finalFile.absolutePath) part.delete()
                    }
                    finalFile?.delete()
                } else if (merged && finalFile != null) {
                    parts.forEach { part ->
                        if (part.absolutePath != finalFile.absolutePath) part.delete()
                    }
                }

                clearSessionLists()
                _state.value = RecordingState.Idle
                _audioLevels.value = emptyList()
                _recordingTime.value = 0.0
                shouldResumeAfterInterruption = false
                _wasInterrupted.value = false
                onSegmentFinalized = null
                duration
            } finally {
                _isFinalizing.value = false
            }
        }
    }

    fun cancel(deleteFile: Boolean = true) {
        if (deleteFile) discardRequested.set(true)
        if (!stopMutex.tryLock()) {
            // stop() holds the lock while merging. It deletes the result when it sees the flag.
            return
        }
        try {
            stopAutosaveTimer()
            stopMetering()
            runCatching { segmentRecorder?.cancel() }
            segmentRecorder = null
            abandonAudioFocus()

            if (deleteFile) {
                completedParts.forEach { it.delete() }
                openPartFile()?.delete()
                sessionFinalFile?.delete()
            }
            discardRequested.set(false)

            clearSessionLists()
            sessionFinalFile = null
            sessionDirectory = null
            sessionBaseName = null
            _state.value = RecordingState.Idle
            _recordingTime.value = 0.0
            _audioLevels.value = emptyList()
            shouldResumeAfterInterruption = false
            _wasInterrupted.value = false
            _reachedMaxDuration.value = false
            lastCompletedDuration = 0.0
            onSegmentFinalized = null
        } finally {
            stopMutex.unlock()
        }
    }

    private fun startNextSegment() {
        val directory = sessionDirectory ?: return
        val baseName = sessionBaseName ?: return
        val part = RecordingSegmentNaming.partFile(directory, baseName, nextPartIndex)
        nextPartIndex += 1
        part.parentFile?.mkdirs()
        if (part.exists()) part.delete()

        segmentRecorder = startRecorderWithPreferredSource(part)
        segmentStartedAtElapsed = SystemClock.elapsedRealtime()
    }

    /**
     * Prefer sources without noise suppression / AGC.
     * Soft digital gain is applied in [GainedAacSegmentRecorder].
     */
    private fun startRecorderWithPreferredSource(outputFile: File): GainedAacSegmentRecorder {
        val sources = preferredAudioSources()
        var lastError: Exception? = null
        for (source in sources) {
            try {
                return startRecorder(outputFile, source)
            } catch (error: Exception) {
                lastError = error
            }
        }
        throw lastError ?: IllegalStateException("Kunde inte starta mikrofoninspelning")
    }

    private fun preferredAudioSources(): List<Int> {
        val sources = mutableListOf<Int>()
        sources += MediaRecorder.AudioSource.VOICE_RECOGNITION
        val supportsUnprocessed =
            audioManager.getProperty(AudioManager.PROPERTY_SUPPORT_AUDIO_SOURCE_UNPROCESSED) == "true"
        if (supportsUnprocessed) {
            sources += MediaRecorder.AudioSource.UNPROCESSED
        }
        sources += MediaRecorder.AudioSource.CAMCORDER
        sources += MediaRecorder.AudioSource.MIC
        return sources.distinct()
    }

    private fun finalizeCurrentSegment(addToCompleted: Boolean) {
        val part = openPartFile()
        runCatching { segmentRecorder?.stop() }
        segmentRecorder = null
        if (addToCompleted && part != null && part.exists() && part.length() > 1024) {
            completedParts += part
            completedDurationSeconds += segmentElapsedSeconds()
        } else {
            part?.delete()
        }
    }

    private suspend fun rotateSegment() {
        if (_state.value != RecordingState.Recording) return
        finalizeCurrentSegment(addToCompleted = true)
        notifySegmentFinalized()
        if (_state.value != RecordingState.Recording) return
        if (totalElapsedSeconds() >= RecordingEntity.MAX_RECORDING_DURATION_SECONDS) {
            _reachedMaxDuration.value = true
            stop()
            _recordingTime.value = RecordingEntity.MAX_RECORDING_DURATION_SECONDS
            return
        }
        try {
            startNextSegment()
        } catch (_: Exception) {
            // Leave session paused-like if we cannot open the next part.
            // Mark when the pause began so the clock stops with it.
            _state.value = RecordingState.Paused
            pauseStartedElapsed = SystemClock.elapsedRealtime()
            _wasInterrupted.value = true
            stopAutosaveTimer()
            stopMetering()
        }
    }

    private fun openPartFile(): File? {
        val directory = sessionDirectory ?: return null
        val baseName = sessionBaseName ?: return null
        val index = nextPartIndex - 1
        if (index < 1) return null
        return RecordingSegmentNaming.partFile(directory, baseName, index)
    }

    private fun notifySegmentFinalized() {
        onSegmentFinalized?.invoke(
            totalElapsedSeconds().coerceAtMost(RecordingEntity.MAX_RECORDING_DURATION_SECONDS),
        )
    }

    private fun startRecorder(outputFile: File, audioSource: Int): GainedAacSegmentRecorder {
        if (audioManager.mode != AudioManager.MODE_NORMAL) {
            audioManager.mode = AudioManager.MODE_NORMAL
        }
        val recorder = GainedAacSegmentRecorder(
            outputFile = outputFile,
            audioSource = audioSource,
            context = appContext,
            sampleRateHz = SAMPLE_RATE_HZ,
            bitRate = BIT_RATE,
        )
        recorder.start()
        return recorder
    }

    private fun segmentElapsedSeconds(): Double {
        if (segmentRecorder == null && _state.value != RecordingState.Recording) {
            return 0.0
        }
        val now = SystemClock.elapsedRealtime()
        return ((now - segmentStartedAtElapsed).coerceAtLeast(0)) / 1000.0
    }

    private fun totalElapsedSeconds(): Double {
        val now = SystemClock.elapsedRealtime()
        val paused = pausedAccumulated + if (_state.value == RecordingState.Paused) {
            now - pauseStartedElapsed
        } else {
            0L
        }
        return ((now - sessionStartedAtElapsed - paused).coerceAtLeast(0)) / 1000.0
    }

    private fun startMetering() {
        stopMetering()
        meterJob = scope.launch {
            while (isActive && _state.value == RecordingState.Recording) {
                val time = totalElapsedSeconds()
                _recordingTime.value = time
                if (time >= RecordingEntity.MAX_RECORDING_DURATION_SECONDS) {
                    _reachedMaxDuration.value = true
                    stop()
                    _recordingTime.value = RecordingEntity.MAX_RECORDING_DURATION_SECONDS
                    break
                }
                val amplitude = segmentRecorder?.maxAmplitude ?: 0
                val normalized = normalizeMeterAmplitude(amplitude)
                val next = (_audioLevels.value + normalized).takeLast(150)
                _audioLevels.value = next
                delay(50)
            }
        }
    }

    private fun stopMetering() {
        meterJob?.cancel()
        meterJob = null
    }

    private fun startAutosaveTimer() {
        stopAutosaveTimer()
        rotateJob = scope.launch {
            while (isActive && _state.value == RecordingState.Recording) {
                delay(AUTOSAVE_INTERVAL_MS)
                if (_state.value == RecordingState.Recording) {
                    rotateSegment()
                }
            }
        }
    }

    private fun stopAutosaveTimer() {
        rotateJob?.cancel()
        rotateJob = null
    }

    private fun normalizeMeterAmplitude(amplitude: Int): Float {
        if (amplitude <= 0) return 0.04f
        val ratio = amplitude.coerceAtMost(32767).toDouble() / 32767.0
        val db = 20.0 * log10(ratio.coerceAtLeast(1e-6))
        return ((db + 50.0) / 50.0).toFloat().coerceIn(0.04f, 1f)
    }

    private fun requestAudioFocus() {
        val listener = AudioManager.OnAudioFocusChangeListener { change ->
            when (change) {
                AudioManager.AUDIOFOCUS_LOSS,
                AudioManager.AUDIOFOCUS_LOSS_TRANSIENT,
                -> {
                    if (_state.value == RecordingState.Recording) {
                        shouldResumeAfterInterruption = change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT
                        pause()
                        _wasInterrupted.value = true
                    }
                }
                AudioManager.AUDIOFOCUS_GAIN -> {
                    if (shouldResumeAfterInterruption && _state.value == RecordingState.Paused) {
                        resume()
                    }
                    shouldResumeAfterInterruption = false
                }
            }
        }
        val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setOnAudioFocusChangeListener(listener)
            .setWillPauseWhenDucked(true)
            .build()
        focusRequest = request
        audioManager.requestAudioFocus(request)
    }

    private fun abandonAudioFocus() {
        focusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
        focusRequest = null
    }

    private fun resetSessionState() {
        discardRequested.set(false)
        completedParts.clear()
        completedDurationSeconds = 0.0
        pausedAccumulated = 0L
        _audioLevels.value = emptyList()
        _recordingTime.value = 0.0
        _reachedMaxDuration.value = false
        _wasInterrupted.value = false
        shouldResumeAfterInterruption = false
        lastCompletedDuration = 0.0
    }

    private fun clearSessionLists() {
        completedParts.clear()
        completedDurationSeconds = 0.0
        nextPartIndex = 1
        sessionDirectory = null
        sessionBaseName = null
    }
}
