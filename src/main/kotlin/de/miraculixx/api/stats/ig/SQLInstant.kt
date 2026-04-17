package de.miraculixx.api.stats.ig

import de.miraculixx.api.data.databaseCredentials
import de.miraculixx.api.json
import de.miraculixx.api.stats.DatabaseStructure
import de.miraculixx.api.stats.Updater
import io.ktor.utils.io.InternalAPI
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
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
    private val categoriesSerializer = ListSerializer(String.serializer())

    @OptIn(InternalAPI::class)
    suspend fun ensureReadIndexes() {
        if (!indexesEnsured.compareAndSet(false, true)) return

        val indexes = listOf(
            Triple("idx_game_meta_name", "game_meta", "CREATE INDEX idx_game_meta_name ON game_meta(name)"),
            Triple("idx_game_meta_type", "game_meta", "CREATE INDEX idx_game_meta_type ON game_meta(type)"),
            Triple("idx_game_meta_flags", "game_meta", "CREATE INDEX idx_game_meta_flags ON game_meta(preorder, giftcard, topseller, in_stock)"),
            Triple("idx_snapshot_prices_id", "snapshot_prices", "CREATE INDEX idx_snapshot_prices_id ON snapshot_prices(id)"),
            Triple("idx_snapshot_prices_ts_discount", "snapshot_prices", "CREATE INDEX idx_snapshot_prices_ts_discount ON snapshot_prices(snapshot_ts, discount)"),
            Triple("idx_snapshot_prices_ts_abs_discount", "snapshot_prices", "CREATE INDEX idx_snapshot_prices_ts_abs_discount ON snapshot_prices(snapshot_ts, abs_discount)"),
            Triple("idx_snapshot_prices_ts_price", "snapshot_prices", "CREATE INDEX idx_snapshot_prices_ts_price ON snapshot_prices(snapshot_ts, price)"),
            Triple("idx_snapshot_prices_ts_retail", "snapshot_prices", "CREATE INDEX idx_snapshot_prices_ts_retail ON snapshot_prices(snapshot_ts, retail)")
        )

        indexes.forEach { (indexName, table, sql) ->
            runCatching {
                if (hasIndex(table, indexName)) return@runCatching
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

        val types = mutableListOf<String>()
        buildStatement("SELECT DISTINCT type FROM game_meta ORDER BY type").use { statement ->
            statement.executeQuery().use { rs ->
                while (rs.next()) types += rs.getString("type")
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
            types = types,
            earliestSnapshotTs = earliestTs.toInstant().toString(),
            latestSnapshotTs = latestTs.toInstant().toString(),
            supportedIntervals = listOf(Interval.Day, Interval.Week, Interval.Month)
        )
    }

    @OptIn(InternalAPI::class)
    suspend fun fetchOverview(request: OverviewRequest): List<OverviewPoint> {
        ensureReadIndexes()

        val bucketExpression = when (request.interval) {
            Interval.Day -> "DATE(p.snapshot_ts)"
            Interval.Week -> "YEARWEEK(p.snapshot_ts, 1)"
            Interval.Month -> "DATE_FORMAT(p.snapshot_ts, '%Y-%m')"
        }

        val where = mutableListOf<String>()
        val args = mutableListOf<Any>()

        where += "m.in_stock = true"

        val zone = ZoneId.systemDefault()
        val fromTs = Timestamp.from(request.from.atStartOfDay(zone).toInstant())
        val toExclusiveTs = Timestamp.from(request.to.plusDays(1).atStartOfDay(zone).toInstant())
        where += "p.snapshot_ts >= ?"
        args += fromTs
        where += "p.snapshot_ts < ?"
        args += toExclusiveTs

        if (!request.type.equals("all", ignoreCase = true)) {
            where += "m.type = ?"
            args += request.type
        }

        if (request.onlyTopseller) {
            where += "m.topseller = true"
        } else {
            if (!request.includePreorder) where += "m.preorder = false"
            if (!request.includeGiftcard) where += "m.giftcard = false"
        }

        val sql =
            """
                SELECT
                    MAX(p.snapshot_ts) AS snapshot_ts,
                    COUNT(*) AS game_count,
                    COALESCE(AVG(p.discount), 0) AS avg_discount,
                    COALESCE(MIN(NULLIF(p.discount, 0)), 0) AS min_discount,
                    COALESCE(MAX(p.discount), 0) AS max_discount,
                    COALESCE(AVG(p.abs_discount), 0) AS avg_abs_discount,
                    COALESCE(MIN(NULLIF(p.abs_discount, 0)), 0) AS min_abs_discount,
                    COALESCE(MAX(p.abs_discount), 0) AS max_abs_discount
                FROM snapshot_prices p
                JOIN game_meta m ON m.id = p.id
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

        val where = mutableListOf("p.snapshot_ts = ?")
        val args = mutableListOf<Any>(request.snapshotTs)

        if (!request.type.equals("all", ignoreCase = true)) {
            where += "m.type = ?"
            args += request.type
        }
        if (!request.search.isNullOrBlank()) {
            where += "m.name LIKE ?"
            args += "%${request.search}%"
        }
        request.preorder?.let {
            where += "m.preorder = ?"
            args += it
        }
        request.giftcard?.let {
            where += "m.giftcard = ?"
            args += it
        }
        request.topseller?.let {
            where += "m.topseller = ?"
            args += it
        }
        request.inStock?.let {
            where += "m.in_stock = ?"
            args += it
        }

        val sortColumn = when (request.sortBy) {
            GameSortField.Discount -> "p.discount"
            GameSortField.AbsDiscount -> "p.abs_discount"
            GameSortField.Price -> "p.price"
            GameSortField.Retail -> "p.retail"
            GameSortField.Name -> "m.name"
        }
        val sortDirection = when (request.sortDir) {
            SortDirection.Asc -> "ASC"
            SortDirection.Desc -> "DESC"
        }
        val offset = (request.page - 1) * request.pageSize

        val whereClause = where.joinToString(" AND ")

        val countSql = """
            SELECT COUNT(*) AS total
            FROM snapshot_prices p
            JOIN game_meta m ON m.id = p.id
            WHERE $whereClause
        """.trimIndent()
        val total = buildStatement(countSql).use { statement ->
            args.forEachIndexed { index, value -> statement.setAny(index + 1, value) }
            statement.executeQuery().use { rs ->
                if (rs.next()) rs.getInt("total") else 0
            }
        }

        val rowsSql =
            """
                SELECT
                    p.snapshot_ts,
                    m.id,
                    m.name,
                    m.type,
                    m.url,
                    m.categories,
                    m.description,
                    m.topseller,
                    m.preorder,
                    m.giftcard,
                    m.in_stock,
                    m.steam_id,
                    p.retail,
                    p.price,
                    p.discount,
                    p.abs_discount
                FROM snapshot_prices p
                JOIN game_meta m ON m.id = p.id
                WHERE $whereClause
                ORDER BY $sortColumn $sortDirection, m.id ASC
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
                    val steamIdRaw = rs.getInt("steam_id")
                    val steamId = if (rs.wasNull()) null else steamIdRaw
                    rows += GameRow(
                        snapshotTs = rs.getTimestamp("snapshot_ts").toInstant().toString(),
                        id = rs.getInt("id"),
                        name = rs.getString("name"),
                        type = rs.getString("type"),
                        url = rs.getString("url"),
                        categories = decodeCategories(rs.getString("categories")),
                        description = rs.getString("description"),
                        topseller = rs.getBoolean("topseller"),
                        preorder = rs.getBoolean("preorder"),
                        giftcard = rs.getBoolean("giftcard"),
                        inStock = rs.getBoolean("in_stock"),
                        steamId = steamId,
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

        val where = mutableListOf("p.id = ?")
        val args = mutableListOf<Any>(request.id)

        val zone = ZoneId.systemDefault()
        request.from?.let {
            where += "p.snapshot_ts >= ?"
            args += Timestamp.from(it.atStartOfDay(zone).toInstant())
        }
        request.to?.let {
            where += "p.snapshot_ts < ?"
            args += Timestamp.from(it.plusDays(1).atStartOfDay(zone).toInstant())
        }

        val sql = """
            SELECT p.snapshot_ts, m.name, m.type, m.url, p.retail, p.price, p.discount, p.abs_discount
            FROM snapshot_prices p
            JOIN game_meta m ON m.id = p.id
            WHERE ${where.joinToString(" AND ")}
            ORDER BY p.snapshot_ts ASC
        """.trimIndent()

        var name: String? = null
        var type: String? = null
        var url: String? = null
        val points = mutableListOf<GameHistoryPoint>()
        buildStatement(sql).use { statement ->
            args.forEachIndexed { index, value -> statement.setAny(index + 1, value) }
            statement.executeQuery().use { rs ->
                while (rs.next()) {
                    if (name == null) {
                        name = rs.getString("name")
                        type = rs.getString("type")
                        url = rs.getString("url")
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
        else GameHistoryData(name = name!!, type = type!!, url = url!!, points = points)
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

    private fun decodeCategories(raw: String?): List<String> {
        if (raw.isNullOrBlank()) return emptyList()
        return runCatching { json.decodeFromString(categoriesSerializer, raw) }.getOrDefault(emptyList())
    }

    private fun isGiftcard(hit: IGHitResponse): Boolean {
        val type = hit.type.lowercase()
        return type.contains("giftcard") || type.contains("gift card") || type == "gift"
    }

    @OptIn(InternalAPI::class)
    suspend fun saveSnapshot(games: List<IGHitResponse>) {
        if (games.isEmpty()) {
            logger.warn("IG snapshot skipped: no games available")
            return
        }

        val gamesInStock = games.filter { it.stock }
        val snapshotTimestamp = Timestamp(System.currentTimeMillis())
        val nonZeroDiscounts = gamesInStock.map { it.discount }.filter { it > 0 }
        val nonZeroAbsoluteDiscounts = gamesInStock.map { it.retail - it.price }.filter { it > 0.0 }

        val gamesListed = games.size
        val gameCount = gamesInStock.size
        val avgDiscount = gamesInStock.map { it.discount.toDouble() }.average().takeIf { !it.isNaN() } ?: 0.0
        val minDiscount = nonZeroDiscounts.minOrNull() ?: 0
        val maxDiscount = nonZeroDiscounts.maxOrNull() ?: 0
        val avgAbsDiscount = gamesInStock.map { it.retail - it.price }.average().takeIf { !it.isNaN() } ?: 0.0
        val minAbsDiscount = nonZeroAbsoluteDiscounts.minOrNull() ?: 0.0
        val maxAbsDiscount = nonZeroAbsoluteDiscounts.maxOrNull() ?: 0.0

        buildStatement("START TRANSACTION").use { it.execute() }
        try {
            buildStatement(
                """
                    INSERT INTO game_meta (
                        id, name, type, url, categories, description,
                        topseller, preorder, giftcard, in_stock, steam_id
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
                gamesInStock.forEach { game ->
                    statement.setInt(1, game.id)
                    statement.setString(2, game.name)
                    statement.setString(3, game.type)
                    statement.setString(4, extractSeoSlug(game.url) ?: "undefined")
                    statement.setString(5, json.encodeToString(game.category))
                    statement.setString(6, game.short_description)
                    statement.setBoolean(7, game.topseller)
                    statement.setBoolean(8, game.preorder)
                    statement.setBoolean(9, isGiftcard(game))
                    statement.setBoolean(10, game.stock)
                    if (game.steam_id > 0) statement.setInt(11, game.steam_id)
                    else statement.setNull(11, java.sql.Types.INTEGER)
                    statement.addBatch()
                }
                statement.executeBatch()
            }

            buildStatement(
                """
                    INSERT INTO snapshot_prices (
                        snapshot_ts, id, price, retail, discount
                    ) VALUES (?, ?, ?, ?, ?)
                    ON DUPLICATE KEY UPDATE
                        price = VALUES(price),
                        retail = VALUES(retail),
                        discount = VALUES(discount)
                """.trimIndent()
            ).use { statement ->
                games.forEach { game ->
                    statement.setTimestamp(1, snapshotTimestamp)
                    statement.setInt(2, game.id)
                    statement.setDouble(3, game.price)
                    statement.setDouble(4, if (game.retail > 0) game.retail else game.price)
                    statement.setInt(5, game.discount)
                    statement.addBatch()
                }
                statement.executeBatch()
            }

            buildStatement(
                """
                    INSERT INTO snapshot_stats (
                        snapshot_ts,
                        game_count, game_listed,
                        avg_discount, min_discount, max_discount,
                        avg_abs_discount, min_abs_discount, max_abs_discount
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                    ON DUPLICATE KEY UPDATE
                        game_count = VALUES(game_count),
                        game_listed = VALUES(game_listed),
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
                statement.setInt(3, gamesListed)
                statement.setDouble(4, avgDiscount)
                statement.setInt(5, minDiscount)
                statement.setInt(6, maxDiscount)
                statement.setDouble(7, avgAbsDiscount)
                statement.setDouble(8, minAbsDiscount)
                statement.setDouble(9, maxAbsDiscount)
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