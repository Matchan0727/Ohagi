package jp.simplespace.discord.utils

import com.jagrosh.jdautilities.command.SlashCommand
import jp.simplespace.discord.OhagiBot
import net.dv8tion.jda.api.EmbedBuilder
import net.dv8tion.jda.api.JDA
import net.dv8tion.jda.api.entities.*
import net.dv8tion.jda.api.interactions.IntegrationType
import net.dv8tion.jda.api.interactions.InteractionContextType
import net.dv8tion.jda.api.interactions.commands.Command
import net.dv8tion.jda.api.utils.TimeFormat
import java.awt.Color
import java.lang.Exception
import java.util.*

object Embeds {
    fun getHelps(retCommands: List<Command>, slashCommands: List<SlashCommand>,
                 contexts: Array<InteractionContextType>, isUserInstall: Boolean, user: User): Collection<MessageEmbed> {
        val retMap: MutableMap<String, Command> = mutableMapOf()
        for (command in retCommands) {
            if (command.type != Command.Type.SLASH) continue
            retMap[command.name] = command
        }
        val eb = EmbedBuilder()
            .setTitle("Help Menu")
            .setColor(0x36b8fa)
        //ここからページ分割
        val ebList = arrayListOf<EmbedBuilder>()
        var cnt = 0
        for (command: SlashCommand in slashCommands) {
            if (command.isOwnerCommand && !(OhagiBot.commandClient.ownerId == user.id || OhagiBot.commandClient.coOwnerIds.contains(user.id))) continue
            var containsContext = false
            for (context in command.contexts) {
                if (contexts.contains(context)) {
                    containsContext = true
                    break
                }
            }
            if (!containsContext) continue
            val types = retMap[command.name]!!.integrationTypes
            if (types.size == 1 && types.contains(IntegrationType.USER_INSTALL) && !isUserInstall) continue
            if (command.getChildren().isEmpty() && command.subcommandGroup == null) {
                eb.addField("</${command.name}:${retMap[command.name]?.id}>", command.help, true)
                cnt++
            } else {
                for (subcommand: SlashCommand in command.children) {
                    if (subcommand.subcommandGroup != null) {
                        eb.addField(
                            "</${command.name} ${subcommand.subcommandGroup.name} ${subcommand.name}:${retMap[command.name]?.id}>"
                            , subcommand.help, true
                        )
                    } else eb.addField("</${command.name} ${subcommand.name}:${retMap[command.name]?.id}>", subcommand.help, true)
                    cnt++
                }
            }
            if (cnt > 10) {
                val newEb = EmbedBuilder()
                newEb.copyFrom(eb)
                ebList.add(newEb)
                eb.clearFields()
                cnt = 0
            }
        }
        if (cnt > 0) {
            ebList.add(eb)
        }
        val results = arrayListOf<MessageEmbed>()
        for ((i, e) in ebList.withIndex()) {
            e.setFooter("${i + 1}/${ebList.size}")
            results.add(e.build())
        }
        return results
    }

    fun getExceptionError(): MessageEmbed {
            val eb: EmbedBuilder = EmbedBuilder()
            eb.setTitle("予期せぬエラーが発生しました").setColor(Color.RED)
            return eb.build()
    }

    fun getExceptionError(e: Exception): MessageEmbed {
        val eb: EmbedBuilder = EmbedBuilder()
        eb.setTitle("予期せぬエラーが発生しました\n```" + e + "```\n").setColor(Color.RED)
        return eb.build()
    }

    fun getCustomError(title: String?, error: String?): MessageEmbed {
        val eb: EmbedBuilder = EmbedBuilder()
        eb.setColor(Color.RED)
            .setTitle(title)
            .setDescription("エラー内容\n```" + error + "```\n")
        return eb.build()
    }

    fun getCustomSuccess(title: String?, success: String?): MessageEmbed {
        val eb: EmbedBuilder = EmbedBuilder()
        eb.setColor(Color.GREEN)
            .setTitle(title)
            .setDescription(success)
        return eb.build()
    }

    val waiting: MessageEmbed
        get() {
            return getWaiting("取得しています...")
        }

    fun getWaiting(str: String?): MessageEmbed {
        val eb: EmbedBuilder = EmbedBuilder()
        eb.setColor(Color.GRAY)
            .setTitle(str)
        return eb.build()
    }
}
