package jp.simplespace.ai

import jp.simplespace.db.AppealDecision
import jp.simplespace.db.WatchAiFeedback
import jp.simplespace.db.WatchAiRule
import jp.simplespace.db.WatchScoreEvent
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

data class ModerationAttachment(
    val fileName: String,
    val mimeType: String,
    val sizeBytes: Long,
    val bytes: ByteArray? = null,
    val skippedReason: String? = null,
) {
    val hasInlineData: Boolean get() = bytes != null && bytes.isNotEmpty()
}

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
    val attachments: List<ModerationAttachment> = emptyList(),
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

data class AppealReviewRequest(
    val guildName: String,
    val channelName: String,
    val targetUserId: String,
    val targetDisplayName: String,
    val currentScore: Double,
    val personaNote: String?,
    val rules: List<WatchAiRule>,
    val feedbacks: List<WatchAiFeedback>,
    val recentViolations: List<WatchScoreEvent>,
    val alreadyReviewedEventIds: Set<Long>,
    val contextMessages: List<ContextMessage>,
    val appealMessage: String,
)

@Serializable
data class AppealReviewResult(
    val intent: String = "UNRELATED",
    @SerialName("target_event_id")
    val targetEventId: Long? = null,
    val decision: String = "EXPLAIN_ONLY",
    @SerialName("score_adjustment")
    val scoreAdjustment: Double = 0.0,
    @SerialName("reply_ja")
    val replyJa: String = "",
    @SerialName("audit_reason_ja")
    val auditReasonJa: String = "",
) {
    fun resolvedDecision(): AppealDecision = AppealDecision.fromString(decision)

    /**
     * コード側の安全ガード:
     * - 減額（REDUCED / REVOKED）は対象違反イベントの元加算値（originalDelta）を絶対に超えない
     * - 既に審査済みイベントへの減額は 0.0 に強制
     * - 棄却・説明のみ（UPHELD / EXPLAIN_ONLY）は 0.0 に強制
     * - 加重（ESCALATED）は 0.0..min(10.0, maxDeltaPerEval) に制限
     */
    fun computeSafeAdjustment(
        targetEvent: WatchScoreEvent?,
        alreadyReviewed: Boolean,
        maxDeltaPerEval: Double,
    ): Pair<AppealDecision, Double> {
        val dec = resolvedDecision()
        return when (dec) {
            AppealDecision.EXPLAIN_ONLY, AppealDecision.UPHELD -> dec to 0.0
            AppealDecision.REVOKED -> {
                if (targetEvent == null || alreadyReviewed || targetEvent.delta <= 0.0) {
                    AppealDecision.UPHELD to 0.0
                } else {
                    AppealDecision.REVOKED to -targetEvent.delta
                }
            }
            AppealDecision.REDUCED -> {
                if (targetEvent == null || alreadyReviewed || targetEvent.delta <= 0.0) {
                    AppealDecision.UPHELD to 0.0
                } else {
                    val requestedReduction = (-scoreAdjustment).coerceIn(0.0, targetEvent.delta)
                    if (requestedReduction <= 0.0) {
                        AppealDecision.UPHELD to 0.0
                    } else if (requestedReduction >= targetEvent.delta - 0.05) {
                        AppealDecision.REVOKED to -targetEvent.delta
                    } else {
                        AppealDecision.REDUCED to -requestedReduction
                    }
                }
            }
            AppealDecision.ESCALATED -> {
                val cap = minOf(10.0, maxDeltaPerEval.coerceAtLeast(0.0))
                val penalty = scoreAdjustment.coerceIn(0.0, cap)
                if (penalty <= 0.0) AppealDecision.UPHELD to 0.0 else AppealDecision.ESCALATED to penalty
            }
        }
    }
}

interface AiModerationClient {
    val isConfigured: Boolean
    suspend fun evaluate(request: ModerationEvaluationRequest): Pair<ModerationEvaluationResult, String>?
    suspend fun reviewAppeal(request: AppealReviewRequest): Pair<AppealReviewResult, String>?
}
