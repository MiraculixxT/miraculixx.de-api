package de.miraculixx.api

import de.miraculixx.api.debug.configureSecurity
import de.miraculixx.api.debug.configureSockets
import de.miraculixx.api.routes.configureRouting
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.*
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory

val isProduction = false
val logger = LoggerFactory.getLogger("MAPI")
val json = Json {
    ignoreUnknownKeys = true
}
val jsonPretty = Json {
    prettyPrint = true
    ignoreUnknownKeys = true
}
val client = HttpClient(CIO) {
    install(ContentNegotiation) {
        json(json)
    }
}

fun main(args: Array<String>) {
    io.ktor.server.cio.EngineMain.main(args)
}

fun Application.module() {
    configureLimiting()
    configureRouting()

    // Debug
    configureSecurity()
    configureSockets()
}
