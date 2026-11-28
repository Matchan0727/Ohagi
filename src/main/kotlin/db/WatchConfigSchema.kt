package jp.simplespace.db

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update

enum class AiAutoTicketMode {
    OFF,
    APPROVAL,
    AUTO;

    companion object {
        fun fromString(value: String?): AiAutoTicketMode =
            entries.firstOrNull { it.name.equals(value, ignoreCase = true) } ?: APPROVAL
    }
}

@Serializable
data class WatchGuildConfig(
    val guildId: String,
    val defaultChannelId: String? = null,
    val approvalChannelId: String? = null,
    val managerRoleId: String? = null,
    val banGraceSeconds: Int = 60,
    val aiEnabled: Boolean = true,
    val aiSensitivity: Double = 1.0,
    val aiMaxDeltaPerEval: Double = 25.0,
    val aiAutoTicketMode: AiAutoTicketMode = AiAutoTicketMode.APPROVAL,
    val excludedChannelIds: List<String> = emptyList(),
    val decayEnabled: Boolean = true,
    val decayGraceHours: Int = 24,
    val decayIntervalHours: Int = 6,
    val decayAmount: Double = 2.0,
    val decayCalmBonusMultiplier: Double = 1.5,
    val voteEnabled: Boolean = true,
    val voteDurationMinutes: Int = 15,
    val voteMinYesVotes: Int = 3,
    val votePassRatio: Double = 0.6,
    val voteCooldownMinutes: Int = 360,
    val voteUseLastWords: Boolean = true,
    val ticketUseLastWords: Boolean = true,
    val updatedAt: Long = System.currentTimeMillis(),
)

@Serializable
data class WatchTarget(
    val id: Long,
    val guildId: String,
    val userId: String,
    val dedicatedChannelId: String?,
    val personaNote: String?,
    val active: Boolean,
    val currentCycleId: Long?,
    val createdBy: String,
    val createdAt: Long,
)

@Serializable
data class WatchTargetReportConfig(
    val targetId: Long,
    val guildId: String,
    val preset1: Double = DEFAULT_PRESET_1,
    val preset2: Double = DEFAULT_PRESET_2,
    val preset3: Double = DEFAULT_PRESET_3,
    val allowNegativeDelta: Boolean = DEFAULT_ALLOW_NEGATIVE,
    val updatedAt: Long = System.currentTimeMillis(),
) {
    fun presets(): List<Double> = listOf(preset1, preset2, preset3)

    companion object {
        const val DEFAULT_PRESET_1 = 5.0
        const val DEFAULT_PRESET_2 = 10.0
        const val DEFAULT_PRESET_3 = 15.0
        const val DEFAULT_ALLOW_NEGATIVE = true
    }
}

@Serializable
data class WatchRoleCounter(
    val id: Long,
    val guildId: String,
    val targetId: Long,
    val roleId: String,
    val nameTemplate: String,
    val currentNumber: Int,
    val step: Int,
    val updatedAt: Long,
) {
    fun nextNumber(): Int = currentNumber + step

    fun renderName(number: Int = currentNumber): String =
        nameTemplate.replace(PLACEHOLDER, number.toString()).take(100)

    companion object {
        const val PLACEHOLDER = "{n}"
        private val LAST_NUMBER_REGEX = Regex("""(\d+)(?!.*\d)""")

        /**
         * 既存ロール名（例: "sasalive 9期生"）の末尾の数値からテンプレートと現在値を自動推定する。
         */
        fun inferFromRoleName(roleName: String): Pair<String, Int>? {
            val match = LAST_NUMBER_REGEX.find(roleName) ?: return null
            val num = match.value.toIntOrNull() ?: return null
            val template = roleName.substring(0, match.range.first) + PLACEHOLDER + roleName.substring(match.range.last + 1)
            return template to num
        }
    }
}

class WatchConfigService(private val database: Database) {
    private val json = Json { ignoreUnknownKeys = true }

    object WatchGuildConfigs : Table("watch_guild_configs") {
        val guildId = varchar("guild_id", 64)
        val defaultChannelId = varchar("default_channel_id", 64).nullable()
        val approvalChannelId = varchar("approval_channel_id", 64).nullable()
        val managerRoleId = varchar("manager_role_id", 64).nullable()
        val banGraceSeconds = integer("ban_grace_seconds").default(60)
        val aiEnabled = bool("ai_enabled").default(true)
        val aiSensitivity = double("ai_sensitivity").default(1.0)
        val aiMaxDeltaPerEval = double("ai_max_delta_per_eval").default(25.0)
        val aiAutoTicketMode = varchar("ai_auto_ticket_mode", 16).default(AiAutoTicketMode.APPROVAL.name)
        val excludedChannelIdsJson = text("excluded_channel_ids_json").default("[]")
        val decayEnabled = bool("decay_enabled").default(true)
        val decayGraceHours = integer("decay_grace_hours").default(24)
        val decayIntervalHours = integer("decay_interval_hours").default(6)
        val decayAmount = double("decay_amount").default(2.0)
        val decayCalmBonusMultiplier = double("decay_calm_bonus_multiplier").default(1.5)
        val voteEnabled = bool("vote_enabled").default(true)
        val voteDurationMinutes = integer("vote_duration_minutes").default(15)
        val voteMinYesVotes = integer("vote_min_yes_votes").default(3)
        val votePassRatio = double("vote_pass_ratio").default(0.6)
        val voteCooldownMinutes = integer("vote_cooldown_minutes").default(360)
        val voteUseLastWords = bool("vote_use_last_words").default(true)
        val ticketUseLastWords = bool("ticket_use_last_words").default(true)
        val updatedAt = long("updated_at")

        override val primaryKey = PrimaryKey(guildId)
    }

    object WatchTargets : Table("watch_targets") {
        val id = long("id").autoIncrement()
        val guildId = varchar("guild_id", 64)
        val userId = varchar("user_id", 64)
        val dedicatedChannelId = varchar("dedicated_channel_id", 64).nullable()
        val personaNote = text("persona_note").nullable()
        val active = bool("active").default(true)
        val currentCycleId = long("current_cycle_id").nullable()
        val createdBy = varchar("created_by", 64)
        val createdAt = long("created_at")

        override val primaryKey = PrimaryKey(id)

        init {
            uniqueIndex("uq_watch_targets_guild_user", guildId, userId)
        }
    }

    object WatchTargetRoleCounters : Table("watch_target_role_counters") {
        val id = long("id").autoIncrement()
        val guildId = varchar("guild_id", 64).index("idx_watch_role_counters_guild")
        val targetId = long("target_id").index("idx_watch_role_counters_target")
        val roleId = varchar("role_id", 64)
        val nameTemplate = varchar("name_template", 120)
        val currentNumber = integer("current_number")
        val step = integer("step").default(1)
        val updatedAt = long("updated_at")

        override val primaryKey = PrimaryKey(id)

        init {
            uniqueIndex("uq_watch_role_counter_target_role", targetId, roleId)
        }
    }

    object WatchTargetReportConfigs : Table("watch_target_report_configs") {
        val targetId = long("target_id")
        val guildId = varchar("guild_id", 64).index("idx_watch_target_report_cfg_guild")
        val preset1 = double("preset_1").default(WatchTargetReportConfig.DEFAULT_PRESET_1)
        val preset2 = double("preset_2").default(WatchTargetReportConfig.DEFAULT_PRESET_2)
        val preset3 = double("preset_3").default(WatchTargetReportConfig.DEFAULT_PRESET_3)
        val allowNegativeDelta = bool("allow_negative_delta").default(WatchTargetReportConfig.DEFAULT_ALLOW_NEGATIVE)
        val updatedAt = long("updated_at")

        override val primaryKey = PrimaryKey(targetId)
    }

    init {
        transaction(database) {
            SchemaUtils.create(WatchGuildConfigs, WatchTargets, WatchTargetRoleCounters, WatchTargetReportConfigs)
        }
    }

    fun getOrCreateConfig(guildId: String): WatchGuildConfig = transaction(database) {
        WatchGuildConfigs.selectAll()
            .where { WatchGuildConfigs.guildId eq guildId }
            .singleOrNull()
            ?.toConfig()
            ?: run {
                val now = System.currentTimeMillis()
                WatchGuildConfigs.insert {
                    it[WatchGuildConfigs.guildId] = guildId
                    it[WatchGuildConfigs.updatedAt] = now
                }
                WatchGuildConfig(guildId = guildId, updatedAt = now)
            }
    }

    fun updateConfig(guildId: String, transform: (WatchGuildConfig) -> WatchGuildConfig): WatchGuildConfig {
        val current = getOrCreateConfig(guildId)
        val updated = transform(current).copy(guildId = guildId, updatedAt = System.currentTimeMillis())
        val excludedJson = json.encodeToString(updated.excludedChannelIds.distinct())
        transaction(database) {
            WatchGuildConfigs.update({ WatchGuildConfigs.guildId eq guildId }) {
                it[defaultChannelId] = updated.defaultChannelId
                it[approvalChannelId] = updated.approvalChannelId
                it[managerRoleId] = updated.managerRoleId
                it[banGraceSeconds] = updated.banGraceSeconds
                it[aiEnabled] = updated.aiEnabled
                it[aiSensitivity] = updated.aiSensitivity
                it[aiMaxDeltaPerEval] = updated.aiMaxDeltaPerEval
                it[aiAutoTicketMode] = updated.aiAutoTicketMode.name
                it[excludedChannelIdsJson] = excludedJson
                it[decayEnabled] = updated.decayEnabled
                it[decayGraceHours] = updated.decayGraceHours
                it[decayIntervalHours] = updated.decayIntervalHours
                it[decayAmount] = updated.decayAmount
                it[decayCalmBonusMultiplier] = updated.decayCalmBonusMultiplier
                it[voteEnabled] = updated.voteEnabled
                it[voteDurationMinutes] = updated.voteDurationMinutes
                it[voteMinYesVotes] = updated.voteMinYesVotes
                it[votePassRatio] = updated.votePassRatio
                it[voteCooldownMinutes] = updated.voteCooldownMinutes
                it[voteUseLastWords] = updated.voteUseLastWords
                it[ticketUseLastWords] = updated.ticketUseLastWords
                it[updatedAt] = updated.updatedAt
            }
        }
        return updated
    }

    fun upsertTarget(
        guildId: String,
        userId: String,
        dedicatedChannelId: String?,
        personaNote: String?,
        createdBy: String,
    ): WatchTarget = transaction(database) {
        val existing = WatchTargets.selectAll()
            .where { (WatchTargets.guildId eq guildId) and (WatchTargets.userId eq userId) }
            .singleOrNull()
            ?.toTarget()

        if (existing != null) {
            WatchTargets.update({ WatchTargets.id eq existing.id }) {
                it[WatchTargets.dedicatedChannelId] = dedicatedChannelId ?: existing.dedicatedChannelId
                it[WatchTargets.personaNote] = personaNote ?: existing.personaNote
                it[WatchTargets.active] = true
            }
            existing.copy(
                dedicatedChannelId = dedicatedChannelId ?: existing.dedicatedChannelId,
                personaNote = personaNote ?: existing.personaNote,
                active = true,
            )
        } else {
            val now = System.currentTimeMillis()
            val id = WatchTargets.insert {
                it[WatchTargets.guildId] = guildId
                it[WatchTargets.userId] = userId
                it[WatchTargets.dedicatedChannelId] = dedicatedChannelId
                it[WatchTargets.personaNote] = personaNote
                it[WatchTargets.active] = true
                it[WatchTargets.currentCycleId] = null
                it[WatchTargets.createdBy] = createdBy
                it[WatchTargets.createdAt] = now
            }[WatchTargets.id]
            WatchTarget(
                id = id,
                guildId = guildId,
                userId = userId,
                dedicatedChannelId = dedicatedChannelId,
                personaNote = personaNote,
                active = true,
                currentCycleId = null,
                createdBy = createdBy,
                createdAt = now,
            )
        }
    }

    fun getTargetById(targetId: Long): WatchTarget? = transaction(database) {
        WatchTargets.selectAll()
            .where { WatchTargets.id eq targetId }
            .singleOrNull()
            ?.toTarget()
    }

    fun getTarget(guildId: String, userId: String, activeOnly: Boolean = true): WatchTarget? = transaction(database) {
        WatchTargets.selectAll()
            .where {
                val base = (WatchTargets.guildId eq guildId) and (WatchTargets.userId eq userId)
                if (activeOnly) base and (WatchTargets.active eq true) else base
            }
            .singleOrNull()
            ?.toTarget()
    }

    fun listTargets(guildId: String, activeOnly: Boolean = true): List<WatchTarget> = transaction(database) {
        WatchTargets.selectAll()
            .where {
                if (activeOnly) (WatchTargets.guildId eq guildId) and (WatchTargets.active eq true)
                else WatchTargets.guildId eq guildId
            }
            .map { it.toTarget() }
    }

    fun updateTargetCurrentCycle(targetId: Long, cycleId: Long?) {
        transaction(database) {
            WatchTargets.update({ WatchTargets.id eq targetId }) {
                it[WatchTargets.currentCycleId] = cycleId
            }
        }
    }

    fun updateTargetChannelAndNote(targetId: Long, dedicatedChannelId: String?, personaNote: String?, updateChannel: Boolean, updateNote: Boolean) {
        transaction(database) {
            WatchTargets.update({ WatchTargets.id eq targetId }) {
                if (updateChannel) it[WatchTargets.dedicatedChannelId] = dedicatedChannelId
                if (updateNote) it[WatchTargets.personaNote] = personaNote
            }
        }
    }

    fun deactivateTarget(targetId: Long) {
        transaction(database) {
            WatchTargets.update({ WatchTargets.id eq targetId }) {
                it[WatchTargets.active] = false
            }
        }
    }

    fun resolveStatusChannelId(target: WatchTarget, config: WatchGuildConfig): String? =
        target.dedicatedChannelId ?: config.defaultChannelId

    fun isDedicatedChannel(guildId: String, channelId: String): Boolean {
        val config = getOrCreateConfig(guildId)
        if (config.defaultChannelId == channelId || config.approvalChannelId == channelId) return true
        return listTargets(guildId, activeOnly = true).any { it.dedicatedChannelId == channelId }
    }

    fun upsertRoleCounter(
        guildId: String,
        targetId: Long,
        roleId: String,
        nameTemplate: String,
        currentNumber: Int,
        step: Int = 1,
    ): WatchRoleCounter = transaction(database) {
        val now = System.currentTimeMillis()
        val existing = WatchTargetRoleCounters.selectAll()
            .where { (WatchTargetRoleCounters.targetId eq targetId) and (WatchTargetRoleCounters.roleId eq roleId) }
            .singleOrNull()
            ?.toRoleCounter()

        if (existing != null) {
            WatchTargetRoleCounters.update({ WatchTargetRoleCounters.id eq existing.id }) {
                it[WatchTargetRoleCounters.nameTemplate] = nameTemplate.take(120)
                it[WatchTargetRoleCounters.currentNumber] = currentNumber
                it[WatchTargetRoleCounters.step] = step
                it[WatchTargetRoleCounters.updatedAt] = now
            }
            existing.copy(
                nameTemplate = nameTemplate.take(120),
                currentNumber = currentNumber,
                step = step,
                updatedAt = now,
            )
        } else {
            val id = WatchTargetRoleCounters.insert {
                it[WatchTargetRoleCounters.guildId] = guildId
                it[WatchTargetRoleCounters.targetId] = targetId
                it[WatchTargetRoleCounters.roleId] = roleId
                it[WatchTargetRoleCounters.nameTemplate] = nameTemplate.take(120)
                it[WatchTargetRoleCounters.currentNumber] = currentNumber
                it[WatchTargetRoleCounters.step] = step
                it[WatchTargetRoleCounters.updatedAt] = now
            }[WatchTargetRoleCounters.id]
            WatchRoleCounter(id, guildId, targetId, roleId, nameTemplate.take(120), currentNumber, step, now)
        }
    }

    fun listRoleCountersByTarget(targetId: Long): List<WatchRoleCounter> = transaction(database) {
        WatchTargetRoleCounters.selectAll()
            .where { WatchTargetRoleCounters.targetId eq targetId }
            .map { it.toRoleCounter() }
    }

    fun listRoleCountersByGuild(guildId: String): List<WatchRoleCounter> = transaction(database) {
        WatchTargetRoleCounters.selectAll()
            .where { WatchTargetRoleCounters.guildId eq guildId }
            .map { it.toRoleCounter() }
    }

    fun updateRoleCounterNumber(counterId: Long, newNumber: Int) {
        transaction(database) {
            WatchTargetRoleCounters.update({ WatchTargetRoleCounters.id eq counterId }) {
                it[WatchTargetRoleCounters.currentNumber] = newNumber
                it[WatchTargetRoleCounters.updatedAt] = System.currentTimeMillis()
            }
        }
    }

    fun removeRoleCounter(targetId: Long, roleId: String): Boolean = transaction(database) {
        WatchTargetRoleCounters.deleteWhere {
            (WatchTargetRoleCounters.targetId eq targetId) and (WatchTargetRoleCounters.roleId eq roleId)
        } > 0
    }

    private fun ResultRow.toRoleCounter(): WatchRoleCounter = WatchRoleCounter(
        id = this[WatchTargetRoleCounters.id],
        guildId = this[WatchTargetRoleCounters.guildId],
        targetId = this[WatchTargetRoleCounters.targetId],
        roleId = this[WatchTargetRoleCounters.roleId],
        nameTemplate = this[WatchTargetRoleCounters.nameTemplate],
        currentNumber = this[WatchTargetRoleCounters.currentNumber],
        step = this[WatchTargetRoleCounters.step],
        updatedAt = this[WatchTargetRoleCounters.updatedAt],
    )

    private fun ResultRow.toConfig(): WatchGuildConfig {
        val excluded = runCatching {
            json.decodeFromString<List<String>>(this[WatchGuildConfigs.excludedChannelIdsJson])
        }.getOrDefault(emptyList())
        return WatchGuildConfig(
            guildId = this[WatchGuildConfigs.guildId],
            defaultChannelId = this[WatchGuildConfigs.defaultChannelId],
            approvalChannelId = this[WatchGuildConfigs.approvalChannelId],
            managerRoleId = this[WatchGuildConfigs.managerRoleId],
            banGraceSeconds = this[WatchGuildConfigs.banGraceSeconds],
            aiEnabled = this[WatchGuildConfigs.aiEnabled],
            aiSensitivity = this[WatchGuildConfigs.aiSensitivity],
            aiMaxDeltaPerEval = this[WatchGuildConfigs.aiMaxDeltaPerEval],
            aiAutoTicketMode = AiAutoTicketMode.fromString(this[WatchGuildConfigs.aiAutoTicketMode]),
            excludedChannelIds = excluded,
            decayEnabled = this[WatchGuildConfigs.decayEnabled],
            decayGraceHours = this[WatchGuildConfigs.decayGraceHours],
            decayIntervalHours = this[WatchGuildConfigs.decayIntervalHours],
            decayAmount = this[WatchGuildConfigs.decayAmount],
            decayCalmBonusMultiplier = this[WatchGuildConfigs.decayCalmBonusMultiplier],
            voteEnabled = this[WatchGuildConfigs.voteEnabled],
            voteDurationMinutes = this[WatchGuildConfigs.voteDurationMinutes],
            voteMinYesVotes = this[WatchGuildConfigs.voteMinYesVotes],
            votePassRatio = this[WatchGuildConfigs.votePassRatio],
            voteCooldownMinutes = this[WatchGuildConfigs.voteCooldownMinutes],
            voteUseLastWords = this[WatchGuildConfigs.voteUseLastWords],
            ticketUseLastWords = this[WatchGuildConfigs.ticketUseLastWords],
            updatedAt = this[WatchGuildConfigs.updatedAt],
        )
    }

    fun getTargetReportConfig(guildId: String, targetId: Long): WatchTargetReportConfig = transaction(database) {
        WatchTargetReportConfigs.selectAll()
            .where { WatchTargetReportConfigs.targetId eq targetId }
            .singleOrNull()
            ?.toTargetReportConfig()
            ?: WatchTargetReportConfig(targetId = targetId, guildId = guildId)
    }

    fun upsertTargetReportConfig(
        guildId: String,
        targetId: Long,
        preset1: Double? = null,
        preset2: Double? = null,
        preset3: Double? = null,
        allowNegativeDelta: Boolean? = null,
    ): WatchTargetReportConfig = transaction(database) {
        val now = System.currentTimeMillis()
        val current = WatchTargetReportConfigs.selectAll()
            .where { WatchTargetReportConfigs.targetId eq targetId }
            .singleOrNull()
            ?.toTargetReportConfig()
        val nextP1 = preset1 ?: current?.preset1 ?: WatchTargetReportConfig.DEFAULT_PRESET_1
        val nextP2 = preset2 ?: current?.preset2 ?: WatchTargetReportConfig.DEFAULT_PRESET_2
        val nextP3 = preset3 ?: current?.preset3 ?: WatchTargetReportConfig.DEFAULT_PRESET_3
        val nextAllowNeg = allowNegativeDelta ?: current?.allowNegativeDelta ?: WatchTargetReportConfig.DEFAULT_ALLOW_NEGATIVE

        if (current != null) {
            WatchTargetReportConfigs.update({ WatchTargetReportConfigs.targetId eq targetId }) {
                it[WatchTargetReportConfigs.preset1] = nextP1
                it[WatchTargetReportConfigs.preset2] = nextP2
                it[WatchTargetReportConfigs.preset3] = nextP3
                it[WatchTargetReportConfigs.allowNegativeDelta] = nextAllowNeg
                it[WatchTargetReportConfigs.updatedAt] = now
            }
        } else {
            WatchTargetReportConfigs.insert {
                it[WatchTargetReportConfigs.targetId] = targetId
                it[WatchTargetReportConfigs.guildId] = guildId
                it[WatchTargetReportConfigs.preset1] = nextP1
                it[WatchTargetReportConfigs.preset2] = nextP2
                it[WatchTargetReportConfigs.preset3] = nextP3
                it[WatchTargetReportConfigs.allowNegativeDelta] = nextAllowNeg
                it[WatchTargetReportConfigs.updatedAt] = now
            }
        }
        WatchTargetReportConfig(
            targetId = targetId,
            guildId = guildId,
            preset1 = nextP1,
            preset2 = nextP2,
            preset3 = nextP3,
            allowNegativeDelta = nextAllowNeg,
            updatedAt = now,
        )
    }

    fun resetTargetReportConfig(targetId: Long) {
        transaction(database) {
            WatchTargetReportConfigs.deleteWhere { WatchTargetReportConfigs.targetId eq targetId }
        }
    }

    private fun ResultRow.toTargetReportConfig(): WatchTargetReportConfig = WatchTargetReportConfig(
        targetId = this[WatchTargetReportConfigs.targetId],
        guildId = this[WatchTargetReportConfigs.guildId],
        preset1 = this[WatchTargetReportConfigs.preset1],
        preset2 = this[WatchTargetReportConfigs.preset2],
        preset3 = this[WatchTargetReportConfigs.preset3],
        allowNegativeDelta = this[WatchTargetReportConfigs.allowNegativeDelta],
        updatedAt = this[WatchTargetReportConfigs.updatedAt],
    )

    private fun ResultRow.toTarget(): WatchTarget = WatchTarget(
        id = this[WatchTargets.id],
        guildId = this[WatchTargets.guildId],
        userId = this[WatchTargets.userId],
        dedicatedChannelId = this[WatchTargets.dedicatedChannelId],
        personaNote = this[WatchTargets.personaNote],
        active = this[WatchTargets.active],
        currentCycleId = this[WatchTargets.currentCycleId],
        createdBy = this[WatchTargets.createdBy],
        createdAt = this[WatchTargets.createdAt],
    )
}
