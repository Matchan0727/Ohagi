package bot.command.slash.mod

import com.jagrosh.jdautilities.command.SlashCommand
import com.jagrosh.jdautilities.command.SlashCommandEvent
import jp.simplespace.db.FeatureKey
import jp.simplespace.discord.utils.simpleTitleAndDescriptionContainer
import jp.simplespace.guildFeatureService
import net.dv8tion.jda.api.interactions.DiscordLocale
import net.dv8tion.jda.api.interactions.commands.OptionType
import net.dv8tion.jda.api.interactions.commands.build.OptionData
import net.dv8tion.jda.api.interactions.commands.build.SubcommandGroupData
import java.awt.Color

private fun featureSubcommandGroup(): SubcommandGroupData =
    SubcommandGroupData("feature", "サーバーごとの機能フラグを管理します")
        .setNameLocalization(DiscordLocale.JAPANESE, "機能フラグ")

private fun featureChoiceOption(): OptionData {
    val option = OptionData(OptionType.STRING, "feature", "対象のFeature Flag", true)
        .setNameLocalization(DiscordLocale.JAPANESE, "機能")
    FeatureKey.entries.forEach {
        option.addChoice("${it.displayName} (${it.key})", it.key)
    }
    return option
}

class FeatureEnableMod : SlashCommand() {
    init {
        name = "enable"
        help = "指定サーバーで機能を有効化します。"
        ownerCommand = true
        subcommandGroup = featureSubcommandGroup()
        options = listOf(
            featureChoiceOption(),
            OptionData(OptionType.STRING, "guild_id", "対象サーバーID（省略時は実行サーバー）", false)
                .setNameLocalization(DiscordLocale.JAPANESE, "サーバーid"),
        )
    }

    override fun execute(event: SlashCommandEvent) {
        val featureKey = event.optString("feature")?.let { FeatureKey.fromKey(it) }
        if (featureKey == null) {
            replyEphemeral(event, "エラー", "無効なFeature Keyです。", Color.RED)
            return
        }
        val guildId = event.optString("guild_id")?.trim()?.takeIf { it.isNotEmpty() } ?: event.guild?.id
        if (guildId == null) {
            replyEphemeral(event, "入力エラー", "サーバー外で実行する場合は `guild_id` を指定してください。", Color.RED)
            return
        }
        guildFeatureService.setEnabled(guildId, featureKey, true, event.user.id)
        val guildLabel = event.jda.getGuildById(guildId)?.name?.let { "$it (`$guildId`)" } ?: "`$guildId`"
        replyEphemeral(
            event,
            "機能を有効化しました",
            "サーバー $guildLabel で **${featureKey.displayName}** (`${featureKey.key}`) を **有効** にしました。",
            Color(0x2ECC71),
        )
    }
}

class FeatureDisableMod : SlashCommand() {
    init {
        name = "disable"
        help = "指定サーバーで機能を無効化します。"
        ownerCommand = true
        subcommandGroup = featureSubcommandGroup()
        options = listOf(
            featureChoiceOption(),
            OptionData(OptionType.STRING, "guild_id", "対象サーバーID（省略時は実行サーバー）", false)
                .setNameLocalization(DiscordLocale.JAPANESE, "サーバーid"),
        )
    }

    override fun execute(event: SlashCommandEvent) {
        val featureKey = event.optString("feature")?.let { FeatureKey.fromKey(it) }
        if (featureKey == null) {
            replyEphemeral(event, "エラー", "無効なFeature Keyです。", Color.RED)
            return
        }
        val guildId = event.optString("guild_id")?.trim()?.takeIf { it.isNotEmpty() } ?: event.guild?.id
        if (guildId == null) {
            replyEphemeral(event, "入力エラー", "サーバー外で実行する場合は `guild_id` を指定してください。", Color.RED)
            return
        }
        guildFeatureService.setEnabled(guildId, featureKey, false, event.user.id)
        val guildLabel = event.jda.getGuildById(guildId)?.name?.let { "$it (`$guildId`)" } ?: "`$guildId`"
        replyEphemeral(
            event,
            "機能を無効化しました",
            "サーバー $guildLabel で **${featureKey.displayName}** (`${featureKey.key}`) を **無効** にしました。",
            Color.ORANGE,
        )
    }
}

class FeatureListMod : SlashCommand() {
    init {
        name = "list"
        help = "サーバーの機能フラグ一覧、または各機能の有効サーバー一覧を表示します。"
        ownerCommand = true
        subcommandGroup = featureSubcommandGroup()
        options = listOf(
            OptionData(OptionType.STRING, "guild_id", "特定サーバーの状態を表示する場合に指定", false)
                .setNameLocalization(DiscordLocale.JAPANESE, "サーバーid"),
        )
    }

    override fun execute(event: SlashCommandEvent) {
        val guildId = event.optString("guild_id")?.trim()?.takeIf { it.isNotEmpty() }
        val description = if (guildId != null) {
            val guildLabel = event.jda.getGuildById(guildId)?.name?.let { "$it (`$guildId`)" } ?: "`$guildId`"
            buildString {
                append("対象サーバー: $guildLabel\n\n")
                FeatureKey.entries.forEach { key ->
                    val enabled = guildFeatureService.isEnabled(guildId, key)
                    val badge = if (enabled) "🟢 ON" else "⚫ OFF"
                    append("- $badge **${key.displayName}** (`${key.key}`)\n")
                }
            }.trim()
        } else {
            buildString {
                FeatureKey.entries.forEach { key ->
                    val enabledGuilds = guildFeatureService.listEnabledGuilds(key)
                    append("### ${key.displayName} (`${key.key}`)\n")
                    if (enabledGuilds.isEmpty()) {
                        append("- 有効化されているサーバーはありません\n")
                    } else {
                        enabledGuilds.forEach { gid ->
                            val name = event.jda.getGuildById(gid)?.name
                            append(if (name != null) "- $name (`$gid`)\n" else "- `$gid`\n")
                        }
                    }
                    append("\n")
                }
            }.trim()
        }

        replyEphemeral(event, "Feature Flag 一覧", description, Color(0x36B8FA))
    }
}

private fun replyEphemeral(event: SlashCommandEvent, title: String, description: String, color: Color) {
    event.replyComponents(simpleTitleAndDescriptionContainer(title, description, color))
        .useComponentsV2()
        .setEphemeral(true)
        .queue()
}
