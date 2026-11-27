package jp.simplespace.discord.listeners

import jp.simplespace.db.AiRuleType
import jp.simplespace.db.AiWeightHint
import jp.simplespace.db.VoteChoice
import jp.simplespace.discord.OhagiBot
import jp.simplespace.discord.commands.watch.WatchAiRuleUi
import jp.simplespace.discord.utils.simpleTitleAndDescriptionContainer
import jp.simplespace.watch.BanTicketService
import jp.simplespace.watch.StatusBoardService
import jp.simplespace.watch.VoteBanService
import jp.simplespace.watch.WatchOrchestrator
import jp.simplespace.watch.WatchPermissions
import jp.simplespace.watch.render.GraphRange
import jp.simplespace.watchAiService
import jp.simplespace.watchConfigService
import jp.simplespace.watchCycleService
import jp.simplespace.watchVoteTicketService
import kotlinx.coroutines.launch
import net.dv8tion.jda.api.components.actionrow.ActionRow
import net.dv8tion.jda.api.components.buttons.Button
import net.dv8tion.jda.api.components.container.Container
import net.dv8tion.jda.api.components.separator.Separator
import net.dv8tion.jda.api.components.textdisplay.TextDisplay
import net.dv8tion.jda.api.events.interaction.ModalInteractionEvent
import net.dv8tion.jda.api.events.interaction.command.CommandAutoCompleteInteractionEvent
import net.dv8tion.jda.api.events.interaction.component.ButtonInteractionEvent
import net.dv8tion.jda.api.events.interaction.component.StringSelectInteractionEvent
import net.dv8tion.jda.api.hooks.ListenerAdapter
import net.dv8tion.jda.api.interactions.commands.Command
import java.awt.Color
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

class WatchInteractionListener : ListenerAdapter() {
    companion object {
        private const val REFRESH_COOLDOWN_MILLIS = 30_000L
        private val lastRefreshByTarget = ConcurrentHashMap<Long, Long>()
    }

    override fun onCommandAutoCompleteInteraction(event: CommandAutoCompleteInteractionEvent) {
        val guild = event.guild ?: return
        if (!WatchPermissions.isFeatureEnabled(guild.id)) return

        if (event.name == "watch" && event.subcommandName == "unban" && event.focusedOption.name == "user_id") {
            val typed = event.focusedOption.value.lowercase()
            val bannedCycles = watchCycleService.listBannedCyclesByGuild(guild.id)
            val choices = bannedCycles.mapNotNull { cycle ->
                val userName = event.jda.getUserById(cycle.userId)?.effectiveName ?: "User"
                val label = "$userName (${cycle.userId}) - 第${cycle.cycleNumber}サイクル"
                if (typed.isBlank() || label.lowercase().contains(typed)) {
                    Command.Choice(label.take(100), cycle.userId)
                } else {
                    null
                }
            }.distinctBy { it.asString }.take(25)
            event.replyChoices(choices).queue()
            return
        }

        if (event.name == "watch" &&
            event.subcommandGroup == "ai" &&
            (event.subcommandName == "rule-edit" || event.subcommandName == "rule-remove") &&
            event.focusedOption.name == "rule_id"
        ) {
            val typed = event.focusedOption.value.lowercase()
            val rules = watchAiService.listRulesByGuild(guild.id)
            val choices = rules.mapNotNull { r ->
                val icon = if (r.ruleType == AiRuleType.VIOLATION) "🚫" else "✅"
                val label = "#${r.id} $icon [${r.weightHint.displayName}] ${r.description.replace("\n", " ")}"
                if (typed.isBlank() || label.lowercase().contains(typed) || r.id.toString().contains(typed)) {
                    Command.Choice(label.take(100), r.id)
                } else {
                    null
                }
            }.take(25)
            event.replyChoices(choices).queue()
        }
    }

    override fun onStringSelectInteraction(event: StringSelectInteractionEvent) {
        val guild = event.guild ?: return
        if (event.componentId != WatchAiRuleUi.SELECT_EDIT_ID) return
        if (!WatchPermissions.ensureFeatureEnabledOrReply(event, guild)) return
        if (!WatchPermissions.ensureAdminOrReply(event, guild, event.member)) return

        val ruleId = event.values.firstOrNull()?.toLongOrNull() ?: return
        val rule = watchAiService.getRuleById(guild.id, ruleId)
        if (rule == null) {
            event.replyComponents(simpleTitleAndDescriptionContainer("エラー", "AI判定ルール `#${ruleId}` が見つかりません。", Color.RED))
                .useComponentsV2().setEphemeral(true).queue()
            return
        }
        val target = rule.targetId?.let { watchConfigService.getTargetById(it) }
        if (target != null && WatchPermissions.isSelfProtectionBlocked(event.user.id, target.userId)) {
            event.replyComponents(
                simpleTitleAndDescriptionContainer("操作できません", "監視対象者本人は自分専用のルールを編集できません。", Color.RED)
            ).useComponentsV2().setEphemeral(true).queue()
            return
        }

        event.replyModal(WatchAiRuleUi.buildEditModal(rule)).queue()
    }

    override fun onModalInteraction(event: ModalInteractionEvent) {
        val guild = event.guild ?: return
        if (!event.modalId.startsWith(WatchAiRuleUi.MODAL_EDIT_PREFIX)) return
        if (!WatchPermissions.ensureFeatureEnabledOrReply(event, guild)) return
        if (!WatchPermissions.ensureAdminOrReply(event, guild, event.member)) return

        val ruleId = event.modalId.substringAfter(WatchAiRuleUi.MODAL_EDIT_PREFIX).toLongOrNull() ?: return
        val existing = watchAiService.getRuleById(guild.id, ruleId)
        if (existing == null) {
            event.replyComponents(simpleTitleAndDescriptionContainer("エラー", "AI判定ルール `#${ruleId}` が見つかりません。", Color.RED))
                .useComponentsV2().setEphemeral(true).queue()
            return
        }
        val target = existing.targetId?.let { watchConfigService.getTargetById(it) }
        if (target != null && WatchPermissions.isSelfProtectionBlocked(event.user.id, target.userId)) {
            event.replyComponents(
                simpleTitleAndDescriptionContainer("操作できません", "監視対象者本人は自分専用のルールを編集できません。", Color.RED)
            ).useComponentsV2().setEphemeral(true).queue()
            return
        }

        val newType = event.getValue("rule_type")?.asStringList?.firstOrNull()?.let { AiRuleType.fromString(it) }
        val newWeight = event.getValue("weight_hint")?.asStringList?.firstOrNull()?.let { AiWeightHint.fromString(it) }
        val newDesc = event.getValue("description")?.asString?.trim()?.takeIf { it.isNotEmpty() }
        if (newDesc == null) {
            event.replyComponents(simpleTitleAndDescriptionContainer("入力エラー", "ルール内容を入力してください。", Color.RED))
                .useComponentsV2().setEphemeral(true).queue()
            return
        }

        val updated = watchAiService.updateRule(
            guildId = guild.id,
            ruleId = ruleId,
            ruleType = newType,
            description = newDesc,
            weightHint = newWeight,
        )
        if (updated == null) {
            event.replyComponents(simpleTitleAndDescriptionContainer("エラー", "ルールの更新に失敗しました。", Color.RED))
                .useComponentsV2().setEphemeral(true).queue()
            return
        }

        event.replyComponents(
            simpleTitleAndDescriptionContainer(
                "✏️ AI判定ルールを更新しました",
                "- **ルールID**: `#${updated.id}`\n" +
                    "- **種類**: ${updated.ruleType.displayName}（重み: ${updated.weightHint.displayName}）\n" +
                    "- **内容**: ${updated.description}",
                Color(0x2E, 0xCC, 0x71),
            )
        ).setAllowedMentions(emptyList()).useComponentsV2().setEphemeral(true).queue()
    }

    override fun onButtonInteraction(event: ButtonInteractionEvent) {
        val id = event.button.customId ?: return
        val guild = event.guild ?: return

        when {
            id.startsWith("watch_graph:") -> handleGraphOpen(event, guild, id)
            id.startsWith("watch_graph_range:") -> handleGraphSwitch(event, guild, id)
            id.startsWith("watch_logs:") -> handleLogsView(event, guild, id)
            id.startsWith("watch_refresh:") -> handleBoardRefresh(event, guild, id.substringAfter("watch_refresh:").toLongOrNull())
            id.startsWith("voteban_yes:") -> handleVoteBallot(event, guild, id.substringAfter("voteban_yes:").toLongOrNull(), VoteChoice.YES)
            id.startsWith("voteban_no:") -> handleVoteBallot(event, guild, id.substringAfter("voteban_no:").toLongOrNull(), VoteChoice.NO)
            id.startsWith("ticket_approve:") -> handleTicketApproval(event, guild, id.substringAfter("ticket_approve:").toLongOrNull(), approve = true)
            id.startsWith("ticket_reject:") -> handleTicketApproval(event, guild, id.substringAfter("ticket_reject:").toLongOrNull(), approve = false)
            id.startsWith("ticket_board_global:") -> handleBoardGlobalTicket(event, guild, id.substringAfter("ticket_board_global:").toLongOrNull())
            id.startsWith("ticket_board_personal:") -> handleBoardPersonalTicket(event, guild, id.substringAfter("ticket_board_personal:").toLongOrNull())
            id.startsWith("ticket_use_confirm:") -> handleTicketUseConfirm(event, guild, id.substringAfter("ticket_use_confirm:").toLongOrNull())
            id.startsWith("ticket_use_exec:") -> handleTicketUseExec(event, guild, id.substringAfter("ticket_use_exec:").toLongOrNull())
            id.startsWith("ticket_use_cancel:") -> {
                event.editComponents(
                    simpleTitleAndDescriptionContainer("キャンセル", "処刑チケットの行使をキャンセルしました。", Color.GRAY)
                ).useComponentsV2().queue()
            }
        }
    }

    private fun handleBoardRefresh(
        event: ButtonInteractionEvent,
        guild: net.dv8tion.jda.api.entities.Guild,
        targetId: Long?,
    ) {
        if (targetId == null) return
        if (!WatchPermissions.ensureFeatureEnabledOrReply(event, guild)) return
        val target = watchConfigService.getTargetById(targetId)
        if (target == null || target.guildId != guild.id) {
            event.replyComponents(simpleTitleAndDescriptionContainer("エラー", "監視対象が見つかりません。", Color.RED))
                .useComponentsV2().setEphemeral(true).queue()
            return
        }
        if (WatchPermissions.isSelfProtectionBlocked(event.user.id, target.userId)) {
            event.replyComponents(
                simpleTitleAndDescriptionContainer("操作できません", "監視対象者本人は自分のステータスカードを再配置できません。", Color.RED)
            ).useComponentsV2().setEphemeral(true).queue()
            return
        }

        val isAdmin = event.member != null && WatchPermissions.isGuildWatchAdmin(guild, event.member!!)
        val now = System.currentTimeMillis()
        if (!isAdmin) {
            val last = lastRefreshByTarget[target.id] ?: 0L
            if (now - last < REFRESH_COOLDOWN_MILLIS) {
                val remain = ((REFRESH_COOLDOWN_MILLIS - (now - last)) / 1000L).coerceAtLeast(1L)
                event.replyComponents(
                    simpleTitleAndDescriptionContainer("⏳ クールダウン中", "再配置はあと **${remain}秒** 後に実行できます。", Color.GRAY)
                ).useComponentsV2().setEphemeral(true).queue()
                return
            }
        }
        lastRefreshByTarget[target.id] = now

        event.deferReply(true).queue { hook ->
            OhagiBot.scope.launch {
                val ok = WatchOrchestrator.repositionTargetBoard(guild, target)
                hook.editOriginalComponents(
                    simpleTitleAndDescriptionContainer(
                        if (ok) "🔄 ステータスカードを再配置しました" else "再配置できませんでした",
                        if (ok) "<@${target.userId}> のステータスカードを最新状態でチャンネル最下部へ再配置しました。"
                        else "現在アクティブなサイクルではないか、専用チャンネルが未設定のため再配置できませんでした。",
                        if (ok) Color(0x2E, 0xCC, 0x71) else Color.RED,
                    )
                ).setAllowedMentions(emptyList()).useComponentsV2().queue()
            }
        }
    }

    private fun handleGraphOpen(event: ButtonInteractionEvent, guild: net.dv8tion.jda.api.entities.Guild, customId: String) {
        if (!WatchPermissions.ensureFeatureEnabledOrReply(event, guild)) return
        val parts = customId.split(":")
        val targetId = parts.getOrNull(1)?.toLongOrNull() ?: return
        val range = GraphRange.fromKey(parts.getOrNull(2))
        val target = watchConfigService.getTargetById(targetId)
        if (target == null || target.guildId != guild.id) {
            event.replyComponents(simpleTitleAndDescriptionContainer("エラー", "監視対象データが見つかりません。", Color.RED))
                .useComponentsV2().setEphemeral(true).queue()
            return
        }

        event.deferReply(true).queue { hook ->
            OhagiBot.scope.launch {
                try {
                    val (container, _) = StatusBoardService.buildGraphPanel(guild, target, range)
                    hook.editOriginalComponents(container)
                        .setAllowedMentions(emptyList())
                        .useComponentsV2()
                        .queue()
                } catch (e: Exception) {
                    OhagiBot.logger.error("グラフ生成中にエラー", e)
                    hook.editOriginalComponents(
                        simpleTitleAndDescriptionContainer("エラー", "グラフの生成中にエラーが発生しました。", Color.RED)
                    ).useComponentsV2().queue()
                }
            }
        }
    }

    private fun handleGraphSwitch(event: ButtonInteractionEvent, guild: net.dv8tion.jda.api.entities.Guild, customId: String) {
        if (!WatchPermissions.ensureFeatureEnabledOrReply(event, guild)) return
        val parts = customId.split(":")
        val targetId = parts.getOrNull(1)?.toLongOrNull() ?: return
        val range = GraphRange.fromKey(parts.getOrNull(2))
        val target = watchConfigService.getTargetById(targetId)
        if (target == null || target.guildId != guild.id) {
            event.replyComponents(simpleTitleAndDescriptionContainer("エラー", "監視対象データが見つかりません。", Color.RED))
                .useComponentsV2().setEphemeral(true).queue()
            return
        }

        event.deferEdit().useComponentsV2().queue { hook ->
            OhagiBot.scope.launch {
                try {
                    val (container, _) = StatusBoardService.buildGraphPanel(guild, target, range)
                    hook.editOriginalComponents(container)
                        .setAllowedMentions(emptyList())
                        .useComponentsV2()
                        .queue()
                } catch (e: Exception) {
                    OhagiBot.logger.error("グラフ期間切替中にエラー", e)
                    hook.editOriginalComponents(
                        simpleTitleAndDescriptionContainer("エラー", "グラフの更新中にエラーが発生しました。", Color.RED)
                    ).useComponentsV2().queue()
                }
            }
        }
    }

    private fun handleLogsView(event: ButtonInteractionEvent, guild: net.dv8tion.jda.api.entities.Guild, customId: String) {
        if (!WatchPermissions.ensureFeatureEnabledOrReply(event, guild)) return
        val parts = customId.split(":")
        val targetId = parts.getOrNull(1)?.toLongOrNull() ?: return
        val page = parts.getOrNull(2)?.toIntOrNull()?.coerceAtLeast(0) ?: 0
        val isPagination = event.message.isEphemeral

        val target = watchConfigService.getTargetById(targetId)
        if (target == null || target.guildId != guild.id) {
            event.replyComponents(simpleTitleAndDescriptionContainer("エラー", "監視対象が見つかりません。", Color.RED))
                .useComponentsV2().setEphemeral(true).queue()
            return
        }

        val pageSize = 6
        val (events, total) = watchCycleService.getRecentEventsPaged(targetId, page, pageSize)
        val totalPages = ((total + pageSize - 1) / pageSize).coerceAtLeast(1).toInt()

        val body = buildString {
            append("## 📋 違反・変動ログ（<@${target.userId}> | ${page + 1}/${totalPages}）\n")
            if (events.isEmpty()) {
                append("履歴はまだありません。")
            } else {
                events.forEach { ev ->
                    val sign = if (ev.delta >= 0) "+" else ""
                    val deltaStr = String.format(Locale.US, "%s%.1f%%", sign, ev.delta)
                    val scoreStr = String.format(Locale.US, "%.1f%%", ev.scoreAfter)
                    append("- **#${ev.id}** <t:${ev.createdAt / 1000L}:f> **[${ev.eventType.displayName}]** `$deltaStr` → **$scoreStr**")
                    if (!ev.reason.isNullOrBlank()) {
                        append("\n  理由: ${ev.reason.take(100)}")
                    }
                    if (!ev.sourceMessageExcerpt.isNullOrBlank()) {
                        append("\n  -# 発言抜粋: 「${ev.sourceMessageExcerpt.take(80).replace("\n", " ")}」")
                    }
                    append("\n")
                }
                append("\n-# AI誤判定の修正にはイベント番号（`#ID`）を使って `/watch ai feedback` を実行できます。")
            }
        }

        val prevBtn = Button.secondary("watch_logs:$targetId:${page - 1}", "◀️ 前へ").withDisabled(page <= 0)
        val nextBtn = Button.secondary("watch_logs:$targetId:${page + 1}", "▶️ 次へ").withDisabled(page + 1 >= totalPages)
        val container = Container.of(
            TextDisplay.of(body.trim()),
            Separator.createInvisible(Separator.Spacing.SMALL),
            ActionRow.of(prevBtn, nextBtn),
        ).withAccentColor(Color(0x36, 0xB8, 0xFA))

        if (isPagination) {
            event.editComponents(container).setAllowedMentions(emptyList()).useComponentsV2().queue()
        } else {
            event.replyComponents(container).setAllowedMentions(emptyList()).useComponentsV2().setEphemeral(true).queue()
        }
    }

    private fun handleVoteBallot(
        event: ButtonInteractionEvent,
        guild: net.dv8tion.jda.api.entities.Guild,
        voteId: Long?,
        choice: VoteChoice,
    ) {
        if (voteId == null) return
        if (!WatchPermissions.ensureFeatureEnabledOrReply(event, guild)) return
        val (ok, msg) = VoteBanService.castVote(guild, voteId, event.user.id, choice)
        event.replyComponents(
            simpleTitleAndDescriptionContainer(
                if (ok) "投票を受け付けました" else "投票できません",
                msg,
                if (ok) Color(0x2E, 0xCC, 0x71) else Color.RED,
            )
        ).useComponentsV2().setEphemeral(true).queue()
    }

    private fun handleTicketApproval(
        event: ButtonInteractionEvent,
        guild: net.dv8tion.jda.api.entities.Guild,
        ticketId: Long?,
        approve: Boolean,
    ) {
        if (ticketId == null) return
        if (!WatchPermissions.ensureFeatureEnabledOrReply(event, guild)) return
        if (!WatchPermissions.ensureAdminOrReply(event, guild, event.member)) return

        val ticket = watchVoteTicketService.getTicketById(ticketId)
        val target = ticket?.let { watchConfigService.getTargetById(it.targetId) }
        if (target != null && WatchPermissions.isSelfProtectionBlocked(event.user.id, target.userId)) {
            event.replyComponents(
                simpleTitleAndDescriptionContainer("操作できません", "監視対象者本人は自分に関するチケット提案を承認・却下できません。", Color.RED)
            ).useComponentsV2().setEphemeral(true).queue()
            return
        }

        val (ok, msg) = if (approve) {
            BanTicketService.approveSuggestedTicket(guild, ticketId, event.user.id)
        } else {
            BanTicketService.rejectSuggestedTicket(ticketId)
        }

        if (ok) {
            val statusTitle = if (approve) "✅ 処刑チケット提案【承認済み】(#$ticketId)" else "❌ 処刑チケット提案【却下】(#$ticketId)"
            val color = if (approve) Color(0x2E, 0xCC, 0x71) else Color.GRAY
            event.editComponents(
                simpleTitleAndDescriptionContainer(statusTitle, "$msg\n対応者: <@${event.user.id}>", color)
            ).setAllowedMentions(emptyList()).useComponentsV2().queue()
        } else {
            event.replyComponents(simpleTitleAndDescriptionContainer("エラー", msg, Color.RED))
                .useComponentsV2().setEphemeral(true).queue()
        }
    }

    private fun handleBoardGlobalTicket(
        event: ButtonInteractionEvent,
        guild: net.dv8tion.jda.api.entities.Guild,
        targetId: Long?,
    ) {
        if (targetId == null) return
        if (!WatchPermissions.ensureFeatureEnabledOrReply(event, guild)) return
        val target = watchConfigService.getTargetById(targetId)
        if (target == null || target.guildId != guild.id) {
            event.replyComponents(simpleTitleAndDescriptionContainer("エラー", "監視対象が見つかりません。", Color.RED))
                .useComponentsV2().setEphemeral(true).queue()
            return
        }
        if (event.user.id == target.userId) {
            event.replyComponents(
                simpleTitleAndDescriptionContainer("使用できません", "監視対象者本人は全体チケットを使用できません。", Color.RED)
            ).useComponentsV2().setEphemeral(true).queue()
            return
        }

        val globalTickets = watchVoteTicketService.listActiveGlobalTicketsForTarget(guild.id, target.id)
        if (globalTickets.isEmpty()) {
            event.replyComponents(
                simpleTitleAndDescriptionContainer("全体チケットなし", "<@${target.userId}> に対する有効な全体チケットは現在 0 枚です。", Color.GRAY)
            ).setAllowedMentions(emptyList()).useComponentsV2().setEphemeral(true).queue()
            return
        }

        val ticket = globalTickets.first()
        val container = Container.of(
            TextDisplay.of(
                "## ⚠️ 全体チケット行使の最終確認\n" +
                    "本当に <@${target.userId}> に対して **🌐 全体チケット (`#${ticket.id}`)** を 1 枚消費して行使しますか？\n" +
                    "- **残り枚数**: 現在 **${globalTickets.size}枚**（行使後: ${globalTickets.size - 1}枚）\n" +
                    "- **発行理由**: ${ticket.reason}\n\n" +
                    "確定すると対象メンバーへの **BAN手続きが即座に開始** されます。"
            ),
            Separator.createInvisible(Separator.Spacing.SMALL),
            ActionRow.of(
                Button.danger("ticket_use_exec:${ticket.id}", "💀 執行する（確定）"),
                Button.secondary("ticket_use_cancel:${ticket.id}", "キャンセル"),
            ),
        ).withAccentColor(Color(0xE7, 0x4C, 0x3C))

        event.replyComponents(container).setAllowedMentions(emptyList()).useComponentsV2().setEphemeral(true).queue()
    }

    private fun handleBoardPersonalTicket(
        event: ButtonInteractionEvent,
        guild: net.dv8tion.jda.api.entities.Guild,
        targetId: Long?,
    ) {
        if (targetId == null) return
        if (!WatchPermissions.ensureFeatureEnabledOrReply(event, guild)) return
        val target = watchConfigService.getTargetById(targetId)
        if (target == null || target.guildId != guild.id) {
            event.replyComponents(simpleTitleAndDescriptionContainer("エラー", "監視対象が見つかりません。", Color.RED))
                .useComponentsV2().setEphemeral(true).queue()
            return
        }

        val personalTickets = watchVoteTicketService.listActivePersonalTicketsForHolderAndTarget(
            guildId = guild.id,
            holderUserId = event.user.id,
            targetId = target.id,
        )
        if (personalTickets.isEmpty()) {
            event.replyComponents(
                simpleTitleAndDescriptionContainer(
                    "個人チケットなし",
                    "あなたは現在 <@${target.userId}> に対する有効な **個人チケット** を保有していません。",
                    Color.GRAY,
                )
            ).setAllowedMentions(emptyList()).useComponentsV2().setEphemeral(true).queue()
            return
        }

        if (personalTickets.size == 1) {
            val ticket = personalTickets.first()
            val container = Container.of(
                TextDisplay.of(
                    "## ⚠️ 個人チケット行使の最終確認\n" +
                        "本当に <@${target.userId}> に対して **🎟️ 個人チケット (`#${ticket.id}`)** を行使しますか？\n" +
                        "- **発行理由**: ${ticket.reason}\n\n" +
                        "確定すると対象メンバーへの **BAN手続きが即座に開始** され、このチケットは消費されます。"
                ),
                Separator.createInvisible(Separator.Spacing.SMALL),
                ActionRow.of(
                    Button.danger("ticket_use_exec:${ticket.id}", "💀 執行する（確定）"),
                    Button.secondary("ticket_use_cancel:${ticket.id}", "キャンセル"),
                ),
            ).withAccentColor(Color(0xE7, 0x4C, 0x3C))
            event.replyComponents(container).setAllowedMentions(emptyList()).useComponentsV2().setEphemeral(true).queue()
            return
        }

        val body = buildString {
            append("## 🎟️ あなたが保有する個人チケット（対象: <@${target.userId}>）\n")
            append("行使したいチケットを選んでください（次画面で最終確認が出ます）。\n\n")
            personalTickets.take(5).forEach { t ->
                val exp = t.expiresAt?.let { "期限: <t:${it / 1000L}:R>" } ?: "無期限"
                append("- **#${t.id}** ($exp) — ${t.reason.take(80)}\n")
            }
        }.trim()

        val buttons = personalTickets.take(5).map { t ->
            Button.danger("ticket_use_confirm:${t.id}", "⚔️ #${t.id} を行使")
        }
        val container = Container.of(
            TextDisplay.of(body),
            Separator.createInvisible(Separator.Spacing.SMALL),
            ActionRow.of(buttons),
        ).withAccentColor(Color(0xE6, 0x7E, 0x22))
        event.replyComponents(container).setAllowedMentions(emptyList()).useComponentsV2().setEphemeral(true).queue()
    }

    private fun handleTicketUseConfirm(
        event: ButtonInteractionEvent,
        guild: net.dv8tion.jda.api.entities.Guild,
        ticketId: Long?,
    ) {
        if (ticketId == null) return
        if (!WatchPermissions.ensureFeatureEnabledOrReply(event, guild)) return
        val ticket = watchVoteTicketService.getTicketById(ticketId)
        val target = ticket?.let { watchConfigService.getTargetById(it.targetId) }
        val allowed = ticket != null && (
            (ticket.isGlobal && target?.userId != event.user.id) ||
                (!ticket.isGlobal && ticket.holderUserId == event.user.id)
            )
        if (!allowed || ticket == null) {
            event.replyComponents(simpleTitleAndDescriptionContainer("エラー", "使用可能なチケットが見つかりません。", Color.RED))
                .useComponentsV2().setEphemeral(true).queue()
            return
        }
        val targetMention = target?.let { "<@${it.userId}>" } ?: "対象不明"
        val label = if (ticket.isGlobal) "🌐 全体チケット" else "🎟️ 個人チケット"

        val container = Container.of(
            TextDisplay.of(
                "## ⚠️ ${label}行使の最終確認\n" +
                    "本当に $targetMention に対して **${label} (`#${ticket.id}`)** を行使しますか？\n" +
                    "実行すると対象メンバーへの **BAN手続きが即座に開始** され、このチケットは消費されます。"
            ),
            Separator.createInvisible(Separator.Spacing.SMALL),
            ActionRow.of(
                Button.danger("ticket_use_exec:${ticket.id}", "💀 執行する（確定）"),
                Button.secondary("ticket_use_cancel:${ticket.id}", "キャンセル"),
            ),
        ).withAccentColor(Color(0xE7, 0x4C, 0x3C))

        event.replyComponents(container).setAllowedMentions(emptyList()).useComponentsV2().setEphemeral(true).queue()
    }

    private fun handleTicketUseExec(
        event: ButtonInteractionEvent,
        guild: net.dv8tion.jda.api.entities.Guild,
        ticketId: Long?,
    ) {
        if (ticketId == null) return
        if (!WatchPermissions.ensureFeatureEnabledOrReply(event, guild)) return

        event.deferEdit().useComponentsV2().queue { hook ->
            OhagiBot.scope.launch {
                val (ok, msg) = BanTicketService.executeTicket(guild, ticketId, event.user.id)
                hook.editOriginalComponents(
                    simpleTitleAndDescriptionContainer(
                        if (ok) "⚔️ 処刑チケットを行使しました" else "行使できませんでした",
                        msg,
                        if (ok) Color(0xE7, 0x4C, 0x3C) else Color.RED,
                    )
                ).setAllowedMentions(emptyList()).useComponentsV2().queue()
            }
        }
    }
}
