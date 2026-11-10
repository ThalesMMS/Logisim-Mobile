# VoiceOver observed-edge SDK build receipt

Result: `PASS_SDK_BUILD_ONLY`. Runtime: `NOT_RUN`. This package documents the standalone target build already authorized by Root. Packaging and review were offline. No additional Xcode/Gradle invocation, device query, boot, launch, install, XCTest execution or reader operation occurred during packaging.

The frozen preparation commit is `4a78e7a180603e01ee649cbd227dd5ad98cf7a71`. Swift source SHA256 is `5e61214bbc2a9790bf60d4a8ca326ef5fbaffbe0e96104e141fca1fbb84da770`; project spec SHA256 is `21b6a94165bfbf7193bd092c403ca860a240b99da2e3b2176809658007081d55`. Preflight reverified the six preparation files and seven SDK headers. The copied source/spec retained those hashes after the build.

Project generation succeeded in 0.029 seconds. The SDK build succeeded in 8.374 seconds with `TEST BUILD SUCCEEDED`. It used `/tmp/logisim-gradle-serial.py`, the shared `/tmp/logisim-gradle.lock`, explicit Xcode developer directory, `generic/platform=iOS Simulator`, arm64 and `build-for-testing`. DerivedData and build.xcresult were isolated in output001. Complete command vectors, timestamps, environment overrides and logs are preserved.

The generated FormatVersion-1 xctestrun contains only `VoiceOverObservedEdgeUITests` and references its generated runner and test bundle. Its SHA256 is `fdd7d1752d24253c146d52eea8cee018c38675f2a2f399dacb385fcd1c67c643`. No product app target, Kotlin or Gradle dependency was built. This xctestrun is a build artifact, not a runtime authorization or a record of execution.

The build has one warning: `Metadata extraction skipped, no AppIntents.framework dependency found`. It is retained in the full build log and the offline review. No compilation failure was reported.

`observations.tar.gz` contains the receipt, complete gzip-compressed build log, its compressed/uncompressed hashes, project-generation log, exact spec, plan, preflight, new xctestrun and offline review. Native runner/test executables, the generated project payload, DerivedData and build.xcresult are excluded. The existing per-file hashes cover three generated-project files and ten selected product files. No hash of the complete DerivedData or build.xcresult tree is claimed; their recorded paths are preserved. The source remains identified by its frozen commit/hash.

`archive-manifest.json` records every archive entry, the archive itself and this README by byte size and SHA256. The manifest excludes its own bytes to avoid a circular hash. No executable payload is included. The xctestrun still refers to the original external build-products paths; the archive is documentation, not a runnable distribution.

The build proves SDK compilation of the prepared capture target. It does not prove reader execution, visible focus outlines, identity, order, activation, rotor/actions, a complete app workflow, physical hardware or audio quality. Issue #28 remains unqualified by this receipt.
