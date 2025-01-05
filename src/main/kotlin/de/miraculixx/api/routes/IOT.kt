package de.miraculixx.api.routes

import de.miraculixx.api.data.IOT
import io.ktor.http.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.server.sessions.*

fun Routing.routingIOT() {
    IOT // Initialize IOT
    route("iot") {

        route("voice") {
            get("get") {
                val session = call.sessions.get<DiscordSession>() ?: return@get respondUnauthorized()
                val characterName = call.request.header("character") ?: return@get call.respond(HttpStatusCode.BadRequest, "Missing character header")
                val character = IOT.getCharacter(characterName, session.id) ?: return@get call.respond(HttpStatusCode.NotFound, "Character not found")

                call.respond(character)
            }

            get("audio/{character}/{id}") {
                val session = call.sessions.get<DiscordSession>() ?: return@get respondUnauthorized()
                val characterName = call.parameters["character"] ?: return@get call.respond(HttpStatusCode.BadRequest)
                val audioID = call.parameters["id"] ?: return@get call.respond(HttpStatusCode.BadRequest)
                val audio = IOT.getAudio(characterName, audioID, session.id) ?: return@get call.respond(HttpStatusCode.NotFound, "Audio not found")

                call.respond(audio)
            }
        }
    }
}

suspend fun RoutingContext.respondUnauthorized() {
    call.respond(HttpStatusCode.Unauthorized, "Unauthorized request. Login first")
}
