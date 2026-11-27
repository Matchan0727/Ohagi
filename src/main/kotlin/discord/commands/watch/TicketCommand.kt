package jp.simplespace.discord.commands.watch

import com.jagrosh.jdautilities.command.SlashCommand
import com.jagrosh.jdautilities.command.SlashCommandEvent
import jp.simplespace.discord.utils.simpleTitleAndDescriptionContainer
import jp.simplespace.watch.BanTicketService
import jp.simplespace.watch.WatchPermissions
import jp.simplespace.watchConfigService
import jp.simplespace.watchVoteTicketService
import net.dv8tion.jda.api.components.actionrow.ActionRow
import net.dv8tion.jda.api.components.buttons.Button
import net.dv8tion.jda.api.components.container.Container
import net.dv8tion.jda.api.components.separator.Separator
import net.dv8tion.jda.api.components.textdisplay.TextDisplay
import net.dv8tion.jda.api.interactions.InteractionContextType
import net.dv8tion.jda.api.interactions.commands.OptionType
import net.dv8tion.jda.api.interactions.commands.build.OptionData
import java.awt.Color

class TicketCommand : SlashCommand() {
    init {
        name = "ticket"
        help = "処刑チケット（いつでもBANできるチケット）の確認・行使・管理を行います。"
        contexts = arrayOf(InteractionContextType.GUILD)
        children = arrayOf(
            TicketListSub(),
            TicketUseSub(),
            TicketIssueSub(),
            TicketRevokeSub(),
        )
    }

    override fun execute(event: SlashCommandEvent) {}
}

private class TicketListSub : SlashCommand() {
    init {
        name = "list"
        help = "自分が保有している有効な処刑チケット一覧を表示します。"
        options = listOf(
            OptionData(OptionType.BOOLEAN, "all", "サーバー内の全有効チケットを表示（管理者専用）", false),
        )
    }

    override fun execute(event: SlashCommandEvent) {
        val guild = event.guild
        if (!WatchPermissions.ensureFeatureEnabledOrReply(event, guild)) return
        guild!!
        val showAll = event.getOption("all")?.asBoolean ?: false
        if (showAll && !WatchPermissions.ensureAdminOrReply(event, guild, event.member)) return

        val tickets = if (showAll) {
            watchVoteTicketService.listActiveTicketsByGuild(guild.id)
        } else {
            watchVoteTicketService.listActiveTicketsByHolder(guild.id, event.user.id)
        }

        if (tickets.isEmpty()) {
            event.replyComponents(
                simpleTitleAndDescriptionContainer(
                    "🎟️ 処刑チケット一覧",
                    if (showAll) "このサーバーに有効な処刑チケットはありません。" else "現在あなたが保有している有効な処刑チケットはありません。",
                    Color(0x36, 0xB8, 0xFA),
                )
            ).useComponentsV2().setEphemeral(true).queue()
            return
        }

        val body = buildString {
            append(if (showAll) "## 🎟️ サーバー内の有効な処刑チケット一覧\n" else "## 🎟️ あなたが保有する個人処刑チケット\n")
            tickets.take(10).forEach { t ->
                val target = watchConfigService.getTargetById(t.targetId)
                val exp = t.expiresAt?.let { "期限: <t:${it / 1000L}:R>" } ?: "無期限"
                val holderLabel = if (t.isGlobal) "🌐 全体共有" else "<@${t.holderUserId}>"
                append("- **#${t.id}** 対象: <@${target?.userId ?: "不明"}>")
                if (showAll) append(" | 保有者: $holderLabel")
                append(" | $exp\n  理由: ${t.reason.take(100)}\n")
            }
        }.trim()

        val components = buildList {
            add(TextDisplay.of(body))
            if (!showAll) {
                val useButtons = tickets.take(5).map { t ->
                    Button.danger("ticket_use_confirm:${t.id}", "⚔️ #${t.id} を行使")
                }
                if (useButtons.isNotEmpty()) {
                    add(Separator.createInvisible(Separator.Spacing.SMALL))
                    add(ActionRow.of(useButtons))
                }
            }
        }

        event.replyComponents(Container.of(components).withAccentColor(Color(0xE6, 0x7E, 0x22)))
            .setAllowedMentions(emptyList())
            .useComponentsV2()
            .setEphemeral(true)
            .queue()
    }
}

private class TicketUseSub : SlashCommand() {
    init {
        name = "use"
        help = "保有している処刑チケットを行使します（確認画面が表示されます）。"
        options = listOf(
            OptionData(OptionType.INTEGER, "ticket_id", "行使するチケットID", true),
        )
    }

    override fun execute(event: SlashCommandEvent) {
        val guild = event.guild
        if (!WatchPermissions.ensureFeatureEnabledOrReply(event, guild)) return
        guild!!
        val ticketId = event.getOption("ticket_id")?.asLong ?: return
        val ticket = watchVoteTicketService.getTicketById(ticketId)
        val target = ticket?.let { watchConfigService.getTargetById(it.targetId) }
        val allowed = ticket != null && ticket.guildId == guild.id && (
            (ticket.isGlobal && target?.userId != event.user.id) ||
                (!ticket.isGlobal && ticket.holderUserId == event.user.id)
            )
        if (!allowed || ticket == null) {
            event.replyComponents(
                simpleTitleAndDescriptionContainer("エラー", "あなたが行使できる有効なチケット `#${ticketId}` が見つかりません。", Color.RED)
            ).useComponentsV2().setEphemeral(true).queue()
            return
        }
        val label = if (ticket.isGlobal) "🌐 全体チケット" else "🎟️ 個人チケット"
        val container = Container.of(
            TextDisplay.of(
                "## ⚠️ ${label}行使の最終確認\n" +
                    "本当に <@${target?.userId ?: "不明"}> に対して **${label} (`#${ticket.id}`)** を行使しますか？\n" +
                    "**発行理由**: ${ticket.reason}\n" +
                    "確定すると対象メンバーへの **BAN手続きが開始** され、チケットは消費されます。"
            ),
            Separator.createInvisible(Separator.Spacing.SMALL),
            ActionRow.of(
                Button.danger("ticket_use_exec:${ticket.id}", "💀 執行する（確定）"),
                Button.secondary("ticket_use_cancel:${ticket.id}", "キャンセル"),
            ),
        ).withAccentColor(Color(0xE7, 0x4C, 0x3C))

        event.replyComponents(container).setAllowedMentions(emptyList()).useComponentsV2().setEphemeral(true).queue()
    }
}

private class TicketIssueSub : SlashCommand() {
    init {
        name = "issue"
        help = "個人チケット（被害者指定）または全体チケット（holder省略）を発行します（管理者用）。"
        options = listOf(
            OptionData(OptionType.USER, "target", "BAN対象の監視メンバー", true),
            OptionData(OptionType.STRING, "reason", "チケット発行理由", true),
            OptionData(OptionType.USER, "holder", "付与する被害者メンバー（省略すると全体チケットとして発行）", false),
            OptionData(OptionType.INTEGER, "expires_days", "有効期限（日数・省略で無期限）", false),
        )
    }

    override fun execute(event: SlashCommandEvent) {
        val guild = event.guild
        if (!WatchPermissions.ensureFeatureEnabledOrReply(event, guild)) return
        guild!!
        if (!WatchPermissions.ensureAdminOrReply(event, guild, event.member)) return

        val targetUser = event.getOption("target")?.asUser ?: return
        val reason = event.optString("reason")?.trim()?.takeIf { it.isNotEmpty() } ?: "管理者による発行"
        val holder = event.getOption("holder")?.asUser
        val expiresDays = event.getOption("expires_days")?.asInt

        if (holder != null && holder.id == targetUser.id) {
            event.replyComponents(
                simpleTitleAndDescriptionContainer("入力エラー", "監視対象者本人に自分用の処刑チケットは発行できません。", Color.RED)
            ).useComponentsV2().setEphemeral(true).queue()
            return
        }

        val target = watchConfigService.getTarget(guild.id, targetUser.id, activeOnly = true)
        if (target == null) {
            event.replyComponents(
                simpleTitleAndDescriptionContainer("対象外", "<@${targetUser.id}> は現在の監視対象に登録されていません。", Color.RED)
            ).setAllowedMentions(emptyList()).useComponentsV2().setEphemeral(true).queue()
            return
        }

        val ticket = BanTicketService.issueManualTicket(
            guild = guild,
            target = target,
            holderUserId = holder?.id,
            issuedByUserId = event.user.id,
            reason = reason,
            expiresDays = expiresDays,
        )
        val kindDesc = if (ticket.isGlobal) {
            "🌐 **全体チケット (`#${ticket.id}`)** を発行しました（対象者以外の全メンバーが使用可能）。"
        } else {
            "🎟️ **個人チケット (`#${ticket.id}`)** を <@${holder!!.id}> へ発行しました。"
        }
        event.replyComponents(
            simpleTitleAndDescriptionContainer(
                "🎟️ 処刑チケットを発行しました",
                "$kindDesc\n- **対象**: <@${targetUser.id}>\n- **理由**: $reason",
                Color(0x2E, 0xCC, 0x71),
            )
        ).setAllowedMentions(emptyList()).useComponentsV2().setEphemeral(true).queue()
    }
}

private class TicketRevokeSub : SlashCommand() {
    init {
        name = "revoke"
        help = "未使用の処刑チケットを取り消します（管理者用）。"
        options = listOf(
            OptionData(OptionType.INTEGER, "ticket_id", "取り消すチケットID", true),
        )
    }

    override fun execute(event: SlashCommandEvent) {
        val guild = event.guild
        if (!WatchPermissions.ensureFeatureEnabledOrReply(event, guild)) return
        guild!!
        if (!WatchPermissions.ensureAdminOrReply(event, guild, event.member)) return

        val ticketId = event.getOption("ticket_id")?.asLong ?: return
        val ticket = watchVoteTicketService.getTicketById(ticketId)
        if (ticket == null || ticket.guildId != guild.id) {
            event.replyComponents(simpleTitleAndDescriptionContainer("エラー", "チケット `#${ticketId}` が見つかりません。", Color.RED))
                .useComponentsV2().setEphemeral(true).queue()
            return
        }
        val target = watchConfigService.getTargetById(ticket.targetId)
        if (target != null && WatchPermissions.isSelfProtectionBlocked(event.user.id, target.userId)) {
            event.replyComponents(
                simpleTitleAndDescriptionContainer("操作できません", "監視対象者本人は自分に対する処刑チケットを取り消せません。", Color.RED)
            ).useComponentsV2().setEphemeral(true).queue()
            return
        }

        watchVoteTicketService.revokeTicket(ticketId)
        if (target != null) {
            BanTicketService.refreshTargetStatusBoard(guild, target)
        }
        event.replyComponents(
            simpleTitleAndDescriptionContainer("チケットを取り消しました", "処刑チケット `#${ticketId}` を無効化しました。", Color.ORANGE)
        ).useComponentsV2().setEphemeral(true).queue()
    }
}
