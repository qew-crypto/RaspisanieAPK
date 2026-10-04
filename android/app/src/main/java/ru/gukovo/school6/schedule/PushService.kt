package ru.gukovo.school6.schedule

import android.content.Intent
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage

class PushService : FirebaseMessagingService() {
    override fun onNewToken(token: String) {
        val host = DeviceRegistrar(this).hostUrl()
        if (host.isNotEmpty()) {
            runCatching { DeviceRegistrar(this).registerBlocking() }
        }
    }

    override fun onMessageReceived(message: RemoteMessage) {
        val title = message.data["title"] ?: getString(R.string.notify_title)
        val body = message.data["body"] ?: "На сайте новое расписание"
        val scheduleId = message.data["schedule_id"]
        if (!scheduleId.isNullOrBlank()) {
            getSharedPreferences("schedule_state", MODE_PRIVATE)
                .edit()
                .putString("last_schedule_id", scheduleId)
                .apply()
        }
        Notifier.show(this, title, body)
        sendBroadcast(
            Intent(MainActivity.ACTION_UPDATED).apply {
                setPackage(packageName)
                putExtra("body", body)
            },
        )
    }
}
