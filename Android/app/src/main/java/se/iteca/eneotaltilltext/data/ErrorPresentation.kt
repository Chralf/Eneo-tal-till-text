package se.iteca.eneotaltilltext.data

import android.content.Context
import se.iteca.eneotaltilltext.R
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import kotlin.coroutines.cancellation.CancellationException

data class ErrorPresentation(
    val title: String,
    val message: String? = null,
    val technicalDetails: String? = null,
    val requiresSignIn: Boolean = false,
) {
    fun persistedText(): String {
        val main = listOfNotNull(title, message).joinToString("\n")
        val tech = technicalDetails?.takeIf { it.isNotBlank() } ?: return main
        return main + TECH_MARKER + tech
    }

    companion object {
        fun from(context: Context, error: Throwable): ErrorPresentation {
            if (error is PresentableException) return error.presentation
            if (error is CancellationException) {
                return ErrorPresentation(title = context.getString(R.string.error_cancelled_title))
            }
            val details = error.javaClass.simpleName + ": " + (error.localizedMessage ?: error.toString())
            return when (error) {
                is UnknownHostException -> ErrorPresentation(
                    title = context.getString(R.string.error_host_title),
                    message = context.getString(R.string.error_host_message),
                    technicalDetails = details,
                )
                is SocketTimeoutException -> ErrorPresentation(
                    title = context.getString(R.string.error_timeout_title),
                    message = context.getString(R.string.error_timeout_message),
                    technicalDetails = details,
                )
                is IOException -> {
                    val message = error.localizedMessage.orEmpty().lowercase()
                    if ("unable to resolve host" in message || "failed to connect" in message) {
                        ErrorPresentation(
                            title = context.getString(R.string.error_host_title),
                            message = context.getString(R.string.error_host_message),
                            technicalDetails = details,
                        )
                    } else if ("network" in message || "offline" in message || "unreachable" in message) {
                        ErrorPresentation(
                            title = context.getString(R.string.error_offline_title),
                            message = context.getString(R.string.error_offline_message),
                            technicalDetails = details,
                        )
                    } else {
                        ErrorPresentation(
                            title = context.getString(R.string.error_unknown),
                            message = error.localizedMessage,
                            technicalDetails = details,
                        )
                    }
                }
                else -> ErrorPresentation(
                    title = context.getString(R.string.error_unknown),
                    message = error.localizedMessage ?: error.toString(),
                    technicalDetails = details,
                )
            }
        }

        fun fromPersisted(context: Context, text: String): ErrorPresentation {
            val techSplit = text.split(TECH_MARKER, limit = 2)
            val lines = techSplit[0].split("\n", limit = 2)
            val title = lines.first()
            val requiresSignIn = title == context.getString(R.string.error_unauthorized_title) ||
                title == context.getString(R.string.auth_missing_token_title)
            return ErrorPresentation(
                title = title,
                message = lines.getOrNull(1)?.takeIf { it.isNotBlank() },
                technicalDetails = techSplit.getOrNull(1)?.takeIf { it.isNotBlank() },
                requiresSignIn = requiresSignIn,
            )
        }

        private const val TECH_MARKER = "\n\u001eTECH\u001e\n"
    }
}

class PresentableException(val presentation: ErrorPresentation) : Exception(presentation.title)
