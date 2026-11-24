package bot.command.slash.mod

import com.jagrosh.jdautilities.command.SlashCommand
import com.jagrosh.jdautilities.command.SlashCommandEvent
import jp.simplespace.commandPermissionService
import jp.simplespace.db.CommandPermissionService
import jp.simplespace.discord.utils.simpleTitleAndDescriptionContainer
import net.dv8tion.jda.api.interactions.DiscordLocale
import net.dv8tion.jda.api.interactions.commands.OptionType
import net.dv8tion.jda.api.interactions.commands.build.OptionData
import net.dv8tion.jda.api.interactions.commands.build.SubcommandGroupData
import java.awt.Color

class CalendarConfigMod : SlashCommand() {
    init {
        name = "calendar"
        help = "calendarコマンドの使用を許可するサーバー・チャンネルを設定します。"
        ownerCommand = true
        subcommandGroup = SubcommandGroupData("config", "各コマンドの設定を行います")
            .setNameLocalization(DiscordLocale.JAPANESE, "設定")
        options = listOf(
            OptionData(OptionType.STRING, "type", "設定対象の種類（guild または channel）", true)
                .setNameLocalization(DiscordLocale.JAPANESE, "種類")
                .addChoice("サーバー (guild)", CommandPermissionService.TYPE_GUILD)
                .addChoice("チャンネル (channel)", CommandPermissionService.TYPE_CHANNEL),
            OptionData(OptionType.STRING, "guild", "許可するサーバーID（typeがguildのとき指定）", false)
                .setNameLocalization(DiscordLocale.JAPANESE, "サーバー"),
            OptionData(OptionType.CHANNEL, "channel", "許可するチャンネル（typeがchannelのとき指定）", false)
                .setNameLocalization(DiscordLocale.JAPANESE, "チャンネル"),
        )
    }

    override fun execute(event: SlashCommandEvent) {
        val type = event.optString("type")
        val guildId = event.optString("guild")?.trim()?.takeIf { it.isNotEmpty() }
        val channel = event.getOption("channel")?.asChannel

        if (guildId == null && channel == null) {
            replyPermissionList(event)
            return
        }

        when (type) {
            CommandPermissionService.TYPE_GUILD -> {
                if (guildId == null) {
                    replyError(event, "`type` が `guild` の場合は `guild`（サーバー）オプションにサーバーIDを指定してください。")
                    return
                }
                val added = commandPermissionService.toggle(COMMAND_NAME, CommandPermissionService.TYPE_GUILD, guildId)
                val guildName = event.jda.getGuildById(guildId)?.name?.let { "$it (`$guildId`)" } ?: "`$guildId`"
                val (title, description, color) = if (added) {
                    Triple("許可サーバーを追加", "サーバー $guildName で `/calendar` を利用できるようにしました。", Color.GREEN)
                } else {
                    Triple("許可サーバーを削除", "サーバー $guildName の `/calendar` 許可設定を削除しました。", Color.ORANGE)
                }
                event.replyComponents(simpleTitleAndDescriptionContainer(title, description, color))
                    .useComponentsV2()
                    .setEphemeral(true)
                    .queue()
            }

            CommandPermissionService.TYPE_CHANNEL -> {
                if (channel == null) {
                    replyError(event, "`type` が `channel` の場合は `channel`（チャンネル）オプションを指定してください。")
                    return
                }
                val added = commandPermissionService.toggle(COMMAND_NAME, CommandPermissionService.TYPE_CHANNEL, channel.id)
                val (title, description, color) = if (added) {
                    Triple("許可チャンネルを追加", "${channel.asMention} (`${channel.id}`) で `/calendar` を利用できるようにしました。", Color.GREEN)
                } else {
                    Triple("許可チャンネルを削除", "${channel.asMention} (`${channel.id}`) の `/calendar` 許可設定を削除しました。", Color.ORANGE)
                }
                event.replyComponents(simpleTitleAndDescriptionContainer(title, description, color))
                    .useComponentsV2()
                    .setEphemeral(true)
                    .queue()
            }

            else -> replyError(event, "`type` には `guild` または `channel` を指定してください。")
        }
    }

    private fun replyPermissionList(event: SlashCommandEvent) {
        val permissions = commandPermissionService.getPermissions(COMMAND_NAME)
        val description = if (permissions.isEmpty()) {
            "現在制限は設定されていません（すべてのサーバー・チャンネルで利用可能です）。"
        } else {
            val guilds = permissions.filter { it.targetType == CommandPermissionService.TYPE_GUILD }
            val channels = permissions.filter { it.targetType == CommandPermissionService.TYPE_CHANNEL }
            buildString {
                if (guilds.isNotEmpty()) {
                    append("### 許可サーバー\n")
                    guilds.forEach { perm ->
                        val name = event.jda.getGuildById(perm.targetId)?.name
                        append(if (name != null) "- $name (`${perm.targetId}`)\n" else "- `${perm.targetId}`\n")
                    }
                }
                if (channels.isNotEmpty()) {
                    if (isNotEmpty()) append("\n")
                    append("### 許可チャンネル\n")
                    channels.forEach { perm ->
                        append("- <#${perm.targetId}> (`${perm.targetId}`)\n")
                    }
                }
            }.trim()
        }

        event.replyComponents(simpleTitleAndDescriptionContainer("calendarコマンドの許可設定一覧", description, Color(0x36B8FA)))
            .useComponentsV2()
            .setEphemeral(true)
            .queue()
    }

    private fun replyError(event: SlashCommandEvent, message: String) {
        event.replyComponents(simpleTitleAndDescriptionContainer("入力エラー", message, Color.RED))
            .useComponentsV2()
            .setEphemeral(true)
            .queue()
    }

    companion object {
        const val COMMAND_NAME = "calendar"
    }
}
