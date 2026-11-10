# Native cancellation and mouse context menu

Two native-input cases passed on candidate 76ea1ec1: cancellation with a normal-release control and one Undo, and the secondary mouse context menu with Duplicate and one Undo. The adjacent per-case JSON receipts and artifact folders contain full XML exports, native results, installed APK verification and source producers.

The cancel case accepted DOWN + 10 MOVE + CANCEL and preserved the complete document. A second DOWN + 10 MOVE + UP moved the NOT from (200,100) to (250,120) with the expected wire routing. One Ctrl+Z restored the original XML byte-for-byte. The body point was observed before CANCEL and reused only after a complete tree proved identical window, canvas and both input/output anchors.

The mouse case observed a popup in a different native window, invoked Duplicate, then waited for the original editor window before export. The exported document contained exactly the expected NOT copy at (220,120), and one Undo restored the original bytes.

These are synthesized Android SDK input tests on an emulator. They qualify these two cases only. The other six input cases and issue #27 remain open; no physical-input ergonomics or performance qualification is claimed.
