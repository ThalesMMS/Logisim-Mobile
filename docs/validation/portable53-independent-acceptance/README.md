# Portable projects independent acceptance

The four recorded Android and iOS bundle and folder flows pass independent artifact review. Twelve offline checks passed against the actual emitted exports and committed workspace snapshots. The final Android-folder native scenario passed in 217.81 seconds. This package records those results and their source provenance. It does not contain another native run.

| Evidence | Native artifact | Independent checks |
| --- | --- | --- |
| Android bundle, 495fccc7 | Product 59d5606b, editor APK aefbd33f… | Storage, Desktop before restart, Desktop after restart: PASS |
| Android folder, 2832d29b | Same product and APK | Storage, Desktop before restart, Desktop after restart: PASS |
| iOS bundle, b811fd26 | Beta producer b9bd71ae, app tree 0b661a20… | Storage, Desktop before restart, Desktop after restart: PASS |
| iOS folder, b811fd26 | Same iOS producer and app | Storage, Desktop before restart, Desktop after restart: PASS |

[The acceptance review](acceptance-review.md) records identities, full hashes, source comparisons and limits. [The combined index](replay/combined-results.json) identifies each copied report and log. Storage-only reports retain nativeUiStatus NOT_RUN. Export reports bind two distinct actual files to one unchanged PASSED native receipt and opposite restart stages. Both mobile and Desktop preserve shared definitions and outputs 8 and 3.

The [Android bundle Desktop-before report](replay/android-bundle-desktop-before.json) is the separate independent execution after the original bundle package. Its SHA-256 is 8d753ff705d2f2c580a8e82ec67c59fec4cc1b6f71577c9bd72de886954e5670. The original 495fccc7 package checked Desktop-after and its identical peer. This review preserves the later before report and all nine earlier replay results byte-for-byte. Final packaging reran only the three checks for the newly passed Android folder.

Recorded native execution remains attributed to the pinned products above. Main85da has adopted the Beta dependency and controller/header changes, but its simulation request and initialization code differs from producer b9bd. The two planned targeted iOS tests on the final main-derived artifact remain NOT_RUN in this package. No current-main native PASS, physical-device validation, performance result, release or issue closure is claimed here.

[The iOS UI trace](review/ios-ui-provenance-review.md) found no dependency of this portable flow on Find, Command-A, clipboard or a candidate-exclusive button. Its stable-main comparison predates the later adoption and remains a historical source trace. The source comparison against adopted main85da is separate. The new main execution check does not require completing every issue27 keyboard scenario.

The frozen oracle sources and staged fixtures are in evidence commit b811fd26 under frozen/offline-replay. Reference core JAR SHA-256 is 7d16a8519f19e033f7ad3350b97402abf492270747b51ed725a9237228f64e20. Desktop JAR SHA-256 is 0f5af2768ae804c79923e942929e19e5e6ff5e9f125fa4c88ecfb09239e09c01. The review used those exact JARs through Java21 and the cached Kotlin2.4.21 compiler, without Gradle or device access. The wrapper records a temporary oracle-checkout revision. That revision does not identify a product build.

Historical absolute paths and timestamps inside copied reports remain unchanged. copy-provenance.json maps each copied file to its original path, byte count and hash. SHA256SUMS covers every package file except itself. Product binaries, source exports, raw snapshots and screenshots remain in the three owning evidence packages rather than being duplicated here. Earlier failed or partial native attempts retain their original statuses.
