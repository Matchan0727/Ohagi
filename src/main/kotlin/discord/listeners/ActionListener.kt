package jp.simplespace.discord.listeners

import calendar.CalendarInfoGenerator
import jp.simplespace.db.FeatureKey
import jp.simplespace.discord.OhagiBot
import jp.simplespace.discord.utils.simpleTitleAndDescriptionContainer
import jp.simplespace.guildFeatureService
import kotlinx.coroutines.launch
import net.dv8tion.jda.api.events.interaction.component.ButtonInteractionEvent
import net.dv8tion.jda.api.hooks.ListenerAdapter
import java.awt.Color

class ActionListener : ListenerAdapter() {

    override fun onButtonInteraction(event: ButtonInteractionEvent) {
        val id = event.button.customId ?: return
        when {
            id.startsWith("calpage_") -> {
                val page = id.substringAfter("calpage_").toIntOrNull() ?: return
                updateCalendar(event) { CalendarInfoGenerator.generateContainer(page = page) }
            }
            id.startsWith("calrefresh_") -> {
                val page = id.substringAfter("calrefresh_").toIntOrNull() ?: 0
                updateCalendar(event) { CalendarInfoGenerator.generateContainer(page = page, forceRefresh = true) }
            }
            id == "calrefresh" -> {
                updateCalendar(event) { CalendarInfoGenerator.generateContainer(page = 0, forceRefresh = true) }
            }
            id == "calreset" || id.startsWith("calnext_") || id.startsWith("calprev_") -> {
                updateCalendar(event) { CalendarInfoGenerator.generateContainer(page = 0) }
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
        val guildId = event.guild?.id
        if (guildId == null || !guildFeatureService.isEnabled(guildId, FeatureKey.CALENDAR)) {
            event.replyComponents(
                simpleTitleAndDescriptionContainer(
                    "利用できません",
                    "このサーバーでは `/calendar` 機能が無効になっています。",
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