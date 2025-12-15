package jp.simplespace.db

import kotlinx.serialization.Serializable
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.transactions.transaction

@Serializable
data class Drive(
    val yoyaku: String,
    val zigen: String,
    val date: String,
    val time: String,
    val week: String,
)

class DriveService(private val db: Database) {
    object Drives: Table() {
        val yoyaku = varchar("yoyaku", 10)
        val zigen = varchar("zigen", 3)
        val date = varchar("date", 20)
        val time = varchar("time", 20)
        val week = varchar("week", 10)

        override val primaryKey: PrimaryKey = PrimaryKey(yoyaku, zigen)
    }

    init {
        transaction(db) {
            SchemaUtils.create(Drives)
        }
    }
}