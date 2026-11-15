package cc.seebrook.platform.storage

import cc.seebrook.platform.config.StorageConfig
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider
import software.amazon.awssdk.core.exception.SdkException
import software.amazon.awssdk.core.sync.RequestBody
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.S3Configuration
import software.amazon.awssdk.services.s3.model.NoSuchKeyException
import software.amazon.awssdk.services.s3.presigner.S3Presigner
import java.net.URI
import java.time.Duration

/** Storage could not complete the request; the caller retries later. Never contains secrets. */
class StorageUnavailableException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

/**
 * Private file storage (ED-38). Nothing is public: owners download through short-lived signed
 * links. Business code sees only this interface; the provider is configuration.
 */
interface ObjectStorage {
    fun put(key: String, content: ByteArray, contentType: String)

    /** The object's bytes, or null if there is no such object. */
    fun get(key: String): ByteArray?

    fun copy(fromKey: String, toKey: String)

    /** Removes the object; does nothing if it does not exist. */
    fun delete(key: String)

    /** A link that downloads the object for [validFor], suggesting [fileName] to the browser or app. */
    fun signedDownloadUrl(key: String, validFor: Duration, fileName: String): URI
}

/**
 * Any S3-compatible storage (AWS S3, Cloudflare R2, Backblaze B2, DigitalOcean Spaces, …; S3Mock
 * locally). Choosing a provider is a matter of endpoint, region, bucket and keys.
 */
class S3ObjectStorage(private val config: StorageConfig) : ObjectStorage, AutoCloseable {
    private val credentials = StaticCredentialsProvider.create(AwsBasicCredentials.create(config.accessKeyId, config.secretAccessKey))
    private val s3Config = S3Configuration.builder().pathStyleAccessEnabled(config.pathStyle).build()

    private val client: S3Client = S3Client.builder()
        .httpClientBuilder(UrlConnectionHttpClient.builder().connectionTimeout(Duration.ofSeconds(10)).socketTimeout(Duration.ofSeconds(60)))
        .region(Region.of(config.region))
        .credentialsProvider(credentials)
        .serviceConfiguration(s3Config)
        .apply { config.endpoint?.let { endpointOverride(URI(it)) } }
        .build()

    private val presigner: S3Presigner = S3Presigner.builder()
        .region(Region.of(config.region))
        .credentialsProvider(credentials)
        .serviceConfiguration(s3Config)
        .apply { config.endpoint?.let { endpointOverride(URI(it)) } }
        .build()

    override fun put(key: String, content: ByteArray, contentType: String) = storage("put") {
        client.putObject({ it.bucket(config.bucket).key(key).contentType(contentType) }, RequestBody.fromBytes(content))
        Unit
    }

    override fun get(key: String): ByteArray? = storage("get") {
        try {
            client.getObjectAsBytes { it.bucket(config.bucket).key(key) }.asByteArray()
        } catch (_: NoSuchKeyException) {
            null
        }
    }

    override fun copy(fromKey: String, toKey: String) = storage("copy") {
        client.copyObject {
            it.sourceBucket(config.bucket).sourceKey(fromKey).destinationBucket(config.bucket).destinationKey(toKey)
        }
        Unit
    }

    override fun delete(key: String) = storage("delete") {
        client.deleteObject { it.bucket(config.bucket).key(key) }
        Unit
    }

    override fun signedDownloadUrl(key: String, validFor: Duration, fileName: String): URI = storage("sign") {
        val safeName = fileName.replace(Regex("[^A-Za-z0-9._ -]"), "_").take(150).ifBlank { "document.pdf" }
        presigner.presignGetObject { request ->
            request.signatureDuration(validFor).getObjectRequest {
                it.bucket(config.bucket).key(key)
                    .responseContentDisposition("attachment; filename=\"$safeName\"")
                    .responseContentType("application/pdf")
            }
        }.url().toURI()
    }

    private fun <T> storage(operation: String, block: () -> T): T =
        try {
            block()
        } catch (e: SdkException) {
            // The SDK's message can include the bucket and key; the type is enough to diagnose.
            throw StorageUnavailableException("Storage $operation failed (${e.javaClass.simpleName})", e)
        }

    override fun close() {
        client.close()
        presigner.close()
    }
}
