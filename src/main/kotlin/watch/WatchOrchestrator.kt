package jp.simplespace.watch

import jp.simplespace.ai.AiModerationClient
import jp.simplespace.ai.ContextMessage
import jp.simplespace.ai.ModerationEvaluationRequest
import jp.simplespace.db.AiTaskType
import jp.simplespace.db.BanTriggerType
import jp.simplespace.db.WatchAiFeedback
import jp.simplespace.db.WatchCycle
import jp.simplespace.db.WatchCycleStatus
import jp.simplespace.db.WatchScoreEvent
import jp.simplespace.db.WatchScoreEventType
import jp.simplespace.db.WatchTarget
import jp.simplespace.discord.OhagiBot
import jp.simplespace.discord.utils.simpleTitleAndDescriptionContainer
import jp.simplespace.watchAiService
import jp.simplespace.watchConfigService
import jp.simplespace.watchCycleService
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import net.dv8tion.jda.api.Permission
import net.dv8tion.jda.api.entities.Guild
import net.dv8tion.jda.api.entities.Message
import net.dv8tion.jda.api.entities.UserSnowflake
import net.dv8tion.jda.api.entities.channel.middleman.GuildMessageChannel
import java.awt.Color
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

data class TargetRegistrationResult(
    val target: WatchTarget,
    val cycle: WatchCycle,
    val warningMessage: String? = null,
)

data class BatchViolationEntry(
    val delta: Double,
    val category: String?,
    val reason: String,
    val sourceChannelId: String?,
    val sourceMessageId: String?,
    val sourceMessageExcerpt: String?,
    val aiRawJson: String?,
)

data class ParsedDiscordMessageLink(
    val guildId: String,
    val channelId: String,
    val messageId: String,
)

data class MissedFeedbackReevaluationResult(
    val executed: Boolean,
    val appliedEvents: List<WatchScoreEvent> = emptyList(),
    val updatedScore: Double? = null,
    val summaryJa: String,
)

object WatchOrchestrator {
    private val locks = ConcurrentHashMap<String, Mutex>()
    private val DISCORD_MESSAGE_LINK_REGEX = Regex(
        """https?://(?:(?:ptb|canary)\.)?discord(?:app)?\.com/channels/(\d+)/(\d+)/(\d+)"""
    )

    fun parseDiscordMessageLink(text: String): ParsedDiscordMessageLink? {
        val m = DISCORD_MESSAGE_LINK_REGEX.find(text.trim()) ?: return null
        return ParsedDiscordMessageLink(
            guildId = m.groupValues[1],
            channelId = m.groupValues[2],
            messageId = m.groupValues[3],
        )
    }

    /**
     * 見逃し（MISSED）フィードバックが登録された際、対象発言をAIで再審査し、
     * 違反と判定された場合は現在サイクルの違反スコアへ即座に反映する。
     */
    suspend fun reevaluateMissedFeedback(
        guild: Guild,
        target: WatchTarget,
        feedback: WatchAiFeedback,
        rawInputText: String,
        aiClient: AiModerationClient? = OhagiBot.aiModerationClient,
    ): MissedFeedbackReevaluationResult {
        val latestTarget = watchConfigService.getTargetById(target.id)
        if (latestTarget == null || !latestTarget.active) {
            return MissedFeedbackReevaluationResult(
                executed = false,
                summaryJa = "対象メンバーは現在アクティブな監視対象ではないため、再審査によるスコア反映はスキップしました。",
            )
        }
        val currentCycle = latestTarget.currentCycleId?.let { watchCycleService.getCycleById(it) }
        if (currentCycle == null || currentCycle.status != WatchCycleStatus.ACTIVE) {
            return MissedFeedbackReevaluationResult(
                executed = false,
                summaryJa = "対象メンバーは現在アクティブな監視サイクル中ではないため、再審査によるスコア反映はスキップしました。",
            )
        }
        if (aiClient == null || !aiClient.isConfigured) {
            return MissedFeedbackReevaluationResult(
                executed = false,
                summaryJa = "AIクライアントが未設定のため、再審査はスキップしました。",
            )
        }

        // Discordメッセージリンクが入力された場合、同一サーバーの実メッセージと周辺文脈を取得
        var resolvedText = rawInputText.trim()
        var sourceChannelId: String? = null
        var sourceMessageId: String? = null
        var channelName = "missed-feedback"
        var contextMessages: List<ContextMessage> = emptyList()

        val link = parseDiscordMessageLink(resolvedText)
        if (link != null && link.guildId == guild.id) {
            val ch = guild.getChannelById(GuildMessageChannel::class.java, link.channelId)
            val fetched = ch?.let {
                runCatching { it.retrieveMessageById(link.messageId).complete() }.getOrNull()
            }
            if (fetched != null) {
                if (fetched.author.id == latestTarget.userId &&
                    watchCycleService.hasViolationForSourceMessage(latestTarget.id, fetched.id)
                ) {
                    return MissedFeedbackReevaluationResult(
                        executed = false,
                        summaryJa = "リンク先のメッセージはすでにAI違反として処罰済みのため、二重加点を防ぐため再審査をスキップしました。",
                    )
                }
                channelName = ch.name
                sourceChannelId = ch.id
                sourceMessageId = fetched.id
                val fetchedText = fetched.contentDisplay.trim()
                if (fetchedText.isNotBlank()) {
                    resolvedText = fetchedText
                }
                contextMessages = runCatching {
                    ch.getHistoryBefore(fetched.id, 8)
                        .complete()
                        .retrievedHistory
                        .asReversed()
                        .filter { !it.author.isBot && it.contentDisplay.isNotBlank() }
                        .map { msg ->
                            ContextMessage(
                                authorName = msg.member?.effectiveName ?: msg.author.effectiveName,
                                authorId = msg.author.id,
                                isTarget = msg.author.id == latestTarget.userId,
                                content = msg.contentDisplay.trim().take(400),
                                timestampEpochSec = msg.timeCreated.toEpochSecond(),
                                position = ContextMessage.POSITION_BEFORE,
                            )
                        }
                }.getOrDefault(emptyList())
            }
        }

        val config = watchConfigService.getOrCreateConfig(guild.id)
        val rules = watchAiService.listRulesForEvaluation(guild.id, latestTarget.id)
        val feedbacks = watchAiService.getFewShotFeedbacks(guild.id, latestTarget.id, limit = 12)
        val taskCfg = watchAiService.getTaskConfig(guild.id, AiTaskType.MODERATION)
        val targetDisplayName = guild.getMemberById(latestTarget.userId)?.effectiveName
            ?: guild.jda.getUserById(latestTarget.userId)?.effectiveName
            ?: latestTarget.userId

        val notePart = feedback.note?.takeIf { it.isNotBlank() }?.let { " / 管理者補足: $it" }.orEmpty()
        val triggerNote = String.format(
            Locale.US,
            "管理者から「AIの見逃し（MISSED）」としてフィードバック #%d が報告されたため、この発言を再審査してください。" +
                "管理者からの目安増分: +%.1f%%%s。サーバールールとフィードバックを踏まえて適切に違反判定・スコア算出を行ってください。",
            feedback.id,
            feedback.expectedDelta,
            notePart,
        )

        val evalReq = ModerationEvaluationRequest(
            guildName = guild.name,
            channelName = channelName,
            targetUserId = latestTarget.userId,
            targetDisplayName = targetDisplayName,
            personaNote = latestTarget.personaNote,
            rules = rules,
            feedbacks = feedbacks,
            contextMessages = contextMessages,
            targetMessages = listOf(resolvedText.take(1000)),
            triggerNote = triggerNote,
            overrideModel = taskCfg?.modelName,
            overrideThinkingLevel = taskCfg?.thinkingLevel,
        )

        val evaluated = aiClient.evaluate(evalReq)
            ?: return MissedFeedbackReevaluationResult(
                executed = true,
                summaryJa = "再審査のAI呼び出しに失敗しました（フィードバック自体は保存済みです）。",
            )

        val (result, rawJson) = evaluated
        val detectedViolations = result.resolvedViolations()
        val entries = detectedViolations.mapNotNull { v ->
            val delta = v.computeFinalDelta(config.aiSensitivity, config.aiMaxDeltaPerEval)
            if (delta <= 0.0) return@mapNotNull null
            BatchViolationEntry(
                delta = delta,
                category = v.category.take(32),
                reason = "[見逃し再審査 #${feedback.id}] ${v.reasonJa.ifBlank { "見逃し報告により違反と判定" }}",
                sourceChannelId = sourceChannelId,
                sourceMessageId = sourceMessageId,
                sourceMessageExcerpt = resolvedText.take(500),
                aiRawJson = rawJson,
            )
        }
        if (entries.isEmpty()) {
            return MissedFeedbackReevaluationResult(
                executed = true,
                summaryJa = "再審査を実行しましたが、AIは追加の違反なし（`+0.0%`）と判断しました。",
            )
        }

        val applied = applyBatchViolations(guild, latestTarget, entries)
            ?: return MissedFeedbackReevaluationResult(
                executed = true,
                summaryJa = "再審査で違反を検知しましたが、現在サイクルの状態によりスコア反映できませんでした。",
            )
        val (updatedCycle, recordedEvents) = applied
        val totalDelta = recordedEvents.sumOf { it.delta }
        val eventIds = recordedEvents.joinToString(", ") { "#${it.id}" }
        return MissedFeedbackReevaluationResult(
            executed = true,
            appliedEvents = recordedEvents,
            updatedScore = updatedCycle.currentScore,
            summaryJa = String.format(
                Locale.US,
                "再審査で違反を **%d件** 検知し、違反度に **`+%.1f%%`** 反映しました（現在違反度: **%.1f%%** / イベント: %s）。",
                recordedEvents.size,
                totalDelta,
                updatedCycle.currentScore,
                eventIds,
            ),
        )
    }

    suspend fun <T> withTargetLock(guildId: String, userId: String, block: suspend () -> T): T {
        val mutex = locks.computeIfAbsent("$guildId:$userId") { Mutex() }
        return mutex.withLock { block() }
    }

    suspend fun registerTarget(
        guild: Guild,
        userId: String,
        dedicatedChannelId: String?,
        personaNote: String?,
        actorUserId: String,
    ): TargetRegistrationResult = withTargetLock(guild.id, userId) {
        val config = watchConfigService.getOrCreateConfig(guild.id)
        val target = watchConfigService.upsertTarget(
            guildId = guild.id,
            userId = userId,
            dedicatedChannelId = dedicatedChannelId,
            personaNote = personaNote,
            createdBy = actorUserId,
        )

        val resolvedChannelId = watchConfigService.resolveStatusChannelId(target, config)
        val existingCycle = target.currentCycleId?.let { watchCycleService.getCycleById(it) }
            ?.takeIf { it.status == WatchCycleStatus.ACTIVE || it.status == WatchCycleStatus.PENDING_BAN }

        val cycle = existingCycle ?: run {
            val created = watchCycleService.createNewCycle(target, resolvedChannelId)
            watchConfigService.updateTargetCurrentCycle(target.id, created.id)
            created
        }

        if (resolvedChannelId != null) {
            StatusBoardService.upsertStatusBoard(guild, target, cycle, forceNewMessage = existingCycle == null)
        }

        val warnings = mutableListOf<String>()
        if (resolvedChannelId == null) {
            warnings += "専用チャンネルが未設定です。`/watch config channel` で設定するとプログレスバーが表示されます。"
        }
        if (!guild.selfMember.hasPermission(Permission.BAN_MEMBERS)) {
            warnings += "Botに `BAN_MEMBERS` 権限が付与されていません。"
        }
        val member = guild.getMemberById(userId)
        if (member != null && !guild.selfMember.canInteract(member)) {
            warnings += "対象メンバーはサーバーオーナーまたはBotより上位ロールのため、Discord仕様上BotからBANできません。Botのロール順位を上げてください。"
        }

        TargetRegistrationResult(
            target = target.copy(currentCycleId = cycle.id),
            cycle = cycle,
            warningMessage = warnings.takeIf { it.isNotEmpty() }?.joinToString("\n"),
        )
    }

    suspend fun unregisterTarget(guild: Guild, userId: String): Boolean = withTargetLock(guild.id, userId) {
        val target = watchConfigService.getTarget(guild.id, userId, activeOnly = true) ?: return@withTargetLock false
        val currentCycle = target.currentCycleId?.let { watchCycleService.getCycleById(it) }
        if (currentCycle != null) {
            if (currentCycle.status == WatchCycleStatus.PENDING_BAN) {
                PendingBanManager.cancelTimer(currentCycle.id)
            }
            if (currentCycle.status != WatchCycleStatus.ARCHIVED && currentCycle.status != WatchCycleStatus.BANNED) {
                watchCycleService.markArchived(currentCycle.id)
            }
        }
        watchConfigService.deactivateTarget(target.id)
        true
    }

    /**
     * スコア増減を適用し、ステータスボードを更新する。100%に到達した場合は遺言BANフローを開始する。
     */
    suspend fun applyScoreChange(
        guild: Guild,
        target: WatchTarget,
        eventType: WatchScoreEventType,
        delta: Double,
        category: String? = null,
        reason: String? = null,
        sourceChannelId: String? = null,
        sourceMessageId: String? = null,
        sourceMessageExcerpt: String? = null,
        aiRawJson: String? = null,
        actorUserId: String? = null,
    ): Pair<WatchCycle, WatchScoreEvent>? = withTargetLock(guild.id, target.userId) {
        val latestTarget = watchConfigService.getTargetById(target.id) ?: return@withTargetLock null
        if (!latestTarget.active) return@withTargetLock null

        val cycle = latestTarget.currentCycleId?.let { watchCycleService.getCycleById(it) }
            ?: watchCycleService.getLatestCycleByTarget(latestTarget.id)
            ?: return@withTargetLock null

        if (cycle.status != WatchCycleStatus.ACTIVE) {
            return@withTargetLock null
        }

        val newScore = (cycle.currentScore + delta).coerceIn(0.0, 100.0)
        val (updatedCycle, event) = watchCycleService.updateScoreAndRecordEvent(
            cycle = cycle,
            eventType = eventType,
            delta = delta,
            newScore = newScore,
            category = category,
            reason = reason,
            sourceChannelId = sourceChannelId,
            sourceMessageId = sourceMessageId,
            sourceMessageExcerpt = sourceMessageExcerpt,
            aiRawJson = aiRawJson,
            actorUserId = actorUserId,
        )

        if (updatedCycle.currentScore >= 100.0) {
            startBanFlowLocked(
                guild = guild,
                target = latestTarget,
                cycle = updatedCycle,
                triggerType = BanTriggerType.SCORE_100,
                triggerDetail = reason ?: "違反度が100%に到達しました",
                useLastWords = true,
            )
        } else {
            StatusBoardService.upsertStatusBoard(guild, latestTarget, updatedCycle, forceNewMessage = false)
        }

        updatedCycle to event
    }

    /**
     * 1バッチ内で検知された複数の違反を個別の違反ログ（watch_score_events）として順次記録し、
     * スコアを累積加算したうえで最後に1回だけステータスボードを更新（または100%到達で遺言BAN開始）する。
     */
    suspend fun applyBatchViolations(
        guild: Guild,
        target: WatchTarget,
        entries: List<BatchViolationEntry>,
    ): Pair<WatchCycle, List<WatchScoreEvent>>? = withTargetLock(guild.id, target.userId) {
        if (entries.isEmpty()) return@withTargetLock null
        val latestTarget = watchConfigService.getTargetById(target.id) ?: return@withTargetLock null
        if (!latestTarget.active) return@withTargetLock null

        var cycle = latestTarget.currentCycleId?.let { watchCycleService.getCycleById(it) }
            ?: watchCycleService.getLatestCycleByTarget(latestTarget.id)
            ?: return@withTargetLock null

        if (cycle.status != WatchCycleStatus.ACTIVE) {
            return@withTargetLock null
        }

        val recordedEvents = mutableListOf<WatchScoreEvent>()
        val baseNow = System.currentTimeMillis()
        for ((idx, entry) in entries.withIndex()) {
            if (entry.delta <= 0.0) continue
            val newScore = (cycle.currentScore + entry.delta).coerceIn(0.0, 100.0)
            val (updatedCycle, event) = watchCycleService.updateScoreAndRecordEvent(
                cycle = cycle,
                eventType = WatchScoreEventType.AI_VIOLATION,
                delta = entry.delta,
                newScore = newScore,
                category = entry.category,
                reason = entry.reason,
                sourceChannelId = entry.sourceChannelId,
                sourceMessageId = entry.sourceMessageId,
                sourceMessageExcerpt = entry.sourceMessageExcerpt,
                aiRawJson = entry.aiRawJson,
                now = baseNow + idx, // 同一バッチ内イベントの時系列順序を保持
            )
            cycle = updatedCycle
            recordedEvents += event
            if (cycle.currentScore >= 100.0) {
                break
            }
        }

        if (recordedEvents.isEmpty()) return@withTargetLock null

        if (cycle.currentScore >= 100.0) {
            val triggerReason = recordedEvents.joinToString(" / ") { it.reason ?: "違反検知" }.take(200)
            startBanFlowLocked(
                guild = guild,
                target = latestTarget,
                cycle = cycle,
                triggerType = BanTriggerType.SCORE_100,
                triggerDetail = triggerReason,
                useLastWords = true,
            )
        } else {
            StatusBoardService.upsertStatusBoard(guild, latestTarget, cycle, forceNewMessage = false)
        }

        cycle to recordedEvents
    }

    /**
     * 投票BAN可決・処刑チケット行使など外部契機からのBANフロー開始。
     */
    suspend fun triggerExternalBan(
        guild: Guild,
        target: WatchTarget,
        triggerType: BanTriggerType,
        triggerDetail: String,
        useLastWords: Boolean,
        actorUserId: String? = null,
    ): Boolean = withTargetLock(guild.id, target.userId) {
        val latestTarget = watchConfigService.getTargetById(target.id) ?: return@withTargetLock false
        val cycle = latestTarget.currentCycleId?.let { watchCycleService.getCycleById(it) }
            ?: watchCycleService.getLatestCycleByTarget(latestTarget.id)
            ?: return@withTargetLock false

        if (cycle.status != WatchCycleStatus.ACTIVE) {
            return@withTargetLock false
        }

        val eventType = when (triggerType) {
            BanTriggerType.VOTE -> WatchScoreEventType.VOTE_BAN
            BanTriggerType.TICKET -> WatchScoreEventType.TICKET_BAN
            BanTriggerType.SCORE_100 -> WatchScoreEventType.MANUAL_ADJUST
        }
        val (updatedCycle, _) = watchCycleService.updateScoreAndRecordEvent(
            cycle = cycle,
            eventType = eventType,
            delta = 0.0,
            newScore = cycle.currentScore,
            reason = triggerDetail,
            actorUserId = actorUserId,
        )

        startBanFlowLocked(guild, latestTarget, updatedCycle, triggerType, triggerDetail, useLastWords)
        true
    }

    suspend fun cancelPendingBan(guild: Guild, target: WatchTarget): Boolean = withTargetLock(guild.id, target.userId) {
        val cycle = target.currentCycleId?.let { watchCycleService.getCycleById(it) } ?: return@withTargetLock false
        if (cycle.status != WatchCycleStatus.PENDING_BAN) return@withTargetLock false

        PendingBanManager.cancelTimer(cycle.id)
        val restored = watchCycleService.cancelPendingBan(cycle.id) ?: return@withTargetLock false
        StatusBoardService.upsertStatusBoard(guild, target, restored, forceNewMessage = false)
        true
    }

    suspend fun repositionTargetBoard(guild: Guild, target: WatchTarget): Boolean = withTargetLock(guild.id, target.userId) {
        val latestTarget = watchConfigService.getTargetById(target.id) ?: return@withTargetLock false
        if (!latestTarget.active) return@withTargetLock false
        val cycle = latestTarget.currentCycleId?.let { watchCycleService.getCycleById(it) } ?: return@withTargetLock false
        if (cycle.status != WatchCycleStatus.ACTIVE && cycle.status != WatchCycleStatus.PENDING_BAN) {
            return@withTargetLock false
        }
        StatusBoardService.repositionStatusBoard(guild, latestTarget, cycle) != null
    }

    private fun startBanFlowLocked(
        guild: Guild,
        target: WatchTarget,
        cycle: WatchCycle,
        triggerType: BanTriggerType,
        triggerDetail: String,
        useLastWords: Boolean,
    ) {
        val config = watchConfigService.getOrCreateConfig(guild.id)
        val graceSeconds = if (useLastWords) config.banGraceSeconds.coerceAtLeast(0) else 0
        val statusChannelId = cycle.statusChannelId ?: watchConfigService.resolveStatusChannelId(target, config)
        val channel = statusChannelId?.let { guild.getTextChannelById(it) }

        if (graceSeconds <= 0 || channel == null) {
            val pending = watchCycleService.markPendingBan(
                cycleId = cycle.id,
                triggerType = triggerType,
                triggerDetail = triggerDetail,
                deadlineAt = System.currentTimeMillis(),
            ) ?: cycle
            executeBanLocked(guild, target, pending, null, null)
            return
        }

        val deadlineAt = System.currentTimeMillis() + graceSeconds * 1000L
        val pendingCycle = watchCycleService.markPendingBan(
            cycleId = cycle.id,
            triggerType = triggerType,
            triggerDetail = triggerDetail,
            deadlineAt = deadlineAt,
        ) ?: return

        StatusBoardService.upsertStatusBoard(guild, target, pendingCycle, forceNewMessage = false)

        val deadlineSec = deadlineAt / 1000L
        val notice = buildString {
            append("<@${target.userId}>\n")
            append("## 💀 処刑宣告（${triggerType.displayName}）\n")
            append("**理由**: $triggerDetail\n\n")
            append("このチャンネルで **遺言** を発言してください。\n")
            append("- 発言した **その瞬間にBANが執行** されます。\n")
            append("- 猶予期限（<t:$deadlineSec:R> / ${graceSeconds}秒）を過ぎると自動でBANされます。")
        }
        channel.sendMessage(notice)
            .setAllowedMentions(listOf(Message.MentionType.USER))
            .queue()

        PendingBanManager.schedulePendingBan(guild, target, pendingCycle)
    }

    suspend fun executeBanIfStillPending(
        guild: Guild,
        target: WatchTarget,
        cycleId: Long,
        lastWordsContent: String?,
        lastWordsMessageId: String?,
    ) = withTargetLock(guild.id, target.userId) {
        val cycle = watchCycleService.getCycleById(cycleId) ?: return@withTargetLock
        if (cycle.status != WatchCycleStatus.PENDING_BAN) return@withTargetLock
        executeBanLocked(guild, target, cycle, lastWordsContent, lastWordsMessageId)
    }

    private fun executeBanLocked(
        guild: Guild,
        target: WatchTarget,
        cycle: WatchCycle,
        lastWordsContent: String?,
        lastWordsMessageId: String?,
    ) {
        val config = watchConfigService.getOrCreateConfig(guild.id)
        val statusChannelId = cycle.statusChannelId ?: watchConfigService.resolveStatusChannelId(target, config)
        val channel = statusChannelId?.let { guild.getTextChannelById(it) }

        // 権限チェック
        val member = guild.getMemberById(target.userId)
        if (!guild.selfMember.hasPermission(Permission.BAN_MEMBERS) ||
            (member != null && !guild.selfMember.canInteract(member))
        ) {
            OhagiBot.logger.error("BAN権限またはロール階層不足でBAN執行に失敗しました: guild={}, user={}", guild.id, target.userId)
            val reverted = watchCycleService.cancelPendingBan(cycle.id)
            if (reverted != null) {
                StatusBoardService.upsertStatusBoard(guild, target, reverted, forceNewMessage = false)
            }
            channel?.sendMessageComponents(
                simpleTitleAndDescriptionContainer(
                    "⚠️ BAN執行エラー",
                    "<@${target.userId}> をBANしようとしましたが、Botの権限不足またはロール階層（対象がサーバーオーナー／Botより上位ロール）により執行できませんでした。Botのロール設定を確認してください。",
                    Color.RED,
                )
            )?.useComponentsV2()?.queue()
            return
        }

        // 1. ロールスナップショット退避
        RoleRestoreService.captureRoleSnapshot(guild, cycle)

        // 2. BAN実行（遺言・履歴を残すためメッセージ削除期間 0 秒）
        val reasonText = "Watch System (${cycle.banTriggerType?.displayName ?: "BAN"})"
        val banAttempt = runCatching {
            guild.ban(UserSnowflake.fromId(target.userId), 0, TimeUnit.SECONDS)
                .reason(reasonText)
                .complete()
        }
        if (banAttempt.isFailure) {
            OhagiBot.logger.error("Discord BAN API呼び出しに失敗: guild={}, user={}", guild.id, target.userId, banAttempt.exceptionOrNull())
            val reverted = watchCycleService.cancelPendingBan(cycle.id)
            if (reverted != null) {
                StatusBoardService.upsertStatusBoard(guild, target, reverted, forceNewMessage = false)
            }
            channel?.sendMessageComponents(
                simpleTitleAndDescriptionContainer(
                    "⚠️ BAN執行に失敗しました",
                    "<@${target.userId}> のBAN処理中にエラーが発生しました: `${banAttempt.exceptionOrNull()?.message}`",
                    Color.RED,
                )
            )?.useComponentsV2()?.queue()
            return
        }

        // 3. DBをBANNEDへ遷移＆旧ステータスカードを凍結更新
        val bannedCycle = watchCycleService.markBanned(
            cycle = cycle,
            lastWordsContent = lastWordsContent,
            lastWordsMessageId = lastWordsMessageId,
        )
        StatusBoardService.upsertStatusBoard(guild, target, bannedCycle, forceNewMessage = false)

        // 4. 執行完了メッセージ
        val reportDesc = buildString {
            append("<@${target.userId}> をサーバーからBANしました（第${bannedCycle.cycleNumber}サイクル終了）。\n")
            if (!lastWordsContent.isNullOrBlank()) {
                append("\n💬 **遺言**:\n> ${lastWordsContent.replace("\n", "\n> ")}")
            } else {
                append("\n💬 **遺言**: なし")
            }
            append("\n\n-# 復帰させる場合は管理者が `/watch unban` を実行してください。")
        }
        channel?.sendMessageComponents(
            simpleTitleAndDescriptionContainer("🔨 BAN執行完了", reportDesc, Color(0x8B, 0x00, 0x00))
        )?.setAllowedMentions(emptyList())?.useComponentsV2()?.queue()
    }
}
