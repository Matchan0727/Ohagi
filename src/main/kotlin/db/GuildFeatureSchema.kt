package jp.simplespace.db

import kotlinx.serialization.Serializable
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import java.util.concurrent.ConcurrentHashMap

enum class FeatureKey(
    val key: String,
    val displayName: String,
    val description: String,
) {
    WATCH_SYSTEM(
        key = "watch_system",
        displayName = "メンバー監視・処罰システム",
        description = "監視対象の違反度可視化・AI判定・遺言BAN・投票・処刑チケット・時間減衰機能",
    );

    companion object {
        fun fromKey(key: String): FeatureKey? = entries.firstOrNull { it.key.equals(key, ignoreCase = true) }
    }
}

@Serializable
data class GuildFeature(
    val guildId: String,
    val featureKey: String,
    val enabled: Boolean,
    val updatedBy: String,
    val updatedAt: Long,
)

class GuildFeatureService(private val database: Database) {
    object GuildFeatures : Table("guild_features") {
        val guildId = varchar("guild_id", 64)
        val featureKey = varchar("feature_key", 64)
        val enabled = bool("enabled").default(false)
        val updatedBy = varchar("updated_by", 64)
        val updatedAt = long("updated_at")

        override val primaryKey = PrimaryKey(guildId, featureKey)
    }

    private val cache = ConcurrentHashMap<Pair<String, String>, Boolean>()

    init {
        transaction(database) {
            SchemaUtils.create(GuildFeatures)
        }
        reloadCache()
    }

    fun reloadCache() {
        val all = transaction(database) {
            GuildFeatures.selectAll().map {
                (it[GuildFeatures.guildId] to it[GuildFeatures.featureKey]) to it[GuildFeatures.enabled]
            }
        }
        cache.clear()
        all.forEach { (k, v) -> cache[k] = v }
    }

    fun isEnabled(guildId: String, feature: FeatureKey): Boolean {
        return isEnabled(guildId, feature.key)
    }

    fun isEnabled(guildId: String, featureKey: String): Boolean {
        return cache.computeIfAbsent(guildId to featureKey) {
            transaction(database) {
                GuildFeatures.selectAll()
                    .where { (GuildFeatures.guildId eq guildId) and (GuildFeatures.featureKey eq featureKey) }
                    .singleOrNull()
                    ?.get(GuildFeatures.enabled)
                    ?: false
            }
        }
    }

    fun setEnabled(guildId: String, feature: FeatureKey, enabled: Boolean, updatedBy: String): GuildFeature {
        val now = System.currentTimeMillis()
        transaction(database) {
            val exists = !GuildFeatures.selectAll()
                .where { (GuildFeatures.guildId eq guildId) and (GuildFeatures.featureKey eq feature.key) }
                .empty()
            if (exists) {
                GuildFeatures.update({
                    (GuildFeatures.guildId eq guildId) and (GuildFeatures.featureKey eq feature.key)
                }) {
                    it[GuildFeatures.enabled] = enabled
                    it[GuildFeatures.updatedBy] = updatedBy
                    it[GuildFeatures.updatedAt] = now
                }
            } else {
                GuildFeatures.insert {
                    it[GuildFeatures.guildId] = guildId
                    it[GuildFeatures.featureKey] = feature.key
                    it[GuildFeatures.enabled] = enabled
                    it[GuildFeatures.updatedBy] = updatedBy
                    it[GuildFeatures.updatedAt] = now
                }
            }
        }
        cache[guildId to feature.key] = enabled
        return GuildFeature(guildId, feature.key, enabled, updatedBy, now)
    }

    fun listByGuild(guildId: String): List<GuildFeature> = transaction(database) {
        GuildFeatures.selectAll()
            .where { GuildFeatures.guildId eq guildId }
            .map {
                GuildFeature(
                    guildId = it[GuildFeatures.guildId],
                    featureKey = it[GuildFeatures.featureKey],
                    enabled = it[GuildFeatures.enabled],
                    updatedBy = it[GuildFeatures.updatedBy],
                    updatedAt = it[GuildFeatures.updatedAt],
                )
            }
    }

    fun listEnabledGuilds(feature: FeatureKey): List<String> = transaction(database) {
        GuildFeatures.selectAll()
            .where { (GuildFeatures.featureKey eq feature.key) and (GuildFeatures.enabled eq true) }
            .map { it[GuildFeatures.guildId] }
    }
}
