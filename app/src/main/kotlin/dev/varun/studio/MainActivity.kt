package dev.varun.studio

import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import dev.varun.studio.core.AppLog
import dev.varun.studio.core.LocaleHelper
import dev.varun.studio.ui.NavGraph
import dev.varun.studio.ui.theme.VarunTheme
import dev.varun.studio.ui.theme.applyLanguage
import dev.varun.studio.ui.theme.languageModeFromString
import dev.varun.studio.ui.theme.themeModeFromString
import dev.varun.studio.ui.vm.SettingsViewModel
import kotlinx.coroutines.launch

/**
 * Single-activity host.
 *
 * Portrait-first but not locked: a phone keyboard plus a diff view genuinely
 * benefits from landscape, and locking orientation breaks external displays and
 * accessibility. Layouts are written to survive both.
 */
class MainActivity : ComponentActivity() {

    private val appContainer: AppContainer by lazy { VarunApplication.get(this).container }

    private val settingsViewModel: SettingsViewModel by viewModels { appContainer.viewModelFactory }

    /** Pre-API-33 language override has to be installed here. */
    override fun attachBaseContext(newBase: Context) {
        val mode = runCatching {
            languageModeFromString(
                dev.varun.studio.data.db.VarunDatabase.get(newBase)
                    .getSetting("language_mode") ?: "system",
            )
        }.getOrDefault(dev.varun.studio.ui.theme.LanguageMode.SYSTEM)
        super.attachBaseContext(LocaleHelper.wrap(newBase, mode))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        if (savedInstanceState == null) {
            // Ask for notification permission once, on Android 13+. Declining is
            // fine: the foreground service still runs, just without a visible
            // notification on some OEM builds.
            maybeRequestNotificationPermission()
        }

        setContent {
            val settings by settingsViewModel.ui.collectAsState()
            val themeMode = themeModeFromString(settings.settings.themeMode)
            val languageMode = languageModeFromString(settings.settings.languageMode)

            // Reflect a language change made at runtime (API 33+ path).
            androidx.compose.runtime.LaunchedEffect(languageMode) {
                applyLanguage(this@MainActivity, languageMode)
            }

            VarunTheme(themeMode = themeMode, fontScale = settings.settings.fontSizeScale) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background,
                ) {
                    NavGraph(container = appContainer)
                }
            }
        }
    }

    private fun maybeRequestNotificationPermissions() {
        if (android.os.Build.VERSION.SDK_INT < 33) return
        val granted = androidx.core.content.ContextCompat.checkSelfPermission(
            this,
            android.Manifest.permission.POST_NOTIFICATIONS,
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        if (!granted) {
            runCatching {
                androidx.core.app.ActivityCompat.requestPermissions(
                    this,
                    arrayOf(android.Manifest.permission.POST_NOTIFICATIONS),
                    REQ_NOTIFICATIONS,
                )
            }.onFailure { AppLog.w("Could not request notification permission", it) }
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_NOTIFICATIONS) {
            val granted = grantResults.isNotEmpty() &&
                grantResults[0] == android.content.pm.PackageManager.PERMISSION_GRANTED
            AppLog.i("Notification permission granted=$granted")
        }
    }

    /**
     * Trim memory is our low-memory hook: release the model rather than let
     * Android kill the whole process and lose the conversation.
     *
     * This directly addresses acceptance criterion 14 (low-memory failures must
     * not crash the app unnecessarily).
     */
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level >= TRIM_MEMORY_RUNNING_CRITICAL) {
            val keep = appContainer.settings.settings.value.keepModelLoaded
            if (!keep && appContainer.engine.isLoaded) {
                AppLog.w("onTrimMemory($level): unloading the model to avoid being killed")
                VarunApplication.get(this).appScope.launch {
                    appContainer.engine.unload()
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (isFinishing) appContainer.engine.release()
    }

    private companion object {
        const val REQ_NOTIFICATIONS = 4101
    }
}
