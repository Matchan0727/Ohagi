package jp.simplespace.discord.commands.watch

import com.jagrosh.jdautilities.command.SlashCommand
import com.jagrosh.jdautilities.command.SlashCommandEvent
import jp.simplespace.discord.utils.simpleTitleAndDescriptionContainer
import jp.simplespace.watch.VoteBanService
import jp.simplespace.watch.WatchPermissions
import jp.simplespace.watchConfigService
import jp.simplespace.watchVoteTicketService
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel
import net.dv8tion.jda.api.interactions.InteractionContextType
import net.dv8tion.jda.api.interactions.commands.OptionType
import net.dv8tion.jda.api.interactions.commands.build.OptionData
import java.awt.Color

class VoteBanCommand : SlashCommand() {
    init {
        name = "voteban"
        help = "監視対象メンバーに対するBAN投票を行います。"
        contexts = arrayOf(InteractionContextType.GUILD)
        children = arrayOf(
            VoteBanStartSub(),
            VoteBanStatusSub(),
            VoteBanCancelSub(),
        )
    }

    override fun execute(event: SlashCommandEvent) {}
}

private class VoteBanStartSub : SlashCommand() {
    init {
        name = "start"
        help = "監視対象メンバーへのBAN投票を発議します。"
        options = listOf(
            OptionData(OptionType.USER, "user", "対象の監視メンバー", true),
            OptionData(OptionType.STRING, "reason", "BAN投票を発議する理由", true),
        )
    }

    override fun execute(event: SlashCommandEvent) {
        val guild = event.guild
        if (!WatchPermissions.ensureFeatureEnabledOrReply(event, guild)) return
        guild!!
        val user = event.getOption("user")?.asUser ?: return
        val reason = event.optString("reason")?.trim()?.takeIf { it.isNotEmpty() } ?: "理由未指定"
        val channel = event.channel as? TextChannel
        if (channel == null) {
            event.replyComponents(simpleTitleAndDescriptionContainer("エラー", "テキストチャンネルで実行してください。", Color.RED))
                .useComponentsV2().setEphemeral(true).queue()
            return
        }

        val target = watchConfigService.getTarget(guild.id, user.id, activeOnly = true)
        if (target == null) {
            event.replyComponents(
                simpleTitleAndDescriptionContainer("対象外", "<@${user.id}> は現在の監視対象に登録されていません。", Color.RED)
            ).setAllowedMentions(emptyList()).useComponentsV2().setEphemeral(true).queue()
            return
        }

        val result = VoteBanService.startVote(guild, channel, target, event.user.id, reason)
        event.replyComponents(
            simpleTitleAndDescriptionContainer(
                if (result.success) "🗳️ 投票を開始しました" else "開始できませんでした",
                result.message,
                if (result.success) Color(0x2E, 0xCC, 0x71) else Color.RED,
            )
        ).setAllowedMentions(emptyList()).useComponentsV2().setEphemeral(true).queue()
    }
}

private class VoteBanStatusSub : SlashCommand() {
    init {
        name = "status"
        help = "このサーバーで現在進行中のBAN投票一覧を表示します。"
    }

    override fun execute(event: SlashCommandEvent) {
        val guild = event.guild
        if (!WatchPermissions.ensureFeatureEnabledOrReply(event, guild)) return
        guild!!
        val openVotes = watchVoteTicketService.listOpenVotes(guild.id)
        val desc = if (openVotes.isEmpty()) {
            "現在進行中のBAN投票はありません。"
        } else {
            buildString {
                openVotes.forEach { v ->
                    val target = watchConfigService.getTargetById(v.targetId)
                    val tally = watchVoteTicketService.getTally(v.id)
                    append("- **#${v.id}** 対象: <@${target?.userId ?: "不明"}> | 👍 ${tally.yesCount} / 👎 ${tally.noCount} | 締切: <t:${v.expiresAt / 1000L}:R>\n")
                    append("  理由: ${v.reason.take(80)}\n")
                }
            }.trim()
        }
        event.replyComponents(simpleTitleAndDescriptionContainer("🗳️ 進行中のBAN投票一覧", desc, Color(0x36, 0xB8, 0xFA)))
            .setAllowedMentions(emptyList()).useComponentsV2().setEphemeral(true).queue()
    }
}

private class VoteBanCancelSub : SlashCommand() {
    init {
        name = "cancel"
        help = "進行中のBAN投票を強制中止します（管理者用）。"
        options = listOf(
            OptionData(OptionType.INTEGER, "vote_id", "中止する投票ID", true),
        )
    }

    override fun execute(event: SlashCommandEvent) {
        val guild = event.guild
        if (!WatchPermissions.ensureFeatureEnabledOrReply(event, guild)) return
        guild!!
        if (!WatchPermissions.ensureAdminOrReply(event, guild, event.member)) return

        val voteId = event.getOption("vote_id")?.asLong ?: return
        val vote = watchVoteTicketService.getVoteById(voteId)
        val target = vote?.let { watchConfigService.getTargetById(it.targetId) }
        if (target != null && WatchPermissions.isSelfProtectionBlocked(event.user.id, target.userId)) {
            event.replyComponents(
                simpleTitleAndDescriptionContainer("操作できません", "監視対象者本人は自分に対する投票を中止できません。", Color.RED)
            ).useComponentsV2().setEphemeral(true).queue()
            return
        }

        val (ok, msg) = VoteBanService.cancelVote(guild, voteId)
        event.replyComponents(
            simpleTitleAndDescriptionContainer(
                if (ok) "投票を中止しました" else "エラー",
                msg,
                if (ok) Color.ORANGE else Color.RED,
            )
        ).useComponentsV2().setEphemeral(true).queue()
    }
}
