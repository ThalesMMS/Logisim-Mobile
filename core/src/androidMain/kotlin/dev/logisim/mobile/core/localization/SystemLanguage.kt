package dev.logisim.mobile.core.localization

import java.util.Locale

actual fun systemAppLanguage(): AppLanguage = AppLanguage.fromTag(Locale.getDefault().toLanguageTag())
