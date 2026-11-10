package dev.logisim.mobile

import dev.logisim.mobile.core.ArgbImage
import dev.logisim.mobile.core.CircuitComponent
import dev.logisim.mobile.core.ComponentMenuItem
import dev.logisim.mobile.core.EmbeddedImage
import dev.logisim.mobile.core.ImageMenu
import dev.logisim.mobile.core.localization.LocalizedArgumentException
import dev.logisim.mobile.core.localization.LocalizedText
import dev.logisim.mobile.core.localization.Messages
import dev.logisim.mobile.core.localization.localizedDescription
import dev.logisim.mobile.core.localization.message

/** Optimize Image Size waiting for imageOptimizeConfirmMessage. */
internal data class PendingImageOptimization(
    val componentId: String,
    val image: ArgbImage,
    val resize: ImageMenu.Optimization.Resize,
    val scale: String,
)

/** The image Evolution's ImageAttributes caches for the menu, or null without an image. */
internal fun annotationMenuImage(component: CircuitComponent): ArgbImage? {
    val raw = component.attributes["image"].orEmpty()
    if (raw.isEmpty()) return null
    val bitmap = decodeEmbeddedAnnotationImage(EmbeddedImage.fromDataUri(raw).bytes)
    val pixels = IntArray(bitmap.width * bitmap.height)
    bitmap.readPixels(pixels)
    return ImageMenu.cached(ArgbImage(bitmap.width, bitmap.height, pixels))
}

/**
 * The Image component's menu items (std.base.Image, #359). Reset and transparency apply at once as undoable
 * edits; a resize returns the optimization to confirm, and an image already at its target size reports it.
 */
internal fun EditorState.applyImageMenu(item: ComponentMenuItem, id: String): PendingImageOptimization? {
    val component = document.components.firstOrNull { it.id == id } ?: return null
    val image = try { annotationMenuImage(component) } catch (_: Exception) {
        reportStatus(message(Messages.ComponentMenuImageUnavailable))
        return null
    } ?: return null
    when (item) {
        ComponentMenuItem.IMAGE_RESET_SIZE ->
            updateComponentAttributes(id, ImageMenu.resetSize(image), message(Messages.ComponentMenuImageResetSize))
        ComponentMenuItem.IMAGE_MAKE_TRANSPARENT ->
            storeMenuImage(id, ImageMenu.makeWhiteTransparent(image), emptyMap(), message(Messages.ComponentMenuImageMakeTransparent))
        ComponentMenuItem.IMAGE_OPTIMIZE -> {
            val scale = component.attributes["scale"] ?: "fit"
            when (val decision = ImageMenu.optimization(image, component.attributeInt("width", 64), component.attributeInt("height", 64), scale)) {
                is ImageMenu.Optimization.AlreadyOptimized -> reportStatus(message(Messages.ComponentMenuImageAlreadyOptimized,
                    "width" to decision.width, "height" to decision.height))
                is ImageMenu.Optimization.Resize -> return PendingImageOptimization(id, image, decision, scale)
                null -> Unit
            }
        }
        else -> Unit
    }
    return null
}

/** The confirmed Optimize Image Size: the scaled image and, for "fit", the new frame in one action. */
internal fun EditorState.confirmImageOptimization(pending: PendingImageOptimization): Boolean {
    val resize = pending.resize
    val frame = resize.frame?.let { (width, height) -> mapOf("width" to width.toString(), "height" to height.toString()) }.orEmpty()
    return storeMenuImage(pending.componentId, ImageMenu.optimize(pending.image, resize.targetWidth, resize.targetHeight, pending.scale),
        frame, message(Messages.ComponentMenuImageOptimize))
}

/** ImageUtil.bufferedImageToBase64: the result is stored as an embedded PNG. */
private fun EditorState.storeMenuImage(id: String, image: ArgbImage, extra: Map<String, String>, label: LocalizedText): Boolean {
    val stored = try {
        EmbeddedImage.fromBytes(encodeAnnotationPng(image), "image/png").dataUri
    } catch (error: LocalizedArgumentException) {
        reportStatus(error.localizedDescription(message(Messages.ComponentMenuImageUnavailable)))
        return false
    } catch (_: Exception) {
        reportStatus(message(Messages.ComponentMenuImageUnavailable))
        return false
    }
    return updateComponentAttributes(id, mapOf("image" to stored) + extra, label)
}
