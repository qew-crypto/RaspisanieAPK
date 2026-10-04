package ru.gukovo.school6.schedule

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    private lateinit var webView: WebView
    private lateinit var refresh: SwipeRefreshLayout
    private lateinit var toolbar: MaterialToolbar
    private var latest: ScheduleInfo? = null

    private val notificationPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        toolbar = findViewById(R.id.toolbar)
        refresh = findViewById(R.id.refresh)
        webView = findViewById(R.id.web)
        setSupportActionBar(toolbar)
        setupWebView()
        refresh.setOnRefreshListener { webView.reload() }
        if (savedInstanceState != null) {
            webView.restoreState(savedInstanceState)
        } else {
            webView.loadUrl(ScheduleClient.SCHEDULE_URL)
        }
        askNotificationPermission()
        refreshStatus(notifyIfChanged = true)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        webView.saveState(outState)
        super.onSaveInstanceState(outState)
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.main_menu, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.action_refresh -> {
                webView.reload()
                refreshStatus(notifyIfChanged = true)
            }
            R.id.action_status -> showStatus()
            R.id.action_browser -> startActivity(
                Intent(Intent.ACTION_VIEW, Uri.parse(ScheduleClient.SCHEDULE_URL)),
            )
            R.id.action_about -> MaterialAlertDialogBuilder(this)
                .setTitle(R.string.app_name)
                .setMessage(R.string.about_text)
                .setPositiveButton(android.R.string.ok, null)
                .show()
            else -> return super.onOptionsItemSelected(item)
        }
        return true
    }

    override fun onBackPressed() {
        if (webView.canGoBack()) {
            webView.goBack()
        } else {
            @Suppress("DEPRECATION")
            super.onBackPressed()
        }
    }

    private fun setupWebView() {
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            useWideViewPort = true
            loadWithOverviewMode = true
            builtInZoomControls = true
            displayZoomControls = false
            allowFileAccess = false
            setSupportMultipleWindows(false)
        }
        webView.webChromeClient = WebChromeClient()
        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                refresh.isRefreshing = false
            }

            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val host = request.url.host.orEmpty()
                if (host == "raspisanie.nikasoft.ru" || host.endsWith(".nikasoft.ru")) {
                    return false
                }
                startActivity(Intent(Intent.ACTION_VIEW, request.url))
                return true
            }
        }
    }

    private fun askNotificationPermission() {
        if (Build.VERSION.SDK_INT < 33) return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    private fun refreshStatus(notifyIfChanged: Boolean) {
        toolbar.subtitle = getString(R.string.status_loading)
        lifecycleScope.launch {
            val info = runCatching {
                withContext(Dispatchers.IO) { ScheduleClient().fetch() }
            }.getOrNull()
            if (info == null) {
                toolbar.subtitle = getString(R.string.status_offline)
                return@launch
            }
            latest = info
            toolbar.subtitle = info.relativeText()
            val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
            val previous = prefs.getString(KEY_ID, null)
            prefs.edit().putString(KEY_ID, info.scheduleId).apply()
            if (notifyIfChanged && previous != null && previous != info.scheduleId) {
                showUpdateNotification(info)
            }
        }
    }

    private fun showStatus() {
        val info = latest
        if (info == null) {
            Toast.makeText(this, R.string.status_offline, Toast.LENGTH_SHORT).show()
            refreshStatus(notifyIfChanged = false)
            return
        }
        val time = info.exportTime ?: "—"
        val date = info.exportDate ?: "—"
        MaterialAlertDialogBuilder(this)
            .setTitle(info.header())
            .setMessage("Обновлено ${info.relativeText()}\nДата: $date\nВремя: $time")
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    private fun showUpdateNotification(info: ScheduleInfo) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        val manager = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26) {
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    getString(R.string.notify_channel),
                    NotificationManager.IMPORTANCE_DEFAULT,
                ),
            )
        }
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_bell)
            .setContentTitle(getString(R.string.notify_title))
            .setContentText("Обновлено ${info.relativeText()}")
            .setAutoCancel(true)
            .setContentIntent(open)
            .build()
        manager.notify(NOTIFICATION_ID, notification)
    }

    companion object {
        private const val PREFS = "schedule_state"
        private const val KEY_ID = "last_schedule_id"
        private const val CHANNEL_ID = "schedule_updates"
        private const val NOTIFICATION_ID = 61
    }
}
