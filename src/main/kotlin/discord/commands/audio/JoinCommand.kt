package jp.simplespace.discord.commands.audio

import com.jagrosh.jdautilities.command.SlashCommand
import com.jagrosh.jdautilities.command.SlashCommandEvent
import jp.simplespace.discord.utils.simpleTitleAndDescriptionContainer
import jp.simplespace.discord.voice.OhagiAudioManager
import net.dv8tion.jda.api.entities.channel.ChannelType
import net.dv8tion.jda.api.interactions.DiscordLocale
import net.dv8tion.jda.api.interactions.commands.OptionType
import net.dv8tion.jda.api.interactions.commands.build.OptionData
import java.awt.Color

class JoinCommand : SlashCommand() {
    init {
        this.name = "join"
        this.help = "ボイスチャンネルに参加します"
        this.options = listOf(
            OptionData(OptionType.CHANNEL, "vc", "参加するボイスチャンネルを指定します", false)
                .setChannelTypes(ChannelType.VOICE)
                .setNameLocalization(DiscordLocale.JAPANESE, "ボイスチャンネル")
        )
    }

    override fun execute(event: SlashCommandEvent) {
        val voiceState = event.guild!!.selfMember.voiceState
        // すでにVCに参加している場合はメッセージを返して終了
        if (voiceState != null && voiceState.inAudioChannel()) {
            event.replyComponents(
                simpleTitleAndDescriptionContainer("ボイスチャンネルに参加済み",
                    "Botはすでにボイスチャンネルに参加しています。",
                    Color.ORANGE))
                .setEphemeral(true)
                .queue()
            return
        }

        val authorVoiceState = event.member!!.voiceState
        val vc = event.optGuildChannel("vc")
        if(authorVoiceState==null || (!authorVoiceState.inAudioChannel() && vc == null)) {
            event.replyComponents(
                simpleTitleAndDescriptionContainer("ボイスチャンネルに未参加",
                    "ボイスチャンネルに参加してからこのコマンドを使用してください。",
                    Color.ORANGE))
                .setEphemeral(true)
                .queue()
            return
        }
        val ch = vc ?: authorVoiceState.channel!!
        authorVoiceState.channel.let {
            OhagiAudioManager.setupGuildAudio(event.guild!!, ch.id)
        }
        event.replyComponents(
            simpleTitleAndDescriptionContainer("ボイスチャンネルに参加",
                "${ch.asMention}に参加しました！",
                Color.GREEN))
            .queue()
    }
}