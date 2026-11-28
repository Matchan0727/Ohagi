package jp.simplespace.discord.commands.watch

import com.jagrosh.jdautilities.command.SlashCommand
import com.jagrosh.jdautilities.command.SlashCommandEvent
import jp.simplespace.ai.GeminiClientSettings
import jp.simplespace.db.AiAutoTicketMode
import jp.simplespace.db.AiFeedbackVerdict
import jp.simplespace.db.AiRuleType
import jp.simplespace.db.AiTaskType
import jp.simplespace.db.AiWeightHint
import jp.simplespace.db.WatchAiRule
import jp.simplespace.db.WatchRoleCounter
import jp.simplespace.db.WatchScoreEventType
import jp.simplespace.discord.OhagiBot
import jp.simplespace.discord.utils.simpleTitleAndDescriptionContainer
import jp.simplespace.watch.RoleRestoreService
import jp.simplespace.watch.WatchOrchestrator
import jp.simplespace.watch.WatchPermissions
import jp.simplespace.watchAiService
import jp.simplespace.watchConfigService
import jp.simplespace.watchCycleService
import kotlinx.coroutines.launch
import net.dv8tion.jda.api.components.actionrow.ActionRow
import net.dv8tion.jda.api.components.container.Container
import net.dv8tion.jda.api.components.label.Label
import net.dv8tion.jda.api.components.selections.SelectOption
import net.dv8tion.jda.api.components.selections.StringSelectMenu
import net.dv8tion.jda.api.components.separator.Separator
import net.dv8tion.jda.api.components.textdisplay.TextDisplay
import net.dv8tion.jda.api.components.textinput.TextInput
import net.dv8tion.jda.api.components.textinput.TextInputStyle
import net.dv8tion.jda.api.entities.channel.ChannelType
import net.dv8tion.jda.api.interactions.InteractionContextType
import net.dv8tion.jda.api.interactions.commands.OptionType
import net.dv8tion.jda.api.interactions.commands.build.OptionData
import net.dv8tion.jda.api.interactions.commands.build.SubcommandGroupData
import net.dv8tion.jda.api.modals.Modal
import java.awt.Color
import java.util.Locale

class WatchCommand : SlashCommand() {
    init {
        name = "watch"
        help = "メンバー監視・違反度・AIチューニング・BAN解除を管理します。"
        contexts = arrayOf(InteractionContextType.GUILD)
        children = arrayOf(
            // target グループ
            WatchTargetAddSub(),
            WatchTargetRemoveSub(),
            WatchTargetListSub(),
            // config グループ
            WatchConfigShowSub(),
            WatchConfigChannelSub(),
            WatchConfigBanSub(),
            WatchConfigDecaySub(),
            WatchConfigVoteSub(),
            WatchConfigAiSub(),
            // score グループ
            WatchScoreAdjustSub(),
            // ai グループ
            WatchAiRuleAddSub(),
            WatchAiRuleListSub(),
            WatchAiRuleEditSub(),
            WatchAiRuleRemoveSub(),
            WatchAiFeedbackSub(),
            WatchAiModelSub(),
            // role-counter グループ
            WatchRoleCounterSetSub(),
            WatchRoleCounterListSub(),
            WatchRoleCounterRemoveSub(),
            // 直下コマンド
            WatchUnbanSub(),
            WatchCancelBanSub(),
            WatchRefreshSub(),
        )
    }

    override fun execute(event: SlashCommandEvent) {}
}

private fun targetGroup() = SubcommandGroupData("target", "監視対象メンバーを管理します")
private fun configGroup() = SubcommandGroupData("config", "サーバー単位の監視設定を管理します")
private fun scoreGroup() = SubcommandGroupData("score", "違反度スコアを操作します")
private fun aiGroup() = SubcommandGroupData("ai", "AI判定ルールとフィードバックを管理します")
private fun roleCounterGroup() = SubcommandGroupData("role-counter", "BAN再参加時のロール名自動カウント更新を管理します")

private class WatchTargetAddSub : SlashCommand() {
    init {
        name = "add"
        help = "監視対象メンバーを登録します（個別専用チャンネル・メモ指定可）。"
        subcommandGroup = targetGroup()
        options = listOf(
            OptionData(OptionType.USER, "user", "監視対象にするメンバー", true),
            OptionData(OptionType.CHANNEL, "channel", "このメンバー専用のステータスチャンネル（省略時はサーバーデフォルト）", false)
                .setChannelTypes(ChannelType.TEXT),
            OptionData(OptionType.STRING, "note", "対象者の特徴メモ（AI判定の参考情報）", false),
        )
    }

    override fun execute(event: SlashCommandEvent) {
        val guild = event.guild
        if (!WatchPermissions.ensureFeatureEnabledOrReply(event, guild)) return
        guild!!
        if (!WatchPermissions.ensureAdminOrReply(event, guild, event.member)) return

        val targetUser = event.getOption("user")?.asUser ?: return
        if (targetUser.isBot) {
            replyEph(event, "エラー", "Botは監視対象にできません。", Color.RED)
            return
        }
        val dedicatedChannel = event.getOption("channel")?.asChannel
        val note = event.optString("note")?.trim()?.takeIf { it.isNotEmpty() }

        event.deferReply(true).queue { hook ->
            OhagiBot.scope.launch {
                val result = WatchOrchestrator.registerTarget(
                    guild = guild,
                    userId = targetUser.id,
                    dedicatedChannelId = dedicatedChannel?.id,
                    personaNote = note,
                    actorUserId = event.user.id,
                )
                val desc = buildString {
                    append("<@${targetUser.id}> を監視対象に登録しました（第${result.cycle.cycleNumber}サイクル開始）。")
                    if (dedicatedChannel != null) {
                        append("\n- **専用チャンネル**: <#${dedicatedChannel.id}>")
                    }
                    if (!note.isNullOrBlank()) {
                        append("\n- **特徴メモ**: $note")
                    }
                    if (!result.warningMessage.isNullOrBlank()) {
                        append("\n\n⚠️ **注意**:\n${result.warningMessage}")
                    }
                }
                hook.editOriginalComponents(
                    simpleTitleAndDescriptionContainer("🛡️ 監視対象を登録しました", desc, Color(0x2E, 0xCC, 0x71))
                ).setAllowedMentions(emptyList()).useComponentsV2().queue()
            }
        }
    }
}

private class WatchTargetRemoveSub : SlashCommand() {
    init {
        name = "remove"
        help = "監視対象から解除します（過去ログは保持されます）。"
        subcommandGroup = targetGroup()
        options = listOf(
            OptionData(OptionType.USER, "user", "監視解除するメンバー", true),
        )
    }

    override fun execute(event: SlashCommandEvent) {
        val guild = event.guild
        if (!WatchPermissions.ensureFeatureEnabledOrReply(event, guild)) return
        guild!!
        if (!WatchPermissions.ensureAdminOrReply(event, guild, event.member)) return

        val targetUser = event.getOption("user")?.asUser ?: return
        if (WatchPermissions.isSelfProtectionBlocked(event.user.id, targetUser.id)) {
            replyEph(event, "操作できません", "監視対象者本人は自分を監視対象から解除できません。", Color.RED)
            return
        }

        event.deferReply(true).queue { hook ->
            OhagiBot.scope.launch {
                val removed = WatchOrchestrator.unregisterTarget(guild, targetUser.id)
                val (title, desc, color) = if (removed) {
                    Triple("監視対象を解除しました", "<@${targetUser.id}> の監視を解除しました。", Color.ORANGE)
                } else {
                    Triple("対象が見つかりません", "<@${targetUser.id}> は現在監視対象ではありません。", Color.RED)
                }
                hook.editOriginalComponents(simpleTitleAndDescriptionContainer(title, desc, color))
                    .setAllowedMentions(emptyList()).useComponentsV2().queue()
            }
        }
    }
}

private class WatchTargetListSub : SlashCommand() {
    init {
        name = "list"
        help = "このサーバーの監視対象メンバー一覧と現在違反度を表示します。"
        subcommandGroup = targetGroup()
    }

    override fun execute(event: SlashCommandEvent) {
        val guild = event.guild
        if (!WatchPermissions.ensureFeatureEnabledOrReply(event, guild)) return
        guild!!
        val targets = watchConfigService.listTargets(guild.id, activeOnly = true)
        val config = watchConfigService.getOrCreateConfig(guild.id)
        val desc = if (targets.isEmpty()) {
            "現在登録されている監視対象メンバーはいません。"
        } else {
            buildString {
                targets.forEach { t ->
                    val cycle = t.currentCycleId?.let { watchCycleService.getCycleById(it) }
                    val scoreStr = String.format(Locale.US, "%.1f%%", cycle?.currentScore ?: 0.0)
                    val chId = watchConfigService.resolveStatusChannelId(t, config)
                    val chMention = chId?.let { "<#$it>" } ?: "未設定"
                    val banCount = watchCycleService.countBansByTarget(t.id)
                    append("- <@${t.userId}> | 違反度: **$scoreStr** | 状態: `${cycle?.status?.name ?: "N/A"}` | BAN: **${banCount}回** | ch: $chMention\n")
                }
            }.trim()
        }
        replyEph(event, "🛡️ 監視対象メンバー一覧", desc, Color(0x36, 0xB8, 0xFA))
    }
}

private class WatchConfigShowSub : SlashCommand() {
    init {
        name = "show"
        help = "このサーバーの現在の監視設定を一覧表示します。"
        subcommandGroup = configGroup()
    }

    override fun execute(event: SlashCommandEvent) {
        val guild = event.guild
        if (!WatchPermissions.ensureFeatureEnabledOrReply(event, guild)) return
        guild!!
        if (!WatchPermissions.ensureAdminOrReply(event, guild, event.member)) return

        val c = watchConfigService.getOrCreateConfig(guild.id)
        val desc = buildString {
            append("### チャンネル・権限\n")
            append("- デフォルト専用ch: ${c.defaultChannelId?.let { "<#$it>" } ?: "未設定"}\n")
            append("- AI承認通知ch: ${c.approvalChannelId?.let { "<#$it>" } ?: "デフォルトと同じ"}\n")
            append("- 監視管理者ロール: ${c.managerRoleId?.let { "<@&$it>" } ?: "未設定（BAN権限者のみ）"}\n")
            append("- AI監視除外ch: ${if (c.excludedChannelIds.isEmpty()) "なし" else c.excludedChannelIds.joinToString(", ") { "<#$it>" }}\n\n")
            append("### BAN・遺言\n")
            append("- 遺言猶予時間: **${c.banGraceSeconds}秒**\n")
            append("- 投票可決時の遺言: **${if (c.voteUseLastWords) "あり" else "即時BAN"}** / チケット行使時の遺言: **${if (c.ticketUseLastWords) "あり" else "即時BAN"}**\n\n")
            append("### AI判定\n")
            append("- 自動判定: **${if (c.aiEnabled) "ON" else "OFF"}** | 感度倍率: **${c.aiSensitivity}x** | 1回上限: **${c.aiMaxDeltaPerEval}%**\n")
            append("- 処刑チケットAI提案モード: **`${c.aiAutoTicketMode.name}`**\n")
            val taskConfigs = watchAiService.listTaskConfigsByGuild(guild.id)
            AiTaskType.entries.forEach { task ->
                val dbCfg = taskConfigs[task]
                val runtime = OhagiBot.aiModerationClient?.resolveTaskConfig(task, dbCfg?.modelName, dbCfg?.thinkingLevel)
                val modelText = runtime?.model ?: (dbCfg?.modelName ?: GeminiClientSettings.DEFAULT_MODEL)
                val thinkingText = runtime?.thinkingLevel ?: (dbCfg?.thinkingLevel ?: task.defaultThinkingLevel)
                append("- ${task.displayName}: モデル `${modelText}` / 思考量 `${thinkingText}`\n")
            }
            append("\n")
            append("### 時間減衰（更生）\n")
            append("- 減衰: **${if (c.decayEnabled) "ON" else "OFF"}** | 開始猶予: **${c.decayGraceHours}時間** | 間隔: **${c.decayIntervalHours}時間**\n")
            append("- 基本減衰量: **-${c.decayAmount}%** | 落ち着きボーナス倍率: **${c.decayCalmBonusMultiplier}x**\n\n")
            append("### メンバー投票BAN\n")
            append("- 投票: **${if (c.voteEnabled) "ON" else "OFF"}** | 受付時間: **${c.voteDurationMinutes}分** | 最低賛成: **${c.voteMinYesVotes}票** | 賛成率: **${(c.votePassRatio * 100).toInt()}%** | CD: **${c.voteCooldownMinutes}分**")
        }
        replyEph(event, "⚙️ 監視システム設定", desc, Color(0x36, 0xB8, 0xFA))
    }
}

private class WatchConfigChannelSub : SlashCommand() {
    init {
        name = "channel"
        help = "デフォルト専用ch・承認通知ch・AI除外chを設定します。"
        subcommandGroup = configGroup()
        options = listOf(
            OptionData(OptionType.CHANNEL, "default_channel", "デフォルト専用チャンネル", false)
                .setChannelTypes(ChannelType.TEXT),
            OptionData(OptionType.CHANNEL, "approval_channel", "AI処刑チケット承認用チャンネル", false)
                .setChannelTypes(ChannelType.TEXT),
            OptionData(OptionType.CHANNEL, "toggle_exclude", "AI監視から除外するチャンネルをトグル追加/解除", false)
                .setChannelTypes(ChannelType.TEXT),
        )
    }

    override fun execute(event: SlashCommandEvent) {
        val guild = event.guild
        if (!WatchPermissions.ensureFeatureEnabledOrReply(event, guild)) return
        guild!!
        if (!WatchPermissions.ensureAdminOrReply(event, guild, event.member)) return

        val defCh = event.getOption("default_channel")?.asChannel
        val appCh = event.getOption("approval_channel")?.asChannel
        val toggleEx = event.getOption("toggle_exclude")?.asChannel
        if (defCh == null && appCh == null && toggleEx == null) {
            replyEph(event, "入力エラー", "変更するオプションを1つ以上指定してください。", Color.RED)
            return
        }

        val updated = watchConfigService.updateConfig(guild.id) { cur ->
            val newExcluded = if (toggleEx != null) {
                if (toggleEx.id in cur.excludedChannelIds) cur.excludedChannelIds - toggleEx.id
                else cur.excludedChannelIds + toggleEx.id
            } else {
                cur.excludedChannelIds
            }
            cur.copy(
                defaultChannelId = defCh?.id ?: cur.defaultChannelId,
                approvalChannelId = appCh?.id ?: cur.approvalChannelId,
                excludedChannelIds = newExcluded,
            )
        }
        replyEph(
            event,
            "チャンネル設定を更新しました",
            "- デフォルト専用ch: ${updated.defaultChannelId?.let { "<#$it>" } ?: "未設定"}\n" +
                "- AI承認ch: ${updated.approvalChannelId?.let { "<#$it>" } ?: "デフォルトと同じ"}\n" +
                "- 除外ch数: ${updated.excludedChannelIds.size}件",
            Color(0x2E, 0xCC, 0x71),
        )
    }
}

private class WatchConfigBanSub : SlashCommand() {
    init {
        name = "ban"
        help = "遺言猶予時間・監視管理者ロール・投票/チケット時の遺言有無を設定します。"
        subcommandGroup = configGroup()
        options = listOf(
            OptionData(OptionType.INTEGER, "grace_seconds", "100%到達〜BANまでの遺言猶予時間（秒・0で即時BAN）", false)
                .setRequiredRange(0, 86400),
            OptionData(OptionType.ROLE, "manager_role", "監視管理コマンドを実行できる追加ロール", false),
            OptionData(OptionType.BOOLEAN, "vote_last_words", "投票可決時に遺言フェーズを挟むか", false),
            OptionData(OptionType.BOOLEAN, "ticket_last_words", "処刑チケット行使時に遺言フェーズを挟むか", false),
        )
    }

    override fun execute(event: SlashCommandEvent) {
        val guild = event.guild
        if (!WatchPermissions.ensureFeatureEnabledOrReply(event, guild)) return
        guild!!
        if (!WatchPermissions.ensureAdminOrReply(event, guild, event.member)) return

        val grace = event.getOption("grace_seconds")?.asInt
        val role = event.getOption("manager_role")?.asRole
        val voteLw = event.getOption("vote_last_words")?.asBoolean
        val ticketLw = event.getOption("ticket_last_words")?.asBoolean

        val updated = watchConfigService.updateConfig(guild.id) { cur ->
            cur.copy(
                banGraceSeconds = grace ?: cur.banGraceSeconds,
                managerRoleId = role?.id ?: cur.managerRoleId,
                voteUseLastWords = voteLw ?: cur.voteUseLastWords,
                ticketUseLastWords = ticketLw ?: cur.ticketUseLastWords,
            )
        }
        replyEph(
            event,
            "BAN・遺言設定を更新しました",
            "- 遺言猶予時間: **${updated.banGraceSeconds}秒**\n" +
                "- 管理者ロール: ${updated.managerRoleId?.let { "<@&$it>" } ?: "未設定"}\n" +
                "- 投票時遺言: **${updated.voteUseLastWords}** / チケット時遺言: **${updated.ticketUseLastWords}**",
            Color(0x2E, 0xCC, 0x71),
        )
    }
}

private class WatchConfigDecaySub : SlashCommand() {
    init {
        name = "decay"
        help = "時間経過による違反度の減衰（更生）設定を変更します。"
        subcommandGroup = configGroup()
        options = listOf(
            OptionData(OptionType.BOOLEAN, "enabled", "時間減衰のON/OFF", false),
            OptionData(OptionType.INTEGER, "grace_hours", "最終違反から減衰開始までの無違反時間（時間）", false).setRequiredRange(1, 720),
            OptionData(OptionType.INTEGER, "interval_hours", "減衰を実行する間隔（時間）", false).setRequiredRange(1, 168),
            OptionData(OptionType.NUMBER, "amount", "1回あたりの基本減衰量（%）", false).setRequiredRange(0.5, 50.0),
            OptionData(OptionType.NUMBER, "calm_bonus", "前週比で落ち着いている時の減衰倍率（例: 1.5）", false).setRequiredRange(1.0, 5.0),
        )
    }

    override fun execute(event: SlashCommandEvent) {
        val guild = event.guild
        if (!WatchPermissions.ensureFeatureEnabledOrReply(event, guild)) return
        guild!!
        if (!WatchPermissions.ensureAdminOrReply(event, guild, event.member)) return

        val updated = watchConfigService.updateConfig(guild.id) { cur ->
            cur.copy(
                decayEnabled = event.getOption("enabled")?.asBoolean ?: cur.decayEnabled,
                decayGraceHours = event.getOption("grace_hours")?.asInt ?: cur.decayGraceHours,
                decayIntervalHours = event.getOption("interval_hours")?.asInt ?: cur.decayIntervalHours,
                decayAmount = event.getOption("amount")?.asDouble ?: cur.decayAmount,
                decayCalmBonusMultiplier = event.getOption("calm_bonus")?.asDouble ?: cur.decayCalmBonusMultiplier,
            )
        }
        replyEph(
            event,
            "時間減衰設定を更新しました",
            "- 減衰: **${if (updated.decayEnabled) "ON" else "OFF"}**\n" +
                "- 無違反猶予: **${updated.decayGraceHours}時間** / 間隔: **${updated.decayIntervalHours}時間**\n" +
                "- 基本減衰量: **-${updated.decayAmount}%** / 落ち着き倍率: **${updated.decayCalmBonusMultiplier}x**",
            Color(0x2E, 0xCC, 0x71),
        )
    }
}

private class WatchConfigVoteSub : SlashCommand() {
    init {
        name = "vote"
        help = "メンバー投票BANの可決条件や受付時間を設定します。"
        subcommandGroup = configGroup()
        options = listOf(
            OptionData(OptionType.BOOLEAN, "enabled", "投票BAN機能のON/OFF", false),
            OptionData(OptionType.INTEGER, "duration_minutes", "投票受付時間（分）", false).setRequiredRange(1, 1440),
            OptionData(OptionType.INTEGER, "min_yes", "可決に必要な最低賛成票数", false).setRequiredRange(1, 100),
            OptionData(OptionType.NUMBER, "pass_ratio", "可決に必要な賛成率（0.1〜1.0、例: 0.6）", false).setRequiredRange(0.1, 1.0),
            OptionData(OptionType.INTEGER, "cooldown_minutes", "否決後の再発議クールダウン（分）", false).setRequiredRange(0, 10080),
        )
    }

    override fun execute(event: SlashCommandEvent) {
        val guild = event.guild
        if (!WatchPermissions.ensureFeatureEnabledOrReply(event, guild)) return
        guild!!
        if (!WatchPermissions.ensureAdminOrReply(event, guild, event.member)) return

        val updated = watchConfigService.updateConfig(guild.id) { cur ->
            cur.copy(
                voteEnabled = event.getOption("enabled")?.asBoolean ?: cur.voteEnabled,
                voteDurationMinutes = event.getOption("duration_minutes")?.asInt ?: cur.voteDurationMinutes,
                voteMinYesVotes = event.getOption("min_yes")?.asInt ?: cur.voteMinYesVotes,
                votePassRatio = event.getOption("pass_ratio")?.asDouble ?: cur.votePassRatio,
                voteCooldownMinutes = event.getOption("cooldown_minutes")?.asInt ?: cur.voteCooldownMinutes,
            )
        }
        replyEph(
            event,
            "投票BAN設定を更新しました",
            "- 投票BAN: **${if (updated.voteEnabled) "ON" else "OFF"}**\n" +
                "- 受付時間: **${updated.voteDurationMinutes}分** | 最低賛成: **${updated.voteMinYesVotes}票** | 賛成率: **${(updated.votePassRatio * 100).toInt()}%** | CD: **${updated.voteCooldownMinutes}分**",
            Color(0x2E, 0xCC, 0x71),
        )
    }
}

private class WatchConfigAiSub : SlashCommand() {
    init {
        name = "ai"
        help = "AI自動判定のON/OFF・感度・上限・チケット自動提案モードを設定します。"
        subcommandGroup = configGroup()
        options = listOf(
            OptionData(OptionType.BOOLEAN, "enabled", "AI自動判定のON/OFF", false),
            OptionData(OptionType.NUMBER, "sensitivity", "違反スコア倍率（0.2〜3.0、デフォルト1.0）", false).setRequiredRange(0.2, 3.0),
            OptionData(OptionType.NUMBER, "max_delta", "1評価あたりの最大加算上限（%）", false).setRequiredRange(1.0, 100.0),
            OptionData(OptionType.STRING, "ticket_mode", "AIによる処刑チケット提案モード", false)
                .addChoice("無効 (OFF)", AiAutoTicketMode.OFF.name)
                .addChoice("管理者承認制 (APPROVAL・推奨)", AiAutoTicketMode.APPROVAL.name)
                .addChoice("自動発行 (AUTO)", AiAutoTicketMode.AUTO.name),
        )
    }

    override fun execute(event: SlashCommandEvent) {
        val guild = event.guild
        if (!WatchPermissions.ensureFeatureEnabledOrReply(event, guild)) return
        guild!!
        if (!WatchPermissions.ensureAdminOrReply(event, guild, event.member)) return

        val ticketModeStr = event.optString("ticket_mode")
        val updated = watchConfigService.updateConfig(guild.id) { cur ->
            cur.copy(
                aiEnabled = event.getOption("enabled")?.asBoolean ?: cur.aiEnabled,
                aiSensitivity = event.getOption("sensitivity")?.asDouble ?: cur.aiSensitivity,
                aiMaxDeltaPerEval = event.getOption("max_delta")?.asDouble ?: cur.aiMaxDeltaPerEval,
                aiAutoTicketMode = if (ticketModeStr != null) AiAutoTicketMode.fromString(ticketModeStr) else cur.aiAutoTicketMode,
            )
        }
        replyEph(
            event,
            "AI判定設定を更新しました",
            "- AI自動判定: **${if (updated.aiEnabled) "ON" else "OFF"}**\n" +
                "- 感度倍率: **${updated.aiSensitivity}x** | 1回上限: **${updated.aiMaxDeltaPerEval}%**\n" +
                "- チケット提案モード: **`${updated.aiAutoTicketMode.name}`**",
            Color(0x2E, 0xCC, 0x71),
        )
    }
}

private class WatchScoreAdjustSub : SlashCommand() {
    init {
        name = "adjust"
        help = "監視対象メンバーの違反度を手動で増減します（管理者用）。"
        subcommandGroup = scoreGroup()
        options = listOf(
            OptionData(OptionType.USER, "user", "対象の監視メンバー", true),
            OptionData(OptionType.NUMBER, "delta", "増減値（例: +10 や -5）", true).setRequiredRange(-100.0, 100.0),
            OptionData(OptionType.STRING, "reason", "調整理由", true),
        )
    }

    override fun execute(event: SlashCommandEvent) {
        val guild = event.guild
        if (!WatchPermissions.ensureFeatureEnabledOrReply(event, guild)) return
        guild!!
        if (!WatchPermissions.ensureAdminOrReply(event, guild, event.member)) return

        val targetUser = event.getOption("user")?.asUser ?: return
        val delta = event.getOption("delta")?.asDouble ?: return
        val reason = event.optString("reason")?.trim()?.takeIf { it.isNotEmpty() } ?: "手動調整"

        if (delta < 0.0 && WatchPermissions.isSelfProtectionBlocked(event.user.id, targetUser.id)) {
            replyEph(event, "操作できません", "監視対象者本人は自分の違反度を減らすことはできません。", Color.RED)
            return
        }

        val target = watchConfigService.getTarget(guild.id, targetUser.id, activeOnly = true)
        if (target == null) {
            replyEph(event, "対象外", "<@${targetUser.id}> は現在の監視対象に登録されていません。", Color.RED)
            return
        }

        event.deferReply(true).queue { hook ->
            OhagiBot.scope.launch {
                val res = WatchOrchestrator.applyScoreChange(
                    guild = guild,
                    target = target,
                    eventType = WatchScoreEventType.MANUAL_ADJUST,
                    delta = delta,
                    category = "MANUAL",
                    reason = reason,
                    actorUserId = event.user.id,
                )
                if (res == null) {
                    hook.editOriginalComponents(
                        simpleTitleAndDescriptionContainer("更新できませんでした", "対象メンバーは現在アクティブな監視状態ではありません。", Color.RED)
                    ).useComponentsV2().queue()
                } else {
                    val (cycle, _) = res
                    val sign = if (delta >= 0) "+" else ""
                    hook.editOriginalComponents(
                        simpleTitleAndDescriptionContainer(
                            "違反度を更新しました",
                            String.format(
                                Locale.US,
                                "<@%s> の違反度を `%s%.1f%%` 調整しました（現在: **%.1f%%**）。\n理由: %s",
                                targetUser.id,
                                sign,
                                delta,
                                cycle.currentScore,
                                reason,
                            ),
                            Color(0x2E, 0xCC, 0x71),
                        )
                    ).setAllowedMentions(emptyList()).useComponentsV2().queue()
                }
            }
        }
    }
}

private class WatchAiRuleAddSub : SlashCommand() {
    init {
        name = "rule-add"
        help = "AI判定用のサーバー固有ルール（NG傾向／許容ルール）を追加します。"
        subcommandGroup = aiGroup()
        options = listOf(
            OptionData(OptionType.STRING, "type", "ルールの種類", true)
                .addChoice("違反ルール (NG傾向)", AiRuleType.VIOLATION.name)
                .addChoice("許容ルール (身内ネタ・セーフ)", AiRuleType.ALLOW.name),
            OptionData(OptionType.STRING, "description", "ルールの内容（自然言語で記述）", true),
            OptionData(OptionType.STRING, "weight", "違反時の重み目安", false)
                .addChoice("軽度 (LOW)", AiWeightHint.LOW.name)
                .addChoice("中度 (MEDIUM)", AiWeightHint.MEDIUM.name)
                .addChoice("重度 (HIGH)", AiWeightHint.HIGH.name),
            OptionData(OptionType.USER, "user", "特定の監視対象専用ルールにする場合に指定", false),
        )
    }

    override fun execute(event: SlashCommandEvent) {
        val guild = event.guild
        if (!WatchPermissions.ensureFeatureEnabledOrReply(event, guild)) return
        guild!!
        if (!WatchPermissions.ensureAdminOrReply(event, guild, event.member)) return

        val ruleType = AiRuleType.fromString(event.optString("type"))
        val description = event.optString("description")?.trim()?.takeIf { it.isNotEmpty() } ?: return
        val weight = AiWeightHint.fromString(event.optString("weight"))
        val user = event.getOption("user")?.asUser
        val target = user?.let { watchConfigService.getTarget(guild.id, it.id, activeOnly = false) }
        if (user != null && target == null) {
            replyEph(event, "対象外", "<@${user.id}> は監視対象として登録されていません。", Color.RED)
            return
        }
        if (ruleType == AiRuleType.ALLOW && user != null && WatchPermissions.isSelfProtectionBlocked(event.user.id, user.id)) {
            replyEph(event, "操作できません", "監視対象者本人は自分用の許容ルールを追加できません。", Color.RED)
            return
        }

        val rule = watchAiService.addRule(
            guildId = guild.id,
            targetId = target?.id,
            ruleType = ruleType,
            description = description,
            weightHint = weight,
            createdBy = event.user.id,
        )
        replyEph(
            event,
            "🤖 AI判定ルールを追加しました",
            "- **ルールID**: `#${rule.id}`\n- **種類**: ${rule.ruleType.displayName}（重み: ${rule.weightHint.displayName}）\n- **内容**: ${rule.description}",
            Color(0x2E, 0xCC, 0x71),
        )
    }
}

object WatchAiRuleUi {
    const val SELECT_EDIT_ID = "watch_rule_edit_select"
    const val MODAL_EDIT_PREFIX = "watch_rule_edit_modal:"

    fun buildEditModal(rule: WatchAiRule): Modal {
        val typeMenu = StringSelectMenu.create("rule_type")
            .addOption("違反ルール (NG傾向)", AiRuleType.VIOLATION.name)
            .addOption("許容ルール (身内ネタ・セーフ)", AiRuleType.ALLOW.name)
            .setDefaultValues(rule.ruleType.name)
            .build()

        val weightMenu = StringSelectMenu.create("weight_hint")
            .addOption("軽度 (LOW)", AiWeightHint.LOW.name)
            .addOption("中度 (MEDIUM)", AiWeightHint.MEDIUM.name)
            .addOption("重度 (HIGH)", AiWeightHint.HIGH.name)
            .setDefaultValues(rule.weightHint.name)
            .build()

        val descInput = TextInput.create("description", TextInputStyle.PARAGRAPH)
            .setValue(rule.description.take(1000))
            .setMinLength(1)
            .setMaxLength(1000)
            .setRequired(true)
            .build()

        return Modal.create("$MODAL_EDIT_PREFIX${rule.id}", "AIルール #${rule.id} を編集")
            .addComponents(
                Label.of("ルールの種類", typeMenu),
                Label.of("違反時の重み目安", weightMenu),
                Label.of("ルール内容", "AI判定の基準を自然言語で入力してください", descInput),
            )
            .build()
    }

    fun buildRuleListContainer(rules: List<WatchAiRule>, canEdit: Boolean): Container {
        val desc = if (rules.isEmpty()) {
            "サーバー固有のAI判定ルールはまだ登録されていません。"
        } else {
            buildString {
                rules.forEach { r ->
                    val scope = r.targetId?.let { tid ->
                        watchConfigService.getTargetById(tid)?.let { "<@${it.userId}>専用" } ?: "対象#$tid"
                    } ?: "サーバー共通"
                    val icon = if (r.ruleType == AiRuleType.VIOLATION) "🚫" else "✅"
                    append("- **#${r.id}** $icon [$scope / ${r.weightHint.displayName}] ${r.description}\n")
                }
                if (canEdit) {
                    append("\n-# 下のメニューからルールを選ぶと、そのまま編集フォームが開きます。")
                }
            }.trim()
        }

        val children = mutableListOf<net.dv8tion.jda.api.components.container.ContainerChildComponent>(
            TextDisplay.of("## 🤖 AI判定ルール一覧\n$desc")
        )

        if (canEdit && rules.isNotEmpty()) {
            val options = rules.take(25).map { r ->
                val icon = if (r.ruleType == AiRuleType.VIOLATION) "🚫" else "✅"
                val label = "#${r.id} $icon ${r.description.replace("\n", " ").take(85)}"
                val sub = "${r.ruleType.displayName} / 重み: ${r.weightHint.displayName}"
                SelectOption.of(label.take(100), r.id.toString()).withDescription(sub.take(100))
            }
            val select = StringSelectMenu.create(SELECT_EDIT_ID)
                .setPlaceholder("編集するルールを選択...")
                .addOptions(options)
                .build()
            children += Separator.createInvisible(Separator.Spacing.SMALL)
            children += ActionRow.of(select)
        }

        return Container.of(children).withAccentColor(Color(0x36, 0xB8, 0xFA))
    }
}

private class WatchAiRuleListSub : SlashCommand() {
    init {
        name = "rule-list"
        help = "登録されているAI判定ルール一覧を表示します（管理者はその場で編集可能）。"
        subcommandGroup = aiGroup()
    }

    override fun execute(event: SlashCommandEvent) {
        val guild = event.guild
        if (!WatchPermissions.ensureFeatureEnabledOrReply(event, guild)) return
        guild!!
        val rules = watchAiService.listRulesByGuild(guild.id)
        val canEdit = event.member != null && WatchPermissions.isGuildWatchAdmin(guild, event.member!!)
        val container = WatchAiRuleUi.buildRuleListContainer(rules, canEdit)
        event.replyComponents(container)
            .setAllowedMentions(emptyList())
            .useComponentsV2()
            .setEphemeral(true)
            .queue()
    }
}

private class WatchAiRuleEditSub : SlashCommand() {
    init {
        name = "rule-edit"
        help = "既存のAI判定ルールを編集します（項目を省略すると編集フォームが開きます）。"
        subcommandGroup = aiGroup()
        options = listOf(
            OptionData(OptionType.INTEGER, "rule_id", "編集するルールID（補完候補から選択可）", true)
                .setAutoComplete(true),
            OptionData(OptionType.STRING, "description", "新しいルール内容（省略可）", false),
            OptionData(OptionType.STRING, "type", "新しいルール種類（省略可）", false)
                .addChoice("違反ルール (NG傾向)", AiRuleType.VIOLATION.name)
                .addChoice("許容ルール (身内ネタ・セーフ)", AiRuleType.ALLOW.name),
            OptionData(OptionType.STRING, "weight", "新しい重み目安（省略可）", false)
                .addChoice("軽度 (LOW)", AiWeightHint.LOW.name)
                .addChoice("中度 (MEDIUM)", AiWeightHint.MEDIUM.name)
                .addChoice("重度 (HIGH)", AiWeightHint.HIGH.name),
        )
    }

    override fun execute(event: SlashCommandEvent) {
        val guild = event.guild
        if (!WatchPermissions.ensureFeatureEnabledOrReply(event, guild)) return
        guild!!
        if (!WatchPermissions.ensureAdminOrReply(event, guild, event.member)) return

        val ruleId = event.getOption("rule_id")?.asLong ?: return
        val existing = watchAiService.getRuleById(guild.id, ruleId)
        if (existing == null) {
            replyEph(event, "エラー", "AI判定ルール `#${ruleId}` が見つかりません。", Color.RED)
            return
        }

        val target = existing.targetId?.let { watchConfigService.getTargetById(it) }
        if (target != null && WatchPermissions.isSelfProtectionBlocked(event.user.id, target.userId)) {
            replyEph(event, "操作できません", "監視対象者本人は自分専用のルールを編集できません。", Color.RED)
            return
        }

        val newDesc = event.optString("description")?.trim()?.takeIf { it.isNotEmpty() }
        val newTypeStr = event.optString("type")
        val newWeightStr = event.optString("weight")

        // 変更項目がすべて省略された場合は現在の内容が入った編集モーダルを開く
        if (newDesc == null && newTypeStr == null && newWeightStr == null) {
            event.replyModal(WatchAiRuleUi.buildEditModal(existing)).queue()
            return
        }

        val newType = newTypeStr?.let { AiRuleType.fromString(it) }
        val newWeight = newWeightStr?.let { AiWeightHint.fromString(it) }
        val updated = watchAiService.updateRule(
            guildId = guild.id,
            ruleId = ruleId,
            ruleType = newType,
            description = newDesc,
            weightHint = newWeight,
        )
        if (updated == null) {
            replyEph(event, "エラー", "ルール `#${ruleId}` の更新に失敗しました。", Color.RED)
            return
        }

        replyEph(
            event,
            "✏️ AI判定ルールを更新しました",
            "- **ルールID**: `#${updated.id}`\n" +
                "- **種類**: ${updated.ruleType.displayName}（重み: ${updated.weightHint.displayName}）\n" +
                "- **内容**: ${updated.description}",
            Color(0x2E, 0xCC, 0x71),
        )
    }
}

private class WatchAiRuleRemoveSub : SlashCommand() {
    init {
        name = "rule-remove"
        help = "登録済みのAI判定ルールを削除します。"
        subcommandGroup = aiGroup()
        options = listOf(
            OptionData(OptionType.INTEGER, "rule_id", "削除するルールID（補完候補から選択可）", true)
                .setAutoComplete(true),
        )
    }

    override fun execute(event: SlashCommandEvent) {
        val guild = event.guild
        if (!WatchPermissions.ensureFeatureEnabledOrReply(event, guild)) return
        guild!!
        if (!WatchPermissions.ensureAdminOrReply(event, guild, event.member)) return

        val ruleId = event.getOption("rule_id")?.asLong ?: return
        val removed = watchAiService.removeRule(guild.id, ruleId)
        if (removed) {
            replyEph(event, "ルールを削除しました", "AI判定ルール `#${ruleId}` を削除しました。", Color.ORANGE)
        } else {
            replyEph(event, "エラー", "AI判定ルール `#${ruleId}` が見つかりません。", Color.RED)
        }
    }
}

private class WatchAiFeedbackSub : SlashCommand() {
    init {
        name = "feedback"
        help = "過去のAI判定に対するフィードバックを登録し、以降の判定精度をチューニングします。"
        subcommandGroup = aiGroup()
        options = listOf(
            OptionData(OptionType.STRING, "verdict", "判定の評価", true)
                .addChoice("過剰判定 / 誤爆 (FALSE_POSITIVE)", AiFeedbackVerdict.FALSE_POSITIVE.name)
                .addChoice("甘すぎた (UNDERESTIMATED)", AiFeedbackVerdict.UNDERESTIMATED.name)
                .addChoice("見逃し (MISSED)", AiFeedbackVerdict.MISSED.name)
                .addChoice("適正判定 (CORRECT)", AiFeedbackVerdict.CORRECT.name),
            OptionData(OptionType.NUMBER, "expected_delta", "望ましかったスコア増分（0〜25）", true).setRequiredRange(0.0, 25.0),
            OptionData(OptionType.INTEGER, "event_id", "違反ログのイベントID（#番号）", false),
            OptionData(OptionType.STRING, "message_text", "見逃し等の場合の発言テキスト（event_id未指定時に必須）", false),
            OptionData(OptionType.USER, "user", "対象メンバー（event_id未指定時に任意指定）", false),
            OptionData(OptionType.STRING, "note", "なぜその評価になるかの補足説明（AI学習に有効）", false),
            OptionData(OptionType.BOOLEAN, "apply_diff", "event_id指定時、元スコアとの差分を現在違反度にも補正反映するか", false),
        )
    }

    override fun execute(event: SlashCommandEvent) {
        val guild = event.guild
        if (!WatchPermissions.ensureFeatureEnabledOrReply(event, guild)) return
        guild!!
        if (!WatchPermissions.ensureAdminOrReply(event, guild, event.member)) return

        val verdict = AiFeedbackVerdict.fromString(event.optString("verdict"))
        val expectedDelta = event.getOption("expected_delta")?.asDouble ?: return
        val eventId = event.getOption("event_id")?.asLong
        val manualText = event.optString("message_text")?.trim()?.takeIf { it.isNotEmpty() }
        val optUser = event.getOption("user")?.asUser
        val note = event.optString("note")?.trim()?.takeIf { it.isNotEmpty() }
        val applyDiff = event.getOption("apply_diff")?.asBoolean ?: false

        val sourceEvent = eventId?.let { watchCycleService.getEventById(it) }
        if (eventId != null && (sourceEvent == null || sourceEvent.guildId != guild.id)) {
            replyEph(event, "エラー", "イベント `#${eventId}` が見つかりません。", Color.RED)
            return
        }

        val targetId = sourceEvent?.targetId ?: optUser?.let { watchConfigService.getTarget(guild.id, it.id, activeOnly = false)?.id }
        val target = targetId?.let { watchConfigService.getTargetById(it) }
        if (target != null && WatchPermissions.isSelfProtectionBlocked(event.user.id, target.userId)) {
            replyEph(event, "操作できません", "監視対象者本人は自分の判定に対してフィードバックを登録できません。", Color.RED)
            return
        }

        val messageContent = sourceEvent?.sourceMessageExcerpt ?: manualText
        if (messageContent.isNullOrBlank()) {
            replyEph(event, "入力エラー", "`event_id` または `message_text` のいずれかを指定してください。", Color.RED)
            return
        }
        val originalDelta = sourceEvent?.delta ?: 0.0

        val fb = watchAiService.addFeedback(
            guildId = guild.id,
            targetId = targetId,
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
                if (applyDiff && target != null && sourceEvent != null) {
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
                if (verdict == AiFeedbackVerdict.MISSED && sourceEvent == null && target != null) {
                    val reeval = WatchOrchestrator.reevaluateMissedFeedback(
                        guild = guild,
                        target = target,
                        feedback = fb,
                        rawInputText = messageContent,
                    )
                    reevalNote = "\n- 🔄 **見逃し再審査**: ${reeval.summaryJa}"
                }
                hook.editOriginalComponents(
                    simpleTitleAndDescriptionContainer(
                        "🧠 AIフィードバックを登録しました",
                        "フィードバック **`#${fb.id}`** を保存しました。以降のAI推論時に Few-shot 事例として自動参照されます。\n" +
                            "- **評価**: ${verdict.displayName}（元 `$originalDelta` → 望ましい値 `$expectedDelta`）" +
                            diffNote +
                            reevalNote,
                        Color(0x2E, 0xCC, 0x71),
                    )
                ).useComponentsV2().queue()
            }
        }
    }
}

private class WatchAiModelSub : SlashCommand() {
    init {
        name = "model"
        help = "AI機能ごと（通常監視判定／異議申し立て審査）のモデルと思考量を確認・設定します。"
        subcommandGroup = aiGroup()
        val taskOpt = OptionData(OptionType.STRING, "task", "設定するAI機能（省略時は現在の設定一覧を表示）", false)
        AiTaskType.entries.forEach { taskOpt.addChoice("${it.displayName} (${it.configKey})", it.name) }

        val thinkingOpt = OptionData(OptionType.STRING, "thinking", "思考量（DEFAULTで環境変数/既定値へ戻す）", false)
            .addChoice("デフォルトに戻す (DEFAULT)", "DEFAULT")
        GeminiClientSettings.ALLOWED_THINKING_LEVELS.forEach { level ->
            thinkingOpt.addChoice(level, level)
        }

        options = listOf(
            taskOpt,
            OptionData(OptionType.STRING, "model", "モデル名（defaultでapplication.yaml/環境変数の既定値へ戻す）", false)
                .setAutoComplete(true),
            thinkingOpt,
        )
    }

    override fun execute(event: SlashCommandEvent) {
        val guild = event.guild
        if (!WatchPermissions.ensureFeatureEnabledOrReply(event, guild)) return
        guild!!
        if (!WatchPermissions.ensureAdminOrReply(event, guild, event.member)) return

        val taskStr = event.optString("task")
        val rawModel = event.optString("model")?.trim()?.takeIf { it.isNotEmpty() }
        val rawThinking = event.optString("thinking")?.trim()?.takeIf { it.isNotEmpty() }

        if (taskStr == null || (rawModel == null && rawThinking == null)) {
            val taskConfigs = watchAiService.listTaskConfigsByGuild(guild.id)
            val desc = buildString {
                AiTaskType.entries.forEach { task ->
                    val dbCfg = taskConfigs[task]
                    val runtime = OhagiBot.aiModerationClient?.resolveTaskConfig(task, dbCfg?.modelName, dbCfg?.thinkingLevel)
                    val modelVal = runtime?.model ?: (dbCfg?.modelName ?: GeminiClientSettings.DEFAULT_MODEL)
                    val thinkVal = runtime?.thinkingLevel ?: (dbCfg?.thinkingLevel ?: task.defaultThinkingLevel)
                    val maxTok = runtime?.maxOutputTokens ?: task.defaultMaxOutputTokens
                    val modelSrc = if (dbCfg?.modelName != null) "サーバー設定" else "デフォルト/環境変数"
                    val thinkSrc = if (dbCfg?.thinkingLevel != null) "サーバー設定" else "デフォルト/環境変数"
                    append("### ${task.displayName} (`${task.configKey}`)\n")
                    append("- **モデル**: `$modelVal`（$modelSrc）\n")
                    append("- **思考量**: `$thinkVal`（$thinkSrc） | 最大出力トークン: `$maxTok`\n\n")
                }
                append("-# 設定を変更するには `/watch ai model task:... model:... thinking:...` を実行してください。")
            }.trim()
            replyEph(event, "🤖 AI 機能別モデル・思考量設定", desc, Color(0x36, 0xB8, 0xFA))
            return
        }

        val task = AiTaskType.fromString(taskStr)
        if (task == null) {
            replyEph(event, "入力エラー", "無効な機能名です。", Color.RED)
            return
        }

        val updateModel = rawModel != null
        val resolvedModel = when {
            rawModel == null -> null
            rawModel.equals("default", ignoreCase = true) || rawModel.equals("reset", ignoreCase = true) -> null
            else -> rawModel.take(128)
        }

        val updateThinking = rawThinking != null
        val resolvedThinking = when {
            rawThinking == null -> null
            rawThinking.equals("DEFAULT", ignoreCase = true) || rawThinking.equals("RESET", ignoreCase = true) -> null
            rawThinking.uppercase() in GeminiClientSettings.ALLOWED_THINKING_LEVELS -> rawThinking.uppercase()
            else -> {
                replyEph(
                    event,
                    "入力エラー",
                    "思考量には ${GeminiClientSettings.ALLOWED_THINKING_LEVELS.joinToString(", ")} または `DEFAULT` を指定してください。",
                    Color.RED,
                )
                return
            }
        }

        val saved = watchAiService.upsertTaskConfig(
            guildId = guild.id,
            taskType = task,
            modelName = resolvedModel,
            thinkingLevel = resolvedThinking,
            updateModel = updateModel,
            updateThinking = updateThinking,
        )
        val runtime = OhagiBot.aiModerationClient?.resolveTaskConfig(task, saved.modelName, saved.thinkingLevel)
        val effModel = runtime?.model ?: (saved.modelName ?: GeminiClientSettings.DEFAULT_MODEL)
        val effThink = runtime?.thinkingLevel ?: (saved.thinkingLevel ?: task.defaultThinkingLevel)

        replyEph(
            event,
            "🤖 ${task.displayName} の AI 設定を更新しました",
            "- **実効モデル**: `$effModel`" + (if (saved.modelName == null) "（デフォルト参照）" else "") + "\n" +
                "- **実効思考量**: `$effThink`" + (if (saved.thinkingLevel == null) "（デフォルト参照）" else ""),
            Color(0x2E, 0xCC, 0x71),
        )
    }
}

private class WatchUnbanSub : SlashCommand() {
    init {
        name = "unban"
        help = "BANされた監視対象メンバーのBANを解除し、1回限り招待リンクをDM送信します。"
        options = listOf(
            OptionData(OptionType.STRING, "user_id", "BAN解除するユーザーID（補完候補から選択可）", true)
                .setAutoComplete(true),
        )
    }

    override fun execute(event: SlashCommandEvent) {
        val guild = event.guild
        if (!WatchPermissions.ensureFeatureEnabledOrReply(event, guild)) return
        guild!!
        if (!WatchPermissions.ensureAdminOrReply(event, guild, event.member)) return

        val raw = event.optString("user_id")?.trim() ?: return
        val userId = raw.replace(Regex("[^0-9]"), "")
        if (userId.isEmpty()) {
            replyEph(event, "入力エラー", "有効なユーザーIDを指定してください。", Color.RED)
            return
        }

        event.deferReply(true).queue { hook ->
            OhagiBot.scope.launch {
                val res = RoleRestoreService.unbanAndInvite(guild, userId)
                hook.editOriginalComponents(
                    simpleTitleAndDescriptionContainer(
                        if (res.success) "🔓 BAN解除＆再招待完了" else "エラー",
                        res.message,
                        if (res.success) Color(0x2E, 0xCC, 0x71) else Color.RED,
                    )
                ).setAllowedMentions(emptyList()).useComponentsV2().queue()
            }
        }
    }
}

private class WatchCancelBanSub : SlashCommand() {
    init {
        name = "cancel-ban"
        help = "遺言待機中（PENDING_BAN）の処刑をキャンセルして通常監視へ戻します。"
        options = listOf(
            OptionData(OptionType.USER, "user", "処刑をキャンセルする監視対象メンバー", true),
        )
    }

    override fun execute(event: SlashCommandEvent) {
        val guild = event.guild
        if (!WatchPermissions.ensureFeatureEnabledOrReply(event, guild)) return
        guild!!
        if (!WatchPermissions.ensureAdminOrReply(event, guild, event.member)) return

        val user = event.getOption("user")?.asUser ?: return
        if (WatchPermissions.isSelfProtectionBlocked(event.user.id, user.id)) {
            replyEph(event, "操作できません", "監視対象者本人は自分の処刑をキャンセルできません。", Color.RED)
            return
        }
        val target = watchConfigService.getTarget(guild.id, user.id, activeOnly = true)
        if (target == null) {
            replyEph(event, "対象外", "<@${user.id}> は監視対象ではありません。", Color.RED)
            return
        }

        event.deferReply(true).queue { hook ->
            OhagiBot.scope.launch {
                val cancelled = WatchOrchestrator.cancelPendingBan(guild, target)
                val (title, desc, color) = if (cancelled) {
                    Triple("処刑をキャンセルしました", "<@${user.id}> の遺言待機BANを中止しました。", Color.ORANGE)
                } else {
                    Triple("キャンセルできません", "<@${user.id}> は現在遺言待機状態ではありません。", Color.RED)
                }
                hook.editOriginalComponents(simpleTitleAndDescriptionContainer(title, desc, color))
                    .setAllowedMentions(emptyList()).useComponentsV2().queue()
            }
        }
    }
}

private class WatchRefreshSub : SlashCommand() {
    init {
        name = "refresh"
        help = "専用チャンネルのステータスカードを削除し、チャンネル最下部へ最新状態で再配置します。"
        options = listOf(
            OptionData(OptionType.USER, "user", "特定の監視対象のみ再配置する場合に指定（省略時は全アクティブ対象）", false),
        )
    }

    override fun execute(event: SlashCommandEvent) {
        val guild = event.guild
        if (!WatchPermissions.ensureFeatureEnabledOrReply(event, guild)) return
        guild!!
        if (!WatchPermissions.ensureAdminOrReply(event, guild, event.member)) return

        val optUser = event.getOption("user")?.asUser
        val targets = if (optUser != null) {
            val t = watchConfigService.getTarget(guild.id, optUser.id, activeOnly = true)
            if (t == null) {
                replyEph(event, "対象外", "<@${optUser.id}> は現在の監視対象に登録されていません。", Color.RED)
                return
            }
            listOf(t)
        } else {
            watchConfigService.listTargets(guild.id, activeOnly = true)
        }

        if (targets.isEmpty()) {
            replyEph(event, "対象なし", "再配置できるアクティブな監視対象がいません。", Color.GRAY)
            return
        }

        event.deferReply(true).queue { hook ->
            OhagiBot.scope.launch {
                var refreshed = 0
                val skipped = mutableListOf<String>()
                for (t in targets) {
                    val ok = WatchOrchestrator.repositionTargetBoard(guild, t)
                    if (ok) refreshed++ else skipped += "<@${t.userId}>"
                }
                val desc = buildString {
                    append("**${refreshed}件** のステータスカードをチャンネル最下部へ再配置しました。")
                    if (skipped.isNotEmpty()) {
                        append("\n-# スキップ（BAN済み／チャンネル未設定等）: ${skipped.joinToString(", ")}")
                    }
                }
                hook.editOriginalComponents(
                    simpleTitleAndDescriptionContainer("🔄 ステータスカードを再配置しました", desc, Color(0x2E, 0xCC, 0x71))
                ).setAllowedMentions(emptyList()).useComponentsV2().queue()
            }
        }
    }
}

private class WatchRoleCounterSetSub : SlashCommand() {
    init {
        name = "set"
        help = "BAN再参加時にロール名の数値を自動カウントアップする設定を登録します（例: sasalive 9期生 → 10期生）。"
        subcommandGroup = roleCounterGroup()
        options = listOf(
            OptionData(OptionType.USER, "user", "対象の監視メンバー", true),
            OptionData(OptionType.ROLE, "role", "名称を自動更新するロール", true),
            OptionData(OptionType.STRING, "template", "ロール名テンプレート（{n}が数値に置換。省略時は現在のロール名から自動推定）", false),
            OptionData(OptionType.INTEGER, "current_number", "現在の数値（省略時は現在のロール名から自動推定）", false),
            OptionData(OptionType.INTEGER, "step", "1回のBAN再参加ごとの増加量（デフォルト: 1）", false)
                .setRequiredRange(1, 100),
        )
    }

    override fun execute(event: SlashCommandEvent) {
        val guild = event.guild
        if (!WatchPermissions.ensureFeatureEnabledOrReply(event, guild)) return
        guild!!
        if (!WatchPermissions.ensureAdminOrReply(event, guild, event.member)) return

        val targetUser = event.getOption("user")?.asUser ?: return
        val role = event.getOption("role")?.asRole ?: return
        val target = watchConfigService.getTarget(guild.id, targetUser.id, activeOnly = false)
        if (target == null) {
            replyEph(event, "対象外", "<@${targetUser.id}> は監視対象として登録されていません。先に `/watch target add` を実行してください。", Color.RED)
            return
        }

        val inferred = WatchRoleCounter.inferFromRoleName(role.name)
        val template = event.optString("template")?.trim()?.takeIf { it.isNotEmpty() } ?: inferred?.first
        val currentNum = event.getOption("current_number")?.asInt ?: inferred?.second
        val step = event.getOption("step")?.asInt ?: 1

        if (template == null || currentNum == null) {
            replyEph(
                event,
                "自動推定できませんでした",
                "ロール名 `${role.name}` に数値が含まれていないため自動推定できませんでした。\n" +
                    "`template`（例: `sasalive {n}期生`）と `current_number`（例: `9`）を指定して実行してください。",
                Color.RED,
            )
            return
        }
        if (!template.contains(WatchRoleCounter.PLACEHOLDER)) {
            replyEph(
                event,
                "入力エラー",
                "`template` には数値の埋め込み箇所として `${WatchRoleCounter.PLACEHOLDER}` を含めてください（例: `sasalive {n}期生`）。",
                Color.RED,
            )
            return
        }

        val saved = watchConfigService.upsertRoleCounter(
            guildId = guild.id,
            targetId = target.id,
            roleId = role.id,
            nameTemplate = template,
            currentNumber = currentNum,
            step = step,
        )

        val warnings = mutableListOf<String>()
        if (!guild.selfMember.hasPermission(net.dv8tion.jda.api.Permission.MANAGE_ROLES)) {
            warnings += "Botに `ロールの管理 (MANAGE_ROLES)` 権限がありません。"
        }
        if (!guild.selfMember.canInteract(role)) {
            warnings += "対象ロール（${role.asMention}）がBotのロールより上位にあるため名前を変更できません。Botのロール順位を上げてください。"
        }

        val desc = buildString {
            append("<@${targetUser.id}> がBANされ再参加するたびに ${role.asMention} のロール名を自動更新します。\n\n")
            append("- **テンプレート**: `${saved.nameTemplate}`\n")
            append("- **現在値**: `${saved.currentNumber}`（現在名イメージ: `${saved.renderName(saved.currentNumber)}`）\n")
            append("- **次回再参加時**: **`${saved.renderName(saved.nextNumber())}`**（+${saved.step}）")
            if (warnings.isNotEmpty()) {
                append("\n\n⚠️ **注意**:\n${warnings.joinToString("\n")}")
            }
        }
        replyEph(event, "🏷️ ロール名自動更新を設定しました", desc, Color(0x2E, 0xCC, 0x71))
    }
}

private class WatchRoleCounterListSub : SlashCommand() {
    init {
        name = "list"
        help = "登録されているBAN再参加時ロール名自動更新の一覧を表示します。"
        subcommandGroup = roleCounterGroup()
        options = listOf(
            OptionData(OptionType.USER, "user", "特定の監視対象で絞り込む場合に指定", false),
        )
    }

    override fun execute(event: SlashCommandEvent) {
        val guild = event.guild
        if (!WatchPermissions.ensureFeatureEnabledOrReply(event, guild)) return
        guild!!
        val optUser = event.getOption("user")?.asUser
        val counters = if (optUser != null) {
            val target = watchConfigService.getTarget(guild.id, optUser.id, activeOnly = false)
            if (target == null) emptyList() else watchConfigService.listRoleCountersByTarget(target.id)
        } else {
            watchConfigService.listRoleCountersByGuild(guild.id)
        }

        val desc = if (counters.isEmpty()) {
            "登録されているロール名自動更新設定はありません。"
        } else {
            buildString {
                counters.forEach { c ->
                    val target = watchConfigService.getTargetById(c.targetId)
                    append(
                        "- 対象: <@${target?.userId ?: "不明"}> | ロール: <@&${c.roleId}>\n" +
                            "  テンプレート: `${c.nameTemplate}` | 現在: `${c.currentNumber}` → 次回: **`${c.renderName(c.nextNumber())}`**\n"
                    )
                }
            }.trim()
        }
        replyEph(event, "🏷️ ロール名自動更新一覧", desc, Color(0x36, 0xB8, 0xFA))
    }
}

private class WatchRoleCounterRemoveSub : SlashCommand() {
    init {
        name = "remove"
        help = "BAN再参加時のロール名自動更新設定を解除します。"
        subcommandGroup = roleCounterGroup()
        options = listOf(
            OptionData(OptionType.USER, "user", "対象の監視メンバー", true),
            OptionData(OptionType.ROLE, "role", "解除するロール", true),
        )
    }

    override fun execute(event: SlashCommandEvent) {
        val guild = event.guild
        if (!WatchPermissions.ensureFeatureEnabledOrReply(event, guild)) return
        guild!!
        if (!WatchPermissions.ensureAdminOrReply(event, guild, event.member)) return

        val targetUser = event.getOption("user")?.asUser ?: return
        val role = event.getOption("role")?.asRole ?: return
        val target = watchConfigService.getTarget(guild.id, targetUser.id, activeOnly = false)
        if (target == null) {
            replyEph(event, "エラー", "<@${targetUser.id}> の監視データが見つかりません。", Color.RED)
            return
        }
        val removed = watchConfigService.removeRoleCounter(target.id, role.id)
        if (removed) {
            replyEph(event, "設定を解除しました", "<@${targetUser.id}> の ${role.asMention} ロール名自動更新を解除しました。", Color.ORANGE)
        } else {
            replyEph(event, "見つかりません", "該当するロール名自動更新設定は登録されていません。", Color.RED)
        }
    }
}

private fun replyEph(event: SlashCommandEvent, title: String, description: String, color: Color) {
    event.replyComponents(simpleTitleAndDescriptionContainer(title, description, color))
        .setAllowedMentions(emptyList())
        .useComponentsV2()
        .setEphemeral(true)
        .queue()
}
