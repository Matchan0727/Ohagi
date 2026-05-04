package command

import calendar.CalendarInfoGenerator
import com.jagrosh.jdautilities.command.SlashCommand
import com.jagrosh.jdautilities.command.SlashCommandEvent
import net.dv8tion.jda.api.interactions.InteractionContextType

class CalendarCommand : SlashCommand() {
    init {
        this.name = "calendar"
        this.help = "いろんな人の予定を表示します"
        this.contexts = InteractionContextType.ALL.toTypedArray()
    }

    override fun execute(event: SlashCommandEvent) {
        event.deferReply().queue {
            val container = CalendarInfoGenerator.generateContainer()
            it.editOriginalComponents(container).setAllowedMentions(emptyList()).useComponentsV2().queue()
        }
    }
}