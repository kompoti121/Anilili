package com.miruronative.data.remote

/**
 * Minimal HTTP surface shared by every KMP target. OkHttp backs it on Android
 * (`OkHttpEngine` in androidMain); an Apple implementation (NSURLSession) plugs in later
 * without touching the clients built on this interface.
 *
 * Transport failures throw [HttpEngineException]. Any received HTTP response — including
 * error statuses — is returned so callers keep their existing status-code handling.
 */
interface HttpEngine {
    suspend fun get(url: String, headers: Map<String, String> = emptyMap()): HttpResult

    suspend fun post(url: String, body: String, headers: Map<String, String> = emptyMap()): HttpResult
}

class HttpResult(val code: Int, val body: String) {
    val isSuccessful: Boolean get() = code in 200..299
}

class HttpEngineException(message: String, cause: Throwable? = null) : Exception(message, cause)
