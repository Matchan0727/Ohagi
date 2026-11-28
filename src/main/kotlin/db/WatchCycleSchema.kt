package jp.simplespace.db

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greater
import org.jetbrains.exposed.v1.core.greaterEq
import org.jetbrains.exposed.v1.core.isNotNull
import org.jetbrains.exposed.v1.core.less
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update

enum class WatchCycleStatus {
    ACTIVE,
    PENDING_BAN,
    BANNED,
    ARCHIVED;

    companion object {
        fun fromString(v: String): WatchCycleStatus =
            entries.firstOrNull { it.name == v } ?: ACTIVE
    }
}

enum class BanTriggerType(val displayName: String) {
    SCORE_100("違反度100%到達"),
    VOTE("メンバー投票可決"),
    TICKET("処刑チケット行使");

    companion object {
        fun fromString(v: String?): BanTriggerType? =
            entries.firstOrNull { it.name == v }
    }
}

enum class WatchScoreEventType(val displayName: String) {
    CYCLE_START("サイクル開始"),
    AI_VIOLATION("AI違反検知"),
    DECAY("時間減衰"),
    MANUAL_ADJUST("手動調整"),
    APPEAL_ADJUST("異議申し立て見直し"),
    VOTE_BAN("投票処罰可決"),
    TICKET_BAN("処刑チケット行使"),
    BANNED("BAN執行");

    companion object {
        fun fromString(v: String): WatchScoreEventType =
            entries.firstOrNull { it.name == v } ?: MANUAL_ADJUST
    }
}

@Serializable
data class WatchCycle(
    val id: Long,
    val targetId: Long,
    val guildId: String,
    val userId: String,
    val cycleNumber: Int,
    val status: WatchCycleStatus,
    val currentScore: Double,
    val statusChannelId: String?,
    val statusMessageId: String?,
    val banTriggerType: BanTriggerType?,
    val banTriggerDetail: String?,
    val banDeadlineAt: Long?,
    val lastWordsContent: String?,
    val lastWordsMessageId: String?,
    val lastViolationAt: Long?,
    val lastDecayAt: Long?,
    val startedAt: Long,
    val bannedAt: Long?,
    val unbannedAt: Long?,
    val rejoinedAt: Long?,
)

@Serializable
data class WatchScoreEvent(
    val id: Long,
    val cycleId: Long,
    val targetId: Long,
    val guildId: String,
    val userId: String,
    val eventType: WatchScoreEventType,
    val delta: Double,
    val scoreAfter: Double,
    val category: String?,
    val reason: String?,
    val sourceChannelId: String?,
    val sourceMessageId: String?,
    val sourceMessageExcerpt: String?,
    val aiRawJson: String?,
    val actorUserId: String?,
    val createdAt: Long,
)

@Serializable
data class SavedRoleInfo(
    val id: String,
    val name: String,
)

@Serializable
data class WatchRoleSnapshot(
    val id: Long,
    val cycleId: Long,
    val guildId: String,
    val userId: String,
    val roles: List<SavedRoleInfo>,
    val nickname: String?,
    val capturedAt: Long,
    val restoredAt: Long?,
)

class WatchCycleService(private val database: Database) {
    private val json = Json { ignoreUnknownKeys = true }

    object WatchCycles : Table("watch_cycles") {
        val id = long("id").autoIncrement()
        val targetId = long("target_id").index("idx_watch_cycles_target")
        val guildId = varchar("guild_id", 64)
        val userId = varchar("user_id", 64)
        val cycleNumber = integer("cycle_number")
        val status = varchar("status", 16)
        val currentScore = double("current_score").default(0.0)
        val statusChannelId = varchar("status_channel_id", 64).nullable()
        val statusMessageId = varchar("status_message_id", 64).nullable()
        val banTriggerType = varchar("ban_trigger_type", 16).nullable()
        val banTriggerDetail = text("ban_trigger_detail").nullable()
        val banDeadlineAt = long("ban_deadline_at").nullable()
        val lastWordsContent = text("last_words_content").nullable()
        val lastWordsMessageId = varchar("last_words_message_id", 64).nullable()
        val lastViolationAt = long("last_violation_at").nullable()
        val lastDecayAt = long("last_decay_at").nullable()
        val startedAt = long("started_at")
        val bannedAt = long("banned_at").nullable()
        val unbannedAt = long("unbanned_at").nullable()
        val rejoinedAt = long("rejoined_at").nullable()

        override val primaryKey = PrimaryKey(id)

        init {
            index("idx_watch_cycles_guild_user_status", false, guildId, userId, status)
        }
    }

    object WatchScoreEvents : Table("watch_score_events") {
        val id = long("id").autoIncrement()
        val cycleId = long("cycle_id").index("idx_watch_events_cycle")
        val targetId = long("target_id")
        val guildId = varchar("guild_id", 64)
        val userId = varchar("user_id", 64)
        val eventType = varchar("event_type", 24)
        val delta = double("delta")
        val scoreAfter = double("score_after")
        val category = varchar("category", 32).nullable()
        val reason = text("reason").nullable()
        val sourceChannelId = varchar("source_channel_id", 64).nullable()
        val sourceMessageId = varchar("source_message_id", 64).nullable()
        val sourceMessageExcerpt = text("source_message_excerpt").nullable()
        val aiRawJson = text("ai_raw_json").nullable()
        val actorUserId = varchar("actor_user_id", 64).nullable()
        val createdAt = long("created_at")

        override val primaryKey = PrimaryKey(id)

        init {
            index("idx_watch_events_target_created", false, targetId, createdAt)
        }
    }

    object WatchRoleSnapshots : Table("watch_role_snapshots") {
        val id = long("id").autoIncrement()
        val cycleId = long("cycle_id").uniqueIndex("uq_watch_role_snapshot_cycle")
        val guildId = varchar("guild_id", 64)
        val userId = varchar("user_id", 64)
        val roleIdsJson = text("role_ids_json")
        val nickname = varchar("nickname", 64).nullable()
        val capturedAt = long("captured_at")
        val restoredAt = long("restored_at").nullable()

        override val primaryKey = PrimaryKey(id)
    }

    init {
        transaction(database) {
            SchemaUtils.create(WatchCycles, WatchScoreEvents, WatchRoleSnapshots)
        }
    }

    fun createNewCycle(target: WatchTarget, statusChannelId: String?): WatchCycle = transaction(database) {
        val prevCount = WatchCycles.selectAll()
            .where { WatchCycles.targetId eq target.id }
            .count()
            .toInt()
        val nextNum = prevCount + 1
        val now = System.currentTimeMillis()
        val cycleId = WatchCycles.insert {
            it[WatchCycles.targetId] = target.id
            it[WatchCycles.guildId] = target.guildId
            it[WatchCycles.userId] = target.userId
            it[WatchCycles.cycleNumber] = nextNum
            it[WatchCycles.status] = WatchCycleStatus.ACTIVE.name
            it[WatchCycles.currentScore] = 0.0
            it[WatchCycles.statusChannelId] = statusChannelId
            it[WatchCycles.startedAt] = now
        }[WatchCycles.id]

        WatchScoreEvents.insert {
            it[WatchScoreEvents.cycleId] = cycleId
            it[WatchScoreEvents.targetId] = target.id
            it[WatchScoreEvents.guildId] = target.guildId
            it[WatchScoreEvents.userId] = target.userId
            it[WatchScoreEvents.eventType] = WatchScoreEventType.CYCLE_START.name
            it[WatchScoreEvents.delta] = 0.0
            it[WatchScoreEvents.scoreAfter] = 0.0
            it[WatchScoreEvents.reason] = "第${nextNum}サイクル開始"
            it[WatchScoreEvents.createdAt] = now
        }

        WatchCycle(
            id = cycleId,
            targetId = target.id,
            guildId = target.guildId,
            userId = target.userId,
            cycleNumber = nextNum,
            status = WatchCycleStatus.ACTIVE,
            currentScore = 0.0,
            statusChannelId = statusChannelId,
            statusMessageId = null,
            banTriggerType = null,
            banTriggerDetail = null,
            banDeadlineAt = null,
            lastWordsContent = null,
            lastWordsMessageId = null,
            lastViolationAt = null,
            lastDecayAt = null,
            startedAt = now,
            bannedAt = null,
            unbannedAt = null,
            rejoinedAt = null,
        )
    }

    fun getCycleById(cycleId: Long): WatchCycle? = transaction(database) {
        WatchCycles.selectAll()
            .where { WatchCycles.id eq cycleId }
            .singleOrNull()
            ?.toCycle()
    }

    fun getLatestCycleByTarget(targetId: Long): WatchCycle? = transaction(database) {
        WatchCycles.selectAll()
            .where { WatchCycles.targetId eq targetId }
            .orderBy(WatchCycles.cycleNumber, SortOrder.DESC)
            .limit(1)
            .singleOrNull()
            ?.toCycle()
    }

    fun findCycleByGuildUserStatus(guildId: String, userId: String, status: WatchCycleStatus): WatchCycle? =
        transaction(database) {
            WatchCycles.selectAll()
                .where {
                    (WatchCycles.guildId eq guildId) and
                        (WatchCycles.userId eq userId) and
                        (WatchCycles.status eq status.name)
                }
                .orderBy(WatchCycles.cycleNumber, SortOrder.DESC)
                .limit(1)
                .singleOrNull()
                ?.toCycle()
        }

    fun listCyclesByStatus(status: WatchCycleStatus): List<WatchCycle> = transaction(database) {
        WatchCycles.selectAll()
            .where { WatchCycles.status eq status.name }
            .map { it.toCycle() }
    }

    fun listBannedCyclesByGuild(guildId: String): List<WatchCycle> = transaction(database) {
        WatchCycles.selectAll()
            .where { (WatchCycles.guildId eq guildId) and (WatchCycles.status eq WatchCycleStatus.BANNED.name) }
            .orderBy(WatchCycles.bannedAt, SortOrder.DESC)
            .map { it.toCycle() }
    }

    fun listActiveCyclesWithPositiveScore(guildId: String): List<WatchCycle> = transaction(database) {
        WatchCycles.selectAll()
            .where {
                (WatchCycles.guildId eq guildId) and
                    (WatchCycles.status eq WatchCycleStatus.ACTIVE.name) and
                    (WatchCycles.currentScore greater 0.0)
            }
            .map { it.toCycle() }
    }

    fun countBansByTarget(targetId: Long): Int = transaction(database) {
        WatchCycles.selectAll()
            .where { (WatchCycles.targetId eq targetId) and WatchCycles.bannedAt.isNotNull() }
            .count()
            .toInt()
    }

    fun updateStatusMessage(cycleId: Long, channelId: String, messageId: String) {
        transaction(database) {
            WatchCycles.update({ WatchCycles.id eq cycleId }) {
                it[WatchCycles.statusChannelId] = channelId
                it[WatchCycles.statusMessageId] = messageId
            }
        }
    }

    fun updateScoreAndRecordEvent(
        cycle: WatchCycle,
        eventType: WatchScoreEventType,
        delta: Double,
        newScore: Double,
        category: String? = null,
        reason: String? = null,
        sourceChannelId: String? = null,
        sourceMessageId: String? = null,
        sourceMessageExcerpt: String? = null,
        aiRawJson: String? = null,
        actorUserId: String? = null,
        now: Long = System.currentTimeMillis(),
    ): Pair<WatchCycle, WatchScoreEvent> = transaction(database) {
        val clamped = newScore.coerceIn(0.0, 100.0)
        val updatedViolationAt = if (eventType == WatchScoreEventType.AI_VIOLATION || (eventType == WatchScoreEventType.MANUAL_ADJUST && delta > 0.0)) {
            now
        } else {
            cycle.lastViolationAt
        }
        val updatedDecayAt = if (eventType == WatchScoreEventType.DECAY) now else cycle.lastDecayAt

        WatchCycles.update({ WatchCycles.id eq cycle.id }) {
            it[currentScore] = clamped
            it[lastViolationAt] = updatedViolationAt
            it[lastDecayAt] = updatedDecayAt
        }

        val excerpt = sourceMessageExcerpt?.take(500)
        val eventId = WatchScoreEvents.insert {
            it[WatchScoreEvents.cycleId] = cycle.id
            it[WatchScoreEvents.targetId] = cycle.targetId
            it[WatchScoreEvents.guildId] = cycle.guildId
            it[WatchScoreEvents.userId] = cycle.userId
            it[WatchScoreEvents.eventType] = eventType.name
            it[WatchScoreEvents.delta] = delta
            it[WatchScoreEvents.scoreAfter] = clamped
            it[WatchScoreEvents.category] = category
            it[WatchScoreEvents.reason] = reason
            it[WatchScoreEvents.sourceChannelId] = sourceChannelId
            it[WatchScoreEvents.sourceMessageId] = sourceMessageId
            it[WatchScoreEvents.sourceMessageExcerpt] = excerpt
            it[WatchScoreEvents.aiRawJson] = aiRawJson
            it[WatchScoreEvents.actorUserId] = actorUserId
            it[WatchScoreEvents.createdAt] = now
        }[WatchScoreEvents.id]

        val updatedCycle = cycle.copy(
            currentScore = clamped,
            lastViolationAt = updatedViolationAt,
            lastDecayAt = updatedDecayAt,
        )
        val event = WatchScoreEvent(
            id = eventId,
            cycleId = cycle.id,
            targetId = cycle.targetId,
            guildId = cycle.guildId,
            userId = cycle.userId,
            eventType = eventType,
            delta = delta,
            scoreAfter = clamped,
            category = category,
            reason = reason,
            sourceChannelId = sourceChannelId,
            sourceMessageId = sourceMessageId,
            sourceMessageExcerpt = excerpt,
            aiRawJson = aiRawJson,
            actorUserId = actorUserId,
            createdAt = now,
        )
        updatedCycle to event
    }

    fun markPendingBan(
        cycleId: Long,
        triggerType: BanTriggerType,
        triggerDetail: String?,
        deadlineAt: Long,
    ): WatchCycle? = transaction(database) {
        WatchCycles.update({ WatchCycles.id eq cycleId }) {
            it[status] = WatchCycleStatus.PENDING_BAN.name
            it[banTriggerType] = triggerType.name
            it[banTriggerDetail] = triggerDetail
            it[banDeadlineAt] = deadlineAt
        }
        WatchCycles.selectAll().where { WatchCycles.id eq cycleId }.singleOrNull()?.toCycle()
    }

    fun cancelPendingBan(cycleId: Long): WatchCycle? = transaction(database) {
        WatchCycles.update({ WatchCycles.id eq cycleId }) {
            it[status] = WatchCycleStatus.ACTIVE.name
            it[banTriggerType] = null
            it[banTriggerDetail] = null
            it[banDeadlineAt] = null
        }
        WatchCycles.selectAll().where { WatchCycles.id eq cycleId }.singleOrNull()?.toCycle()
    }

    fun markBanned(
        cycle: WatchCycle,
        lastWordsContent: String?,
        lastWordsMessageId: String?,
        bannedAt: Long = System.currentTimeMillis(),
    ): WatchCycle = transaction(database) {
        WatchCycles.update({ WatchCycles.id eq cycle.id }) {
            it[status] = WatchCycleStatus.BANNED.name
            it[WatchCycles.lastWordsContent] = lastWordsContent
            it[WatchCycles.lastWordsMessageId] = lastWordsMessageId
            it[WatchCycles.bannedAt] = bannedAt
        }
        WatchScoreEvents.insert {
            it[WatchScoreEvents.cycleId] = cycle.id
            it[WatchScoreEvents.targetId] = cycle.targetId
            it[WatchScoreEvents.guildId] = cycle.guildId
            it[WatchScoreEvents.userId] = cycle.userId
            it[WatchScoreEvents.eventType] = WatchScoreEventType.BANNED.name
            it[WatchScoreEvents.delta] = 0.0
            it[WatchScoreEvents.scoreAfter] = cycle.currentScore
            it[WatchScoreEvents.reason] = buildString {
                append(cycle.banTriggerType?.displayName ?: "BAN執行")
                if (!lastWordsContent.isNullOrBlank()) {
                    append(" / 遺言: ").append(lastWordsContent.take(120))
                }
            }
            it[WatchScoreEvents.createdAt] = bannedAt
        }
        cycle.copy(
            status = WatchCycleStatus.BANNED,
            lastWordsContent = lastWordsContent,
            lastWordsMessageId = lastWordsMessageId,
            bannedAt = bannedAt,
        )
    }

    fun markUnbanned(cycleId: Long, unbannedAt: Long = System.currentTimeMillis()) {
        transaction(database) {
            WatchCycles.update({ WatchCycles.id eq cycleId }) {
                it[WatchCycles.unbannedAt] = unbannedAt
            }
        }
    }

    fun markArchived(cycleId: Long, rejoinedAt: Long? = null) {
        transaction(database) {
            WatchCycles.update({ WatchCycles.id eq cycleId }) {
                it[status] = WatchCycleStatus.ARCHIVED.name
                if (rejoinedAt != null) it[WatchCycles.rejoinedAt] = rejoinedAt
            }
        }
    }

    fun getEventById(eventId: Long): WatchScoreEvent? = transaction(database) {
        WatchScoreEvents.selectAll()
            .where { WatchScoreEvents.id eq eventId }
            .singleOrNull()
            ?.toEvent()
    }

    fun getLatestEventForCycle(cycleId: Long): WatchScoreEvent? = transaction(database) {
        WatchScoreEvents.selectAll()
            .where { WatchScoreEvents.cycleId eq cycleId }
            .orderBy(WatchScoreEvents.createdAt, SortOrder.DESC)
            .limit(1)
            .singleOrNull()
            ?.toEvent()
    }

    fun getRecentViolationsForCycle(cycleId: Long, limit: Int = 5): List<WatchScoreEvent> = transaction(database) {
        WatchScoreEvents.selectAll()
            .where {
                (WatchScoreEvents.cycleId eq cycleId) and
                    (WatchScoreEvents.eventType eq WatchScoreEventType.AI_VIOLATION.name)
            }
            .orderBy(WatchScoreEvents.createdAt, SortOrder.DESC)
            .limit(limit)
            .map { it.toEvent() }
    }

    fun hasViolationForSourceMessage(targetId: Long, sourceMessageId: String): Boolean = transaction(database) {
        !WatchScoreEvents.selectAll()
            .where {
                (WatchScoreEvents.targetId eq targetId) and
                    (WatchScoreEvents.eventType eq WatchScoreEventType.AI_VIOLATION.name) and
                    (WatchScoreEvents.sourceMessageId eq sourceMessageId)
            }
            .empty()
    }

    fun getEventsForTargetSince(targetId: Long, sinceMillis: Long?): List<WatchScoreEvent> = transaction(database) {
        val query = if (sinceMillis != null) {
            WatchScoreEvents.selectAll()
                .where { (WatchScoreEvents.targetId eq targetId) and (WatchScoreEvents.createdAt greaterEq sinceMillis) }
        } else {
            WatchScoreEvents.selectAll()
                .where { WatchScoreEvents.targetId eq targetId }
        }
        query.orderBy(WatchScoreEvents.createdAt, SortOrder.ASC).map { it.toEvent() }
    }

    fun getLastEventBefore(targetId: Long, beforeMillis: Long): WatchScoreEvent? = transaction(database) {
        WatchScoreEvents.selectAll()
            .where { (WatchScoreEvents.targetId eq targetId) and (WatchScoreEvents.createdAt less beforeMillis) }
            .orderBy(WatchScoreEvents.createdAt, SortOrder.DESC)
            .limit(1)
            .singleOrNull()
            ?.toEvent()
    }

    fun getRecentEventsPaged(targetId: Long, page: Int, pageSize: Int = 8): Pair<List<WatchScoreEvent>, Long> =
        transaction(database) {
            val total = WatchScoreEvents.selectAll()
                .where { WatchScoreEvents.targetId eq targetId }
                .count()
            val offset = (page.coerceAtLeast(0) * pageSize).toLong()
            val items = WatchScoreEvents.selectAll()
                .where { WatchScoreEvents.targetId eq targetId }
                .orderBy(WatchScoreEvents.createdAt, SortOrder.DESC)
                .limit(pageSize)
                .offset(offset)
                .map { it.toEvent() }
            items to total
        }

    fun sumAiViolationDeltaBetween(targetId: Long, fromMillis: Long, toMillis: Long): Double = transaction(database) {
        WatchScoreEvents.selectAll()
            .where {
                (WatchScoreEvents.targetId eq targetId) and
                    (WatchScoreEvents.eventType eq WatchScoreEventType.AI_VIOLATION.name) and
                    (WatchScoreEvents.createdAt greaterEq fromMillis) and
                    (WatchScoreEvents.createdAt less toMillis)
            }
            .sumOf { it[WatchScoreEvents.delta] }
    }

    fun saveRoleSnapshot(
        cycleId: Long,
        guildId: String,
        userId: String,
        roles: List<SavedRoleInfo>,
        nickname: String?,
    ): WatchRoleSnapshot = transaction(database) {
        val now = System.currentTimeMillis()
        val rolesJson = json.encodeToString(roles)
        val existing = WatchRoleSnapshots.selectAll()
            .where { WatchRoleSnapshots.cycleId eq cycleId }
            .singleOrNull()

        val id = if (existing != null) {
            WatchRoleSnapshots.update({ WatchRoleSnapshots.cycleId eq cycleId }) {
                it[roleIdsJson] = rolesJson
                it[WatchRoleSnapshots.nickname] = nickname
                it[capturedAt] = now
                it[restoredAt] = null
            }
            existing[WatchRoleSnapshots.id]
        } else {
            WatchRoleSnapshots.insert {
                it[WatchRoleSnapshots.cycleId] = cycleId
                it[WatchRoleSnapshots.guildId] = guildId
                it[WatchRoleSnapshots.userId] = userId
                it[roleIdsJson] = rolesJson
                it[WatchRoleSnapshots.nickname] = nickname
                it[capturedAt] = now
            }[WatchRoleSnapshots.id]
        }
        WatchRoleSnapshot(id, cycleId, guildId, userId, roles, nickname, now, null)
    }

    fun getRoleSnapshot(cycleId: Long): WatchRoleSnapshot? = transaction(database) {
        WatchRoleSnapshots.selectAll()
            .where { WatchRoleSnapshots.cycleId eq cycleId }
            .singleOrNull()
            ?.let { row ->
                val roles = runCatching {
                    json.decodeFromString<List<SavedRoleInfo>>(row[WatchRoleSnapshots.roleIdsJson])
                }.getOrDefault(emptyList())
                WatchRoleSnapshot(
                    id = row[WatchRoleSnapshots.id],
                    cycleId = row[WatchRoleSnapshots.cycleId],
                    guildId = row[WatchRoleSnapshots.guildId],
                    userId = row[WatchRoleSnapshots.userId],
                    roles = roles,
                    nickname = row[WatchRoleSnapshots.nickname],
                    capturedAt = row[WatchRoleSnapshots.capturedAt],
                    restoredAt = row[WatchRoleSnapshots.restoredAt],
                )
            }
    }

    fun markRoleSnapshotRestored(cycleId: Long, restoredAt: Long = System.currentTimeMillis()) {
        transaction(database) {
            WatchRoleSnapshots.update({ WatchRoleSnapshots.cycleId eq cycleId }) {
                it[WatchRoleSnapshots.restoredAt] = restoredAt
            }
        }
    }

    private fun ResultRow.toCycle(): WatchCycle = WatchCycle(
        id = this[WatchCycles.id],
        targetId = this[WatchCycles.targetId],
        guildId = this[WatchCycles.guildId],
        userId = this[WatchCycles.userId],
        cycleNumber = this[WatchCycles.cycleNumber],
        status = WatchCycleStatus.fromString(this[WatchCycles.status]),
        currentScore = this[WatchCycles.currentScore],
        statusChannelId = this[WatchCycles.statusChannelId],
        statusMessageId = this[WatchCycles.statusMessageId],
        banTriggerType = BanTriggerType.fromString(this[WatchCycles.banTriggerType]),
        banTriggerDetail = this[WatchCycles.banTriggerDetail],
        banDeadlineAt = this[WatchCycles.banDeadlineAt],
        lastWordsContent = this[WatchCycles.lastWordsContent],
        lastWordsMessageId = this[WatchCycles.lastWordsMessageId],
        lastViolationAt = this[WatchCycles.lastViolationAt],
        lastDecayAt = this[WatchCycles.lastDecayAt],
        startedAt = this[WatchCycles.startedAt],
        bannedAt = this[WatchCycles.bannedAt],
        unbannedAt = this[WatchCycles.unbannedAt],
        rejoinedAt = this[WatchCycles.rejoinedAt],
    )

    private fun ResultRow.toEvent(): WatchScoreEvent = WatchScoreEvent(
        id = this[WatchScoreEvents.id],
        cycleId = this[WatchScoreEvents.cycleId],
        targetId = this[WatchScoreEvents.targetId],
        guildId = this[WatchScoreEvents.guildId],
        userId = this[WatchScoreEvents.userId],
        eventType = WatchScoreEventType.fromString(this[WatchScoreEvents.eventType]),
        delta = this[WatchScoreEvents.delta],
        scoreAfter = this[WatchScoreEvents.scoreAfter],
        category = this[WatchScoreEvents.category],
        reason = this[WatchScoreEvents.reason],
        sourceChannelId = this[WatchScoreEvents.sourceChannelId],
        sourceMessageId = this[WatchScoreEvents.sourceMessageId],
        sourceMessageExcerpt = this[WatchScoreEvents.sourceMessageExcerpt],
        aiRawJson = this[WatchScoreEvents.aiRawJson],
        actorUserId = this[WatchScoreEvents.actorUserId],
        createdAt = this[WatchScoreEvents.createdAt],
    )
}
