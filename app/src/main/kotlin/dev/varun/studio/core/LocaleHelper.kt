package dev.varun.studio.core

import android.content.Context
import android.content.res.Configuration
import dev.varun.studio.ui.theme.LanguageMode
import java.util.Locale

/**
 * Per-app language support without adding androidx.appcompat.
 *
 * API 33+ has a real framework API (handled by Theme.applyLanguage, which also
 * makes the choice visible in system Settings). Below 33 we wrap the base
 * Context with an overridden Configuration, which is the standard approach and
 * works back to API 17.
 */
object LocaleHelper {

    /** Install in Activity.attachBaseContext for pre-API-33 devices. */
    fun wrap(context: Context, mode: LanguageMode): Context {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            return context
        }
        val tag = when (mode) {
            LanguageMode.SYSTEM -> return context
            LanguageMode.EN -> "en"
            LanguageMode.TA -> "ta"
        }
        val locale = Locale.forLanguageTag(tag)
        Locale.setDefault(locale)
        val config = Configuration(context.resources.configuration)
        config.setLocale(locale)
        config.setLayoutDirection(locale)
        return context.createConfigurationContext(config)
    }

    /** Locale for a Configuration, for diagnostics. */
    fun describe(config: Configuration): String = config.locales?.get(0)?.toLanguageTag() ?: "unknown"
}
