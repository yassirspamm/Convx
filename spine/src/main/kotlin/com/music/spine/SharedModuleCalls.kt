package com.music.spine

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async

/** Shares duplicate requests; cancelling a waiter preserves work needed by prefetch/downloads. */
internal class SharedModuleCalls<T>(private val ttlMs: Long) {
    private class Entry<T> {
        lateinit var work: Deferred<Result<T>>
        var completedAt: Long = 0
    }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val entries = LinkedHashMap<String, Entry<T>>(16, 0.75f, true)

    suspend fun get(key: String, produce: suspend () -> Result<T>): Result<T> {
        val entry = synchronized(entries) {
            val now = now()
            entries[key]?.takeIf { it.work.isActive || (it.completedAt > 0 && now - it.completedAt < ttlMs) }
                ?: run {
                    entries.entries.removeAll { !it.value.work.isActive && now - it.value.completedAt >= ttlMs }
                    if (entries.size >= 128) {
                        entries.entries.firstOrNull { !it.value.work.isActive }?.let { entries.remove(it.key) }
                    }
                    val pending = Entry<T>()
                    pending.work = scope.async(start = CoroutineStart.LAZY) {
                        try {
                            val result = produce()
                            synchronized(entries) {
                                pending.completedAt = now()
                                if (entries[key] === pending && (result.isFailure || ttlMs == 0L)) {
                                    entries.remove(key)
                                }
                            }
                            result
                        } catch (e: Throwable) {
                            synchronized(entries) { if (entries[key] === pending) entries.remove(key) }
                            throw e
                        }
                    }
                    entries[key] = pending
                    pending.work.start()
                    pending
                }
        }
        return entry.work.await()
    }

    fun remove(key: String) {
        val work = synchronized(entries) { entries.remove(key)?.work }
        work?.cancel()
    }

    fun clear() {
        val pending = synchronized(entries) {
            entries.values.map { it.work }.also { entries.clear() }
        }
        pending.forEach { it.cancel() }
    }
    private fun now() = System.nanoTime() / 1_000_000
}
