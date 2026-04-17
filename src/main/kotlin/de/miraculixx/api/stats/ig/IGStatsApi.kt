package de.miraculixx.api.stats.ig

import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.respond
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.sql.Timestamp
import java.time.LocalDate

@Serializable
data class ErrorEnvelope(val error: ErrorDetail)

@Serializable
data class ErrorDetail(val code: String, val message: String)

@Serializable
enum class Interval {
    @SerialName("day") Day,
    @SerialName("week") Week,
    @SerialName("month") Month
}

@Serializable
enum class SortDirection {
    @SerialName("asc") Asc,
    @SerialName("desc") Desc
}

@Serializable
enum class GameSortField {
    @SerialName("discount") Discount,
    @SerialName("absDiscount") AbsDiscount,
    @SerialName("price") Price,
    @SerialName("retail") Retail,
    @SerialName("name") Name
}

@Serializable
data class MetaResponse(
    val types: List<String>,
    val earliestSnapshotTs: String,
    val latestSnapshotTs: String,
    val supportedIntervals: List<Interval>
)

@Serializable
data class OverviewQuery(
    val from: String,
    val to: String,
    val interval: Interval,
    val type: String,
    val onlyTopseller: Boolean,
    val includePreorder: Boolean,
    val includeGiftcard: Boolean
)

@Serializable
data class OverviewPoint(
    val snapshotTs: String,
    val gameCount: Int,
    val avgDiscount: Double,
    val minDiscount: Int,
    val maxDiscount: Int,
    val avgAbsDiscount: Double,
    val minAbsDiscount: Double,
    val maxAbsDiscount: Double
)

@Serializable
data class OverviewResponse(
    val query: OverviewQuery,
    val points: List<OverviewPoint>
)

@Serializable
data class GamesQuery(
    val snapshotTs: String,
    val type: String,
    val search: String? = null,
    val preorder: Boolean? = null,
    val giftcard: Boolean? = null,
    val topseller: Boolean? = null,
    val inStock: Boolean? = null,
    val sortBy: GameSortField,
    val sortDir: SortDirection,
    val page: Int,
    val pageSize: Int
)

@Serializable
data class GameRow(
    val snapshotTs: String,
    val id: Int,
    val name: String,
    val type: String,
    val url: String,
    val categories: List<String>,
    val description: String?,
    val topseller: Boolean,
    val preorder: Boolean,
    val giftcard: Boolean,
    val inStock: Boolean,
    val steamId: Int?,
    val retail: Double,
    val price: Double,
    val discount: Int,
    val absDiscount: Double
)

@Serializable
data class GamesResponse(
    val query: GamesQuery,
    val rows: List<GameRow>,
    val total: Int,
    val page: Int,
    val pageSize: Int
)

@Serializable
data class GameHistoryPoint(
    val snapshotTs: String,
    val price: Double,
    val retail: Double,
    val discount: Int,
    val absDiscount: Double
)

@Serializable
data class GameHistoryResponse(
    val id: Int,
    val name: String,
    val type: String,
    val url: String,
    val points: List<GameHistoryPoint>
)

data class GameHistoryData(
    val name: String,
    val type: String,
    val url: String,
    val points: List<GameHistoryPoint>
)

data class OverviewRequest(
    val from: LocalDate,
    val to: LocalDate,
    val interval: Interval,
    val type: String,
    val onlyTopseller: Boolean,
    val includePreorder: Boolean,
    val includeGiftcard: Boolean
)

data class GamesRequest(
    val snapshotTs: Timestamp,
    val type: String,
    val search: String?,
    val preorder: Boolean?,
    val giftcard: Boolean?,
    val topseller: Boolean?,
    val inStock: Boolean?,
    val sortBy: GameSortField,
    val sortDir: SortDirection,
    val page: Int,
    val pageSize: Int
)

data class GameHistoryRequest(
    val id: Int,
    val from: LocalDate?,
    val to: LocalDate?
)

object IGStatsApi {
    suspend fun meta(call: ApplicationCall) {
        val response = IGStatsCache.remember("meta", "all") { SQLInstant.fetchMeta() }
        if (response == null) {
            return call.respondApiError(HttpStatusCode.NotFound, "no_data", "No Instant Gaming snapshot data found")
        }

        call.response.headers.append(HttpHeaders.CacheControl, "public, max-age=300")
        call.respond(response)
    }

    suspend fun overview(call: ApplicationCall) {
        val parsed = parseOverview(call) ?: return
        val key = listOf(
            parsed.from.toString(),
            parsed.to.toString(),
            parsed.interval.name,
            parsed.type,
            parsed.onlyTopseller.toString(),
            parsed.includePreorder.toString(),
            parsed.includeGiftcard.toString()
        ).joinToString("|")

        val points = IGStatsCache.remember("overview", key) { SQLInstant.fetchOverview(parsed) }
        val response = OverviewResponse(
            query = OverviewQuery(
                from = parsed.from.toString(),
                to = parsed.to.toString(),
                interval = parsed.interval,
                type = parsed.type,
                onlyTopseller = parsed.onlyTopseller,
                includePreorder = parsed.includePreorder,
                includeGiftcard = parsed.includeGiftcard
            ),
            points = points
        )

        call.response.headers.append(HttpHeaders.CacheControl, "public, max-age=120")
        call.respond(response)
    }

    suspend fun games(call: ApplicationCall) {
        val rawSnapshotTs = call.request.queryParameters["snapshotTs"]
            ?: return call.respondApiError(HttpStatusCode.BadRequest, "invalid_query", "Missing required query parameter: snapshotTs")

        val resolvedSnapshotTs = SQLInstant.resolveSnapshotTimestamp(rawSnapshotTs)
            ?: return call.respondApiError(HttpStatusCode.NotFound, "snapshot_not_found", "No matching snapshot found for snapshotTs=$rawSnapshotTs")

        val type = call.request.queryParameters["type"]?.trim().orEmpty().ifBlank { "all" }
        val search = call.request.queryParameters["search"]?.trim()?.takeIf { it.isNotEmpty() }

        val preorder = parseOptionalBoolean(call, "preorder") ?: return
        val giftcard = parseOptionalBoolean(call, "giftcard") ?: return
        val topseller = parseOptionalBoolean(call, "topseller") ?: return
        val inStock = parseOptionalBoolean(call, "inStock") ?: return

        val sortBy = when (call.request.queryParameters["sortBy"]?.trim()?.lowercase()) {
            null, "", "discount" -> GameSortField.Discount
            "absdiscount" -> GameSortField.AbsDiscount
            "price" -> GameSortField.Price
            "retail" -> GameSortField.Retail
            "name" -> GameSortField.Name
            else -> return call.respondApiError(HttpStatusCode.BadRequest, "invalid_query", "Invalid sortBy value")
        }
        val sortDir = when (call.request.queryParameters["sortDir"]?.trim()?.lowercase()) {
            null, "", "desc" -> SortDirection.Desc
            "asc" -> SortDirection.Asc
            else -> return call.respondApiError(HttpStatusCode.BadRequest, "invalid_query", "Invalid sortDir value")
        }

        val pageRaw = call.request.queryParameters["page"]
        val page = if (pageRaw == null) 1 else pageRaw.toIntOrNull()?.coerceAtLeast(1)
            ?: return call.respondApiError(HttpStatusCode.BadRequest, "invalid_query", "Invalid page value")

        val pageSizeRaw = call.request.queryParameters["pageSize"]
        val pageSize = if (pageSizeRaw == null) 50 else pageSizeRaw.toIntOrNull()?.coerceIn(1, 200)
            ?: return call.respondApiError(HttpStatusCode.BadRequest, "invalid_query", "Invalid pageSize value")

        val req = GamesRequest(
            snapshotTs = resolvedSnapshotTs,
            type = type,
            search = search,
            preorder = preorder.value,
            giftcard = giftcard.value,
            topseller = topseller.value,
            inStock = inStock.value,
            sortBy = sortBy,
            sortDir = sortDir,
            page = page,
            pageSize = pageSize
        )

        val key = listOf(
            req.snapshotTs.time.toString(),
            req.type,
            req.search.orEmpty(),
            req.preorder?.toString() ?: "null",
            req.giftcard?.toString() ?: "null",
            req.topseller?.toString() ?: "null",
            req.inStock?.toString() ?: "null",
            req.sortBy.name,
            req.sortDir.name,
            req.page.toString(),
            req.pageSize.toString()
        ).joinToString("|")

        val payload = IGStatsCache.remember("games", key) { SQLInstant.fetchGames(req) }
        val response = GamesResponse(
            query = GamesQuery(
                snapshotTs = req.snapshotTs.toInstant().toString(),
                type = req.type,
                search = req.search,
                preorder = req.preorder,
                giftcard = req.giftcard,
                topseller = req.topseller,
                inStock = req.inStock,
                sortBy = req.sortBy,
                sortDir = req.sortDir,
                page = req.page,
                pageSize = req.pageSize
            ),
            rows = payload.second,
            total = payload.first,
            page = req.page,
            pageSize = req.pageSize
        )

        call.response.headers.append(HttpHeaders.CacheControl, "public, max-age=60")
        call.respond(response)
    }

    suspend fun history(call: ApplicationCall) {
        val idRaw = call.request.queryParameters["id"] ?: call.request.queryParameters["prodId"]
            ?: return call.respondApiError(HttpStatusCode.BadRequest, "invalid_query", "Missing required query parameter: id")
        val id = idRaw.toIntOrNull()
            ?: return call.respondApiError(HttpStatusCode.BadRequest, "invalid_query", "Invalid id value")

        val fromRaw = call.request.queryParameters["from"]
        val from: LocalDate? = if (fromRaw != null) {
            runCatching { LocalDate.parse(fromRaw) }.getOrNull()
                ?: return call.respondApiError(HttpStatusCode.BadRequest, "invalid_query", "Invalid from date (expected YYYY-MM-DD)")
        } else null

        val toRaw = call.request.queryParameters["to"]
        val to: LocalDate? = if (toRaw != null) {
            runCatching { LocalDate.parse(toRaw) }.getOrNull()
                ?: return call.respondApiError(HttpStatusCode.BadRequest, "invalid_query", "Invalid to date (expected YYYY-MM-DD)")
        } else null

        if (from != null && to != null && to.isBefore(from)) {
            return call.respondApiError(HttpStatusCode.BadRequest, "invalid_query", "to must be equal or after from")
        }

        val req = GameHistoryRequest(id = id, from = from, to = to)
        val key = listOf(req.id.toString(), req.from?.toString() ?: "null", req.to?.toString() ?: "null").joinToString("|")

        val data = IGStatsCache.remember("history", key) { SQLInstant.fetchGameHistory(req) }
        if (data == null) {
            return call.respondApiError(HttpStatusCode.NotFound, "game_not_found", "No history found for id=$id")
        }

        val response = GameHistoryResponse(
            id = req.id,
            name = data.name,
            type = data.type,
            url = data.url,
            points = data.points
        )

        call.response.headers.append(HttpHeaders.CacheControl, "public, max-age=120")
        call.respond(response)
    }

    private suspend fun parseOverview(call: ApplicationCall): OverviewRequest? {
        val fromRaw = call.request.queryParameters["from"]
            ?: return call.respondApiError(HttpStatusCode.BadRequest, "invalid_query", "Missing required query parameter: from").let { null }
        val toRaw = call.request.queryParameters["to"]
            ?: return call.respondApiError(HttpStatusCode.BadRequest, "invalid_query", "Missing required query parameter: to").let { null }

        val from = runCatching { LocalDate.parse(fromRaw) }.getOrNull()
            ?: return call.respondApiError(HttpStatusCode.BadRequest, "invalid_query", "Invalid from date (expected YYYY-MM-DD)").let { null }
        val to = runCatching { LocalDate.parse(toRaw) }.getOrNull()
            ?: return call.respondApiError(HttpStatusCode.BadRequest, "invalid_query", "Invalid to date (expected YYYY-MM-DD)").let { null }
        if (to.isBefore(from)) {
            return call.respondApiError(HttpStatusCode.BadRequest, "invalid_query", "to must be equal or after from").let { null }
        }

        val interval = when (call.request.queryParameters["interval"]?.trim()?.lowercase()) {
            null, "", "day" -> Interval.Day
            "week" -> Interval.Week
            "month" -> Interval.Month
            else -> return call.respondApiError(HttpStatusCode.BadRequest, "invalid_query", "Invalid interval value").let { null }
        }
        val type = call.request.queryParameters["type"]?.trim().orEmpty().ifBlank { "all" }

        val onlyTopsellerRaw = call.request.queryParameters["onlyTopseller"]
        val onlyTopseller = if (onlyTopsellerRaw == null) false else parseBoolean(onlyTopsellerRaw)
            ?: return call.respondApiError(HttpStatusCode.BadRequest, "invalid_query", "Invalid onlyTopseller value").let { null }

        val includePreorderRaw = call.request.queryParameters["includePreorder"]
        val includePreorder = if (includePreorderRaw == null) true else parseBoolean(includePreorderRaw)
            ?: return call.respondApiError(HttpStatusCode.BadRequest, "invalid_query", "Invalid includePreorder value").let { null }

        val includeGiftcardRaw = call.request.queryParameters["includeGiftcard"]
        val includeGiftcard = if (includeGiftcardRaw == null) false else parseBoolean(includeGiftcardRaw)
            ?: return call.respondApiError(HttpStatusCode.BadRequest, "invalid_query", "Invalid includeGiftcard value").let { null }

        return OverviewRequest(
            from = from,
            to = to,
            interval = interval,
            type = type,
            onlyTopseller = onlyTopseller,
            includePreorder = includePreorder,
            includeGiftcard = includeGiftcard
        )
    }

    private data class OptionalBool(val value: Boolean?)

    private suspend fun parseOptionalBoolean(call: ApplicationCall, name: String): OptionalBool? {
        val raw = call.request.queryParameters[name] ?: return OptionalBool(null)
        val parsed = parseBoolean(raw) ?: run {
            call.respondApiError(HttpStatusCode.BadRequest, "invalid_query", "Invalid $name value")
            return null
        }
        return OptionalBool(parsed)
    }

    private fun parseBoolean(value: String): Boolean? {
        return when (value.trim().lowercase()) {
            "true", "1" -> true
            "false", "0" -> false
            else -> null
        }
    }

    private suspend fun ApplicationCall.respondApiError(status: HttpStatusCode, code: String, message: String) {
        respond(status, ErrorEnvelope(ErrorDetail(code, message)))
    }
}
