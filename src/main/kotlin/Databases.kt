package jp.simplespace

import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import jp.simplespace.db.CalendarService
import jp.simplespace.db.ExposedUser
import jp.simplespace.db.UserService
import org.jetbrains.exposed.v1.jdbc.Database

lateinit var database: Database

fun Application.configureDatabases() {
    val config = environment.config.config("db")
    database = Database.connect(
        driver = "com.mysql.cj.jdbc.Driver",
        url = config.property("url").getString(),
        user = config.property("user").getString(),
        password = config.property("password").getString(),
    )
    val userService = UserService(database)
    CalendarService(database)
    install(ContentNegotiation) {
        json()
    }
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
