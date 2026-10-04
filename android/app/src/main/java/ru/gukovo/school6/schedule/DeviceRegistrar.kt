package ru.gukovo.school6.schedule

import android.content.Context
import com.google.android.gms.tasks.Tasks
import com.google.firebase.FirebaseApp
import com.google.firebase.messaging.FirebaseMessaging
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

class DeviceRegistrar(private val context: Context) {
    fun hostUrl(): String {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_HOST, "")
            .orEmpty()
            .trim()
            .trimEnd('/')
    }

    fun saveHost(url: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_HOST, url.trim().trimEnd('/'))
            .apply()
    }

    fun registerBlocking(): String {
        val host = hostUrl()
        if (!host.startsWith("http://") && !host.startsWith("https://")) {
            return "Адрес хоста не указан"
        }
        if (!firebaseReady()) {
            return "Адрес сохранён. В этой сборке нет Firebase, поэтому закрытому приложению уведомление не придёт"
        }
        val token = try {
            Tasks.await(FirebaseMessaging.getInstance().token)
        } catch (error: Exception) {
            return "Не удалось получить токен уведомлений"
        }
        return post(host, token, "POST")
    }

    fun unregisterBlocking() {
        val host = hostUrl()
        if (!firebaseReady() || host.isEmpty()) return
        val token = runCatching { Tasks.await(FirebaseMessaging.getInstance().token) }.getOrNull() ?: return
        runCatching { post(host, token, "DELETE") }
    }

    private fun firebaseReady(): Boolean {
        return try {
            if (FirebaseApp.getApps(context).isEmpty()) {
                FirebaseApp.initializeApp(context)
            }
            FirebaseApp.getApps(context).isNotEmpty()
        } catch (error: Exception) {
            false
        }
    }

    private fun post(host: String, token: String, method: String): String {
        val connection = (URL("$host/devices").openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 10_000
            readTimeout = 10_000
            doOutput = true
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
        }
        return try {
            val body = JSONObject().put("token", token).toString().toByteArray(Charsets.UTF_8)
            connection.outputStream.use { it.write(body) }
            val code = connection.responseCode
            if (code in 200..299) "Телефон зарегистрирован, уведомления включены" else "Хост ответил $code"
        } catch (error: Exception) {
            "Хост недоступен"
        } finally {
            connection.disconnect()
        }
    }

    companion object {
        private const val PREFS = "schedule_state"
        private const val KEY_HOST = "host_url"
    }
}
