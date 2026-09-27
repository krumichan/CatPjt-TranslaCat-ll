package jp.co.translacat.languagelearning.features.speaking.infrastructure

import jp.co.translacat.languagelearning.features.speaking.application.SpeakingAudioStore
import jp.co.translacat.languagelearning.features.speaking.domain.SpeakingFailure
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider
import software.amazon.awssdk.core.exception.SdkException
import software.amazon.awssdk.core.sync.RequestBody
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.S3Configuration
import software.amazon.awssdk.services.s3.model.*
import java.net.URI

internal class S3SpeakingAudioStore(
    endpoint: URI, region: String, private val bucket: String,
    accessKey: String, secretKey: String,
) : SpeakingAudioStore, AutoCloseable {
    private val client: S3Client

    init {
        require(
            endpoint.scheme in setOf("http", "https") && endpoint.host != null && endpoint.rawUserInfo == null &&
                endpoint.rawQuery == null && endpoint.rawFragment == null,
        )
        require(region.isNotBlank() && bucket.isNotBlank() && accessKey.isNotBlank() && secretKey.isNotBlank())
        // 명시된 endpoint·정적 자격증명만 사용하며 PC/클라우드의 기본 credential chain을 탐색하지 않는다.
        client = S3Client.builder().endpointOverride(endpoint).region(Region.of(region))
            .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(accessKey, secretKey)))
            .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(true).build()).build()
    }

    override suspend fun put(key: String, bytes: ByteArray, contentType: String) = runInterruptible(Dispatchers.IO) {
        require(bytes.isNotEmpty())
        try {
            client.putObject(
                PutObjectRequest.builder().bucket(bucket).key(objectKey(key)).contentType(contentType)
                    .cacheControl("private, no-store").ifNoneMatch("*").build(),
                RequestBody.fromBytes(bytes),
            )
        } catch (_: SdkException) {
            throw SpeakingFailure("SPEAKING_AUDIO_STORAGE_FAILED", 502)
        }
        Unit
    }

    override suspend fun load(key: String): ByteArray = runInterruptible(Dispatchers.IO) {
        try {
            client.getObjectAsBytes(GetObjectRequest.builder().bucket(bucket).key(objectKey(key)).build()).asByteArray()
        } catch (_: NoSuchKeyException) {
            throw SpeakingFailure("INVALID_AUDIO")
        } catch (_: SdkException) {
            throw SpeakingFailure("SPEAKING_AUDIO_STORAGE_FAILED", 502)
        }
    }

    override suspend fun delete(key: String) = runInterruptible(Dispatchers.IO) {
        try {
            client.deleteObject(DeleteObjectRequest.builder().bucket(bucket).key(objectKey(key)).build())
        } catch (_: SdkException) {
            throw SpeakingFailure("SPEAKING_AUDIO_STORAGE_FAILED", 502)
        }
        Unit
    }

    private fun objectKey(key: String): String {
        require(Regex("[0-9a-f-]{36}\\.audio").matches(key))
        return "language-learning/speaking-ll/$key"
    }

    override fun close() {
        client.close()
    }
}
