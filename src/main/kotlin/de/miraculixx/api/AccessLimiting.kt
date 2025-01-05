package de.miraculixx.api

import io.github.flaxoos.ktor.server.plugins.ratelimiter.RateLimiting
import io.github.flaxoos.ktor.server.plugins.ratelimiter.implementations.TokenBucket
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.plugins.cors.routing.*
import kotlin.time.Duration.Companion.seconds

fun Application.configureLimiting() {
    install(CORS) {
        allowMethod(HttpMethod.Get)
        allowMethod(HttpMethod.Put)
        allowHeaders { true } // Allow any headers
        allowHost("miraculixx.de", listOf("http", "https", "ws", "wss"))
        // anyHost()
    }

    install(RateLimiting) {
        rateLimiter {
            type = TokenBucket::class
            capacity = 100
            rate = 10.seconds
        }
    }
}
