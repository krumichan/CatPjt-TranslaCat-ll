package jp.co.translacat.languagelearning.features.leveltest.infrastructure.storage

import jp.co.translacat.languagelearning.features.leveltest.application.LevelTestAudioStore
import jp.co.translacat.languagelearning.features.leveltest.domain.exception.LevelTestException
import jp.co.translacat.languagelearning.features.leveltest.domain.policy.LevelTestRules
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.security.MessageDigest

/** 전용 디렉토리의 파일만 사용한다. 키는 서버가 만든 UUID이며 경로/사용자 파일명을 받지 않는다. */
internal class LocalLevelTestAudioStore(directory: Path) : LevelTestAudioStore {
    private val root = Files.createDirectories(directory.toAbsolutePath().normalize()).toRealPath()
    private fun path(key: String): Path {
        require(Regex("[0-9a-f-]{36}\\.(wav|audio)").matches(key)) { "음성 키 형식이 올바르지 않습니다." }
        return root.resolve(key).also { require(!Files.isSymbolicLink(it)) }
    }

    override suspend fun store(key: String, bytes: ByteArray) = withContext(Dispatchers.IO) {
        require(bytes.size in 1..LevelTestRules.MAX_AUDIO_BYTES)
        val target = path(key)
        // 완성된 임시 파일에만 원자적으로 이름을 붙인다. 중간 바이트를 다른 요청에 노출하지 않는다.
        // 동일 파일 시스템의 hard link를 사용하므로 대상이 있으면 덮어쓰지 않고 충돌을 검사한다.
        val temporary = Files.createTempFile(root, "upload-", ".part")
        try {
            Files.write(temporary, bytes, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)
            try {
                Files.createLink(target, temporary)
            } catch (_: java.nio.file.FileAlreadyExistsException) {
                if (!Files.isRegularFile(target) || Files.size(target) != bytes.size.toLong() ||
                    !MessageDigest.isEqual(Files.readAllBytes(target), bytes)
                ) {
                    throw LevelTestException("LEVEL_TEST_UPLOAD_CONFLICT", 409, "기존 음성과 내용이 다릅니다.")
                }
            }
        } finally {
            Files.deleteIfExists(temporary)
        }
        Unit
    }

    override suspend fun load(key: String): ByteArray = withContext(Dispatchers.IO) {
        val target = path(key)
        if (!Files.isRegularFile(target) || Files.size(target) !in 1..LevelTestRules.MAX_AUDIO_BYTES.toLong()) {
            throw LevelTestException("LANGUAGE_LEARNING_LEVEL_TEST_NOT_FOUND", 404, "음성이 없거나 보관기한이 지났습니다.")
        }
        Files.readAllBytes(target)
    }

    override suspend fun delete(key: String) = withContext(Dispatchers.IO) { Files.deleteIfExists(path(key)); Unit }
    override suspend fun exists(key: String): Boolean = withContext(Dispatchers.IO) { Files.isRegularFile(path(key)) }
}
