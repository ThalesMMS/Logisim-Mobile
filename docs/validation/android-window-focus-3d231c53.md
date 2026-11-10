# Native window-focus cancellation

The exact Android focus-loss case passed on producer 3d231c53, using the same test APK, event injector and document oracles as the failing R1. DOWN, ten MOVE events and UP were accepted. The editor window lost focus while the separate Inspector field held it, then recovered focus before UP. The complete exported XML remained byte-for-byte equal to the fixture and pre-gesture document: NOT at (200,100), two wires, 1209 bytes.

R1 changed that NOT to (250,120) and rerouted its wires. The fixed product observes the owning WindowInfo as well as node focus; returning focus does not resume the interrupted gesture. The runtime receipt does not trace the internal callback or infer a cross-clock duration.

Installed APK hashes were unchanged and the last-ANR record was unchanged. This qualifies only the focusloss case on the isolated emulator app. CANCEL, context, the remaining native-input cases and physical-device ergonomics are separate scopes. Full XML, native result, producer and manifest artifacts accompany the JSON receipt.
