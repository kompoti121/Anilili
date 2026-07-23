package com.miruronative.ui.watch

internal const val TV_SEEK_STEP_MS = 10_000L
internal const val TV_SEEK_COALESCE_MS = 180L
internal const val SEEK_ERROR_RECOVERY_WINDOW_MS = 8_000L

/**
 * Repeated remote presses build on the last pending target instead of the player's stale position.
 * The duration is optional because some streams do not expose it until their first segment loads.
 */
internal fun tvSeekTargetMs(
    currentPositionMs: Long,
    durationMs: Long,
    offsetMs: Long,
): Long {
    val current = currentPositionMs.coerceAtLeast(0L)
    val unboundedTarget = when {
        offsetMs > 0L && current > Long.MAX_VALUE - offsetMs -> Long.MAX_VALUE
        offsetMs < 0L && current < -offsetMs -> 0L
        else -> current + offsetMs
    }
    val maximum = durationMs.takeIf { it > 0L } ?: Long.MAX_VALUE
    return unboundedTarget.coerceIn(0L, maximum)
}

/**
 * Media3 reserves 2xxx error codes for I/O failures. A single one directly after a user seek is
 * commonly a cancelled/stale segment request, so retry the same media item before changing server.
 */
internal fun shouldRecoverSeekError(
    errorCode: Int,
    elapsedSinceSeekMs: Long,
    recoveryAlreadyAttempted: Boolean,
): Boolean = !recoveryAlreadyAttempted &&
    elapsedSinceSeekMs in 0L..SEEK_ERROR_RECOVERY_WINDOW_MS &&
    errorCode in 2_000..2_999
