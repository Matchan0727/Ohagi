package jp.simplespace.watch

import jp.simplespace.db.FeatureKey
import jp.simplespace.discord.OhagiBot
import jp.simplespace.discord.utils.simpleTitleAndDescriptionContainer
import jp.simplespace.guildFeatureService
import jp.simplespace.watchConfigService
import net.dv8tion.jda.api.Permission
import net.dv8tion.jda.api.entities.Guild
import net.dv8tion.jda.api.entities.Member
import net.dv8tion.jda.api.interactions.callbacks.IReplyCallback
import java.awt.Color

object WatchPermissions {
    fun isBotOwner(userId: String): Boolean {
        val client = OhagiBot.commandClient
        return client.ownerId == userId || (client.coOwnerIds?.contains(userId) == true)
    }

    fun isFeatureEnabled(guildId: String): Boolean =
        guildFeatureService.isEnabled(guildId, FeatureKey.WATCH_SYSTEM)

    fun isGuildWatchAdmin(guild: Guild, member: Member): Boolean {
        if (isBotOwner(member.id)) return true
        if (member.hasPermission(Permission.ADMINISTRATOR) || member.hasPermission(Permission.BAN_MEMBERS)) {
            return true
        }
        val managerRoleId = watchConfigService.getOrCreateConfig(guild.id).managerRoleId ?: return false
        return member.roles.any { it.id == managerRoleId }
    }

    /**
     * 監視対象者本人が自分自身に有利な管理操作を行うことを防ぐ（Botオーナーは例外的に介入可能）。
     */
    fun isSelfProtectionBlocked(actorUserId: String, targetUserId: String): Boolean {
        if (isBotOwner(actorUserId)) return false
        return actorUserId == targetUserId
    }

    fun ensureFeatureEnabledOrReply(event: IReplyCallback, guild: Guild?): Boolean {
        if (guild == null) {
            event.replyComponents(
                simpleTitleAndDescriptionContainer("利用できません", "このコマンドはサーバー内でのみ実行できます。", Color.RED)
            ).useComponentsV2().setEphemeral(true).queue()
            return false
        }
        if (!isFeatureEnabled(guild.id)) {
            event.replyComponents(
                simpleTitleAndDescriptionContainer(
                    "機能が無効です",
                    "このサーバーではメンバー監視機能（`watch_system`）が有効化されていません。\nボット管理者に `/mod feature enable` での有効化を依頼してください。",
                    Color.RED,
                )
            ).useComponentsV2().setEphemeral(true).queue()
            return false
        }
        return true
    }

    fun ensureAdminOrReply(event: IReplyCallback, guild: Guild, member: Member?): Boolean {
        if (member == null || !isGuildWatchAdmin(guild, member)) {
            event.replyComponents(
                simpleTitleAndDescriptionContainer(
                    "権限がありません",
                    "この操作を行うには `BAN_MEMBERS` / `ADMINISTRATOR` 権限、またはサーバー設定の監視管理者ロールが必要です。",
                    Color.RED,
                )
            ).useComponentsV2().setEphemeral(true).queue()
            return false
        }
        return true
    }
}
