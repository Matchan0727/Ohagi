package command

import calendar.CalendarInfoGenerator
import com.jagrosh.jdautilities.command.SlashCommand
import com.jagrosh.jdautilities.command.SlashCommandEvent

class CalendarCommand : SlashCommand() {
    init {
        this.name = "calendar"
        this.help = "いろんな人の予定を表示します"
    }

    override fun execute(event: SlashCommandEvent) {
        event.deferReply().queue {
            val container = CalendarInfoGenerator.generateContainer()
            it.editOriginalComponents(container).setAllowedMentions(emptyList()).useComponentsV2().queue()
        }
    }
}