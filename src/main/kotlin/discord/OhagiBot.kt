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
import data.SwitchBotEvent
import dev.minn.jda.ktx.events.listener
import dev.minn.jda.ktx.jdabuilder.default
import dev.minn.jda.ktx.jdabuilder.intents
import io.ktor.server.application.*
import io.ktor.server.config.*
import jp.simplespace.discord.commands.HelpCommand
import jp.simplespace.discord.commands.audio.ByeCommand
import jp.simplespace.discord.commands.audio.JoinCommand
import jp.simplespace.discord.commands.audio.RvcCommand
import jp.simplespace.discord.commands.audio.SetSpeakerCommand
import jp.simplespace.discord.listeners.ActionListener
import jp.simplespace.audio.RvcApiClient
import net.dv8tion.jda.api.JDA
import net.dv8tion.jda.api.OnlineStatus
import net.dv8tion.jda.api.audio.AudioModuleConfig
import net.dv8tion.jda.api.entities.Activity
import net.dv8tion.jda.api.events.session.ReadyEvent
import net.dv8tion.jda.api.requests.GatewayIntent
import net.dv8tion.jda.api.utils.MemberCachePolicy
import net.dv8tion.jda.api.utils.cache.CacheFlag
import net.dv8tion.jda.api.utils.messages.MessageRequest
import okhttp3.OkHttpClient
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.util.concurrent.TimeUnit

fun Application.configureDiscord() {
    OhagiBot.initialize(environment.config)
}

object OhagiBot {

    lateinit var jda: JDA
    lateinit var config: ApplicationConfig
    lateinit var discordConfig: ApplicationConfig
    lateinit var commandClient: CommandClient
    lateinit var rvcApiClient: RvcApiClient
    val eventWaiter: EventWaiter = EventWaiter()
    val logger: Logger = LoggerFactory.getLogger(OhagiBot::class.java)
    val audioPlayerManager: AudioPlayerManager = DefaultAudioPlayerManager()
    var currentSwitchBotEvent: SwitchBotEvent? = null
        set(value) {
            field = value?: return
            val temp = value.context.temperature
            val humidity = value.context.humidity
            jda.presence.activity = Activity.customStatus("🌡️${temp}℃ 💧${humidity}%")
        }

    private var isInitialized = false

    fun initialize(config: ApplicationConfig) {
        if (isInitialized) return

        this.config = config
        this.discordConfig = config.config("discord")
        setupAudioPlayerManager()
        rvcApiClient = setupRvcApiClient()
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
            setHttpClientBuilder(
                OkHttpClient.Builder()
                    .connectTimeout(60, TimeUnit.SECONDS)
                    .readTimeout(60, TimeUnit.SECONDS)
                    .writeTimeout(60, TimeUnit.SECONDS)
            )
        }

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
                SetSpeakerCommand(),
                RvcCommand(rvcApiClient),
            )
            .build()
        return commandClient
    }

    private fun setupRvcApiClient(): RvcApiClient {
        val baseUrl = config.property("rvc_api.base_url").getString()
        return RvcApiClient(baseUrl)
    }

    private fun setupAudioPlayerManager() {
        AudioSourceManagers.registerLocalSource(audioPlayerManager)
        AudioSourceManagers.registerRemoteSources(audioPlayerManager)
    }

}