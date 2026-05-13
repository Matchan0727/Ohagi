package jp.simplespace.discord.commands.audio

import com.jagrosh.jdautilities.command.SlashCommand
import com.jagrosh.jdautilities.command.SlashCommandEvent
import jp.simplespace.discord.utils.simpleTitleAndDescriptionContainer
import jp.simplespace.discord.voice.OhagiAudioManager
import java.awt.Color

class ByeCommand : SlashCommand() {
    init {
        this.name = "bye"
        this.help = "ボイスチャンネルから切断します。"

    }
    override fun execute(event: SlashCommandEvent) {
        val voiceState = event.guild!!.selfMember.voiceState
        // すでにVCに参加している場合はメッセージを返して終了
        if (voiceState == null || !voiceState.inAudioChannel()) {
            event.replyComponents(
                simpleTitleAndDescriptionContainer("ボイスチャンネルにいません",
                    "Botはすでにボイスチャンネルから切断しています。",
                    Color.ORANGE))
                .useComponentsV2()
                .setEphemeral(true)
                .queue()
            return
        }
        OhagiAudioManager.destroyGuildAudio(event.guild!!.id)
        event.replyComponents(
            simpleTitleAndDescriptionContainer("ボイスチャンネルから切断",
                "ボイスチャンネルから切断しました。"))
            .useComponentsV2()
            .queue()
    }
}