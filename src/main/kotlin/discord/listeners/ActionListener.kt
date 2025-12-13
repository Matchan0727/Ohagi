package jp.simplespace.discord.listeners

import calendar.CalendarInfoGenerator
import net.dv8tion.jda.api.events.interaction.component.ButtonInteractionEvent
import net.dv8tion.jda.api.hooks.ListenerAdapter

class ActionListener : ListenerAdapter() {

    override fun onButtonInteraction(event: ButtonInteractionEvent) {
        val id = event.button.customId
        if (id == null) return
        if (id.startsWith("calnext")) {
            val timeMin = id.split("_")[1].toLong()
            event.deferEdit().useComponentsV2().queue {
                val container = CalendarInfoGenerator.generateContainer(timeMin = timeMin)
                it.editOriginalComponents(container).setAllowedMentions(emptyList()).useComponentsV2().queue()
            }
        }
        if (id.startsWith("calprev")) {
            val timeMax = id.split("_")[1].toLong()
            event.deferEdit().useComponentsV2().queue {
                val container = CalendarInfoGenerator.generateContainer(timeMin = null, timeMax = timeMax)
                it.editOriginalComponents(container).setAllowedMentions(emptyList()).useComponentsV2().queue()
            }
        }
        if (id == "calreset") {
            event.deferEdit().useComponentsV2().queue {
                val container = CalendarInfoGenerator.generateContainer()
                it.editOriginalComponents(container).setAllowedMentions(emptyList()).useComponentsV2().queue()
            }
        }
        if (id == "clear") {
            event.deferEdit().queue {
                it.deleteOriginal().queue()
            }
        }
    }
}