package dev.logisim.mobile

import platform.Foundation.NSUserDefaults

class IosPreferencesStorage(
    private val defaults: NSUserDefaults = NSUserDefaults.standardUserDefaults,
    private val key: String = "editor.preferences",
) : PreferencesStorage {
    override fun load(): String? = defaults.stringForKey(key)

    override fun save(serialized: String) {
        defaults.setObject(serialized, forKey = key)
    }
}
