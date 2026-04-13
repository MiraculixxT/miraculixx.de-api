package de.miraculixx.api.data

import kotlinx.serialization.Serializable

@Serializable
data class APIKeys(
    val ig_key: String = "<key>",
    val ig_id: String = "<id>"
)

@Serializable
data class DatabaseCredentials(
    val user: String = "<user>",
    val password: String = "<password>",
    val url: String = "<url>"
)

@Serializable
data class DatabaseConfig(
    val instant_gaming: DatabaseCredentials = DatabaseCredentials()
)
