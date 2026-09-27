package jp.co.translacat.languagelearning.features.speaking.application

internal interface SpeakingAudioStore {
    suspend fun put(key: String, bytes: ByteArray, contentType: String = "application/octet-stream")
    suspend fun load(key: String): ByteArray
    suspend fun delete(key: String)
}
