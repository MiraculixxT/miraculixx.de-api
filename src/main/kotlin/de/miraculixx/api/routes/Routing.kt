package de.miraculixx.api.routes

import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.*
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.response.*
import io.ktor.server.routing.*

fun Application.configureRouting() {
    routing {
        install(ContentNegotiation) {
            this.json()
        }

        get("/") {
            call.respondText("API for miraculixx.de - version 1.1.0")
        }

        routingAuthentication()
        routingIOT()
        routingStats()
    }
}
