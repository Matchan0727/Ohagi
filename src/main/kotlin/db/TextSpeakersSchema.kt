package jp.simplespace.db

import kotlinx.serialization.Serializable
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.*
import org.jetbrains.exposed.v1.jdbc.transactions.suspendTransaction
import org.jetbrains.exposed.v1.jdbc.transactions.transaction

@Serializable
data class TextSpeaker(val id: String, val name: String, val engine: String)

class TextSpeakerService(database: Database) {
    object TextSpeakers : Table() {
        val id = varchar("id", 64)
        val name = varchar("name", 255)
        val engine = varchar("engine", 32)

        override val primaryKey = PrimaryKey(id)
    }

    init {
        transaction(database) {
            SchemaUtils.create(TextSpeakers)
        }
    }

    suspend fun create(textSpeaker: TextSpeaker) = dbQuery {
        TextSpeakers.insert {
            it[id] = textSpeaker.id
            it[name] = textSpeaker.name
            it[engine] = textSpeaker.engine
        }
    }

    suspend fun read(id: String): TextSpeaker? {
        return dbQuery {
            TextSpeakers.selectAll()
                .where { TextSpeakers.id eq id }
                .map { TextSpeaker(it[TextSpeakers.id], it[TextSpeakers.name], it[TextSpeakers.engine]) }
                .singleOrNull()
        }
    }

    suspend fun update(id: String, textSpeaker: TextSpeaker) {
        dbQuery {
            TextSpeakers.update({ TextSpeakers.id eq id }) {
                it[name] = textSpeaker.name
                it[engine] = textSpeaker.engine
            }
        }
    }

    suspend fun delete(id: String) {
        dbQuery {
            TextSpeakers.deleteWhere { TextSpeakers.id eq id }
        }
    }

    private suspend fun <T> dbQuery(block: suspend () -> T): T =
        suspendTransaction { block() }
}