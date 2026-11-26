package jp.simplespace.watch

import jp.simplespace.ai.AiModerationClient
import jp.simplespace.ai.ContextMessage
import jp.simplespace.ai.ModerationEvaluationRequest
import jp.simplespace.db.WatchCycleStatus
import jp.simplespace.db.WatchScoreEventType
import jp.simplespace.discord.OhagiBot
import jp.simplespace.watchAiService
import jp.simplespace.watchConfigService
import jp.simplespace.watchCycleService
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import net.dv8tion.jda.api.entities.Message
import net.dv8tion.jda.api.entities.channel.middleman.GuildMessageChannel
import java.util.concurrent.ConcurrentHashMap

class MessageBatchBuffer(
    private val aiClientProvider: () -> AiModerationClient?,
    private val onTicketSuggested: suspend (
        guild: net.dv8tion.jda.api.entities.Guild,
        target: jp.simplespace.db.WatchTarget,
        victimUserId: String,
        reason: String,
        sourceScoreEventId: Long,
        excerpt: String,
    ) -> Unit,
) {
    private data class BufferedMessage(
        val messageId: String,
        val content: String,
        val createdAt: Long,
    )

    private class PendingBatch(
        val guildId: String,
        val channelId: String,
        val userId: String,
        val targetId: Long,
        val firstCreatedAt: Long = System.currentTimeMillis(),
        val messages: MutableList<BufferedMessage> = mutableListOf(),
        var flushJob: Job? = null,
    )

    private val batches = ConcurrentHashMap<String, PendingBatch>()

    companion object {
        private const val DEBOUNCE_MILLIS = 5_000L
        private const val MAX_WAIT_MILLIS = 10_000L
        private const val MAX_BATCH_SIZE = 5
        private val URL_ONLY_REGEX = Regex("""^https?://\S+$""", RegexOption.IGNORE_CASE)
    }

    fun onGuildMessage(message: Message) {
        if (!message.isFromGuild || message.author.isBot) return
        val guild = message.guild
        if (!WatchPermissions.isFeatureEnabled(guild.id)) return

        val config = watchConfigService.getOrCreateConfig(guild.id)
        if (!config.aiEnabled) return

        val channelId = message.channel.id
        if (channelId in config.excludedChannelIds) return
        if (watchConfigService.isDedicatedChannel(guild.id, channelId)) return

        val target = watchConfigService.getTarget(guild.id, message.author.id, activeOnly = true) ?: return
        val currentCycle = target.currentCycleId?.let { watchCycleService.getCycleById(it) } ?: return
        if (currentCycle.status != WatchCycleStatus.ACTIVE) return

        val text = message.contentDisplay.trim()
        if (shouldIgnoreText(text)) return

        val key = "${guild.id}:$channelId:${message.author.id}"
        val now = System.currentTimeMillis()
        var immediateFlushBatch: PendingBatch? = null

        batches.compute(key) { _, existing ->
            val batch = existing ?: PendingBatch(
                guildId = guild.id,
                channelId = channelId,
                userId = message.author.id,
                targetId = target.id,
                firstCreatedAt = now,
            )
            batch.messages.add(BufferedMessage(message.id, text, now))
            batch.flushJob?.cancel()

            val waited = now - batch.firstCreatedAt
            if (batch.messages.size >= MAX_BATCH_SIZE || waited >= MAX_WAIT_MILLIS) {
                immediateFlushBatch = batch
                null
            } else {
                val nextDelay = minOf(DEBOUNCE_MILLIS, (MAX_WAIT_MILLIS - waited).coerceAtLeast(500L))
                batch.flushJob = OhagiBot.scope.launch {
                    delay(nextDelay)
                    val toFlush = batches.remove(key)
                    if (toFlush != null) {
                        evaluateBatch(toFlush)
                    }
                }
                batch
            }
        }

        val toFlushNow = immediateFlushBatch
        if (toFlushNow != null) {
            OhagiBot.scope.launch {
                evaluateBatch(toFlushNow)
            }
        }
    }

    internal fun shouldIgnoreText(text: String): Boolean {
        if (text.isBlank()) return true
        if (text.length <= 2) return true
        if (URL_ONLY_REGEX.matches(text)) return true
        return false
    }

    private suspend fun evaluateBatch(batch: PendingBatch) {
        val aiClient = aiClientProvider()
        if (aiClient == null || !aiClient.isConfigured) {
            OhagiBot.logger.debug("AIクライアント未設定のため監視評価をスキップしました")
            return
        }

        val guild = OhagiBot.jda.getGuildById(batch.guildId) ?: return
        val channel = guild.getChannelById(GuildMessageChannel::class.java, batch.channelId) ?: return
        val target = watchConfigService.getTargetById(batch.targetId) ?: return
        if (!target.active) return

        val config = watchConfigService.getOrCreateConfig(guild.id)
        if (!config.aiEnabled) return

        val firstMessageId = batch.messages.firstOrNull()?.messageId ?: return
        val contextMessages = fetchContextMessages(channel, firstMessageId, target.userId)
        val rules = watchAiService.listRulesForEvaluation(guild.id, target.id)
        val feedbacks = watchAiService.getFewShotFeedbacks(guild.id, target.id, limit = 12)
        val targetDisplayName = guild.getMemberById(target.userId)?.effectiveName
            ?: guild.jda.getUserById(target.userId)?.effectiveName
            ?: target.userId

        val evalReq = ModerationEvaluationRequest(
            guildName = guild.name,
            channelName = channel.name,
            targetUserId = target.userId,
            targetDisplayName = targetDisplayName,
            personaNote = target.personaNote,
            rules = rules,
            feedbacks = feedbacks,
            contextMessages = contextMessages,
            targetMessages = batch.messages.map { it.content },
        )

        val (result, rawJson) = aiClient.evaluate(evalReq) ?: return
        val finalDelta = result.computeFinalDelta(config.aiSensitivity, config.aiMaxDeltaPerEval)
        if (finalDelta <= 0.0) return

        val combinedExcerpt = batch.messages.joinToString("\n") { it.content }.take(500)
        val lastMessageId = batch.messages.last().messageId

        val applied = WatchOrchestrator.applyScoreChange(
            guild = guild,
            target = target,
            eventType = WatchScoreEventType.AI_VIOLATION,
            delta = finalDelta,
            category = result.category.take(32),
            reason = result.reasonJa.ifBlank { "不適切な発言を検知" },
            sourceChannelId = batch.channelId,
            sourceMessageId = lastMessageId,
            sourceMessageExcerpt = combinedExcerpt,
            aiRawJson = rawJson,
        ) ?: return

        val (_, scoreEvent) = applied

        // 処刑チケット提案の検証と連携
        val victimId = result.victimUserId?.trim()?.takeIf { it.isNotEmpty() && it != target.userId }
        if (result.suggestBanTicket && victimId != null) {
            val victimExistsInContext = contextMessages.any { it.authorId == victimId } ||
                guild.getMemberById(victimId) != null
            if (victimExistsInContext) {
                val ticketReason = result.ticketReasonJa?.takeIf { it.isNotBlank() }
                    ?: result.reasonJa.ifBlank { "特定メンバーへの重大な問題発言" }
                onTicketSuggested(guild, target, victimId, ticketReason, scoreEvent.id, combinedExcerpt)
            }
        }
    }

    private fun fetchContextMessages(
        channel: GuildMessageChannel,
        beforeMessageId: String,
        targetUserId: String,
    ): List<ContextMessage> {
        return runCatching {
            channel.getHistoryBefore(beforeMessageId, 6)
                .complete()
                .retrievedHistory
                .asReversed()
                .filter { !it.author.isBot && it.contentDisplay.isNotBlank() }
                .map { msg ->
                    ContextMessage(
                        authorId = msg.author.id,
                        authorName = msg.member?.effectiveName ?: msg.author.effectiveName,
                        isTarget = msg.author.id == targetUserId,
                        content = msg.contentDisplay.take(300),
                        timestampEpochSec = msg.timeCreated.toEpochSecond(),
                    )
                }
        }.onFailure {
            OhagiBot.logger.warn("直前メッセージ履歴の取得に失敗しました: channel={}", channel.id, it)
        }.getOrDefault(emptyList())
    }
}
