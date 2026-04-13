package de.miraculixx.api.stats.ig

import de.miraculixx.api.data.databaseCredentials
import de.miraculixx.api.stats.DatabaseStructure
import de.miraculixx.api.stats.Updater
import io.ktor.utils.io.InternalAPI
import java.sql.Timestamp
import kotlin.math.abs

object SQLInstant : DatabaseStructure(Updater.logger, databaseCredentials.instant_gaming) {

    @OptIn(InternalAPI::class)
    suspend fun saveSnapshot(games: List<IGHitResponse>) {
        if (games.isEmpty()) {
            logger.warn("IG snapshot skipped: no games available")
            return
        }

        val snapshotTimestamp = Timestamp(System.currentTimeMillis())
        val nonZeroDiscounts = games.map { it.discount }.filter { it > 0 }
        val nonZeroAbsoluteDiscounts = nonZeroDiscounts.map { abs(it) }

        // Calculate all stats
        val gameCount = games.size
        val avgDiscount = games.map { it.discount.toDouble() }.average().takeIf { !it.isNaN() } ?: 0.0
        val minDiscount = nonZeroDiscounts.minOrNull() ?: 0
        val maxDiscount = nonZeroDiscounts.maxOrNull() ?: 0
        val avgAbsDiscount = games.map { abs(it.discount).toDouble() }.average().takeIf { !it.isNaN() } ?: 0.0
        val minAbsDiscount = nonZeroAbsoluteDiscounts.minOrNull() ?: 0
        val maxAbsDiscount = nonZeroAbsoluteDiscounts.maxOrNull() ?: 0

        buildStatement("START TRANSACTION").use { it.execute() }
        try {
            buildStatement(
                """
                    INSERT INTO _snapshot_item (
                        snapshot_ts, prod_id,
                        name, platform, type, seo_name,
                        is_dlc, preorder, has_stock,
                        retail, price, discount
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    ON DUPLICATE KEY UPDATE
                        name = VALUES(name),
                        platform = VALUES(platform),
                        type = VALUES(type),
                        seo_name = VALUES(seo_name),
                        is_dlc = VALUES(is_dlc),
                        preorder = VALUES(preorder),
                        has_stock = VALUES(has_stock),
                        retail = VALUES(retail),
                        price = VALUES(price),
                        discount = VALUES(discount)
                """.trimIndent()
            ).use { statement ->
                games.forEach { game ->
                    statement.setTimestamp(1, snapshotTimestamp)
                    statement.setInt(2, game.prod_id)
                    statement.setString(3, game.name)
                    statement.setString(4, game.platform)
                    statement.setString(5, game.type)
                    statement.setString(6, game.seo_name)
                    statement.setBoolean(7, game.is_dlc)
                    statement.setBoolean(8, game.preorder)
                    statement.setBoolean(9, game.has_stock)
                    statement.setDouble(10, game.retail.toDoubleOrNull() ?: game.price)
                    statement.setDouble(11, game.price)
                    statement.setInt(12, game.discount)
                    statement.addBatch()
                }
                statement.executeBatch()
            }

            buildStatement(
                """
                    INSERT INTO _snapshot_stats (
                        snapshot_ts,
                        game_count,
                        avg_discount, min_discount, max_discount,
                        avg_abs_discount, min_abs_discount, max_abs_discount
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                    ON DUPLICATE KEY UPDATE
                        game_count = VALUES(game_count),
                        avg_discount = VALUES(avg_discount),
                        min_discount = VALUES(min_discount),
                        max_discount = VALUES(max_discount),
                        avg_abs_discount = VALUES(avg_abs_discount),
                        min_abs_discount = VALUES(min_abs_discount),
                        max_abs_discount = VALUES(max_abs_discount)
                """.trimIndent()
            ).use { statement ->
                statement.setTimestamp(1, snapshotTimestamp)
                statement.setInt(2, gameCount)
                statement.setDouble(3, avgDiscount)
                statement.setInt(4, minDiscount)
                statement.setInt(5, maxDiscount)
                statement.setDouble(6, avgAbsDiscount)
                statement.setInt(7, minAbsDiscount)
                statement.setInt(8, maxAbsDiscount)
                statement.executeUpdate()
            }

            buildStatement("COMMIT").use { it.execute() }
            logger.info("IG snapshot persisted for {} games at {}", gameCount, snapshotTimestamp)
        } catch (exception: Exception) {
            runCatching { buildStatement("ROLLBACK").use { it.execute() } }
            logger.error("IG snapshot save failed", exception)
        }
    }
}