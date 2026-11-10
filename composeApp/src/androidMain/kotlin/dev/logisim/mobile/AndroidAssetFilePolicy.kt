package dev.logisim.mobile

import dev.logisim.mobile.core.localization.message
import java.io.ByteArrayOutputStream
import java.io.InputStream

internal object AndroidAssetFilePolicy {
    const val MAXIMUM_BYTES = 64 * 1024 * 1024
    private val mimePattern = Regex("[a-z0-9!#$&^_.+-]+/(?:[a-z0-9!#$&^_.+-]+|\\*)")
    private val extensions = mapOf(
        "image/png" to "png", "image/jpeg" to "jpg", "image/gif" to "gif",
        "image/svg+xml" to "svg", "application/pdf" to "pdf", "application/zip" to "zip",
        "application/x-logisim-bundle" to "lsebdl", "application/x-tex" to "tex",
        "application/x-logisim-circuit" to "circ",
    )

    fun mime(value: String): String? {
        if (value.length > 1024) return null
        return value.substringBefore(';').trim().lowercase().takeIf {
            it.length <= 255 && (it == "*/*" || mimePattern.matches(it))
        }
    }

    fun fileName(value: String, mimeType: String): String {
        val cleaned = value.substringAfterLast('/').substringAfterLast('\\')
            .replace(Regex("[\\p{Cntrl}]"), "").trim().trimEnd('.')
            .takeIf { it.isNotBlank() && it != "." && it != ".." } ?: "export"
        val suffix = cleaned.substringAfterLast('.', "").takeIf { it.length in 1..16 &&
            it.all { char -> char.isLetterOrDigit() || char == '_' || char == '-' } }?.let { ".$it" }
        val stem = if (cleaned.length <= 180) cleaned else if (suffix != null) {
            cleaned.take(180 - suffix.length) + suffix
        } else cleaned.take(180)
        val extension = extensions[mimeType]
        return when {
            extension == null -> stem
            stem.endsWith(".$extension", true) -> stem.dropLast(extension.length) + extension
            mimeType == "image/jpeg" && stem.endsWith(".jpeg", true) -> stem.dropLast(4) + "jpeg"
            else -> "$stem.$extension"
        }
    }

    fun detectedMime(bytes: ByteArray, name: String, providerType: String?, acceptedTypes: List<String> = emptyList()): String {
        fun starts(vararg values: Int) = bytes.size >= values.size && values.indices.all {
            bytes[it].toInt() and 255 == values[it]
        }
        return when {
            starts(0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a) -> "image/png"
            starts(0xff, 0xd8, 0xff) -> "image/jpeg"
            starts(0x47, 0x49, 0x46, 0x38, 0x37, 0x61) || starts(0x47, 0x49, 0x46, 0x38, 0x39, 0x61) -> "image/gif"
            starts(0x25, 0x50, 0x44, 0x46, 0x2d) -> "application/pdf"
            starts(0x7f, 0x45, 0x4c, 0x46) -> "application/x-elf"
            starts(0x50, 0x4b, 0x03, 0x04) || starts(0x50, 0x4b, 0x05, 0x06) -> {
                if ("application/java-archive" in acceptedTypes && name.endsWith(".jar", ignoreCase = true)) {
                    "application/java-archive"
                } else {
                    "application/zip"
                }
            }
            else -> providerType?.let(::mime)?.takeIf { it != "application/octet-stream" && it != "*/*" }
                ?: extensions.entries.firstOrNull { name.endsWith(".${it.value}", true) }?.key
                ?: if (name.endsWith(".jpeg", true)) "image/jpeg" else "application/octet-stream"
        }
    }

    fun accepts(mimeType: String, accepted: List<String>): Boolean = accepted.any {
        it == "*/*" || it == "application/octet-stream" || it == mimeType ||
            it.endsWith("/*") && it.substringBefore('/') == mimeType.substringBefore('/')
    }

    fun pickerTypes(accepted: List<String>): List<String> =
        if ("application/octet-stream" in accepted) listOf("*/*") else accepted

    fun createPickerType(name: String, mimeType: String): String =
        if (mimeType == "text/plain" && !name.endsWith(".txt", true)) "application/octet-stream" else mimeType

    fun readBounded(stream: InputStream, maximumBytes: Int): ByteArray {
        if (maximumBytes !in 1..MAXIMUM_BYTES) throw AssetStorageException(message("android_asset_invalid_limit"))
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            val count = stream.read(buffer)
            if (count < 0) break
            if (output.size().toLong() + count > maximumBytes) throw AssetStorageException(message("android_asset_too_large"))
            output.write(buffer, 0, count)
        }
        return output.toByteArray()
    }
}
