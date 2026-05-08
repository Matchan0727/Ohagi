package calendar

import net.dv8tion.jda.api.entities.User
import java.time.Instant

data class Schedule(
    val user: User,
    val link: String,
    val title: String? = null,
    val startDate: Instant?,
    val endDate: Instant?,
    val startTime: Instant?,
    val endTime: Instant?,
    val location: String? = null,
) {
}