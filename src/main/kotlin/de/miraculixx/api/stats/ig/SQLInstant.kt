package de.miraculixx.api.stats.ig

import de.miraculixx.api.data.databaseCredentials
import de.miraculixx.api.json
import de.miraculixx.api.stats.DatabaseStructure
import de.miraculixx.api.stats.Updater
import io.ktor.utils.io.InternalAPI
import kotlinx.serialization.encodeToString
import java.net.URI
import java.sql.Timestamp
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.concurrent.atomic.AtomicBoolean

object SQLInstant : DatabaseStructure(Updater.logger, databaseCredentials.instant_gaming) {
    private val indexesEnsured = AtomicBoolean(false)

    @OptIn(InternalAPI::class)
    suspend fun ensureReadIndexes() {
        if (!indexesEnsured.compareAndSet(false, true)) return

        val indexes = listOf(
            "idx_snapshot_item_snapshot_platform" to "CREATE INDEX idx_snapshot_item_snapshot_platform ON snapshot_item(snapshot_ts, platform)",
            "idx_snapshot_item_snapshot_flags" to "CREATE INDEX idx_snapshot_item_snapshot_flags ON snapshot_item(snapshot_ts, is_dlc, preorder, is_prepaid, is_sub)",
            "idx_snapshot_item_snapshot_name" to "CREATE INDEX idx_snapshot_item_snapshot_name ON snapshot_item(snapshot_ts, name)"
        )

        indexes.forEach { (indexName, sql) ->
            runCatching {
                if (hasIndex("snapshot_item", indexName)) return@runCatching
                buildStatement(sql).use { it.execute() }
            }.onFailure { ex ->
                logger.warn("IG index bootstrap statement failed for {}", indexName, ex)
            }
        }
    }

    @OptIn(InternalAPI::class)
    private suspend fun hasIndex(tableName: String, indexName: String): Boolean {
        val sql =
            """
                SELECT 1
                FROM information_schema.statistics
                WHERE table_schema = DATABASE()
                  AND table_name = ?
                  AND index_name = ?
                LIMIT 1
            """.trimIndent()

        return buildStatement(sql).use { statement ->
            statement.setString(1, tableName)
            statement.setString(2, indexName)
            statement.executeQuery().use { it.next() }
        }
    }

    @OptIn(InternalAPI::class)
    suspend fun fetchMeta(): MetaResponse? {
        ensureReadIndexes()

        val platforms = mutableListOf<String>()
        buildStatement("SELECT DISTINCT platform FROM snapshot_item ORDER BY platform").use { statement ->
            statement.executeQuery().use { rs ->
                while (rs.next()) platforms += rs.getString("platform")
            }
        }

        var earliest: Timestamp? = null
        var latest: Timestamp? = null
        buildStatement("SELECT MIN(snapshot_ts) AS earliest, MAX(snapshot_ts) AS latest FROM snapshot_stats").use { statement ->
            statement.executeQuery().use { rs ->
                if (rs.next()) {
                    earliest = rs.getTimestamp("earliest")
                    latest = rs.getTimestamp("latest")
                }
            }
        }

        val earliestTs = earliest ?: return null
        val latestTs = latest ?: return null
        return MetaResponse(
            platforms = platforms,
            earliestSnapshotTs = earliestTs.toInstant().toString(),
            latestSnapshotTs = latestTs.toInstant().toString(),
            supportedIntervals = listOf(Interval.Day, Interval.Week, Interval.Month)
        )
    }

    @OptIn(InternalAPI::class)
    suspend fun fetchOverview(request: OverviewRequest): List<OverviewPoint> {
        ensureReadIndexes()

        val bucketExpression = when (request.interval) {
            Interval.Day -> "DATE(snapshot_ts)"
            Interval.Week -> "YEARWEEK(snapshot_ts, 1)"
            Interval.Month -> "DATE_FORMAT(snapshot_ts, '%Y-%m')"
        }

        val where = mutableListOf<String>()
        val args = mutableListOf<Any>()

        val zone = ZoneId.systemDefault()
        val fromTs = Timestamp.from(request.from.atStartOfDay(zone).toInstant())
        val toExclusiveTs = Timestamp.from(request.to.plusDays(1).atStartOfDay(zone).toInstant())
        where += "snapshot_ts >= ?"
        args += fromTs
        where += "snapshot_ts < ?"
        args += toExclusiveTs

        if (!request.platform.equals("all", ignoreCase = true)) {
            where += "platform = ?"
            args += request.platform
        }
        if (!request.includeDlc) where += "is_dlc = false"
        if (!request.includePreorder) where += "preorder = false"

        val sql =
            """
                SELECT
                    MAX(snapshot_ts) AS snapshot_ts,
                    COUNT(*) AS game_count,
                    COALESCE(AVG(discount), 0) AS avg_discount,
                    COALESCE(MIN(NULLIF(discount, 0)), 0) AS min_discount,
                    COALESCE(MAX(discount), 0) AS max_discount,
                    COALESCE(AVG(abs_discount), 0) AS avg_abs_discount,
                    COALESCE(MIN(NULLIF(abs_discount, 0)), 0) AS min_abs_discount,
                    COALESCE(MAX(abs_discount), 0) AS max_abs_discount
                FROM snapshot_item
                WHERE ${where.joinToString(" AND ")}
                GROUP BY $bucketExpression
                ORDER BY snapshot_ts
            """.trimIndent()

        val points = mutableListOf<OverviewPoint>()
        buildStatement(sql).use { statement ->
            args.forEachIndexed { index, value -> statement.setAny(index + 1, value) }
            statement.executeQuery().use { rs ->
                while (rs.next()) {
                    points += OverviewPoint(
                        snapshotTs = rs.getTimestamp("snapshot_ts").toInstant().toString(),
                        gameCount = rs.getInt("game_count"),
                        avgDiscount = rs.getDouble("avg_discount"),
                        minDiscount = rs.getInt("min_discount"),
                        maxDiscount = rs.getInt("max_discount"),
                        avgAbsDiscount = rs.getDouble("avg_abs_discount"),
                        minAbsDiscount = rs.getDouble("min_abs_discount"),
                        maxAbsDiscount = rs.getDouble("max_abs_discount")
                    )
                }
            }
        }
        return points
    }

    @OptIn(InternalAPI::class)
    suspend fun resolveSnapshotTimestamp(raw: String): Timestamp? {
        ensureReadIndexes()

        val date = runCatching { LocalDate.parse(raw) }.getOrNull()
        if (date != null) {
            return latestSnapshotForDate(date)
        }

        val localDateTime = runCatching { LocalDateTime.parse(raw) }.getOrNull()
        if (localDateTime != null) {
            return exactSnapshot(Timestamp.valueOf(localDateTime))
        }

        val instant = runCatching { Instant.parse(raw) }.getOrNull() ?: return null
        val exact = exactSnapshot(Timestamp.from(instant))
        if (exact != null) return exact

        val fallbackDate = instant.atZone(ZoneId.systemDefault()).toLocalDate()
        return latestSnapshotForDate(fallbackDate)
    }

    @OptIn(InternalAPI::class)
    suspend fun fetchGames(request: GamesRequest): Pair<Int, List<GameRow>> {
        ensureReadIndexes()

        val where = mutableListOf("snapshot_ts = ?")
        val args = mutableListOf<Any>(request.snapshotTs)

        if (!request.platform.equals("all", ignoreCase = true)) {
            where += "platform = ?"
            args += request.platform
        }
        if (!request.search.isNullOrBlank()) {
            where += "name LIKE ?"
            args += "%${request.search}%"
        }
        when (request.prepaid) {
            true -> where += "(is_prepaid = true OR is_sub = true)"
            false -> where += "(is_prepaid = false AND is_sub = false)"
            null -> {}
        }
        request.isDlc?.let {
            where += "is_dlc = ?"
            args += it
        }
        request.preorder?.let {
            where += "preorder = ?"
            args += it
        }

        val sortColumn = when (request.sortBy) {
            GameSortField.Discount -> "discount"
            GameSortField.AbsDiscount -> "abs_discount"
            GameSortField.Price -> "price"
            GameSortField.Retail -> "retail"
            GameSortField.Name -> "name"
        }
        val sortDirection = when (request.sortDir) {
            SortDirection.Asc -> "ASC"
            SortDirection.Desc -> "DESC"
        }
        val offset = (request.page - 1) * request.pageSize

        val countSql = "SELECT COUNT(*) AS total FROM snapshot_item WHERE ${where.joinToString(" AND ")}"
        val total = buildStatement(countSql).use { statement ->
            args.forEachIndexed { index, value -> statement.setAny(index + 1, value) }
            statement.executeQuery().use { rs ->
                if (rs.next()) rs.getInt("total") else 0
            }
        }

        val rowsSql =
            """
                SELECT
                    snapshot_ts,
                    prod_id,
                    name,
                    platform,
                    seo_name,
                    is_sub,
                    is_prepaid,
                    is_dlc,
                    preorder,
                    has_stock,
                    retail,
                    price,
                    discount,
                    abs_discount
                FROM snapshot_item
                WHERE ${where.joinToString(" AND ")}
                ORDER BY $sortColumn $sortDirection, prod_id ASC
                LIMIT ? OFFSET ?
            """.trimIndent()

        val rows = mutableListOf<GameRow>()
        buildStatement(rowsSql).use { statement ->
            var argIndex = 1
            args.forEach { value ->
                statement.setAny(argIndex, value)
                argIndex++
            }
            statement.setInt(argIndex++, request.pageSize)
            statement.setInt(argIndex, offset)

            statement.executeQuery().use { rs ->
                while (rs.next()) {
                    rows += GameRow(
                        snapshotTs = rs.getTimestamp("snapshot_ts").toInstant().toString(),
                        prodId = rs.getInt("prod_id"),
                        name = rs.getString("name"),
                        platform = rs.getString("platform"),
                        seoName = rs.getString("seo_name"),
                        isSub = rs.getBoolean("is_sub"),
                        isPrepaid = rs.getBoolean("is_prepaid"),
                        isDlc = rs.getBoolean("is_dlc"),
                        preorder = rs.getBoolean("preorder"),
                        hasStock = rs.getBoolean("has_stock"),
                        retail = rs.getDouble("retail"),
                        price = rs.getDouble("price"),
                        discount = rs.getInt("discount"),
                        absDiscount = rs.getDouble("abs_discount")
                    )
                }
            }
        }

        return total to rows
    }

    @OptIn(InternalAPI::class)
    private suspend fun exactSnapshot(timestamp: Timestamp): Timestamp? {
        val sql = "SELECT snapshot_ts FROM snapshot_stats WHERE snapshot_ts = ? LIMIT 1"
        return buildStatement(sql).use { statement ->
            statement.setTimestamp(1, timestamp)
            statement.executeQuery().use { rs -> if (rs.next()) rs.getTimestamp("snapshot_ts") else null }
        }
    }

    @OptIn(InternalAPI::class)
    private suspend fun latestSnapshotForDate(date: LocalDate): Timestamp? {
        val zone = ZoneId.systemDefault()
        val start = Timestamp.from(date.atStartOfDay(zone).toInstant())
        val end = Timestamp.from(date.plusDays(1).atStartOfDay(zone).toInstant())
        val sql =
            """
                SELECT snapshot_ts
                FROM snapshot_stats
                WHERE snapshot_ts >= ? AND snapshot_ts < ?
                ORDER BY snapshot_ts DESC
                LIMIT 1
            """.trimIndent()
        return buildStatement(sql).use { statement ->
            statement.setTimestamp(1, start)
            statement.setTimestamp(2, end)
            statement.executeQuery().use { rs -> if (rs.next()) rs.getTimestamp("snapshot_ts") else null }
        }
    }

    @OptIn(InternalAPI::class)
    suspend fun fetchGameHistory(request: GameHistoryRequest): GameHistoryData? {
        ensureReadIndexes()

        val where = mutableListOf("prod_id = ?")
        val args = mutableListOf<Any>(request.prodId)

        val zone = ZoneId.systemDefault()
        request.from?.let {
            where += "snapshot_ts >= ?"
            args += Timestamp.from(it.atStartOfDay(zone).toInstant())
        }
        request.to?.let {
            where += "snapshot_ts < ?"
            args += Timestamp.from(it.plusDays(1).atStartOfDay(zone).toInstant())
        }

        val sql = """
            SELECT snapshot_ts, name, seo_name, retail, price, discount, abs_discount
            FROM snapshot_item
            WHERE ${where.joinToString(" AND ")}
            ORDER BY snapshot_ts ASC
        """.trimIndent()

        var name: String? = null
        var seoName: String? = null
        val points = mutableListOf<GameHistoryPoint>()
        buildStatement(sql).use { statement ->
            args.forEachIndexed { index, value -> statement.setAny(index + 1, value) }
            statement.executeQuery().use { rs ->
                while (rs.next()) {
                    if (name == null) {
                        name = rs.getString("name")
                        seoName = rs.getString("seo_name")
                    }
                    points += GameHistoryPoint(
                        snapshotTs = rs.getTimestamp("snapshot_ts").toInstant().toString(),
                        price = rs.getDouble("price"),
                        retail = rs.getDouble("retail"),
                        discount = rs.getInt("discount"),
                        absDiscount = rs.getDouble("abs_discount")
                    )
                }
            }
        }
        return if (points.isEmpty()) null
        else GameHistoryData(name = name!!, seoName = seoName!!, points = points)
    }

    private fun java.sql.PreparedStatement.setAny(index: Int, value: Any) {
        when (value) {
            is Timestamp -> setTimestamp(index, value)
            is String -> setString(index, value)
            is Int -> setInt(index, value)
            is Boolean -> setBoolean(index, value)
            is Double -> setDouble(index, value)
            is Long -> setLong(index, value)
            else -> setObject(index, value)
        }
    }


    @OptIn(InternalAPI::class)
    suspend fun saveSnapshot(games: List<IGHitResponse>) {
        if (games.isEmpty()) {
            logger.warn("IG snapshot skipped: no games available")
            return
        }

        val snapshotTimestamp = Timestamp(System.currentTimeMillis())
        val nonZeroDiscounts = games.map { it.discount }.filter { it > 0 }
        val nonZeroAbsoluteDiscounts = games.map { it.retail - it.price }.filter { it > 0.0 }

        // Calculate all stats
        val gameCount = games.size
        val avgDiscount = games.map { it.discount.toDouble() }.average().takeIf { !it.isNaN() } ?: 0.0
        val minDiscount = nonZeroDiscounts.minOrNull() ?: 0
        val maxDiscount = nonZeroDiscounts.maxOrNull() ?: 0
        val avgAbsDiscount = games.map { it.retail - it.price }.average().takeIf { !it.isNaN() } ?: 0.0
        val minAbsDiscount = nonZeroAbsoluteDiscounts.minOrNull() ?: 0.0
        val maxAbsDiscount = nonZeroAbsoluteDiscounts.maxOrNull() ?: 0.0

        buildStatement("START TRANSACTION").use { it.execute() }
        try {
            // Add or update game meta
            buildStatement(
                """
                    INSERT INTO game_meta (
                        id, name, 
                        type, url,
                        categories, description,
                        topseller, preorder, giftcard,
                        in_stock, steam_id
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    ON DUPLICATE KEY UPDATE
                        name = VALUES(name),
                        type = VALUES(type),
                        url = VALUES(url),
                        categories = VALUES(categories),
                        description = VALUES(description),
                        topseller = VALUES(topseller),
                        preorder = VALUES(preorder),
                        giftcard = VALUES(giftcard),
                        in_stock = VALUES(in_stock),
                        steam_id = VALUES(steam_id)
                """.trimIndent()
            ).use { statement ->
                games.forEach { game ->
                    statement.setInt(1, game.id)
                    statement.setString(2, game.name)
                    statement.setString(3, game.type)
                    statement.setString(4, extractSeoSlug(game.url) ?: "undefined")
                    statement.setString(5, json.encodeToString(game.category))
                    statement.setString(6, game.short_description)
                    statement.setBoolean(7, game.topseller)
                    statement.setBoolean(8, game.preorder)
                    statement.setBoolean(9, game.category.contains("Gift Cards"))
                    statement.setBoolean(10, game.stock)
                    statement.setInt(11, game.steam_id)
                    statement.addBatch()
                }
                statement.executeBatch()
            }

            // Add price snapshot
            buildStatement(
                """
                    INSERT INTO snapshot_prices (
                        snapshot_ts,
                        id,
                        price,
                        retail,
                        discount
                    ) VALUES (?, ?, ?, ?, ?)
                    ON DUPLICATE KEY UPDATE
                        price = VALUES(price),
                        retail = VALUES(retail),
                        discount = VALUES(discount)
                """.trimIndent()
            ).use { statement ->
                games.asSequence()
                    .filter { it.stock }
                    .forEach { game ->
                        statement.setTimestamp(1, snapshotTimestamp)
                        statement.setInt(2, game.id)
                        statement.setDouble(3, game.price)
                        statement.setDouble(4, game.retail)
                        statement.setInt(5, game.discount)
                        statement.addBatch()
                    }
                statement.executeBatch()
            }

            // Summed stats snapshot
            buildStatement(
                """
                    INSERT INTO snapshot_stats (
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
                statement.setDouble(7, minAbsDiscount)
                statement.setDouble(8, maxAbsDiscount)
                statement.executeUpdate()
            }

            buildStatement("COMMIT").use { it.execute() }
            IGStatsCache.invalidateAll()
            logger.info("IG snapshot persisted for {} games at {}", gameCount, snapshotTimestamp)
        } catch (exception: Exception) {
            runCatching { buildStatement("ROLLBACK").use { it.execute() } }
            logger.error("IG snapshot save failed", exception)
        }
    }

    private fun extractSeoSlug(url: String): String? {
        val path = runCatching { URI(url).path }.getOrNull() ?: return null
        val segment = path
            .trim('/')
            .split('/')
            .firstOrNull { it.contains("-buy-") }
            ?: return null
        return segment.substringAfter("-buy-", missingDelimiterValue = "").takeIf { it.isNotBlank() }
    }
}