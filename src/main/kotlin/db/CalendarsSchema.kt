package jp.simplespace.db

import kotlinx.serialization.Serializable
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction

@Serializable
data class Calendar(val discordId: String, val calendarId: String)

class CalendarService(private val db: Database) {
    object Calendars : Table() {
        val discordId = varchar("discord_id", 64)
        val calendarId = varchar("calendar_id", 256)

        override val primaryKey = PrimaryKey(discordId, calendarId)
    }

    init {
        transaction(db) {
            SchemaUtils.create(Calendars)
        }
    }

    fun getIdMapping(): Map<String, String> {
        return transaction(db) {
            Calendars.selectAll().associate { it[Calendars.discordId] to it[Calendars.calendarId] }
        }
    }
}