package jp.simplespace.discord

import bot.command.slash.mod.ModCommand
import club.minnced.discord.jdave.interop.JDaveSessionFactory
import com.jagrosh.jdautilities.command.CommandClient
import com.jagrosh.jdautilities.command.CommandClientBuilder
import com.jagrosh.jdautilities.commons.waiter.EventWaiter
import com.sedmelluq.discord.lavaplayer.player.AudioPlayerManager
import com.sedmelluq.discord.lavaplayer.player.DefaultAudioPlayerManager
import com.sedmelluq.discord.lavaplayer.source.AudioSourceManagers
import command.BaitoCommand
import command.CalendarCommand
import dev.minn.jda.ktx.events.listener
import dev.minn.jda.ktx.jdabuilder.default
import dev.minn.jda.ktx.jdabuilder.intents
import io.ktor.server.application.*
import io.ktor.server.config.*
import jp.simplespace.discord.commands.HelpCommand
import jp.simplespace.discord.commands.audio.ByeCommand
import jp.simplespace.discord.commands.audio.JoinCommand
import jp.simplespace.discord.listeners.ActionListener
import net.dv8tion.jda.api.JDA
import net.dv8tion.jda.api.OnlineStatus
import net.dv8tion.jda.api.audio.AudioModuleConfig
import net.dv8tion.jda.api.entities.Activity
import net.dv8tion.jda.api.events.session.ReadyEvent
import net.dv8tion.jda.api.requests.GatewayIntent
import net.dv8tion.jda.api.utils.MemberCachePolicy
import net.dv8tion.jda.api.utils.cache.CacheFlag
import net.dv8tion.jda.api.utils.messages.MessageRequest
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
    val audioPlayerManager: AudioPlayerManager = DefaultAudioPlayerManager()

    private var isInitialized = false

    fun initialize(config: ApplicationConfig) {
        if (isInitialized) return

        this.config = config
        this.discordConfig = config.config("discord")
        setupAudioPlayerManager()
        jda = default(discordConfig.property("token").getString(), enableCoroutines = true) {
            intents += GatewayIntent.entries
            setMemberCachePolicy(MemberCachePolicy.ALL)
            enableCache(CacheFlag.entries)
            addEventListeners(
                eventWaiter,
                setupCommands(),
                ActionListener()
            )
            setAudioModuleConfig(
                AudioModuleConfig().withDaveSessionFactory(JDaveSessionFactory())
            )
        }
        // デフォルトでCompoonentsV2を有効化
        MessageRequest.setDefaultUseComponentsV2(true)

        jda.listener<ReadyEvent> {
            logger.info("正常に動作しています。")
        }

        isInitialized = true
    }

    private fun setupCommands(): CommandClient {
        commandClient = CommandClientBuilder()
            .setOwnerId(discordConfig.property("owner_id").getString())
            .useHelpBuilder(false)
            .setStatus(OnlineStatus.ONLINE)
            .setActivity(Activity.customStatus("おはぎくんです。"))
            .addSlashCommands(
                HelpCommand(),
                ModCommand(config),
                BaitoCommand(),
                CalendarCommand(),
                JoinCommand(),
                ByeCommand(),
            )
            .build()
        return commandClient
    }

    private fun setupAudioPlayerManager() {
        AudioSourceManagers.registerLocalSource(audioPlayerManager)
        AudioSourceManagers.registerRemoteSources(audioPlayerManager)
    }

}