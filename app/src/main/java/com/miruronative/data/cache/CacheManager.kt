package com.miruronative.data.cache

import android.content.Context
import coil.annotation.ExperimentalCoilApi
import coil.imageLoader
import com.miruronative.data.AppGraph
import com.miruronative.diagnostics.DiagnosticsLog
import com.miruronative.playback.MediaCache
import java.io.File

/**
 * On-demand maintenance for the app's on-disk caches: the streamed-video segment cache
 * ([MediaCache]), Coil's image cache, and OkHttp's HTTP response cache. All three live under
 * [Context.getCacheDir] and are already system-evictable and self-capping (the video cache
 * auto-trims at 512 MB, images at 256 MB, HTTP at 50 MB). This just lets the user see how much
 * is in use and reclaim it immediately.
 */
object CacheManager {
    /** Cache subdirectories we measure and clear, all relative to [Context.getCacheDir]. */
    private val MANAGED_DIRS = listOf("media", "images", "http")

    /** Total bytes currently held by the managed caches. Does disk IO; call off the main thread. */
    fun usageBytes(context: Context): Long {
        val root = context.applicationContext.cacheDir
        return MANAGED_DIRS.sumOf { directorySize(File(root, it)) }
    }

    /**
     * Empties every managed cache. Best-effort: a failure clearing one cache never blocks the
     * others. Safe to call while playing — the video cache skips any segment locked by an
     * in-flight read. Does disk IO; call off the main thread.
     */
    @OptIn(ExperimentalCoilApi::class)
    fun clear(context: Context) {
        val app = context.applicationContext
        runCatching { MediaCache.clear(app) }
            .onFailure { DiagnosticsLog.throwable("Clear media cache failed", it) }
        runCatching {
            app.imageLoader.diskCache?.clear()
            app.imageLoader.memoryCache?.clear()
        }.onFailure { DiagnosticsLog.throwable("Clear image cache failed", it) }
        runCatching { AppGraph.httpClient.cache?.evictAll() }
            .onFailure { DiagnosticsLog.throwable("Clear HTTP cache failed", it) }
    }

    private fun directorySize(dir: File): Long {
        if (!dir.exists()) return 0L
        return dir.walkBottomUp().filter(File::isFile).sumOf(File::length)
    }
}
