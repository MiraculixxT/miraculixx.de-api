package de.miraculixx.api.utils

import de.miraculixx.api.jsonPretty
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.nio.file.Path
import kotlin.io.path.createParentDirectories
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText


inline fun <reified T> Path.load(default: T, instance: Json = jsonPretty): T {
    return if (!exists()) {
        createParentDirectories()
        val string = instance.encodeToString(default)
        writeText(string)
        println("Created ${this.fileName} default config")
        default
    } else {
        try {
            instance.decodeFromString<T>(readText())
        } catch (e: Exception) {
            println("Failed to load ${this.fileName} config: Reason: ${e.message}")
            default
        }
    }
}
