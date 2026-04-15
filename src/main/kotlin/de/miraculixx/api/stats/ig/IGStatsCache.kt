package de.miraculixx.api.stats.ig

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

object IGStatsCache {
    private data class CacheEntry(val value: Any?)

    private val dataVersion = AtomicLong(0)
    private val cache = ConcurrentHashMap<String, CacheEntry>()

    fun invalidateAll() {
        dataVersion.incrementAndGet()
        cache.clear()
    }

    @Suppress("UNCHECKED_CAST")
    suspend fun <T> remember(endpoint: String, key: String, compute: suspend () -> T): T {
        val cacheKey = "${dataVersion.get()}|$endpoint|$key"
        val cached = cache[cacheKey]
        if (cached != null) return cached.value as T

        val computed = compute()
        if (cache.size > 2000) cache.clear()
        cache[cacheKey] = CacheEntry(computed)
        return computed
    }
}

