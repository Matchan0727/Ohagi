package jp.simplespace.watch

import jp.simplespace.db.BanTriggerType
import jp.simplespace.db.ReportPunishment
import jp.simplespace.db.VoteChoice
import jp.simplespace.db.WatchCycleStatus
import jp.simplespace.db.WatchIncidentReport
import jp.simplespace.db.WatchScoreEventType
import jp.simplespace.db.WatchTarget
import jp.simplespace.db.WatchVote
import jp.simplespace.db.WatchVoteStatus
import jp.simplespace.db.WatchVoteTally
import jp.simplespace.discord.OhagiBot
import jp.simplespace.watchConfigService
import jp.simplespace.watchCycleService
import jp.simplespace.watchVoteTicketService
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import net.dv8tion.jda.api.JDA
import net.dv8tion.jda.api.components.actionrow.ActionRow
import net.dv8tion.jda.api.components.buttons.Button
import net.dv8tion.jda.api.components.container.Container
import net.dv8tion.jda.api.components.separator.Separator
import net.dv8tion.jda.api.components.textdisplay.TextDisplay
import net.dv8tion.jda.api.entities.Guild
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel
import java.awt.Color
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

data class StartVoteResult(
    val success: Boolean,
    val message: String,
    val vote: WatchVote? = null,
)

object VoteBanService {
    private val voteJobs = ConcurrentHashMap<Long, Job>()

    fun startVote(
        guild: Guild,
        channel: TextChannel,
        target: WatchTarget,
        initiatorUserId: String,
        reason: String,
    ): StartVoteResult {
        val config = watchConfigService.getOrCreateConfig(guild.id)
        if (!config.voteEnabled) {
            return StartVoteResult(false, "このサーバーではメンバー投票BAN機能が無効になっています。")
        }
        if (initiatorUserId == target.userId) {
            return StartVoteResult(false, "監視対象者本人が自分に対する投票を発議することはできません。")
        }
        val cycle = target.currentCycleId?.let { watchCycleService.getCycleById(it) }
        if (cycle == null || cycle.status != WatchCycleStatus.ACTIVE) {
            return StartVoteResult(false, "対象メンバーは現在アクティブな監視状態ではないため投票を開始できません。")
        }

        val existingOpen = watchVoteTicketService.getOpenVoteForTarget(target.id)
        if (existingOpen != null) {
            return StartVoteResult(false, "対象メンバーに対する投票（ID: `#${existingOpen.id}`）がすでに進行中です。")
        }

        val lastRejected = watchVoteTicketService.getLastRejectedVoteForTarget(target.id)
        if (lastRejected?.closedAt != null) {
            val cooldownEnd = lastRejected.closedAt + config.voteCooldownMinutes * 60_000L
            if (System.currentTimeMillis() < cooldownEnd) {
                return StartVoteResult(
                    false,
                    "前回の投票が否決されたため、再発議は <t:${cooldownEnd / 1000L}:R> 以降に可能です。",
                )
            }
        }

        val postChannelId = watchConfigService.resolveStatusChannelId(target, config) ?: channel.id
        val postChannel = guild.getTextChannelById(postChannelId) ?: channel
        val expiresAt = System.currentTimeMillis() + config.voteDurationMinutes.coerceAtLeast(1) * 60_000L

        val vote = watchVoteTicketService.createVote(
            guildId = guild.id,
            targetId = target.id,
            cycleId = cycle.id,
            initiatorUserId = initiatorUserId,
            reason = reason,
            channelId = postChannel.id,
            expiresAt = expiresAt,
        )

        // 発議者の賛成票を自動加算
        val tally = watchVoteTicketService.castBallot(vote.id, initiatorUserId, VoteChoice.YES)
        val container = buildVoteContainer(vote, target, tally, config.voteMinYesVotes, config.votePassRatio)
        val sent = runCatching {
            postChannel.sendMessageComponents(container)
                .setAllowedMentions(emptyList())
                .useComponentsV2()
                .complete()
        }.onFailure {
            OhagiBot.logger.error("投票メッセージ送信エラー: voteId={}", vote.id, it)
        }.getOrNull()

        if (sent != null) {
            watchVoteTicketService.updateVoteMessageId(vote.id, sent.id)
        }

        val savedVote = vote.copy(messageId = sent?.id)
        scheduleVoteClose(guild, savedVote, target)

        return StartVoteResult(
            success = true,
            message = "<#${postChannel.id}> に <@${target.userId}> へのBAN投票（ID: `#${vote.id}`）を開始しました。",
            vote = savedVote,
        )
    }

    /**
     * 専用チャンネルの「🚨 違反を報告」ボタンから、VC等のBot監視外インシデントに対する処罰投票を開始する。
     */
    fun startIncidentReportVote(
        guild: Guild,
        fallbackChannel: TextChannel,
        target: WatchTarget,
        reporterUserId: String,
        incidentPlace: String,
        punishment: ReportPunishment,
        detail: String,
    ): StartVoteResult {
        val config = watchConfigService.getOrCreateConfig(guild.id)
        if (!config.voteEnabled) {
            return StartVoteResult(false, "このサーバーではメンバー投票機能が無効になっています。")
        }
        if (reporterUserId == target.userId) {
            return StartVoteResult(false, "監視対象者本人が自分を報告することはできません。")
        }
        val cycle = target.currentCycleId?.let { watchCycleService.getCycleById(it) }
        if (cycle == null || cycle.status != WatchCycleStatus.ACTIVE) {
            return StartVoteResult(false, "対象メンバーは現在アクティブな監視状態ではないため報告投票を開始できません。")
        }
        val existingOpen = watchVoteTicketService.getOpenVoteForTarget(target.id)
        if (existingOpen != null) {
            return StartVoteResult(false, "対象メンバーに対する投票（ID: `#${existingOpen.id}`）がすでに進行中です。")
        }
        val lastRejected = watchVoteTicketService.getLastRejectedVoteForTarget(target.id)
        if (lastRejected?.closedAt != null) {
            val cooldownEnd = lastRejected.closedAt + config.voteCooldownMinutes * 60_000L
            if (System.currentTimeMillis() < cooldownEnd) {
                return StartVoteResult(
                    false,
                    "前回の投票が否決されたため、再発議は <t:${cooldownEnd / 1000L}:R> 以降に可能です。",
                )
            }
        }

        val postChannelId = watchConfigService.resolveStatusChannelId(target, config) ?: fallbackChannel.id
        val postChannel = guild.getTextChannelById(postChannelId) ?: fallbackChannel
        val expiresAt = System.currentTimeMillis() + config.voteDurationMinutes.coerceAtLeast(1) * 60_000L
        val formattedReason = "[$incidentPlace] $detail"

        val vote = watchVoteTicketService.createVote(
            guildId = guild.id,
            targetId = target.id,
            cycleId = cycle.id,
            initiatorUserId = reporterUserId,
            reason = formattedReason,
            channelId = postChannel.id,
            expiresAt = expiresAt,
        )
        val report = watchVoteTicketService.createIncidentReport(
            voteId = vote.id,
            guildId = guild.id,
            targetId = target.id,
            reporterUserId = reporterUserId,
            incidentPlace = incidentPlace,
            punishment = punishment,
            detail = detail,
        )

        val tally = watchVoteTicketService.castBallot(vote.id, reporterUserId, VoteChoice.YES)
        val container = buildVoteContainer(vote, target, tally, config.voteMinYesVotes, config.votePassRatio, report)
        val sent = runCatching {
            postChannel.sendMessageComponents(container)
                .setAllowedMentions(emptyList())
                .useComponentsV2()
                .complete()
        }.onFailure {
            OhagiBot.logger.error("報告投票メッセージ送信エラー: voteId={}", vote.id, it)
        }.getOrNull()

        if (sent != null) {
            watchVoteTicketService.updateVoteMessageId(vote.id, sent.id)
        }

        val savedVote = vote.copy(messageId = sent?.id)
        scheduleVoteClose(guild, savedVote, target)

        return StartVoteResult(
            success = true,
            message = "<#${postChannel.id}> に違反報告と処罰投票（ID: `#${vote.id}` / 提案: **${punishment.displayName}**）を開始しました。",
            vote = savedVote,
        )
    }

    fun castVote(
        guild: Guild,
        voteId: Long,
        voterUserId: String,
        choice: VoteChoice,
    ): Pair<Boolean, String> {
        val vote = watchVoteTicketService.getVoteById(voteId)
            ?: return false to "投票が見つかりません。"
        if (vote.status != WatchVoteStatus.OPEN) {
            return false to "この投票はすでに終了しています。"
        }
        val target = watchConfigService.getTargetById(vote.targetId)
            ?: return false to "監視対象が見つかりません。"
        if (voterUserId == target.userId) {
            return false to "監視対象者本人はこの投票に参加できません。"
        }

        val report = watchVoteTicketService.getIncidentReportByVoteId(voteId)
        val tally = watchVoteTicketService.castBallot(voteId, voterUserId, choice)
        val config = watchConfigService.getOrCreateConfig(guild.id)
        updateVoteMessage(guild, vote, target, tally, config.voteMinYesVotes, config.votePassRatio, report)

        val choiceLabel = if (choice == VoteChoice.YES) {
            if (report != null) "👍 賛成 (${report.punishment.displayName})" else "👍 賛成 (BAN)"
        } else {
            "👎 反対"
        }
        return true to "**$choiceLabel** に投票しました（現在: 賛成 ${tally.yesCount}票 / 反対 ${tally.noCount}票）。"
    }

    fun cancelVote(guild: Guild, voteId: Long): Pair<Boolean, String> {
        val vote = watchVoteTicketService.getVoteById(voteId)
            ?: return false to "投票 `#${voteId}` が見つかりません。"
        if (vote.status != WatchVoteStatus.OPEN) {
            return false to "投票 `#${voteId}` はすでに終了しています。"
        }
        voteJobs.remove(voteId)?.cancel()
        val closed = watchVoteTicketService.closeVote(voteId, WatchVoteStatus.CANCELLED) ?: return false to "更新失敗"
        val target = watchConfigService.getTargetById(vote.targetId)
        val tally = watchVoteTicketService.getTally(voteId)
        val config = watchConfigService.getOrCreateConfig(guild.id)
        if (target != null) {
            updateVoteMessage(guild, closed, target, tally, config.voteMinYesVotes, config.votePassRatio)
        }
        return true to "投票 `#${voteId}` を中止しました。"
    }

    fun restoreOpenVotes(jda: JDA) {
        val openVotes = watchVoteTicketService.listOpenVotes()
        if (openVotes.isEmpty()) return
        OhagiBot.logger.info("進行中のBAN投票を復元します: {}件", openVotes.size)
        for (vote in openVotes) {
            val guild = jda.getGuildById(vote.guildId)
            val target = watchConfigService.getTargetById(vote.targetId)
            if (guild != null && target != null) {
                scheduleVoteClose(guild, vote, target)
            }
        }
    }

    private fun scheduleVoteClose(guild: Guild, vote: WatchVote, target: WatchTarget) {
        voteJobs.remove(vote.id)?.cancel()
        val delayMs = (vote.expiresAt - System.currentTimeMillis()).coerceAtLeast(0L)
        voteJobs[vote.id] = OhagiBot.scope.launch {
            if (delayMs > 0L) delay(delayMs)
            voteJobs.remove(vote.id)
            finalizeVote(guild, vote.id, target)
        }
    }

    private suspend fun finalizeVote(guild: Guild, voteId: Long, target: WatchTarget) {
        val vote = watchVoteTicketService.getVoteById(voteId) ?: return
        if (vote.status != WatchVoteStatus.OPEN) return

        val config = watchConfigService.getOrCreateConfig(guild.id)
        val tally = watchVoteTicketService.getTally(voteId)
        val passed = isVotePassed(tally, config.voteMinYesVotes, config.votePassRatio)
        val finalStatus = if (passed) WatchVoteStatus.PASSED else WatchVoteStatus.REJECTED

        val closed = watchVoteTicketService.closeVote(voteId, finalStatus) ?: return
        val report = watchVoteTicketService.getIncidentReportByVoteId(voteId)
        updateVoteMessage(guild, closed, target, tally, config.voteMinYesVotes, config.votePassRatio, report)

        if (passed) {
            if (report != null && !report.punishment.isBan) {
                val detail = "報告投票#${voteId}可決（賛成${tally.yesCount}/反対${tally.noCount}）[${report.incidentPlace}]: ${report.detail.take(120)}"
                WatchOrchestrator.applyScoreChange(
                    guild = guild,
                    target = target,
                    eventType = WatchScoreEventType.VOTE_BAN,
                    delta = report.punishment.scoreDelta,
                    category = "REPORT_VOTE",
                    reason = detail,
                    sourceChannelId = vote.channelId,
                    sourceMessageId = vote.messageId,
                    sourceMessageExcerpt = "[${report.incidentPlace}] ${report.detail}".take(500),
                    actorUserId = vote.initiatorUserId,
                )
            } else {
                val detail = "投票#${voteId}可決（賛成${tally.yesCount} / 反対${tally.noCount}）: ${vote.reason}"
                WatchOrchestrator.triggerExternalBan(
                    guild = guild,
                    target = target,
                    triggerType = BanTriggerType.VOTE,
                    triggerDetail = detail,
                    useLastWords = config.voteUseLastWords,
                    actorUserId = vote.initiatorUserId,
                )
            }
        }
    }

    fun isVotePassed(tally: WatchVoteTally, minYesVotes: Int, passRatio: Double): Boolean {
        if (tally.yesCount < minYesVotes) return false
        if (tally.total == 0) return false
        return tally.yesRatio >= passRatio
    }

    private fun updateVoteMessage(
        guild: Guild,
        vote: WatchVote,
        target: WatchTarget,
        tally: WatchVoteTally,
        minYesVotes: Int,
        passRatio: Double,
        report: WatchIncidentReport? = watchVoteTicketService.getIncidentReportByVoteId(vote.id),
    ) {
        val messageId = vote.messageId ?: return
        val channel = guild.getTextChannelById(vote.channelId) ?: return
        val container = buildVoteContainer(vote, target, tally, minYesVotes, passRatio, report)
        channel.editMessageComponentsById(messageId, container)
            .setAllowedMentions(emptyList())
            .useComponentsV2()
            .queue(
                {},
                { OhagiBot.logger.warn("投票メッセージの更新に失敗しました: voteId={}", vote.id, it) },
            )
    }

    fun buildVoteContainer(
        vote: WatchVote,
        target: WatchTarget,
        tally: WatchVoteTally,
        minYesVotes: Int,
        passRatio: Double,
        report: WatchIncidentReport? = null,
    ): Container {
        val ratioPct = String.format(Locale.US, "%.0f%%", tally.yesRatio * 100.0)
        val requiredPct = String.format(Locale.US, "%.0f%%", passRatio * 100.0)
        val isReportVote = report != null
        val kindName = if (isReportVote) "🚨 違反報告・処罰投票" else "🗳️ メンバー投票BAN"
        val (title, accent) = when (vote.status) {
            WatchVoteStatus.OPEN -> "$kindName（進行中 #${vote.id}）" to Color(0xE6, 0x7E, 0x22)
            WatchVoteStatus.PASSED -> "🔨 $kindName【可決】(#${vote.id})" to Color(0xE7, 0x4C, 0x3C)
            WatchVoteStatus.REJECTED -> "🛡️ $kindName【否決】(#${vote.id})" to Color(0x2E, 0xCC, 0x71)
            WatchVoteStatus.CANCELLED -> "⛔ $kindName【中止】(#${vote.id})" to Color.GRAY
        }

        val body = buildString {
            append("## $title\n")
            append("- **対象メンバー**: <@${target.userId}>\n")
            append("- **${if (isReportVote) "報告者" else "発議者"}**: <@${vote.initiatorUserId}>\n")
            if (report != null) {
                append("- **発生場所**: ${report.incidentPlace}\n")
                append("- **提案処罰**: **${report.punishment.displayName}**\n")
                append("- **報告内容**: ${report.detail}\n")
            } else {
                append("- **理由**: ${vote.reason}\n")
            }
            append("- **集計**: 👍 賛成 **${tally.yesCount}票** / 👎 反対 **${tally.noCount}票**（賛成率: **$ratioPct**）\n")
            append("- **可決条件**: 賛成 **${minYesVotes}票以上** かつ 賛成率 **${requiredPct}以上**\n")
            if (vote.status == WatchVoteStatus.OPEN) {
                append("- **締切**: <t:${vote.expiresAt / 1000L}:R>")
            } else {
                append("- **確定日時**: <t:${(vote.closedAt ?: System.currentTimeMillis()) / 1000L}:F>")
            }
        }

        val yesLabel = if (report != null) "👍 賛成 (${report.punishment.displayName}) [${tally.yesCount}]" else "👍 賛成 (BAN) [${tally.yesCount}]"
        val isPositiveReduction = report != null && !report.punishment.isBan && report.punishment.scoreDelta < 0.0
        val yesBtn = (if (isPositiveReduction) {
            Button.success("voteban_yes:${vote.id}", yesLabel.take(80))
        } else {
            Button.danger("voteban_yes:${vote.id}", yesLabel.take(80))
        }).withDisabled(vote.status != WatchVoteStatus.OPEN)
        val noBtn = Button.secondary("voteban_no:${vote.id}", "👎 反対 [${tally.noCount}]")
            .withDisabled(vote.status != WatchVoteStatus.OPEN)

        return Container.of(
            TextDisplay.of(body),
            Separator.createInvisible(Separator.Spacing.SMALL),
            ActionRow.of(yesBtn, noBtn),
        ).withAccentColor(accent)
    }
}
