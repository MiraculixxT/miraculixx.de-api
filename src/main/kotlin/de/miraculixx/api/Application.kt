package de.miraculixx.api

import de.miraculixx.api.debug.configureSecurity
import de.miraculixx.api.debug.configureSockets
import io.ktor.server.application.*

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
