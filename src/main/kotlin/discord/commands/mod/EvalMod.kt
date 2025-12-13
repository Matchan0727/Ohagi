package bot.command.slash.mod

import com.jagrosh.jdautilities.command.SlashCommand
import com.jagrosh.jdautilities.command.SlashCommandEvent
import jp.simplespace.discord.OhagiBot
import net.dv8tion.jda.api.EmbedBuilder
import net.dv8tion.jda.api.interactions.InteractionHook
import net.dv8tion.jda.api.interactions.commands.OptionType
import net.dv8tion.jda.api.interactions.commands.build.OptionData
import org.openjdk.nashorn.api.scripting.NashornScriptEngineFactory
import java.awt.Color
import java.util.*
import java.util.function.Consumer

class EvalMod : SlashCommand() {
    init {
        name = "eval"
        help = "コードを評価します。"
        ownerCommand = true
        val options: MutableList<OptionData> = ArrayList()
        options.add(OptionData(OptionType.STRING, "code", "実行したいコード", true, true))
        options.add(OptionData(OptionType.BOOLEAN, "silent", "結果を返信するかどうか", false, false))
        this.options = options
    }

    public override fun execute(e: SlashCommandEvent) {
        engine.put("event", e)
        engine.put("channel", e.channel)
        engine.put("jda", e.jda)
        engine.put("guild", e.guild)
        engine.put("user", e.user)
        engine.put("member", e.member)
        engine.put("bot", OhagiBot)
        engine.put("deleteConsumer", Consumer { hook: InteractionHook -> hook.deleteOriginal().queue() })
        val consumer = Consumer { hook: InteractionHook ->
            try {
                val out = engine.eval(e.getOption("code")!!.asString)
                val embed = EmbedBuilder()
                    .setTitle("コードの評価")
                    .setDescription("コードを評価しました。")
                    .addField("戻り値", "```java\n$out\n```", false)
                    .setColor(Color.decode("#36b8fa"))
                    .setTimestamp(Date().toInstant())
                    .build()
                hook.editOriginalEmbeds(embed).queue()
            } catch (e1: Exception) {
                val embed = EmbedBuilder()
                    .setTitle("コードの評価")
                    .setDescription("コードを評価しました。")
                    .addField("戻り値", "```java\n$e1\n```", false)
                    .setColor(Color.RED)
                    .setTimestamp(Date().toInstant())
                    .build()
                hook.editOriginalEmbeds(embed).queue()
            }
        }
        var isSilent = false
        if (e.getOption("silent") != null) isSilent = e.getOption("silent")!!.asBoolean
        if (!isSilent) e.deferReply().queue(consumer) else e.deferReply(true).queue(consumer)
    }

    companion object {
        private val engine = NashornScriptEngineFactory().getScriptEngine(EvalMod::class.java.getClassLoader())
    }
}