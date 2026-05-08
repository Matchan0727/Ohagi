package calendar

import com.google.api.client.util.DateTime
import jp.simplespace.database
import jp.simplespace.db.CalendarService
import jp.simplespace.discord.OhagiBot
import net.dv8tion.jda.api.JDA
import net.dv8tion.jda.api.components.actionrow.ActionRow
import net.dv8tion.jda.api.components.buttons.Button
import net.dv8tion.jda.api.components.container.Container
import net.dv8tion.jda.api.components.section.Section
import net.dv8tion.jda.api.components.separator.Separator
import net.dv8tion.jda.api.components.textdisplay.TextDisplay
import java.time.*
import java.time.format.DateTimeFormatter
import java.util.*
import java.util.concurrent.TimeUnit

object CalendarInfoGenerator {
    val jda: JDA = OhagiBot.jda
    val calendar = CalendarController.getCalendar()
    val zoneId = ZoneId.of("Asia/Tokyo")

    fun generateContainer(maxSize: Int = 10, timeMin: Long? = System.currentTimeMillis(), timeMax: Long? = null): Container {
        val list = generateSortedScheduleList(maxSize, timeMin, timeMax)
        val resetButton = Button.secondary("calreset", "🔄最初に戻る")
        val clearButton = Button.secondary("clear", "🗑️")
        if (list.isEmpty()) {
            return Container.of(
                TextDisplay.of("# 予定はありません\nGoogleカレンダーから取得した予定がありません。"),
                Separator.createInvisible(Separator.Spacing.SMALL),
                ActionRow.of(
                    resetButton,
                    clearButton
                )
            )
        }
        val topDisplay = TextDisplay.of("# 予定一覧\nGoogleカレンダーから取得した予定です。")
        val sections = list.map { schedule ->
            Section.of(
                Button.link(schedule.link, "表示"),
                TextDisplay.of(
                    String.format(
                                "%s    **%s**" +
                                "\n%s  %s" +
                                "\n%s",
                        schedule.user.asMention, schedule.title ?: "タイトルなし",
                        dateFormatFromInstant(schedule), timeRangeFormatFromInstant(schedule),
                        if (schedule.location.isNullOrBlank()) "" else "-# 場所: ${schedule.location}"
                    )
                )
            )
        }
        var lastSchedule = list.first()
        for (schedule in list.reversed()) {
            if (dateEquals(schedule)) {
                lastSchedule = schedule
                break
            }
        }
        val lastDate = (lastSchedule.endTime?: lastSchedule.endDate!!)
        var firstSchedule = list.first()
        for (schedule in list) {
            if (dateEquals(schedule)) {
                firstSchedule = schedule
                break
            }
        }
        val preDate = (firstSchedule.startTime?: firstSchedule.startDate!!)
        val actionRows = mutableListOf(
            Button.secondary("calprev_${preDate.toEpochMilli()}", "◀️前"),
            Button.secondary("calnext_${lastDate.toEpochMilli()}", "▶️次"),
            clearButton
        )
        return Container.of(
            topDisplay,
            Separator.createDivider(Separator.Spacing.LARGE),
            *sections.toTypedArray(),
            Separator.createInvisible(Separator.Spacing.SMALL),
            ActionRow.of(
                actionRows
            ),
        )
    }

    fun generateSortedScheduleList(maxSize: Int, timeMin: Long? = System.currentTimeMillis(), timeMax: Long? = null): List<Schedule> {
        val schedules = mutableListOf<Schedule>()
        val calendarIdMap = CalendarService(database).getIdMapping()
        top@ for ((userId, calendarId) in calendarIdMap) {
            val list = calendar.events().list(calendarId)
                .setMaxResults(10)
                .setOrderBy("startTime")
                .setSingleEvents(true)
            if (timeMax != null) {
                list.setTimeMax(DateTime(timeMax))
                    .setTimeMin(DateTime(timeMax - TimeUnit.DAYS.toMillis(90)))
                    .setMaxResults(100)
            }
            if (timeMin != null) list.setTimeMin(DateTime(timeMin))
            var events = list.execute().items
            if (timeMax != null) events = events.takeLast(maxSize)

            for (event in events) {
                val startDate = dateToInstant(event.start.date)
                val startTime = dateTimeToInstant(event.start.dateTime)
                val endDate = dateToInstant(event.end.date)
                val endTime = dateTimeToInstant(event.end.dateTime)
                schedules.add(Schedule(
                    user = jda.getUserById(userId)?: continue@top,
                    link = event.htmlLink,
                    title = event.summary,
                    startDate = startDate,
                    endDate = endDate,
                    startTime = startTime,
                    endTime = endTime,
                    location = event.location
                ))
            }
        }
        val result = schedules.sortedBy { it.startTime?: it.startDate }
        return if (timeMin != null) return result.take(maxSize)
            else result.takeLast(maxSize)
    }

    fun dateTimeToInstant(dateTime: DateTime?): Instant? {
        if (dateTime == null) return null
        return OffsetDateTime.parse(dateTime.toStringRfc3339(), DateTimeFormatter.ISO_OFFSET_DATE_TIME).toInstant()
    }

    fun dateToInstant(date: DateTime?): Instant? {
        if (date == null) return null
        return LocalDate.parse(date.toStringRfc3339(), DateTimeFormatter.ofPattern("yyyy-MM-dd"))
            .atStartOfDay(zoneId)
            .toInstant()
    }

    fun dateFormatFromInstant(schedule: Schedule): String {
        val start = ZonedDateTime.ofInstant(schedule.startTime?: schedule.startDate, zoneId)
        var end = ZonedDateTime.ofInstant(schedule.endTime?: schedule.endDate, zoneId)
        if (schedule.startTime == null && schedule.endTime == null) {
            end = end.minusDays(1)
        }
        val formatter = DateTimeFormatter.ofPattern("MM/dd(E)", Locale.JAPAN)
        val sb = StringBuilder()
        sb.append(start.format(formatter))
        val now = ZonedDateTime.now(zoneId)
        if (dateEquals(schedule)){
            if (dateEquals(now, start)) sb.insert(0, "**__").append("__**")
            return sb.toString()
        }
        sb.append(" - ")
            .append(end.format(formatter))
        if ((now.isAfter(start) && now.isBefore(end)) || dateEquals(now, start) || dateEquals(now, end)) {
            sb.insert(0, "**__").append("__**")
        }
        return sb.toString()
    }

    fun timeRangeFormatFromInstant(schedule: Schedule): String {
        if (schedule.startTime == null && schedule.endTime == null) {
            return "終日"
        }
        val startTime = if (schedule.startTime != null) ZonedDateTime.ofInstant(schedule.startTime, zoneId) else null
        val endTime = if (schedule.endTime != null) ZonedDateTime.ofInstant(schedule.endTime, zoneId) else null
        return (if (startTime != null) "<t:${startTime.toEpochSecond()}:t>" else "") +
                " - ${if (endTime != null) "<t:${endTime.toEpochSecond()}:t>" else ""}"
    }

    fun dateEquals(schedule: Schedule): Boolean {
        val z1 = ZonedDateTime.ofInstant(schedule.startTime?: schedule.startDate, zoneId)
        var z2 = ZonedDateTime.ofInstant(schedule.endTime?: schedule.endDate, zoneId)
        if (schedule.startTime == null && schedule.endTime == null) {
            z2 = z2.minusDays(1)
        }
        return dateEquals(z1, z2)
    }

    fun dateEquals(z1: ZonedDateTime, z2: ZonedDateTime): Boolean {
        return z1.year == z2.year && z1.month == z2.month && z1.dayOfMonth == z2.dayOfMonth
    }

}