package de.miraculixx.api.data

import kotlinx.serialization.Serializable
import java.security.SecureRandom
import java.util.*

object Authentication {
    private val userSessions: MutableMap<String, SessionData> = mutableMapOf()

    fun getSession(token: String): DiscordSession? {
        return userSessions[token]?.apply { lastAccess = System.currentTimeMillis() / 1000 }?.data
    }

    /**
     * Generates a new token for the user
     */
    fun setSession(session: DiscordSession): String {
        val token = generateToken()
        SessionData(System.currentTimeMillis() / 1000, session).also {
            userSessions[token] = it
        }
        return token
    }

    private fun generateToken(): String {
        val secureRandom = SecureRandom()
        val tokenBytes = ByteArray(32)
        secureRandom.nextBytes(tokenBytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(tokenBytes)
    }

    @Serializable
    data class DiscordSession(
        val id: Long,
        val username: @ParameterName("global_name") String,
        val avatar: String
    )

    data class SessionData(
        var lastAccess: Long,
        val data: DiscordSession
    )
}