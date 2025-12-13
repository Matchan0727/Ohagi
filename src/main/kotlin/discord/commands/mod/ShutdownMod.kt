package bot.command.slash.mod

import com.jagrosh.jdautilities.command.SlashCommand
import com.jagrosh.jdautilities.command.SlashCommandEvent
import net.dv8tion.jda.api.EmbedBuilder
import net.dv8tion.jda.api.OnlineStatus
import net.dv8tion.jda.api.entities.Activity
import net.dv8tion.jda.api.interactions.InteractionHook
import java.awt.Color

class ShutdownMod : SlashCommand() {
    init {
        name = "shutdown"
        help = "Botをシャットダウンします。"
        ownerCommand = true
    }

    public override fun execute(event: SlashCommandEvent) {
        println("シャットダウンを実行中です...")
        val embed = EmbedBuilder()
            .setColor(Color.YELLOW)
            .setTitle("シャットダウンを実行します")
            .build()
        event.jda.presence.setPresence(OnlineStatus.DO_NOT_DISTURB, Activity.playing("シャットダウン処理中..."))
        event.replyEmbeds(embed).setEphemeral(true).queue { hook: InteractionHook? ->
            event.client.shutdown()
            event.jda.shutdown()
            System.exit(0)
        }
    }
}
