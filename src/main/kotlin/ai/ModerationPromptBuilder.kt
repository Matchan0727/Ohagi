package jp.simplespace.ai

import jp.simplespace.db.AiRuleType

object ModerationPromptBuilder {
    fun buildSystemInstruction(): String = """
あなたは身内Discordサーバーのモデレーション判定AIです。
監視対象メンバーの発言が「他メンバーを不快にさせる問題発言・暴言・過度な煽り・迷惑行為・ハラスメント」に該当するかを会話の文脈を含めて判定し、厳密なJSONで出力してください。

【重要な前提】
- 身内サーバーのため、軽い冗談・ゲーム中の一般的な掛け合い・周囲も乗っているボケ/ツッコミは過度に違反としないでください。
- サーバー固有の「違反ルール (NG傾向)」「許容ルール (身内ネタ等)」および「過去のフィードバック修正事例」が提供された場合は、それを最優先で基準にしてください。

【base_delta（違反スコア基準値: 0.0〜25.0）】
- 0.0 (NONE): 問題なし、または身内ノリの許容範囲
- 2.0〜5.0 (LOW): 軽度の不快発言、トゲのある言い方、空気を悪くする愚痴・嫌味
- 6.0〜12.0 (MEDIUM): 明確な煽り、失礼な発言、しつこい迷惑行動、相手を貶す発言
- 13.0〜25.0 (HIGH): 強い暴言、人格否定、特定メンバーへの激しい攻撃・深刻なハラスメント

【処刑チケット提案 (suggest_ban_ticket)】
- 特定の他メンバー1名に対して「あまりにもひどい攻撃・暴言・ハラスメント」を行った場合のみ `suggest_ban_ticket: true` とし、会話コンテキスト内に実在する被害者の `author_id` を `victim_user_id` に設定してください。
- 被害者が特定できない場合や軽度〜中度の違反では必ず `suggest_ban_ticket: false`, `victim_user_id: null` にしてください。

【出力形式】
必ず指定されたJSONスキーマのみを出力し、理由 (`reason_ja`, `ticket_reason_ja`) は簡潔な日本語で記述してください。
""".trimIndent()

    fun buildUserPrompt(request: ModerationEvaluationRequest): String = buildString {
        appendLine("## 評価対象メタ情報")
        appendLine("- サーバー名: ${request.guildName}")
        appendLine("- チャンネル名: #${request.channelName}")
        appendLine("- 監視対象ユーザー: ${request.targetDisplayName} (user_id=${request.targetUserId})")
        if (!request.personaNote.isNullOrBlank()) {
            appendLine("- 対象者の特徴メモ: ${request.personaNote}")
        }
        appendLine()

        if (request.rules.isNotEmpty()) {
            appendLine("## サーバー固有ルール（最優先基準）")
            val ngRules = request.rules.filter { it.ruleType == AiRuleType.VIOLATION }
            val allowRules = request.rules.filter { it.ruleType == AiRuleType.ALLOW }
            if (ngRules.isNotEmpty()) {
                appendLine("### NG傾向（違反とみなす基準）")
                ngRules.forEach { rule ->
                    appendLine("- [重み: ${rule.weightHint.name}] ${rule.description}")
                }
            }
            if (allowRules.isNotEmpty()) {
                appendLine("### 許容ルール（身内ネタ・セーフ基準）")
                allowRules.forEach { rule ->
                    appendLine("- ${rule.description}")
                }
            }
            appendLine()
        }

        if (request.feedbacks.isNotEmpty()) {
            appendLine("## 過去の管理者フィードバック事例（Few-shot基準）")
            request.feedbacks.forEachIndexed { idx, fb ->
                appendLine("### 事例 ${idx + 1} (${fb.verdict.name})")
                appendLine("- 発言: 「${fb.messageContent}」")
                if (!fb.contextExcerpt.isNullOrBlank()) {
                    appendLine("- 文脈: ${fb.contextExcerpt}")
                }
                appendLine("- 元AIスコア: ${fb.originalDelta} -> 望ましいスコア: ${fb.expectedDelta}")
                if (!fb.note.isNullOrBlank()) {
                    appendLine("- 管理者コメント: ${fb.note}")
                }
            }
            appendLine()
        }

        if (request.contextMessages.isNotEmpty()) {
            appendLine("## 直前の会話コンテキスト（古い順）")
            request.contextMessages.forEach { msg ->
                val roleTag = if (msg.isTarget) "監視対象" else "他メンバー"
                appendLine("- [$roleTag] ${msg.authorName} (author_id=${msg.authorId}): ${msg.content}")
            }
            appendLine()
        }

        appendLine("## 今回評価する監視対象ユーザーの発言")
        request.targetMessages.forEachIndexed { i, text ->
            appendLine("${i + 1}. $text")
        }
    }
}
