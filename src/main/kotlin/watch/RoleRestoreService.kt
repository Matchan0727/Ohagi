package jp.simplespace.watch

import jp.simplespace.db.SavedRoleInfo
import jp.simplespace.db.WatchCycle
import jp.simplespace.db.WatchCycleStatus
import jp.simplespace.discord.OhagiBot
import jp.simplespace.discord.utils.simpleTitleAndDescriptionContainer
import jp.simplespace.watchConfigService
import jp.simplespace.watchCycleService
import net.dv8tion.jda.api.Permission
import net.dv8tion.jda.api.entities.Guild
import net.dv8tion.jda.api.entities.Member
import net.dv8tion.jda.api.entities.UserSnowflake
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel
import java.awt.Color
import java.util.concurrent.TimeUnit

data class UnbanInviteResult(
    val success: Boolean,
    val message: String,
    val inviteUrl: String? = null,
    val dmSent: Boolean = false,
)

data class RoleRestoreReport(
    val restoredRoles: List<String>,
    val skippedRoles: List<String>,
    val nicknameRestored: Boolean,
)

data class RoleRenameReport(
    val renamed: List<String>,
    val failed: List<String>,
)

object RoleRestoreService {
    fun captureRoleSnapshot(guild: Guild, cycle: WatchCycle) {
        val member = guild.getMemberById(cycle.userId)
            ?: runCatching { guild.retrieveMemberById(cycle.userId).complete() }.getOrNull()
        if (member == null) {
            OhagiBot.logger.warn("BAN前のメンバー情報を取得できずロール保存をスキップしました: guild={}, user={}", guild.id, cycle.userId)
            return
        }
        val roles = member.roles
            .filter { !it.isPublicRole && !it.isManaged }
            .map { SavedRoleInfo(id = it.id, name = it.name) }

        watchCycleService.saveRoleSnapshot(
            cycleId = cycle.id,
            guildId = guild.id,
            userId = cycle.userId,
            roles = roles,
            nickname = member.nickname,
        )
        OhagiBot.logger.info("ロールスナップショット保存完了: guild={}, user={}, roles={}", guild.id, cycle.userId, roles.size)
    }

    fun unbanAndInvite(guild: Guild, userId: String): UnbanInviteResult {
        if (!guild.selfMember.hasPermission(Permission.BAN_MEMBERS)) {
            return UnbanInviteResult(false, "Botに `BAN_MEMBERS` 権限がないためBAN解除できません。")
        }

        val unbanResult = runCatching {
            guild.unban(UserSnowflake.fromId(userId)).reason("Watch System によるBAN解除・再招待").complete()
        }
        if (unbanResult.isFailure) {
            return UnbanInviteResult(
                false,
                "BAN解除に失敗しました（すでに解除済み、または権限不足の可能性があります）: `${unbanResult.exceptionOrNull()?.message}`",
            )
        }

        // BANNED状態の最新サイクルに unbannedAt を記録
        val bannedCycle = watchCycleService.findCycleByGuildUserStatus(guild.id, userId, WatchCycleStatus.BANNED)
        if (bannedCycle != null) {
            watchCycleService.markUnbanned(bannedCycle.id)
        }

        // 1回限り・24時間有効の招待リンクを作成
        val inviteChannel = findInviteChannel(guild, bannedCycle?.statusChannelId)
        val inviteUrl = if (inviteChannel != null) {
            runCatching {
                inviteChannel.createInvite()
                    .setMaxUses(1)
                    .setMaxAge(24L, TimeUnit.HOURS)
                    .setUnique(true)
                    .complete()
                    .url
            }.onFailure {
                OhagiBot.logger.warn("招待リンク作成に失敗しました: guild={}, channel={}", guild.id, inviteChannel.id, it)
            }.getOrNull()
        } else {
            null
        }

        if (inviteUrl == null) {
            return UnbanInviteResult(
                success = true,
                message = "<@$userId> のBANを解除しましたが、Botに招待作成権限がないため招待リンクを自動発行できませんでした。手動で招待リンクを送ってください（どの招待リンクから再参加してもロールは自動復元されます）。",
                inviteUrl = null,
                dmSent = false,
            )
        }

        // DM送信を試行
        val dmSent = runCatching {
            val user = guild.jda.retrieveUserById(userId).complete()
            val dm = user.openPrivateChannel().complete()
            dm.sendMessage(
                "サーバー **${guild.name}** のBANが解除されました。\n" +
                    "以下の1回限り有効な招待リンク（24時間有効）から再参加してください。\n" +
                    "再参加すると、以前のロールが可能な限り自動で復元されます。\n$inviteUrl"
            ).complete()
            true
        }.getOrElse {
            OhagiBot.logger.info("対象ユーザーへのDM送信に失敗（DM拒否設定等の可能性）: user={}, err={}", userId, it.message)
            false
        }

        val summary = if (dmSent) {
            "<@$userId> のBANを解除し、1回限りの招待リンクをDMで送信しました。\n再参加すると以前のロールが自動で復元され、新しい監視サイクルが開始されます。"
        } else {
            "<@$userId> のBANを解除しましたが、対象メンバーがBotからのDMを受け付けない設定のため送信できませんでした。\n" +
                "以下の1回限り招待リンクを別経路で渡してください（他メンバーが発行した招待リンクから参加しても同じようにロール復元＆新サイクル開始が行われます）：\n$inviteUrl"
        }

        return UnbanInviteResult(
            success = true,
            message = summary,
            inviteUrl = inviteUrl,
            dmSent = dmSent,
        )
    }

    fun restoreRolesForMember(guild: Guild, member: Member, cycleId: Long): RoleRestoreReport {
        val snapshot = watchCycleService.getRoleSnapshot(cycleId)
            ?: return RoleRestoreReport(emptyList(), emptyList(), false)

        val restored = mutableListOf<String>()
        val skipped = mutableListOf<String>()

        if (!guild.selfMember.hasPermission(Permission.MANAGE_ROLES)) {
            snapshot.roles.forEach { skipped += "${it.name}（BotにMANAGE_ROLES権限なし）" }
        } else {
            val rolesToAdd = mutableListOf<net.dv8tion.jda.api.entities.Role>()
            for (saved in snapshot.roles) {
                val role = guild.getRoleById(saved.id)
                when {
                    role == null -> skipped += "${saved.name}（ロール削除済み）"
                    role.isManaged || role.isPublicRole -> skipped += "${saved.name}（自動管理ロール）"
                    !guild.selfMember.canInteract(role) -> skipped += "${saved.name}（Botより上位ロール）"
                    else -> rolesToAdd += role
                }
            }
            for (role in rolesToAdd) {
                val ok = runCatching {
                    guild.addRoleToMember(member, role).reason("Watch System: 再参加時のロール復元").complete()
                    true
                }.getOrElse {
                    OhagiBot.logger.warn("ロール付与に失敗: guild={}, user={}, role={}", guild.id, member.id, role.id, it)
                    skipped += "${role.name}（付与エラー）"
                    false
                }
                if (ok) restored += role.name
            }
        }

        val nicknameRestored = if (!snapshot.nickname.isNullOrBlank() && guild.selfMember.canInteract(member)) {
            runCatching {
                guild.modifyNickname(member, snapshot.nickname).reason("Watch System: ニックネーム復元").complete()
                true
            }.getOrDefault(false)
        } else {
            false
        }

        watchCycleService.markRoleSnapshotRestored(cycleId)
        return RoleRestoreReport(restored, skipped, nicknameRestored)
    }

    fun incrementRoleCountersForTarget(guild: Guild, targetId: Long): RoleRenameReport {
        val counters = watchConfigService.listRoleCountersByTarget(targetId)
        if (counters.isEmpty()) return RoleRenameReport(emptyList(), emptyList())

        val renamed = mutableListOf<String>()
        val failed = mutableListOf<String>()
        val canManageRoles = guild.selfMember.hasPermission(Permission.MANAGE_ROLES)

        for (counter in counters) {
            val role = guild.getRoleById(counter.roleId)
            val nextNum = counter.nextNumber()
            val newName = counter.renderName(nextNum)
            when {
                role == null -> failed += "ロールID `${counter.roleId}`（削除済み）"
                !canManageRoles -> failed += "`${role.name}`（BotにMANAGE_ROLES権限なし）"
                role.isManaged || role.isPublicRole -> failed += "`${role.name}`（変更不可ロール）"
                !guild.selfMember.canInteract(role) -> failed += "`${role.name}`（Botより上位ロール）"
                else -> {
                    val oldName = role.name
                    val ok = runCatching {
                        role.manager.setName(newName)
                            .reason("Watch System: BAN後の再参加に伴うロール名自動更新")
                            .complete()
                        true
                    }.onFailure {
                        OhagiBot.logger.warn("ロール名更新に失敗しました: guild={}, roleId={}", guild.id, role.id, it)
                    }.getOrDefault(false)
                    if (ok) {
                        watchConfigService.updateRoleCounterNumber(counter.id, nextNum)
                        renamed += "`$oldName` → **`$newName`**"
                    } else {
                        failed += "`$oldName`（更新エラー）"
                    }
                }
            }
        }
        return RoleRenameReport(renamed, failed)
    }

    suspend fun handleMemberRejoin(guild: Guild, member: Member) {
        if (!WatchPermissions.isFeatureEnabled(guild.id)) return
        val bannedCycle = watchCycleService.findCycleByGuildUserStatus(guild.id, member.id, WatchCycleStatus.BANNED)
            ?: return
        val target = watchConfigService.getTargetById(bannedCycle.targetId) ?: return

        WatchOrchestrator.withTargetLock(guild.id, member.id) {
            // 二重処理防止の再チェック
            val latestBanned = watchCycleService.getCycleById(bannedCycle.id) ?: return@withTargetLock
            if (latestBanned.status != WatchCycleStatus.BANNED) return@withTargetLock

            // BAN再参加時のロール名自動カウント更新（例: sasalive 9期生 → sasalive 10期生）
            val renameReport = incrementRoleCountersForTarget(guild, target.id)
            val report = restoreRolesForMember(guild, member, latestBanned.id)
            val now = System.currentTimeMillis()
            watchCycleService.markArchived(latestBanned.id, rejoinedAt = now)

            val config = watchConfigService.getOrCreateConfig(guild.id)
            val statusChannelId = watchConfigService.resolveStatusChannelId(target, config)
            val channel = statusChannelId?.let { guild.getTextChannelById(it) }

            val welcomeDesc = buildString {
                append("<@${member.id}> がサーバーに再参加しました！\n")
                if (renameReport.renamed.isNotEmpty()) {
                    append("\n**🏷️ ロール名更新**: ${renameReport.renamed.joinToString(", ")}")
                }
                if (renameReport.failed.isNotEmpty()) {
                    append("\n**⚠️ ロール名更新失敗**: ${renameReport.failed.joinToString(", ")}")
                }
                if (report.restoredRoles.isNotEmpty()) {
                    append("\n**✅ 復元したロール**: ${report.restoredRoles.joinToString(", ") { "`$it`" }}")
                }
                if (report.skippedRoles.isNotEmpty()) {
                    append("\n**⚠️ 復元できなかったロール**: ${report.skippedRoles.joinToString(", ")}")
                }
                if (report.restoredRoles.isEmpty() && report.skippedRoles.isEmpty()) {
                    append("\n（復元対象の保存ロールはありませんでした）")
                }
            }
            channel?.sendMessageComponents(
                simpleTitleAndDescriptionContainer("🔄 再参加＆ロール復元完了", welcomeDesc, Color(0x2E, 0xCC, 0x71))
            )?.useComponentsV2()?.queue()

            if (target.active) {
                val newCycle = watchCycleService.createNewCycle(target, statusChannelId)
                watchConfigService.updateTargetCurrentCycle(target.id, newCycle.id)
                StatusBoardService.upsertStatusBoard(guild, target, newCycle, forceNewMessage = true)
            }
        }
    }

    private fun findInviteChannel(guild: Guild, preferredChannelId: String?): TextChannel? {
        val preferred = preferredChannelId?.let { guild.getTextChannelById(it) }
        if (preferred != null && guild.selfMember.hasPermission(preferred, Permission.CREATE_INSTANT_INVITE)) {
            return preferred
        }
        val sys = guild.systemChannel
        if (sys != null && guild.selfMember.hasPermission(sys, Permission.CREATE_INSTANT_INVITE)) {
            return sys
        }
        return guild.textChannels.firstOrNull {
            guild.selfMember.hasPermission(it, Permission.CREATE_INSTANT_INVITE)
        }
    }
}
