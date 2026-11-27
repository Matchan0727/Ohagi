package jp.simplespace.watch

import jp.simplespace.ai.AiModerationClient
import jp.simplespace.ai.ContextMessage
import jp.simplespace.ai.ModerationAttachment
import jp.simplespace.ai.ModerationEvaluationRequest
import jp.simplespace.db.WatchCycleStatus
import jp.simplespace.db.WatchScoreEventType
import jp.simplespace.discord.OhagiBot
import jp.simplespace.watchAiService
import jp.simplespace.watchConfigService
import jp.simplespace.watchCycleService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.dv8tion.jda.api.entities.Message
import net.dv8tion.jda.api.entities.channel.middleman.GuildMessageChannel
import java.io.ByteArrayOutputStream
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
        val attachments: List<Message.Attachment>,
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
        internal const val MAX_FILE_BYTES = 8L * 1024L * 1024L          // 1ファイル最大 8MB
        internal const val MAX_TOTAL_BATCH_BYTES = 15L * 1024L * 1024L  // 1バッチ合計最大 15MB
        internal const val MAX_ATTACHMENTS_PER_BATCH = 4                // 1バッチ最大 4ファイル
        private val URL_ONLY_REGEX = Regex("""^https?://\S+$""", RegexOption.IGNORE_CASE)

        private val SUPPORTED_MIME_TYPES = setOf(
            "image/png",
            "image/jpeg",
            "image/jpg",
            "image/webp",
            "image/gif",
            "audio/mpeg",
            "audio/mp3",
            "audio/wav",
            "audio/ogg",
            "audio/mp4",
            "audio/aac",
            "audio/flac",
            "video/mp4",
            "video/webm",
            "video/quicktime",
            "application/pdf",
            "text/plain",
        )

        private val EXTENSION_MIME_MAP = mapOf(
            "png" to "image/png",
            "jpg" to "image/jpeg",
            "jpeg" to "image/jpeg",
            "webp" to "image/webp",
            "gif" to "image/gif",
            "mp3" to "audio/mpeg",
            "wav" to "audio/wav",
            "ogg" to "audio/ogg",
            "m4a" to "audio/mp4",
            "aac" to "audio/aac",
            "flac" to "audio/flac",
            "mp4" to "video/mp4",
            "webm" to "video/webm",
            "mov" to "video/quicktime",
            "pdf" to "application/pdf",
            "txt" to "text/plain",
        )

        internal fun resolveSupportedMimeType(contentType: String?, fileName: String): String? {
            val normalized = contentType
                ?.substringBefore(';')
                ?.trim()
                ?.lowercase()
                ?.let { if (it == "image/jpg") "image/jpeg" else it }
            if (normalized != null && normalized in SUPPORTED_MIME_TYPES) {
                return normalized
            }
            val ext = fileName.substringAfterLast('.', "").lowercase()
            return EXTENSION_MIME_MAP[ext]
        }
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
        val hasAttachments = message.attachments.isNotEmpty()
        val hasStickers = message.stickers.isNotEmpty()
        if (shouldIgnoreMessage(text, hasAttachments, hasStickers)) return

        val displayContent = buildMessageSummary(text, message.attachments, message.stickers.map { it.name })

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
            batch.messages.add(
                BufferedMessage(
                    messageId = message.id,
                    content = displayContent,
                    attachments = message.attachments,
                    createdAt = now,
                )
            )
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

    internal fun shouldIgnoreMessage(text: String, hasAttachments: Boolean, hasStickers: Boolean): Boolean {
        if (hasAttachments || hasStickers) return false
        return shouldIgnoreText(text)
    }

    private fun buildMessageSummary(
        text: String,
        attachments: List<Message.Attachment>,
        stickerNames: List<String>,
    ): String = buildString {
        if (text.isNotBlank()) append(text)
        if (attachments.isNotEmpty()) {
            if (isNotEmpty()) append(" ")
            val names = attachments.joinToString(", ") { att ->
                val kind = when {
                    att.isImage -> "画像"
                    att.isVideo -> "動画"
                    else -> "添付"
                }
                "$kind:${att.fileName}"
            }
            append("[$names]")
        }
        if (stickerNames.isNotEmpty()) {
            if (isNotEmpty()) append(" ")
            append("[スタンプ: ${stickerNames.joinToString(", ")}]")
        }
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
        val moderationAttachments = downloadBatchAttachments(batch.messages.flatMap { it.attachments })
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
            attachments = moderationAttachments,
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
            reason = result.reasonJa.ifBlank { "不適切な発言・添付を検知" },
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

    private suspend fun downloadBatchAttachments(attachments: List<Message.Attachment>): List<ModerationAttachment> {
        if (attachments.isEmpty()) return emptyList()
        return withContext(Dispatchers.IO) {
            val results = mutableListOf<ModerationAttachment>()
            var downloadedCount = 0
            var totalBytes = 0L

            for (att in attachments) {
                val size = att.size.toLong()
                val mime = resolveSupportedMimeType(att.contentType, att.fileName)
                if (mime == null) {
                    results += ModerationAttachment(
                        fileName = att.fileName,
                        mimeType = att.contentType ?: "application/octet-stream",
                        sizeBytes = size,
                        skippedReason = "未対応のファイル形式",
                    )
                    continue
                }
                if (downloadedCount >= MAX_ATTACHMENTS_PER_BATCH) {
                    results += ModerationAttachment(
                        fileName = att.fileName,
                        mimeType = mime,
                        sizeBytes = size,
                        skippedReason = "1バッチの最大解析件数(${MAX_ATTACHMENTS_PER_BATCH}件)を超過",
                    )
                    continue
                }
                if (size > MAX_FILE_BYTES) {
                    results += ModerationAttachment(
                        fileName = att.fileName,
                        mimeType = mime,
                        sizeBytes = size,
                        skippedReason = "ファイルサイズが上限(8MB)を超過",
                    )
                    continue
                }
                if (totalBytes + size > MAX_TOTAL_BATCH_BYTES) {
                    results += ModerationAttachment(
                        fileName = att.fileName,
                        mimeType = mime,
                        sizeBytes = size,
                        skippedReason = "バッチ合計サイズ上限(15MB)を超過",
                    )
                    continue
                }

                val downloaded = runCatching {
                    att.proxy.download().join().use { input ->
                        val out = ByteArrayOutputStream()
                        val buf = ByteArray(8192)
                        var readTotal = 0L
                        while (true) {
                            val n = input.read(buf)
                            if (n <= 0) break
                            readTotal += n
                            if (readTotal > MAX_FILE_BYTES) {
                                throw IllegalStateException("ストリーム読み込みが8MB上限を超過しました")
                            }
                            out.write(buf, 0, n)
                        }
                        out.toByteArray()
                    }
                }.onFailure {
                    OhagiBot.logger.warn("添付ファイルのダウンロードに失敗しました: file={}, err={}", att.fileName, it.message)
                }.getOrNull()

                if (downloaded != null && downloaded.isNotEmpty()) {
                    downloadedCount++
                    totalBytes += downloaded.size.toLong()
                    results += ModerationAttachment(
                        fileName = att.fileName,
                        mimeType = mime,
                        sizeBytes = downloaded.size.toLong(),
                        bytes = downloaded,
                    )
                } else {
                    results += ModerationAttachment(
                        fileName = att.fileName,
                        mimeType = mime,
                        sizeBytes = size,
                        skippedReason = "ダウンロード失敗",
                    )
                }
            }
            results
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
                .filter { !it.author.isBot && (it.contentDisplay.isNotBlank() || it.attachments.isNotEmpty()) }
                .map { msg ->
                    val summary = buildMessageSummary(
                        msg.contentDisplay.trim().take(260),
                        msg.attachments,
                        msg.stickers.map { it.name },
                    )
                    ContextMessage(
                        authorId = msg.author.id,
                        authorName = msg.member?.effectiveName ?: msg.author.effectiveName,
                        isTarget = msg.author.id == targetUserId,
                        content = summary.take(300),
                        timestampEpochSec = msg.timeCreated.toEpochSecond(),
                    )
                }
        }.onFailure {
            OhagiBot.logger.warn("直前メッセージ履歴の取得に失敗しました: channel={}", channel.id, it)
        }.getOrDefault(emptyList())
    }
}
