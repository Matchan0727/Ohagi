package command

import calendar.CalendarInfoGenerator
import com.jagrosh.jdautilities.command.SlashCommand
import com.jagrosh.jdautilities.command.SlashCommandEvent
import jp.simplespace.commandPermissionService
import jp.simplespace.discord.OhagiBot
import jp.simplespace.discord.utils.simpleTitleAndDescriptionContainer
import kotlinx.coroutines.launch
import net.dv8tion.jda.api.interactions.InteractionContextType
import java.awt.Color

class CalendarCommand : SlashCommand() {
    init {
        this.name = "calendar"
        this.help = "いろんな人の予定を表示します"
        this.contexts = InteractionContextType.ALL.toTypedArray()
    }

    override fun execute(event: SlashCommandEvent) {
        if (!commandPermissionService.isAllowed(name, event.guild?.id, event.channel.id)) {
            event.replyComponents(
                simpleTitleAndDescriptionContainer(
                    "利用できません",
                    "このサーバーまたはチャンネルでは `/calendar` コマンドを使用できません。",
                    Color.RED,
                )
            )
                .useComponentsV2()
                .setEphemeral(true)
                .queue()
            return
        }

        event.deferReply().queue { hook ->
            OhagiBot.scope.launch {
                try {
                    val container = CalendarInfoGenerator.generateContainer()
                    hook.editOriginalComponents(container).setAllowedMentions(emptyList()).useComponentsV2().queue()
                } catch (e: Exception) {
                    OhagiBot.logger.error("カレンダー取得中にエラーが発生しました", e)
                    hook.editOriginalComponents(
                        simpleTitleAndDescriptionContainer("エラー", "カレンダーの取得中にエラーが発生しました。", Color.RED)
                    ).useComponentsV2().queue()
                }
            }
        }
    }
}