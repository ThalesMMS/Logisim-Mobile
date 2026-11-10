package dev.logisim.mobile.core.localization

import platform.Foundation.NSLocale
import platform.Foundation.preferredLanguages

@OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)
actual fun systemAppLanguage(): AppLanguage =
    AppLanguage.fromTag(NSLocale.preferredLanguages.firstOrNull() as? String)
