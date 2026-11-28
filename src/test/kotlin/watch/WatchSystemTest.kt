package jp.simplespace.watch

import jp.simplespace.ai.AppealReviewRequest
import jp.simplespace.ai.AppealReviewResult
import jp.simplespace.ai.ContextMessage
import jp.simplespace.ai.GeminiClientSettings
import jp.simplespace.ai.GeminiModerationClient
import jp.simplespace.ai.GeminiTaskOverrideSettings
import jp.simplespace.ai.ModerationAttachment
import jp.simplespace.ai.ModerationEvaluationRequest
import jp.simplespace.ai.ModerationEvaluationResult
import jp.simplespace.ai.ModerationPromptBuilder
import jp.simplespace.db.AiFeedbackVerdict
import jp.simplespace.db.AiRuleType
import jp.simplespace.db.AiTaskType
import jp.simplespace.db.AiWeightHint
import jp.simplespace.db.AppealDecision
import jp.simplespace.db.ReportPunishmentType
import jp.simplespace.db.TicketIssueSource
import jp.simplespace.db.TicketStatus
import jp.simplespace.db.WatchAiFeedback
import jp.simplespace.db.WatchAiRule
import jp.simplespace.db.WatchBanTicket
import jp.simplespace.db.WatchCycle
import jp.simplespace.db.WatchCycleStatus
import jp.simplespace.db.WatchGuildConfig
import jp.simplespace.db.WatchRoleCounter
import jp.simplespace.db.WatchScoreEvent
import jp.simplespace.db.WatchScoreEventType
import jp.simplespace.db.WatchVoteTally
import jp.simplespace.db.WatchVoteTicketService
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
                ContextMessage(
                    authorId = "u_other",
                    authorName = "Aさん",
                    isTarget = false,
                    content = "また負けたわｗ",
                    timestampEpochSec = 100L,
                    position = ContextMessage.POSITION_BEFORE,
                ),
                ContextMessage(
                    authorId = "u_other",
                    authorName = "Aさん",
                    isTarget = false,
                    content = "さすがにそれは言い過ぎだろ",
                    timestampEpochSec = 115L,
                    position = ContextMessage.POSITION_DURING_OR_AFTER,
                    replyToAuthorName = "T氏",
                    replyToExcerpt = "お前本当に使えないな",
                    reactionsSummary = "😡x2",
                ),
            ),
            targetMessages = listOf("お前本当に使えないな"),
        )
        val prompt = ModerationPromptBuilder.buildUserPrompt(req)
        assertTrue(prompt.contains("人格否定は重く判定"))
        assertTrue(prompt.contains("ゲーム中の草・ドンマイは許容"))
        assertTrue(prompt.contains("雑魚すぎ草"))
        assertTrue(prompt.contains("author_id=u_other"))
        assertTrue(prompt.contains("お前本当に使えないな"))
        assertTrue(prompt.contains("発言の間〜直後の他メンバーの反応"))
        assertTrue(prompt.contains("[返信先: T氏「お前本当に使えないな」]"))
        assertTrue(prompt.contains("[リアクション: 😡x2]"))
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

    @Test
    fun testAppealSafeAdjustmentGuardsAndParsing() {
        val originalEvent = WatchScoreEvent(
            id = 42, cycleId = 1, targetId = 1, guildId = "g1", userId = "u1",
            eventType = WatchScoreEventType.AI_VIOLATION, delta = 8.0, scoreAfter = 28.0,
            category = "INSULT", reason = "暴言", sourceChannelId = "c1", sourceMessageId = "m1",
            sourceMessageExcerpt = "バカ野郎", aiRawJson = null, actorUserId = null, createdAt = 1000L,
        )

        // 1. 元の違反加算(+8.0)を超える減額(-50.0)をAIが返しても、-8.0(撤回)にクランプされる
        val overReduce = AppealReviewResult(intent = "APPEAL", targetEventId = 42, decision = "REDUCED", scoreAdjustment = -50.0)
        val (d1, adj1) = overReduce.computeSafeAdjustment(originalEvent, alreadyReviewed = false, maxDeltaPerEval = 25.0)
        assertEquals(AppealDecision.REVOKED, d1)
        assertEquals(-8.0, adj1)

        // 2. 既に審査済みのイベントへの減額は強制的にUPHELD(0.0)へブロック
        val (d2, adj2) = overReduce.computeSafeAdjustment(originalEvent, alreadyReviewed = true, maxDeltaPerEval = 25.0)
        assertEquals(AppealDecision.UPHELD, d2)
        assertEquals(0.0, adj2)

        // 3. 棄却時はスコア変更なし
        val upheld = AppealReviewResult(intent = "APPEAL", targetEventId = 42, decision = "UPHELD", scoreAdjustment = -5.0)
        val (d3, adj3) = upheld.computeSafeAdjustment(originalEvent, alreadyReviewed = false, maxDeltaPerEval = 25.0)
        assertEquals(AppealDecision.UPHELD, d3)
        assertEquals(0.0, adj3)

        // 4. 加重ペナルティは最大10.0%にキャップされる
        val escalated = AppealReviewResult(intent = "APPEAL", targetEventId = 42, decision = "ESCALATED", scoreAdjustment = 30.0)
        val (d4, adj4) = escalated.computeSafeAdjustment(originalEvent, alreadyReviewed = false, maxDeltaPerEval = 25.0)
        assertEquals(AppealDecision.ESCALATED, d4)
        assertEquals(10.0, adj4)

        // 5. #ID抽出とJSONパースとプロンプト組み立て
        assertEquals(42L, WatchAppealService.extractMentionedEventId("さっきの #42 はゲームのセリフなんだけど"))
        assertEquals(null, WatchAppealService.extractMentionedEventId("なんで増えたの？"))

        val client = GeminiModerationClient(GeminiClientSettings(provider = "gemini_api", googleApiKey = null))
        val parsed = client.parseAppealResponseJson(
            """{"intent":"APPEAL","target_event_id":42,"decision":"REDUCED","score_adjustment":-4.0,"reply_ja":"文脈を確認し一部軽減します","audit_reason_ja":"ゲーム内発言と確認"}"""
        )
        assertEquals("APPEAL", parsed.intent)
        assertEquals(42L, parsed.targetEventId)
        assertEquals(-4.0, parsed.scoreAdjustment)

        val prompt = ModerationPromptBuilder.buildAppealUserPrompt(
            AppealReviewRequest(
                guildName = "身内鯖",
                channelName = "監視ch",
                targetUserId = "u1",
                targetDisplayName = "T氏",
                currentScore = 28.0,
                personaNote = null,
                rules = emptyList(),
                feedbacks = emptyList(),
                recentViolations = listOf(originalEvent),
                alreadyReviewedEventIds = setOf(42L),
                contextMessages = emptyList(),
                appealMessage = "#42 は冗談だったから消して",
            )
        )
        assertTrue(prompt.contains("event_id=#42"))
        assertTrue(prompt.contains("already_reviewed=true"))
    }

    @Test
    fun testAdaptiveBatchWindowOptimization() {
        // 1. サンプル不足時はデフォルト値 (12秒 / 45秒)
        val initial = MessageBatchBuffer.computeAdaptiveWindow(listOf(3_000L, 4_000L))
        assertEquals(MessageBatchBuffer.DEFAULT_DEBOUNCE_MILLIS, initial.debounceMillis)
        assertEquals(MessageBatchBuffer.DEFAULT_MAX_WAIT_MILLIS, initial.maxWaitMillis)

        // 2. 早打ち連投ユーザー (1〜3秒間隔) -> 下限 6秒へ自動短縮
        val fast = MessageBatchBuffer.computeAdaptiveWindow(listOf(1_500L, 2_000L, 2_500L, 1_800L))
        assertEquals(MessageBatchBuffer.MIN_DEBOUNCE_MILLIS, fast.debounceMillis)
        assertEquals(MessageBatchBuffer.MIN_MAX_WAIT_MILLIS, fast.maxWaitMillis)

        // 3. 中速ユーザー (8〜12秒間隔) -> P75(12秒) * 1.35 = 16.2秒前後へ最適化
        val medium = MessageBatchBuffer.computeAdaptiveWindow(listOf(8_000L, 10_000L, 12_000L, 15_000L))
        assertTrue(medium.debounceMillis in 14_000L..20_000L)
        assertTrue(medium.maxWaitMillis > medium.debounceMillis)

        // 4. じっくり入力ユーザー (25〜40秒間隔) -> 上限 30秒 / 最大90秒にクランプ
        val slow = MessageBatchBuffer.computeAdaptiveWindow(listOf(25_000L, 30_000L, 35_000L, 40_000L))
        assertEquals(MessageBatchBuffer.MAX_DEBOUNCE_MILLIS, slow.debounceMillis)
        assertEquals(MessageBatchBuffer.MAX_MAX_WAIT_MILLIS, slow.maxWaitMillis)

        // 5. トラッカーは2分超の空き（別会話）を学習サンプルから除外する
        val tracker = MessageBatchBuffer.UserCadenceTracker()
        val t0 = 1_000_000L
        tracker.recordAndGetWindow("ch1", t0)
        tracker.recordAndGetWindow("ch1", t0 + 10_000L)  // sample 1: 10s
        tracker.recordAndGetWindow("ch1", t0 + 20_000L)  // sample 2: 10s
        tracker.recordAndGetWindow("ch1", t0 + 300_000L) // 280s空き -> 除外
        val w = tracker.recordAndGetWindow("ch1", t0 + 310_000L) // sample 3: 10s
        assertEquals(3, w.sampleCount)
        assertEquals(13_500L, w.debounceMillis)
    }

    @Test
    fun testGlobalVsPersonalBanTicket() {
        val globalTicket = WatchBanTicket(
            id = 1, guildId = "g1", targetId = 1,
            holderUserId = WatchVoteTicketService.GLOBAL_HOLDER_ID,
            issuedByUserId = "admin", issueSource = TicketIssueSource.MANUAL,
            reason = "共有処刑権", sourceScoreEventId = null,
            status = TicketStatus.ACTIVE, expiresAt = null, usedCycleId = null,
            createdAt = 0L, usedAt = null,
        )
        val personalTicket = globalTicket.copy(id = 2, holderUserId = "1234567890")
        assertTrue(globalTicket.isGlobal)
        assertFalse(personalTicket.isGlobal)
    }

    @Test
    fun testThinkingLevelAndOutputTokenLimits() {
        val client = GeminiModerationClient(GeminiClientSettings(provider = "gemini_api", googleApiKey = null))

        val evalCfg = client.buildEvaluationConfig()
        assertEquals(GeminiClientSettings.DEFAULT_EVAL_MAX_OUTPUT_TOKENS, evalCfg.maxOutputTokens().orElse(0))
        assertEquals("LOW", evalCfg.thinkingConfig().get().thinkingLevel().get().toString())

        val appealCfg = client.buildAppealConfig()
        assertEquals(GeminiClientSettings.DEFAULT_APPEAL_MAX_OUTPUT_TOKENS, appealCfg.maxOutputTokens().orElse(0))
        assertEquals("MEDIUM", appealCfg.thinkingConfig().get().thinkingLevel().get().toString())

        val sysPrompt = ModerationPromptBuilder.buildSystemInstruction()
        assertTrue(sysPrompt.contains("空配列"))
        assertTrue(sysPrompt.contains("30文字以内"))
    }

    @Test
    fun testAiRuleEditModalBuildsWithPrefilledValues() {
        val rule = WatchAiRule(
            id = 7,
            guildId = "g1",
            targetId = null,
            ruleType = AiRuleType.ALLOW,
            description = "ゲーム中の草・ドンマイは許容する",
            weightHint = AiWeightHint.LOW,
            createdBy = "admin",
            createdAt = 0L,
        )
        val modal = jp.simplespace.discord.commands.watch.WatchAiRuleUi.buildEditModal(rule)
        assertEquals("watch_rule_edit_modal:7", modal.id)
        assertEquals(3, modal.components.size)
    }

    @Test
    fun testSpamDetectorCatchesFloodDuplicatesMentionsAndWalls() {
        val detector = SpamDetector()
        val base = 2_000_000L

        // 1. 単発の短文はスパムではない
        val (obs1, r1) = detector.observe("g1", "c1", "u1", "m1", "あ", "あ", 0, false, base)
        assertFalse(r1.isSuspicious)
        detector.rememberSkipped("g1", "c1", "u1", obs1)

        val (obs2, r2) = detector.observe("g1", "c1", "u1", "m2", "あ", "あ", 0, false, base + 2_000L)
        assertFalse(r2.isSuspicious)
        detector.rememberSkipped("g1", "c1", "u1", obs2)

        // 3回目で同一文言連投を検知し、スキップしていた過去2件も回収される
        val (_, r3) = detector.observe("g1", "c1", "u1", "m3", "あ", "あ", 0, false, base + 4_000L)
        assertTrue(r3.isSuspicious)
        assertEquals(2, r3.recentSkippedSummaries.size)
        assertTrue(r3.toPromptSummary()!!.contains("繰り返し"))

        // 2. メンション爆撃
        val (_, mentionRes) = detector.observe("g1", "c2", "u2", "m10", "@a @b @c @d @e", "@a @b @c @d @e", 5, false, base)
        assertTrue(mentionRes.isSuspicious)
        assertTrue(mentionRes.isSevereFlood)

        // 3. 大量改行 & 同一文字連打
        val wallText = (1..25).joinToString("\n") { "x" }
        val (_, wallRes) = detector.observe("g1", "c3", "u3", "m20", wallText, wallText, 0, false, base)
        assertTrue(wallRes.isSuspicious)
        assertTrue(wallRes.toPromptSummary()!!.contains("改行"))

        val repeatText = "あ".repeat(30)
        val (_, repeatRes) = detector.observe("g1", "c4", "u4", "m30", repeatText, repeatText, 0, false, base)
        assertTrue(repeatRes.isSuspicious)
        assertTrue(repeatRes.toPromptSummary()!!.contains("連打"))

        // 4. 10秒以内の6連投フラッド
        var lastBurstRes = r1
        for (i in 1..6) {
            lastBurstRes = detector.observe("g1", "c5", "u5", "mb$i", "発言$i", "発言$i", 0, false, base + i * 1_000L).second
        }
        assertTrue(lastBurstRes.isSuspicious)
        assertTrue(lastBurstRes.toPromptSummary()!!.contains("高速連投"))
    }

    @Test
    fun testUrlImageExtractionAndSsrfGuard() {
        val urls = UrlImageFetcher.extractDirectImageUrls(
            "これ見て https://example.com/a.png と https://pbs.twimg.com/media/xyz?format=jpg&name=large あと https://example.com/article"
        )
        assertEquals(2, urls.size)
        assertTrue(urls.any { it.contains("a.png") })
        assertTrue(urls.any { it.contains("pbs.twimg.com") })

        // SSRF 防止チェック: プライベート/ループバック/メタデータIPはすべて拒否
        assertFalse(UrlImageFetcher.isPublicSafeAddress(java.net.InetAddress.getByName("127.0.0.1")))
        assertFalse(UrlImageFetcher.isPublicSafeAddress(java.net.InetAddress.getByName("10.0.0.5")))
        assertFalse(UrlImageFetcher.isPublicSafeAddress(java.net.InetAddress.getByName("192.168.1.10")))
        assertFalse(UrlImageFetcher.isPublicSafeAddress(java.net.InetAddress.getByName("169.254.169.254")))
        assertFalse(UrlImageFetcher.isPublicSafeAddress(java.net.InetAddress.getByName("100.64.0.1")))
        assertFalse(UrlImageFetcher.isPublicSafeAddress(java.net.InetAddress.getByName("::1")))
        assertFalse(UrlImageFetcher.isPublicSafeAddress(java.net.InetAddress.getByName("fc00::1")))

        // パブリックIPは許可
        assertTrue(UrlImageFetcher.isPublicSafeAddress(java.net.InetAddress.getByName("8.8.8.8")))
    }

    @Test
    fun testRoleCounterInferenceAndIncrement() {
        val inferred = WatchRoleCounter.inferFromRoleName("sasalive 9期生")
        assertNotNull(inferred)
        assertEquals("sasalive {n}期生", inferred.first)
        assertEquals(9, inferred.second)

        val counter = WatchRoleCounter(
            id = 1, guildId = "g1", targetId = 1, roleId = "r1",
            nameTemplate = inferred.first, currentNumber = inferred.second, step = 1, updatedAt = 0L,
        )
        assertEquals("sasalive 9期生", counter.renderName())
        assertEquals("sasalive 10期生", counter.renderName(counter.nextNumber()))

        // 複数数値がある場合は末尾の数値を採用
        val multi = WatchRoleCounter.inferFromRoleName("2026年 sasalive 9期生")
        assertNotNull(multi)
        assertEquals("2026年 sasalive {n}期生", multi.first)
        assertEquals(9, multi.second)

        // 数値が無いロール名は null
        assertEquals(null, WatchRoleCounter.inferFromRoleName("一般メンバー"))
    }

    @Test
    fun testMultiViolationResponseParsingAndScoring() {
        val client = GeminiModerationClient(GeminiClientSettings(provider = "gemini_api", googleApiKey = null))
        val parsed = client.parseResponseJson(
            """
            {
              "violations": [
                {
                  "message_indices": [1, 2],
                  "base_delta": 10.0,
                  "severity": "MEDIUM",
                  "category": "INSULT",
                  "reason_ja": "特定メンバーへの暴言"
                },
                {
                  "message_indices": [4],
                  "base_delta": 6.0,
                  "severity": "LOW",
                  "category": "SPAM",
                  "reason_ja": "無意味な連投スパム"
                }
              ],
              "victim_user_id": null,
              "suggest_ban_ticket": false,
              "ticket_reason_ja": null
            }
            """.trimIndent()
        )
        val violations = parsed.resolvedViolations()
        assertEquals(2, violations.size)
        assertEquals(listOf(1, 2), violations[0].messageIndices)
        assertEquals(listOf(4), violations[1].messageIndices)
        assertEquals(16.0, parsed.computeFinalDelta(sensitivity = 1.0, maxDeltaPerEval = 25.0))
        // 1件あたりの上限はそれぞれ個別に適用される（10*2=20, 6*2=12 -> 合計32）
        assertEquals(32.0, parsed.computeFinalDelta(sensitivity = 2.0, maxDeltaPerEval = 25.0))

        // 違反0件の空配列
        val clean = client.parseResponseJson("""{"violations":[],"suggest_ban_ticket":false}""")
        assertTrue(clean.resolvedViolations().isEmpty())
        assertEquals(0.0, clean.computeFinalDelta(1.0, 25.0))
    }

    @Test
    fun testPerFeatureModelAndThinkingConfigResolution() {
        val settings = GeminiClientSettings(
            provider = "gemini_api",
            model = "gemini-4-hydrogen", // application.yaml 既定値
            taskOverrides = mapOf(
                AiTaskType.MODERATION to GeminiTaskOverrideSettings(
                    model = "gemini-3.5-flash-lite",
                    thinkingLevel = "MINIMAL",
                ),
                // APPEAL は未指定なのでデフォルトモデル + タスク既定思考量(MEDIUM)にフォールバック
            ),
        )
        val client = GeminiModerationClient(settings)

        val modRuntime = client.resolveTaskConfig(AiTaskType.MODERATION)
        assertEquals("gemini-3.5-flash-lite", modRuntime.model)
        assertEquals("MINIMAL", modRuntime.thinkingLevel)

        val appealRuntime = client.resolveTaskConfig(AiTaskType.APPEAL)
        assertEquals("gemini-4-hydrogen", appealRuntime.model)
        assertEquals("MEDIUM", appealRuntime.thinkingLevel)

        // サーバーDBオーバーライドが最優先されること
        val dbOverridden = client.resolveTaskConfig(
            task = AiTaskType.APPEAL,
            overrideModel = "gemini-4-argon",
            overrideThinkingLevel = "HIGH",
        )
        assertEquals("gemini-4-argon", dbOverridden.model)
        assertEquals("HIGH", dbOverridden.thinkingLevel)
    }

    @Test
    fun testReplyReevaluationFilterAndIncidentReportModal() {
        // 軽い笑い・肯定的な相槌はスキップ
        assertTrue(MessageBatchBuffer.shouldSkipReplyReevaluation("草"))
        assertTrue(MessageBatchBuffer.shouldSkipReplyReevaluation("www"))
        assertTrue(MessageBatchBuffer.shouldSkipReplyReevaluation("わかる"))
        assertTrue(MessageBatchBuffer.shouldSkipReplyReevaluation("ありがとう"))
        assertTrue(MessageBatchBuffer.shouldSkipReplyReevaluation("あ"))

        // 不快・苦言・通常長文の返信は事後再評価の対象
        assertFalse(MessageBatchBuffer.shouldSkipReplyReevaluation("は？"))
        assertFalse(MessageBatchBuffer.shouldSkipReplyReevaluation("きも"))
        assertFalse(MessageBatchBuffer.shouldSkipReplyReevaluation("それはさすがに言い過ぎだろ"))
        assertFalse(MessageBatchBuffer.shouldSkipReplyReevaluation("マジで不快だからやめて"))

        // 報告モーダルと処罰種別（デフォルト 5% / 10% / 15% + ユーザー別設定 + マイナス許容）
        assertEquals(5.0, jp.simplespace.db.WatchTargetReportConfig.DEFAULT_PRESET_1)
        assertEquals(10.0, jp.simplespace.db.WatchTargetReportConfig.DEFAULT_PRESET_2)
        assertEquals(15.0, jp.simplespace.db.WatchTargetReportConfig.DEFAULT_PRESET_3)
        assertTrue(jp.simplespace.db.WatchTargetReportConfig.DEFAULT_ALLOW_NEGATIVE)
        assertTrue(ReportPunishmentType.BAN.isBan)

        val defaultCfg = jp.simplespace.db.WatchTargetReportConfig(targetId = 9L, guildId = "g1")
        val (p1, errP1) = jp.simplespace.discord.listeners.WatchInteractionListener.resolveReportPunishment("PRESET_1", "", defaultCfg)
        val (p2, errP2) = jp.simplespace.discord.listeners.WatchInteractionListener.resolveReportPunishment("PRESET_2", "", defaultCfg)
        val (p3, errP3) = jp.simplespace.discord.listeners.WatchInteractionListener.resolveReportPunishment("PRESET_3", "", defaultCfg)
        assertEquals(null, errP1); assertEquals(5.0, p1?.scoreDelta)
        assertEquals(null, errP2); assertEquals(10.0, p2?.scoreDelta)
        assertEquals(null, errP3); assertEquals(15.0, p3?.scoreDelta)

        // ユーザー別カスタム候補 & マイナス禁止設定
        val customCfg = jp.simplespace.db.WatchTargetReportConfig(
            targetId = 9L,
            guildId = "g1",
            preset1 = 3.0,
            preset2 = 8.0,
            preset3 = 20.0,
            allowNegativeDelta = false,
        )
        val (cp2, errCp2) = jp.simplespace.discord.listeners.WatchInteractionListener.resolveReportPunishment("PRESET_2", "", customCfg)
        assertEquals(null, errCp2)
        assertEquals(8.0, cp2?.scoreDelta)

        // マイナス違反度の往復ストレージ・全角パース（デフォルトは許容）
        val negStored = jp.simplespace.db.ReportPunishment.fromStorageValue(
            jp.simplespace.db.ReportPunishment.score(-12.5).toStorageValue()
        )
        assertFalse(negStored.isBan)
        assertEquals(-12.5, negStored.scoreDelta)
        assertEquals("違反度 -12.5% 減算", negStored.displayName)

        val (resolvedNeg, errNeg) = jp.simplespace.discord.listeners.WatchInteractionListener.resolveReportPunishment(
            ReportPunishmentType.CUSTOM.name,
            "－１０％",
            defaultCfg,
        )
        assertEquals(null, errNeg)
        assertEquals(-10.0, resolvedNeg?.scoreDelta)
        assertEquals("違反度 -10% 減算", resolvedNeg?.displayName)

        // マイナス禁止設定のユーザーにはエラーになること
        val (blockedNeg, errBlockedNeg) = jp.simplespace.discord.listeners.WatchInteractionListener.resolveReportPunishment(
            ReportPunishmentType.CUSTOM.name,
            "-5",
            customCfg,
        )
        assertEquals(null, blockedNeg)
        assertNotNull(errBlockedNeg)

        // 全角・%付きプラス入力のパース
        val (resolvedCustom, errCustom) = jp.simplespace.discord.listeners.WatchInteractionListener.resolveReportPunishment(
            ReportPunishmentType.CUSTOM.name,
            "＋２５％",
            defaultCfg,
        )
        assertEquals(null, errCustom)
        assertNotNull(resolvedCustom)
        assertEquals(25.0, resolvedCustom.scoreDelta)
        assertEquals("違反度 +25% 加算", resolvedCustom.displayName)

        // プリセットのまま自由入力欄に数値を入れた場合は自由入力を優先
        val (resolvedOverride, errOverride) = jp.simplespace.discord.listeners.WatchInteractionListener.resolveReportPunishment(
            "PRESET_2",
            "12.5",
            defaultCfg,
        )
        assertEquals(null, errOverride)
        assertEquals(12.5, resolvedOverride?.scoreDelta)

        // BAN と自由入力の同時指定や空欄はエラー
        val (banConflict, errBan) = jp.simplespace.discord.listeners.WatchInteractionListener.resolveReportPunishment(
            ReportPunishmentType.BAN.name,
            "20",
            defaultCfg,
        )
        assertEquals(null, banConflict)
        assertNotNull(errBan)

        val (missingCustom, errMissing) = jp.simplespace.discord.listeners.WatchInteractionListener.resolveReportPunishment(
            ReportPunishmentType.CUSTOM.name,
            "",
            defaultCfg,
        )
        assertEquals(null, missingCustom)
        assertNotNull(errMissing)

        val modal = jp.simplespace.discord.listeners.WatchInteractionListener.buildIncidentReportModal(9L, "T氏", customCfg)
        assertEquals("watch_report_modal:9", modal.id)
        assertEquals(4, modal.components.size)

        // AIフィードバックモーダル・入力パースの検証
        assertEquals(0.0, jp.simplespace.discord.listeners.WatchInteractionListener.parseFeedbackExpectedDelta("0"))
        assertEquals(12.5, jp.simplespace.discord.listeners.WatchInteractionListener.parseFeedbackExpectedDelta("＋１２．５％"))
        assertEquals(null, jp.simplespace.discord.listeners.WatchInteractionListener.parseFeedbackExpectedDelta("30"))
        assertEquals(null, jp.simplespace.discord.listeners.WatchInteractionListener.parseFeedbackExpectedDelta("-1"))

        val manualFbModal = jp.simplespace.discord.listeners.WatchInteractionListener.buildFeedbackModalManual(9L, "T氏")
        assertEquals("watch_feedback_modal:9:manual", manualFbModal.id)
        assertEquals(4, manualFbModal.components.size)

        val sampleEvent = WatchScoreEvent(
            id = 42L,
            cycleId = 1L,
            targetId = 9L,
            guildId = "g1",
            userId = "u1",
            eventType = WatchScoreEventType.AI_VIOLATION,
            delta = 15.0,
            scoreAfter = 45.0,
            category = "INSULT",
            reason = "テスト",
            sourceChannelId = "ch1",
            sourceMessageId = "m1",
            sourceMessageExcerpt = "テスト発言",
            aiRawJson = null,
            actorUserId = null,
            createdAt = 0L,
        )
        val eventFbModal = jp.simplespace.discord.listeners.WatchInteractionListener.buildFeedbackModalForEvent(9L, "T氏", sampleEvent)
        assertEquals("watch_feedback_modal:9:42", eventFbModal.id)
        assertEquals(4, eventFbModal.components.size)

        // 見逃し再審査用の Discord メッセージリンク解析
        val parsedLink = WatchOrchestrator.parseDiscordMessageLink("https://discord.com/channels/111/222/333")
        assertNotNull(parsedLink)
        assertEquals("111", parsedLink.guildId)
        assertEquals("222", parsedLink.channelId)
        assertEquals("333", parsedLink.messageId)
        assertEquals(null, WatchOrchestrator.parseDiscordMessageLink("これはただのテキスト発言"))

        // FeatureKey に calendar / rvc が統合されていること
        assertEquals(jp.simplespace.db.FeatureKey.CALENDAR, jp.simplespace.db.FeatureKey.fromKey("calendar"))
        assertEquals(jp.simplespace.db.FeatureKey.RVC, jp.simplespace.db.FeatureKey.fromKey("rvc"))
    }
}
