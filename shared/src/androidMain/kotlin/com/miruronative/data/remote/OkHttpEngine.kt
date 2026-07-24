package com.miruronative.data.remote

import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/** Android [HttpEngine] backed by the app's shared OkHttpClient (cache, Cronet, timeouts). */
class OkHttpEngine(private val client: OkHttpClient) : HttpEngine {

    override suspend fun get(url: String, headers: Map<String, String>): HttpResult =
        execute(Request.Builder().url(url), headers)

    override suspend fun post(url: String, body: String, headers: Map<String, String>): HttpResult =
        execute(
            Request.Builder().url(url).post(body.toRequestBody(JSON_MEDIA_TYPE)),
            headers,
        )

    private suspend fun execute(builder: Request.Builder, headers: Map<String, String>): HttpResult =
        withContext(Dispatchers.IO) {
            headers.forEach { (name, value) -> builder.header(name, value) }
            try {
                client.newCall(builder.build()).execute().use { response ->
                    HttpResult(response.code, response.body?.string().orEmpty())
                }
            } catch (e: IOException) {
                throw HttpEngineException(e.message ?: "HTTP transport failure", e)
            }
        }

    private companion object {
        val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }
}
