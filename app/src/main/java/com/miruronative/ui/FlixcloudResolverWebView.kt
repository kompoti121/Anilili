package com.miruronative.ui

import android.view.View
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.miruronative.data.remote.FlixcloudBridge
import com.miruronative.diagnostics.CrashReporter
import com.miruronative.diagnostics.DiagnosticsLog

/**
 * Hidden resolver WebView for flixcloud embeds. It is not a player surface; it only lets
 * flixcloud's own JS/WASM produce the HLS URL so native playback can use it when possible.
 */
@Composable
fun FlixcloudResolverWebView() {
    AndroidView(
        factory = { ctx ->
            try {
                DiagnosticsLog.event("FlixcloudResolverWebView factory create WebView start")
                DiagnosticsLog.webViewPackage("FlixcloudResolverWebView factory")
                WebView(ctx).also {
                    it.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
                    it.isFocusable = false
                    it.isClickable = false
                    // Software layer keeps this hidden helper off Chromium's SurfaceControl
                    // overlay path — see PipeWebView for details.
                    it.setLayerType(View.LAYER_TYPE_SOFTWARE, null)
                    FlixcloudBridge.attach(it)
                    DiagnosticsLog.event("FlixcloudResolverWebView factory create WebView complete")
                }
            } catch (e: Throwable) {
                CrashReporter.logNonFatal("System WebView unavailable; flixcloud native resolver disabled", e)
                View(ctx)
            }
        },
        onRelease = { view ->
            val web = view as? WebView ?: return@AndroidView
            DiagnosticsLog.event("FlixcloudResolverWebView release url=${web.url ?: "none"} size=${web.width}x${web.height}")
            FlixcloudBridge.detach(web)
            web.stopLoading()
            web.webChromeClient = null
            web.webViewClient = WebViewClient()
            web.loadUrl("about:blank")
            web.destroy()
        },
        modifier = Modifier.size(1.dp),
    )
}
