package jp.simplespace.discord.listeners

import jp.simplespace.ai.GeminiClientSettings
import jp.simplespace.db.AiFeedbackVerdict
import jp.simplespace.db.AiRuleType
import jp.simplespace.db.AiWeightHint
import jp.simplespace.db.ReportPunishment
import jp.simplespace.db.ReportPunishmentType
import jp.simplespace.db.VoteChoice
import jp.simplespace.db.WatchScoreEvent
import jp.simplespace.db.WatchScoreEventType
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
import net.dv8tion.jda.api.components.label.Label
import net.dv8tion.jda.api.components.selections.StringSelectMenu
import net.dv8tion.jda.api.components.separator.Separator
import net.dv8tion.jda.api.components.textdisplay.TextDisplay
import net.dv8tion.jda.api.components.textinput.TextInput
import net.dv8tion.jda.api.components.textinput.TextInputStyle
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel
import net.dv8tion.jda.api.events.interaction.ModalInteractionEvent
import net.dv8tion.jda.api.events.interaction.command.CommandAutoCompleteInteractionEvent
import net.dv8tion.jda.api.events.interaction.component.ButtonInteractionEvent
import net.dv8tion.jda.api.events.interaction.component.StringSelectInteractionEvent
import net.dv8tion.jda.api.hooks.ListenerAdapter
import net.dv8tion.jda.api.interactions.commands.Command
import net.dv8tion.jda.api.modals.Modal
import java.awt.Color
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

class WatchInteractionListener : ListenerAdapter() {
    companion object {
        private const val REFRESH_COOLDOWN_MILLIS = 30_000L
        private val lastRefreshByTarget = ConcurrentHashMap<Long, Long>()
        const val MODAL_REPORT_PREFIX = "watch_report_modal:"
        const val SELECT_FEEDBACK_PREFIX = "watch_feedback_select:"
        const val MODAL_FEEDBACK_PREFIX = "watch_feedback_modal:"

        fun parseFeedbackExpectedDelta(raw: String?): Double? {
            if (raw.isNullOrBlank()) return null
            val normalized = buildString {
                for (ch in raw.trim()) {
                    when (ch) {
                        in '０'..'９' -> append(('0'.code + (ch.code - '０'.code)).toChar())
                        '．' -> append('.')
                        '＋' -> append('+')
                        '％' -> append('%')
                        ' ', '　' -> Unit
                        else -> append(ch)
                    }
                }
            }.removePrefix("+").removeSuffix("%")
            val parsed = normalized.toDoubleOrNull() ?: return null
            if (!parsed.isFinite() || parsed < 0.0 || parsed > 25.0) return null
            return kotlin.math.round(parsed * 10.0) / 10.0
        }

        fun buildFeedbackModalForEvent(
            targetId: Long,
            targetDisplayName: String,
            sourceEvent: WatchScoreEvent,
        ): Modal {
            val verdictMenu = StringSelectMenu.create("feedback_verdict")
                .addOption("過剰判定 / 誤爆 (FALSE_POSITIVE)", AiFeedbackVerdict.FALSE_POSITIVE.name)
                .addOption("甘すぎた (UNDERESTIMATED)", AiFeedbackVerdict.UNDERESTIMATED.name)
                .addOption("見逃し (MISSED)", AiFeedbackVerdict.MISSED.name)
                .addOption("適正判定 (CORRECT)", AiFeedbackVerdict.CORRECT.name)
                .setDefaultValues(AiFeedbackVerdict.FALSE_POSITIVE.name)
                .build()

            val expectedInput = TextInput.create("feedback_expected_delta", TextInputStyle.SHORT)
                .setPlaceholder(String.format(Locale.US, "例: 0 や 10（現在: +%.1f%% / 0〜25）", sourceEvent.delta))
                .setMaxLength(10)
                .setRequired(true)
                .build()

            val applyDiffMenu = StringSelectMenu.create("feedback_apply_diff")
                .addOption("学習のみ（現在違反度は変更しない）", "NO")
                .addOption("元スコアとの差分を現在違反度にも補正反映する", "YES")
                .setDefaultValues("NO")
                .build()

            val noteInput = TextInput.create("feedback_note", TextInputStyle.PARAGRAPH)
                .setPlaceholder("例: 仲間内の冗談であり攻撃性はないため誤爆")
                .setMaxLength(500)
                .setRequired(false)
                .build()

            val safeName = targetDisplayName.take(15)
            return Modal.create("$MODAL_FEEDBACK_PREFIX$targetId:${sourceEvent.id}", "🧠 $safeName #${sourceEvent.id} フィードバック")
                .addComponents(
                    Label.of("判定の評価", verdictMenu),
                    Label.of("望ましかったスコア増分（0〜25）", expectedInput),
                    Label.of("現在違反度への差分補正", applyDiffMenu),
                    Label.of("補足説明（任意）", "なぜその評価になるかを書くと以降のAI精度が向上します", noteInput),
                )
                .build()
        }

        fun buildFeedbackModalManual(
            targetId: Long,
            targetDisplayName: String,
        ): Modal {
            val verdictMenu = StringSelectMenu.create("feedback_verdict")
                .addOption("見逃し (MISSED)", AiFeedbackVerdict.MISSED.name)
                .addOption("過剰判定 / 誤爆 (FALSE_POSITIVE)", AiFeedbackVerdict.FALSE_POSITIVE.name)
                .addOption("甘すぎた (UNDERESTIMATED)", AiFeedbackVerdict.UNDERESTIMATED.name)
                .addOption("適正判定 (CORRECT)", AiFeedbackVerdict.CORRECT.name)
                .setDefaultValues(AiFeedbackVerdict.MISSED.name)
                .build()

            val expectedInput = TextInput.create("feedback_expected_delta", TextInputStyle.SHORT)
                .setPlaceholder("例: 10 や 15（0〜25）")
                .setMaxLength(10)
                .setRequired(true)
                .build()

            val messageInput = TextInput.create("feedback_message_text", TextInputStyle.PARAGRAPH)
                .setPlaceholder("見逃された発言テキスト、またはDiscordのメッセージリンクを入力")
                .setMinLength(1)
                .setMaxLength(500)
                .setRequired(true)
                .build()

            val noteInput = TextInput.create("feedback_note", TextInputStyle.PARAGRAPH)
                .setPlaceholder("例: 遠回しな煽り表現なので中程度の違反として検知してほしい")
                .setMaxLength(500)
                .setRequired(false)
                .build()

            val safeName = targetDisplayName.take(16)
            return Modal.create("$MODAL_FEEDBACK_PREFIX$targetId:manual", "🧠 $safeName へのAIフィードバック")
                .addComponents(
                    Label.of("判定の評価", verdictMenu),
                    Label.of("望ましかったスコア増分（0〜25）", expectedInput),
                    Label.of("対象の発言テキスト", messageInput),
                    Label.of("補足説明（任意）", "なぜその評価になるかを書くと以降のAI精度が向上します", noteInput),
                )
                .build()
        }

        fun buildIncidentReportModal(targetId: Long, targetDisplayName: String): Modal {
            val placeMenu = StringSelectMenu.create("incident_place")
                .addOption("VC（ボイスチャット）", "VC（ボイスチャット）")
                .addOption("ゲーム内 / 外部チャット", "ゲーム内 / 外部チャット")
                .addOption("その他（Bot監視外）", "その他（Bot監視外）")
                .setDefaultValues("VC（ボイスチャット）")
                .build()

            val punishmentMenu = StringSelectMenu.create("punishment_type")
                .addOption(ReportPunishmentType.SCORE_15.displayName, ReportPunishmentType.SCORE_15.name)
                .addOption(ReportPunishmentType.SCORE_30.displayName, ReportPunishmentType.SCORE_30.name)
                .addOption(ReportPunishmentType.SCORE_50.displayName, ReportPunishmentType.SCORE_50.name)
                .addOption(ReportPunishmentType.CUSTOM.displayName, ReportPunishmentType.CUSTOM.name)
                .addOption(ReportPunishmentType.BAN.displayName, ReportPunishmentType.BAN.name)
                .setDefaultValues(ReportPunishmentType.SCORE_30.name)
                .build()

            val customScoreInput = TextInput.create("custom_score_delta", TextInputStyle.SHORT)
                .setPlaceholder("例: 20 や 12.5（0.1〜100。空欄なら上のプリセットを使用）")
                .setMaxLength(10)
                .setRequired(false)
                .build()

            val detailInput = TextInput.create("report_detail", TextInputStyle.PARAGRAPH)
                .setPlaceholder("例: VCで試合後にAさんへ暴言を吐いていた")
                .setMinLength(3)
                .setMaxLength(1000)
                .setRequired(true)
                .build()

            val safeName = targetDisplayName.take(20)
            return Modal.create("$MODAL_REPORT_PREFIX$targetId", "🚨 $safeName の違反報告（投票開始）")
                .addComponents(
                    Label.of("発生場所・状況", placeMenu),
                    Label.of("提案する処罰内容", punishmentMenu),
                    Label.of("違反度の自由加算数（任意）", "数値を入力するとその加算数が適用されます（0.1〜100）", customScoreInput),
                    Label.of("報告内容", "Botが監視できない場所での問題行動を具体的に記入してください", detailInput),
                )
                .build()
        }

        fun resolveReportPunishment(
            selectedPresetRaw: String?,
            customDeltaRaw: String?,
        ): Pair<ReportPunishment?, String?> {
            val preset = ReportPunishmentType.fromString(selectedPresetRaw)
            val customTrimmed = customDeltaRaw?.trim().orEmpty()
            if (customTrimmed.isNotEmpty()) {
                if (preset == ReportPunishmentType.BAN) {
                    return null to "「BAN 処刑」と自由指定の違反度加算数は同時に指定できません。数値加算にする場合はプリセットを「自由入力の違反度を加算」にしてください。"
                }
                val parsed = ReportPunishment.parseCustomDeltaInput(customTrimmed)
                    ?: return null to "違反度加算数は `0.1` 〜 `100` の数値で入力してください（例: `20`、`12.5`）。"
                return ReportPunishment.score(parsed) to null
            }
            if (preset == ReportPunishmentType.CUSTOM) {
                return null to "「自由入力の違反度を加算」を選んだ場合は、違反度の自由加算数（`0.1` 〜 `100`）を入力してください。"
            }
            return ReportPunishment.fromPreset(preset) to null
        }
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
            return
        }

        if (event.name == "watch" &&
            event.subcommandGroup == "ai" &&
            event.subcommandName == "model" &&
            event.focusedOption.name == "model"
        ) {
            val typed = event.focusedOption.value.lowercase()
            val candidates = listOf("default" to "default (application.yaml / 環境変数の既定値へ戻す)") +
                GeminiClientSettings.RECOMMENDED_MODELS.map { it to it }
            val choices = candidates
                .filter { (value, label) -> typed.isBlank() || value.lowercase().contains(typed) || label.lowercase().contains(typed) }
                .take(25)
                .map { (value, label) -> Command.Choice(label.take(100), value) }
            event.replyChoices(choices).queue()
        }
    }

    override fun onStringSelectInteraction(event: StringSelectInteractionEvent) {
        val guild = event.guild ?: return
        if (event.componentId.startsWith(SELECT_FEEDBACK_PREFIX)) {
            handleFeedbackEventSelect(event, guild)
            return
        }
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
        if (event.modalId.startsWith(MODAL_REPORT_PREFIX)) {
            handleIncidentReportModalSubmit(event, guild)
            return
        }
        if (event.modalId.startsWith(MODAL_FEEDBACK_PREFIX)) {
            handleFeedbackModalSubmit(event, guild)
            return
        }
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
            id.startsWith("watch_report:") -> handleReportButton(event, guild, id.substringAfter("watch_report:").toLongOrNull())
            id.startsWith("watch_feedback:") -> handleFeedbackButton(event, guild, id.substringAfter("watch_feedback:").toLongOrNull())
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

    private fun handleReportButton(
        event: ButtonInteractionEvent,
        guild: net.dv8tion.jda.api.entities.Guild,
        targetId: Long?,
    ) {
        if (targetId == null) return
        if (!WatchPermissions.ensureFeatureEnabledOrReply(event, guild)) return
        val target = watchConfigService.getTargetById(targetId)
        if (target == null || target.guildId != guild.id || !target.active) {
            event.replyComponents(simpleTitleAndDescriptionContainer("エラー", "対象の監視データが見つかりません。", Color.RED))
                .useComponentsV2().setEphemeral(true).queue()
            return
        }
        if (event.user.id == target.userId) {
            event.replyComponents(
                simpleTitleAndDescriptionContainer("操作できません", "監視対象者本人は自分を報告できません。", Color.RED)
            ).useComponentsV2().setEphemeral(true).queue()
            return
        }
        val displayName = guild.getMemberById(target.userId)?.effectiveName
            ?: guild.jda.getUserById(target.userId)?.effectiveName
            ?: "対象"
        event.replyModal(buildIncidentReportModal(target.id, displayName)).queue()
    }

    private fun handleIncidentReportModalSubmit(
        event: ModalInteractionEvent,
        guild: net.dv8tion.jda.api.entities.Guild,
    ) {
        if (!WatchPermissions.ensureFeatureEnabledOrReply(event, guild)) return
        val targetId = event.modalId.substringAfter(MODAL_REPORT_PREFIX).toLongOrNull() ?: return
        val target = watchConfigService.getTargetById(targetId)
        if (target == null || target.guildId != guild.id || !target.active) {
            event.replyComponents(simpleTitleAndDescriptionContainer("エラー", "対象の監視データが見つかりません。", Color.RED))
                .useComponentsV2().setEphemeral(true).queue()
            return
        }
        val fallbackChannel = event.channel as? TextChannel
        if (fallbackChannel == null) {
            event.replyComponents(simpleTitleAndDescriptionContainer("エラー", "テキストチャンネル内で実行してください。", Color.RED))
                .useComponentsV2().setEphemeral(true).queue()
            return
        }

        val place = event.getValue("incident_place")?.asStringList?.firstOrNull() ?: "VC（ボイスチャット）"
        val (punishment, punishmentError) = resolveReportPunishment(
            selectedPresetRaw = event.getValue("punishment_type")?.asStringList?.firstOrNull(),
            customDeltaRaw = event.getValue("custom_score_delta")?.asString,
        )
        if (punishment == null) {
            event.replyComponents(
                simpleTitleAndDescriptionContainer("入力エラー", punishmentError ?: "処罰内容の指定が不正です。", Color.RED)
            ).useComponentsV2().setEphemeral(true).queue()
            return
        }
        val detail = event.getValue("report_detail")?.asString?.trim()?.takeIf { it.isNotEmpty() }
        if (detail == null) {
            event.replyComponents(simpleTitleAndDescriptionContainer("入力エラー", "報告内容を入力してください。", Color.RED))
                .useComponentsV2().setEphemeral(true).queue()
            return
        }

        val result = VoteBanService.startIncidentReportVote(
            guild = guild,
            fallbackChannel = fallbackChannel,
            target = target,
            reporterUserId = event.user.id,
            incidentPlace = place,
            punishment = punishment,
            detail = detail,
        )
        event.replyComponents(
            simpleTitleAndDescriptionContainer(
                if (result.success) "🚨 違反報告＆処罰投票を開始しました" else "開始できませんでした",
                result.message,
                if (result.success) Color(0x2E, 0xCC, 0x71) else Color.RED,
            )
        ).setAllowedMentions(emptyList()).useComponentsV2().setEphemeral(true).queue()
    }

    private fun handleFeedbackButton(
        event: ButtonInteractionEvent,
        guild: net.dv8tion.jda.api.entities.Guild,
        targetId: Long?,
    ) {
        if (targetId == null) return
        if (!WatchPermissions.ensureFeatureEnabledOrReply(event, guild)) return
        if (!WatchPermissions.ensureAdminOrReply(event, guild, event.member)) return
        val target = watchConfigService.getTargetById(targetId)
        if (target == null || target.guildId != guild.id) {
            event.replyComponents(simpleTitleAndDescriptionContainer("エラー", "対象の監視データが見つかりません。", Color.RED))
                .useComponentsV2().setEphemeral(true).queue()
            return
        }
        if (WatchPermissions.isSelfProtectionBlocked(event.user.id, target.userId)) {
            event.replyComponents(
                simpleTitleAndDescriptionContainer("操作できません", "監視対象者本人は自分の判定に対してフィードバックを登録できません。", Color.RED)
            ).useComponentsV2().setEphemeral(true).queue()
            return
        }

        val displayName = guild.getMemberById(target.userId)?.effectiveName
            ?: guild.jda.getUserById(target.userId)?.effectiveName
            ?: "対象"
        val recentViolations = watchCycleService.getRecentAiViolationsForTarget(target.id, limit = 24)
        if (recentViolations.isEmpty()) {
            event.replyModal(buildFeedbackModalManual(target.id, displayName)).queue()
            return
        }

        val selectBuilder = StringSelectMenu.create("$SELECT_FEEDBACK_PREFIX${target.id}")
            .setPlaceholder("フィードバック対象のAI違反ログ、または見逃し手動入力を選択")
            .addOption("📝 新規・見逃し発言を手動入力（イベント未指定）", "manual", "AIが見逃した発言などをテキスト入力で登録します")
        recentViolations.forEach { ev ->
            val label = String.format(Locale.US, "#%d (+%.1f%%) %s", ev.id, ev.delta, ev.category ?: "違反").take(100)
            val desc = (ev.sourceMessageExcerpt ?: ev.reason ?: "詳細なし")
                .replace("\n", " ")
                .trim()
                .take(100)
            if (desc.isNotEmpty()) {
                selectBuilder.addOption(label, "event:${ev.id}", desc)
            } else {
                selectBuilder.addOption(label, "event:${ev.id}")
            }
        }

        val container = Container.of(
            TextDisplay.of(
                "## 🧠 AIフィードバック登録（対象: <@${target.userId}>）\n" +
                    "直近のAI違反ログから評価対象を選ぶか、**「新規・見逃し発言を手動入力」** を選んでください。"
            ),
            Separator.createInvisible(Separator.Spacing.SMALL),
            ActionRow.of(selectBuilder.build()),
        ).withAccentColor(Color(0x34, 0x98, 0xDB))

        event.replyComponents(container).setAllowedMentions(emptyList()).useComponentsV2().setEphemeral(true).queue()
    }

    private fun handleFeedbackEventSelect(
        event: StringSelectInteractionEvent,
        guild: net.dv8tion.jda.api.entities.Guild,
    ) {
        if (!WatchPermissions.ensureFeatureEnabledOrReply(event, guild)) return
        if (!WatchPermissions.ensureAdminOrReply(event, guild, event.member)) return
        val targetId = event.componentId.substringAfter(SELECT_FEEDBACK_PREFIX).toLongOrNull() ?: return
        val target = watchConfigService.getTargetById(targetId)
        if (target == null || target.guildId != guild.id) {
            event.replyComponents(simpleTitleAndDescriptionContainer("エラー", "対象の監視データが見つかりません。", Color.RED))
                .useComponentsV2().setEphemeral(true).queue()
            return
        }
        if (WatchPermissions.isSelfProtectionBlocked(event.user.id, target.userId)) {
            event.replyComponents(
                simpleTitleAndDescriptionContainer("操作できません", "監視対象者本人は自分の判定に対してフィードバックを登録できません。", Color.RED)
            ).useComponentsV2().setEphemeral(true).queue()
            return
        }

        val displayName = guild.getMemberById(target.userId)?.effectiveName
            ?: guild.jda.getUserById(target.userId)?.effectiveName
            ?: "対象"
        val selected = event.values.firstOrNull() ?: return
        if (selected == "manual") {
            event.replyModal(buildFeedbackModalManual(target.id, displayName)).queue()
            return
        }
        if (selected.startsWith("event:")) {
            val eventId = selected.substringAfter("event:").toLongOrNull() ?: return
            val sourceEvent = watchCycleService.getEventById(eventId)
            if (sourceEvent == null || sourceEvent.guildId != guild.id || sourceEvent.targetId != target.id) {
                event.replyComponents(simpleTitleAndDescriptionContainer("エラー", "イベント `#${eventId}` が見つかりません。", Color.RED))
                    .useComponentsV2().setEphemeral(true).queue()
                return
            }
            event.replyModal(buildFeedbackModalForEvent(target.id, displayName, sourceEvent)).queue()
        }
    }

    private fun handleFeedbackModalSubmit(
        event: ModalInteractionEvent,
        guild: net.dv8tion.jda.api.entities.Guild,
    ) {
        if (!WatchPermissions.ensureFeatureEnabledOrReply(event, guild)) return
        if (!WatchPermissions.ensureAdminOrReply(event, guild, event.member)) return

        val parts = event.modalId.substringAfter(MODAL_FEEDBACK_PREFIX).split(":")
        val targetId = parts.getOrNull(0)?.toLongOrNull() ?: return
        val mode = parts.getOrNull(1) ?: return
        val target = watchConfigService.getTargetById(targetId)
        if (target == null || target.guildId != guild.id) {
            event.replyComponents(simpleTitleAndDescriptionContainer("エラー", "対象の監視データが見つかりません。", Color.RED))
                .useComponentsV2().setEphemeral(true).queue()
            return
        }
        if (WatchPermissions.isSelfProtectionBlocked(event.user.id, target.userId)) {
            event.replyComponents(
                simpleTitleAndDescriptionContainer("操作できません", "監視対象者本人は自分の判定に対してフィードバックを登録できません。", Color.RED)
            ).useComponentsV2().setEphemeral(true).queue()
            return
        }

        val sourceEvent = if (mode != "manual") {
            val eventId = mode.toLongOrNull()
            val found = eventId?.let { watchCycleService.getEventById(it) }
            if (found == null || found.guildId != guild.id || found.targetId != target.id) {
                event.replyComponents(simpleTitleAndDescriptionContainer("エラー", "対象の違反イベントが見つかりません。", Color.RED))
                    .useComponentsV2().setEphemeral(true).queue()
                return
            }
            found
        } else {
            null
        }

        val verdict = AiFeedbackVerdict.fromString(
            event.getValue("feedback_verdict")?.asStringList?.firstOrNull()
        )
        val expectedDelta = parseFeedbackExpectedDelta(event.getValue("feedback_expected_delta")?.asString)
        if (expectedDelta == null) {
            event.replyComponents(
                simpleTitleAndDescriptionContainer("入力エラー", "望ましかったスコア増分は `0` 〜 `25` の数値で入力してください。", Color.RED)
            ).useComponentsV2().setEphemeral(true).queue()
            return
        }
        val manualText = event.getValue("feedback_message_text")?.asString?.trim()?.takeIf { it.isNotEmpty() }
        val messageContent = sourceEvent?.sourceMessageExcerpt ?: manualText
        if (messageContent.isNullOrBlank()) {
            event.replyComponents(
                simpleTitleAndDescriptionContainer("入力エラー", "対象の発言テキストを入力してください。", Color.RED)
            ).useComponentsV2().setEphemeral(true).queue()
            return
        }
        val note = event.getValue("feedback_note")?.asString?.trim()?.takeIf { it.isNotEmpty() }
        val applyDiff = sourceEvent != null &&
            event.getValue("feedback_apply_diff")?.asStringList?.firstOrNull() == "YES"
        val originalDelta = sourceEvent?.delta ?: 0.0

        val fb = watchAiService.addFeedback(
            guildId = guild.id,
            targetId = target.id,
            scoreEventId = sourceEvent?.id,
            messageContent = messageContent,
            contextExcerpt = null,
            originalDelta = originalDelta,
            expectedDelta = expectedDelta,
            verdict = verdict,
            note = note,
            reviewerUserId = event.user.id,
        )

        event.deferReply(true).queue { hook ->
            OhagiBot.scope.launch {
                var diffNote = ""
                if (applyDiff) {
                    val diff = expectedDelta - originalDelta
                    if (diff != 0.0) {
                        WatchOrchestrator.applyScoreChange(
                            guild = guild,
                            target = target,
                            eventType = WatchScoreEventType.MANUAL_ADJUST,
                            delta = diff,
                            category = "FEEDBACK_CORRECTION",
                            reason = "フィードバック #${fb.id} に基づくスコア補正",
                            actorUserId = event.user.id,
                        )
                        val sign = if (diff >= 0) "+" else ""
                        diffNote = String.format(Locale.US, "\n- 現在違反度にも `%s%.1f%%` 補正反映しました。", sign, diff)
                    }
                }
                var reevalNote = ""
                if (verdict == AiFeedbackVerdict.MISSED && sourceEvent == null) {
                    val reeval = WatchOrchestrator.reevaluateMissedFeedback(
                        guild = guild,
                        target = target,
                        feedback = fb,
                        rawInputText = messageContent,
                    )
                    reevalNote = "\n- 🔄 **見逃し再審査**: ${reeval.summaryJa}"
                }
                val eventNote = sourceEvent?.let { "\n- **対象イベント**: `#${it.id}`" }.orEmpty()
                hook.editOriginalComponents(
                    simpleTitleAndDescriptionContainer(
                        "🧠 AIフィードバックを登録しました",
                        "フィードバック **`#${fb.id}`** を保存しました。以降のAI推論時に Few-shot 事例として自動参照されます。" +
                            eventNote +
                            "\n- **評価**: ${verdict.displayName}（元 `$originalDelta` → 望ましい値 `$expectedDelta`）" +
                            diffNote +
                            reevalNote,
                        Color(0x2E, 0xCC, 0x71),
                    )
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
