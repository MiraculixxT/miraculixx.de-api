package de.miraculixx.api.data

import de.miraculixx.api.json
import de.miraculixx.api.jsonPretty
import io.github.irgaly.kfswatch.KfsDirectoryWatcher
import io.github.irgaly.kfswatch.KfsDirectoryWatcherEvent
import io.github.irgaly.kfswatch.KfsEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import org.slf4j.LoggerFactory
import java.io.File
import kotlin.time.Duration.Companion.seconds

object IOT {
    val logger = LoggerFactory.getLogger("IOT")
    private val watcherScope = CoroutineScope(Dispatchers.Default)
    private val watcher = KfsDirectoryWatcher(watcherScope)
    private val watcherFolder = File("data/iot/voice")

    private val voiceDataFile = File(watcherFolder, "data.json")
    private var voiceData: VoiceData

    private val voiceEditorFile = File(watcherFolder, "editor.json")
    private var voiceEditor: Set<Long>

    init {
        voiceDataFile.parentFile.mkdirs()
        if (!voiceDataFile.exists()) {
            voiceDataFile.writeText(json.encodeToString(VoiceData(mutableMapOf())))
        }
        if (!voiceEditorFile.exists()) {
            voiceEditorFile.writeText(json.encodeToString(emptySet<Long>()))
        }

        voiceData = json.decodeFromString<VoiceData>(voiceDataFile.readText())
        voiceEditor = json.decodeFromString(voiceEditorFile.readText())

        watcherScope.launch {
            watcher.add(watcherFolder.path)
            val logger = LoggerFactory.getLogger("FileWatcher")
            watcher.onEventFlow.collect { event: KfsDirectoryWatcherEvent ->
                if (event.event != KfsEvent.Modify) return@collect
                logger.info("Event received: {}", event)
                when (event.path) {
                    voiceDataFile.name -> reloadData<VoiceData>(voiceDataFile) { voiceData = it }
                    voiceEditorFile.name -> reloadData<Set<Long>>(voiceEditorFile) { voiceEditor = it }
                    else -> return@collect
                }
            }
        }
    }

    private var reloadLock = false
    private suspend inline fun <reified A> reloadData(file: File, consumer: (A) -> Unit) {
        if (reloadLock) return
        reloadLock = true
        delay(3.seconds)
        val data = try {
            logger.info("Reloading data from ${file.name}")
            logger.debug("Data: ${file.readText()}")
            json.decodeFromString<A>(file.readText())
        } catch (e: Exception) {
            logger.error("Failed to reload data from ${file.name}\nReason: ${e.message}")
            return
        }
        consumer(data)
        reloadLock = false
    }

    /**
     * Get a voice character by name if the requester has access to it
     * @return The voice character or null if the character does not exist or the requester does not have access
     */
    fun getCharacter(characterName: String, dcID: Long): VoiceCharacter? {
        val character = voiceData.characters[characterName] ?: return null
        if (dcID !in voiceEditor && dcID !in character.editor) return null
        return character.copy(editor = setOf(dcID))
    }

    fun getAudio(characterName: String, audioID: String, dcID: Long): ByteArray? {
        getCharacter(characterName, dcID) ?: return null
        val audio = File(watcherFolder, "audio/$characterName/$audioID.mp3")
        if (!audio.exists()) return null
        return audio.readBytes()
    }

    fun getSubmitAudio(characterName: String, audioID: String, dcID: Long): ByteArray? {
        getCharacter(characterName, dcID) ?: return null
        val audio = File(watcherFolder, "submits/$characterName/$audioID.mp3")
        if (!audio.exists()) return null
        return audio.readBytes()
    }

    /**
     * Get all characters the requester has access to
     */
    fun getAllCharacters(dcID: Long): Map<String, VoiceCharacter> {
        return voiceData.characters.filter { dcID in voiceEditor || dcID in it.value.editor }
    }

    fun isEditor(dcID: Long): Boolean {
        return dcID in voiceEditor
    }

    fun editCharacter(data: VoiceCharacter, dcID: Long) {
        if (dcID !in voiceEditor) return
        voiceData.characters[data.id] = data
        reloadLock = true
        voiceDataFile.writeText(jsonPretty.encodeToString(voiceData))

        CoroutineScope(Dispatchers.Default).launch {
            delay(3.seconds)
            reloadLock = false
        }
    }

    @Serializable
    data class VoiceData(
        val characters: MutableMap<String, VoiceCharacter>
    )

    @Serializable
    data class VoiceCharacter(
        val id: String,
        val name: String,
        val description: String,
        val editor: Set<Long>,
        val texts: List<String>
    )
}