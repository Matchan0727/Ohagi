package jp.simplespace.discord.listeners

import calendar.CalendarInfoGenerator
import jp.simplespace.commandPermissionService
import jp.simplespace.discord.OhagiBot
import jp.simplespace.discord.utils.simpleTitleAndDescriptionContainer
import kotlinx.coroutines.launch
import net.dv8tion.jda.api.events.interaction.component.ButtonInteractionEvent
import net.dv8tion.jda.api.hooks.ListenerAdapter
import java.awt.Color

class ActionListener : ListenerAdapter() {

    override fun onButtonInteraction(event: ButtonInteractionEvent) {
        val id = event.button.customId ?: return
        when {
            id.startsWith("calnext_") -> {
                val timeMin = id.substringAfter("calnext_").toLongOrNull() ?: return
                updateCalendar(event) { CalendarInfoGenerator.generateContainer(timeMin = timeMin) }
            }
            id.startsWith("calprev_") -> {
                val timeMax = id.substringAfter("calprev_").toLongOrNull() ?: return
                updateCalendar(event) { CalendarInfoGenerator.generateContainer(timeMin = null, timeMax = timeMax) }
            }
            id == "calreset" -> {
                updateCalendar(event) { CalendarInfoGenerator.generateContainer() }
            }
            id == "calrefresh" -> {
                updateCalendar(event) { CalendarInfoGenerator.generateContainer(forceRefresh = true) }
            }
            id == "clear" -> {
                event.deferEdit().queue {
                    it.deleteOriginal().queue()
                }
            }
        }
    }

    private fun updateCalendar(
        event: ButtonInteractionEvent,
        generator: suspend () -> net.dv8tion.jda.api.components.container.Container,
    ) {
        if (!commandPermissionService.isAllowed("calendar", event.guild?.id, event.channel.id)) {
            event.replyComponents(
                simpleTitleAndDescriptionContainer(
                    "利用できません",
                    "このサーバーまたはチャンネルでは `/calendar` を使用できません。",
                    Color.RED,
                )
            )
                .useComponentsV2()
                .setEphemeral(true)
                .queue()
            return
        }

        event.deferEdit().useComponentsV2().queue { hook ->
            OhagiBot.scope.launch {
                try {
                    val container = generator()
                    hook.editOriginalComponents(container).setAllowedMentions(emptyList()).useComponentsV2().queue()
                } catch (e: Exception) {
                    OhagiBot.logger.error("カレンダー更新中にエラーが発生しました", e)
                    hook.editOriginalComponents(
                        simpleTitleAndDescriptionContainer("エラー", "カレンダーの取得中にエラーが発生しました。", Color.RED)
                    ).useComponentsV2().queue()
                }
            }
        }
    }
}