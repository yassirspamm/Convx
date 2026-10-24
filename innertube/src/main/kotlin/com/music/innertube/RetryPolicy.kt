package com.music.innertube

import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import kotlin.random.Random

internal object RetryPolicy {
    const val MAX_INLINE_WAIT_MS = 15_000L

    fun retryAfterMillis(value: String?, nowMillis: Long = System.currentTimeMillis()): Long? {
        val header = value?.trim() ?: return null
        header.toLongOrNull()?.let {
            return if (it < 0) null else it.coerceAtMost(Long.MAX_VALUE / 1000) * 1000
        }
        return runCatching {
            (ZonedDateTime.parse(header, DateTimeFormatter.RFC_1123_DATE_TIME)
                .toInstant().toEpochMilli() - nowMillis).coerceAtLeast(0)
        }.getOrNull()
    }

    fun jitter(baseMillis: Long): Long = baseMillis / 2 + Random.nextLong(baseMillis / 2 + 1)
}

/** The server's cooldown is still active; callers should wait before requesting again. */
class InnerTubeRateLimitException(val retryAfterMillis: Long) :
    Exception("YouTube rate limit active; retry after $retryAfterMillis ms")
