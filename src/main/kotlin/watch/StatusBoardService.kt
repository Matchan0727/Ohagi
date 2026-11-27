package jp.simplespace.watch

import jp.simplespace.db.WatchCycle
import jp.simplespace.db.WatchCycleStatus
import jp.simplespace.db.WatchScoreEvent
import jp.simplespace.db.WatchTarget
import jp.simplespace.discord.OhagiBot
import jp.simplespace.watch.render.ColorGradient
import jp.simplespace.watch.render.GraphRange
import jp.simplespace.watch.render.HistoryGraphRenderer
import jp.simplespace.watch.render.ProgressBarRenderer
import jp.simplespace.watchConfigService
import jp.simplespace.watchCycleService
import jp.simplespace.watchVoteTicketService
import net.dv8tion.jda.api.components.actionrow.ActionRow
import net.dv8tion.jda.api.components.buttons.Button
import net.dv8tion.jda.api.components.container.Container
import net.dv8tion.jda.api.components.mediagallery.MediaGallery
import net.dv8tion.jda.api.components.mediagallery.MediaGalleryItem
import net.dv8tion.jda.api.components.separator.Separator
import net.dv8tion.jda.api.components.textdisplay.TextDisplay
import net.dv8tion.jda.api.entities.Guild
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel
import net.dv8tion.jda.api.exceptions.ErrorResponseException
import net.dv8tion.jda.api.requests.ErrorResponse
import net.dv8tion.jda.api.utils.FileUpload
import java.awt.Color
import java.util.Locale

object StatusBoardService {
    private const val PROGRESS_FILE_NAME = "progress_bar.png"
    private const val GRAPH_FILE_NAME = "history_graph.png"

    /**
     * ステータスカードを作成または編集更新する。
     * forceNewMessage = true の場合は旧メッセージを編集せず新規投稿する（再参加時の新サイクル開始用）。
     */
    fun upsertStatusBoard(
        guild: Guild,
        target: WatchTarget,
        cycle: WatchCycle,
        forceNewMessage: Boolean = false,
        overrideChannelId: String? = null,
    ): String? {
        val config = watchConfigService.getOrCreateConfig(guild.id)
        val channelId = overrideChannelId ?: cycle.statusChannelId ?: watchConfigService.resolveStatusChannelId(target, config)
        if (channelId == null) {
            OhagiBot.logger.warn("専用チャンネル未設定のためステータスボードを更新できません: guild={}, target={}", guild.id, target.id)
            return null
        }
        val channel = guild.getTextChannelById(channelId)
        if (channel == null) {
            OhagiBot.logger.warn("専用チャンネルが見つかりません: guild={}, channelId={}", guild.id, channelId)
            return null
        }

        val member = guild.getMemberById(target.userId)
        val displayName = member?.effectiveName ?: guild.jda.getUserById(target.userId)?.effectiveName ?: "User(${target.userId})"
        val banCount = watchCycleService.countBansByTarget(target.id)
        val globalTicketCount = watchVoteTicketService.countActiveGlobalTicketsForTarget(guild.id, target.id)
        val latestEvent = watchCycleService.getLatestEventForCycle(cycle.id)
        val footer = buildFooterText(cycle, latestEvent, config.decayEnabled, config.decayGraceHours)

        val pngBytes = ProgressBarRenderer.render(
            displayName = displayName,
            cycleNumber = cycle.cycleNumber,
            score = cycle.currentScore,
            status = cycle.status,
            footerText = footer,
        )
        val fileUpload = FileUpload.fromData(pngBytes, PROGRESS_FILE_NAME)
        val container = buildStatusContainer(
            target, cycle, displayName, banCount, globalTicketCount, latestEvent, config.decayEnabled, config.decayGraceHours, fileUpload
        )

        if (!forceNewMessage && !cycle.statusMessageId.isNullOrBlank()) {
            val edited = tryEditExisting(channel, cycle.statusMessageId, container)
            if (edited) return cycle.statusMessageId
        }

        // 新規投稿（または編集失敗時のフォールバック再投稿）
        val newUpload = FileUpload.fromData(pngBytes, PROGRESS_FILE_NAME)
        val newContainer = buildStatusContainer(
            target, cycle, displayName, banCount, globalTicketCount, latestEvent, config.decayEnabled, config.decayGraceHours, newUpload
        )
        return runCatching {
            val sent = channel.sendMessageComponents(newContainer)
                .setAllowedMentions(emptyList())
                .useComponentsV2()
                .complete()
            watchCycleService.updateStatusMessage(cycle.id, channel.id, sent.id)
            sent.id
        }.onFailure {
            OhagiBot.logger.error("ステータスボードの新規投稿に失敗しました: guild={}, channel={}", guild.id, channel.id, it)
        }.getOrNull()
    }

    /**
     * 既存のステータスカードを削除し、チャンネル最下部へ最新状態で再投稿する。
     * 遺言などの過去記録を守るため、ACTIVE / PENDING_BAN サイクルのみを対象とする。
     */
    fun repositionStatusBoard(
        guild: Guild,
        target: WatchTarget,
        cycle: WatchCycle,
    ): String? {
        if (cycle.status != WatchCycleStatus.ACTIVE && cycle.status != WatchCycleStatus.PENDING_BAN) {
            return null
        }

        // 旧メッセージの削除を試行（既に手動削除済みの場合は無視して続行）
        val oldChannelId = cycle.statusChannelId
        val oldMessageId = cycle.statusMessageId
        if (!oldChannelId.isNullOrBlank() && !oldMessageId.isNullOrBlank()) {
            val oldChannel = guild.getTextChannelById(oldChannelId)
            if (oldChannel != null) {
                runCatching {
                    oldChannel.deleteMessageById(oldMessageId).complete()
                }.onFailure { e ->
                    if (e !is ErrorResponseException || e.errorResponse != ErrorResponse.UNKNOWN_MESSAGE) {
                        OhagiBot.logger.warn("旧ステータスボードの削除に失敗しました: messageId={}", oldMessageId, e)
                    }
                }
            }
        }

        val config = watchConfigService.getOrCreateConfig(guild.id)
        val destChannelId = watchConfigService.resolveStatusChannelId(target, config) ?: oldChannelId
        return upsertStatusBoard(
            guild = guild,
            target = target,
            cycle = cycle,
            forceNewMessage = true,
            overrideChannelId = destChannelId,
        )
    }

    private fun tryEditExisting(
        channel: TextChannel,
        messageId: String,
        container: Container,
    ): Boolean {
        return try {
            channel.editMessageComponentsById(messageId, container)
                .setAllowedMentions(emptyList())
                .useComponentsV2()
                .complete()
            true
        } catch (e: ErrorResponseException) {
            if (e.errorResponse == ErrorResponse.UNKNOWN_MESSAGE) {
                OhagiBot.logger.warn("既存のステータスボードメッセージ({})が削除されていたため再投稿します", messageId)
                false
            } else {
                OhagiBot.logger.error("ステータスボード編集エラー: messageId={}", messageId, e)
                false
            }
        } catch (e: Exception) {
            OhagiBot.logger.error("ステータスボード編集で予期せぬエラー: messageId={}", messageId, e)
            false
        }
    }

    private fun buildStatusContainer(
        target: WatchTarget,
        cycle: WatchCycle,
        displayName: String,
        banCount: Int,
        globalTicketCount: Int,
        latestEvent: WatchScoreEvent?,
        decayEnabled: Boolean,
        decayGraceHours: Int,
        fileUpload: FileUpload,
    ): Container {
        val accent = when (cycle.status) {
            WatchCycleStatus.BANNED -> Color(0x8B, 0x00, 0x00)
            WatchCycleStatus.PENDING_BAN -> Color(0xE7, 0x4C, 0x3C)
            else -> ColorGradient.colorForScore(cycle.currentScore)
        }
        val stateLabel = when (cycle.status) {
            WatchCycleStatus.ACTIVE -> ColorGradient.statusLabel(cycle.currentScore)
            WatchCycleStatus.PENDING_BAN -> "⏳ 遺言待機中（BAN執行直前）"
            WatchCycleStatus.BANNED -> "💀 BAN執行済み（サイクル凍結）"
            WatchCycleStatus.ARCHIVED -> "📦 アーカイブ済み"
        }

        val headerText = buildString {
            append("## 🛡️ 監視ステータス: <@${target.userId}>（第${cycle.cycleNumber}サイクル）\n")
            append(
                "状態: **$stateLabel** | 現在違反度: **${String.format(Locale.US, "%.1f", cycle.currentScore)}%** | " +
                    "通算BAN: **${banCount}回** | 🌐 全体チケット: **${globalTicketCount}枚**"
            )
        }

        val detailText = buildString {
            when (cycle.status) {
                WatchCycleStatus.PENDING_BAN -> {
                    val deadlineSec = (cycle.banDeadlineAt ?: System.currentTimeMillis()) / 1000L
                    append("🚨 **契機**: ${cycle.banTriggerType?.displayName ?: "処刑執行"}\n")
                    append("⏳ **BAN執行予定**: <t:$deadlineSec:R>（遺言を発言した瞬間に執行されます）")
                }
                WatchCycleStatus.BANNED -> {
                    val bannedSec = (cycle.bannedAt ?: System.currentTimeMillis()) / 1000L
                    append("🔨 **BAN執行日時**: <t:$bannedSec:F>\n")
                    append("📌 **契機**: ${cycle.banTriggerType?.displayName ?: "不明"}")
                    if (!cycle.banTriggerDetail.isNullOrBlank()) {
                        append("（${cycle.banTriggerDetail}）")
                    }
                    append("\n")
                    if (!cycle.lastWordsContent.isNullOrBlank()) {
                        append("💬 **遺言**:\n> ${cycle.lastWordsContent.replace("\n", "\n> ")}")
                    } else {
                        append("💬 **遺言**: なし（時間切れ / 即時執行）")
                    }
                }
                else -> {
                    if (latestEvent != null) {
                        val sign = if (latestEvent.delta >= 0) "+" else ""
                        val deltaStr = String.format(Locale.US, "%s%.1f%%", sign, latestEvent.delta)
                        append("直近の変動: `$deltaStr` — ${latestEvent.reason ?: latestEvent.eventType.displayName} (<t:${latestEvent.createdAt / 1000L}:R>)")
                    } else {
                        append("まだ違反履歴はありません。")
                    }
                    if (decayEnabled && cycle.currentScore > 0.0) {
                        val baseTs = cycle.lastViolationAt ?: cycle.startedAt
                        val decayStartSec = (baseTs + decayGraceHours * 3600_000L) / 1000L
                        append("\n-# 減衰開始目安: <t:$decayStartSec:R>（無違反が${decayGraceHours}時間続くと減少）")
                    }
                }
            }
        }

        val isActive = cycle.status == WatchCycleStatus.ACTIVE
        val canReposition = cycle.status == WatchCycleStatus.ACTIVE || cycle.status == WatchCycleStatus.PENDING_BAN
        val globalBtn = Button.danger(
            "ticket_board_global:${target.id}",
            "🌐 全体チケット使用 (${globalTicketCount}枚)",
        ).withDisabled(!isActive || globalTicketCount <= 0)
        val personalBtn = Button.secondary(
            "ticket_board_personal:${target.id}",
            "🎟️ 個人チケット使用",
        ).withDisabled(!isActive)
        val refreshBtn = Button.secondary(
            "watch_refresh:${target.id}",
            "🔄 再配置",
        ).withDisabled(!canReposition)

        val buttons = listOf(
            Button.primary("watch_graph:${target.id}:${GraphRange.WEEK_1.key}", "📊 グラフを見る"),
            Button.secondary("watch_logs:${target.id}:0", "📋 違反ログ"),
            globalBtn,
            personalBtn,
            refreshBtn,
        )

        return Container.of(
            TextDisplay.of(headerText),
            MediaGallery.of(MediaGalleryItem.fromFile(fileUpload)),
            TextDisplay.of(detailText),
            Separator.createInvisible(Separator.Spacing.SMALL),
            ActionRow.of(buttons),
        ).withAccentColor(accent)
    }

    private fun buildFooterText(
        cycle: WatchCycle,
        latestEvent: WatchScoreEvent?,
        decayEnabled: Boolean,
        decayGraceHours: Int,
    ): String {
        if (cycle.status == WatchCycleStatus.BANNED) {
            return if (!cycle.lastWordsContent.isNullOrBlank()) {
                "遺言: ${cycle.lastWordsContent}"
            } else {
                "遺言なし / BAN執行完了"
            }
        }
        if (cycle.status == WatchCycleStatus.PENDING_BAN) {
            return "遺言待機中... 専用チャンネルで発言すると即BAN執行"
        }
        if (latestEvent != null && latestEvent.delta != 0.0) {
            val sign = if (latestEvent.delta > 0) "+" else ""
            val reason = latestEvent.reason ?: latestEvent.eventType.displayName
            return String.format(Locale.US, "直近: %s%.1f%% (%s)", sign, latestEvent.delta, reason)
        }
        return if (decayEnabled) "無違反が${decayGraceHours}時間継続すると時間減衰が開始します" else "監視中"
    }

    /**
     * Ephemeralグラフ表示用の Container と FileUpload を生成する。
     */
    fun buildGraphPanel(
        guild: Guild,
        target: WatchTarget,
        range: GraphRange,
    ): Pair<Container, FileUpload> {
        val now = System.currentTimeMillis()
        val sinceMillis = range.durationMillis?.let { now - it }
        val events = watchCycleService.getEventsForTargetSince(target.id, sinceMillis)
        val initialScore = if (sinceMillis != null) {
            watchCycleService.getLastEventBefore(target.id, sinceMillis)?.scoreAfter ?: 0.0
        } else {
            0.0
        }

        val displayName = guild.getMemberById(target.userId)?.effectiveName
            ?: guild.jda.getUserById(target.userId)?.effectiveName
            ?: "User(${target.userId})"

        val pngBytes = HistoryGraphRenderer.render(
            displayName = displayName,
            range = range,
            events = events,
            initialScoreBeforeRange = initialScore,
            nowMillis = now,
        )
        val upload = FileUpload.fromData(pngBytes, GRAPH_FILE_NAME)

        val violationCount = events.count { it.eventType == jp.simplespace.db.WatchScoreEventType.AI_VIOLATION }
        val banCount = events.count { it.eventType == jp.simplespace.db.WatchScoreEventType.BANNED }
        val decaySum = events
            .filter { it.eventType == jp.simplespace.db.WatchScoreEventType.DECAY }
            .sumOf { -it.delta }
        val maxScore = (events.map { it.scoreAfter } + initialScore).maxOrNull() ?: 0.0

        val summary = buildString {
            append("## 📊 違反度推移グラフ（${range.label}）\n")
            append("対象: <@${target.userId}>\n")
            append(
                String.format(
                    Locale.US,
                    "AI違反検知: **%d回** | 最大違反度: **%.1f%%** | BAN回数: **%d回** | 減衰回復: **-%.1f%%**",
                    violationCount,
                    maxScore,
                    banCount,
                    decaySum,
                )
            )
        }

        val rangeButtons = GraphRange.entries.map { r ->
            val customId = "watch_graph_range:${target.id}:${r.key}"
            if (r == range) {
                Button.primary(customId, r.label).asDisabled()
            } else {
                Button.secondary(customId, r.label)
            }
        }

        val container = Container.of(
            TextDisplay.of(summary),
            MediaGallery.of(MediaGalleryItem.fromFile(upload)),
            Separator.createInvisible(Separator.Spacing.SMALL),
            ActionRow.of(rangeButtons),
        ).withAccentColor(Color(0x36, 0xB8, 0xFA))

        return container to upload
    }
}
