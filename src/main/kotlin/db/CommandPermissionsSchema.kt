package jp.simplespace.db

import kotlinx.serialization.Serializable
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction

@Serializable
data class CommandPermission(
    val commandName: String,
    val targetType: String,
    val targetId: String,
)

class CommandPermissionService(private val database: Database) {
    object CommandPermissions : Table("command_permissions") {
        val commandName = varchar("command_name", 64)
        val targetType = varchar("target_type", 16)
        val targetId = varchar("target_id", 64)

        override val primaryKey = PrimaryKey(commandName, targetType, targetId)
    }

    init {
        transaction(database) {
            SchemaUtils.create(CommandPermissions)
        }
    }

    fun getPermissions(commandName: String): List<CommandPermission> {
        return transaction(database) {
            CommandPermissions.selectAll()
                .where { CommandPermissions.commandName eq commandName }
                .map {
                    CommandPermission(
                        commandName = it[CommandPermissions.commandName],
                        targetType = it[CommandPermissions.targetType],
                        targetId = it[CommandPermissions.targetId],
                    )
                }
        }
    }

    /**
     * 対象が未登録なら追加して true、登録済みなら削除して false を返します。
     */
    fun toggle(commandName: String, targetType: String, targetId: String): Boolean {
        return transaction(database) {
            val exists = !CommandPermissions.selectAll()
                .where {
                    (CommandPermissions.commandName eq commandName) and
                        (CommandPermissions.targetType eq targetType) and
                        (CommandPermissions.targetId eq targetId)
                }
                .empty()

            if (exists) {
                CommandPermissions.deleteWhere {
                    (CommandPermissions.commandName eq commandName) and
                        (CommandPermissions.targetType eq targetType) and
                        (CommandPermissions.targetId eq targetId)
                }
                false
            } else {
                CommandPermissions.insert {
                    it[CommandPermissions.commandName] = commandName
                    it[CommandPermissions.targetType] = targetType
                    it[CommandPermissions.targetId] = targetId
                }
                true
            }
        }
    }

    /**
     * 許可設定が1件も登録されていない場合は制限なし（true）とします。
     */
    fun isAllowed(commandName: String, guildId: String?, channelId: String?): Boolean {
        val permissions = getPermissions(commandName)
        if (permissions.isEmpty()) return true
        return permissions.any { perm ->
            (perm.targetType == TYPE_GUILD && guildId != null && perm.targetId == guildId) ||
                (perm.targetType == TYPE_CHANNEL && channelId != null && perm.targetId == channelId)
        }
    }

    companion object {
        const val TYPE_GUILD = "guild"
        const val TYPE_CHANNEL = "channel"
    }
}
