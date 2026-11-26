package jp.simplespace.watch

import jp.simplespace.db.AiAutoTicketMode
import jp.simplespace.db.BanTriggerType
import jp.simplespace.db.TicketIssueSource
import jp.simplespace.db.TicketStatus
import jp.simplespace.db.WatchBanTicket
import jp.simplespace.db.WatchCycleStatus
import jp.simplespace.db.WatchTarget
import jp.simplespace.discord.OhagiBot
import jp.simplespace.discord.utils.simpleTitleAndDescriptionContainer
import jp.simplespace.watchConfigService
import jp.simplespace.watchCycleService
import jp.simplespace.watchVoteTicketService
import net.dv8tion.jda.api.components.actionrow.ActionRow
import net.dv8tion.jda.api.components.buttons.Button
import net.dv8tion.jda.api.components.container.Container
import net.dv8tion.jda.api.components.separator.Separator
import net.dv8tion.jda.api.components.textdisplay.TextDisplay
import net.dv8tion.jda.api.entities.Guild
import net.dv8tion.jda.api.entities.Message
import java.awt.Color

object BanTicketService {
    fun issueManualTicket(
        guild: Guild,
        target: WatchTarget,
        holderUserId: String,
        issuedByUserId: String,
        reason: String,
        expiresDays: Int?,
    ): WatchBanTicket {
        val expiresAt = expiresDays?.takeIf { it > 0 }?.let { System.currentTimeMillis() + it * 24L * 3600_000L }
        val ticket = watchVoteTicketService.createTicket(
            guildId = guild.id,
            targetId = target.id,
            holderUserId = holderUserId,
            issuedByUserId = issuedByUserId,
            issueSource = TicketIssueSource.MANUAL,
            reason = reason,
            status = TicketStatus.ACTIVE,
            expiresAt = expiresAt,
        )
        notifyHolderTicketIssued(guild, target, ticket)
        return ticket
    }

    suspend fun handleAiTicketSuggestion(
        guild: Guild,
        target: WatchTarget,
        victimUserId: String,
        reason: String,
        sourceScoreEventId: Long,
        excerpt: String,
    ) {
        val config = watchConfigService.getOrCreateConfig(guild.id)
        when (config.aiAutoTicketMode) {
            AiAutoTicketMode.OFF -> return
            AiAutoTicketMode.AUTO -> {
                val ticket = watchVoteTicketService.createTicket(
                    guildId = guild.id,
                    targetId = target.id,
                    holderUserId = victimUserId,
                    issuedByUserId = null,
                    issueSource = TicketIssueSource.AI_AUTO,
                    reason = reason,
                    sourceScoreEventId = sourceScoreEventId,
                    status = TicketStatus.ACTIVE,
                )
                notifyHolderTicketIssued(guild, target, ticket)
            }
            AiAutoTicketMode.APPROVAL -> {
                val ticket = watchVoteTicketService.createTicket(
                    guildId = guild.id,
                    targetId = target.id,
                    holderUserId = victimUserId,
                    issuedByUserId = null,
                    issueSource = TicketIssueSource.AI_APPROVED,
                    reason = reason,
                    sourceScoreEventId = sourceScoreEventId,
                    status = TicketStatus.PENDING_APPROVAL,
                )
                val notifyChannelId = config.approvalChannelId ?: watchConfigService.resolveStatusChannelId(target, config)
                val channel = notifyChannelId?.let { guild.getTextChannelById(it) } ?: return

                val body = buildString {
                    append("## 🎟️ 処刑チケット発行のAI提案（承認待ち #${ticket.id}）\n")
                    append("AIが特定メンバーへの重大な問題発言を検知しました。\n\n")
                    append("- **監視対象**: <@${target.userId}>\n")
                    append("- **被害者（発行先）**: <@$victimUserId>\n")
                    append("- **理由**: $reason\n")
                    append("- **該当発言抜粋**:\n> ${excerpt.take(250).replace("\n", "\n> ")}")
                }
                val container = Container.of(
                    TextDisplay.of(body),
                    Separator.createInvisible(Separator.Spacing.SMALL),
                    ActionRow.of(
                        Button.success("ticket_approve:${ticket.id}", "✅ チケット発行を承認"),
                        Button.danger("ticket_reject:${ticket.id}", "❌ 却下"),
                    ),
                ).withAccentColor(Color(0xF1, 0xC4, 0x0F))

                channel.sendMessageComponents(container)
                    .setAllowedMentions(emptyList())
                    .useComponentsV2()
                    .queue()
            }
        }
    }

    fun approveSuggestedTicket(guild: Guild, ticketId: Long, approverUserId: String): Pair<Boolean, String> {
        val current = watchVoteTicketService.getTicketById(ticketId)
            ?: return false to "チケット `#${ticketId}` が見つかりません。"
        if (current.status != TicketStatus.PENDING_APPROVAL) {
            return false to "チケット `#${ticketId}` はすでに処理済みです（状態: `${current.status}`）。"
        }
        val approved = watchVoteTicketService.approveTicket(ticketId, approverUserId)
            ?: return false to "承認処理に失敗しました。"
        val target = watchConfigService.getTargetById(approved.targetId)
        if (target != null) {
            notifyHolderTicketIssued(guild, target, approved)
        }
        return true to "処刑チケット `#${approved.id}` の発行を承認し、<@${approved.holderUserId}> へ配布しました。"
    }

    fun rejectSuggestedTicket(ticketId: Long): Pair<Boolean, String> {
        val current = watchVoteTicketService.getTicketById(ticketId)
            ?: return false to "チケット `#${ticketId}` が見つかりません。"
        if (current.status != TicketStatus.PENDING_APPROVAL) {
            return false to "チケット `#${ticketId}` はすでに処理済みです。"
        }
        watchVoteTicketService.revokeTicket(ticketId)
        return true to "処刑チケット提案 `#${ticketId}` を却下しました。"
    }

    suspend fun executeTicket(guild: Guild, ticketId: Long, actorUserId: String): Pair<Boolean, String> {
        val ticket = watchVoteTicketService.getTicketById(ticketId)
            ?: return false to "チケット `#${ticketId}` が見つかりません。"
        if (ticket.guildId != guild.id) {
            return false to "このサーバーのチケットではありません。"
        }
        if (ticket.holderUserId != actorUserId) {
            return false to "このチケットの保有者本人のみが行使できます。"
        }
        if (ticket.status != TicketStatus.ACTIVE) {
            return false to "このチケットは使用できません（状態: `${ticket.status}`）。"
        }

        val target = watchConfigService.getTargetById(ticket.targetId)
            ?: return false to "対象の監視データが見つかりません。"
        val cycle = target.currentCycleId?.let { watchCycleService.getCycleById(it) }
        if (cycle == null || cycle.status != WatchCycleStatus.ACTIVE) {
            return false to "対象メンバーは現在アクティブな監視状態ではないため行使できません（すでにBAN済み、または遺言待機中です）。"
        }

        val consumed = watchVoteTicketService.markTicketUsed(ticket.id, cycle.id)
            ?: return false to "チケットはすでに使用されています。"

        val config = watchConfigService.getOrCreateConfig(guild.id)
        val detail = "<@$actorUserId> が処刑チケット #${consumed.id} を行使（理由: ${consumed.reason}）"

        val channelId = watchConfigService.resolveStatusChannelId(target, config)
        channelId?.let { guild.getTextChannelById(it) }?.sendMessageComponents(
            simpleTitleAndDescriptionContainer(
                "⚔️ 処刑チケット行使！",
                "<@$actorUserId> が <@${target.userId}> に対して **処刑チケット (`#${consumed.id}`)** を行使しました！\n**発行理由**: ${consumed.reason}",
                Color(0xE7, 0x4C, 0x3C),
            )
        )?.setAllowedMentions(emptyList())?.useComponentsV2()?.queue()

        val triggered = WatchOrchestrator.triggerExternalBan(
            guild = guild,
            target = target,
            triggerType = BanTriggerType.TICKET,
            triggerDetail = detail,
            useLastWords = config.ticketUseLastWords,
            actorUserId = actorUserId,
        )
        return if (triggered) {
            true to "処刑チケット `#${consumed.id}` を行使しました！<@${target.userId}> への処刑手続きを開始します。"
        } else {
            false to "処刑手続きの開始に失敗しました（対象の状態が直前に変更された可能性があります）。"
        }
    }

    private fun notifyHolderTicketIssued(guild: Guild, target: WatchTarget, ticket: WatchBanTicket) {
        val expiresText = ticket.expiresAt?.let { "\n- **有効期限**: <t:${it / 1000L}:F>" } ?: "\n- **有効期限**: 無期限"
        val dmText = buildString {
            append("🎟️ サーバー **${guild.name}** で **処刑チケット (`#${ticket.id}`)** があなたに配布されました！\n")
            append("- **対象メンバー**: <@${target.userId}>\n")
            append("- **発行理由**: ${ticket.reason}")
            append(expiresText)
            append("\n\n任意のタイミングでサーバー内の `/ticket list` または `/ticket use ticket_id:${ticket.id}` から行使できます。")
        }

        val dmSent = runCatching {
            val user = guild.jda.retrieveUserById(ticket.holderUserId).complete()
            user.openPrivateChannel().complete().sendMessage(dmText).complete()
            true
        }.getOrDefault(false)

        if (!dmSent) {
            val config = watchConfigService.getOrCreateConfig(guild.id)
            val chId = watchConfigService.resolveStatusChannelId(target, config)
            chId?.let { guild.getTextChannelById(it) }?.sendMessage(
                "<@${ticket.holderUserId}> へ <@${target.userId}> に対する **処刑チケット (`#${ticket.id}`)** が配布されました！（`/ticket list` で確認・行使できます）"
            )?.setAllowedMentions(listOf(Message.MentionType.USER))?.queue()
        }
    }
}
