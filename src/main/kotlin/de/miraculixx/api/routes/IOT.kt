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

            get("list") {
                val token = call.request.header("Authorization") ?: return@get respondUnauthorized()
                val session = Authentication.getSession(token) ?: return@get respondUnauthorized()
                val characters = IOT.getAllCharacters(session.id)

                IOT.logger.info("Requested character list")
                call.respond(characters)
            }

            get("is-editor") {
                val token = call.request.header("Authorization") ?: return@get respondUnauthorized()
                val session = Authentication.getSession(token) ?: return@get respondUnauthorized()
                call.respond(IOT.isEditor(session.id))
            }

            route("audio") {
                get("{character}/{id}") {
                    val token = call.request.header("Authorization") ?: return@get respondUnauthorized()
                    val session = Authentication.getSession(token) ?: return@get respondUnauthorized()
                    val characterName = call.parameters["character"] ?: return@get call.respond(HttpStatusCode.BadRequest, "Missing character header")
                    val audioID = call.parameters["id"] ?: return@get call.respond(HttpStatusCode.BadRequest, "Missing audio id")
                    val audio = IOT.getAudio(characterName, audioID, session.id) ?: return@get call.respond(HttpStatusCode.NotFound, "Audio not found")

                    call.respond(audio)
                }

                post("{character}/{id}") {
                    val token = call.request.header("Authorization") ?: return@post respondUnauthorized()
                    val session = Authentication.getSession(token) ?: return@post respondUnauthorized()
                    val characterName = call.parameters["character"] ?: return@post call.respond(HttpStatusCode.BadRequest, "Missing character header")
                    val audioID = call.parameters["id"] ?: return@post call.respond(HttpStatusCode.BadRequest, "Missing audio id")
                    if (IOT.getAudio(characterName, audioID, session.id) == null) return@post call.respond(HttpStatusCode.NotFound, "Audio not found")

                    // Check file size
                    val contentLength = call.request.header(HttpHeaders.ContentLength)?.toLongOrNull()
                    if (contentLength == null || contentLength > 10 * 1024 * 1024) {
                        return@post call.respond(HttpStatusCode.PayloadTooLarge, "File size exceeds the 10MB limit")
                    }

                    // Receive audio data
                    val stream = call.receiveStream()
                    val audio = stream.readBytes().also { stream.close() }
                    val targetFile = File("data/iot/voice/submits/$characterName/$audioID.mp3")
                    if (!targetFile.exists()) targetFile.parentFile.mkdirs()
                    targetFile.writeBytes(audio)

                    IOT.logger.info("Received audio submission for $characterName/$audioID")
                    call.respond(HttpStatusCode.OK)
                }

                get("submits/{character}/{id}") {
                    val token = call.request.header("Authorization") ?: return@get respondUnauthorized()
                    val session = Authentication.getSession(token) ?: return@get respondUnauthorized()
                    val characterName = call.parameters["character"] ?: return@get call.respond(HttpStatusCode.BadRequest, "Missing character header")
                    val audioID = call.parameters["id"] ?: return@get call.respond(HttpStatusCode.BadRequest, "Missing audio id")
                    val audio = IOT.getSubmitAudio(characterName, audioID, session.id) ?: return@get call.respond(HttpStatusCode.NotFound, "Audio not found")

                    call.respond(audio)
                }
            }
        }
    }
}

suspend fun RoutingContext.respondUnauthorized() {
    call.respond(HttpStatusCode.Unauthorized, "Unauthorized request. Login first")
}
