package command

import calendar.OldBaitoInfoManager
import com.jagrosh.jdautilities.command.SlashCommand
import com.jagrosh.jdautilities.command.SlashCommandEvent

class BaitoCommand : SlashCommand() {
    init {
        this.name = "baito"
        this.help = "バイトの有無を表示します"
    }

    override fun execute(event: SlashCommandEvent) {
        event.reply(OldBaitoInfoManager().getMessageText()).useComponentsV2(false).queue()
    }
}