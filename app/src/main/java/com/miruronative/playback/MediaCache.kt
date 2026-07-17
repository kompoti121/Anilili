package com.miruronative.playback

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import java.io.File

/** Singleton, system-evictable disk cache for manifests and already-streamed video segments. */
@OptIn(UnstableApi::class)
object MediaCache {
    @Volatile private var instance: SimpleCache? = null

    fun get(context: Context): SimpleCache = instance ?: synchronized(this) {
        instance ?: SimpleCache(
            File(context.applicationContext.cacheDir, "media"),
            LeastRecentlyUsedCacheEvictor(MAX_BYTES),
            StandaloneDatabaseProvider(context.applicationContext),
        ).also { instance = it }
    }

    /**
     * Removes every cached segment, freeing the disk space under [cacheDir]/media. Keeps the
     * singleton valid so an active player keeps working against the now-empty cache. A segment
     * currently locked by an in-flight read is left in place. Does disk IO; call off the main thread.
     */
    fun clear(context: Context) {
        val cache = get(context)
        for (key in cache.keys.toList()) {
            try {
                cache.removeResource(key)
            } catch (_: Exception) {
                // Locked or already-gone resource; skip it and keep clearing the rest.
            }
        }
    }

    private const val MAX_BYTES = 512L * 1024 * 1024
}
