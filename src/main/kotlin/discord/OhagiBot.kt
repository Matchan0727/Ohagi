package jp.simplespace.discord

import bot.command.slash.mod.ModCommand
import calendar.CalendarController
import calendar.CalendarInfoGenerator
import com.jagrosh.jdautilities.command.CommandClient
import com.jagrosh.jdautilities.command.CommandClientBuilder
import com.jagrosh.jdautilities.commons.waiter.EventWaiter
import command.BaitoCommand
import command.CalendarCommand
import dev.minn.jda.ktx.events.listener
import dev.minn.jda.ktx.jdabuilder.default
import dev.minn.jda.ktx.jdabuilder.intents
import io.ktor.server.application.*
import io.ktor.server.config.ApplicationConfig
import jp.simplespace.discord.commands.HelpCommand
import jp.simplespace.discord.listeners.ActionListener
import net.dv8tion.jda.api.JDA
import net.dv8tion.jda.api.OnlineStatus
import net.dv8tion.jda.api.events.session.ReadyEvent
import net.dv8tion.jda.api.requests.GatewayIntent
import net.dv8tion.jda.api.utils.MemberCachePolicy
import net.dv8tion.jda.api.utils.cache.CacheFlag
import org.slf4j.Logger
import org.slf4j.LoggerFactory

fun Application.configureDiscord() {
    OhagiBot.initialize(environment.config)
}

object OhagiBot {

    lateinit var jda: JDA
    lateinit var config: ApplicationConfig
    lateinit var discordConfig: ApplicationConfig
    lateinit var commandClient: CommandClient
    val eventWaiter: EventWaiter = EventWaiter()
    val logger: Logger = LoggerFactory.getLogger(OhagiBot::class.java)

    private var isInitialized = false

    fun initialize(config: ApplicationConfig) {
        if (isInitialized) return

        this.config = config
        this.discordConfig = config.config("discord")
        jda = default(discordConfig.property("token").getString(), enableCoroutines = true) {
            intents += GatewayIntent.entries
            setMemberCachePolicy(MemberCachePolicy.ALL)
            enableCache(CacheFlag.entries)
            addEventListeners(
                eventWaiter,
                setupCommands(),
                ActionListener()
            )
        }

        jda.listener<ReadyEvent> {
            if (CalendarController.getCalendar() == null) {
                logger.warn("カレンダーが設定されていません。カレンダー機能は利用できません。")
            }
            else {
                logger.info("カレンダーは正常に設定されています。")
            }
        }

        isInitialized = true
    }

    private fun setupCommands(): CommandClient {
        commandClient = CommandClientBuilder()
            .setOwnerId(discordConfig.property("owner_id").getString())
            .useHelpBuilder(false)
            .setStatus(OnlineStatus.ONLINE)
            .addSlashCommands(
                HelpCommand(),
                ModCommand(config),
                BaitoCommand(),
                CalendarCommand(),
            )
            .build()
        return commandClient
    }
}