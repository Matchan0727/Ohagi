package jp.simplespace.discord.commands

import com.jagrosh.jdautilities.command.SlashCommand
import com.jagrosh.jdautilities.command.SlashCommandEvent
import com.jagrosh.jdautilities.menu.ButtonEmbedPaginator
import jp.simplespace.discord.OhagiBot
import jp.simplespace.discord.utils.Embeds
import net.dv8tion.jda.api.entities.Message
import net.dv8tion.jda.api.interactions.InteractionContextType
import net.dv8tion.jda.api.interactions.InteractionHook
import net.dv8tion.jda.api.interactions.commands.Command
import java.util.concurrent.TimeUnit

class HelpCommand : SlashCommand() {

    companion object {
        var commands: List<Command>? = null
    }

    init {
        name = "help"
        help = "コマンドのヘルプを表示します。"
        contexts = InteractionContextType.ALL.toTypedArray()
    }

    public override fun execute(event: SlashCommandEvent) {
        if (commands == null) {
            commands = event.jda.retrieveCommands().complete()
        }
        event.deferReply().useComponentsV2(false).queue { hook: InteractionHook ->
            val paginatorBuilder: ButtonEmbedPaginator.Builder = ButtonEmbedPaginator.Builder()
                .waitOnSinglePage(false)
                .setFinalAction { m: Message -> m.editMessageComponents().queue() }
                .setEventWaiter(OhagiBot.eventWaiter)
                .setTimeout(1, TimeUnit.MINUTES)
                .addItems(Embeds.getHelps(commands!!, event.client.slashCommands, arrayOf(event.context), event.integrationOwners.isUserIntegration, event.user))
            val paginator = paginatorBuilder.build()
            paginator.display(hook)
        }
    }
}