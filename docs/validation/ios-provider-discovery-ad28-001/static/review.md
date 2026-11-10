# AD28 static provider review

Scope: read-only static inspection of produced and installed RevocationProvider.appex, plus host entitlement queries, source and the installed Xcode template. No target binary was loaded or executed; no launch, install, registration, device control, build, new runtime test, or source modification was performed.

No concrete static principal-class, architecture, template or signed-entitlement incompatibility was found. This result does not prove provider discovery, Foundation class resolution, domain registration, extension initialization, or an OS/signing cause.

- Source HEAD is 11fc44d53b229558fbfc57b66e0b2f2d11a0037b. The source worktree was clean at capture; .codegraph was absent.
- Produced and installed appex inventories contain five files and are byte-identical, including Info.plist, both dylibs and CodeResources. The principal class in both Info.plist files is RevocationProvider.RevocationFileProvider, extension point com.apple.fileprovider-nonui, document group group.thalesmms.logisim.validation.revocationfixture.
- The arm64 executable is a 38,080-byte debug stub loading @rpath/RevocationProvider.debug.dylib. The real 529,856-byte debug dylib SHA256 is 650f73447e27a6543f3f2fe3a71eb0891666558590c7328da1957211c208d105 in both copies. Its LC_BUILD_VERSION is platform 7 (iOSSimulator), minimum 17.0, SDK 27.2.
- The debug dylib exports _OBJC_CLASS_$__TtC18RevocationProvider22RevocationFileProvider and its metaclass. The ObjC class record names the same Swift runtime class and NSFileProviderReplicatedExtension protocol. Static swift-demangle resolves the runtime name to RevocationProvider.RevocationFileProvider, matching the plist's module/class spelling.
- RevocationFileProvider.swift:5-6 declares an available(iOS16) final NSObject subclass conforming to NSFileProviderReplicatedExtension, without @objc override. The official FileProviderExtension.swift:5 uses the same NSObject/protocol form. TemplateInfo.plist:154-161 uses the same extension point, module-prefix principal convention, document-group shape and enumeration support. No @objc or PRODUCT_MODULE_NAME override was found in the fixture subtree.
- codesign -d --entitlements :- for the produced and installed host apps and extensions contains the same com.apple.security.application-groups array with group.thalesmms.logisim.validation.revocationfixture. Therefore the observed GroupContainers={} is not explained by absence of that entitlement in these signed files. This file fact does not explain container provisioning or registration.
- Both extensions report ad-hoc signatures and no TeamIdentifier. codesign --verify --strict exits 0 for each extension and each embedded dylib. This establishes the queried file signatures' self-consistency, not runtime trust or discovery.

Evidence pointers:
- binary/full-extension-file-hashes.stdout: all relative filenames, byte sizes, SHA256 and equal-inventory result.
- binary/produced-extension-plist.stdout and installed-extension-plist.stdout: effective built extension metadata.
- binary/bounded-static-observations.stdout, principal-runtime-name-demangle.stdout and original nm/otool outputs: class/protocol/platform records.
- binary/*codesign*.stdout and *.stderr: signatures, effective entitlements and verification results.
- source-template/review.md, inputs.json, 019-bounded-source-comparison.stdout.txt: line-numbered source/template comparison and hashes.
- Each subdirectory journal.json preserves argv, exit code, stdout/stderr paths and SHA256. binary/captured-evidence-integrity.stdout confirms captured stream hashes; expected nonzero source searches are recorded rather than converted into success.
