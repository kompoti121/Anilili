package com.miruronative.data

import android.content.Context
import com.miruronative.data.cache.AppCache
import com.miruronative.data.remote.AniListClient
import com.miruronative.data.remote.AniSkipClient
import com.miruronative.data.remote.AnivexaClient
import com.miruronative.data.remote.JikanClient
import com.miruronative.data.remote.KonohaClient
import com.miruronative.data.remote.MalClient
import com.miruronative.data.remote.OkHttpEngine
import com.miruronative.data.remote.PipeClient
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Cache
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Tiny manual DI container. Built once from [MiruroApp.onCreate] and read by ViewModels.
 * Avoids pulling in Hilt for a project this size.
 */
object AppGraph {
    lateinit var repository: MiruroRepository
        private set
    lateinit var httpClient: OkHttpClient
        private set

    /** TV boxes are memory/CPU-starved; non-UI layers use this to throttle background work. */
    var isTv: Boolean = false
        private set

    fun init(context: Context) {
        if (::repository.isInitialized) return

        isTv = (context.getSystemService(Context.UI_MODE_SERVICE) as? android.app.UiModeManager)
            ?.currentModeType == android.content.res.Configuration.UI_MODE_TYPE_TELEVISION

        val json = Json {
            ignoreUnknownKeys = true
            isLenient = true
            coerceInputValues = true
            explicitNulls = false
        }

        httpClient = OkHttpClient.Builder()
            .cache(Cache(File(context.applicationContext.cacheDir, "http"), 50L * 1024 * 1024))
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .callTimeout(45, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()

        val aniList = AniListClient(httpClient, json)
        // KMP-moved clients (in :shared) take the platform-neutral engine instead of OkHttp.
        val httpEngine = OkHttpEngine(httpClient)
        val cache = AppCache(context, json)
        repository = MiruroRepository(
            aniList = aniList,
            pipe = PipeClient(json),
            anivexa = AnivexaClient(context, httpClient, json, aniList, cache),
            jikan = JikanClient(httpEngine, json),
            aniSkip = AniSkipClient(httpEngine, json),
            mal = MalClient(httpClient, json),
            konoha = KonohaClient(context, httpClient, json, cache),
            cache = cache,
        )
    }
}
