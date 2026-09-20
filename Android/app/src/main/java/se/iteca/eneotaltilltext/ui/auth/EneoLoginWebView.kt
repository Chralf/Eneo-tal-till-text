package se.iteca.eneotaltilltext.ui.auth

import android.annotation.SuppressLint
import android.net.Uri
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import se.iteca.eneotaltilltext.network.EneoAuthService

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun EneoLoginWebView(
    authorizationUrl: Uri,
    callbackHost: String,
    onCallback: (Uri) -> Unit,
    modifier: Modifier = Modifier,
) {
    AndroidView(
        modifier = modifier,
        factory = { context ->
            WebView(context).apply {
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                settings.allowFileAccess = false
                settings.allowContentAccess = false
                settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                CookieManager.getInstance().setAcceptCookie(true)
                CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                webViewClient = object : WebViewClient() {
                    private var captured = false

                    private fun captureIfCallback(url: Uri): Boolean {
                        if (captured) return false
                        if (!EneoAuthService.isLoginCallback(url, callbackHost)) return false
                        captured = true
                        CookieManager.getInstance().flush()
                        onCallback(url)
                        return true
                    }

                    override fun shouldOverrideUrlLoading(
                        view: WebView,
                        request: WebResourceRequest,
                    ): Boolean = captureIfCallback(request.url)

                    override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                        val parsed = url?.let(Uri::parse) ?: return
                        if (captureIfCallback(parsed)) {
                            view?.stopLoading()
                        }
                    }
                }
                loadUrl(authorizationUrl.toString())
            }
        },
        onRelease = { webView ->
            webView.stopLoading()
            webView.destroy()
        },
    )
}
