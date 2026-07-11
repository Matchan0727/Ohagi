package jp.simplespace.audio

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.forms.MultiPartFormDataContent
import io.ktor.client.request.forms.formData
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import java.nio.file.Files
import java.nio.file.Path

class RvcApiClient(
    private val baseUrl: String,
    httpClient: HttpClient? = null,
) : AutoCloseable {
    private val httpClient: HttpClient = httpClient ?: defaultHttpClient()
    private val ownsHttpClient: Boolean = httpClient == null

    suspend fun separate(
        file: ByteArray,
        fileName: String,
        modelName: String = DEFAULT_UVR_MODEL,
    ): ByteArray = postMultipartFile(
        path = "/api/v1/separate",
        file = file,
        fileName = fileName,
        fields = listOf("model_name" to modelName),
    )

    suspend fun separate(
        file: Path,
        modelName: String = DEFAULT_UVR_MODEL,
    ): ByteArray = separate(withContext(Dispatchers.IO) {
        Files.readAllBytes(file)
    }, file.fileName.toString(), modelName)

    suspend fun convert(
        file: ByteArray,
        fileName: String,
        modelName: String,
        pitch: Int = 0,
        indexRate: Double = 0.75,
        protect: Double = 0.33,
        rvcVersion: String = DEFAULT_RVC_VERSION,
    ): ByteArray = postMultipartFile(
        path = "/api/v1/convert",
        file = file,
        fileName = fileName,
        fields = listOf(
            "model_name" to modelName,
            "pitch" to pitch.toString(),
            "index_rate" to indexRate.toString(),
            "protect" to protect.toString(),
            "rvc_version" to rvcVersion,
        ),
    )

    suspend fun convert(
        file: Path,
        modelName: String,
        pitch: Int = 0,
        indexRate: Double = 0.75,
        protect: Double = 0.33,
        rvcVersion: String = DEFAULT_RVC_VERSION,
    ): ByteArray = convert(withContext(Dispatchers.IO) {
        Files.readAllBytes(file)
    }, file.fileName.toString(), modelName, pitch, indexRate, protect, rvcVersion)

    suspend fun createCover(
        file: ByteArray,
        fileName: String,
        rvcModel: String,
        uvrModel: String = DEFAULT_UVR_MODEL,
        pitch: Int = 0,
        indexRate: Double = 0.75,
        protect: Double = 0.33,
        rvcVersion: String = DEFAULT_RVC_VERSION,
        vocalVolumeAdjust: Double = 7.0,
    ): ByteArray = postMultipartFile(
        path = "/api/v1/cover",
        file = file,
        fileName = fileName,
        fields = listOf(
            "uvr_model" to uvrModel,
            "rvc_model" to rvcModel,
            "pitch" to pitch.toString(),
            "index_rate" to indexRate.toString(),
            "protect" to protect.toString(),
            "rvc_version" to rvcVersion,
            "vocal_volume_adjust" to vocalVolumeAdjust.toString(),
        ),
    )

    suspend fun createCover(
        file: Path,
        rvcModel: String,
        uvrModel: String = DEFAULT_UVR_MODEL,
        pitch: Int = 0,
        indexRate: Double = 0.75,
        protect: Double = 0.33,
        rvcVersion: String = DEFAULT_RVC_VERSION,
        vocalVolumeAdjust: Double = 7.0,
    ): ByteArray = createCover(
        file = withContext(Dispatchers.IO) {
            Files.readAllBytes(file)
        },
        fileName = file.fileName.toString(),
        rvcModel = rvcModel,
        uvrModel = uvrModel,
        pitch = pitch,
        indexRate = indexRate,
        protect = protect,
        rvcVersion = rvcVersion,
        vocalVolumeAdjust = vocalVolumeAdjust,
    )

    suspend fun download(filePath: String): ByteArray = httpClient.get(fullUrl("/api/v1/download")) {
        url {
            parameters.append("file_path", filePath)
        }
    }.body<ByteArray>()

    suspend fun listModels(): JsonElement = httpClient.get(fullUrl("/api/v1/models")).body()

    suspend fun root(): JsonElement = httpClient.get(fullUrl("/")).body()

    override fun close() {
        if (ownsHttpClient) {
            httpClient.close()
        }
    }

    private suspend fun postMultipartFile(
        path: String,
        file: ByteArray,
        fileName: String,
        fields: List<Pair<String, String>>,
    ): ByteArray = httpClient.post(fullUrl(path)) {
        setBody(
            MultiPartFormDataContent(
                formData {
                    append(
                        key = "file",
                        value = file,
                        headers = Headers.build {
                            append(HttpHeaders.ContentDisposition, """filename="$fileName"""")
                            append(HttpHeaders.ContentType, ContentType.Application.OctetStream.toString())
                        },
                    )
                    fields.forEach { (name, value) ->
                        append(name, value)
                    }
                },
            ),
        )
    }.body<ByteArray>()

    private fun fullUrl(path: String): String = baseUrl.trimEnd('/') + path

    companion object {
        const val DEFAULT_UVR_MODEL = "UVR-MDX-NET-Inst_HQ_5.onnx"
        const val DEFAULT_RVC_VERSION = "v2"
        private const val LONG_TIMEOUT_MILLIS = 5 * 60 * 1000L

        fun defaultHttpClient(): HttpClient = HttpClient(CIO) {
            install(HttpTimeout) {
                requestTimeoutMillis = LONG_TIMEOUT_MILLIS
                connectTimeoutMillis = LONG_TIMEOUT_MILLIS
                socketTimeoutMillis = LONG_TIMEOUT_MILLIS
            }
            install(ContentNegotiation) {
                json(Json {
                    ignoreUnknownKeys = true
                    isLenient = true
                })
            }
        }
    }
}
