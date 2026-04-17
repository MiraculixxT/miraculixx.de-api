package de.miraculixx.api.routes

import de.miraculixx.api.data.Authentication
import de.miraculixx.api.stats.ig.IGStatsApi
import de.miraculixx.api.stats.Updater
import de.miraculixx.api.stats.ig.IGStatsCache
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.header
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.Routing
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

fun Routing.routingStats() {
    val admins = listOf(341998118574751745)
    route("stats") {

        get("admin") {
            val token = call.request.header("Authorization") ?: return@get respondUnauthorized()
            val session = Authentication.getSession(token) ?: return@get respondUnauthorized()
            if (!admins.contains(session.id)) return@get respondUnauthorized() // Admin only
            call.respond(HttpStatusCode.OK, "You are an admin Pog")
        }

        // Trigger a full update for all stats
        post("force-update") {
            val token = call.request.header("Authorization") ?: return@post respondUnauthorized()
            val session = Authentication.getSession(token) ?: return@post respondUnauthorized()
            if (!admins.contains(session.id)) return@post respondUnauthorized() // Admin only
            CoroutineScope(Dispatchers.Default).launch {
                Updater.runFullUpdate()
            }
            call.respond(HttpStatusCode.OK, "Triggered full update")
        }

        post("refresh-cache") {
            val token = call.request.header("Authorization") ?: return@post respondUnauthorized()
            val session = Authentication.getSession(token) ?: return@post respondUnauthorized()
            if (!admins.contains(session.id)) return@post respondUnauthorized() // Admin only
            IGStatsCache.invalidateAll()
            call.respond(HttpStatusCode.OK, "Refreshed cache")
        }

        route("ig") {
            get("meta") {
                IGStatsApi.meta(call)
            }

            get("overview") {
                IGStatsApi.overview(call)
            }

            get("games") {
                IGStatsApi.games(call)
            }

            get("games/history") {
                IGStatsApi.history(call)
            }
        }
    }
}
