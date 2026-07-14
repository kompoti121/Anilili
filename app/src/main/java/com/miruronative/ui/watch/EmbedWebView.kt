package com.miruronative.ui.watch

import android.annotation.SuppressLint
import android.content.Context
import android.content.ContextWrapper
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.webkit.JavascriptInterface
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebChromeClient.CustomViewCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import com.miruronative.data.model.SkipTimes
import com.miruronative.data.settings.SettingsStore
import com.miruronative.diagnostics.CrashReporter
import com.miruronative.diagnostics.DiagnosticsLog
import com.miruronative.ui.adaptive.LocalAppDeviceProfile
import com.miruronative.ui.adaptive.focusHighlight
import kotlinx.coroutines.delay

/**
 * Renders an embed/iframe provider (or the live site) in a WebView. This is both the player for
 * `type:"embed"` sources and the durable fallback when the native path is Cloudflare-blocked.
 *
 * Main-frame navigation is pinned to the embed's own host: ad-funded embed pages fire same-frame
 * redirects that would otherwise replace the video with an ad page. Resource and iframe loads are
 * unaffected, so the players themselves keep working.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun EmbedWebView(
    url: String,
    referer: String?,
    modifier: Modifier = Modifier,
    skip: SkipTimes? = null,
    onPreviousEpisode: (() -> Unit)? = null,
    onNextEpisode: (() -> Unit)? = null,
    hasPreviousEpisode: Boolean = false,
    hasNextEpisode: Boolean = false,
    onFullscreenChanged: (Boolean) -> Unit = {},
    onProgress: ((positionMs: Long, durationMs: Long) -> Unit)? = null,
) {
    val device = LocalAppDeviceProfile.current
    val lifecycleOwner = LocalContext.current.findLifecycleOwner()
    var loadError by remember { mutableStateOf<String?>(null) }
    var webView by remember { mutableStateOf<WebView?>(null) }
    var finishedUrl by remember(url) { mutableStateOf<String?>(null) }
    val currentOnFullscreenChanged by rememberUpdatedState(onFullscreenChanged)
    val currentOnProgress by rememberUpdatedState(onProgress)
    val currentOnPreviousEpisode by rememberUpdatedState(onPreviousEpisode)
    val currentOnNextEpisode by rememberUpdatedState(onNextEpisode)
    val currentHasPreviousEpisode by rememberUpdatedState(hasPreviousEpisode)
    val currentHasNextEpisode by rememberUpdatedState(hasNextEpisode)
    val mainHandler = remember { Handler(Looper.getMainLooper()) }
    val previousFocus = remember { FocusRequester() }
    val nextFocus = remember { FocusRequester() }
    var positionMs by remember { mutableLongStateOf(0L) }
    val autoSkipIntroOutro by SettingsStore.autoSkipIntroOutro.collectAsState()
    val autoplay by SettingsStore.autoplay.collectAsState()
    val introStartMs = skip?.introStart?.times(1000)?.toLong() ?: 0L
    val introEndMs = skip?.introEnd?.times(1000)?.toLong()
    val outroStartMs = skip?.outroStart?.times(1000)?.toLong()
    val outroEndMs = skip?.outroEnd?.times(1000)?.toLong()
    var introAutoSkipped by remember(url, introStartMs, introEndMs) { mutableStateOf(false) }
    var outroAutoHandled by remember(url, outroStartMs, outroEndMs) { mutableStateOf(false) }

    LaunchedEffect(url, webView, device.isTv, hasPreviousEpisode, hasNextEpisode) {
        if (!device.isTv || webView == null) return@LaunchedEffect
        delay(250)
        runCatching {
            when {
                hasNextEpisode -> nextFocus.requestFocus()
                hasPreviousEpisode -> previousFocus.requestFocus()
                else -> webView?.requestFocus()
            }
        }
    }

    // Registrable-ish host the embed is allowed to navigate within (its own host + subdomains).
    val allowedHost = remember(url) {
        runCatching { Uri.parse(url).host }.getOrNull()?.lowercase()?.removePrefix("www.")
    }
    LaunchedEffect(url) {
        DiagnosticsLog.event("EmbedWebView composed urlHost=${allowedHost ?: "unknown"} refererHost=${referer.hostOrNone()}")
        delay(10_000)
        if (finishedUrl == null && loadError == null) {
            DiagnosticsLog.event("EmbedWebView still loading after 10000ms urlHost=${allowedHost ?: "unknown"}")
        }
    }
    // Once the embed page has finished loading, the main frame is locked: a real player never
    // navigates its own top frame again, so any later navigation is an ad hijack — including
    // same-host popunder gateways that the host allowlist alone would let through.
    val navigationLock = remember { object { @Volatile var locked = false } }
    // Compare against the URL we last requested, not WebView.url: pages rewrite their own URL
    // (history.replaceState), which would otherwise re-trigger loadUrl on every recomposition.
    val lastRequestedUrl = remember { object { var value: String? = null } }

    // Called from the WebView's JS bridge thread while the page's accessible <video> plays.
    val progressBridge = remember(mainHandler) {
        object {
            @JavascriptInterface
            fun onTick(positionSec: Double, durationSec: Double) {
                if (positionSec <= 0 || durationSec <= 0) return
                val nextPositionMs = (positionSec * 1000).toLong()
                val nextDurationMs = (durationSec * 1000).toLong()
                mainHandler.post {
                    positionMs = nextPositionMs
                    currentOnProgress?.invoke(nextPositionMs, nextDurationMs)
                }
            }
        }
    }

    val chromeClient = remember {
        object : WebChromeClient() {
            // With multiple windows enabled, window.open lands here instead of navigating the
            // main frame. Returning false swallows the popunder without disturbing playback.
            override fun onCreateWindow(
                view: WebView?,
                isDialog: Boolean,
                isUserGesture: Boolean,
                resultMsg: android.os.Message?,
            ): Boolean {
                DiagnosticsLog.event("EmbedWebView blocked popup isDialog=$isDialog userGesture=$isUserGesture")
                return false
            }

            // Android's custom-view fullscreen detaches the page's <video> into a separate
            // surface, and the rotation we trigger right after tears that surface down —
            // Chromium exits fullscreen and the embed player re-initializes mid-episode.
            // Embed pages are full-bleed players already, so fullscreen is implemented by
            // denying the custom view and expanding the WebView natively instead.
            override fun onShowCustomView(view: View?, callback: CustomViewCallback?) {
                DiagnosticsLog.event("EmbedWebView custom fullscreen requested")
                callback?.onCustomViewHidden()
                currentOnFullscreenChanged(true)
            }

            override fun onHideCustomView() {
                DiagnosticsLog.event("EmbedWebView custom fullscreen hidden")
                currentOnFullscreenChanged(false)
            }
        }
    }

    val webClient = remember(allowedHost, navigationLock) {
        object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                val target = request?.url ?: return true
                if (!request.isForMainFrame) return false
                if (navigationLock.locked) return true // page is up; any top-frame nav is an ad
                val host = target.host.orEmpty().lowercase().removePrefix("www.")
                val allowed = allowedHost != null &&
                    (host == allowedHost || host.endsWith(".$allowedHost"))
                if (!allowed) DiagnosticsLog.event("EmbedWebView blocked main-frame nav targetHost=$host")
                return !allowed // block ad redirects that navigate away from the embed
            }

            override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                loadError = null
                finishedUrl = null
                DiagnosticsLog.event("EmbedWebView page started host=${url.hostOrNone()}")
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                navigationLock.locked = true
                finishedUrl = url
                DiagnosticsLog.event("EmbedWebView page finished host=${url.hostOrNone()} title=${view?.title ?: "none"}")
                view?.evaluateJavascript(PROGRESS_POLL_JS, null)
            }

            override fun onReceivedError(view: WebView?, request: WebResourceRequest?, error: WebResourceError?) {
                if (request?.isForMainFrame == true) {
                    loadError = error?.description?.toString() ?: "The server did not respond"
                    DiagnosticsLog.event(
                        "EmbedWebView main-frame error code=${error?.errorCode} " +
                            "description=${error?.description} host=${request.url?.host ?: "unknown"}",
                    )
                }
            }

            override fun onReceivedHttpError(
                view: WebView?,
                request: WebResourceRequest?,
                errorResponse: WebResourceResponse?,
            ) {
                if (request?.isForMainFrame == true) {
                    loadError = "HTTP ${errorResponse?.statusCode ?: "error"} from the video server"
                    DiagnosticsLog.event(
                        "EmbedWebView main-frame HTTP error status=${errorResponse?.statusCode} " +
                            "reason=${errorResponse?.reasonPhrase} host=${request.url?.host ?: "unknown"}",
                    )
                }
            }

            override fun onRenderProcessGone(view: WebView?, detail: RenderProcessGoneDetail?): Boolean {
                DiagnosticsLog.event(
                    "EmbedWebView render process gone didCrash=${detail?.didCrash()} " +
                        "priority=${detail?.rendererPriorityAtExit()}",
                )
                if (webView === view) webView = null
                return true
            }
        }
    }

    DisposableEffect(lifecycleOwner, webView) {
        val owner = lifecycleOwner
        val web = webView
        if (owner == null || web == null) {
            onDispose {}
        } else {
            val observer = LifecycleEventObserver { _, event ->
                when (event) {
                    Lifecycle.Event.ON_PAUSE,
                    Lifecycle.Event.ON_STOP -> {
                        runCatching { web.evaluateJavascript(PAUSE_VIDEO_JS, null) }
                        web.onPause()
                        web.pauseTimers()
                    }
                    Lifecycle.Event.ON_RESUME,
                    Lifecycle.Event.ON_START -> {
                        web.resumeTimers()
                        web.onResume()
                    }
                    else -> Unit
                }
            }
            owner.lifecycle.addObserver(observer)
            onDispose { owner.lifecycle.removeObserver(observer) }
        }
    }

    LaunchedEffect(
        autoSkipIntroOutro,
        autoplay,
        webView,
        positionMs,
        introStartMs,
        introEndMs,
        outroStartMs,
        outroEndMs,
    ) {
        if (!autoSkipIntroOutro || positionMs <= 0L) return@LaunchedEffect

        if (!introAutoSkipped && isInSkipWindow(positionMs, introStartMs, introEndMs)) {
            introAutoSkipped = true
            seekWebVideo(webView, introEndMs)
            return@LaunchedEffect
        }

        if (
            autoplay &&
            !outroAutoHandled &&
            currentOnNextEpisode != null &&
            isInSkipWindow(positionMs, outroStartMs, outroEndMs)
        ) {
            outroAutoHandled = true
            currentOnNextEpisode?.invoke()
        }
    }

    Box(modifier) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                try {
                    DiagnosticsLog.event("EmbedWebView factory create WebView")
                    DiagnosticsLog.webViewPackage("EmbedWebView factory")
                    WebView(ctx).apply {
                        layoutParams = ViewGroup.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT,
                        )
                        setBackgroundColor(android.graphics.Color.BLACK)
                        isFocusable = true
                        isFocusableInTouchMode = true
                        with(settings) {
                            javaScriptEnabled = true
                            domStorageEnabled = true
                            mediaPlaybackRequiresUserGesture = false
                            loadWithOverviewMode = true
                            useWideViewPort = true
                            mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                            allowFileAccess = false
                            allowContentAccess = false
                            // Route window.open through onCreateWindow (dropped there) instead
                            // of letting popunders replace the player in the main frame.
                            setSupportMultipleWindows(true)
                            javaScriptCanOpenWindowsAutomatically = false
                            userAgentString = userAgentString.replace("; wv", "") // look less like a webview
                        }
                        addJavascriptInterface(progressBridge, "AniliProgress")
                        setOnKeyListener { _, keyCode, event ->
                            if (event.action != KeyEvent.ACTION_DOWN || event.repeatCount != 0) {
                                return@setOnKeyListener false
                            }
                            when {
                                keyCode == KeyEvent.KEYCODE_MEDIA_NEXT && currentHasNextEpisode -> {
                                    currentOnNextEpisode?.invoke()
                                    true
                                }
                                keyCode == KeyEvent.KEYCODE_MEDIA_PREVIOUS && currentHasPreviousEpisode -> {
                                    currentOnPreviousEpisode?.invoke()
                                    true
                                }
                                else -> false
                            }
                        }
                        webViewClient = webClient
                        webChromeClient = chromeClient
                        webView = this
                        DiagnosticsLog.event("EmbedWebView factory complete userAgent=${settings.userAgentString.take(100)}")
                    }
                } catch (e: Throwable) {
                    CrashReporter.logNonFatal("System WebView unavailable; embed player disabled", e)
                    View(ctx).apply { setBackgroundColor(android.graphics.Color.BLACK) }
                }
            },
            update = { view ->
                val web = view as? WebView ?: return@AndroidView
                web.webViewClient = webClient
                val headers = referer?.let { mapOf("Referer" to it) } ?: emptyMap()
                if (lastRequestedUrl.value != url) {
                    lastRequestedUrl.value = url
                    navigationLock.locked = false // allow the new embed's own redirect chain
                    DiagnosticsLog.event("EmbedWebView loadUrl host=${url.hostOrNone()} headers=${headers.keys.joinToString()}")
                    web.loadUrl(url, headers)
                }
            },
            onRelease = { view ->
                val web = view as? WebView ?: return@AndroidView
                if (webView === web) webView = null
                DiagnosticsLog.event("EmbedWebView release url=${web.url ?: "none"} size=${web.width}x${web.height}")
                runCatching { web.evaluateJavascript(PAUSE_VIDEO_JS, null) }
                web.onPause()
                web.pauseTimers()
                web.stopLoading()
                web.removeJavascriptInterface("AniliProgress")
                web.loadUrl("about:blank")
                web.clearHistory()
                web.removeAllViews()
                web.webChromeClient = null
                web.webViewClient = WebViewClient()
                web.destroy()
            },
        )

        loadError?.let { message ->
            Column(
                Modifier
                    .align(Alignment.Center)
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    "This server's page failed to load",
                    color = Color.White,
                    style = MaterialTheme.typography.titleMedium,
                    textAlign = TextAlign.Center,
                )
                Text(
                    "$message — try another server below.",
                    color = Color.White.copy(alpha = 0.7f),
                    style = MaterialTheme.typography.bodySmall,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        }

        Row(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = 12.dp)
                .background(Color.Black.copy(alpha = 0.68f), RoundedCornerShape(4.dp))
                .onPreviewKeyEvent { event ->
                    if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                    when {
                        event.key == Key.MediaNext && currentHasNextEpisode -> {
                            currentOnNextEpisode?.invoke()
                            true
                        }
                        event.key == Key.MediaPrevious && currentHasPreviousEpisode -> {
                            currentOnPreviousEpisode?.invoke()
                            true
                        }
                        else -> false
                    }
                }
                .focusGroup(),
        ) {
            IconButton(
                onClick = { currentOnPreviousEpisode?.invoke() },
                enabled = hasPreviousEpisode && currentOnPreviousEpisode != null,
                modifier = Modifier
                    .focusRequester(previousFocus)
                    .focusHighlight(RoundedCornerShape(4.dp)),
            ) {
                Icon(
                    Icons.Default.SkipPrevious,
                    contentDescription = "Previous episode",
                    tint = Color.White,
                )
            }
            IconButton(
                onClick = { currentOnNextEpisode?.invoke() },
                enabled = hasNextEpisode && currentOnNextEpisode != null,
                modifier = Modifier
                    .focusRequester(nextFocus)
                    .focusHighlight(RoundedCornerShape(4.dp)),
            ) {
                Icon(
                    Icons.Default.SkipNext,
                    contentDescription = "Next episode",
                    tint = Color.White,
                )
            }
        }

        val action: Pair<String, () -> Unit>? = when {
            introEndMs != null && isInSkipWindow(positionMs, introStartMs, introEndMs) ->
                "Skip Intro" to { seekWebVideo(webView, introEndMs) }
            outroStartMs != null &&
                outroEndMs != null &&
                currentOnNextEpisode != null &&
                isInSkipWindow(positionMs, outroStartMs, outroEndMs) ->
                "Next Episode" to { currentOnNextEpisode?.invoke() }
            else -> null
        }
        action?.let { (label, onClick) ->
            WebSkipButton(
                label = label,
                onClick = onClick,
                modifier = Modifier.align(Alignment.BottomStart),
            )
        }
    }
}

@Composable
private fun WebSkipButton(label: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    OutlinedButton(
        onClick = onClick,
        shape = RoundedCornerShape(3.dp),
        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.55f)),
        colors = ButtonDefaults.outlinedButtonColors(
            containerColor = Color.Black.copy(alpha = 0.5f),
            contentColor = Color.White,
        ),
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
        modifier = modifier
            .padding(start = 24.dp, bottom = 24.dp),
    ) {
        Text(
            label.uppercase(),
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Bold,
        )
    }
}

/**
 * Every web player ultimately drives an HTML5 <video>. Poll it (and any same-origin iframe's)
 * every second while playing and report position/duration to the Kotlin bridge. Cross-origin
 * iframes are unreachable by design — those hosts simply won't report progress.
 */
private val PROGRESS_POLL_JS = """
    (function() {
      if (window.__aniliProgressHooked) return;
      window.__aniliProgressHooked = true;
      function findVideo() {
        var v = document.querySelector('video');
        if (v) return v;
        var frames = document.querySelectorAll('iframe');
        for (var i = 0; i < frames.length; i++) {
          try {
            var d = frames[i].contentDocument;
            if (d) {
              var fv = d.querySelector('video');
              if (fv) return fv;
            }
          } catch (e) { /* cross-origin */ }
        }
        return null;
      }
      setInterval(function() {
        try {
          var v = findVideo();
          if (v && !v.paused && isFinite(v.duration) && v.duration > 0 && v.currentTime > 0) {
            AniliProgress.onTick(v.currentTime, v.duration);
          }
        } catch (e) { /* bridge detached */ }
      }, 1000);
    })();
""".trimIndent()

private fun seekWebVideo(webView: WebView?, targetMs: Long?) {
    val targetSec = targetMs?.div(1000.0) ?: return
    runCatching { webView?.evaluateJavascript(SEEK_VIDEO_JS(targetSec), null) }
}

private fun SEEK_VIDEO_JS(targetSec: Double): String = """
    (function() {
      function findVideo() {
        var v = document.querySelector('video');
        if (v) return v;
        var frames = document.querySelectorAll('iframe');
        for (var i = 0; i < frames.length; i++) {
          try {
            var d = frames[i].contentDocument;
            if (d) {
              var fv = d.querySelector('video');
              if (fv) return fv;
            }
          } catch (e) { /* cross-origin */ }
        }
        return null;
      }
      try {
        var v = findVideo();
        if (!v) return false;
        var target = $targetSec;
        v.currentTime = isFinite(v.duration) && v.duration > 0 ? Math.min(target, v.duration) : target;
        if (v.paused) v.play();
        return true;
      } catch (e) {
        return false;
      }
    })();
""".trimIndent()

private val PAUSE_VIDEO_JS = """
    (function() {
      function pauseVideos(root) {
        var videos = root.querySelectorAll('video');
        for (var i = 0; i < videos.length; i++) {
          videos[i].pause();
        }
      }
      try {
        pauseVideos(document);
        var frames = document.querySelectorAll('iframe');
        for (var i = 0; i < frames.length; i++) {
          try {
            var d = frames[i].contentDocument;
            if (d) pauseVideos(d);
          } catch (e) { /* cross-origin */ }
        }
      } catch (e) { /* ignored */ }
    })();
""".trimIndent()

private tailrec fun Context.findLifecycleOwner(): LifecycleOwner? = when (this) {
    is LifecycleOwner -> this
    is ContextWrapper -> baseContext.findLifecycleOwner()
    else -> null
}

private fun isInSkipWindow(positionMs: Long, startMs: Long?, endMs: Long?): Boolean {
    val start = startMs ?: 0L
    val end = endMs ?: return false
    return end > start && positionMs in start until end
}

private fun String?.hostOrNone(): String =
    this?.let { runCatching { Uri.parse(it).host }.getOrNull() } ?: "none"
