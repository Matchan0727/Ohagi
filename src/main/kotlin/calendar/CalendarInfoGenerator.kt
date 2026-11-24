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
import java.util.concurrent.TimeUnit

object CalendarInfoGenerator {
    private const val EVENT_FIELDS = "items(htmlLink,summary,start,end,location)"
    private const val MAX_RESULTS_PER_CALENDAR = 250
    private val CACHE_TTL_MILLIS = TimeUnit.MINUTES.toMillis(3)
    private val PAST_WINDOW_MILLIS = TimeUnit.DAYS.toMillis(90)
    private val FUTURE_WINDOW_MILLIS = TimeUnit.DAYS.toMillis(180)
    private val URL_REGEX = Regex("""https?://\S+""", RegexOption.IGNORE_CASE)

    private data class CacheEntry(
        val schedules: List<Schedule>,
        val cachedAt: Long,
    )

    @Volatile
    private var scheduleCache: CacheEntry? = null

    val jda: JDA
        get() = OhagiBot.jda
    val calendar by lazy { CalendarController.getCalendar() }
    val zoneId = ZoneId.of("Asia/Tokyo")

    fun clearCache() {
        scheduleCache = null
    }

    suspend fun generateContainer(
        maxSize: Int = 10,
        page: Int = 0,
        forceRefresh: Boolean = false,
    ): Container {
        val allSchedules = getAllSortedSchedules(forceRefresh)
        val nowInstant = Instant.now()
        val initialIndex = allSchedules.indexOfFirst { schedule ->
            val end = schedule.endTime ?: schedule.endDate
            end != null && end.isAfter(nowInstant)
        }.let { if (it == -1) allSchedules.size else it }

        val (startIdx, endIdx) = if (page >= 0) {
            val start = minOf(initialIndex + page * maxSize, allSchedules.size)
            val end = minOf(start + maxSize, allSchedules.size)
            start to end
        } else {
            val end = maxOf(0, initialIndex + (page + 1) * maxSize)
            val start = maxOf(0, end - maxSize)
            start to end
        }

        val list = if (startIdx < endIdx) allSchedules.subList(startIdx, endIdx) else emptyList()
        val hasPrev = startIdx > 0
        val hasNext = endIdx < allSchedules.size

        val prevButton = Button.secondary("calpage_${page - 1}", "◀️前").withDisabled(!hasPrev)
        val nextButton = Button.secondary("calpage_${page + 1}", "▶️次").withDisabled(!hasNext)
        val resetButton = Button.secondary("calreset", "🔄最初に戻る")
        val refreshButton = Button.secondary("calrefresh_$page", "🔄更新")
        val clearButton = Button.secondary("clear", "🗑️")

        if (list.isEmpty()) {
            val emptyButtons = buildList {
                if (hasPrev) add(prevButton)
                if (page != 0) add(resetButton)
                add(refreshButton)
                add(clearButton)
            }
            return Container.of(
                TextDisplay.of("# 予定はありません\nGoogleカレンダーから取得した予定がありません。"),
                Separator.createInvisible(Separator.Spacing.SMALL),
                ActionRow.of(emptyButtons)
            )
        }
        val topDisplay = TextDisplay.of("# 予定一覧\nGoogleカレンダーから取得した予定です。")
        val sections = list.map { schedule ->
            val formattedLocation = formatLocation(schedule.location)
            Section.of(
                Button.link(schedule.link, "表示"),
                TextDisplay.of(
                    String.format(
                                "%s    **%s**" +
                                "\n%s  %s" +
                                "\n%s",
                        schedule.user.asMention, schedule.title ?: "タイトルなし",
                        dateFormatFromInstant(schedule), timeRangeFormatFromInstant(schedule),
                        if (formattedLocation == null) "" else "-# 場所: $formattedLocation"
                    )
                )
            )
        }
        val actionRows = buildList {
            add(prevButton)
            add(nextButton)
            if (page != 0) add(resetButton)
            add(refreshButton)
            add(clearButton)
        }
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

    suspend fun getAllSortedSchedules(forceRefresh: Boolean = false): List<Schedule> {
        val now = System.currentTimeMillis()
        if (forceRefresh) {
            scheduleCache = null
        } else {
            val cached = scheduleCache
            if (cached != null && now - cached.cachedAt < CACHE_TTL_MILLIS) {
                return cached.schedules
            }
        }

        val timeMin = DateTime(now - PAST_WINDOW_MILLIS)
        val timeMax = DateTime(now + FUTURE_WINDOW_MILLIS)
        val calendarIdMap = calendarService.getIdMapping()
        val schedules = coroutineScope {
            calendarIdMap.mapNotNull { (userId, calendarId) ->
                val user = jda.getUserById(userId) ?: return@mapNotNull null
                async(Dispatchers.IO) {
                    val events = calendar.events().list(calendarId)
                        .setTimeMin(timeMin)
                        .setTimeMax(timeMax)
                        .setMaxResults(MAX_RESULTS_PER_CALENDAR)
                        .setOrderBy("startTime")
                        .setSingleEvents(true)
                        .setFields(EVENT_FIELDS)
                        .execute()
                        .items
                        .orEmpty()

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

        val sorted = schedules.sortedWith(
            compareBy<Schedule>({ it.startTime ?: it.startDate }, { it.endTime ?: it.endDate })
        )
        scheduleCache = CacheEntry(sorted, System.currentTimeMillis())
        return sorted
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

    private fun formatLocation(location: String?): String? {
        if (location.isNullOrBlank()) return null
        val cleaned = location.replace(URL_REGEX, "").trim()
        return cleaned.takeIf { it.isNotEmpty() }
    }

}