package jp.simplespace.ai

import jp.simplespace.db.WatchAiFeedback
import jp.simplespace.db.WatchAiRule
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class ContextMessage(
    val authorId: String,
    val authorName: String,
    val isTarget: Boolean,
    val content: String,
    val timestampEpochSec: Long,
)

data class ModerationEvaluationRequest(
    val guildName: String,
    val channelName: String,
    val targetUserId: String,
    val targetDisplayName: String,
    val personaNote: String?,
    val rules: List<WatchAiRule>,
    val feedbacks: List<WatchAiFeedback>,
    val contextMessages: List<ContextMessage>,
    val targetMessages: List<String>,
)

@Serializable
data class ModerationEvaluationResult(
    @SerialName("is_violation")
    val isViolation: Boolean = false,
    @SerialName("base_delta")
    val baseDelta: Double = 0.0,
    val severity: String = "NONE",
    val category: String = "NONE",
    @SerialName("reason_ja")
    val reasonJa: String = "",
    @SerialName("victim_user_id")
    val victimUserId: String? = null,
    @SerialName("suggest_ban_ticket")
    val suggestBanTicket: Boolean = false,
    @SerialName("ticket_reason_ja")
    val ticketReasonJa: String? = null,
) {
    /**
     * サーバー設定の感度倍率・最大加算キャップを適用した最終スコア増分を計算する。
     */
    fun computeFinalDelta(sensitivity: Double, maxDeltaPerEval: Double): Double {
        if (!isViolation || baseDelta <= 0.0) return 0.0
        val scaled = baseDelta * sensitivity.coerceIn(0.1, 5.0)
        return scaled.coerceIn(0.0, maxDeltaPerEval.coerceAtLeast(0.0))
    }
}

interface AiModerationClient {
    val isConfigured: Boolean
    suspend fun evaluate(request: ModerationEvaluationRequest): Pair<ModerationEvaluationResult, String>?
}
