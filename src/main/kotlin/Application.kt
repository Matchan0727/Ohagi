package jp.simplespace

import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.*
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import jp.simplespace.discord.configureDiscord

fun main(args: Array<String>) {
    // Ktorの処理
    io.ktor.server.netty.EngineMain.main(args)
}

fun Application.module() {
    install(ContentNegotiation) {
        json()
    }
    configureTemplating()
    configureSerialization()
    configureDatabases()
    configureRouting()
    configureDiscord()
}
