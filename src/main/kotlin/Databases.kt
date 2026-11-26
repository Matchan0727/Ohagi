package jp.simplespace

import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import jp.simplespace.db.CalendarService
import jp.simplespace.db.CommandPermissionService
import jp.simplespace.db.ExposedUser
import jp.simplespace.db.GuildFeatureService
import jp.simplespace.db.UserService
import jp.simplespace.db.TextSpeakerService
import jp.simplespace.db.WatchAiService
import jp.simplespace.db.WatchConfigService
import jp.simplespace.db.WatchCycleService
import jp.simplespace.db.WatchVoteTicketService
import org.jetbrains.exposed.v1.jdbc.Database

lateinit var database: Database
lateinit var calendarService: CalendarService
lateinit var textSpeakerService: TextSpeakerService
lateinit var commandPermissionService: CommandPermissionService
lateinit var guildFeatureService: GuildFeatureService
lateinit var watchConfigService: WatchConfigService
lateinit var watchCycleService: WatchCycleService
lateinit var watchVoteTicketService: WatchVoteTicketService
lateinit var watchAiService: WatchAiService

fun Application.configureDatabases() {
    val config = environment.config.config("db")
    database = Database.connect(
        driver = "com.mysql.cj.jdbc.Driver",
        url = config.property("url").getString(),
        user = config.property("user").getString(),
        password = config.property("password").getString(),
    )
    val userService = UserService(database)
    calendarService = CalendarService(database)
    textSpeakerService = TextSpeakerService(database)
    commandPermissionService = CommandPermissionService(database)
    guildFeatureService = GuildFeatureService(database)
    watchConfigService = WatchConfigService(database)
    watchCycleService = WatchCycleService(database)
    watchVoteTicketService = WatchVoteTicketService(database)
    watchAiService = WatchAiService(database)
    routing {
        // Create user
        post("/users") {
            val user = call.receive<ExposedUser>()
            val id = userService.create(user)
            call.respond(HttpStatusCode.Created, id)
        }

        // Read user
        get("/users/{id}") {
            val id = call.parameters["id"]?.toInt() ?: throw IllegalArgumentException("Invalid ID")
            val user = userService.read(id)
            if (user != null) {
                call.respond(HttpStatusCode.OK, user)
            } else {
                call.respond(HttpStatusCode.NotFound)
            }
        }

        // Update user
        put("/users/{id}") {
            val id = call.parameters["id"]?.toInt() ?: throw IllegalArgumentException("Invalid ID")
            val user = call.receive<ExposedUser>()
            userService.update(id, user)
            call.respond(HttpStatusCode.OK)
        }

        // Delete user
        delete("/users/{id}") {
            val id = call.parameters["id"]?.toInt() ?: throw IllegalArgumentException("Invalid ID")
            userService.delete(id)
            call.respond(HttpStatusCode.OK)
        }
    }
}
