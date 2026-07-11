package jp.simplespace.audio

import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertTrue

class RvcApiClientIntegrationTest {

    @Test
    fun root_and_models_are_reachable() = runBlocking {
        createClient().use { client ->
            val root = client.root()
            val models = client.listModels()

            assertTrue(root.toString().isNotBlank())
            assertTrue(models.toString().isNotBlank())
        }
    }

    @Test
    fun separate_can_process_a_real_audio_file() = runBlocking {
        createClient().use { client ->
            val audio = createSilentWav()
            try {
                val response = client.separate(audio)
                assertTrue(response.toString().isNotBlank())
            } finally {
                Files.deleteIfExists(audio)
            }
        }
    }

    @Test
    fun convert_can_process_a_real_audio_file() = runBlocking {
        val modelName = requiredEnv("RVC_TEST_CONVERT_MODEL")
        createClient().use { client ->
            val audio = createSilentWav()
            try {
                val response = client.convert(audio, modelName = modelName)
                assertTrue(response.toString().isNotBlank())
            } finally {
                Files.deleteIfExists(audio)
            }
        }
    }

    @Test
    fun cover_can_process_a_real_audio_file() = runBlocking {
        val rvcModel = requiredEnv("RVC_TEST_COVER_MODEL")
        createClient().use { client ->
            val audio = createSilentWav()
            try {
                val response = client.createCover(audio, rvcModel = rvcModel)
                assertTrue(response.toString().isNotBlank())
            } finally {
                Files.deleteIfExists(audio)
            }
        }
    }

    @Test
    fun download_can_fetch_a_saved_file() = runBlocking {
        val filePath = requiredEnv("RVC_TEST_DOWNLOAD_FILE_PATH")
        createClient().use { client ->
            val bytes = client.download(filePath)
            assertTrue(bytes.isNotEmpty())
        }
    }

    private fun createClient(): RvcApiClient {
        val baseUrl = requiredEnv("RVC_API_BASE_URL")
        return RvcApiClient(baseUrl = baseUrl)
    }

    private fun requiredEnv(name: String): String {
        val value = System.getenv(name)
        assumeTrue("$name is not set", !value.isNullOrBlank())
        return value!!.trim()
    }

    private fun createSilentWav(): Path {
        val file = Files.createTempFile("rvc-client-test", ".wav")
        val sampleRate = 16_000
        val durationMillis = 200
        val samples = sampleRate * durationMillis / 1000
        val bytesPerSample = 2
        val dataSize = samples * bytesPerSample

        val buffer = ByteBuffer.allocate(44 + dataSize).order(ByteOrder.LITTLE_ENDIAN)
        buffer.put("RIFF".toByteArray(StandardCharsets.US_ASCII))
        buffer.putInt(36 + dataSize)
        buffer.put("WAVE".toByteArray(StandardCharsets.US_ASCII))
        buffer.put("fmt ".toByteArray(StandardCharsets.US_ASCII))
        buffer.putInt(16)
        buffer.putShort(1.toShort())
        buffer.putShort(1.toShort())
        buffer.putInt(sampleRate)
        buffer.putInt(sampleRate * bytesPerSample)
        buffer.putShort(bytesPerSample.toShort())
        buffer.putShort(16.toShort())
        buffer.put("data".toByteArray(StandardCharsets.US_ASCII))
        buffer.putInt(dataSize)
        repeat(samples) {
            buffer.putShort(0)
        }
        buffer.flip()
        Files.write(file, buffer.array())
        file.toFile().deleteOnExit()
        return file
    }
}
