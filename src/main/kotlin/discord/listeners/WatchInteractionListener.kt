package jp.simplespace.discord.listeners

import jp.simplespace.db.VoteChoice
import jp.simplespace.discord.OhagiBot
import jp.simplespace.discord.utils.simpleTitleAndDescriptionContainer
import jp.simplespace.watch.BanTicketService
import jp.simplespace.watch.StatusBoardService
import jp.simplespace.watch.VoteBanService
import jp.simplespace.watch.WatchPermissions
import jp.simplespace.watch.render.GraphRange
import jp.simplespace.watchConfigService
import jp.simplespace.watchCycleService
import jp.simplespace.watchVoteTicketService
import kotlinx.coroutines.launch
import net.dv8tion.jda.api.components.actionrow.ActionRow
import net.dv8tion.jda.api.components.buttons.Button
import net.dv8tion.jda.api.components.container.Container
import net.dv8tion.jda.api.components.separator.Separator
import net.dv8tion.jda.api.components.textdisplay.TextDisplay
import net.dv8tion.jda.api.events.interaction.command.CommandAutoCompleteInteractionEvent
import net.dv8tion.jda.api.events.interaction.component.ButtonInteractionEvent
import net.dv8tion.jda.api.hooks.ListenerAdapter
import net.dv8tion.jda.api.interactions.commands.Command
import java.awt.Color
import java.util.Locale

class WatchInteractionListener : ListenerAdapter() {

    override fun onCommandAutoCompleteInteraction(event: CommandAutoCompleteInteractionEvent) {
        val guild = event.guild ?: return
        if (!WatchPermissions.isFeatureEnabled(guild.id)) return

        if (event.name == "watch" && event.subcommandName == "unban" && event.focusedOption.name == "user_id") {
            val typed = event.focusedOption.value.lowercase()
            val bannedCycles = watchCycleService.listBannedCyclesByGuild(guild.id)
            val choices = bannedCycles.mapNotNull { cycle ->
                val userName = event.jda.getUserById(cycle.userId)?.effectiveName ?: "User"
                val label = "$userName (${cycle.userId}) - 第${cycle.cycleNumber}サイクル"
                if (typed.isBlank() || label.lowercase().contains(typed)) {
                    Command.Choice(label.take(100), cycle.userId)
                } else {
                    null
                }
            }.distinctBy { it.asString }.take(25)
            event.replyChoices(choices).queue()
        }
    }

    override fun onButtonInteraction(event: ButtonInteractionEvent) {
        val id = event.button.customId ?: return
        val guild = event.guild ?: return

        when {
            id.startsWith("watch_graph:") -> handleGraphOpen(event, guild, id)
            id.startsWith("watch_graph_range:") -> handleGraphSwitch(event, guild, id)
            id.startsWith("watch_logs:") -> handleLogsView(event, guild, id)
            id.startsWith("voteban_yes:") -> handleVoteBallot(event, guild, id.substringAfter("voteban_yes:").toLongOrNull(), VoteChoice.YES)
            id.startsWith("voteban_no:") -> handleVoteBallot(event, guild, id.substringAfter("voteban_no:").toLongOrNull(), VoteChoice.NO)
            id.startsWith("ticket_approve:") -> handleTicketApproval(event, guild, id.substringAfter("ticket_approve:").toLongOrNull(), approve = true)
            id.startsWith("ticket_reject:") -> handleTicketApproval(event, guild, id.substringAfter("ticket_reject:").toLongOrNull(), approve = false)
            id.startsWith("ticket_use_confirm:") -> handleTicketUseConfirm(event, guild, id.substringAfter("ticket_use_confirm:").toLongOrNull())
            id.startsWith("ticket_use_exec:") -> handleTicketUseExec(event, guild, id.substringAfter("ticket_use_exec:").toLongOrNull())
            id.startsWith("ticket_use_cancel:") -> {
                event.editComponents(
                    simpleTitleAndDescriptionContainer("キャンセル", "処刑チケットの行使をキャンセルしました。", Color.GRAY)
                ).useComponentsV2().queue()
            }
        }
    }

    private fun handleGraphOpen(event: ButtonInteractionEvent, guild: net.dv8tion.jda.api.entities.Guild, customId: String) {
        if (!WatchPermissions.ensureFeatureEnabledOrReply(event, guild)) return
        val parts = customId.split(":")
        val targetId = parts.getOrNull(1)?.toLongOrNull() ?: return
        val range = GraphRange.fromKey(parts.getOrNull(2))
        val target = watchConfigService.getTargetById(targetId)
        if (target == null || target.guildId != guild.id) {
            event.replyComponents(simpleTitleAndDescriptionContainer("エラー", "監視対象データが見つかりません。", Color.RED))
                .useComponentsV2().setEphemeral(true).queue()
            return
        }

        event.deferReply(true).queue { hook ->
            OhagiBot.scope.launch {
                try {
                    val (container, _) = StatusBoardService.buildGraphPanel(guild, target, range)
                    hook.editOriginalComponents(container)
                        .setAllowedMentions(emptyList())
                        .useComponentsV2()
                        .queue()
                } catch (e: Exception) {
                    OhagiBot.logger.error("グラフ生成中にエラー", e)
                    hook.editOriginalComponents(
                        simpleTitleAndDescriptionContainer("エラー", "グラフの生成中にエラーが発生しました。", Color.RED)
                    ).useComponentsV2().queue()
                }
            }
        }
    }

    private fun handleGraphSwitch(event: ButtonInteractionEvent, guild: net.dv8tion.jda.api.entities.Guild, customId: String) {
        if (!WatchPermissions.ensureFeatureEnabledOrReply(event, guild)) return
        val parts = customId.split(":")
        val targetId = parts.getOrNull(1)?.toLongOrNull() ?: return
        val range = GraphRange.fromKey(parts.getOrNull(2))
        val target = watchConfigService.getTargetById(targetId)
        if (target == null || target.guildId != guild.id) {
            event.replyComponents(simpleTitleAndDescriptionContainer("エラー", "監視対象データが見つかりません。", Color.RED))
                .useComponentsV2().setEphemeral(true).queue()
            return
        }

        event.deferEdit().useComponentsV2().queue { hook ->
            OhagiBot.scope.launch {
                try {
                    val (container, _) = StatusBoardService.buildGraphPanel(guild, target, range)
                    hook.editOriginalComponents(container)
                        .setAllowedMentions(emptyList())
                        .useComponentsV2()
                        .queue()
                } catch (e: Exception) {
                    OhagiBot.logger.error("グラフ期間切替中にエラー", e)
                    hook.editOriginalComponents(
                        simpleTitleAndDescriptionContainer("エラー", "グラフの更新中にエラーが発生しました。", Color.RED)
                    ).useComponentsV2().queue()
                }
            }
        }
    }

    private fun handleLogsView(event: ButtonInteractionEvent, guild: net.dv8tion.jda.api.entities.Guild, customId: String) {
        if (!WatchPermissions.ensureFeatureEnabledOrReply(event, guild)) return
        val parts = customId.split(":")
        val targetId = parts.getOrNull(1)?.toLongOrNull() ?: return
        val page = parts.getOrNull(2)?.toIntOrNull()?.coerceAtLeast(0) ?: 0
        val isPagination = event.message.isEphemeral

        val target = watchConfigService.getTargetById(targetId)
        if (target == null || target.guildId != guild.id) {
            event.replyComponents(simpleTitleAndDescriptionContainer("エラー", "監視対象が見つかりません。", Color.RED))
                .useComponentsV2().setEphemeral(true).queue()
            return
        }

        val pageSize = 6
        val (events, total) = watchCycleService.getRecentEventsPaged(targetId, page, pageSize)
        val totalPages = ((total + pageSize - 1) / pageSize).coerceAtLeast(1).toInt()

        val body = buildString {
            append("## 📋 違反・変動ログ（<@${target.userId}> | ${page + 1}/${totalPages}）\n")
            if (events.isEmpty()) {
                append("履歴はまだありません。")
            } else {
                events.forEach { ev ->
                    val sign = if (ev.delta >= 0) "+" else ""
                    val deltaStr = String.format(Locale.US, "%s%.1f%%", sign, ev.delta)
                    val scoreStr = String.format(Locale.US, "%.1f%%", ev.scoreAfter)
                    append("- **#${ev.id}** `<t:${ev.createdAt / 1000L}:g>` **[${ev.eventType.displayName}]** `$deltaStr` → **$scoreStr**")
                    if (!ev.reason.isNullOrBlank()) {
                        append("\n  理由: ${ev.reason.take(100)}")
                    }
                    if (!ev.sourceMessageExcerpt.isNullOrBlank()) {
                        append("\n  -# 発言抜粋: 「${ev.sourceMessageExcerpt.take(80).replace("\n", " ")}」")
                    }
                    append("\n")
                }
                append("\n-# AI誤判定の修正にはイベント番号（`#ID`）を使って `/watch ai feedback` を実行できます。")
            }
        }

        val prevBtn = Button.secondary("watch_logs:$targetId:${page - 1}", "◀️ 前へ").withDisabled(page <= 0)
        val nextBtn = Button.secondary("watch_logs:$targetId:${page + 1}", "▶️ 次へ").withDisabled(page + 1 >= totalPages)
        val container = Container.of(
            TextDisplay.of(body.trim()),
            Separator.createInvisible(Separator.Spacing.SMALL),
            ActionRow.of(prevBtn, nextBtn),
        ).withAccentColor(Color(0x36, 0xB8, 0xFA))

        if (isPagination) {
            event.editComponents(container).setAllowedMentions(emptyList()).useComponentsV2().queue()
        } else {
            event.replyComponents(container).setAllowedMentions(emptyList()).useComponentsV2().setEphemeral(true).queue()
        }
    }

    private fun handleVoteBallot(
        event: ButtonInteractionEvent,
        guild: net.dv8tion.jda.api.entities.Guild,
        voteId: Long?,
        choice: VoteChoice,
    ) {
        if (voteId == null) return
        if (!WatchPermissions.ensureFeatureEnabledOrReply(event, guild)) return
        val (ok, msg) = VoteBanService.castVote(guild, voteId, event.user.id, choice)
        event.replyComponents(
            simpleTitleAndDescriptionContainer(
                if (ok) "投票を受け付けました" else "投票できません",
                msg,
                if (ok) Color(0x2E, 0xCC, 0x71) else Color.RED,
            )
        ).useComponentsV2().setEphemeral(true).queue()
    }

    private fun handleTicketApproval(
        event: ButtonInteractionEvent,
        guild: net.dv8tion.jda.api.entities.Guild,
        ticketId: Long?,
        approve: Boolean,
    ) {
        if (ticketId == null) return
        if (!WatchPermissions.ensureFeatureEnabledOrReply(event, guild)) return
        if (!WatchPermissions.ensureAdminOrReply(event, guild, event.member)) return

        val ticket = watchVoteTicketService.getTicketById(ticketId)
        val target = ticket?.let { watchConfigService.getTargetById(it.targetId) }
        if (target != null && WatchPermissions.isSelfProtectionBlocked(event.user.id, target.userId)) {
            event.replyComponents(
                simpleTitleAndDescriptionContainer("操作できません", "監視対象者本人は自分に関するチケット提案を承認・却下できません。", Color.RED)
            ).useComponentsV2().setEphemeral(true).queue()
            return
        }

        val (ok, msg) = if (approve) {
            BanTicketService.approveSuggestedTicket(guild, ticketId, event.user.id)
        } else {
            BanTicketService.rejectSuggestedTicket(ticketId)
        }

        if (ok) {
            val statusTitle = if (approve) "✅ 処刑チケット提案【承認済み】(#$ticketId)" else "❌ 処刑チケット提案【却下】(#$ticketId)"
            val color = if (approve) Color(0x2E, 0xCC, 0x71) else Color.GRAY
            event.editComponents(
                simpleTitleAndDescriptionContainer(statusTitle, "$msg\n対応者: <@${event.user.id}>", color)
            ).setAllowedMentions(emptyList()).useComponentsV2().queue()
        } else {
            event.replyComponents(simpleTitleAndDescriptionContainer("エラー", msg, Color.RED))
                .useComponentsV2().setEphemeral(true).queue()
        }
    }

    private fun handleTicketUseConfirm(
        event: ButtonInteractionEvent,
        guild: net.dv8tion.jda.api.entities.Guild,
        ticketId: Long?,
    ) {
        if (ticketId == null) return
        if (!WatchPermissions.ensureFeatureEnabledOrReply(event, guild)) return
        val ticket = watchVoteTicketService.getTicketById(ticketId)
        if (ticket == null || ticket.holderUserId != event.user.id) {
            event.replyComponents(simpleTitleAndDescriptionContainer("エラー", "使用可能なチケットが見つかりません。", Color.RED))
                .useComponentsV2().setEphemeral(true).queue()
            return
        }
        val target = watchConfigService.getTargetById(ticket.targetId)
        val targetMention = target?.let { "<@${it.userId}>" } ?: "対象不明"

        val container = Container.of(
            TextDisplay.of(
                "## ⚠️ 処刑チケット行使の最終確認\n" +
                    "本当に $targetMention に対して **処刑チケット (`#${ticket.id}`)** を行使しますか？\n" +
                    "実行すると対象メンバーへの **BAN手続きが即座に開始** され、このチケットは消費されます。"
            ),
            Separator.createInvisible(Separator.Spacing.SMALL),
            ActionRow.of(
                Button.danger("ticket_use_exec:${ticket.id}", "💀 執行する（確定）"),
                Button.secondary("ticket_use_cancel:${ticket.id}", "キャンセル"),
            ),
        ).withAccentColor(Color(0xE7, 0x4C, 0x3C))

        event.replyComponents(container).setAllowedMentions(emptyList()).useComponentsV2().setEphemeral(true).queue()
    }

    private fun handleTicketUseExec(
        event: ButtonInteractionEvent,
        guild: net.dv8tion.jda.api.entities.Guild,
        ticketId: Long?,
    ) {
        if (ticketId == null) return
        if (!WatchPermissions.ensureFeatureEnabledOrReply(event, guild)) return

        event.deferEdit().useComponentsV2().queue { hook ->
            OhagiBot.scope.launch {
                val (ok, msg) = BanTicketService.executeTicket(guild, ticketId, event.user.id)
                hook.editOriginalComponents(
                    simpleTitleAndDescriptionContainer(
                        if (ok) "⚔️ 処刑チケットを行使しました" else "行使できませんでした",
                        msg,
                        if (ok) Color(0xE7, 0x4C, 0x3C) else Color.RED,
                    )
                ).setAllowedMentions(emptyList()).useComponentsV2().queue()
            }
        }
    }
}
