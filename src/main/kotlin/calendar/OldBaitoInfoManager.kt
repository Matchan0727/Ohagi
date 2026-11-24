package calendar

import com.google.api.client.util.DateTime
import net.dv8tion.jda.api.events.message.MessageReceivedEvent
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.time.temporal.ChronoUnit
import java.util.*

class OldBaitoInfoManager {
    val timeFormatter = DateTimeFormatter.ofPattern("HH時mm分")
    val zoneId = ZoneId.of("Asia/Tokyo")
    val calendarId = "e07acc8d856b576196ff1fa6e81e5cc2a6a61756c0fcdcee5a1779587c8d5a1c@group.calendar.google.com"
    val baitoTitle = "アルバイト"

    fun getMessageText(): String {
        val calendar = CalendarController.getCalendar()
        val nowTime = DateTime(System.currentTimeMillis())
        val nowDate = ZonedDateTime.now(zoneId)
        val events = calendar.events().list(calendarId)
            .setMaxResults(100)
            .setTimeMin(nowTime)
            .setOrderBy("startTime")
            .setSingleEvents(true)
            .execute()
        // 終日予定は start.dateTime が null になるため、時刻付きの「アルバイト」だけを対象にする
        val items = events.items.filter {
            it.summary?.trim() == baitoTitle && it.start?.dateTime != null && it.end?.dateTime != null
        }
        if (items.isEmpty()) return "今日はない"

        val start = toZoned(items[0].start.dateTime)
        val end = toZoned(items[0].end.dateTime)
        val sb = StringBuilder()
        var current = nowDate
        if (nowDate.toLocalDate() == start.toLocalDate()) {
            sb.append("今日はある(${start.format(timeFormatter)}～${end.format(timeFormatter)})\n")
            current = start
        }
        else if (nowDate.isBefore(start)) {
            sb.append("今日はない\n${getDayStr(nowDate, start)}はある(${start.format(timeFormatter)}～${end.format(timeFormatter)})\n")
            current = start
        }
        else {
            sb.append("今日はない\n")
        }
        for (i in 1 until items.size) {
            val nextStart = toZoned(items[i].start.dateTime)
            val nextEnd = toZoned(items[i].end.dateTime)
            if (daysBetween(current, nextStart) == 1L) {
                sb.append("${getDayStr(nowDate, nextStart)}もある(${nextStart.format(timeFormatter)}～${nextEnd.format(timeFormatter)})\n")
                current = nextStart
            } else {
                break
            }
        }
        return sb.toString()
    }

    fun sendMessage(event: MessageReceivedEvent) {
        val cont = event.message.contentDisplay.lowercase()
        val pattern = ".*(まっちゃん|matchan).*(バイト|ばいと).*".toRegex()
        if (pattern.matches(cont)) {
            event.channel.sendMessage(getMessageText()).setMessageReference(event.message.id).queue()
        }
    }

    // サーバーのタイムゾーンに依存しないよう、常に Asia/Tokyo に揃える
    fun toZoned(dateTime: DateTime): ZonedDateTime {
        return OffsetDateTime.parse(dateTime.toStringRfc3339(), DateTimeFormatter.ISO_OFFSET_DATE_TIME)
            .atZoneSameInstant(zoneId)
    }

    fun getDayStr(d1: ZonedDateTime, d2: ZonedDateTime): String {
        return when (daysBetween(d1, d2)) {
            1L -> "明日(${d2.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.JAPANESE)})"
            2L -> "明後日(${d2.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.JAPANESE)})"
            else -> "${d2.monthValue}/${d2.dayOfMonth}(${d2.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.JAPANESE)})"
        }
    }

    fun daysBetween(d1: ZonedDateTime, d2: ZonedDateTime): Long {
        return ChronoUnit.DAYS.between(d1.toLocalDate(), d2.toLocalDate())
    }
}