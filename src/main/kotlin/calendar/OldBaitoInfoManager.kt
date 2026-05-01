package calendar

import com.google.api.client.util.DateTime
import net.dv8tion.jda.api.events.message.MessageReceivedEvent
import java.time.Duration
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.*

class OldBaitoInfoManager {
    val timeFormatter = DateTimeFormatter.ofPattern("HH時mm分")
    fun getMessageText(): String{
        val calendar = CalendarController.getCalendar()
        val nowTime = DateTime(System.currentTimeMillis())
        val nowDate = OffsetDateTime.parse(nowTime.toStringRfc3339(), DateTimeFormatter.ISO_OFFSET_DATE_TIME)
//            val nowDate = OffsetDateTime.parse("2024-11-21T17:00:00.000+09:00", DateTimeFormatter.ISO_OFFSET_DATE_TIME)
        val events = calendar.events().list("e07acc8d856b576196ff1fa6e81e5cc2a6a61756c0fcdcee5a1779587c8d5a1c@group.calendar.google.com")
            .setMaxResults(10)
//                .setTimeMin(DateTime(nowDate.toEpochSecond() * 1000))
            .setTimeMin(nowTime)
            .setOrderBy("startTime")
            .setSingleEvents(true)
            .execute()
        val items = events.items.filter {it.summary == "アルバイト"}
        if (items.isNotEmpty()) {
            val start = OffsetDateTime.parse(items[0].start.dateTime.toStringRfc3339(), DateTimeFormatter.ISO_OFFSET_DATE_TIME)
            val end = OffsetDateTime.parse(items[0].end.dateTime.toStringRfc3339(), DateTimeFormatter.ISO_OFFSET_DATE_TIME)
//                       event.channel.sendMessage("${e.summary} ${start.format(dateFormatter)} ${start.format(timeFormatter)}～${end.format(timeFormatter)}").setMessageReference(event.message.id).queue()
            val sb = StringBuilder()
            var current = nowDate
            if (nowDate.dayOfYear == start.dayOfYear) {
                sb.append("今日はある(${start.format(timeFormatter)}～${end.format(timeFormatter)})\n")
            }
            else if (nowDate.isBefore(start)) {
                sb.append(("今日はない\n${getDayStr(nowDate,start)}はある(${start.format(timeFormatter)}～${end.format(timeFormatter)})\n"))
                current = start
            }
            else {
                sb.append("今日はない\n")
            }
            for (i in 1 until items.size) {
                val nextStart = OffsetDateTime.parse(items[i].start.dateTime.toStringRfc3339(), DateTimeFormatter.ISO_OFFSET_DATE_TIME)
                val nextEnd = OffsetDateTime.parse(items[i].end.dateTime.toStringRfc3339(), DateTimeFormatter.ISO_OFFSET_DATE_TIME)
                if (duration(current,nextStart).toDays() == 1L) {
                    sb.append("${getDayStr(nowDate,nextStart)}もある(${nextStart.format(timeFormatter)}～${nextEnd.format(timeFormatter)})\n")
                    current = nextStart
                } else {
                    break;
                }
            }
            return sb.toString()
        }
        else return "今日はない"
    }

    fun sendMessage(event: MessageReceivedEvent) {
        val cont = event.message.contentDisplay.lowercase()
        val pattern = ".*(まっちゃん|matchan).*(バイト|ばいと).*".toRegex()
        if (pattern.matches(cont)) {
            event.channel.sendMessage(getMessageText()).setMessageReference(event.message.id).queue()
        }
    }

    fun getDayStr(d1: OffsetDateTime, d2: OffsetDateTime): String {
        return when {
            duration(d1,d2).toDays() == 1L -> "明日(${d2.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.JAPANESE)})"
            duration(d1,d2).toDays() == 2L -> "明後日(${d2.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.JAPANESE)})"
            else -> "${d2.monthValue}/${d2.dayOfMonth}(${d2.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.JAPANESE)})"
        }
    }

    fun duration(d1: OffsetDateTime, d2: OffsetDateTime): Duration {
        val d3 = d1.withHour(0).withMinute(0).withSecond(0).withNano(0)
        val d4 = d2.withHour(0).withMinute(0).withSecond(0).withNano(0)
        return Duration.between(d3,d4)
    }
}