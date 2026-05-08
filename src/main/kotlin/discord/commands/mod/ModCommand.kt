package bot.command.slash.mod

import com.jagrosh.jdautilities.command.SlashCommand
import com.jagrosh.jdautilities.command.SlashCommandEvent
import io.ktor.server.config.*

class ModCommand(config: ApplicationConfig) : SlashCommand() {
    init {
        name = "mod"
        help = "モデレーター用コマンド"
        ownerCommand = true
        children = arrayOf(
            ShutdownMod(),
            EvalMod(),
            SQLMod(config.config("db")),
        )
    }

    public override fun execute(event: SlashCommandEvent) {}
}
