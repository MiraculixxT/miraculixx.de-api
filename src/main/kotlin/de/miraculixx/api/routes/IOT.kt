package de.miraculixx.api.routes

import de.miraculixx.api.data.Authentication
import de.miraculixx.api.data.IOT
import io.ktor.http.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import java.io.File

fun Routing.routingIOT() {
    IOT // Initialize IOT
    route("iot") {

        route("voice") {
            get("get") {
                val token = call.request.header("Authorization") ?: return@get respondUnauthorized()
                val session = Authentication.getSession(token) ?: return@get respondUnauthorized()
                val characterName = call.request.header("character") ?: return@get call.respond(HttpStatusCode.BadRequest, "Missing character header")
                val character = IOT.getCharacter(characterName, session.id) ?: return@get call.respond(HttpStatusCode.NotFound, "Character not found")

                IOT.logger.info("Requested character: $characterName")
                call.respond(character)
            }

            get("audio/{character}/{id}") {
                val token = call.request.header("Authorization") ?: return@get respondUnauthorized()
                val session = Authentication.getSession(token) ?: return@get respondUnauthorized()
                val characterName = call.parameters["character"] ?: return@get call.respond(HttpStatusCode.BadRequest, "Missing character header")
                val audioID = call.parameters["id"] ?: return@get call.respond(HttpStatusCode.BadRequest, "Missing audio id")
                val audio = IOT.getAudio(characterName, audioID, session.id) ?: return@get call.respond(HttpStatusCode.NotFound, "Audio not found")

                call.respond(audio)
            }

            post("audio/{character}/{id}") {
                val token = call.request.header("Authorization") ?: return@post respondUnauthorized()
                val session = Authentication.getSession(token) ?: return@post respondUnauthorized()
                val characterName = call.parameters["character"] ?: return@post call.respond(HttpStatusCode.BadRequest, "Missing character header")
                val audioID = call.parameters["id"] ?: return@post call.respond(HttpStatusCode.BadRequest, "Missing audio id")
                if (IOT.getAudio(characterName, audioID, session.id) == null) return@post call.respond(HttpStatusCode.NotFound, "Audio not found")

                // Receive audio data
                val audio = call.receiveStream().readBytes()
                val targetFile = File("data/iot/voice/submits/$characterName/$audioID.mp3")
                if (!targetFile.exists()) targetFile.parentFile.mkdirs()
                targetFile.writeBytes(audio)

                IOT.logger.info("Received audio submission for $characterName/$audioID")
                call.respond(HttpStatusCode.OK)
            }
        }
    }
}

suspend fun RoutingContext.respondUnauthorized() {
    call.respond(HttpStatusCode.Unauthorized, "Unauthorized request. Login first")
}
