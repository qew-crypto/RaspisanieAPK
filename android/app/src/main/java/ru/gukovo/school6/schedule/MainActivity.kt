package ru.gukovo.school6.schedule

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.view.Menu
import android.view.MenuItem
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity() {
    private lateinit var webView: WebView
    private lateinit var refresh: SwipeRefreshLayout
    private lateinit var toolbar: MaterialToolbar
    private var latest: ScheduleInfo? = null
    private val registrar by lazy { DeviceRegistrar(this) }

    private val updated = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            toolbar.subtitle = intent?.getStringExtra("body")
            webView.reload()
        }
    }

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
        refresh.setOnRefreshListener {
            webView.reload()
            refreshStatus(notifyIfChanged = true)
        }
        if (savedInstanceState != null) {
            webView.restoreState(savedInstanceState)
        } else {
            webView.loadUrl(ScheduleClient.SCHEDULE_URL)
        }
        askNotificationPermission()
        refreshStatus(notifyIfChanged = true)
        if (registrar.hostUrl().isNotEmpty()) {
            registerHost()
        }
    }

    override fun onStart() {
        super.onStart()
        ContextCompat.registerReceiver(
            this,
            updated,
            IntentFilter(ACTION_UPDATED),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
    }

    override fun onStop() {
        unregisterReceiver(updated)
        super.onStop()
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
            R.id.action_host -> askHost()
            R.id.action_browser -> startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(ScheduleClient.SCHEDULE_URL)))
            R.id.action_about -> MaterialAlertDialogBuilder(this)
                .setTitle(R.string.app_name)
                .setMessage(R.string.about_text)
                .setPositiveButton(android.R.string.ok, null)
                .show()
            else -> return super.onOptionsItemSelected(item)
        }
        return true
    }

    @Deprecated("Deprecated in Java")
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

    private fun askHost() {
        val input = EditText(this).apply {
            setText(registrar.hostUrl())
            hint = getString(R.string.host_hint)
            inputType = InputType.TYPE_TEXT_VARIATION_URI
            val pad = (16 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad, pad, 0)
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.host_title)
            .setView(input)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val url = input.text.toString().trim().trimEnd('/')
                if (url.isEmpty()) {
                    lifecycleScope.launch(Dispatchers.IO) { registrar.unregisterBlocking() }
                    registrar.saveHost("")
                    Toast.makeText(this, "Уведомления с хоста выключены", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                if (!url.startsWith("http://") && !url.startsWith("https://")) {
                    Toast.makeText(this, R.string.host_invalid, Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                registrar.saveHost(url)
                registerHost()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun registerHost() {
        lifecycleScope.launch {
            val message = withContext(Dispatchers.IO) { registrar.registerBlocking() }
            Toast.makeText(this@MainActivity, message, Toast.LENGTH_LONG).show()
        }
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
                Notifier.show(this@MainActivity, getString(R.string.notify_title), "Обновлено ${info.relativeText()}")
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
        MaterialAlertDialogBuilder(this)
            .setTitle(info.header())
            .setMessage("Обновлено ${info.relativeText()}\nДата: ${info.exportDate ?: "—"}\nВремя: ${info.exportTime ?: "—"}")
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    companion object {
        const val ACTION_UPDATED = "ru.gukovo.school6.schedule.UPDATED"
        private const val PREFS = "schedule_state"
        private const val KEY_ID = "last_schedule_id"
    }
}
