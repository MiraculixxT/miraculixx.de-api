package de.miraculixx.api.data

import de.miraculixx.api.utils.load
import kotlin.io.path.Path

private val fileAPIKeys = Path("config/api-keys.json")
val apiKeys = fileAPIKeys.load(APIKeys())

private val fileDatabaseCredentials = Path("config/database.json")
val databaseCredentials = fileDatabaseCredentials.load(DatabaseConfig())

