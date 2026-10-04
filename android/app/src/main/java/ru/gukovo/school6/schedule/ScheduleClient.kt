package ru.gukovo.school6.schedule

import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.regex.Pattern

data class ScheduleInfo(
    val scheduleId: String,
    val exportDate: String?,
    val exportTime: String?,
    val schoolName: String,
    val cityName: String,
) {
    fun relativeText(): String {
        val date = exportDate.orEmpty()
        val time = exportTime.orEmpty()
        val short = if (time.length >= 5) time.substring(0, 5) else time
        val parsed = runCatching {
            LocalDate.parse(date, DateTimeFormatter.ofPattern("dd.MM.yyyy"))
        }.getOrNull() ?: return "$date в $short".trim()
        val today = LocalDate.now(ZoneId.of("Europe/Moscow"))
        return when (parsed) {
            today -> "сегодня в $short"
            today.minusDays(1) -> "вчера в $short"
            else -> "$date в $short"
        }
    }

    fun header(): String {
        return if (cityName.isBlank()) schoolName else "$schoolName ($cityName)"
    }
}

class ScheduleClient {
    fun fetch(): ScheduleInfo {
        val scheduleId = get(CHECK_URL).trim()
        if (scheduleId.isEmpty() || scheduleId.contains("/") || scheduleId.contains("..")) {
            throw IOException("Сайт вернул неожиданный идентификатор")
        }
        val payload = get(DATA_BASE_URL + scheduleId)
        return ScheduleInfo(
            scheduleId = scheduleId,
            exportDate = pick(payload, "EXPORT_DATE"),
            exportTime = pick(payload, "EXPORT_TIME"),
            schoolName = pick(payload, "SCHOOL_NAME") ?: "Школа",
            cityName = pick(payload, "CITY_NAME").orEmpty(),
        )
    }

    private fun pick(text: String, key: String): String? {
        val matcher = Pattern.compile("\"" + Pattern.quote(key) + "\"\\s*:\\s*\"([^\"]+)\"").matcher(text)
        return if (matcher.find()) matcher.group(1) else null
    }

    private fun get(url: String): String {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 15_000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", USER_AGENT)
        }
        try {
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val body = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            if (code !in 200..299) {
                throw IOException("Сайт ответил $code")
            }
            return body
        } finally {
            connection.disconnect()
        }
    }

    companion object {
        const val SCHEDULE_URL = "https://raspisanie.nikasoft.ru/15312761.html"
        private const val CHECK_URL = "https://raspisanie.nikasoft.ru/check/15312761.html"
        private const val DATA_BASE_URL = "https://raspisanie.nikasoft.ru/static/public/"
        private const val USER_AGENT = "RaspisanieSchool6/1.0"
    }
}
