package se.iteca.eneotaltilltext.network

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import okhttp3.Call
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.cancellation.CancellationException
import org.json.JSONArray
import org.json.JSONObject
import se.iteca.eneotaltilltext.data.ApiSettings
import se.iteca.eneotaltilltext.data.ApiSettingsStore
import java.io.File
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

import android.content.Context
import se.iteca.eneotaltilltext.R

class RecordingUploadService(
    private val context: Context,
    private val settingsStore: ApiSettingsStore,
) {
    private val _isUploading = MutableStateFlow(false)
    val isUploading: StateFlow<Boolean> = _isUploading.asStateFlow()

    private val _statusText = MutableStateFlow<String?>(null)
    val statusText: StateFlow<String?> = _statusText.asStateFlow()

    private val _uploadingRecordingId = MutableStateFlow<String?>(null)
    val uploadingRecordingId: StateFlow<String?> = _uploadingRecordingId.asStateFlow()

    private val currentCall = AtomicReference<Call?>(null)
    private val uploadEpoch = AtomicInteger(0)

    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.MINUTES)
        .writeTimeout(30, TimeUnit.MINUTES)
        .callTimeout(4, TimeUnit.HOURS)
        .retryOnConnectionFailure(true)
        .build()

    fun loadSettings(selectedAppId: String? = null): ApiSettings {
        val baseUrl = ApiSettingsStore.makeBaseUrl(settingsStore.baseUrl)
            ?: throw RecordingUploadErrors.missingApiSettings(context)
        val trimmedSelected = selectedAppId?.trim().orEmpty()
        val appId = trimmedSelected.ifEmpty { settingsStore.appId }.ifEmpty {
            throw RecordingUploadErrors.missingAppId(context)
        }
        val token = settingsStore.accessToken
        if (token.isEmpty()) throw RecordingUploadErrors.missingAccessToken(context)
        return ApiSettings(baseUrl = baseUrl, appId = appId, accessToken = token)
    }

    fun isUploading(recordingId: String): Boolean =
        _isUploading.value && _uploadingRecordingId.value == recordingId

    fun cancelUpload() {
        currentCall.get()?.cancel()
        uploadEpoch.incrementAndGet()
        _isUploading.value = false
        _statusText.value = null
        _uploadingRecordingId.value = null
    }

    fun supersedeInFlight() {
        currentCall.get()?.cancel()
        uploadEpoch.incrementAndGet()
    }

    fun markStarting(recordingId: String) {
        _isUploading.value = true
        _uploadingRecordingId.value = recordingId
    }

    /**
     * Lightweight reachability: any HTTP response means the host is reachable
     * (even 401/404). Connection failures surface as network guidance.
     */
    suspend fun probeServerReachability() = withContext(Dispatchers.IO) {
        val baseUrl = ApiSettingsStore.makeBaseUrl(settingsStore.baseUrl)
            ?: throw RecordingUploadErrors.missingApiSettings(context)
        val probeClient = client.newBuilder()
            .connectTimeout(12, TimeUnit.SECONDS)
            .readTimeout(12, TimeUnit.SECONDS)
            .writeTimeout(12, TimeUnit.SECONDS)
            .callTimeout(15, TimeUnit.SECONDS)
            .build()
        val request = Request.Builder().url(baseUrl).get().build()
        probeClient.newCall(request).execute().use { /* any HTTP response = reachable */ }
    }

    suspend fun fetchPersonalSpaceId(): String? = withContext(Dispatchers.IO) {
        val settings = try {
            loadSettings()
        } catch (_: Exception) {
            return@withContext null
        }
        fetchPersonalSpace(settings).firstOrNull()?.id
    }

    suspend fun fetchApps(): List<EneoApp> = withContext(Dispatchers.IO) {
        val settings = loadSettings()
        val spaces = fetchSpaces(settings)
        if (spaces.isEmpty()) throw RecordingUploadErrors.noSpacesFound(context)

        val apps = coroutineScope {
            spaces.map { space ->
                async {
                    val appsEndpoint = EneoResponseParsers.makeUrl(settings.baseUrl, "/api/v1/spaces/${space.id}/applications/")
                    val appsRequest = Request.Builder()
                        .url(appsEndpoint)
                        .get()
                        .header("Authorization", "Bearer ${settings.accessToken}")
                        .build()
                    val listed = execute(appsRequest).use { response ->
                        val body = response.body.string()
                        validate(response, body, context.getString(R.string.debug_fetch_apps, space.name), appsEndpoint, "GET")
                        EneoResponseParsers.parseApps(body, space)
                    }
                    coroutineScope {
                        listed.map { app ->
                            async {
                                val appEndpoint = EneoResponseParsers.makeUrl(settings.baseUrl, "/api/v1/apps/${app.id}/")
                                val appRequest = Request.Builder()
                                    .url(appEndpoint)
                                    .get()
                                    .header("Authorization", "Bearer ${settings.accessToken}")
                                    .build()
                                execute(appRequest).use { response ->
                                    val body = response.body.string()
                                    validate(
                                        response,
                                        body,
                                        context.getString(R.string.debug_fetch_app, app.name),
                                        appEndpoint,
                                        "GET",
                                    )
                                    if (EneoResponseParsers.acceptsAudioInput(body)) app else null
                                }
                            }
                        }.awaitAll().filterNotNull()
                    }
                }
            }.awaitAll().flatten()
        }
        if (apps.isEmpty()) throw RecordingUploadErrors.noAppsFound(context)
        apps.sortedWith(compareBy({ it.spaceName.lowercase() }, { it.name.lowercase() }))
    }

    suspend fun upload(
        audioFile: File,
        audioFileName: String,
        selectedAppId: String?,
        recordingId: String? = null,
        existingFileId: String? = null,
        existingRunId: String? = null,
        onCheckpoint: (suspend (fileId: String?, runId: String?) -> Unit)? = null,
    ): RecordingUploadResponse =
        withContext(Dispatchers.IO) {
            if (!audioFile.exists()) throw RecordingUploadErrors.audioFileMissing(context)
            val epoch = uploadEpoch.incrementAndGet()
            _isUploading.value = true
            _uploadingRecordingId.value = recordingId
            _statusText.value = context.getString(R.string.status_uploading)
            try {
                val settings = loadSettings(selectedAppId)
                var fileId = existingFileId?.trim()?.takeIf { it.isNotEmpty() }
                var runId = existingRunId?.trim()?.takeIf { it.isNotEmpty() }

                if (runId == null) {
                    if (fileId == null) {
                        fileId = uploadAudioFile(audioFile, audioFileName, settings)
                        onCheckpoint?.invoke(fileId, null)
                    } else {
                        _statusText.value = context.getString(R.string.status_transcribing)
                    }
                    runId = startRun(fileId!!, settings)
                    onCheckpoint?.invoke(fileId, runId)
                } else {
                    _statusText.value = context.getString(R.string.status_transcribing)
                }

                val completed = try {
                    pollRun(runId, settings)
                } catch (_: StaleRunException) {
                    if (fileId == null) {
                        fileId = uploadAudioFile(audioFile, audioFileName, settings)
                        onCheckpoint?.invoke(fileId, null)
                    }
                    runId = startRun(fileId!!, settings)
                    onCheckpoint?.invoke(fileId, runId)
                    pollRun(runId, settings)
                }

                RecordingUploadResponse(
                    id = completed.id ?: runId,
                    fileId = fileId,
                    status = completed.status,
                    createdAt = completed.createdAt,
                    output = completed.output,
                )
            } catch (error: java.io.IOException) {
                if (error is java.io.InterruptedIOException || error.message?.contains("Canceled", ignoreCase = true) == true) {
                    throw CancellationException()
                }
                throw error
            } finally {
                if (uploadEpoch.get() == epoch) {
                    currentCall.set(null)
                    _isUploading.value = false
                    _statusText.value = null
                    _uploadingRecordingId.value = null
                }
            }
        }

    fun metadataFrom(markdown: String): RecordingResultMetadata =
        RecordingMetadataParser.from(markdown)

    private fun uploadAudioFile(audioFile: File, audioFileName: String, settings: ApiSettings): String {
        val endpoint = EneoResponseParsers.makeUrl(settings.baseUrl, "/api/v1/files/")
        val mime = mimeType(audioFile)
        val body = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart(
                "upload_file",
                audioFileName,
                audioFile.asRequestBody(mime.toMediaType()),
            )
            .build()
        val request = Request.Builder()
            .url(endpoint)
            .header("Authorization", "Bearer ${settings.accessToken}")
            .post(body)
            .build()
        execute(request).use { response ->
            val responseBody = response.body.string()
            validate(response, responseBody, context.getString(R.string.debug_upload_file), endpoint)
            val fileId = stringValue("id", responseBody)
            if (fileId.isNullOrEmpty()) {
                throw RecordingUploadErrors.missingResponseValue(context, context.getString(R.string.debug_upload_file), "id")
            }
            return fileId
        }
    }

    private fun startRun(fileId: String, settings: ApiSettings): String {
        val endpoint = EneoResponseParsers.makeUrl(settings.baseUrl, "/api/v1/apps/${settings.appId}/runs/")
        val payload = JSONObject()
            .put("files", JSONArray().put(JSONObject().put("id", fileId)))
            .toString()
        val request = Request.Builder()
            .url(endpoint)
            .header("Authorization", "Bearer ${settings.accessToken}")
            .header("Content-Type", "application/json")
            .post(payload.toRequestBody("application/json; charset=utf-8".toMediaType()))
            .build()
        execute(request).use { response ->
            val responseBody = response.body.string()
            validate(response, responseBody, context.getString(R.string.debug_start_run), endpoint)
            val runId = stringValue("id", responseBody)
            if (runId.isNullOrEmpty()) {
                throw RecordingUploadErrors.missingResponseValue(context, context.getString(R.string.debug_start_run), "id")
            }
            return runId
        }
    }

    private suspend fun pollRun(runId: String, settings: ApiSettings): RunResult {
        val endpoint = EneoResponseParsers.makeUrl(settings.baseUrl, "/api/v1/app-runs/$runId/")
        var lastStatus = "queued"
        repeat(MAX_POLL_ATTEMPTS) {
            currentCoroutineContext().ensureActive()
            val result = fetchRunResult(endpoint, settings)
            lastStatus = (result.status ?: lastStatus).trim().lowercase()
            _statusText.value = statusTextFor(lastStatus)
            if (EneoResponseParsers.isCompletedStatus(lastStatus) || (result.hasOutput && result.status == null)) {
                if (!result.hasOutput) {
                    throw RecordingUploadErrors.runFailed(
                        context,
                        lastStatus,
                        context.getString(R.string.error_run_empty_output),
                    )
                }
                _statusText.value = context.getString(R.string.status_downloading)
                return result
            }
            if (EneoResponseParsers.isFailedStatus(lastStatus)) {
                throw RecordingUploadErrors.runFailed(context, lastStatus, result.failureMessage)
            }
            delay(POLL_INTERVAL_MS)
        }
        throw RecordingUploadErrors.runTimedOut(context, lastStatus)
    }

    private suspend fun fetchRunResult(endpoint: String, settings: ApiSettings): RunResult =
        withContext(Dispatchers.IO) {
            val request = Request.Builder()
                .url(endpoint)
                .header("Authorization", "Bearer ${settings.accessToken}")
                .get()
                .build()
            execute(request).use { response ->
                val body = response.body.string()
                if (response.code == 404) {
                    throw StaleRunException()
                }
                validate(response, body, context.getString(R.string.debug_fetch_run), endpoint, "GET")
                EneoResponseParsers.parseRunResult(body)
            }
        }

    private fun statusTextFor(status: String): String = when (status) {
        "queued", "pending", "created" -> context.getString(R.string.status_transcribing)
        "running", "processing", "in_progress", "started" -> context.getString(R.string.status_summarizing)
        "complete", "completed", "success", "succeeded", "done", "finished" -> context.getString(R.string.status_downloading)
        else -> context.getString(R.string.status_generic)
    }

    private fun execute(request: Request): Response {
        val call = client.newCall(request)
        currentCall.set(call)
        return call.execute()
    }

    private fun validate(response: Response, body: String, operation: String, url: String, method: String = "POST") {
        if (response.code == 401) {
            throw RecordingUploadErrors.unauthorized(context, operation, url)
        }
        if (response.code !in 200..299) {
            throw RecordingUploadErrors.serverError(context, operation, response.code, url, method, makeServerMessage(body))
        }
    }

    private suspend fun fetchSpaces(settings: ApiSettings): List<EneoSpace> {
        val fromList = fetchSpacesFromList(settings)
        val personal = fetchPersonalSpace(settings)
        return (fromList + personal).distinctBy { it.id }
    }

    private suspend fun fetchSpacesFromList(settings: ApiSettings): List<EneoSpace> {
        val spacesEndpoint = EneoResponseParsers.makeUrl(
            settings.baseUrl,
            "/api/v1/spaces/",
            mapOf("include_personal" to "true"),
        )
        val spacesRequest = Request.Builder()
            .url(spacesEndpoint)
            .get()
            .header("Authorization", "Bearer ${settings.accessToken}")
            .build()
        return execute(spacesRequest).use { response ->
            val body = response.body.string()
            validate(response, body, context.getString(R.string.debug_fetch_spaces), spacesEndpoint, "GET")
            EneoResponseParsers.parseSpaces(body)
        }
    }

    private suspend fun fetchPersonalSpace(settings: ApiSettings): List<EneoSpace> {
        val spacesEndpoint = EneoResponseParsers.makeUrl(settings.baseUrl, "/api/v1/spaces/type/personal/")
        val spacesRequest = Request.Builder()
            .url(spacesEndpoint)
            .get()
            .header("Authorization", "Bearer ${settings.accessToken}")
            .build()
        return try {
            execute(spacesRequest).use { response ->
                val body = response.body.string()
                validate(response, body, context.getString(R.string.debug_fetch_spaces), spacesEndpoint, "GET")
                EneoResponseParsers.parseSpaces(body)
            }
        } catch (_: Exception) {
            emptyList()
        }
    }


    companion object {
        private class StaleRunException : Exception()

        private const val MAX_POLL_ATTEMPTS = 1_080
        private const val POLL_INTERVAL_MS = 10_000L

        private fun mimeType(file: File): String = when (file.extension.lowercase()) {
            "m4a" -> "audio/x-m4a"
            "mp3" -> "audio/mpeg"
            "wav" -> "audio/wav"
            "aac" -> "audio/aac"
            "caf" -> "audio/x-caf"
            "flac" -> "audio/flac"
            else -> "application/octet-stream"
        }

        private fun makeServerMessage(body: String): String? {
            if (body.isEmpty()) return null
            body.toJsonObject()?.let { json ->
                json.optStringOrNull("detail")?.let { return it }
                json.optStringOrNull("message")?.let { return it }
                json.optStringOrNull("error")?.let { return it }
                if (json.has("output") && !json.isNull("output")) {
                    return json.opt("output")?.toString()
                }
            }
            return body
        }

        private fun stringValue(key: String, body: String): String? {
            return body.toJsonObject()?.optStringOrNull(key)
        }

        private fun String.toJsonObject(): JSONObject? = try {
            JSONObject(this)
        } catch (_: Exception) {
            null
        }

        private fun JSONObject.optStringOrNull(key: String): String? {
            if (!has(key) || isNull(key)) return null
            val value = opt(key)
            val text = (value as? String) ?: value?.toString()
            return text?.trim()?.takeIf { it.isNotEmpty() && it != "null" }
        }
    }
}
