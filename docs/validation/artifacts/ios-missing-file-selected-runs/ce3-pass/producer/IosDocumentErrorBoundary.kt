@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package dev.logisim.mobile

import dev.logisim.mobile.core.localization.LocalizedText
import dev.logisim.mobile.core.localization.Messages
import dev.logisim.mobile.core.localization.message
import platform.Foundation.NSCocoaErrorDomain
import platform.Foundation.NSError
import platform.Foundation.NSFileNoSuchFileError
import platform.Foundation.NSFileReadNoSuchFileError

/** Keep document context while classifying missing-file errors only by Foundation domain and code. */
internal fun iosDocumentProviderNotice(error: NSError?, name: String, fallback: LocalizedText): LocalizedText = when {
    error == null -> fallback
    error.domain == NSCocoaErrorDomain && (error.code == NSFileNoSuchFileError || error.code == NSFileReadNoSuchFileError) ->
        message(Messages.IosDocumentMissing, "name" to name)
    else -> message(Messages.IosDocumentAccessFailed, "name" to name,
        "reason" to LocalizedText.literal(error.localizedDescription))
}
