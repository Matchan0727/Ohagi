package jp.simplespace.audio

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandler
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.forms.MultiPartFormDataContent
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.headersOf
import io.ktor.client.request.HttpRequestData
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RvcApiClientTest {

    @Test
    fun separate_sends_multipart_request() = runBlocking {
        var capturedRequest: HttpRequestData? = null
        val client = createClient { request ->
            capturedRequest = request
            respond(
                content = """{"path":"separate.wav"}""",
                headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
            )
        }

        client.use {
            val response = it.separate("sample".toByteArray(), "sample.wav")

            assertEquals("separate.wav", response.jsonObject["path"]!!.jsonPrimitive.content)
        }

        val request = capturedRequest ?: error("request not captured")
        assertEquals(HttpMethod.Post, request.method)
        assertEquals("/api/v1/separate", request.url.encodedPath)
        assertTrue(request.body is MultiPartFormDataContent)
    }

    @Test
    fun convert_and_cover_use_the_specified_fields() = runBlocking {
        val paths = mutableListOf<String>()
        val client = createClient { request ->
            paths += request.url.encodedPath
            respond(
                content = """{"ok":true}""",
                headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
            )
        }

        client.use {
            it.convert("sample".toByteArray(), "sample.wav", modelName = "voice-model", pitch = 2)
            it.createCover("sample".toByteArray(), "sample.wav", rvcModel = "cover-model")
        }

        assertEquals(listOf("/api/v1/convert", "/api/v1/cover"), paths)
    }

    @Test
    fun list_models_and_root_return_json() = runBlocking {
        val client = createClient { request ->
            when (request.url.encodedPath) {
                "/api/v1/models" -> respond(
                    content = """{"models":["A","B"]}""",
                    headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
                )
                "/" -> respond(
                    content = """{"status":"ok"}""",
                    headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
                )
                else -> error("unexpected path: ${request.url.encodedPath}")
            }
        }

        client.use {
            val models = it.listModels()
            val rootResponse = it.root()

            assertEquals("A", models.jsonObject["models"]!!.jsonArray[0].jsonPrimitive.content)
            assertEquals("ok", rootResponse.jsonObject["status"]!!.jsonPrimitive.content)
        }
    }

    private fun createClient(
        handler: MockRequestHandler,
    ): RvcApiClient {
        val engine = MockEngine(handler)
        val httpClient = HttpClient(engine) {
            install(ContentNegotiation) {
                json(Json { ignoreUnknownKeys = true })
            }
        }

        return RvcApiClient(
            baseUrl = "http://localhost:8000",
            httpClient = httpClient,
        )
    }
}
