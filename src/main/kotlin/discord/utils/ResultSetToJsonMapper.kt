package jp.simplespace.discord.utils

import java.lang.IllegalArgumentException
import java.math.BigDecimal
import java.math.BigInteger
import java.sql.ResultSet
import java.sql.ResultSetMetaData
import java.sql.SQLException
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Base64
import java.util.Date
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonPrimitive

object ResultSetToJsonMapper {
    @Throws(SQLException::class)
    fun mapResultSet(rs: ResultSet): JsonArray {
        val jArray = JsonArray()
        val rsmd: ResultSetMetaData = rs.metaData
        val columnCount: Int = rsmd.columnCount
        // iterate rows
        while (rs.next()) {
            val jsonObject = JsonObject()
            for (index in 1..columnCount) {
                val column: String = rsmd.getColumnName(index)
                val value: Any? = rs.getObject(index)
                if (value == null) {
                    // 既存の挙動に合わせて空文字列を格納
                    jsonObject.add(column, JsonPrimitive(""))
                } else when (value) {
                    is Int -> jsonObject.add(column, JsonPrimitive(value))
                    is String -> jsonObject.add(column, JsonPrimitive(value))
                    is Boolean -> jsonObject.add(column, JsonPrimitive(value))
                    is Date -> jsonObject.add(column, JsonPrimitive(value.time))
                    is Long -> jsonObject.add(column, JsonPrimitive(value))
                    is Double -> jsonObject.add(column, JsonPrimitive(value))
                    is Float -> jsonObject.add(column, JsonPrimitive(value))
                    is BigDecimal -> jsonObject.add(column, JsonPrimitive(value))
                    is Byte -> jsonObject.add(column, JsonPrimitive(value.toInt()))
                    is ByteArray -> {
                        val encoded = Base64.getEncoder().encodeToString(value)
                        jsonObject.add(column, JsonPrimitive(encoded))
                    }
                    is LocalDateTime -> {
                        jsonObject.add(column, JsonPrimitive(value.format(DateTimeFormatter.ofPattern("yyyy/MM/dd HH:mm:ss"))))
                    }
                    is BigInteger -> jsonObject.add(column, JsonPrimitive(value))
                    else -> throw IllegalArgumentException("Unmappable object type: " + value.javaClass)
                }
            }
            jArray.add(jsonObject)
        }
        return jArray
    }
}