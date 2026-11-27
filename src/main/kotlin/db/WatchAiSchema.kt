package jp.simplespace.db

import kotlinx.serialization.Serializable
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.core.or
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction

enum class AiRuleType(val displayName: String) {
    VIOLATION("違反ルール (NG傾向)"),
    ALLOW("許容ルール (身内ネタ等)");

    companion object {
        fun fromString(v: String?): AiRuleType =
            entries.firstOrNull { it.name.equals(v, ignoreCase = true) } ?: VIOLATION
    }
}

enum class AiWeightHint(val displayName: String) {
    LOW("軽度"),
    MEDIUM("中度"),
    HIGH("重度");

    companion object {
        fun fromString(v: String?): AiWeightHint =
            entries.firstOrNull { it.name.equals(v, ignoreCase = true) } ?: MEDIUM
    }
}

enum class AiFeedbackVerdict(val displayName: String) {
    CORRECT("適正判定"),
    FALSE_POSITIVE("過剰判定 (誤爆)"),
    UNDERESTIMATED("甘すぎた"),
    MISSED("見逃し");

    companion object {
        fun fromString(v: String?): AiFeedbackVerdict =
            entries.firstOrNull { it.name.equals(v, ignoreCase = true) } ?: FALSE_POSITIVE
    }
}

enum class AppealDecision(val displayName: String) {
    EXPLAIN_ONLY("罰則理由の説明"),
    UPHELD("異議棄却（罰則維持）"),
    REDUCED("異議一部容認（罰則軽減）"),
    REVOKED("異議容認（違反撤回）"),
    ESCALATED("異議棄却＋加重ペナルティ");

    val isFinalizedReview: Boolean
        get() = this != EXPLAIN_ONLY

    companion object {
        fun fromString(v: String?): AppealDecision =
            entries.firstOrNull { it.name.equals(v, ignoreCase = true) } ?: UPHELD
    }
}

@Serializable
data class WatchAppeal(
    val id: Long,
    val guildId: String,
    val targetId: Long,
    val cycleId: Long,
    val scoreEventId: Long?,
    val appealChannelId: String,
    val appealMessageId: String,
    val appealContent: String,
    val decision: AppealDecision,
    val appliedDelta: Double,
    val botReply: String,
    val createdAt: Long,
)

@Serializable
data class WatchAiRule(
    val id: Long,
    val guildId: String,
    val targetId: Long?,
    val ruleType: AiRuleType,
    val description: String,
    val weightHint: AiWeightHint,
    val createdBy: String,
    val createdAt: Long,
)

@Serializable
data class WatchAiFeedback(
    val id: Long,
    val guildId: String,
    val targetId: Long?,
    val scoreEventId: Long?,
    val messageContent: String,
    val contextExcerpt: String?,
    val originalDelta: Double,
    val expectedDelta: Double,
    val verdict: AiFeedbackVerdict,
    val note: String?,
    val reviewerUserId: String,
    val createdAt: Long,
)

class WatchAiService(private val database: Database) {
    object WatchAiRules : Table("watch_ai_rules") {
        val id = long("id").autoIncrement()
        val guildId = varchar("guild_id", 64).index("idx_watch_ai_rules_guild")
        val targetId = long("target_id").nullable()
        val ruleType = varchar("rule_type", 16)
        val description = text("description")
        val weightHint = varchar("weight_hint", 16).default(AiWeightHint.MEDIUM.name)
        val createdBy = varchar("created_by", 64)
        val createdAt = long("created_at")

        override val primaryKey = PrimaryKey(id)
    }

    object WatchAiFeedbacks : Table("watch_ai_feedbacks") {
        val id = long("id").autoIncrement()
        val guildId = varchar("guild_id", 64).index("idx_watch_ai_feedbacks_guild")
        val targetId = long("target_id").nullable()
        val scoreEventId = long("score_event_id").nullable()
        val messageContent = text("message_content")
        val contextExcerpt = text("context_excerpt").nullable()
        val originalDelta = double("original_delta")
        val expectedDelta = double("expected_delta")
        val verdict = varchar("verdict", 24)
        val note = text("note").nullable()
        val reviewerUserId = varchar("reviewer_user_id", 64)
        val createdAt = long("created_at")

        override val primaryKey = PrimaryKey(id)
    }

    object WatchAppeals : Table("watch_appeals") {
        val id = long("id").autoIncrement()
        val guildId = varchar("guild_id", 64).index("idx_watch_appeals_guild")
        val targetId = long("target_id").index("idx_watch_appeals_target")
        val cycleId = long("cycle_id")
        val scoreEventId = long("score_event_id").nullable().index("idx_watch_appeals_event")
        val appealChannelId = varchar("appeal_channel_id", 64)
        val appealMessageId = varchar("appeal_message_id", 64)
        val appealContent = text("appeal_content")
        val decision = varchar("decision", 24)
        val appliedDelta = double("applied_delta").default(0.0)
        val botReply = text("bot_reply")
        val createdAt = long("created_at")

        override val primaryKey = PrimaryKey(id)
    }

    init {
        transaction(database) {
            SchemaUtils.create(WatchAiRules, WatchAiFeedbacks, WatchAppeals)
        }
    }

    fun addRule(
        guildId: String,
        targetId: Long?,
        ruleType: AiRuleType,
        description: String,
        weightHint: AiWeightHint,
        createdBy: String,
    ): WatchAiRule = transaction(database) {
        val now = System.currentTimeMillis()
        val id = WatchAiRules.insert {
            it[WatchAiRules.guildId] = guildId
            it[WatchAiRules.targetId] = targetId
            it[WatchAiRules.ruleType] = ruleType.name
            it[WatchAiRules.description] = description
            it[WatchAiRules.weightHint] = weightHint.name
            it[WatchAiRules.createdBy] = createdBy
            it[WatchAiRules.createdAt] = now
        }[WatchAiRules.id]
        WatchAiRule(id, guildId, targetId, ruleType, description, weightHint, createdBy, now)
    }

    fun listRulesForEvaluation(guildId: String, targetId: Long): List<WatchAiRule> = transaction(database) {
        WatchAiRules.selectAll()
            .where {
                (WatchAiRules.guildId eq guildId) and
                    ((WatchAiRules.targetId eq targetId) or WatchAiRules.targetId.isNull())
            }
            .orderBy(WatchAiRules.createdAt, SortOrder.ASC)
            .map { it.toRule() }
    }

    fun listRulesByGuild(guildId: String): List<WatchAiRule> = transaction(database) {
        WatchAiRules.selectAll()
            .where { WatchAiRules.guildId eq guildId }
            .orderBy(WatchAiRules.id, SortOrder.ASC)
            .map { it.toRule() }
    }

    fun removeRule(guildId: String, ruleId: Long): Boolean = transaction(database) {
        WatchAiRules.deleteWhere {
            (WatchAiRules.guildId eq guildId) and (WatchAiRules.id eq ruleId)
        } > 0
    }

    fun addFeedback(
        guildId: String,
        targetId: Long?,
        scoreEventId: Long?,
        messageContent: String,
        contextExcerpt: String?,
        originalDelta: Double,
        expectedDelta: Double,
        verdict: AiFeedbackVerdict,
        note: String?,
        reviewerUserId: String,
    ): WatchAiFeedback = transaction(database) {
        val now = System.currentTimeMillis()
        val id = WatchAiFeedbacks.insert {
            it[WatchAiFeedbacks.guildId] = guildId
            it[WatchAiFeedbacks.targetId] = targetId
            it[WatchAiFeedbacks.scoreEventId] = scoreEventId
            it[WatchAiFeedbacks.messageContent] = messageContent.take(500)
            it[WatchAiFeedbacks.contextExcerpt] = contextExcerpt?.take(1000)
            it[WatchAiFeedbacks.originalDelta] = originalDelta
            it[WatchAiFeedbacks.expectedDelta] = expectedDelta
            it[WatchAiFeedbacks.verdict] = verdict.name
            it[WatchAiFeedbacks.note] = note
            it[WatchAiFeedbacks.reviewerUserId] = reviewerUserId
            it[WatchAiFeedbacks.createdAt] = now
        }[WatchAiFeedbacks.id]
        WatchAiFeedback(
            id = id,
            guildId = guildId,
            targetId = targetId,
            scoreEventId = scoreEventId,
            messageContent = messageContent.take(500),
            contextExcerpt = contextExcerpt?.take(1000),
            originalDelta = originalDelta,
            expectedDelta = expectedDelta,
            verdict = verdict,
            note = note,
            reviewerUserId = reviewerUserId,
            createdAt = now,
        )
    }

    /**
     * 対象者専用のフィードバックを優先し、合計 limit 件まで最新順で取得する（Few-shotプロンプト注入用）。
     */
    fun getFewShotFeedbacks(guildId: String, targetId: Long, limit: Int = 12): List<WatchAiFeedback> =
        transaction(database) {
            val targetSpecific = WatchAiFeedbacks.selectAll()
                .where { (WatchAiFeedbacks.guildId eq guildId) and (WatchAiFeedbacks.targetId eq targetId) }
                .orderBy(WatchAiFeedbacks.createdAt, SortOrder.DESC)
                .limit(limit)
                .map { it.toFeedback() }

            if (targetSpecific.size >= limit) return@transaction targetSpecific

            val remaining = limit - targetSpecific.size
            val guildWide = WatchAiFeedbacks.selectAll()
                .where { (WatchAiFeedbacks.guildId eq guildId) and WatchAiFeedbacks.targetId.isNull() }
                .orderBy(WatchAiFeedbacks.createdAt, SortOrder.DESC)
                .limit(remaining)
                .map { it.toFeedback() }

            targetSpecific + guildWide
        }

    fun hasFinalizedAppealForEvent(scoreEventId: Long): Boolean = transaction(database) {
        WatchAppeals.selectAll()
            .where { WatchAppeals.scoreEventId eq scoreEventId }
            .any { AppealDecision.fromString(it[WatchAppeals.decision]).isFinalizedReview }
    }

    fun getLastAppealTimestamp(guildId: String, targetId: Long): Long? = transaction(database) {
        WatchAppeals.selectAll()
            .where { (WatchAppeals.guildId eq guildId) and (WatchAppeals.targetId eq targetId) }
            .orderBy(WatchAppeals.createdAt, SortOrder.DESC)
            .limit(1)
            .singleOrNull()
            ?.get(WatchAppeals.createdAt)
    }

    fun recordAppeal(
        guildId: String,
        targetId: Long,
        cycleId: Long,
        scoreEventId: Long?,
        appealChannelId: String,
        appealMessageId: String,
        appealContent: String,
        decision: AppealDecision,
        appliedDelta: Double,
        botReply: String,
    ): WatchAppeal = transaction(database) {
        val now = System.currentTimeMillis()
        val id = WatchAppeals.insert {
            it[WatchAppeals.guildId] = guildId
            it[WatchAppeals.targetId] = targetId
            it[WatchAppeals.cycleId] = cycleId
            it[WatchAppeals.scoreEventId] = scoreEventId
            it[WatchAppeals.appealChannelId] = appealChannelId
            it[WatchAppeals.appealMessageId] = appealMessageId
            it[WatchAppeals.appealContent] = appealContent.take(1000)
            it[WatchAppeals.decision] = decision.name
            it[WatchAppeals.appliedDelta] = appliedDelta
            it[WatchAppeals.botReply] = botReply.take(2000)
            it[WatchAppeals.createdAt] = now
        }[WatchAppeals.id]

        WatchAppeal(
            id = id,
            guildId = guildId,
            targetId = targetId,
            cycleId = cycleId,
            scoreEventId = scoreEventId,
            appealChannelId = appealChannelId,
            appealMessageId = appealMessageId,
            appealContent = appealContent.take(1000),
            decision = decision,
            appliedDelta = appliedDelta,
            botReply = botReply.take(2000),
            createdAt = now,
        )
    }

    private fun ResultRow.toRule(): WatchAiRule = WatchAiRule(
        id = this[WatchAiRules.id],
        guildId = this[WatchAiRules.guildId],
        targetId = this[WatchAiRules.targetId],
        ruleType = AiRuleType.fromString(this[WatchAiRules.ruleType]),
        description = this[WatchAiRules.description],
        weightHint = AiWeightHint.fromString(this[WatchAiRules.weightHint]),
        createdBy = this[WatchAiRules.createdBy],
        createdAt = this[WatchAiRules.createdAt],
    )

    private fun ResultRow.toFeedback(): WatchAiFeedback = WatchAiFeedback(
        id = this[WatchAiFeedbacks.id],
        guildId = this[WatchAiFeedbacks.guildId],
        targetId = this[WatchAiFeedbacks.targetId],
        scoreEventId = this[WatchAiFeedbacks.scoreEventId],
        messageContent = this[WatchAiFeedbacks.messageContent],
        contextExcerpt = this[WatchAiFeedbacks.contextExcerpt],
        originalDelta = this[WatchAiFeedbacks.originalDelta],
        expectedDelta = this[WatchAiFeedbacks.expectedDelta],
        verdict = AiFeedbackVerdict.fromString(this[WatchAiFeedbacks.verdict]),
        note = this[WatchAiFeedbacks.note],
        reviewerUserId = this[WatchAiFeedbacks.reviewerUserId],
        createdAt = this[WatchAiFeedbacks.createdAt],
    )
}
