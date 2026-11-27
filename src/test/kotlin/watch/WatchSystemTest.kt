package jp.simplespace.watch

import jp.simplespace.ai.ContextMessage
import jp.simplespace.ai.GeminiClientSettings
import jp.simplespace.ai.GeminiModerationClient
import jp.simplespace.ai.ModerationAttachment
import jp.simplespace.ai.ModerationEvaluationRequest
import jp.simplespace.ai.ModerationEvaluationResult
import jp.simplespace.ai.ModerationPromptBuilder
import jp.simplespace.db.AiFeedbackVerdict
import jp.simplespace.db.AiRuleType
import jp.simplespace.db.AiWeightHint
import jp.simplespace.db.WatchAiFeedback
import jp.simplespace.db.WatchAiRule
import jp.simplespace.db.WatchCycle
import jp.simplespace.db.WatchCycleStatus
import jp.simplespace.db.WatchGuildConfig
import jp.simplespace.db.WatchScoreEvent
import jp.simplespace.db.WatchScoreEventType
import jp.simplespace.db.WatchVoteTally
import jp.simplespace.watch.render.ColorGradient
import jp.simplespace.watch.render.FontLoader
import jp.simplespace.watch.render.GraphRange
import jp.simplespace.watch.render.HistoryGraphRenderer
import jp.simplespace.watch.render.ProgressBarRenderer
import java.io.ByteArrayInputStream
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class WatchSystemTest {

    @Test
    fun testColorGradientStopsAndInterpolation() {
        val c0 = ColorGradient.colorForScore(0.0)
        val c50 = ColorGradient.colorForScore(50.0)
        val c100 = ColorGradient.colorForScore(100.0)

        assertEquals(0x2E, c0.red)
        assertEquals(0xCC, c0.green)
        assertEquals(0x8B, c100.red)
        assertEquals(0x00, c100.green)
        assertTrue(c50.red > c0.red)
    }

    @Test
    fun testProgressBarAndHistoryGraphRenderValidPng() {
        assertTrue(FontLoader.supportsJapanese(FontLoader.regular(16f)))
        assertTrue(FontLoader.supportsJapanese(FontLoader.bold(20f)))

        val barPng = ProgressBarRenderer.render(
            displayName = "T氏テスト",
            cycleNumber = 2,
            score = 72.5,
            status = WatchCycleStatus.ACTIVE,
            footerText = "直近: +8.0% (煽り発言)",
        )
        val barImg = ImageIO.read(ByteArrayInputStream(barPng))
        assertNotNull(barImg)
        assertTrue(barImg.width > 0 && barImg.height > 0)

        val now = System.currentTimeMillis()
        val sampleEvents = listOf(
            WatchScoreEvent(1, 1, 1, "g1", "u1", WatchScoreEventType.CYCLE_START, 0.0, 0.0, null, "開始", null, null, null, null, null, now - 5 * 3600_000L),
            WatchScoreEvent(2, 1, 1, "g1", "u1", WatchScoreEventType.AI_VIOLATION, 35.0, 35.0, "INSULT", "暴言", null, null, null, null, null, now - 3 * 3600_000L),
            WatchScoreEvent(3, 1, 1, "g1", "u1", WatchScoreEventType.DECAY, -5.0, 30.0, "DECAY", "減衰", null, null, null, null, null, now - 1 * 3600_000L),
        )
        val graphPng = HistoryGraphRenderer.render(
            displayName = "T氏テスト",
            range = GraphRange.WEEK_1,
            events = sampleEvents,
            initialScoreBeforeRange = 0.0,
            nowMillis = now,
        )
        val graphImg = ImageIO.read(ByteArrayInputStream(graphPng))
        assertNotNull(graphImg)
        assertTrue(graphImg.width > 0 && graphImg.height > 0)
    }

    @Test
    fun testScoreDeltaSensitivityAndCap() {
        val violation = ModerationEvaluationResult(isViolation = true, baseDelta = 15.0)
        assertEquals(15.0, violation.computeFinalDelta(1.0, 25.0))
        assertEquals(25.0, violation.computeFinalDelta(2.0, 25.0)) // cap applied
        assertEquals(7.5, violation.computeFinalDelta(0.5, 25.0))

        val clean = ModerationEvaluationResult(isViolation = false, baseDelta = 10.0)
        assertEquals(0.0, clean.computeFinalDelta(1.0, 25.0))
    }

    @Test
    fun testScoreDecayWithAndWithoutCalmBonus() {
        val now = 1_800_000_000_000L
        val config = WatchGuildConfig(
            guildId = "g1",
            decayEnabled = true,
            decayGraceHours = 24,
            decayIntervalHours = 6,
            decayAmount = 4.0,
            decayCalmBonusMultiplier = 1.5,
        )
        val cycleTooRecent = WatchCycle(
            id = 1, targetId = 1, guildId = "g1", userId = "u1", cycleNumber = 1,
            status = WatchCycleStatus.ACTIVE, currentScore = 40.0,
            statusChannelId = null, statusMessageId = null,
            banTriggerType = null, banTriggerDetail = null, banDeadlineAt = null,
            lastWordsContent = null, lastWordsMessageId = null,
            lastViolationAt = now - 10L * 3600_000L, lastDecayAt = null,
            startedAt = now - 48L * 3600_000L, bannedAt = null, unbannedAt = null, rejoinedAt = null,
        )
        assertFalse(ScoreDecayScheduler.calculateDecay(cycleTooRecent, config, 0.0, 0.0, now).shouldDecay)

        val cycleReady = cycleTooRecent.copy(lastViolationAt = now - 30L * 3600_000L)
        val normalDecay = ScoreDecayScheduler.calculateDecay(cycleReady, config, 10.0, 10.0, now)
        assertTrue(normalDecay.shouldDecay)
        assertFalse(normalDecay.calmBonusApplied)
        assertEquals(4.0, normalDecay.decayAmount)

        val calmDecay = ScoreDecayScheduler.calculateDecay(cycleReady, config, 4.0, 20.0, now)
        assertTrue(calmDecay.shouldDecay)
        assertTrue(calmDecay.calmBonusApplied)
        assertEquals(6.0, calmDecay.decayAmount)
    }

    @Test
    fun testVotePassConditions() {
        assertTrue(VoteBanService.isVotePassed(WatchVoteTally(yesCount = 3, noCount = 2), minYesVotes = 3, passRatio = 0.6))
        assertFalse(VoteBanService.isVotePassed(WatchVoteTally(yesCount = 2, noCount = 0), minYesVotes = 3, passRatio = 0.6))
        assertFalse(VoteBanService.isVotePassed(WatchVoteTally(yesCount = 3, noCount = 3), minYesVotes = 3, passRatio = 0.6))
    }

    @Test
    fun testPromptBuilderIncludesRulesFeedbackAndContext() {
        val req = ModerationEvaluationRequest(
            guildName = "身内鯖",
            channelName = "general",
            targetUserId = "u_target",
            targetDisplayName = "T氏",
            personaNote = "口が悪いが冗談も多い",
            rules = listOf(
                WatchAiRule(1, "g1", null, AiRuleType.VIOLATION, "人格否定は重く判定", AiWeightHint.HIGH, "admin", 0L),
                WatchAiRule(2, "g1", null, AiRuleType.ALLOW, "ゲーム中の草・ドンマイは許容", AiWeightHint.LOW, "admin", 0L),
            ),
            feedbacks = listOf(
                WatchAiFeedback(1, "g1", 1, 10, "雑魚すぎ草", null, 8.0, 0.0, AiFeedbackVerdict.FALSE_POSITIVE, "身内ネタなのでセーフ", "admin", 0L),
            ),
            contextMessages = listOf(
                ContextMessage("u_other", "Aさん", false, "また負けたわｗ", 100L),
            ),
            targetMessages = listOf("お前本当に使えないな"),
        )
        val prompt = ModerationPromptBuilder.buildUserPrompt(req)
        assertTrue(prompt.contains("人格否定は重く判定"))
        assertTrue(prompt.contains("ゲーム中の草・ドンマイは許容"))
        assertTrue(prompt.contains("雑魚すぎ草"))
        assertTrue(prompt.contains("author_id=u_other"))
        assertTrue(prompt.contains("お前本当に使えないな"))
    }

    @Test
    fun testGeminiResponseJsonParser() {
        val client = GeminiModerationClient(GeminiClientSettings(provider = "gemini_api", googleApiKey = null))
        val parsed = client.parseResponseJson(
            """
            ```json
            {
              "is_violation": true,
              "base_delta": 12.5,
              "severity": "MEDIUM",
              "category": "INSULT",
              "reason_ja": "暴言を検知",
              "victim_user_id": "999",
              "suggest_ban_ticket": true,
              "ticket_reason_ja": "特定メンバーへの激しい暴言"
            }
            ```
            """.trimIndent()
        )
        assertTrue(parsed.isViolation)
        assertEquals(12.5, parsed.baseDelta)
        assertEquals("999", parsed.victimUserId)
        assertTrue(parsed.suggestBanTicket)
    }

    @Test
    fun testMessagePrefilter() {
        val buffer = MessageBatchBuffer(
            aiClientProvider = { null },
            onTicketSuggested = { _, _, _, _, _, _ -> },
        )
        assertTrue(buffer.shouldIgnoreText(""))
        assertTrue(buffer.shouldIgnoreText("草"))
        assertTrue(buffer.shouldIgnoreText("https://example.com/test"))
        assertFalse(buffer.shouldIgnoreText("おいマジでいい加減にしろ"))

        // テキストが空でも添付ファイルやスタンプがあれば審査対象とする
        assertTrue(buffer.shouldIgnoreMessage("", hasAttachments = false, hasStickers = false))
        assertFalse(buffer.shouldIgnoreMessage("", hasAttachments = true, hasStickers = false))
        assertFalse(buffer.shouldIgnoreMessage("草", hasAttachments = true, hasStickers = false))
        assertFalse(buffer.shouldIgnoreMessage("", hasAttachments = false, hasStickers = true))

        // MIME判定
        assertEquals("image/png", MessageBatchBuffer.resolveSupportedMimeType("image/png; charset=utf-8", "test.png"))
        assertEquals("image/jpeg", MessageBatchBuffer.resolveSupportedMimeType(null, "photo.JPG"))
        assertEquals("application/pdf", MessageBatchBuffer.resolveSupportedMimeType("application/pdf", "doc.pdf"))
        assertEquals(null, MessageBatchBuffer.resolveSupportedMimeType("application/zip", "archive.zip"))
    }

    @Test
    fun testMultimodalAttachmentContentBuilder() {
        val client = GeminiModerationClient(GeminiClientSettings(provider = "gemini_api", googleApiKey = null))
        val fakePngBytes = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47)
        val req = ModerationEvaluationRequest(
            guildName = "身内鯖",
            channelName = "general",
            targetUserId = "u1",
            targetDisplayName = "T氏",
            personaNote = null,
            rules = emptyList(),
            feedbacks = emptyList(),
            contextMessages = emptyList(),
            targetMessages = listOf("[画像: toxic.png]"),
            attachments = listOf(
                ModerationAttachment("toxic.png", "image/png", fakePngBytes.size.toLong(), bytes = fakePngBytes),
                ModerationAttachment("huge.mp4", "video/mp4", 20_000_000L, bytes = null, skippedReason = "上限超過"),
            ),
        )
        val prompt = ModerationPromptBuilder.buildUserPrompt(req)
        assertTrue(prompt.contains("toxic.png"))
        assertTrue(prompt.contains("未解析: 上限超過"))

        val content = client.buildUserContent(req)
        val parts = content.parts().orElse(emptyList())
        // テキストパート1つ + バイナリがある添付1つ = 2パート
        assertEquals(2, parts.size)
        assertTrue(parts[1].inlineData().isPresent)
        assertEquals("image/png", parts[1].inlineData().get().mimeType().orElse(""))
    }
}
