package de.miraculixx.api.stats

import de.miraculixx.api.stats.ig.Gathering
import de.miraculixx.api.stats.ig.SQLInstant
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory
import java.time.Duration
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.time.Duration.Companion.milliseconds

object Updater {
    val logger = LoggerFactory.getLogger("Stats")
    private val schedulerScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val started = AtomicBoolean(false)

    fun updateAll() {
        if (!started.compareAndSet(false, true)) {
            logger.info(">> Daily updater already started")
            return
        }

        SQLInstant

        schedulerScope.launch {
            val zone = ZoneId.systemDefault()
            logger.info(">> Daily updater started (runs at 01:00 in {})", zone)
            while (isActive) {
                val now = ZonedDateTime.now(zone)
                val nextRun = nextRunAtOne(now)
                val waitMillis = Duration.between(now, nextRun).toMillis().coerceAtLeast(0)
                logger.info(">> Next stats update scheduled for {}", nextRun)
                delay(waitMillis.milliseconds)

                runFullUpdate()
            }
        }
    }

    suspend fun runFullUpdate() {
        try {
            Gathering.refreshInstantGaming()
            logger.info(">> Finished daily stats update")
        } catch (exception: Exception) {
            logger.error(">> Daily stats update failed", exception)
        }
    }

    private fun nextRunAtOne(now: ZonedDateTime): ZonedDateTime {
        var candidate = now.toLocalDate().atTime(LocalTime.of(1, 0)).atZone(now.zone)
        if (!now.isBefore(candidate)) candidate = candidate.plusDays(1)
        return candidate
    }
}