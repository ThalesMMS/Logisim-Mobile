package dev.logisim.mobile

import androidx.compose.ui.graphics.asSkiaBitmap
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.isRoot
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Image
import java.io.File

/**
 * Screenshots of the shared Compose editor rendered by Skia on the JVM host, the same renderer as iOS. They are
 * evidence for UI reviews; `-PuiScreenshots=<dir>` (system property `ui.screenshots`) chooses the directory.
 */
internal object HostScreenshots {
    private val root = generateSequence(File("").absoluteFile) { it.parentFile }.first { File(it, "fixtures/user-scenarios").isDirectory }
    val directory: File get() = File(System.getProperty("ui.screenshots") ?: File(root, "outputs/ui-screenshots").path)

    fun fixture(path: String): String = File(root, path).readText()

    fun save(node: SemanticsNodeInteraction, name: String): File {
        val bitmap = node.captureToImage().asSkiaBitmap()
        val data = checkNotNull(Image.makeFromBitmap(bitmap).encodeToData(EncodedImageFormat.PNG))
        directory.mkdirs()
        return File(directory, "$name.png").also { it.writeBytes(data.bytes) }
    }
}

/** Captures the window, including dialog and popup layers drawn into it. */
@OptIn(ExperimentalTestApi::class)
internal fun ComposeUiTest.screenshot(name: String): File = HostScreenshots.save(onAllNodes(isRoot())[0], name)
