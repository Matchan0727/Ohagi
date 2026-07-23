package jp.simplespace

import data.SwitchBotEvent
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.*
import io.ktor.server.request.receive
import io.ktor.server.request.receiveText
import io.ktor.server.response.*
import io.ktor.server.routing.*
import jp.simplespace.discord.OhagiBot
import kotlinx.serialization.json.Json
import java.util.logging.Logger

fun Application.configureRouting() {
    routing {
        get("/") {
            call.respondText("Hello World!")
        }
        post("/switchbot") {
            val event = call.receive<SwitchBotEvent>()
            if (event.context.deviceMac != environment.config.config("switchbot").property("mac_address").getString()) {
                call.respondText("Bad", status = HttpStatusCode.BadRequest)
                return@post
            }
            OhagiBot.currentSwitchBotEvent = event
            call.respondText("OK", status = HttpStatusCode.OK)
            Logger.getGlobal().info("SwitchBot request received: ${Json.encodeToString(event)}")
        }
    }
}
