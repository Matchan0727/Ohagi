package bot.command.slash.mod

import com.jagrosh.jdautilities.command.SlashCommand
import com.jagrosh.jdautilities.command.SlashCommandEvent
import com.mysql.cj.jdbc.MysqlDataSource
import io.ktor.server.config.*
import jp.simplespace.discord.utils.Embeds
import jp.simplespace.discord.utils.ResultSetToJsonMapper
import net.dv8tion.jda.api.EmbedBuilder
import net.dv8tion.jda.api.interactions.InteractionHook
import net.dv8tion.jda.api.interactions.commands.OptionType
import net.dv8tion.jda.api.interactions.commands.build.OptionData
import java.awt.Color
import java.util.*

class SQLMod(val dbConfig: ApplicationConfig) : SlashCommand() {
    init {
        name = "sql"
        help = "指定データベースからSQLコマンドを実行します。"
        ownerCommand = true
        val options: MutableList<OptionData> = ArrayList()
        options.add(OptionData(OptionType.STRING, "sql", "SQL文", true, true))
        this.options = options
    }

    public override fun execute(event: SlashCommandEvent) {
        event.deferReply().queue { hook: InteractionHook ->
            try {
                val sql = event.getOption("sql")!!.asString
                val ds = MysqlDataSource()
                ds.setUrl(dbConfig.property("url").getString())
                ds.user = dbConfig.property("user").getString()
                ds.password = dbConfig.property("password").getString()
                val con = ds.connection
                val stmt = con.createStatement()
                val rs = stmt.executeQuery(sql)
                val jarray = ResultSetToJsonMapper.mapResultSet(rs)
                rs.close()
                stmt.close()
                con.close()
                val eb = EmbedBuilder()
                val sb = StringBuilder()
                sb.append(jarray).setLength(1000)
                eb.setTitle("SQLの実行結果")
                    .setColor(Color.decode("#36b8fa"))
                    .setTimestamp(Date().toInstant())
                    .setDescription("```json\n$sb\n```")
                hook.editOriginalEmbeds(eb.build()).queue()
            } catch (e: Exception) {
                hook.editOriginalEmbeds(Embeds.getCustomError("例外が発生しました", e.localizedMessage)).queue()
                throw RuntimeException(e)
            }
        }
    }
}
