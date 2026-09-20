package se.iteca.eneotaltilltext.network

import android.content.Context
import se.iteca.eneotaltilltext.R
import se.iteca.eneotaltilltext.data.ErrorPresentation
import se.iteca.eneotaltilltext.data.PresentableException

object RecordingUploadErrors {
    fun missingApiSettings(context: Context) = PresentableException(
        ErrorPresentation(
            title = context.getString(R.string.auth_invalid_url_title),
            message = context.getString(R.string.auth_invalid_url_message),
        ),
    )

    fun missingAppId(context: Context) = PresentableException(
        ErrorPresentation(
            title = context.getString(R.string.error_missing_app_id),
            message = context.getString(R.string.error_missing_app_id_message),
        ),
    )

    fun missingAccessToken(context: Context) = PresentableException(
        ErrorPresentation(
            title = context.getString(R.string.error_unauthorized_title),
            message = context.getString(R.string.error_unauthorized_message),
            requiresSignIn = true,
        ),
    )

    fun audioFileMissing(context: Context) = PresentableException(
        ErrorPresentation(
            title = context.getString(R.string.detail_audio_missing_title),
            message = context.getString(R.string.error_audio_missing_upload_message),
        ),
    )

    fun noSpacesFound(context: Context) = PresentableException(
        ErrorPresentation(
            title = context.getString(R.string.error_no_spaces_found),
            message = context.getString(R.string.error_no_spaces_message),
        ),
    )

    fun noAppsFound(context: Context) = PresentableException(
        ErrorPresentation(
            title = context.getString(R.string.error_no_apps_found),
            message = context.getString(R.string.error_no_apps_message),
        ),
    )

    fun missingResponseValue(context: Context, operation: String, key: String) = PresentableException(
        ErrorPresentation(
            title = context.getString(R.string.error_missing_response_title),
            message = context.getString(R.string.error_missing_response_message),
            technicalDetails = context.getString(R.string.error_missing_response_details, operation, key),
        ),
    )

    fun runFailed(context: Context, status: String, message: String?) = PresentableException(
        ErrorPresentation(
            title = context.getString(R.string.error_run_failed_title),
            message = if (!message.isNullOrBlank() && message != "<null>") {
                message
            } else {
                context.getString(R.string.error_run_failed_no_reason)
            },
            technicalDetails = if (!message.isNullOrBlank() && message != "<null>") {
                context.getString(R.string.error_run_failed_details_message, status, message)
            } else {
                context.getString(R.string.error_run_failed_details, status)
            },
        ),
    )

    fun runTimedOut(context: Context, status: String) = PresentableException(
        ErrorPresentation(
            title = context.getString(R.string.error_run_timeout_title),
            message = context.getString(R.string.error_run_timeout_message),
            technicalDetails = context.getString(R.string.error_run_timeout_details, status),
        ),
    )

    fun serverError(
        context: Context,
        operation: String,
        statusCode: Int,
        url: String,
        method: String,
        message: String?,
    ) =
        PresentableException(
            ErrorPresentation(
                title = context.getString(R.string.error_server_title),
                message = message?.takeIf { it.isNotBlank() }
                    ?: context.getString(R.string.auth_server_error_message),
                technicalDetails = if (message.isNullOrBlank()) {
                    context.getString(
                        R.string.error_server_details,
                        operation,
                        statusCode,
                        method,
                        url,
                        context.getString(R.string.debug_auth_bearer),
                    )
                } else {
                    context.getString(
                        R.string.error_server_details_body,
                        operation,
                        statusCode,
                        method,
                        url,
                        context.getString(R.string.debug_auth_bearer),
                        message,
                    )
                },
            ),
        )

    fun unauthorized(context: Context, operation: String, url: String) = PresentableException(
        ErrorPresentation(
            title = context.getString(R.string.error_unauthorized_title),
            message = context.getString(R.string.error_unauthorized_message),
            technicalDetails = context.getString(R.string.error_unauthorized_details, operation, url),
            requiresSignIn = true,
        ),
    )
}

data class EneoApp(
    val id: String,
    val name: String,
    val spaceId: String,
    val spaceName: String,
) {
    val displayName: String
        get() = formatAppDisplayName(name, spaceName)
}

fun formatAppDisplayName(name: String, spaceName: String): String {
    val trimmedName = name.trim()
    val trimmedSpace = spaceName.trim()
    return if (trimmedSpace.isEmpty()) trimmedName else "[$trimmedSpace] $trimmedName"
}

data class GroupedEneoApps(
    val personal: List<EneoApp>,
    val shared: List<EneoApp>,
)

fun groupEneoApps(apps: List<EneoApp>, personalSpaceId: String?): GroupedEneoApps {
    val personal = personalSpaceId?.let { id ->
        apps.filter { it.spaceId == id }.sortedBy { it.name.lowercase() }
    }.orEmpty()
    val shared = apps.filter { personalSpaceId == null || it.spaceId != personalSpaceId }
        .sortedWith(compareBy({ it.spaceName.lowercase() }, { it.name.lowercase() }))
    return GroupedEneoApps(personal, shared)
}

fun EneoApp.pickerLabel(isPersonalSection: Boolean): String =
    if (isPersonalSection) name else displayName

data class RecordingUploadResponse(
    val id: String?,
    val fileId: String?,
    val status: String?,
    val createdAt: String?,
    val output: String?,
)

data class RecordingResultMetadata(
    val suggestedTitle: String?,
    val shortSummary: String?,
)

internal data class EneoSpace(
    val id: String,
    val name: String,
)

internal data class RunResult(
    val id: String?,
    val status: String?,
    val createdAt: String?,
    val output: String?,
    val failureMessage: String?,
) {
    val hasOutput: Boolean
        get() = !output.isNullOrBlank()
}
