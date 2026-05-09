package jp.simplespace.discord.voice

import com.sedmelluq.discord.lavaplayer.container.wav.WavAudioTrack
import com.sedmelluq.discord.lavaplayer.player.AudioLoadResultHandler
import com.sedmelluq.discord.lavaplayer.player.AudioPlayer
import com.sedmelluq.discord.lavaplayer.tools.FriendlyException
import com.sedmelluq.discord.lavaplayer.track.AudioPlaylist
import com.sedmelluq.discord.lavaplayer.track.AudioTrack
import jp.simplespace.AudioPlayerSendHandler
import jp.simplespace.discord.OhagiBot
import net.dv8tion.jda.api.entities.Guild
import net.dv8tion.jda.api.managers.AudioManager
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardOpenOption
import kotlin.io.path.outputStream
import kotlin.io.path.pathString

object OhagiAudioManager {
    val bot: OhagiBot = OhagiBot
    private val guildMap = mutableMapOf<String, GuildAudioManager>()

    fun setupGuildAudio(guild: Guild, ttsChannelId: String? = null) {
        val audioPlayer = bot.audioPlayerManager.createPlayer()
        val scheduler = TrackScheduler(audioPlayer)
        audioPlayer.addListener(scheduler)
        val audioManager = guild.audioManager
        audioManager.sendingHandler = AudioPlayerSendHandler(audioPlayer)
        guildMap[guild.id] = GuildAudioManager(
            audioManager,
            ttsChannelId,
            audioPlayer,
            scheduler,
        )
        if (ttsChannelId != null) {
            audioManager.openAudioConnection(guild.getVoiceChannelById(ttsChannelId)!!)
        }
    }

    fun destroyGuildAudio(guildId: String) {
        val info = guildMap[guildId]?: return
        guildMap.remove(guildId)
        info.audioManager.sendingHandler = null
        info.audioManager.closeAudioConnection()
        info.audioPlayer.destroy()
    }

    fun getAudioManager(guildId: String): GuildAudioManager? {
        return guildMap[guildId]
    }

    class GuildAudioManager(
        val audioManager: AudioManager,
        var ttsChannelId: String?,
        val audioPlayer: AudioPlayer,
        val scheduler: TrackScheduler,
    ) {
        fun playByteArray(bytes: ByteArray, id: String = System.nanoTime().toString(), ext: String = "wav") {
            val tmpFilePath = Files.createTempFile(Paths.get(System.getProperty("java.io.tmpdir")), id,".$ext")
            tmpFilePath.outputStream().use {
                it.write(bytes)
            }
            tmpFilePath.toFile().deleteOnExit()
            play(tmpFilePath.pathString)
        }

        fun play(identifier: String) {
            bot.audioPlayerManager.loadItem(identifier,object : AudioLoadResultHandler {
                override fun trackLoaded(track: AudioTrack) {
                    scheduler.queue(track)
                }

                override fun playlistLoaded(trackList: AudioPlaylist) {
                    for(track in trackList.tracks) {
                        scheduler.queue(track)
                    }
                }

                override fun noMatches() {
                }

                override fun loadFailed(e: FriendlyException) {
                    bot.logger.error("TrackLoadFailed:"+e.localizedMessage)
                }
            })
        }
    }
}