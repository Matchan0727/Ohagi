package jp.simplespace.discord.listeners

import jp.simplespace.discord.OhagiBot
import jp.simplespace.watch.MessageBatchBuffer
import jp.simplespace.watch.PendingBanManager
import jp.simplespace.watch.RoleRestoreService
import jp.simplespace.watch.WatchAppealService
import kotlinx.coroutines.launch
import net.dv8tion.jda.api.events.guild.member.GuildMemberJoinEvent
import net.dv8tion.jda.api.events.message.MessageReceivedEvent
import net.dv8tion.jda.api.events.user.UserTypingEvent
import net.dv8tion.jda.api.hooks.ListenerAdapter

class WatchEventListener(
    private val messageBatchBuffer: MessageBatchBuffer,
) : ListenerAdapter() {

    override fun onMessageReceived(event: MessageReceivedEvent) {
        if (!event.isFromGuild || event.author.isBot) return
        val message = event.message
        OhagiBot.scope.launch {
            try {
                // 1. 遺言待機中の専用チャンネル発言を最優先で検知
                val consumedAsLastWords = PendingBanManager.handlePossibleLastWords(message)
                if (consumedAsLastWords) return@launch

                // 2. 監視対象者からBotへのメンション／返信による罰則理由確認・異議申し立て
                val consumedAsAppeal = WatchAppealService.handlePossibleAppeal(message)
                if (consumedAsAppeal) return@launch

                // 3. 通常のAI違反監視バッチへ投入（監視対象本人の発言）
                messageBatchBuffer.onGuildMessage(message)

                // 4. 他メンバーから監視対象メッセージへの後追い返信による事後再評価
                messageBatchBuffer.onReplyToTargetMessage(message)
            } catch (e: Exception) {
                OhagiBot.logger.error("WatchEventListener メッセージ処理中にエラー", e)
            }
        }
    }

    override fun onUserTyping(event: UserTypingEvent) {
        val guild = event.guild ?: return
        if (event.user.isBot) return
        messageBatchBuffer.onUserTyping(guild.id, event.channel.id, event.user.id)
    }

    override fun onGuildMemberJoin(event: GuildMemberJoinEvent) {
        OhagiBot.scope.launch {
            try {
                RoleRestoreService.handleMemberRejoin(event.guild, event.member)
            } catch (e: Exception) {
                OhagiBot.logger.error("再参加時ロール復元処理中にエラー: guild={}, user={}", event.guild.id, event.member.id, e)
            }
        }
    }
}
