package data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class SwitchBotEvent(
    @SerialName("context")
    val context: Context,
    @SerialName("eventType")
    val eventType: String,
    @SerialName("eventVersion")
    val eventVersion: String
) {
    @Serializable
    data class Context(
        @SerialName("deviceMac")
        val deviceMac: String,
        @SerialName("deviceType")
        val deviceType: String,
        @SerialName("humidity")
        val humidity: Double,
        @SerialName("temperature")
        val temperature: Double,
        @SerialName("timeOfSample")
        val timeOfSample: Long
    )
}