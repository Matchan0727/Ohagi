package jp.simplespace.discord.commands.audio

import com.jagrosh.jdautilities.command.SlashCommand
import com.jagrosh.jdautilities.command.SlashCommandEvent
import jp.simplespace.database
import jp.simplespace.db.TextSpeaker
import jp.simplespace.db.TextSpeakerService
import jp.simplespace.discord.utils.simpleTitleAndDescriptionContainer
import net.dv8tion.jda.api.interactions.DiscordLocale
import net.dv8tion.jda.api.interactions.commands.OptionType
import net.dv8tion.jda.api.interactions.commands.build.OptionData
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.awt.Color

class SetSpeakerCommand : SlashCommand() {

    val speakers: List<TextSpeaker>

    init {
        this.name = "setspeaker"
        this.help = "テキスト読み上げ機能の話者を設定します"
        speakers = transaction {
            TextSpeakerService.TextSpeakers.selectAll().map {
                TextSpeaker(it[TextSpeakerService.TextSpeakers.id], it[TextSpeakerService.TextSpeakers.name], it[TextSpeakerService.TextSpeakers.engine])
            }
        }
        this.options = listOf(
            OptionData(OptionType.STRING, "speaker", "読み上げる声の話者を選択します", true)
                .setNameLocalization(DiscordLocale.JAPANESE, "話者")
        )
    }

    override fun execute(event: SlashCommandEvent) {
        val speaker = event.optString("speaker")
        if (speaker.isNullOrBlank() || speakers.none { it.id == speaker }) {
            event.replyComponents(simpleTitleAndDescriptionContainer(
                "話者の選択が不正",
                "話者の選択が不正です。",
                Color.RED
            ))
                .setEphemeral(true).queue()
            return
        }
        
    }
}