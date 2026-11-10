# Static source/template review

Source HEAD: 11fc44d53b229558fbfc57b66e0b2f2d11a0037b. Working tree clean at capture. `.codegraph/` absent, so source lookup used rg only afterward.

- Provider/RevocationFileProvider.swift:5-6 declares @available(iOS 16.0, *) and final class RevocationFileProvider: NSObject, NSFileProviderReplicatedExtension. No @objc occurrence was found within scripts/ios_fileprovider_revocation. The installed official FileProviderExtension.swift:5 uses class FileProviderExtension: NSObject, NSFileProviderReplicatedExtension, also with no explicit @objc name.
- Config/Provider-Info.plist:13-16 declares com.apple.fileprovider-nonui, $(PRODUCT_MODULE_NAME).RevocationFileProvider, group.thalesmms.logisim.validation.revocationfixture and enumeration support. TemplateInfo.plist:154-161 uses the same point, module-name-prefix class convention, document group prefix and enumeration support.
- Config/App.entitlements and Config/Provider.entitlements both declare exactly group.thalesmms.logisim.validation.revocationfixture; source files are byte-identical. Official entitlements:5-7 uses the same application-groups shape.
- project.yml:29-42 defines RevocationProvider app-extension, Provider + Shared source directories, FileProvider.framework, bundle ID thalesmms.logisim.validation.revocationfixture.provider, Provider-Info.plist and Provider.entitlements. No PRODUCT_MODULE_NAME override was found in the fixture subtree. This source fact is not a proof of the built module/runtime class name.
- Official Swift template:7 explicitly says the containing application must add a domain and the system then initializes the extension with init(domain:). This review does not execute or prove that lifecycle event.

No concrete principal-class source mismatch found. Static source/template agreement cannot prove discovery, class lookup or extension initialization. Parent separately inspects produced/installed artifacts; no OS/signing causal claim follows here.

Evidence: journal.json records every captured source/template command with argv/cwd/exit and stdout/stderr hashes. inputs.json records all eight source/template file byte sizes and SHA256. 015-input-hashes.stdout.txt contains the same file hashes. 019-bounded-source-comparison.stdout.txt contains bounded line-numbered excerpts.
