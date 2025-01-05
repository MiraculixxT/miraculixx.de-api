package de.miraculixx.api.routes

import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.*
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.server.sessions.Sessions
import io.ktor.server.sessions.cookie
import kotlinx.serialization.Serializable

fun Application.configureRouting() {
    routing {
        install(Sessions) {
            cookie<DiscordSession>("SESSION-DISCORD") {

            }
        }

        install(ContentNegotiation) {
            this.json()
        }

        get("/") {
            call.respondText("API for miraculixx.de - version 1.0.0")
        }

        routingAuthentication()
        routingIOT()
    }
}

@Serializable
data class DiscordSession(
    val id: Long,
    val username: @ParameterName("global_name") String,
    val avatar: String
)
