package com.miruronative.data.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import com.miruronative.BuildConfig
import com.miruronative.data.AppGraph
import com.miruronative.diagnostics.DiagnosticsLog
import java.io.File
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import okhttp3.Request

/**
 * In-app updates for a sideloaded install: polls the rolling GitHub release
 * (`kompoti121/anilili`, tag `APK-release`), downloads the APK asset, and hands it
 * to the system package installer. Android rejects the install unless the new APK
 * is signed with the same key, so a hijacked release can't replace the app.
 */
object UpdateManager {
    data class UpdateInfo(
        val version: String,
        val changelog: String,
        val apkUrl: String,
        val sizeBytes: Long,
    )

    sealed interface State {
        data object Idle : State
        data object Checking : State
        /** Manual check found nothing newer. */
        data class UpToDate(val latestPublishedVersion: String) : State
        data class Available(val update: UpdateInfo) : State
        data class Downloading(val update: UpdateInfo, val progress: Float) : State
        data class ReadyToInstall(val update: UpdateInfo, val file: File) : State
        data class Failed(val message: String) : State
    }

    private const val RELEASES_LATEST = "https://api.github.com/repos/kompoti121/anilili/releases/latest"
    private const val PREFS = "anilili_updates"
    private const val KEY_LAST_CHECK = "last_check_ms"
    private val CHECK_INTERVAL_MS = TimeUnit.HOURS.toMillis(12)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    private val _state = MutableStateFlow<State>(State.Idle)
    val state = _state.asStateFlow()

    val currentVersion: String get() = BuildConfig.VERSION_NAME

    /** Throttled startup check; only surfaces a prompt when an update exists. */
    fun autoCheckIfDue(context: Context) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (System.currentTimeMillis() - prefs.getLong(KEY_LAST_CHECK, 0L) < CHECK_INTERVAL_MS) return
        check(context, manual = false)
    }

    fun check(context: Context, manual: Boolean) {
        val current = _state.value
        if (current is State.Checking || current is State.Downloading) {
            DiagnosticsLog.event(
                "Update check ignored busyState=${current.javaClass.simpleName} manual=$manual",
            )
            return
        }
        DiagnosticsLog.event("Update check requested manual=$manual installed=$currentVersion")
        _state.value = State.Checking
        val appContext = context.applicationContext
        scope.launch {
            runCatching { fetchLatest() }
                .onSuccess { info ->
                    appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                        .edit().putLong(KEY_LAST_CHECK, System.currentTimeMillis()).apply()
                    val comparison = compareAppVersions(info.version, currentVersion)
                    _state.value = when {
                        comparison > 0 -> State.Available(info)
                        manual -> State.UpToDate(info.version)
                        else -> State.Idle
                    }
                    DiagnosticsLog.event(
                        "Update check complete manual=$manual installed=$currentVersion " +
                            "published=${info.version} result=${when {
                                comparison > 0 -> "available"
                                comparison == 0 -> "up-to-date"
                                else -> "installed-newer-than-published"
                            }}",
                    )
                }
                .onFailure { error ->
                    DiagnosticsLog.throwable(
                        "Update check failed manual=$manual installed=$currentVersion",
                        error,
                    )
                    _state.value = if (manual) State.Failed(error.message ?: "Update check failed") else State.Idle
                }
        }
    }

    fun download(context: Context) {
        val info = (_state.value as? State.Available)?.update
        if (info == null) {
            DiagnosticsLog.event("Update download ignored state=${_state.value.javaClass.simpleName}")
            return
        }
        DiagnosticsLog.event(
            "Update download requested version=${info.version} sizeBytes=${info.sizeBytes} " +
                "host=${runCatching { Uri.parse(info.apkUrl).host }.getOrNull() ?: "unknown"}",
        )
        _state.value = State.Downloading(info, 0f)
        val appContext = context.applicationContext
        scope.launch {
            runCatching { downloadApk(appContext, info) }
                .onSuccess { file ->
                    DiagnosticsLog.event(
                        "Update download complete version=${info.version} bytes=${file.length()}",
                    )
                    _state.value = State.ReadyToInstall(info, file)
                    install(appContext)
                }
                .onFailure { error ->
                    DiagnosticsLog.throwable("Update download failed version=${info.version}", error)
                    _state.value = State.Failed(error.message ?: "Download failed")
                }
        }
    }

    /**
     * Launches the system installer for the downloaded APK. If the app can't request
     * installs yet, opens the "install unknown apps" settings screen instead; the
     * ReadyToInstall state is kept so the user can retry after granting.
     */
    fun install(context: Context) {
        val ready = _state.value as? State.ReadyToInstall
        if (ready == null) {
            DiagnosticsLog.event("Update install ignored state=${_state.value.javaClass.simpleName}")
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !context.packageManager.canRequestPackageInstalls()) {
            DiagnosticsLog.event(
                "Update install permission required version=${ready.update.version}; opening Android settings",
            )
            // Some TV builds don't resolve the per-app screen; fall back to the general list.
            val perApp = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            val generic = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            runCatching { context.startActivity(perApp) }
                .recoverCatching { context.startActivity(generic) }
                .onSuccess {
                    DiagnosticsLog.event("Update install permission settings launched")
                }
                .onFailure {
                    DiagnosticsLog.throwable("Update install permission settings failed", it)
                    _state.value = State.Failed(
                        "Android blocked the install. Allow \"install unknown apps\" for Anilili in system settings, then try again.",
                    )
                }
            return
        }
        runCatching {
            val uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                ready.file,
            )
            context.startActivity(
                Intent(Intent.ACTION_VIEW)
                    .setDataAndType(uri, "application/vnd.android.package-archive")
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }.onSuccess {
            DiagnosticsLog.event(
                "Update installer launched version=${ready.update.version} bytes=${ready.file.length()}",
            )
        }.onFailure { error ->
            DiagnosticsLog.throwable("Update installer launch failed version=${ready.update.version}", error)
            _state.value = State.Failed(error.message ?: "Couldn't launch the installer")
        }
    }

    fun dismiss() {
        if (_state.value !is State.Downloading) _state.value = State.Idle
    }

    private fun fetchLatest(): UpdateInfo {
        val request = Request.Builder()
            .url(RELEASES_LATEST)
            .header("Accept", "application/vnd.github+json")
            .build()
        AppGraph.httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) error("Update check failed (HTTP ${response.code})")
            val release = json.parseToJsonElement(response.body?.string().orEmpty()).jsonObject
            val name = release["name"]?.jsonPrimitive?.content.orEmpty()
            val tag = release["tag_name"]?.jsonPrimitive?.content.orEmpty()
            val body = release["body"]?.jsonPrimitive?.content.orEmpty()
            val version = parseVersion(name)
                ?: parseVersion(tag)
                ?: parseVersion(body)
                ?: error("The published release doesn't contain a version number")
            val apks = release["assets"]?.jsonArray
                ?.map { it.jsonObject }
                ?.filter { it["name"]?.jsonPrimitive?.content.orEmpty().endsWith(".apk", ignoreCase = true) }
                .orEmpty()
            val preferredName = preferredReleaseApkName(
                apks.map { it["name"]?.jsonPrimitive?.content.orEmpty() },
                Build.SUPPORTED_ABIS.toList(),
            ) ?: error("The published release doesn't contain a compatible APK")
            val apk = apks.firstOrNull {
                it["name"]?.jsonPrimitive?.content.orEmpty() == preferredName
            } ?: error("The published APK asset could not be selected")
            val apkUrl = apk["browser_download_url"]?.jsonPrimitive?.content
                ?: error("The published APK is missing its download URL")
            val sizeBytes = apk["size"]?.jsonPrimitive?.content?.toLongOrNull() ?: -1L
            DiagnosticsLog.event(
                "Update release parsed version=$version asset=$preferredName sizeBytes=$sizeBytes",
            )
            return UpdateInfo(
                version = version,
                changelog = body,
                apkUrl = apkUrl,
                sizeBytes = sizeBytes,
            )
        }
    }

    private fun downloadApk(context: Context, info: UpdateInfo): File {
        val dir = File(context.cacheDir, "updates").apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() }
        val file = File(dir, "Anilili-${info.version}.apk")
        // No call timeout: the release APK takes longer than the API client's 45s cap on slow links.
        val client = AppGraph.httpClient.newBuilder()
            .cache(null)
            .callTimeout(0, TimeUnit.SECONDS)
            .build()
        client.newCall(Request.Builder().url(info.apkUrl).build()).execute().use { response ->
            if (!response.isSuccessful) error("Download failed (HTTP ${response.code})")
            val responseBody = response.body ?: error("Download failed (empty response)")
            val total = responseBody.contentLength().takeIf { it > 0 } ?: info.sizeBytes
            responseBody.byteStream().use { input ->
                file.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var written = 0L
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                        written += read
                        if (total > 0) {
                            _state.value = State.Downloading(info, (written.toFloat() / total).coerceIn(0f, 1f))
                        }
                    }
                    if (total > 0 && written < total) error("Download incomplete ($written of $total bytes)")
                }
            }
        }
        return file
    }

    private fun parseVersion(text: String): String? =
        Regex("""v?(\d+(?:\.\d+)+)""").find(text)?.groupValues?.get(1)

}

internal fun compareAppVersions(remote: String, installed: String): Int {
    fun parts(version: String): List<Int> = version
        .removePrefix("v")
        .split('.')
        .map { part -> part.takeWhile(Char::isDigit).toIntOrNull() ?: 0 }

    val remoteParts = parts(remote)
    val installedParts = parts(installed)
    for (i in 0 until maxOf(remoteParts.size, installedParts.size)) {
        val remotePart = remoteParts.getOrElse(i) { 0 }
        val installedPart = installedParts.getOrElse(i) { 0 }
        if (remotePart != installedPart) return remotePart.compareTo(installedPart)
    }
    return 0
}

/** Chooses the smallest compatible split while retaining the universal APK as a safe fallback. */
internal fun preferredReleaseApkName(names: List<String>, supportedAbis: List<String>): String? {
    val apks = names.filter { it.endsWith(".apk", ignoreCase = true) }
    if (apks.isEmpty()) return null
    val preferredAbi = when {
        supportedAbis.any { it.equals("arm64-v8a", ignoreCase = true) } -> "arm64-v8a"
        supportedAbis.any { it.equals("armeabi-v7a", ignoreCase = true) } -> "armeabi-v7a"
        else -> null
    }
    return preferredAbi?.let { abi ->
        apks.firstOrNull { it.contains(abi, ignoreCase = true) }
    } ?: apks.firstOrNull { it.equals("Anilili.apk", ignoreCase = true) }
        ?: apks.firstOrNull { it.contains("universal", ignoreCase = true) }
        ?: apks.first()
}
