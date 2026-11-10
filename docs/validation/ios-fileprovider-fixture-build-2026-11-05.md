# FileProvider fixture: build and ad-hoc signature receipt

Frozen fixture source `ff99aadb4991933774eed684dda70805de9d0055` passed a standalone ARM64 Simulator build and ad-hoc signing on 2026-11-05, using SDK 27.2 and deployment target iOS 17. The original run took 8.191 seconds including staging, build and signature checks. Its manifest records `BUILD_AND_ADHOC_SIGN_PASS`; every recorded command exited 0.

The driver signed four embedded dylibs, then the provider extension and containing app. Saved `codesign --verify --deep --strict` logs report both bundles valid on disk; their display logs identify ad-hoc signatures. Extracted App/Provider entitlements each contain only `group.thalesmms.logisim.validation.revocationfixture` under `com.apple.security.application-groups`. This receipt preserves that existing evidence; no signing command was rerun.

| Evidence | SHA-256 |
| --- | --- |
| Original build manifest | `92a7137d1dc28cc094c41d5012343f0ed3fd155730b032949ba1831ff631e6eb` |
| Executed driver | `a601618a424cbcf0f466423a32776631b9a08734b3184dc4afa2aae7d29594f9` |
| Signed app bundle tree | `b56a12ba0f9e7c8aaefa36e62a981ea24694c5518697162d2014b0d144cb4974` |
| Signed provider bundle tree | `06ea5a3e6a30ed9a20ebc84d40b180682487a5718481069ca44facbf4bf92b81` |
| Each extracted entitlement plist | `5089d1514bbfbdaecd9dbae06f4d17c53ca34d48aac56e91326aa3bc534c5a7a` |
| Versioned builder | `e44222c9b33fe41b6d969c64747fe966da2182c8d5359eba3c30db07172e1255` |

The [machine-readable receipt](ios-fileprovider-fixture-build-2026-11-05.json) records all original log/entitlement hashes, executable fingerprints and the five compiled Swift source hashes. The local evidence root is `outputs/ios-fileprovider-fixture-ff99aadb/artifact/`. Read-only verification matched both bundle fingerprints and all 16 manifest source files against the frozen Git pin.

The preservation commit adds the builder, cancellation tests and documentation. It does not rebuild the app or provider: their compiled Swift provenance remains `ff99aadb`. The versioned builder includes one cancellation correction beyond the executed driver. The original driver waited only for its command leader; an isolated reproduction observed two live descendants after its private lock became available. The corrected builder terminates remaining members of its owned process group before releasing the lock.

Two Python checks passed: SIGINT while waiting on a private lock records `INTERRUPTED`, exit 130 and zero commands; SIGTERM during a fake verifier stops TERM-resistant descendants before a competitor acquires that lock while preserving an unrelated process session. Tests use a temporary Git fixture and traps for Xcode, signing and SDK tools. They never use the global build lock or execute native tools. Reproduction instructions are in the [fixture README](../../scripts/ios_fileprovider_revocation/README.md).

Installation, native execution, domain registration, App Group runtime access, provider denial propagation and recovery remain **NOT_RUN**. This build does not qualify security-scope revocation, FileProvider registration, an immediate editor error, or physical-device behavior.
