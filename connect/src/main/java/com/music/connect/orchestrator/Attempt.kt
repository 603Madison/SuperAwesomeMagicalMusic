package com.music.connect.orchestrator

import kotlinx.coroutines.CancellationException

/**
 * [block]'s result, or why it failed. Unlike `runCatching`, never swallows a coroutine being
 * cancelled (a screen left, say): that goes on up, as it must.
 */
inline fun <T> attempt(block: () -> T): Result<T> = try {
    Result.success(block())
} catch (e: CancellationException) {
    throw e
} catch (e: Exception) {
    Result.failure(e)
}
