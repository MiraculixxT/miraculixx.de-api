package de.miraculixx.api.data

import de.miraculixx.api.json
import io.github.irgaly.kfswatch.KfsDirectoryWatcher
import io.github.irgaly.kfswatch.KfsDirectoryWatcherEvent
import io.github.irgaly.kfswatch.KfsEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import org.slf4j.LoggerFactory
import java.io.File

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
        if (!voiceDataFile.exists()) {
            voiceDataFile.mkdirs()
            voiceDataFile.writeText(json.encodeToString(VoiceData(emptyMap())))
        }

        voiceData = json.decodeFromString<VoiceData>(voiceDataFile.readText())
        voiceEditor = json.decodeFromString(voiceEditorFile.readText())

        watcherScope.launch {
            watcher.add(watcherFolder.path)
            val logger = LoggerFactory.getLogger("FileWatcher")
            watcher.onEventFlow.collect { event: KfsDirectoryWatcherEvent ->
                if (event.event != KfsEvent.Modify) return@collect
                when (event.path) {
                    voiceDataFile.name -> voiceData = json.decodeFromString<VoiceData>(voiceDataFile.readText())
                    voiceEditorFile.name -> voiceEditor = json.decodeFromString(voiceEditorFile.readText())
                    else -> return@collect
                }
                logger.debug("Event received: {}", event)
            }
        }
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
    fun getAllCharacters(dcID: Long): List<VoiceCharacter> {
        return voiceData.characters.values.filter { dcID in voiceEditor || dcID in it.editor }
    }

    fun isEditor(dcID: Long): Boolean {
        return dcID in voiceEditor
    }

    @Serializable
    data class VoiceData(
        val characters: Map<String, VoiceCharacter>
    )

    @Serializable
    data class VoiceCharacter(
        val name: String,
        val description: String,
        val editor: Set<Long>,
        val texts: List<String>
    )
}