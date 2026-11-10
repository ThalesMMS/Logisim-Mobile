# Text and embedded images

`AnnotationLibrary.definitions()` supplies the Base `Text` and `Image` component definitions. Install them in the application registry, replacing the legacy Text definition. Both components have no electrical terminals. Their geometry follows the desktop anchor and alignment conventions.

The annotation panel edits text/font/color/alignment, embeds PNG/JPEG/GIF images, and changes image size and fit/cover/stretch. Images are data URIs in the `.circ` attributes; the mobile boundary rejects file paths and remote URLs. An image is limited to 1 MB and 4096 pixels per dimension. Native decoders validate pixel content before insertion. Imported images should also pass `validateProjectAnnotationImages` before the host accepts the project.

The panel sends one immutable document through `EditorToolContext.applyDocument` per operation. Alignment/distribution moves connected electrical components through `CircuitEditor`; a rejected connection-preserving move aborts the whole result. Layer order is `document.components` order, with the last item on top. Group membership is the `mobile-group` extension attribute; host selection must expand `AnnotationEdits.groupMembers`. A desktop version that ignores unknown attributes may discard mobile group membership when it saves, while text/image content uses standard desktop attributes.

Native wiring: pass `DocumentAssetActions.annotationImagePicker()` into `annotationEditorTool`. The binary asset API distinguishes cancellation from failure. For drawing, use `drawAnnotationComponent`; for export bounds, use `TextMeasurer.annotationVisualItem`, which measures the same font layout that is drawn. `AnnotationLibrary.bounds` remains the desktop-compatible estimate used for document geometry.

Focused checks cover embedded-byte immutability, image header/size rejection, text geometry and attributes, alignment/distribution undo, layer/group order, and terminal-free definitions. Native picker delivery, desktop `.circ` reopening, and touch selection depend on host integration and are not established by those checks.
