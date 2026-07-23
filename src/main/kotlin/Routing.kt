package jp.simplespace

import io.ktor.server.application.*
import io.ktor.server.request.receive
import io.ktor.server.request.receiveText
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.json.Json
import java.util.logging.Logger

fun Application.configureRouting() {
    routing {
        get("/") {
            call.respondText("Hello World!")
        }
        post("/switchbot") {
            val request = call.receiveText()
            Logger.getGlobal().info("Switchbot request received: $request")
        }
    }
}
