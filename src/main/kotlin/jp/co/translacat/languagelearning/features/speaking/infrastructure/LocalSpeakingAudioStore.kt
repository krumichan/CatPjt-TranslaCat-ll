package jp.co.translacat.languagelearning.features.speaking.infrastructure

import jp.co.translacat.languagelearning.features.speaking.application.SpeakingAudioStore
import jp.co.translacat.languagelearning.features.speaking.domain.SpeakingFailure
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption

internal class LocalSpeakingAudioStore(directory: Path) : SpeakingAudioStore {
    private val root = Files.createDirectories(directory.toAbsolutePath().normalize()).toRealPath()
    private fun path(key: String): Path {
        require(Regex("[0-9a-f-]{36}\\.audio").matches(key))
        return root.resolve(key).also { require(!Files.isSymbolicLink(it)) }
    }

    override suspend fun put(key: String, bytes: ByteArray, contentType: String) = withContext(Dispatchers.IO) {
        require(bytes.isNotEmpty())
        val target = path(key)
        val temporary = Files.createTempFile(root, "speaking-upload-", ".part")
        try {
            // 재녹음은 새 객체에 완성된 바이트를 쓴 뒤 DB 참조를 바꾼다. 기존 객체를 덮어쓰지 않는다.
            Files.write(temporary, bytes, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)
            Files.createLink(target, temporary)
        } finally {
            Files.deleteIfExists(temporary)
        }
        Unit
    }

    override suspend fun load(key: String): ByteArray = withContext(Dispatchers.IO) {
        val target = path(key)
        if (!Files.isRegularFile(target)) throw SpeakingFailure("INVALID_AUDIO")
        Files.readAllBytes(target)
    }

    override suspend fun delete(key: String) = withContext(Dispatchers.IO) {
        Files.deleteIfExists(path(key))
        Unit
    }
}
