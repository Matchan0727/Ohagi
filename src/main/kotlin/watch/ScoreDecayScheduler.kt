package jp.simplespace.watch

import jp.simplespace.db.FeatureKey
import jp.simplespace.db.WatchCycle
import jp.simplespace.db.WatchGuildConfig
import jp.simplespace.db.WatchScoreEventType
import jp.simplespace.discord.OhagiBot
import jp.simplespace.guildFeatureService
import jp.simplespace.watchConfigService
import jp.simplespace.watchCycleService
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import net.dv8tion.jda.api.JDA
import java.util.Locale

data class DecayCalculation(
    val shouldDecay: Boolean,
    val decayAmount: Double,
    val calmBonusApplied: Boolean,
)

object ScoreDecayScheduler {
    private const val CHECK_INTERVAL_MILLIS = 30L * 60_000L
    private const val SEVEN_DAYS_MILLIS = 7L * 24L * 3600_000L

    @Volatile
    private var job: Job? = null

    fun start(jda: JDA) {
        if (job?.isActive == true) return
        job = OhagiBot.scope.launch {
            while (isActive) {
                runCatching {
                    processAllGuilds(jda)
                }.onFailure {
                    OhagiBot.logger.error("違反度減衰バッチ処理中にエラーが発生しました", it)
                }
                delay(CHECK_INTERVAL_MILLIS)
            }
        }
        OhagiBot.logger.info("違反度時間減衰スケジューラを開始しました（チェック周期: 30分）")
    }

    fun calculateDecay(
        cycle: WatchCycle,
        config: WatchGuildConfig,
        recent7dViolationSum: Double,
        prev7dViolationSum: Double,
        nowMillis: Long,
    ): DecayCalculation {
        if (!config.decayEnabled || cycle.currentScore <= 0.0 || config.decayAmount <= 0.0) {
            return DecayCalculation(false, 0.0, false)
        }
        val graceBase = cycle.lastViolationAt ?: cycle.startedAt
        val graceMillis = config.decayGraceHours.coerceAtLeast(1) * 3600_000L
        if (nowMillis - graceBase < graceMillis) {
            return DecayCalculation(false, 0.0, false)
        }

        val intervalMillis = config.decayIntervalHours.coerceAtLeast(1) * 3600_000L
        val lastDecay = cycle.lastDecayAt ?: 0L
        if (lastDecay > 0L && nowMillis - lastDecay < intervalMillis) {
            return DecayCalculation(false, 0.0, false)
        }

        val calmBonus = prev7dViolationSum > 0.0 && recent7dViolationSum <= prev7dViolationSum * 0.5
        val multiplier = if (calmBonus) config.decayCalmBonusMultiplier.coerceAtLeast(1.0) else 1.0
        val rawAmount = (config.decayAmount * multiplier).coerceAtMost(cycle.currentScore)
        return DecayCalculation(
            shouldDecay = rawAmount > 0.0,
            decayAmount = rawAmount,
            calmBonusApplied = calmBonus,
        )
    }

    private suspend fun processAllGuilds(jda: JDA) {
        val now = System.currentTimeMillis()
        val enabledGuildIds = guildFeatureService.listEnabledGuilds(FeatureKey.WATCH_SYSTEM)
        for (guildId in enabledGuildIds) {
            val guild = jda.getGuildById(guildId) ?: continue
            val config = watchConfigService.getOrCreateConfig(guildId)
            if (!config.decayEnabled) continue

            val activeCycles = watchCycleService.listActiveCyclesWithPositiveScore(guildId)
            for (cycle in activeCycles) {
                val target = watchConfigService.getTargetById(cycle.targetId) ?: continue
                if (!target.active) continue

                val recent7d = watchCycleService.sumAiViolationDeltaBetween(
                    target.id,
                    now - SEVEN_DAYS_MILLIS,
                    now,
                )
                val prev7d = watchCycleService.sumAiViolationDeltaBetween(
                    target.id,
                    now - 2 * SEVEN_DAYS_MILLIS,
                    now - SEVEN_DAYS_MILLIS,
                )

                val calc = calculateDecay(cycle, config, recent7d, prev7d, now)
                if (!calc.shouldDecay) continue

                val reason = if (calc.calmBonusApplied) {
                    String.format(
                        Locale.US,
                        "時間経過による減衰（落ち着きボーナス %.1fx 適用）",
                        config.decayCalmBonusMultiplier,
                    )
                } else {
                    "無違反継続による時間減衰"
                }

                WatchOrchestrator.applyScoreChange(
                    guild = guild,
                    target = target,
                    eventType = WatchScoreEventType.DECAY,
                    delta = -calc.decayAmount,
                    category = "DECAY",
                    reason = reason,
                )
            }
        }
    }
}
