package dev.varun.studio

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import dev.varun.studio.core.AppLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Application entry point.
 *
 * Startup does the minimum possible work: no model is loaded, nothing is
 * downloaded, and no network call is made. Requirement: never download or load
 * a model without user consent. The heavy lifting happens when the user asks.
 */
class VarunApplication : Application() {

    lateinit var container: AppContainer
        private set

    /** Application-scoped coroutines; cancelled only when the process dies. */
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onCreate() {
        super.onCreate()
        instance = this
        container = AppContainer(this)

        // Verbose logging is OFF unless the user enables it in Settings, so
        // nothing sensitive reaches logcat by default.
        AppLog.verbose = BuildConfig.DEBUG

        createNotificationChannels()

        appScope.launch(Dispatchers.IO) {
            runCatching { container.settings.load() }
            runCatching { container.github.restoreFromVault() }
            runCatching { container.models.refresh() }
            runCatching { container.chat.refreshSessions() }
            runCatching { container.tasks.refresh() }
            // Restore the last open workspace without reading any file contents.
            runCatching {
                val last = container.settings.settings.value.lastWorkspacePath
                if (last != null) {
                    val name = java.io.File(last).name
                    container.workspace.open(name)
                }
            }
        }
    }

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
        val channel = NotificationChannel(
            CHANNEL_INFERENCE,
            getString(R.string.notif_channel_inference),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = getString(R.string.notif_inference_body)
            setShowBadge(false)
        }
        runCatching { nm.createNotificationChannel(channel) }
    }

    companion object {
        const val CHANNEL_INFERENCE = "varun_inference"

        @Volatile
        private var instance: VarunApplication? = null

        fun get(context: Context): VarunApplication =
            instance ?: (context.applicationContext as VarunApplication)
    }
}
