package jp.simplespace.ai

import com.google.genai.Client
import com.google.genai.types.Content
import com.google.genai.types.GenerateContentConfig
import com.google.genai.types.Part
import com.google.genai.types.Schema
import com.google.genai.types.ThinkingConfig
import com.google.genai.types.Type
import io.ktor.server.config.ApplicationConfig
import jp.simplespace.discord.OhagiBot
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json

data class GeminiClientSettings(
    val provider: String = "enterprise",
    val model: String = DEFAULT_MODEL,
    val googleApiKey: String? = null,
    val gcpProjectId: String? = null,
    val gcpLocation: String? = null,
    val thinkingLevel: String = DEFAULT_EVAL_THINKING_LEVEL,
    val appealThinkingLevel: String = DEFAULT_APPEAL_THINKING_LEVEL,
    val maxOutputTokens: Int = DEFAULT_EVAL_MAX_OUTPUT_TOKENS,
    val appealMaxOutputTokens: Int = DEFAULT_APPEAL_MAX_OUTPUT_TOKENS,
) {
    companion object {
        const val DEFAULT_MODEL = "gemini-4-hydrogen"
        const val DEFAULT_EVAL_THINKING_LEVEL = "LOW"
        const val DEFAULT_APPEAL_THINKING_LEVEL = "MEDIUM"
        const val DEFAULT_EVAL_MAX_OUTPUT_TOKENS = 768
        const val DEFAULT_APPEAL_MAX_OUTPUT_TOKENS = 1536

        fun fromApplicationConfig(appConfig: ApplicationConfig): GeminiClientSettings {
            val aiSection = runCatching { appConfig.config("ai") }.getOrNull()
            fun prop(key: String, envKey: String): String? {
                val fromYaml = runCatching { aiSection?.propertyOrNull(key)?.getString() }.getOrNull()
                    ?.trim()
                    ?.takeIf { it.isNotEmpty() && !it.startsWith("$") }
                if (fromYaml != null) return fromYaml
                return System.getenv(envKey)?.trim()?.takeIf { it.isNotEmpty() }
            }

            val provider = prop("provider", "AI_PROVIDER") ?: "enterprise"
            val model = prop("model", "AI_MODEL") ?: DEFAULT_MODEL
            val apiKey = prop("google_api_key", "GOOGLE_API_KEY")
                ?: System.getenv("GEMINI_API_KEY")?.trim()?.takeIf { it.isNotEmpty() }
            val projectId = prop("gcp_project_id", "GCP_PROJECT_ID")
                ?: System.getenv("GOOGLE_CLOUD_PROJECT")?.trim()?.takeIf { it.isNotEmpty() }
            val location = prop("gcp_location", "GCP_LOCATION")
                ?: System.getenv("GOOGLE_CLOUD_LOCATION")?.trim()?.takeIf { it.isNotEmpty() }
                ?: "global"
            val thinkingLevel = (prop("thinking_level", "AI_THINKING_LEVEL") ?: DEFAULT_EVAL_THINKING_LEVEL).uppercase()
            val appealThinkingLevel = (prop("appeal_thinking_level", "AI_APPEAL_THINKING_LEVEL") ?: DEFAULT_APPEAL_THINKING_LEVEL).uppercase()
            val maxOutputTokens = prop("max_output_tokens", "AI_MAX_OUTPUT_TOKENS")?.toIntOrNull()?.coerceIn(128, 8192)
                ?: DEFAULT_EVAL_MAX_OUTPUT_TOKENS
            val appealMaxOutputTokens = prop("appeal_max_output_tokens", "AI_APPEAL_MAX_OUTPUT_TOKENS")?.toIntOrNull()?.coerceIn(256, 8192)
                ?: DEFAULT_APPEAL_MAX_OUTPUT_TOKENS

            return GeminiClientSettings(
                provider = provider.lowercase(),
                model = model,
                googleApiKey = apiKey,
                gcpProjectId = projectId,
                gcpLocation = location,
                thinkingLevel = thinkingLevel,
                appealThinkingLevel = appealThinkingLevel,
                maxOutputTokens = maxOutputTokens,
                appealMaxOutputTokens = appealMaxOutputTokens,
            )
        }
    }
}

class GeminiModerationClient(
    private val settings: GeminiClientSettings,
) : AiModerationClient {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    private val client: Client? by lazy { buildClient() }

    override val isConfigured: Boolean
        get() = client != null

    private fun buildClient(): Client? {
        return runCatching {
            when (settings.provider) {
                "gemini_api", "developer", "ai_studio" -> {
                    val key = settings.googleApiKey
                    if (key.isNullOrBlank()) {
                        OhagiBot.logger.warn("Gemini Developer APIキーが未設定のためAI違反判定は無効です (GOOGLE_API_KEY)")
                        null
                    } else {
                        Client.builder().apiKey(key).build()
                    }
                }
                else -> {
                    // Gemini Enterprise Agent Platform (旧 Vertex AI)
                    // 1) Project + Location (+ 任意で API Key / または ADC・サービスアカウント JSON)
                    // 2) Express Mode (API Key のみ + enterprise(true))
                    val project = settings.gcpProjectId
                    val location = settings.gcpLocation ?: "global"
                    val apiKey = settings.googleApiKey
                    when {
                        !project.isNullOrBlank() && !apiKey.isNullOrBlank() -> {
                            Client.builder()
                                .project(project)
                                .location(location)
                                .apiKey(apiKey)
                                .enterprise(true)
                                .build()
                        }
                        !project.isNullOrBlank() -> {
                            Client.builder()
                                .project(project)
                                .location(location)
                                .enterprise(true)
                                .build()
                        }
                        !apiKey.isNullOrBlank() -> {
                            Client.builder()
                                .apiKey(apiKey)
                                .enterprise(true)
                                .build()
                        }
                        else -> {
                            OhagiBot.logger.warn(
                                "Gemini Platform (enterprise) の認証情報が未設定のためAI違反判定は無効です (GCP_PROJECT_ID または GOOGLE_API_KEY を設定してください)"
                            )
                            null
                        }
                    }
                }
            }
        }.onFailure {
            OhagiBot.logger.error("Gemini SDK Client の初期化に失敗しました (provider={})", settings.provider, it)
        }.getOrNull()
    }

    override suspend fun evaluate(request: ModerationEvaluationRequest): Pair<ModerationEvaluationResult, String>? {
        val activeClient = client ?: return null
        val userContent = buildUserContent(request)
        val config = buildEvaluationConfig()

        return withContext(Dispatchers.IO) {
            runCatching {
                val response = activeClient.models.generateContent(settings.model, userContent, config)
                val rawText = response.text()?.trim()
                if (rawText.isNullOrEmpty()) {
                    OhagiBot.logger.warn("Gemini API から空レスポンスが返りました")
                    return@runCatching null
                }
                val parsed = parseResponseJson(rawText)
                parsed to rawText
            }.onFailure {
                OhagiBot.logger.error("Gemini API 違反判定呼び出しに失敗しました (model={})", settings.model, it)
            }.getOrNull()
        }
    }

    internal fun buildEvaluationConfig(): GenerateContentConfig {
        val systemInstruction = Content.fromParts(Part.fromText(ModerationPromptBuilder.buildSystemInstruction()))
        return GenerateContentConfig.builder()
            .systemInstruction(systemInstruction)
            .responseMimeType("application/json")
            .responseSchema(responseSchema)
            .thinkingConfig(
                ThinkingConfig.builder()
                    .thinkingLevel(settings.thinkingLevel)
                    .includeThoughts(false)
                    .build()
            )
            .maxOutputTokens(settings.maxOutputTokens)
            .temperature(0.2f)
            .candidateCount(1)
            .build()
    }

    internal fun buildAppealConfig(): GenerateContentConfig {
        val systemInstruction = Content.fromParts(Part.fromText(ModerationPromptBuilder.buildAppealSystemInstruction()))
        return GenerateContentConfig.builder()
            .systemInstruction(systemInstruction)
            .responseMimeType("application/json")
            .responseSchema(appealResponseSchema)
            .thinkingConfig(
                ThinkingConfig.builder()
                    .thinkingLevel(settings.appealThinkingLevel)
                    .includeThoughts(false)
                    .build()
            )
            .maxOutputTokens(settings.appealMaxOutputTokens)
            .temperature(0.2f)
            .candidateCount(1)
            .build()
    }

    internal fun buildUserContent(request: ModerationEvaluationRequest): Content {
        val parts = mutableListOf<Part>()
        parts += Part.fromText(ModerationPromptBuilder.buildUserPrompt(request))
        request.attachments.forEach { att ->
            val bytes = att.bytes
            if (bytes != null && bytes.isNotEmpty()) {
                parts += Part.fromBytes(bytes, att.mimeType)
            }
        }
        return Content.fromParts(*parts.toTypedArray())
    }

    internal fun parseResponseJson(rawJson: String): ModerationEvaluationResult {
        val cleaned = rawJson
            .removePrefix("```json")
            .removePrefix("```")
            .removeSuffix("```")
            .trim()
        return json.decodeFromString<ModerationEvaluationResult>(cleaned)
    }

    override suspend fun reviewAppeal(request: AppealReviewRequest): Pair<AppealReviewResult, String>? {
        val activeClient = client ?: return null
        val userPrompt = ModerationPromptBuilder.buildAppealUserPrompt(request)
        val config = buildAppealConfig()

        return withContext(Dispatchers.IO) {
            runCatching {
                val response = activeClient.models.generateContent(settings.model, userPrompt, config)
                val rawText = response.text()?.trim()
                if (rawText.isNullOrEmpty()) {
                    OhagiBot.logger.warn("Gemini API 異議審査から空レスポンスが返りました")
                    return@runCatching null
                }
                val parsed = parseAppealResponseJson(rawText)
                parsed to rawText
            }.onFailure {
                OhagiBot.logger.error("Gemini API 異議審査呼び出しに失敗しました (model={})", settings.model, it)
            }.getOrNull()
        }
    }

    internal fun parseAppealResponseJson(rawJson: String): AppealReviewResult {
        val cleaned = rawJson
            .removePrefix("```json")
            .removePrefix("```")
            .removeSuffix("```")
            .trim()
        return json.decodeFromString<AppealReviewResult>(cleaned)
    }

    companion object {
        private val responseSchema: Schema by lazy {
            Schema.builder()
                .type(Type.Known.OBJECT)
                .properties(
                    mapOf(
                        "is_violation" to Schema.builder().type(Type.Known.BOOLEAN).build(),
                        "base_delta" to Schema.builder().type(Type.Known.NUMBER).build(),
                        "severity" to Schema.builder().type(Type.Known.STRING).build(),
                        "category" to Schema.builder().type(Type.Known.STRING).build(),
                        "reason_ja" to Schema.builder().type(Type.Known.STRING).build(),
                        "victim_user_id" to Schema.builder().type(Type.Known.STRING).nullable(true).build(),
                        "suggest_ban_ticket" to Schema.builder().type(Type.Known.BOOLEAN).build(),
                        "ticket_reason_ja" to Schema.builder().type(Type.Known.STRING).nullable(true).build(),
                    )
                )
                .required(
                    listOf(
                        "is_violation",
                        "base_delta",
                        "severity",
                        "category",
                        "reason_ja",
                        "suggest_ban_ticket",
                    )
                )
                .build()
        }

        private val appealResponseSchema: Schema by lazy {
            Schema.builder()
                .type(Type.Known.OBJECT)
                .properties(
                    mapOf(
                        "intent" to Schema.builder().type(Type.Known.STRING).build(),
                        "target_event_id" to Schema.builder().type(Type.Known.INTEGER).nullable(true).build(),
                        "decision" to Schema.builder().type(Type.Known.STRING).build(),
                        "score_adjustment" to Schema.builder().type(Type.Known.NUMBER).build(),
                        "reply_ja" to Schema.builder().type(Type.Known.STRING).build(),
                        "audit_reason_ja" to Schema.builder().type(Type.Known.STRING).build(),
                    )
                )
                .required(
                    listOf(
                        "intent",
                        "decision",
                        "score_adjustment",
                        "reply_ja",
                        "audit_reason_ja",
                    )
                )
                .build()
        }
    }
}
