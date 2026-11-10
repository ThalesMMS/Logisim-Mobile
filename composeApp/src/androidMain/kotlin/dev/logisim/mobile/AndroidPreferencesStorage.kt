package dev.logisim.mobile

import android.content.Context
import dev.logisim.mobile.core.localization.LocalizedArgumentException
import dev.logisim.mobile.core.localization.message

/** Personal defaults stay in this application's sandbox, separate from project XML. */
class AndroidPreferencesStorage(context: Context) : PreferencesStorage {
    private val preferences = context.applicationContext.getSharedPreferences("editor-preferences", Context.MODE_PRIVATE)

    override fun load(): String? = preferences.getString("serialized", null)

    override fun save(serialized: String) {
        if (!preferences.edit().putString("serialized", serialized).commit()) {
            throw LocalizedArgumentException(message("android_preferences_save_failed"))
        }
    }
}
