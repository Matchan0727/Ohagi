package jp.simplespace.watch

import jp.simplespace.ai.AiModerationClient
import jp.simplespace.ai.ContextMessage
import jp.simplespace.ai.ModerationAttachment
import jp.simplespace.ai.ModerationEvaluationRequest
import jp.simplespace.db.AiTaskType
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
import kotlin.math.ceil
import kotlin.math.roundToLong

data class AdaptiveBatchWindow(
    val debounceMillis: Long,
    val maxWaitMillis: Long,
    val sampleCount: Int,
)

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
        val rawText: String,
        val content: String,
        val replyToAuthorName: String?,
        val replyToExcerpt: String?,
        val attachments: List<Message.Attachment>,
        val embedImageUrls: List<String>,
        val directImageUrls: List<String>,
        val isUrlOnly: Boolean,
        val createdAt: Long,
    )

    private class PendingBatch(
        val guildId: String,
        val channelId: String,
        val userId: String,
        val targetId: Long,
        val firstCreatedAt: Long = System.currentTimeMillis(),
        val maxWaitMillis: Long = DEFAULT_MAX_WAIT_MILLIS,
        val messages: MutableList<BufferedMessage> = mutableListOf(),
        val spamReasons: MutableSet<String> = linkedSetOf(),
        var flushJob: Job? = null,
    )

    /**
     * ユーザーごとの連投間隔（同一セッション内の投稿間隔）を記録し、
     * 最適なデバウンス待機時間・最大待機時間を自動算出するトラッカー。
     */
    internal class UserCadenceTracker {
        private val intervals = ArrayDeque<Long>()
        private var lastMessageAt: Long? = null
        private var lastChannelId: String? = null

        @Synchronized
        fun recordAndGetWindow(channelId: String, nowMillis: Long): AdaptiveBatchWindow {
            val prevAt = lastMessageAt
            if (prevAt != null && lastChannelId == channelId) {
                val gap = nowMillis - prevAt
                if (gap in MIN_SAMPLE_GAP_MILLIS..MAX_SESSION_GAP_MILLIS) {
                    if (intervals.size >= MAX_CADENCE_SAMPLES) {
                        intervals.removeFirst()
                    }
                    intervals.addLast(gap)
                }
            }
            lastMessageAt = nowMillis
            lastChannelId = channelId
            return computeAdaptiveWindow(intervals.toList())
        }

        @Synchronized
        fun currentWindow(): AdaptiveBatchWindow = computeAdaptiveWindow(intervals.toList())
    }

    private val batches = ConcurrentHashMap<String, PendingBatch>()
    private val cadenceTrackers = ConcurrentHashMap<String, UserCadenceTracker>()
    private val spamDetector = SpamDetector()
    private val reevaluatedMessageIds = ConcurrentHashMap<String, Long>()

    companion object {
        // 適応型バッチングのパラメータ
        internal const val DEFAULT_DEBOUNCE_MILLIS = 12_000L
        internal const val MIN_DEBOUNCE_MILLIS = 6_000L
        internal const val MAX_DEBOUNCE_MILLIS = 30_000L

        internal const val DEFAULT_MAX_WAIT_MILLIS = 45_000L
        internal const val MIN_MAX_WAIT_MILLIS = 25_000L
        internal const val MAX_MAX_WAIT_MILLIS = 90_000L

        internal const val TYPING_EXTENSION_MILLIS = 8_000L
        internal const val MAX_BATCH_SIZE = 12

        internal const val MIN_SAMPLE_GAP_MILLIS = 300L
        internal const val MAX_SESSION_GAP_MILLIS = 120_000L
        private const val MAX_CADENCE_SAMPLES = 20
        private const val MIN_SAMPLES_FOR_ADAPTATION = 3
        private const val DEBOUNCE_HEADROOM_MULTIPLIER = 1.35
        private const val MAX_WAIT_TO_DEBOUNCE_RATIO = 3.5

        internal const val MAX_FILE_BYTES = 8L * 1024L * 1024L          // 1ファイル最大 8MB
        internal const val MAX_TOTAL_BATCH_BYTES = 15L * 1024L * 1024L  // 1バッチ合計最大 15MB
        internal const val MAX_ATTACHMENTS_PER_BATCH = 4                // 1バッチ最大 4ファイル
        internal const val REPLY_REEVAL_MAX_AGE_MILLIS = 60L * 60_000L  // 事後再評価は直近1時間以内の発言のみ
        private val URL_ONLY_REGEX = Regex("""^https?://\S+$""", RegexOption.IGNORE_CASE)
        private val CASUAL_LAUGH_OR_ACK_REGEX = Regex(
            """^(?:[wｗ草笑]+|[!！?？。、…・]+|わかる|それな|たしかに|ほんとそれ|ありがとう|あり|おつ|おつかれ|おけ|りょ|りょうかい|了解|ok|okay|lol|lmao|gg|nice|thx|草生える|ワロタ|わろた)+$""",
            RegexOption.IGNORE_CASE,
        )
        private val SHORT_NEGATIVE_HINT_REGEX = Regex(
            """は？|は\?|え？|え\?|きも|キモ|うざ|ウザ|やめ|不快|ひど|酷|最悪|やば|無理|黙|だま|消え|しね|死ね|ふざけ""",
            RegexOption.IGNORE_CASE,
        )

        internal fun shouldSkipReplyReevaluation(replyText: String): Boolean {
            val trimmed = replyText.trim()
            if (trimmed.isEmpty()) return true
            if (SHORT_NEGATIVE_HINT_REGEX.containsMatchIn(trimmed)) return false
            if (CASUAL_LAUGH_OR_ACK_REGEX.matches(trimmed)) return true
            if (trimmed.length <= 2) return true
            return false
        }

        /**
         * 過去の連投間隔サンプルから、75パーセンタイル × 余裕係数(1.35) で
         * ユーザーごとの最適な待機時間を算出する。
         */
        internal fun computeAdaptiveWindow(samples: List<Long>): AdaptiveBatchWindow {
            if (samples.size < MIN_SAMPLES_FOR_ADAPTATION) {
                return AdaptiveBatchWindow(
                    debounceMillis = DEFAULT_DEBOUNCE_MILLIS,
                    maxWaitMillis = DEFAULT_MAX_WAIT_MILLIS,
                    sampleCount = samples.size,
                )
            }
            val sorted = samples.sorted()
            val p75Index = (ceil(sorted.size * 0.75).toInt() - 1).coerceIn(0, sorted.lastIndex)
            val p75 = sorted[p75Index]

            val debounce = (p75 * DEBOUNCE_HEADROOM_MULTIPLIER)
                .roundToLong()
                .coerceIn(MIN_DEBOUNCE_MILLIS, MAX_DEBOUNCE_MILLIS)
            val maxWait = (debounce * MAX_WAIT_TO_DEBOUNCE_RATIO)
                .roundToLong()
                .coerceIn(MIN_MAX_WAIT_MILLIS, MAX_MAX_WAIT_MILLIS)

            return AdaptiveBatchWindow(
                debounceMillis = debounce,
                maxWaitMillis = maxWait,
                sampleCount = samples.size,
            )
        }

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

    fun getUserAdaptiveWindow(guildId: String, userId: String): AdaptiveBatchWindow {
        val tracker = cadenceTrackers["$guildId:$userId"]
        return tracker?.currentWindow() ?: AdaptiveBatchWindow(DEFAULT_DEBOUNCE_MILLIS, DEFAULT_MAX_WAIT_MILLIS, 0)
    }

    /**
     * 監視対象ユーザーが同じチャンネルで入力中（タイピング中）のとき、
     * 待機中バッチがあれば最大待機時間の範囲内でフラッシュを延長して分割送信を防ぐ。
     */
    fun onUserTyping(guildId: String, channelId: String, userId: String) {
        val key = "$guildId:$channelId:$userId"
        val now = System.currentTimeMillis()
        batches.computeIfPresent(key) { _, batch ->
            val remainingMaxWait = batch.maxWaitMillis - (now - batch.firstCreatedAt)
            if (remainingMaxWait > 1_000L) {
                val trackerWindow = getUserAdaptiveWindow(guildId, userId)
                val extendDelay = minOf(
                    maxOf(TYPING_EXTENSION_MILLIS, trackerWindow.debounceMillis / 2),
                    remainingMaxWait,
                )
                batch.flushJob?.cancel()
                batch.flushJob = OhagiBot.scope.launch {
                    delay(extendDelay)
                    val toFlush = batches.remove(key)
                    if (toFlush != null) {
                        evaluateBatch(toFlush, trackerWindow)
                    }
                }
            }
            batch
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
        val embedImageUrls = extractEmbedImageUrls(message)
        val directImageUrls = UrlImageFetcher.extractDirectImageUrls(text)
        val hasUrl = UrlImageFetcher.extractAllUrls(text).isNotEmpty()
        val isUrlOnly = URL_ONLY_REGEX.matches(text) && !hasAttachments && !hasStickers
        val displayContent = buildMessageSummary(text, message.attachments, message.stickers.map { it.name })
        val now = System.currentTimeMillis()

        // プリフィルタ前に全発言をスパム検知ウィンドウへ記録（短文連投・URL連投・スタンプ連打も捕捉）
        val mentionCount = message.mentions.users.size + message.mentions.roles.size +
            (if (message.mentions.mentionsEveryone()) 5 else 0)
        val isMediaOnly = (text.isBlank() || isUrlOnly) && (hasAttachments || hasStickers || embedImageUrls.isNotEmpty() || directImageUrls.isNotEmpty())
        val (spamObs, spamResult) = spamDetector.observe(
            guildId = guild.id,
            channelId = channelId,
            userId = message.author.id,
            messageId = message.id,
            rawText = text,
            summary = displayContent.ifBlank { "(空メッセージ)" },
            mentionCount = mentionCount,
            isMediaOrStickerOnly = isMediaOnly,
            nowMillis = now,
        )

        if (!spamResult.isSuspicious && shouldIgnoreMessage(text, hasAttachments, hasStickers, hasUrl, embedImageUrls.isNotEmpty())) {
            spamDetector.rememberSkipped(guild.id, channelId, message.author.id, spamObs)
            return
        }

        val tracker = cadenceTrackers.computeIfAbsent("${guild.id}:${message.author.id}") { UserCadenceTracker() }
        val window = tracker.recordAndGetWindow(channelId, now)

        val ref = message.referencedMessage
        val replyAuthor = ref?.let { it.member?.effectiveName ?: it.author.effectiveName }
        val replyExcerpt = ref?.contentDisplay?.trim()?.replace("\n", " ")?.take(60)?.takeIf { it.isNotEmpty() }
        val key = "${guild.id}:$channelId:${message.author.id}"
        var immediateFlushBatch: PendingBatch? = null

        batches.compute(key) { _, existing ->
            val batch = existing ?: PendingBatch(
                guildId = guild.id,
                channelId = channelId,
                userId = message.author.id,
                targetId = target.id,
                firstCreatedAt = now,
                maxWaitMillis = window.maxWaitMillis,
            )
            // 直前にスキップされていた短文連投があれば、スパム発火時にまとめて文脈へ合流させる
            spamResult.recentSkippedSummaries.forEach { skipped ->
                batch.messages.add(
                    BufferedMessage(
                        messageId = message.id,
                        rawText = skipped,
                        content = skipped,
                        replyToAuthorName = null,
                        replyToExcerpt = null,
                        attachments = emptyList(),
                        embedImageUrls = emptyList(),
                        directImageUrls = emptyList(),
                        isUrlOnly = false,
                        createdAt = now,
                    )
                )
            }
            batch.messages.add(
                BufferedMessage(
                    messageId = message.id,
                    rawText = text,
                    content = displayContent.ifBlank { "(空メッセージ)" },
                    replyToAuthorName = replyAuthor,
                    replyToExcerpt = replyExcerpt,
                    attachments = message.attachments,
                    embedImageUrls = embedImageUrls,
                    directImageUrls = directImageUrls,
                    isUrlOnly = isUrlOnly,
                    createdAt = now,
                )
            )
            batch.spamReasons.addAll(spamResult.reasons)
            batch.flushJob?.cancel()

            val waited = now - batch.firstCreatedAt
            val remainingMaxWait = batch.maxWaitMillis - waited
            if (batch.messages.size >= MAX_BATCH_SIZE || remainingMaxWait <= 500L) {
                immediateFlushBatch = batch
                null
            } else {
                // 明確な重度フラッドスパム時は長待ちせず早めに評価する（最大5秒）
                val targetDebounce = if (spamResult.isSevereFlood) {
                    minOf(window.debounceMillis, 5_000L)
                } else {
                    window.debounceMillis
                }
                val nextDelay = minOf(targetDebounce, remainingMaxWait.coerceAtLeast(500L))
                batch.flushJob = OhagiBot.scope.launch {
                    delay(nextDelay)
                    val toFlush = batches.remove(key)
                    if (toFlush != null) {
                        evaluateBatch(toFlush, window)
                    }
                }
                batch
            }
        }

        val toFlushNow = immediateFlushBatch
        if (toFlushNow != null) {
            OhagiBot.scope.launch {
                evaluateBatch(toFlushNow, window)
            }
        }
    }

    /**
     * 他メンバーが監視対象ユーザーの直近発言（1時間以内）へ返信（Reply）した際に、
     * 未処罰かつ未再評価であれば、その反応を踏まえて元発言を1回だけ事後再審査する。
     */
    suspend fun onReplyToTargetMessage(replyMessage: Message) {
        if (!replyMessage.isFromGuild || replyMessage.author.isBot) return
        val refMessage = replyMessage.referencedMessage ?: return
        if (refMessage.author.isBot) return
        if (refMessage.author.id == replyMessage.author.id) return

        val guild = replyMessage.guild
        if (!WatchPermissions.isFeatureEnabled(guild.id)) return

        val config = watchConfigService.getOrCreateConfig(guild.id)
        if (!config.aiEnabled) return

        val channelId = replyMessage.channel.id
        if (channelId in config.excludedChannelIds) return
        if (watchConfigService.isDedicatedChannel(guild.id, channelId)) return

        val target = watchConfigService.getTarget(guild.id, refMessage.author.id, activeOnly = true) ?: return
        val currentCycle = target.currentCycleId?.let { watchCycleService.getCycleById(it) } ?: return
        if (currentCycle.status != WatchCycleStatus.ACTIVE) return

        val replyText = replyMessage.contentDisplay.trim()
        if (shouldSkipReplyReevaluation(replyText)) return

        val now = System.currentTimeMillis()
        val refAgeMillis = now - refMessage.timeCreated.toInstant().toEpochMilli()
        if (refAgeMillis < 0L || refAgeMillis > REPLY_REEVAL_MAX_AGE_MILLIS) return

        // 現在まだバッチ待機中のメッセージなら通常バッチ側の「直後反応」に含まれるためスキップ
        val batchKey = "${guild.id}:$channelId:${target.userId}"
        val pendingBatch = batches[batchKey]
        if (pendingBatch != null && pendingBatch.messages.any { it.messageId == refMessage.id }) {
            return
        }

        // すでにAI違反として処罰済みなら二重処罰防止のためスキップ
        if (watchCycleService.hasViolationForSourceMessage(target.id, refMessage.id)) {
            return
        }

        // 1元発言につき事後再評価は最大1回まで（古いキャッシュは定期掃除）
        if (reevaluatedMessageIds.size > 2000) {
            val cutoff = now - REPLY_REEVAL_MAX_AGE_MILLIS * 2
            reevaluatedMessageIds.entries.removeIf { it.value < cutoff }
        }
        if (reevaluatedMessageIds.putIfAbsent(refMessage.id, now) != null) {
            return
        }

        val aiClient = aiClientProvider()
        if (aiClient == null || !aiClient.isConfigured) return
        val channel = guild.getChannelById(GuildMessageChannel::class.java, channelId) ?: return

        val beforeContext = runCatching {
            channel.getHistoryBefore(refMessage.id, 8)
                .complete()
                .retrievedHistory
                .asReversed()
                .filter { !it.author.isBot && (it.contentDisplay.isNotBlank() || it.attachments.isNotEmpty()) }
                .map { toContextMessage(it, target.userId, ContextMessage.POSITION_BEFORE) }
        }.getOrDefault(emptyList())

        val afterReplyContext = listOf(
            toContextMessage(replyMessage, target.userId, ContextMessage.POSITION_DURING_OR_AFTER)
        )

        val refText = refMessage.contentDisplay.trim()
        val refSummary = buildMessageSummary(refText, refMessage.attachments, refMessage.stickers.map { it.name })
            .ifBlank { "(空メッセージ)" }
        val urlCandidates = (extractEmbedImageUrls(refMessage) + UrlImageFetcher.extractDirectImageUrls(refText)).distinct()
        val moderationAttachments = downloadBatchAttachments(refMessage.attachments, urlCandidates)

        val rules = watchAiService.listRulesForEvaluation(guild.id, target.id)
        val feedbacks = watchAiService.getFewShotFeedbacks(guild.id, target.id, limit = 12)
        val taskCfg = watchAiService.getTaskConfig(guild.id, AiTaskType.MODERATION)
        val targetDisplayName = guild.getMemberById(target.userId)?.effectiveName
            ?: guild.jda.getUserById(target.userId)?.effectiveName
            ?: target.userId
        val replierName = replyMessage.member?.effectiveName ?: replyMessage.author.effectiveName

        val evalReq = ModerationEvaluationRequest(
            guildName = guild.name,
            channelName = channel.name,
            targetUserId = target.userId,
            targetDisplayName = targetDisplayName,
            personaNote = target.personaNote,
            rules = rules,
            feedbacks = feedbacks,
            contextMessages = beforeContext + afterReplyContext,
            targetMessages = listOf(refSummary),
            attachments = moderationAttachments,
            triggerNote = "他メンバー（${replierName}）が監視対象の過去発言へ後から返信したため、この反応を踏まえて元発言を再審査してください。" +
                "返信が不快感・苦言・注意ではなく通常の会話や好意的な冗談であれば、必ず違反なし(violations=[])にしてください。",
            overrideModel = taskCfg?.modelName,
            overrideThinkingLevel = taskCfg?.thinkingLevel,
        )

        OhagiBot.logger.info(
            "他メンバーの返信を契機に事後再評価を実行: guild={}, target={}, refMsgId={}, replier={}",
            guild.id,
            target.userId,
            refMessage.id,
            replyMessage.author.id,
        )

        val (result, rawJson) = aiClient.evaluate(evalReq) ?: return
        val detectedViolations = result.resolvedViolations()
        if (detectedViolations.isEmpty()) return

        val entries = detectedViolations.mapNotNull { v ->
            val delta = v.computeFinalDelta(config.aiSensitivity, config.aiMaxDeltaPerEval)
            if (delta <= 0.0) return@mapNotNull null
            BatchViolationEntry(
                delta = delta,
                category = v.category.take(32),
                reason = v.reasonJa.ifBlank { "他メンバーの反応により不適切発言と判定" },
                sourceChannelId = channelId,
                sourceMessageId = refMessage.id,
                sourceMessageExcerpt = refSummary.take(500),
                aiRawJson = rawJson,
            )
        }
        if (entries.isEmpty()) return

        val applied = WatchOrchestrator.applyBatchViolations(guild, target, entries) ?: return
        val (_, recordedEvents) = applied
        val representative = recordedEvents.maxByOrNull { it.delta } ?: recordedEvents.last()

        val victimId = result.victimUserId?.trim()?.takeIf { it.isNotEmpty() && it != target.userId }
        if (result.suggestBanTicket && victimId != null) {
            val victimExists = victimId == replyMessage.author.id ||
                beforeContext.any { it.authorId == victimId } ||
                guild.getMemberById(victimId) != null
            if (victimExists) {
                val ticketReason = result.ticketReasonJa?.takeIf { it.isNotBlank() }
                    ?: representative.reason?.takeIf { it.isNotBlank() }
                    ?: "特定メンバーへの重大な問題発言"
                onTicketSuggested(guild, target, victimId, ticketReason, representative.id, refSummary.take(500))
            }
        }
    }

    internal fun shouldIgnoreText(text: String): Boolean {
        if (text.isBlank()) return true
        if (text.length <= 2) return true
        if (URL_ONLY_REGEX.matches(text)) return true
        return false
    }

    internal fun shouldIgnoreMessage(
        text: String,
        hasAttachments: Boolean,
        hasStickers: Boolean,
        hasUrl: Boolean = false,
        hasEmbedImages: Boolean = false,
    ): Boolean {
        if (hasAttachments || hasStickers || hasEmbedImages) return false
        // URL を含む場合は Discord の Embed 画像展開を待つため一旦バッチへ通す
        if (hasUrl) return false
        return shouldIgnoreText(text)
    }

    private fun extractEmbedImageUrls(msg: Message): List<String> {
        if (msg.embeds.isEmpty()) return emptyList()
        return msg.embeds.mapNotNull { embed ->
            val img = embed.image
            val thumb = embed.thumbnail
            (img?.proxyUrl ?: img?.url ?: thumb?.proxyUrl ?: thumb?.url)?.takeIf { it.isNotBlank() }
        }.distinct()
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

    private suspend fun evaluateBatch(batch: PendingBatch, window: AdaptiveBatchWindow) {
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

        OhagiBot.logger.info(
            "AI監視バッチ評価を実行: guild={}, user={}, batchSize={}件, adaptiveDebounce={}ms, maxWait={}ms (samples={})",
            guild.id,
            target.userId,
            batch.messages.size,
            window.debounceMillis,
            window.maxWaitMillis,
            window.sampleCount,
        )

        val firstMessageId = batch.messages.firstOrNull()?.messageId ?: return
        val batchMessageIds = batch.messages.map { it.messageId }.toSet()
        val fetched = fetchSurroundingContextAndReactions(
            channel = channel,
            firstMessageId = firstMessageId,
            batchMessageIds = batchMessageIds,
            targetUserId = target.userId,
        )
        val contextMessages = fetched.contextMessages
        val reactionMap = fetched.reactionMap

        // 添付ファイル + Discordが後から展開したEmbed画像 + 本文中の直リンク画像URLを統合取得
        val urlImageCandidates = linkedSetOf<String>()
        batch.messages.forEach { bm ->
            val refreshedEmbedUrls = fetched.embedImagesByMessageId[bm.messageId].orEmpty()
            val mergedEmbedUrls = (refreshedEmbedUrls + bm.embedImageUrls).distinct()
            if (mergedEmbedUrls.isNotEmpty()) {
                urlImageCandidates.addAll(mergedEmbedUrls)
            } else {
                urlImageCandidates.addAll(bm.directImageUrls)
            }
        }

        val moderationAttachments = downloadBatchAttachments(
            attachments = batch.messages.flatMap { it.attachments },
            imageUrls = urlImageCandidates.toList(),
        )

        // スパム兆候も添付画像も無い「純粋な非画像URLのみ」バッチはAPIを呼ばずコスト節約スキップ
        val allPlainNonImageUrlOnly = batch.messages.all { it.isUrlOnly } &&
            batch.spamReasons.isEmpty() &&
            moderationAttachments.none { it.hasInlineData }
        if (allPlainNonImageUrlOnly) {
            OhagiBot.logger.debug("非画像URLのみの発言のためAI評価をスキップしました: guild={}, user={}", guild.id, target.userId)
            return
        }
        val rules = watchAiService.listRulesForEvaluation(guild.id, target.id)
        val feedbacks = watchAiService.getFewShotFeedbacks(guild.id, target.id, limit = 12)
        val targetDisplayName = guild.getMemberById(target.userId)?.effectiveName
            ?: guild.jda.getUserById(target.userId)?.effectiveName
            ?: target.userId

        val formattedTargetMessages = batch.messages.map { bm ->
            buildString {
                if (!bm.replyToAuthorName.isNullOrBlank()) {
                    append("[返信先: ${bm.replyToAuthorName}")
                    if (!bm.replyToExcerpt.isNullOrBlank()) append("「${bm.replyToExcerpt}」")
                    append("] ")
                }
                append(bm.content)
                val rx = reactionMap[bm.messageId]
                if (!rx.isNullOrBlank()) {
                    append(" [リアクション: $rx]")
                }
            }
        }

        val spamSummary = batch.spamReasons.takeIf { it.isNotEmpty() }?.joinToString(" / ")
        val taskCfg = watchAiService.getTaskConfig(guild.id, AiTaskType.MODERATION)

        val evalReq = ModerationEvaluationRequest(
            guildName = guild.name,
            channelName = channel.name,
            targetUserId = target.userId,
            targetDisplayName = targetDisplayName,
            personaNote = target.personaNote,
            rules = rules,
            feedbacks = feedbacks,
            contextMessages = contextMessages,
            targetMessages = formattedTargetMessages,
            attachments = moderationAttachments,
            spamMetricsSummary = spamSummary,
            overrideModel = taskCfg?.modelName,
            overrideThinkingLevel = taskCfg?.thinkingLevel,
        )

        val (result, rawJson) = aiClient.evaluate(evalReq) ?: return
        val detectedViolations = result.resolvedViolations()
        if (detectedViolations.isEmpty()) return

        val entries = detectedViolations.mapNotNull { v ->
            val delta = v.computeFinalDelta(config.aiSensitivity, config.aiMaxDeltaPerEval)
            if (delta <= 0.0) return@mapNotNull null

            val matchedMsgs = v.messageIndices
                .mapNotNull { idx -> batch.messages.getOrNull(idx - 1) }
                .ifEmpty { batch.messages }

            val excerpt = matchedMsgs.joinToString("\n") { it.content }.take(500)
            val sourceMsgId = matchedMsgs.last().messageId
            BatchViolationEntry(
                delta = delta,
                category = v.category.take(32),
                reason = v.reasonJa.ifBlank { "不適切な発言・添付を検知" },
                sourceChannelId = batch.channelId,
                sourceMessageId = sourceMsgId,
                sourceMessageExcerpt = excerpt,
                aiRawJson = rawJson,
            )
        }
        if (entries.isEmpty()) return

        val applied = WatchOrchestrator.applyBatchViolations(
            guild = guild,
            target = target,
            entries = entries,
        ) ?: return

        val (_, recordedEvents) = applied
        val representativeEvent = recordedEvents.maxByOrNull { it.delta } ?: recordedEvents.last()
        val combinedExcerpt = batch.messages.joinToString("\n") { it.content }.take(500)

        // 処刑チケット提案の検証と連携
        val victimId = result.victimUserId?.trim()?.takeIf { it.isNotEmpty() && it != target.userId }
        if (result.suggestBanTicket && victimId != null) {
            val victimExistsInContext = contextMessages.any { it.authorId == victimId } ||
                guild.getMemberById(victimId) != null
            if (victimExistsInContext) {
                val ticketReason = result.ticketReasonJa?.takeIf { it.isNotBlank() }
                    ?: representativeEvent.reason?.takeIf { it.isNotBlank() }
                    ?: "特定メンバーへの重大な問題発言"
                onTicketSuggested(guild, target, victimId, ticketReason, representativeEvent.id, combinedExcerpt)
            }
        }
    }

    private suspend fun downloadBatchAttachments(
        attachments: List<Message.Attachment>,
        imageUrls: List<String> = emptyList(),
    ): List<ModerationAttachment> {
        if (attachments.isEmpty() && imageUrls.isEmpty()) return emptyList()
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

            // URL画像 / Embed展開画像のダウンロード（SSRF防止チェック済み）
            for (url in imageUrls) {
                if (downloadedCount >= MAX_ATTACHMENTS_PER_BATCH) break
                val remainingBudget = (MAX_TOTAL_BATCH_BYTES - totalBytes).coerceAtMost(MAX_FILE_BYTES)
                if (remainingBudget <= 0L) break

                val fetchedImg = UrlImageFetcher.fetchImage(url, maxBytes = remainingBudget) ?: continue
                if (fetchedImg.hasInlineData) {
                    downloadedCount++
                    totalBytes += fetchedImg.sizeBytes
                }
                results += fetchedImg
            }

            results
        }
    }

    private data class SurroundingFetchResult(
        val contextMessages: List<ContextMessage>,
        val reactionMap: Map<String, String>,
        val embedImagesByMessageId: Map<String, List<String>>,
    )

    /**
     * チャンネル最新履歴から「発言前の文脈（最大10件）」「発言の間〜直後の他メンバーの反応（最大8件）」
     * 「返信先」「待機終了時点のリアクション」「後から展開されたEmbed画像URL」を最小限のAPIコールで一括収集する。
     */
    private fun fetchSurroundingContextAndReactions(
        channel: GuildMessageChannel,
        firstMessageId: String,
        batchMessageIds: Set<String>,
        targetUserId: String,
    ): SurroundingFetchResult {
        val firstIdLong = firstMessageId.toLongOrNull()
            ?: return SurroundingFetchResult(emptyList(), emptyMap(), emptyMap())
        return runCatching {
            // 通常は最新25件の1回のAPIコールで発言前・対象発言・直後反応・Embed展開結果をすべて取得できる
            val latest = channel.history.retrievePast(25).complete().asReversed()
            val targetMsgsInHistory = latest.filter { it.id in batchMessageIds }
            val reactionMap = targetMsgsInHistory
                .mapNotNull { msg ->
                    val rx = formatReactions(msg)
                    if (rx != null) msg.id to rx else null
                }
                .toMap()
            val embedImagesByMessageId = targetMsgsInHistory
                .mapNotNull { msg ->
                    val urls = extractEmbedImageUrls(msg)
                    if (urls.isNotEmpty()) msg.id to urls else null
                }
                .toMap()

            var beforeRaw = latest.filter { it.idLong < firstIdLong }
            if (beforeRaw.isEmpty()) {
                // チャンネルが非常に活発で25件以上流れていた場合のみフォールバック取得
                beforeRaw = channel.getHistoryBefore(firstMessageId, 10).complete().retrievedHistory.asReversed()
            }

            val beforeContext = beforeRaw
                .filter { !it.author.isBot && (it.contentDisplay.isNotBlank() || it.attachments.isNotEmpty()) }
                .takeLast(10)
                .map { toContextMessage(it, targetUserId, ContextMessage.POSITION_BEFORE) }

            val afterContext = latest
                .filter {
                    it.idLong > firstIdLong &&
                        it.id !in batchMessageIds &&
                        !it.author.isBot &&
                        it.author.id != targetUserId &&
                        (it.contentDisplay.isNotBlank() || it.attachments.isNotEmpty())
                }
                .take(8)
                .map { toContextMessage(it, targetUserId, ContextMessage.POSITION_DURING_OR_AFTER) }

            SurroundingFetchResult(
                contextMessages = beforeContext + afterContext,
                reactionMap = reactionMap,
                embedImagesByMessageId = embedImagesByMessageId,
            )
        }.onFailure {
            OhagiBot.logger.warn("周囲メッセージ履歴の取得に失敗しました: channel={}", channel.id, it)
        }.getOrDefault(SurroundingFetchResult(emptyList(), emptyMap(), emptyMap()))
    }

    private fun toContextMessage(msg: Message, targetUserId: String, position: String): ContextMessage {
        val summary = buildMessageSummary(
            msg.contentDisplay.trim().take(260),
            msg.attachments,
            msg.stickers.map { it.name },
        )
        val ref = msg.referencedMessage
        val replyAuthor = ref?.let { it.member?.effectiveName ?: it.author.effectiveName }
        val replyExcerpt = ref?.contentDisplay?.trim()?.replace("\n", " ")?.take(60)?.takeIf { it.isNotEmpty() }
        return ContextMessage(
            authorId = msg.author.id,
            authorName = msg.member?.effectiveName ?: msg.author.effectiveName,
            isTarget = msg.author.id == targetUserId,
            content = summary.take(300),
            timestampEpochSec = msg.timeCreated.toEpochSecond(),
            position = position,
            replyToAuthorName = replyAuthor,
            replyToExcerpt = replyExcerpt,
            reactionsSummary = formatReactions(msg),
        )
    }

    private fun formatReactions(msg: Message): String? {
        if (msg.reactions.isEmpty()) return null
        return msg.reactions
            .take(5)
            .joinToString(", ") { "${it.emoji.name}x${it.count}" }
            .takeIf { it.isNotBlank() }
    }
}
