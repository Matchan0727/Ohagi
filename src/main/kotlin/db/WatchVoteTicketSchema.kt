package jp.simplespace.db

import kotlinx.serialization.Serializable
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update

enum class WatchVoteStatus {
    OPEN,
    PASSED,
    REJECTED,
    CANCELLED;

    companion object {
        fun fromString(v: String): WatchVoteStatus =
            entries.firstOrNull { it.name == v } ?: OPEN
    }
}

enum class VoteChoice {
    YES,
    NO;

    companion object {
        fun fromString(v: String): VoteChoice =
            entries.firstOrNull { it.name == v } ?: NO
    }
}

enum class ReportPunishmentType(
    val displayName: String,
    val scoreDelta: Double,
    val isBan: Boolean,
) {
    SCORE_15("違反度 +15% 加算", 15.0, false),
    SCORE_30("違反度 +30% 加算", 30.0, false),
    SCORE_50("違反度 +50% 加算", 50.0, false),
    CUSTOM("自由入力の違反度を加算（下の欄で指定）", 0.0, false),
    BAN("BAN 処刑", 0.0, true);

    companion object {
        fun fromString(v: String?): ReportPunishmentType =
            entries.firstOrNull { it.name.equals(v, ignoreCase = true) } ?: SCORE_30
    }
}

@Serializable
data class ReportPunishment(
    val isBan: Boolean,
    val scoreDelta: Double,
) {
    val displayName: String
        get() = if (isBan) {
            "BAN 処刑"
        } else {
            val formatted = formatDelta(scoreDelta)
            "違反度 +${formatted}% 加算"
        }

    fun toStorageValue(): String = if (isBan) {
        ReportPunishmentType.BAN.name
    } else {
        when (scoreDelta) {
            15.0 -> ReportPunishmentType.SCORE_15.name
            30.0 -> ReportPunishmentType.SCORE_30.name
            50.0 -> ReportPunishmentType.SCORE_50.name
            else -> "SCORE:${formatDelta(scoreDelta)}"
        }
    }

    companion object {
        const val MIN_CUSTOM_SCORE_DELTA = 0.1
        const val MAX_CUSTOM_SCORE_DELTA = 100.0

        fun ban(): ReportPunishment = ReportPunishment(isBan = true, scoreDelta = 0.0)

        fun score(delta: Double): ReportPunishment {
            val rounded = kotlin.math.round(delta.coerceIn(MIN_CUSTOM_SCORE_DELTA, MAX_CUSTOM_SCORE_DELTA) * 10.0) / 10.0
            return ReportPunishment(isBan = false, scoreDelta = rounded)
        }

        fun fromPreset(type: ReportPunishmentType): ReportPunishment =
            if (type.isBan) ban() else score(if (type.scoreDelta > 0.0) type.scoreDelta else 30.0)

        fun parseCustomDeltaInput(raw: String?): Double? {
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
            if (!parsed.isFinite() || parsed < MIN_CUSTOM_SCORE_DELTA || parsed > MAX_CUSTOM_SCORE_DELTA) return null
            return kotlin.math.round(parsed * 10.0) / 10.0
        }

        fun fromStorageValue(raw: String?): ReportPunishment {
            val v = raw?.trim().orEmpty()
            if (v.equals(ReportPunishmentType.BAN.name, ignoreCase = true)) return ban()
            if (v.startsWith("SCORE:", ignoreCase = true)) {
                val num = v.substringAfter(":").toDoubleOrNull()
                if (num != null && num.isFinite() && num >= MIN_CUSTOM_SCORE_DELTA) {
                    return score(num)
                }
            }
            return fromPreset(ReportPunishmentType.fromString(v))
        }

        private fun formatDelta(delta: Double): String =
            if (delta % 1.0 == 0.0) {
                delta.toInt().toString()
            } else {
                String.format(java.util.Locale.US, "%.1f", delta)
            }
    }
}

@Serializable
data class WatchIncidentReport(
    val id: Long,
    val voteId: Long,
    val guildId: String,
    val targetId: Long,
    val reporterUserId: String,
    val incidentPlace: String,
    val punishment: ReportPunishment,
    val detail: String,
    val createdAt: Long,
)

enum class TicketIssueSource {
    MANUAL,
    AI_APPROVED,
    AI_AUTO;

    companion object {
        fun fromString(v: String): TicketIssueSource =
            entries.firstOrNull { it.name == v } ?: MANUAL
    }
}

enum class TicketStatus {
    PENDING_APPROVAL,
    ACTIVE,
    USED,
    REVOKED,
    EXPIRED;

    companion object {
        fun fromString(v: String): TicketStatus =
            entries.firstOrNull { it.name == v } ?: ACTIVE
    }
}

@Serializable
data class WatchVote(
    val id: Long,
    val guildId: String,
    val targetId: Long,
    val cycleId: Long,
    val initiatorUserId: String,
    val reason: String,
    val channelId: String,
    val messageId: String?,
    val status: WatchVoteStatus,
    val expiresAt: Long,
    val createdAt: Long,
    val closedAt: Long?,
)

@Serializable
data class WatchVoteTally(
    val yesCount: Int,
    val noCount: Int,
) {
    val total: Int get() = yesCount + noCount
    val yesRatio: Double get() = if (total == 0) 0.0 else yesCount.toDouble() / total.toDouble()
}

@Serializable
data class WatchBanTicket(
    val id: Long,
    val guildId: String,
    val targetId: Long,
    val holderUserId: String,
    val issuedByUserId: String?,
    val issueSource: TicketIssueSource,
    val reason: String,
    val sourceScoreEventId: Long?,
    val status: TicketStatus,
    val expiresAt: Long?,
    val usedCycleId: Long?,
    val createdAt: Long,
    val usedAt: Long?,
) {
    val isGlobal: Boolean
        get() = holderUserId == WatchVoteTicketService.GLOBAL_HOLDER_ID
}

class WatchVoteTicketService(private val database: Database) {
    companion object {
        const val GLOBAL_HOLDER_ID = "ALL"
    }
    object WatchVotes : Table("watch_votes") {
        val id = long("id").autoIncrement()
        val guildId = varchar("guild_id", 64).index("idx_watch_votes_guild")
        val targetId = long("target_id").index("idx_watch_votes_target")
        val cycleId = long("cycle_id")
        val initiatorUserId = varchar("initiator_user_id", 64)
        val reason = text("reason")
        val channelId = varchar("channel_id", 64)
        val messageId = varchar("message_id", 64).nullable()
        val status = varchar("status", 16)
        val expiresAt = long("expires_at")
        val createdAt = long("created_at")
        val closedAt = long("closed_at").nullable()

        override val primaryKey = PrimaryKey(id)
    }

    object WatchVoteBallots : Table("watch_vote_ballots") {
        val voteId = long("vote_id")
        val voterUserId = varchar("voter_user_id", 64)
        val choice = varchar("choice", 8)
        val votedAt = long("voted_at")

        override val primaryKey = PrimaryKey(voteId, voterUserId)
    }

    object WatchBanTickets : Table("watch_ban_tickets") {
        val id = long("id").autoIncrement()
        val guildId = varchar("guild_id", 64).index("idx_watch_tickets_guild")
        val targetId = long("target_id").index("idx_watch_tickets_target")
        val holderUserId = varchar("holder_user_id", 64).index("idx_watch_tickets_holder")
        val issuedByUserId = varchar("issued_by_user_id", 64).nullable()
        val issueSource = varchar("issue_source", 16)
        val reason = text("reason")
        val sourceScoreEventId = long("source_score_event_id").nullable()
        val status = varchar("status", 24)
        val expiresAt = long("expires_at").nullable()
        val usedCycleId = long("used_cycle_id").nullable()
        val createdAt = long("created_at")
        val usedAt = long("used_at").nullable()

        override val primaryKey = PrimaryKey(id)
    }

    object WatchIncidentReports : Table("watch_incident_reports") {
        val id = long("id").autoIncrement()
        val voteId = long("vote_id").uniqueIndex("uq_watch_incident_reports_vote")
        val guildId = varchar("guild_id", 64).index("idx_watch_incident_reports_guild")
        val targetId = long("target_id").index("idx_watch_incident_reports_target")
        val reporterUserId = varchar("reporter_user_id", 64)
        val incidentPlace = varchar("incident_place", 64)
        val punishmentType = varchar("punishment_type", 24)
        val detail = text("detail")
        val createdAt = long("created_at")

        override val primaryKey = PrimaryKey(id)
    }

    init {
        transaction(database) {
            SchemaUtils.create(WatchVotes, WatchVoteBallots, WatchBanTickets, WatchIncidentReports)
        }
    }

    fun createVote(
        guildId: String,
        targetId: Long,
        cycleId: Long,
        initiatorUserId: String,
        reason: String,
        channelId: String,
        expiresAt: Long,
    ): WatchVote = transaction(database) {
        val now = System.currentTimeMillis()
        val id = WatchVotes.insert {
            it[WatchVotes.guildId] = guildId
            it[WatchVotes.targetId] = targetId
            it[WatchVotes.cycleId] = cycleId
            it[WatchVotes.initiatorUserId] = initiatorUserId
            it[WatchVotes.reason] = reason
            it[WatchVotes.channelId] = channelId
            it[WatchVotes.status] = WatchVoteStatus.OPEN.name
            it[WatchVotes.expiresAt] = expiresAt
            it[WatchVotes.createdAt] = now
        }[WatchVotes.id]

        WatchVote(
            id = id,
            guildId = guildId,
            targetId = targetId,
            cycleId = cycleId,
            initiatorUserId = initiatorUserId,
            reason = reason,
            channelId = channelId,
            messageId = null,
            status = WatchVoteStatus.OPEN,
            expiresAt = expiresAt,
            createdAt = now,
            closedAt = null,
        )
    }

    fun updateVoteMessageId(voteId: Long, messageId: String) {
        transaction(database) {
            WatchVotes.update({ WatchVotes.id eq voteId }) {
                it[WatchVotes.messageId] = messageId
            }
        }
    }

    fun getVoteById(voteId: Long): WatchVote? = transaction(database) {
        WatchVotes.selectAll().where { WatchVotes.id eq voteId }.singleOrNull()?.toVote()
    }

    fun getOpenVoteForTarget(targetId: Long): WatchVote? = transaction(database) {
        WatchVotes.selectAll()
            .where { (WatchVotes.targetId eq targetId) and (WatchVotes.status eq WatchVoteStatus.OPEN.name) }
            .singleOrNull()
            ?.toVote()
    }

    fun listOpenVotes(guildId: String? = null): List<WatchVote> = transaction(database) {
        WatchVotes.selectAll()
            .where {
                val base = WatchVotes.status eq WatchVoteStatus.OPEN.name
                if (guildId != null) base and (WatchVotes.guildId eq guildId) else base
            }
            .map { it.toVote() }
    }

    fun getLastRejectedVoteForTarget(targetId: Long): WatchVote? = transaction(database) {
        WatchVotes.selectAll()
            .where { (WatchVotes.targetId eq targetId) and (WatchVotes.status eq WatchVoteStatus.REJECTED.name) }
            .orderBy(WatchVotes.closedAt, SortOrder.DESC)
            .limit(1)
            .singleOrNull()
            ?.toVote()
    }

    fun castBallot(voteId: Long, voterUserId: String, choice: VoteChoice): WatchVoteTally = transaction(database) {
        val now = System.currentTimeMillis()
        val exists = !WatchVoteBallots.selectAll()
            .where { (WatchVoteBallots.voteId eq voteId) and (WatchVoteBallots.voterUserId eq voterUserId) }
            .empty()
        if (exists) {
            WatchVoteBallots.update({
                (WatchVoteBallots.voteId eq voteId) and (WatchVoteBallots.voterUserId eq voterUserId)
            }) {
                it[WatchVoteBallots.choice] = choice.name
                it[WatchVoteBallots.votedAt] = now
            }
        } else {
            WatchVoteBallots.insert {
                it[WatchVoteBallots.voteId] = voteId
                it[WatchVoteBallots.voterUserId] = voterUserId
                it[WatchVoteBallots.choice] = choice.name
                it[WatchVoteBallots.votedAt] = now
            }
        }
        getTallyInternal(voteId)
    }

    fun getTally(voteId: Long): WatchVoteTally = transaction(database) {
        getTallyInternal(voteId)
    }

    private fun getTallyInternal(voteId: Long): WatchVoteTally {
        var yes = 0
        var no = 0
        WatchVoteBallots.selectAll()
            .where { WatchVoteBallots.voteId eq voteId }
            .forEach {
                if (it[WatchVoteBallots.choice] == VoteChoice.YES.name) yes++ else no++
            }
        return WatchVoteTally(yes, no)
    }

    fun closeVote(voteId: Long, status: WatchVoteStatus, closedAt: Long = System.currentTimeMillis()): WatchVote? =
        transaction(database) {
            WatchVotes.update({ WatchVotes.id eq voteId }) {
                it[WatchVotes.status] = status.name
                it[WatchVotes.closedAt] = closedAt
            }
            WatchVotes.selectAll().where { WatchVotes.id eq voteId }.singleOrNull()?.toVote()
        }

    fun createIncidentReport(
        voteId: Long,
        guildId: String,
        targetId: Long,
        reporterUserId: String,
        incidentPlace: String,
        punishment: ReportPunishment,
        detail: String,
    ): WatchIncidentReport = transaction(database) {
        val now = System.currentTimeMillis()
        val id = WatchIncidentReports.insert {
            it[WatchIncidentReports.voteId] = voteId
            it[WatchIncidentReports.guildId] = guildId
            it[WatchIncidentReports.targetId] = targetId
            it[WatchIncidentReports.reporterUserId] = reporterUserId
            it[WatchIncidentReports.incidentPlace] = incidentPlace.take(64)
            it[WatchIncidentReports.punishmentType] = punishment.toStorageValue().take(24)
            it[WatchIncidentReports.detail] = detail.take(1500)
            it[WatchIncidentReports.createdAt] = now
        }[WatchIncidentReports.id]

        WatchIncidentReport(
            id = id,
            voteId = voteId,
            guildId = guildId,
            targetId = targetId,
            reporterUserId = reporterUserId,
            incidentPlace = incidentPlace.take(64),
            punishment = punishment,
            detail = detail.take(1500),
            createdAt = now,
        )
    }

    fun getIncidentReportByVoteId(voteId: Long): WatchIncidentReport? = transaction(database) {
        WatchIncidentReports.selectAll()
            .where { WatchIncidentReports.voteId eq voteId }
            .singleOrNull()
            ?.let {
                WatchIncidentReport(
                    id = it[WatchIncidentReports.id],
                    voteId = it[WatchIncidentReports.voteId],
                    guildId = it[WatchIncidentReports.guildId],
                    targetId = it[WatchIncidentReports.targetId],
                    reporterUserId = it[WatchIncidentReports.reporterUserId],
                    incidentPlace = it[WatchIncidentReports.incidentPlace],
                    punishment = ReportPunishment.fromStorageValue(it[WatchIncidentReports.punishmentType]),
                    detail = it[WatchIncidentReports.detail],
                    createdAt = it[WatchIncidentReports.createdAt],
                )
            }
    }

    fun createTicket(
        guildId: String,
        targetId: Long,
        holderUserId: String,
        issuedByUserId: String?,
        issueSource: TicketIssueSource,
        reason: String,
        sourceScoreEventId: Long? = null,
        status: TicketStatus = TicketStatus.ACTIVE,
        expiresAt: Long? = null,
    ): WatchBanTicket = transaction(database) {
        val now = System.currentTimeMillis()
        val id = WatchBanTickets.insert {
            it[WatchBanTickets.guildId] = guildId
            it[WatchBanTickets.targetId] = targetId
            it[WatchBanTickets.holderUserId] = holderUserId
            it[WatchBanTickets.issuedByUserId] = issuedByUserId
            it[WatchBanTickets.issueSource] = issueSource.name
            it[WatchBanTickets.reason] = reason
            it[WatchBanTickets.sourceScoreEventId] = sourceScoreEventId
            it[WatchBanTickets.status] = status.name
            it[WatchBanTickets.expiresAt] = expiresAt
            it[WatchBanTickets.createdAt] = now
        }[WatchBanTickets.id]

        WatchBanTicket(
            id = id,
            guildId = guildId,
            targetId = targetId,
            holderUserId = holderUserId,
            issuedByUserId = issuedByUserId,
            issueSource = issueSource,
            reason = reason,
            sourceScoreEventId = sourceScoreEventId,
            status = status,
            expiresAt = expiresAt,
            usedCycleId = null,
            createdAt = now,
            usedAt = null,
        )
    }

    fun getTicketById(ticketId: Long): WatchBanTicket? = transaction(database) {
        val ticket = WatchBanTickets.selectAll()
            .where { WatchBanTickets.id eq ticketId }
            .singleOrNull()
            ?.toTicket() ?: return@transaction null
        expireIfNeeded(ticket)
    }

    fun listActiveTicketsByHolder(guildId: String, holderUserId: String): List<WatchBanTicket> = transaction(database) {
        WatchBanTickets.selectAll()
            .where {
                (WatchBanTickets.guildId eq guildId) and
                    (WatchBanTickets.holderUserId eq holderUserId) and
                    (WatchBanTickets.status eq TicketStatus.ACTIVE.name)
            }
            .orderBy(WatchBanTickets.createdAt, SortOrder.DESC)
            .map { expireIfNeeded(it.toTicket()) }
            .filter { it.status == TicketStatus.ACTIVE }
    }

    fun listActiveTicketsByGuild(guildId: String): List<WatchBanTicket> = transaction(database) {
        WatchBanTickets.selectAll()
            .where {
                (WatchBanTickets.guildId eq guildId) and
                    (WatchBanTickets.status eq TicketStatus.ACTIVE.name)
            }
            .orderBy(WatchBanTickets.createdAt, SortOrder.DESC)
            .map { expireIfNeeded(it.toTicket()) }
            .filter { it.status == TicketStatus.ACTIVE }
    }

    fun listActiveGlobalTicketsForTarget(guildId: String, targetId: Long): List<WatchBanTicket> = transaction(database) {
        WatchBanTickets.selectAll()
            .where {
                (WatchBanTickets.guildId eq guildId) and
                    (WatchBanTickets.targetId eq targetId) and
                    (WatchBanTickets.holderUserId eq GLOBAL_HOLDER_ID) and
                    (WatchBanTickets.status eq TicketStatus.ACTIVE.name)
            }
            .orderBy(WatchBanTickets.createdAt, SortOrder.ASC)
            .map { expireIfNeeded(it.toTicket()) }
            .filter { it.status == TicketStatus.ACTIVE }
    }

    fun listActivePersonalTicketsForHolderAndTarget(
        guildId: String,
        holderUserId: String,
        targetId: Long,
    ): List<WatchBanTicket> = transaction(database) {
        WatchBanTickets.selectAll()
            .where {
                (WatchBanTickets.guildId eq guildId) and
                    (WatchBanTickets.targetId eq targetId) and
                    (WatchBanTickets.holderUserId eq holderUserId) and
                    (WatchBanTickets.status eq TicketStatus.ACTIVE.name)
            }
            .orderBy(WatchBanTickets.createdAt, SortOrder.DESC)
            .map { expireIfNeeded(it.toTicket()) }
            .filter { it.status == TicketStatus.ACTIVE }
    }

    fun countActiveGlobalTicketsForTarget(guildId: String, targetId: Long): Int =
        listActiveGlobalTicketsForTarget(guildId, targetId).size

    fun approveTicket(ticketId: Long, approverUserId: String): WatchBanTicket? = transaction(database) {
        WatchBanTickets.update({
            (WatchBanTickets.id eq ticketId) and (WatchBanTickets.status eq TicketStatus.PENDING_APPROVAL.name)
        }) {
            it[status] = TicketStatus.ACTIVE.name
            it[issuedByUserId] = approverUserId
            it[issueSource] = TicketIssueSource.AI_APPROVED.name
        }
        WatchBanTickets.selectAll().where { WatchBanTickets.id eq ticketId }.singleOrNull()?.toTicket()
    }

    fun revokeTicket(ticketId: Long): WatchBanTicket? = transaction(database) {
        WatchBanTickets.update({ WatchBanTickets.id eq ticketId }) {
            it[status] = TicketStatus.REVOKED.name
        }
        WatchBanTickets.selectAll().where { WatchBanTickets.id eq ticketId }.singleOrNull()?.toTicket()
    }

    fun markTicketUsed(ticketId: Long, usedCycleId: Long, usedAt: Long = System.currentTimeMillis()): WatchBanTicket? =
        transaction(database) {
            val updatedRows = WatchBanTickets.update({
                (WatchBanTickets.id eq ticketId) and (WatchBanTickets.status eq TicketStatus.ACTIVE.name)
            }) {
                it[status] = TicketStatus.USED.name
                it[WatchBanTickets.usedCycleId] = usedCycleId
                it[WatchBanTickets.usedAt] = usedAt
            }
            if (updatedRows == 0) return@transaction null
            WatchBanTickets.selectAll().where { WatchBanTickets.id eq ticketId }.singleOrNull()?.toTicket()
        }

    private fun expireIfNeeded(ticket: WatchBanTicket): WatchBanTicket {
        val now = System.currentTimeMillis()
        if (ticket.status == TicketStatus.ACTIVE && ticket.expiresAt != null && ticket.expiresAt <= now) {
            WatchBanTickets.update({ WatchBanTickets.id eq ticket.id }) {
                it[status] = TicketStatus.EXPIRED.name
            }
            return ticket.copy(status = TicketStatus.EXPIRED)
        }
        return ticket
    }

    private fun ResultRow.toVote(): WatchVote = WatchVote(
        id = this[WatchVotes.id],
        guildId = this[WatchVotes.guildId],
        targetId = this[WatchVotes.targetId],
        cycleId = this[WatchVotes.cycleId],
        initiatorUserId = this[WatchVotes.initiatorUserId],
        reason = this[WatchVotes.reason],
        channelId = this[WatchVotes.channelId],
        messageId = this[WatchVotes.messageId],
        status = WatchVoteStatus.fromString(this[WatchVotes.status]),
        expiresAt = this[WatchVotes.expiresAt],
        createdAt = this[WatchVotes.createdAt],
        closedAt = this[WatchVotes.closedAt],
    )

    private fun ResultRow.toTicket(): WatchBanTicket = WatchBanTicket(
        id = this[WatchBanTickets.id],
        guildId = this[WatchBanTickets.guildId],
        targetId = this[WatchBanTickets.targetId],
        holderUserId = this[WatchBanTickets.holderUserId],
        issuedByUserId = this[WatchBanTickets.issuedByUserId],
        issueSource = TicketIssueSource.fromString(this[WatchBanTickets.issueSource]),
        reason = this[WatchBanTickets.reason],
        sourceScoreEventId = this[WatchBanTickets.sourceScoreEventId],
        status = TicketStatus.fromString(this[WatchBanTickets.status]),
        expiresAt = this[WatchBanTickets.expiresAt],
        usedCycleId = this[WatchBanTickets.usedCycleId],
        createdAt = this[WatchBanTickets.createdAt],
        usedAt = this[WatchBanTickets.usedAt],
    )
}
