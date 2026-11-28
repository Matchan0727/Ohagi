package jp.simplespace.ai

import com.google.genai.Client
import com.google.genai.types.Content
import com.google.genai.types.GenerateContentConfig
import com.google.genai.types.Part
import com.google.genai.types.Schema
import com.google.genai.types.ThinkingConfig
import com.google.genai.types.Type
import io.ktor.server.config.ApplicationConfig
import jp.simplespace.db.AiTaskType
import jp.simplespace.discord.OhagiBot
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json

data class GeminiTaskOverrideSettings(
    val model: String? = null,
    val thinkingLevel: String? = null,
    val maxOutputTokens: Int? = null,
)

data class GeminiClientSettings(
    val provider: String = "enterprise",
    val model: String = DEFAULT_MODEL,
    val googleApiKey: String? = null,
    val gcpProjectId: String? = null,
    val gcpLocation: String? = null,
    val defaultThinkingLevel: String? = null,
    val taskOverrides: Map<AiTaskType, GeminiTaskOverrideSettings> = emptyMap(),
) {
    companion object {
        const val DEFAULT_MODEL = "gemini-4-hydrogen"
        const val DEFAULT_EVAL_THINKING_LEVEL = "LOW"
        const val DEFAULT_APPEAL_THINKING_LEVEL = "MEDIUM"
        const val DEFAULT_EVAL_MAX_OUTPUT_TOKENS = 1024
        const val DEFAULT_APPEAL_MAX_OUTPUT_TOKENS = 1536

        val RECOMMENDED_MODELS = listOf(
            "gemini-4-hydrogen",
            "gemini-3.5-flash-lite",
            "gemini-4-argon",
            "gemini-4-neon",
            "gemini-4-carbon",
            "gemini-3.1-flash-lite",
        )

        val ALLOWED_THINKING_LEVELS = listOf("MINIMAL", "LOW", "MEDIUM", "HIGH", "XHIGH")

        fun fromApplicationConfig(appConfig: ApplicationConfig): GeminiClientSettings {
            val aiSection = runCatching { appConfig.config("ai") }.getOrNull()
            val tasksSection = runCatching { aiSection?.config("tasks") }.getOrNull()

            fun cleanVal(raw: String?): String? =
                raw?.trim()?.takeIf { it.isNotEmpty() && !it.startsWith("$") }

            fun prop(key: String, envKey: String): String? {
                val fromYaml = cleanVal(runCatching { aiSection?.propertyOrNull(key)?.getString() }.getOrNull())
                if (fromYaml != null) return fromYaml
                return cleanVal(System.getenv(envKey))
            }

            fun taskProp(taskKey: String, field: String, vararg envKeys: String): String? {
                val taskSec = runCatching { tasksSection?.config(taskKey) }.getOrNull()
                val fromNestedYaml = cleanVal(runCatching { taskSec?.propertyOrNull(field)?.getString() }.getOrNull())
                if (fromNestedYaml != null) return fromNestedYaml
                for (env in envKeys) {
                    val fromEnv = cleanVal(System.getenv(env))
                    if (fromEnv != null) return fromEnv
                }
                return null
            }

            val provider = prop("provider", "AI_PROVIDER") ?: "enterprise"
            val defaultModel = prop("model", "AI_MODEL") ?: DEFAULT_MODEL
            val apiKey = prop("google_api_key", "GOOGLE_API_KEY")
                ?: cleanVal(System.getenv("GEMINI_API_KEY"))
            val projectId = prop("gcp_project_id", "GCP_PROJECT_ID")
                ?: cleanVal(System.getenv("GOOGLE_CLOUD_PROJECT"))
            val location = prop("gcp_location", "GCP_LOCATION")
                ?: cleanVal(System.getenv("GOOGLE_CLOUD_LOCATION"))
                ?: "global"
            val defaultThinking = prop("thinking_level", "AI_THINKING_LEVEL")?.uppercase()

            val modModel = taskProp("moderation", "model", "AI_MODERATION_MODEL")
            val modThinking = taskProp("moderation", "thinking_level", "AI_MODERATION_THINKING_LEVEL")?.uppercase()
            val modMaxTokens = (taskProp("moderation", "max_output_tokens", "AI_MODERATION_MAX_OUTPUT_TOKENS", "AI_MAX_OUTPUT_TOKENS")
                ?: prop("max_output_tokens", "AI_MAX_OUTPUT_TOKENS"))
                ?.toIntOrNull()?.coerceIn(128, 8192)

            val appealModel = taskProp("appeal", "model", "AI_APPEAL_MODEL")
            val appealThinking = (taskProp("appeal", "thinking_level", "AI_APPEAL_THINKING_LEVEL")
                ?: prop("appeal_thinking_level", "AI_APPEAL_THINKING_LEVEL"))?.uppercase()
            val appealMaxTokens = (taskProp("appeal", "max_output_tokens", "AI_APPEAL_MAX_OUTPUT_TOKENS")
                ?: prop("appeal_max_output_tokens", "AI_APPEAL_MAX_OUTPUT_TOKENS"))
                ?.toIntOrNull()?.coerceIn(256, 8192)

            val overrides = mapOf(
                AiTaskType.MODERATION to GeminiTaskOverrideSettings(
                    model = modModel,
                    thinkingLevel = modThinking,
                    maxOutputTokens = modMaxTokens,
                ),
                AiTaskType.APPEAL to GeminiTaskOverrideSettings(
                    model = appealModel,
                    thinkingLevel = appealThinking,
                    maxOutputTokens = appealMaxTokens,
                ),
            )

            return GeminiClientSettings(
                provider = provider.lowercase(),
                model = defaultModel,
                googleApiKey = apiKey,
                gcpProjectId = projectId,
                gcpLocation = location,
                defaultThinkingLevel = defaultThinking,
                taskOverrides = overrides,
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

    override fun resolveTaskConfig(
        task: AiTaskType,
        overrideModel: String?,
        overrideThinkingLevel: String?,
    ): AiTaskRuntimeConfig {
        val taskOverride = settings.taskOverrides[task]
        val resolvedModel = overrideModel?.trim()?.takeIf { it.isNotEmpty() }
            ?: taskOverride?.model?.trim()?.takeIf { it.isNotEmpty() }
            ?: settings.model.ifBlank { GeminiClientSettings.DEFAULT_MODEL }

        val resolvedThinking = overrideThinkingLevel?.trim()?.takeIf { it.isNotEmpty() }?.uppercase()
            ?: taskOverride?.thinkingLevel?.trim()?.takeIf { it.isNotEmpty() }?.uppercase()
            ?: settings.defaultThinkingLevel?.trim()?.takeIf { it.isNotEmpty() }?.uppercase()
            ?: task.defaultThinkingLevel

        val resolvedMaxTokens = taskOverride?.maxOutputTokens ?: task.defaultMaxOutputTokens

        return AiTaskRuntimeConfig(
            model = resolvedModel,
            thinkingLevel = resolvedThinking,
            maxOutputTokens = resolvedMaxTokens,
        )
    }

    override suspend fun evaluate(request: ModerationEvaluationRequest): Pair<ModerationEvaluationResult, String>? {
        val activeClient = client ?: return null
        val runtime = resolveTaskConfig(
            task = AiTaskType.MODERATION,
            overrideModel = request.overrideModel,
            overrideThinkingLevel = request.overrideThinkingLevel,
        )
        val userContent = buildUserContent(request)
        val config = buildEvaluationConfig(runtime)

        return withContext(Dispatchers.IO) {
            runCatching {
                val response = activeClient.models.generateContent(runtime.model, userContent, config)
                val rawText = response.text()?.trim()
                if (rawText.isNullOrEmpty()) {
                    OhagiBot.logger.warn("Gemini API から空レスポンスが返りました (model={})", runtime.model)
                    return@runCatching null
                }
                val parsed = parseResponseJson(rawText)
                parsed to rawText
            }.onFailure {
                OhagiBot.logger.error(
                    "Gemini API 違反判定呼び出しに失敗しました (model={}, thinking={})",
                    runtime.model,
                    runtime.thinkingLevel,
                    it,
                )
            }.getOrNull()
        }
    }

    internal fun buildEvaluationConfig(
        runtime: AiTaskRuntimeConfig = resolveTaskConfig(AiTaskType.MODERATION),
    ): GenerateContentConfig {
        val systemInstruction = Content.fromParts(Part.fromText(ModerationPromptBuilder.buildSystemInstruction()))
        return GenerateContentConfig.builder()
            .systemInstruction(systemInstruction)
            .responseMimeType("application/json")
            .responseSchema(responseSchema)
            .thinkingConfig(
                ThinkingConfig.builder()
                    .thinkingLevel(runtime.thinkingLevel)
                    .includeThoughts(false)
                    .build()
            )
            .maxOutputTokens(runtime.maxOutputTokens)
            .temperature(0.2f)
            .candidateCount(1)
            .build()
    }

    internal fun buildAppealConfig(
        runtime: AiTaskRuntimeConfig = resolveTaskConfig(AiTaskType.APPEAL),
    ): GenerateContentConfig {
        val systemInstruction = Content.fromParts(Part.fromText(ModerationPromptBuilder.buildAppealSystemInstruction()))
        return GenerateContentConfig.builder()
            .systemInstruction(systemInstruction)
            .responseMimeType("application/json")
            .responseSchema(appealResponseSchema)
            .thinkingConfig(
                ThinkingConfig.builder()
                    .thinkingLevel(runtime.thinkingLevel)
                    .includeThoughts(false)
                    .build()
            )
            .maxOutputTokens(runtime.maxOutputTokens)
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
        val runtime = resolveTaskConfig(
            task = AiTaskType.APPEAL,
            overrideModel = request.overrideModel,
            overrideThinkingLevel = request.overrideThinkingLevel,
        )
        val userPrompt = ModerationPromptBuilder.buildAppealUserPrompt(request)
        val config = buildAppealConfig(runtime)

        return withContext(Dispatchers.IO) {
            runCatching {
                val response = activeClient.models.generateContent(runtime.model, userPrompt, config)
                val rawText = response.text()?.trim()
                if (rawText.isNullOrEmpty()) {
                    OhagiBot.logger.warn("Gemini API 異議審査から空レスポンスが返りました (model={})", runtime.model)
                    return@runCatching null
                }
                val parsed = parseAppealResponseJson(rawText)
                parsed to rawText
            }.onFailure {
                OhagiBot.logger.error(
                    "Gemini API 異議審査呼び出しに失敗しました (model={}, thinking={})",
                    runtime.model,
                    runtime.thinkingLevel,
                    it,
                )
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
            val violationItemSchema = Schema.builder()
                .type(Type.Known.OBJECT)
                .properties(
                    mapOf(
                        "message_indices" to Schema.builder()
                            .type(Type.Known.ARRAY)
                            .items(Schema.builder().type(Type.Known.INTEGER).build())
                            .build(),
                        "base_delta" to Schema.builder().type(Type.Known.NUMBER).build(),
                        "severity" to Schema.builder().type(Type.Known.STRING).build(),
                        "category" to Schema.builder().type(Type.Known.STRING).build(),
                        "reason_ja" to Schema.builder().type(Type.Known.STRING).build(),
                    )
                )
                .required(
                    listOf(
                        "message_indices",
                        "base_delta",
                        "severity",
                        "category",
                        "reason_ja",
                    )
                )
                .build()

            Schema.builder()
                .type(Type.Known.OBJECT)
                .properties(
                    mapOf(
                        "violations" to Schema.builder()
                            .type(Type.Known.ARRAY)
                            .items(violationItemSchema)
                            .build(),
                        "victim_user_id" to Schema.builder().type(Type.Known.STRING).nullable(true).build(),
                        "suggest_ban_ticket" to Schema.builder().type(Type.Known.BOOLEAN).build(),
                        "ticket_reason_ja" to Schema.builder().type(Type.Known.STRING).nullable(true).build(),
                    )
                )
                .required(
                    listOf(
                        "violations",
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
