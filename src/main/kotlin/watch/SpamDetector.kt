package jp.simplespace.watch

import java.util.concurrent.ConcurrentHashMap

data class SpamObservation(
    val messageId: String,
    val summary: String,
    val normalizedKey: String,
    val mentionCount: Int,
    val newlineCount: Int,
    val maxRepeatedCharRun: Int,
    val isMediaOrStickerOnly: Boolean,
    val timestampMillis: Long,
)

data class SpamDetectionResult(
    val isSuspicious: Boolean,
    val isSevereFlood: Boolean,
    val reasons: List<String>,
    val recentSkippedSummaries: List<String> = emptyList(),
) {
    fun toPromptSummary(): String? {
        if (!isSuspicious || reasons.isEmpty()) return null
        return reasons.joinToString(" / ")
    }
}

class SpamDetector {
    private class UserChannelWindow {
        private val entries = ArrayDeque<SpamObservation>()
        private val skippedShortEntries = ArrayDeque<SpamObservation>()

        @Synchronized
        fun record(obs: SpamObservation): SpamDetectionResult {
            val cutoff60s = obs.timestampMillis - WINDOW_60S_MILLIS
            while (entries.isNotEmpty() && entries.first().timestampMillis < cutoff60s) {
                entries.removeFirst()
            }
            while (skippedShortEntries.isNotEmpty() && skippedShortEntries.first().timestampMillis < cutoff60s) {
                skippedShortEntries.removeFirst()
            }

            entries.addLast(obs)
            if (entries.size > MAX_WINDOW_ENTRIES) {
                entries.removeFirst()
            }

            val count10s = entries.count { obs.timestampMillis - it.timestampMillis <= WINDOW_10S_MILLIS }
            val count30s = entries.count { obs.timestampMillis - it.timestampMillis <= WINDOW_30S_MILLIS }
            val dup60s = if (obs.normalizedKey.isNotEmpty()) {
                entries.count { it.normalizedKey == obs.normalizedKey }
            } else {
                0
            }
            val mentions30s = entries
                .filter { obs.timestampMillis - it.timestampMillis <= WINDOW_30S_MILLIS }
                .sumOf { it.mentionCount }
            val media15s = entries.count {
                it.isMediaOrStickerOnly && obs.timestampMillis - it.timestampMillis <= WINDOW_15S_MILLIS
            }

            val reasons = mutableListOf<String>()
            var severe = false

            if (count10s >= BURST_10S_THRESHOLD) {
                reasons += "直近10秒で${count10s}件の高速連投"
                if (count10s >= SEVERE_BURST_10S_THRESHOLD) severe = true
            }
            if (count30s >= BURST_30S_THRESHOLD) {
                reasons += "直近30秒で${count30s}件の大量連投"
                if (count30s >= SEVERE_BURST_30S_THRESHOLD) severe = true
            }
            if (dup60s >= DUPLICATE_60S_THRESHOLD) {
                reasons += "直近60秒で同一/酷似内容の${dup60s}回繰り返し"
                if (dup60s >= SEVERE_DUPLICATE_60S_THRESHOLD) severe = true
            }
            if (obs.mentionCount >= MENTION_SINGLE_THRESHOLD || mentions30s >= MENTION_30S_THRESHOLD) {
                reasons += "過度なメンション（今回${obs.mentionCount}件 / 30秒計${mentions30s}件）"
                severe = true
            }
            if (obs.newlineCount >= NEWLINE_THRESHOLD) {
                reasons += "大量の改行（${obs.newlineCount}行）による画面埋め尽くし"
            }
            if (obs.maxRepeatedCharRun >= REPEATED_CHAR_THRESHOLD) {
                reasons += "同一文字の異常連打（${obs.maxRepeatedCharRun}文字連続）"
            }
            if (media15s >= MEDIA_15S_THRESHOLD) {
                reasons += "直近15秒で画像・スタンプ等の${media15s}連続投稿"
            }

            val suspicious = reasons.isNotEmpty()
            val flushedSkipped = if (suspicious && skippedShortEntries.isNotEmpty()) {
                val list = skippedShortEntries.map { it.summary }
                skippedShortEntries.clear()
                list
            } else {
                emptyList()
            }

            return SpamDetectionResult(
                isSuspicious = suspicious,
                isSevereFlood = severe,
                reasons = reasons,
                recentSkippedSummaries = flushedSkipped,
            )
        }

        @Synchronized
        fun rememberSkipped(obs: SpamObservation) {
            skippedShortEntries.addLast(obs)
            if (skippedShortEntries.size > 10) {
                skippedShortEntries.removeFirst()
            }
        }
    }

    private val windows = ConcurrentHashMap<String, UserChannelWindow>()

    fun observe(
        guildId: String,
        channelId: String,
        userId: String,
        messageId: String,
        rawText: String,
        summary: String,
        mentionCount: Int,
        isMediaOrStickerOnly: Boolean,
        nowMillis: Long = System.currentTimeMillis(),
    ): Pair<SpamObservation, SpamDetectionResult> {
        val obs = SpamObservation(
            messageId = messageId,
            summary = summary,
            normalizedKey = normalizeForDuplicate(rawText, summary),
            mentionCount = mentionCount,
            newlineCount = rawText.count { it == '\n' },
            maxRepeatedCharRun = maxRepeatedCharRun(rawText),
            isMediaOrStickerOnly = isMediaOrStickerOnly,
            timestampMillis = nowMillis,
        )
        val window = windows.computeIfAbsent("$guildId:$channelId:$userId") { UserChannelWindow() }
        return obs to window.record(obs)
    }

    fun rememberSkipped(guildId: String, channelId: String, userId: String, obs: SpamObservation) {
        windows["$guildId:$channelId:$userId"]?.rememberSkipped(obs)
    }

    companion object {
        private const val WINDOW_10S_MILLIS = 10_000L
        private const val WINDOW_15S_MILLIS = 15_000L
        private const val WINDOW_30S_MILLIS = 30_000L
        private const val WINDOW_60S_MILLIS = 60_000L
        private const val MAX_WINDOW_ENTRIES = 40

        internal const val BURST_10S_THRESHOLD = 6
        internal const val SEVERE_BURST_10S_THRESHOLD = 9
        internal const val BURST_30S_THRESHOLD = 10
        internal const val SEVERE_BURST_30S_THRESHOLD = 15
        internal const val DUPLICATE_60S_THRESHOLD = 3
        internal const val SEVERE_DUPLICATE_60S_THRESHOLD = 5
        internal const val MENTION_SINGLE_THRESHOLD = 5
        internal const val MENTION_30S_THRESHOLD = 8
        internal const val NEWLINE_THRESHOLD = 20
        internal const val REPEATED_CHAR_THRESHOLD = 25
        internal const val MEDIA_15S_THRESHOLD = 4

        internal fun normalizeForDuplicate(rawText: String, summary: String): String {
            val base = rawText.ifBlank { summary }
            return base
                .lowercase()
                .replace(Regex("""[\s\u3000]+"""), "")
                .take(120)
        }

        internal fun maxRepeatedCharRun(text: String): Int {
            if (text.isEmpty()) return 0
            var maxRun = 1
            var curRun = 1
            var prev: Char? = null
            for (ch in text) {
                if (ch.isWhitespace()) {
                    prev = null
                    curRun = 1
                    continue
                }
                if (prev == ch) {
                    curRun++
                    if (curRun > maxRun) maxRun = curRun
                } else {
                    prev = ch
                    curRun = 1
                }
            }
            return maxRun
        }
    }
}
