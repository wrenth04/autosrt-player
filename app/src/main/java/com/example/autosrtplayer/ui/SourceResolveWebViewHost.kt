package com.example.autosrtplayer.ui

import android.annotation.SuppressLint
import android.graphics.Color as AndroidColor
import android.os.SystemClock
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.util.UnstableApi
import org.json.JSONArray

@SuppressLint("SetJavaScriptEnabled")
@Composable
internal fun SourceResolveWebViewHost(
    request: SourceWebResolveRequest,
    onHtmlResolved: (requestId: Long, html: String, userAgent: String, finalUrl: String) -> Unit,
    onResolveFailed: (requestId: Long, message: String) -> Unit
) {
    val latestRequestState = rememberUpdatedState(request)
    val latestOnHtmlResolvedState = rememberUpdatedState(onHtmlResolved)
    val latestOnResolveFailedState = rememberUpdatedState(onResolveFailed)
    var webView by remember { mutableStateOf<WebView?>(null) }
    val startedAtMs = remember(request.requestId) { SystemClock.elapsedRealtime() }
    val startedAtMsState = rememberUpdatedState(startedAtMs)

    DisposableEffect(request.requestId) {
        onDispose {
            webView?.stopLoading()
            webView?.destroy()
            webView = null
        }
    }

    AndroidView(
        factory = { viewContext ->
            WebView(viewContext).apply {
                webView = this
                tag = request.requestId
                setBackgroundColor(AndroidColor.TRANSPARENT)
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                settings.loadWithOverviewMode = true
                settings.useWideViewPort = true
                CookieManager.getInstance().setAcceptCookie(true)
                webChromeClient = WebChromeClient()
                webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView, url: String?) {
                        super.onPageFinished(view, url)
                        val activeRequest = latestRequestState.value
                        if ((view.tag as? Long) != activeRequest.requestId) return
                        pollForHtml(
                            view,
                            activeRequest,
                            startedAtMsState.value,
                            latestOnHtmlResolvedState.value,
                            latestOnResolveFailedState.value
                        )
                    }

                    override fun onReceivedError(
                        view: WebView,
                        request: WebResourceRequest,
                        error: WebResourceError
                    ) {
                        if (!request.isForMainFrame) return
                        val activeRequest = latestRequestState.value
                        if ((view.tag as? Long) != activeRequest.requestId) return
                        latestOnResolveFailedState.value(activeRequest.requestId, "解析失敗，請稍後再試或改用手動 M3U8。")
                    }
                }
                loadUrl(request.url)
            }
        },
        update = { view ->
            if (view.tag != request.requestId) {
                view.tag = request.requestId
                view.stopLoading()
                view.loadUrl(request.url)
            }
        },
        modifier = Modifier
            .fillMaxWidth()
            .height(1.dp)
            .alpha(0.01f)
    )
}

private fun pollForHtml(
    webView: WebView,
    request: SourceWebResolveRequest,
    startedAtMs: Long,
    onHtmlResolved: (requestId: Long, html: String, userAgent: String, finalUrl: String) -> Unit,
    onResolveFailed: (requestId: Long, message: String) -> Unit
) {
    if ((webView.tag as? Long) != request.requestId) return
    val elapsed = SystemClock.elapsedRealtime() - startedAtMs
    if (elapsed > 25_000L) {
        onResolveFailed(request.requestId, "解析失敗，請稍後再試或改用手動 M3U8。")
        return
    }

    webView.evaluateJavascript("document.documentElement.outerHTML") { htmlResult ->
        val html = decodeJsValue(htmlResult).orEmpty()
        if (html.contains("eval(function(p,a,c,k,e,d)") || html.contains(".m3u8")) {
            webView.evaluateJavascript("navigator.userAgent") { uaResult ->
                val userAgent = decodeJsValue(uaResult).orEmpty().ifBlank { webView.settings.userAgentString.orEmpty() }
                webView.evaluateJavascript("location.href") { urlResult ->
                    val finalUrl = decodeJsValue(urlResult).orEmpty().ifBlank { request.url }
                    if ((webView.tag as? Long) == request.requestId) {
                        onHtmlResolved(request.requestId, html, userAgent, finalUrl)
                    }
                }
            }
            return@evaluateJavascript
        }

        webView.postDelayed({ pollForHtml(webView, request, startedAtMs, onHtmlResolved, onResolveFailed) }, 700L)
    }
}

private fun decodeJsValue(value: String?): String {
    if (value.isNullOrBlank() || value == "null") return ""
    return runCatching { JSONArray("[$value]").getString(0) }.getOrDefault(value.trim('"'))
}
