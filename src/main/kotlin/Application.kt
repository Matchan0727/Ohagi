package jp.simplespace

import calendar.CalendarInfoGenerator
import io.ktor.server.application.*
import jp.simplespace.discord.configureDiscord

fun main(args: Array<String>) {
    // Ktorの処理
    io.ktor.server.netty.EngineMain.main(args)
}

fun Application.module() {
    configureTemplating()
    configureSerialization()
    configureDatabases()
    configureRouting()
    configureDiscord()
}
