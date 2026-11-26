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
)

class WatchVoteTicketService(private val database: Database) {
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

    init {
        transaction(database) {
            SchemaUtils.create(WatchVotes, WatchVoteBallots, WatchBanTickets)
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
