package jp.simplespace.watch

import jp.simplespace.ai.AppealReviewRequest
import jp.simplespace.ai.ContextMessage
import jp.simplespace.db.AiTaskType
import jp.simplespace.db.AppealDecision
import jp.simplespace.db.WatchCycleStatus
import jp.simplespace.db.WatchScoreEvent
import jp.simplespace.db.WatchScoreEventType
import jp.simplespace.discord.OhagiBot
import jp.simplespace.discord.utils.simpleTitleAndDescriptionContainer
import jp.simplespace.watchAiService
import jp.simplespace.watchConfigService
import jp.simplespace.watchCycleService
import net.dv8tion.jda.api.entities.Message
import net.dv8tion.jda.api.entities.channel.middleman.GuildMessageChannel
import java.awt.Color
import java.util.Locale

object WatchAppealService {
    private const val APPEAL_COOLDOWN_MILLIS = 30_000L
    private val EVENT_ID_REGEX = Regex("""#(\d{1,12})""")

    fun isAddressedToBot(message: Message): Boolean {
        val selfUser = message.jda.selfUser
        if (message.mentions.isMentioned(selfUser, Message.MentionType.USER)) {
            return true
        }
        val refAuthorId = message.referencedMessage?.author?.id
        if (refAuthorId != null && refAuthorId == selfUser.id) {
            return true
        }
        return false
    }

    internal fun extractMentionedEventId(text: String): Long? =
        EVENT_ID_REGEX.find(text)?.groupValues?.getOrNull(1)?.toLongOrNull()

    /**
     * 監視対象者による Bot へのメンション／返信を検知し、罰則理由の説明または厳格な異議審査を行う。
     * 異議申し立て／理由確認として処理した場合は true を返す。
     */
    suspend fun handlePossibleAppeal(message: Message): Boolean {
        if (!message.isFromGuild || message.author.isBot) return false
        if (!isAddressedToBot(message)) return false

        val guild = message.guild
        if (!WatchPermissions.isFeatureEnabled(guild.id)) return false

        val config = watchConfigService.getOrCreateConfig(guild.id)
        if (!config.aiEnabled) return false

        val target = watchConfigService.getTarget(guild.id, message.author.id, activeOnly = true) ?: return false
        val cycle = target.currentCycleId?.let { watchCycleService.getCycleById(it) } ?: return false
        if (cycle.status != WatchCycleStatus.ACTIVE) return false

        val aiClient = OhagiBot.aiModerationClient
        if (aiClient == null || !aiClient.isConfigured) return false

        // Botメンション自体を取り除いた本文をチェック
        val selfId = message.jda.selfUser.id
        val cleanedText = message.contentRaw
            .replace("<@$selfId>", "")
            .replace("<@!$selfId>", "")
            .trim()
        if (cleanedText.isBlank()) return false

        // 連投クールダウン（30秒）
        val now = System.currentTimeMillis()
        val lastAppealAt = watchAiService.getLastAppealTimestamp(guild.id, target.id)
        if (lastAppealAt != null && now - lastAppealAt < APPEAL_COOLDOWN_MILLIS) {
            val remainSec = ((APPEAL_COOLDOWN_MILLIS - (now - lastAppealAt)) / 1000L).coerceAtLeast(1L)
            message.replyComponents(
                simpleTitleAndDescriptionContainer(
                    "⏳ クールダウン中",
                    "異議申し立て・理由確認は連続で実行できません。あと **${remainSec}秒** 待ってから再度お試しください。",
                    Color.GRAY,
                )
            ).setAllowedMentions(emptyList()).useComponentsV2().queue()
            return true
        }

        // 直近のAI違反イベントを収集（#ID明示指定があれば優先して含める）
        val recentViolations = watchCycleService.getRecentViolationsForCycle(cycle.id, limit = 5).toMutableList()
        val explicitEventId = extractMentionedEventId(cleanedText)
        if (explicitEventId != null && recentViolations.none { it.id == explicitEventId }) {
            val explicitEv = watchCycleService.getEventById(explicitEventId)
            if (explicitEv != null && explicitEv.cycleId == cycle.id && explicitEv.eventType == WatchScoreEventType.AI_VIOLATION) {
                recentViolations.add(0, explicitEv)
            }
        }

        if (recentViolations.isEmpty()) {
            message.replyComponents(
                simpleTitleAndDescriptionContainer(
                    "⚖️ 罰則記録なし",
                    "現在の第${cycle.cycleNumber}サイクルでは、AIによる違反（罰則）記録はまだありません（現在違反度: **${String.format(Locale.US, "%.1f", cycle.currentScore)}%**）。",
                    Color(0x36, 0xB8, 0xFA),
                )
            ).setAllowedMentions(emptyList()).useComponentsV2().queue()
            return true
        }

        val alreadyReviewedIds = recentViolations
            .filter { watchAiService.hasFinalizedAppealForEvent(it.id) }
            .map { it.id }
            .toSet()

        val channel = message.channel as? GuildMessageChannel
        val contextMessages = if (channel != null) {
            fetchRecentContext(channel, message.id, target.userId)
        } else {
            emptyList()
        }

        val targetDisplayName = message.member?.effectiveName ?: message.author.effectiveName
        val rules = watchAiService.listRulesForEvaluation(guild.id, target.id)
        val feedbacks = watchAiService.getFewShotFeedbacks(guild.id, target.id, limit = 8)
        val appealTaskCfg = watchAiService.getTaskConfig(guild.id, AiTaskType.APPEAL)

        val request = AppealReviewRequest(
            guildName = guild.name,
            channelName = message.channel.name,
            targetUserId = target.userId,
            targetDisplayName = targetDisplayName,
            currentScore = cycle.currentScore,
            personaNote = target.personaNote,
            rules = rules,
            feedbacks = feedbacks,
            recentViolations = recentViolations,
            alreadyReviewedEventIds = alreadyReviewedIds,
            contextMessages = contextMessages,
            appealMessage = cleanedText,
            overrideModel = appealTaskCfg?.modelName,
            overrideThinkingLevel = appealTaskCfg?.thinkingLevel,
        )

        val (result, rawJson) = aiClient.reviewAppeal(request) ?: return false
        if (result.intent.equals("UNRELATED", ignoreCase = true)) {
            // 監視・罰則に無関係な雑談メンションは静かにスルー
            return false
        }

        val resolvedEvent: WatchScoreEvent? = result.targetEventId?.let { id ->
            recentViolations.firstOrNull { it.id == id }
        } ?: explicitEventId?.let { id ->
            recentViolations.firstOrNull { it.id == id }
        } ?: recentViolations.firstOrNull()

        val alreadyReviewed = resolvedEvent != null && resolvedEvent.id in alreadyReviewedIds
        val (finalDecision, safeDelta) = result.computeSafeAdjustment(
            targetEvent = resolvedEvent,
            alreadyReviewed = alreadyReviewed,
            maxDeltaPerEval = config.aiMaxDeltaPerEval,
        )

        // スコア変動がある場合は排他ロック経由で適用＆ステータスカード更新
        var latestScore = cycle.currentScore
        if (safeDelta != 0.0) {
            val auditReason = buildString {
                append(finalDecision.displayName)
                if (resolvedEvent != null) append(" (#${resolvedEvent.id})")
                if (result.auditReasonJa.isNotBlank()) append(": ").append(result.auditReasonJa.take(120))
            }
            val applied = WatchOrchestrator.applyScoreChange(
                guild = guild,
                target = target,
                eventType = WatchScoreEventType.APPEAL_ADJUST,
                delta = safeDelta,
                category = finalDecision.name,
                reason = auditReason,
                sourceChannelId = message.channel.id,
                sourceMessageId = message.id,
                sourceMessageExcerpt = cleanedText.take(500),
                aiRawJson = rawJson,
                actorUserId = target.userId,
            )
            if (applied != null) {
                latestScore = applied.first.currentScore
            }
        }

        val replyBody = result.replyJa.ifBlank {
            when (finalDecision) {
                AppealDecision.EXPLAIN_ONLY -> "直近の違反理由は `${resolvedEvent?.reason ?: "不適切な発言"}`（+${resolvedEvent?.delta ?: 0.0}%）です。"
                AppealDecision.UPHELD -> "申し立て内容を確認しましたが、元の判定を覆す十分な根拠がないため罰則を維持します。"
                AppealDecision.REDUCED -> "文脈を再確認した結果、罰則を一部軽減しました。"
                AppealDecision.REVOKED -> "文脈を再確認した結果、該当の違反判定を撤回しました。"
                AppealDecision.ESCALATED -> "不適切な申し立て内容のため、異議を却下し追加ペナルティを科しました。"
            }
        }

        // DBへ裁定履歴を記録（同一イベントの再審査防止）
        watchAiService.recordAppeal(
            guildId = guild.id,
            targetId = target.id,
            cycleId = cycle.id,
            scoreEventId = resolvedEvent?.id,
            appealChannelId = message.channel.id,
            appealMessageId = message.id,
            appealContent = cleanedText,
            decision = finalDecision,
            appliedDelta = safeDelta,
            botReply = replyBody,
        )

        val (title, color) = when (finalDecision) {
            AppealDecision.EXPLAIN_ONLY -> "⚖️ 罰則の理由説明" to Color(0x36, 0xB8, 0xFA)
            AppealDecision.UPHELD -> "🚫 異議棄却（罰則維持）" to Color(0xE6, 0x7E, 0x22)
            AppealDecision.REDUCED -> "🔽 異議一部容認（罰則軽減）" to Color(0x2E, 0xCC, 0x71)
            AppealDecision.REVOKED -> "✅ 異議容認（違反撤回）" to Color(0x2E, 0xCC, 0x71)
            AppealDecision.ESCALATED -> "🔥 異議棄却＋加重ペナルティ" to Color(0xE7, 0x4C, 0x3C)
        }

        val fullDesc = buildString {
            append(replyBody)
            append("\n\n")
            if (resolvedEvent != null) {
                append("- **対象違反**: `#${resolvedEvent.id}`（元加算: `+${String.format(Locale.US, "%.1f", resolvedEvent.delta)}%`）\n")
            }
            if (safeDelta != 0.0) {
                val sign = if (safeDelta > 0) "+" else ""
                append("- **見直し結果**: `${String.format(Locale.US, "%s%.1f%%", sign, safeDelta)}` → 現在違反度: **${String.format(Locale.US, "%.1f", latestScore)}%**")
            } else {
                append("- **現在違反度**: **${String.format(Locale.US, "%.1f", latestScore)}%**（変更なし）")
            }
            if (alreadyReviewed && finalDecision == AppealDecision.UPHELD) {
                append("\n-# ※ この違反イベントはすでに審査済みのため、再度の軽減・撤回はできません。")
            }
        }

        message.replyComponents(simpleTitleAndDescriptionContainer(title, fullDesc, color))
            .setAllowedMentions(emptyList())
            .useComponentsV2()
            .queue()

        return true
    }

    private fun fetchRecentContext(
        channel: GuildMessageChannel,
        beforeMessageId: String,
        targetUserId: String,
    ): List<ContextMessage> {
        return runCatching {
            channel.getHistoryBefore(beforeMessageId, 10)
                .complete()
                .retrievedHistory
                .asReversed()
                .filter { !it.author.isBot && it.contentDisplay.isNotBlank() }
                .map { msg ->
                    val ref = msg.referencedMessage
                    ContextMessage(
                        authorId = msg.author.id,
                        authorName = msg.member?.effectiveName ?: msg.author.effectiveName,
                        isTarget = msg.author.id == targetUserId,
                        content = msg.contentDisplay.take(250),
                        timestampEpochSec = msg.timeCreated.toEpochSecond(),
                        position = ContextMessage.POSITION_BEFORE,
                        replyToAuthorName = ref?.let { it.member?.effectiveName ?: it.author.effectiveName },
                        replyToExcerpt = ref?.contentDisplay?.trim()?.replace("\n", " ")?.take(60)?.takeIf { it.isNotEmpty() },
                        reactionsSummary = msg.reactions.take(5).joinToString(", ") { "${it.emoji.name}x${it.count}" }.takeIf { it.isNotBlank() },
                    )
                }
        }.getOrDefault(emptyList())
    }
}
