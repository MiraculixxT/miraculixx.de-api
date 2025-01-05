package de.miraculixx.api.routes

import de.miraculixx.api.client
import de.miraculixx.api.isProduction
import de.miraculixx.api.json
import de.miraculixx.api.logger
import io.ktor.client.call.*
import io.ktor.client.request.*
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.server.sessions.*
import kotlinx.serialization.Serializable
import java.io.File


/**
 * Routing for Authentication
 */
fun Routing.routingAuthentication() {
    val testingRedirect = "http%3A%2F%2Flocalhost%3A8080%2Fauth%2Fcallback"
    val productionRedirect = "https%3A%2F%2Fapi.miraculixx.de%2Fauth%2Fcallback"
    val discordRequest = "https://discord.com/oauth2/authorize?client_id=1325239668739932190&response_type=code&redirect_uri=${if (isProduction) productionRedirect else testingRedirect}&scope=identify"
    val testingReturn = "http://localhost:3000"
    val productionReturn = "https://miraculixx.de"

    val credentials = json.decodeFromString<DcCredentials>(File("config/discord-credentials.json").readText())

    route("auth") {
        get("login/{previous}") {
            val previous = call.parameters["previous"] ?: "%2F" // URL encoded "/"
            call.respondRedirect("$discordRequest&state=$previous")
        }

        get("callback") {
            val returnTarget = if (isProduction) productionReturn else testingReturn
            val prev = call.parameters["state"] ?: return@get call.respondRedirect("/") // Unauthorized
            val code = call.parameters["code"] ?: return@get call.respondRedirect("$returnTarget/auth/failed")

            // Exchange code for token
            val response = try {
                client.post("https://discord.com/api/oauth2/token") {
                    contentType(ContentType.Application.FormUrlEncoded)
                    val redirect = if (isProduction) productionRedirect else testingRedirect
                    setBody("grant_type=authorization_code&code=$code&redirect_uri=$redirect&client_id=${credentials.clientID}&client_secret=${credentials.clientSecret}")
                }.body<DcOAuthCallback>()
            } catch (e: Exception) {
                logger.debug("Failed to exchange code for token\nReason: ${e.message}")
                return@get call.respondRedirect("$returnTarget/auth/failed")
            }
            if (response.scope != "identify") return@get call.respondRedirect("$returnTarget/auth/failed")

            // Get user data & create session
            val userData = client.get("https://discord.com/api/users/@me") {
                bearerAuth(response.access_token)
            }.body<DiscordSession>()
            call.sessions.set(userData)

            call.respondRedirect((if (isProduction) testingReturn else productionReturn) + prev)
        }
    }
}

@Serializable
data class DcCredentials(
    val clientID: String,
    val clientSecret: String
)

@Serializable
data class DcOAuthExchange(
    val grant_type: String = "authorization_code",
    val code: String,
    val redirect_uri: String
)

@Serializable
data class DcOAuthCallback(
    val access_token: String,
    val token_type: String,
    val expires_in: Long,
    val refresh_token: String,
    val scope: String
)
