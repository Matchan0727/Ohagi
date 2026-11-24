package calendar

import com.google.api.client.util.DateTime
import jp.simplespace.calendarService
import jp.simplespace.discord.OhagiBot
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
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
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

object CalendarInfoGenerator {
    private const val EVENT_FIELDS = "items(htmlLink,summary,start,end,location)"
    private val CACHE_TTL_MILLIS = TimeUnit.MINUTES.toMillis(3)

    private data class CacheKey(
        val maxSize: Int,
        val timeMin: Long?,
        val timeMax: Long?,
    )

    private data class CacheEntry(
        val schedules: List<Schedule>,
        val cachedAt: Long,
    )

    private val scheduleCache = ConcurrentHashMap<CacheKey, CacheEntry>()

    val jda: JDA
        get() = OhagiBot.jda
    val calendar by lazy { CalendarController.getCalendar() }
    val zoneId = ZoneId.of("Asia/Tokyo")

    fun clearCache() {
        scheduleCache.clear()
    }

    suspend fun generateContainer(
        maxSize: Int = 10,
        timeMin: Long? = null,
        timeMax: Long? = null,
        forceRefresh: Boolean = false,
    ): Container {
        val list = generateSortedScheduleList(maxSize, timeMin, timeMax, forceRefresh)
        val resetButton = Button.secondary("calreset", "🔄最初に戻る")
        val refreshButton = Button.secondary("calrefresh", "🔄更新")
        val clearButton = Button.secondary("clear", "🗑️")
        if (list.isEmpty()) {
            return Container.of(
                TextDisplay.of("# 予定はありません\nGoogleカレンダーから取得した予定がありません。"),
                Separator.createInvisible(Separator.Spacing.SMALL),
                ActionRow.of(
                    resetButton,
                    refreshButton,
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
            refreshButton,
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

    suspend fun generateSortedScheduleList(
        maxSize: Int,
        timeMin: Long? = null,
        timeMax: Long? = null,
        forceRefresh: Boolean = false,
    ): List<Schedule> {
        val cacheKey = CacheKey(maxSize, timeMin, timeMax)
        val now = System.currentTimeMillis()
        if (forceRefresh) {
            scheduleCache.clear()
        } else {
            val cached = scheduleCache[cacheKey]
            if (cached != null && now - cached.cachedAt < CACHE_TTL_MILLIS) {
                return cached.schedules
            }
        }

        val calendarIdMap = calendarService.getIdMapping()
        val schedules = coroutineScope {
            calendarIdMap.mapNotNull { (userId, calendarId) ->
                val user = jda.getUserById(userId) ?: return@mapNotNull null
                async(Dispatchers.IO) {
                    val request = calendar.events().list(calendarId)
                        .setMaxResults(10)
                        .setOrderBy("startTime")
                        .setSingleEvents(true)
                        .setFields(EVENT_FIELDS)
                    if (timeMax != null) {
                        request.setTimeMax(DateTime(timeMax))
                            .setTimeMin(DateTime(timeMax - TimeUnit.DAYS.toMillis(90)))
                            .setMaxResults(100)
                    } else {
                        request.setTimeMin(DateTime(timeMin ?: now))
                    }
                    var events = request.execute().items.orEmpty()
                    if (timeMax != null) events = events.takeLast(maxSize)

                    events.map { event ->
                        Schedule(
                            user = user,
                            link = event.htmlLink,
                            title = event.summary,
                            startDate = dateToInstant(event.start.date),
                            endDate = dateToInstant(event.end.date),
                            startTime = dateTimeToInstant(event.start.dateTime),
                            endTime = dateTimeToInstant(event.end.dateTime),
                            location = event.location
                        )
                    }
                }
            }.awaitAll().flatten()
        }

        val sorted = schedules.sortedBy { it.startTime ?: it.startDate }
        val result = if (timeMax != null) sorted.takeLast(maxSize) else sorted.take(maxSize)
        scheduleCache[cacheKey] = CacheEntry(result, System.currentTimeMillis())
        return result
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