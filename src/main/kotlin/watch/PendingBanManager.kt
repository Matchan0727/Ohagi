package jp.simplespace.watch

import jp.simplespace.db.WatchCycle
import jp.simplespace.db.WatchCycleStatus
import jp.simplespace.db.WatchTarget
import jp.simplespace.discord.OhagiBot
import jp.simplespace.watchConfigService
import jp.simplespace.watchCycleService
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import net.dv8tion.jda.api.JDA
import net.dv8tion.jda.api.entities.Guild
import net.dv8tion.jda.api.entities.Message
import java.util.concurrent.ConcurrentHashMap

object PendingBanManager {
    private val scheduledJobs = ConcurrentHashMap<Long, Job>()

    fun schedulePendingBan(guild: Guild, target: WatchTarget, cycle: WatchCycle) {
        cancelTimer(cycle.id)
        val deadlineAt = cycle.banDeadlineAt ?: System.currentTimeMillis()
        val delayMs = (deadlineAt - System.currentTimeMillis()).coerceAtLeast(0L)

        val job = OhagiBot.scope.launch {
            if (delayMs > 0L) {
                delay(delayMs)
            }
            scheduledJobs.remove(cycle.id)
            WatchOrchestrator.executeBanIfStillPending(
                guild = guild,
                target = target,
                cycleId = cycle.id,
                lastWordsContent = null,
                lastWordsMessageId = null,
            )
        }
        scheduledJobs[cycle.id] = job
    }

    fun cancelTimer(cycleId: Long) {
        scheduledJobs.remove(cycleId)?.cancel()
    }

    /**
     * Bot起動時（ReadyEvent）に未完了の PENDING_BAN をDBから復元する。
     */
    fun restorePendingBans(jda: JDA) {
        val pendingCycles = watchCycleService.listCyclesByStatus(WatchCycleStatus.PENDING_BAN)
        if (pendingCycles.isEmpty()) return
        OhagiBot.logger.info("未執行の遺言待機BANを復元します: {}件", pendingCycles.size)

        for (cycle in pendingCycles) {
            val guild = jda.getGuildById(cycle.guildId)
            val target = watchConfigService.getTargetById(cycle.targetId)
            if (guild == null || target == null) {
                OhagiBot.logger.warn("復元対象のGuildまたはTargetが見つかりません: cycleId={}", cycle.id)
                continue
            }
            schedulePendingBan(guild, target, cycle)
        }
    }

    /**
     * 専用チャンネルでの対象者による遺言発言を検知し、即座にBANを実行する。
     * 遺言として処理した場合は true を返す。
     */
    suspend fun handlePossibleLastWords(message: Message): Boolean {
        if (!message.isFromGuild || message.author.isBot) return false
        val guild = message.guild
        if (!WatchPermissions.isFeatureEnabled(guild.id)) return false

        val pending = watchCycleService.findCycleByGuildUserStatus(
            guildId = guild.id,
            userId = message.author.id,
            status = WatchCycleStatus.PENDING_BAN,
        ) ?: return false

        val expectedChannelId = pending.statusChannelId ?: return false
        if (message.channel.id != expectedChannelId) return false

        val target = watchConfigService.getTargetById(pending.targetId) ?: return false
        val content = buildString {
            val raw = message.contentDisplay.trim()
            if (raw.isNotEmpty()) append(raw)
            if (message.attachments.isNotEmpty()) {
                if (isNotEmpty()) append(" ")
                append("[添付ファイル ${message.attachments.size}件]")
            }
            if (message.stickers.isNotEmpty()) {
                if (isNotEmpty()) append(" ")
                append("[スタンプ: ${message.stickers.joinToString(", ") { it.name }}]")
            }
        }.ifBlank { "(空のメッセージ)" }.take(1000)

        cancelTimer(pending.id)
        WatchOrchestrator.executeBanIfStillPending(
            guild = guild,
            target = target,
            cycleId = pending.id,
            lastWordsContent = content,
            lastWordsMessageId = message.id,
        )
        return true
    }
}
