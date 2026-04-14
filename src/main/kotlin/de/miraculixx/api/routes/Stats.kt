package de.miraculixx.api.routes

import de.miraculixx.api.data.Authentication
import de.miraculixx.api.stats.Updater
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.header
import io.ktor.server.response.respond
import io.ktor.server.routing.Routing
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

fun Routing.routingStats() {
    route("stats") {

        // Trigger a full update for all stats
        post("force-update") {
            val token = call.request.header("Authorization") ?: return@post respondUnauthorized()
            val session = Authentication.getSession(token) ?: return@post respondUnauthorized()
            if (session.id != 341998118574751745) return@post respondUnauthorized() // Admin only
            CoroutineScope(Dispatchers.Default).launch {
                Updater.runFullUpdate()
            }
            call.respond(HttpStatusCode.OK, "Triggered full update")
        }

    }
}
