package com.miruronative.ui

import android.view.View
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.miruronative.data.remote.HanimeBridge
import com.miruronative.diagnostics.CrashReporter
import com.miruronative.diagnostics.DiagnosticsLog

/**
 * Hidden resolver WebView for hanime. Not a player surface; it exists so the site's own WASM can
 * produce its rotating credentials and negotiate a stream URL.
 *
 * The caller composes this only while adult content is switched on, so a viewer who never enables
 * it does not carry a third resident WebView — which matters most on the TV sticks where memory
 * pressure already costs us the hardware decoder.
 */
@Composable
fun HanimeResolverWebView() {
    AndroidView(
        factory = { ctx ->
            try {
                DiagnosticsLog.event("HanimeResolverWebView factory create WebView start")
                WebView(ctx).also {
                    it.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
                    it.isFocusable = false
                    it.isClickable = false
                    // Software layer keeps this hidden helper off Chromium's SurfaceControl
                    // overlay path — see PipeWebView for details.
                    it.setLayerType(View.LAYER_TYPE_SOFTWARE, null)
                    HanimeBridge.attach(it)
                    DiagnosticsLog.event("HanimeResolverWebView factory create WebView complete")
                }
            } catch (e: Throwable) {
                CrashReporter.logNonFatal("System WebView unavailable; hanime resolver disabled", e)
                View(ctx)
            }
        },
        onRelease = { view ->
            val web = view as? WebView ?: return@AndroidView
            DiagnosticsLog.event("HanimeResolverWebView release")
            HanimeBridge.detach(web)
            web.stopLoading()
            web.webChromeClient = null
            web.webViewClient = WebViewClient()
            web.loadUrl("about:blank")
            web.destroy()
        },
        modifier = Modifier.size(1.dp),
    )
}
