package jp.simplespace

import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.*
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.request.receive
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.websocket.WebSocketDeflateExtension.Companion.install
import kotlinx.serialization.json.Json
import java.util.logging.Logger

fun Application.configureRouting() {
    install(ContentNegotiation) {
        json()
    }
    routing {
        get("/") {
            call.respondText("Hello World!")
        }
        post("/switchbot") {
            val request = call.receive<Json>()
            Logger.getGlobal().info("Switchbot request received: $request")
        }
    }
}
