# File Provider document-group keypaths: conflicting Apple references

**The published sources conflict.** Current Apple DocC metadata and the Xcode 27.2 template place both File Provider keys directly under `NSExtension`. The archived 2018 key reference assigns them to `NSExtensionAttributes`; the archived 2017 programming-guide declaration already places DocumentGroup directly under `NSExtension`. These records do not establish a migration rule, alias precedence or the cause of the fixture's discovery failure.

Checked **2026-11-05, 07:33–07:40 UTC**. All ten retained HTTP responses returned **200**: eight initial responses and two additional parent DocC schemas. `raw-pages-manifest.json` preserves their exact URLs, fetch times, byte counts, SHA-256 values and local raw-directory pointer. Full page bodies remain local. This compact package includes technical metadata, declarations and one 14-word prose excerpt; no complete Apple page is included.

| Primary source | Documented keypath | Platform/version and status | What the evidence establishes |
| --- | --- | --- | --- |
| [Archived key reference](https://developer.apple.com/library/archive/documentation/General/Reference/InfoPlistKeyReference/Articles/AppExtensionKeys.html), updated 2018-06-04 | `NSExtension.NSExtensionAttributes.NSExtensionFileProviderDocumentGroup`; same nesting for `NSExtensionFileProviderSupportsEnumeration` | iOS only; Group 8+, Enumeration 11+; archived | Explicit section-wide placement rule. It conflicts with the other concrete declarations below. |
| [Archived programming guide](https://developer.apple.com/library/archive/documentation/General/Conceptual/ExtensibilityPG/FileProvider.html), updated 2017-10-19 | `NSExtension.NSExtensionFileProviderDocumentGroup` | Legacy iOS Document Picker / `NSFileProviderExtension` architecture; archived | The parsed XML example contains Group directly. It does not declare SupportsEnumeration. |
| [Current DocumentGroup leaf](https://developer.apple.com/documentation/bundleresources/information-property-list/nsextension/nsextensionfileproviderdocumentgroup) | `NSExtension.NSExtensionFileProviderDocumentGroup` | iOS/iPadOS 8+, visionOS 1+; no deprecation marker in retrieved metadata | `externalID` and `hierarchy.paths` explicitly model the direct path. The leaf does not list macOS or a replicated-extension exception. |
| [Current SupportsEnumeration leaf](https://developer.apple.com/documentation/bundleresources/information-property-list/nsextension/nsextensionfileprovidersupportsenumeration) | `NSExtension.NSExtensionFileProviderSupportsEnumeration` | iOS/iPadOS 11+, visionOS 1+; no deprecation marker in retrieved metadata | The same direct hierarchy. This is a different key with a later introduction version. |
| [Current parent](https://developer.apple.com/documentation/bundleresources/information-property-list/nsextension) and [Attributes parent](https://developer.apple.com/documentation/bundleresources/information-property-list/nsextension/nsextensionattributes) | Both File Provider topic identifiers belong to the `NSExtension` parent; neither is a topic under Attributes | Parent dictionaries also list macOS 10.10+ | Parent availability must not be transferred to the leaf keys. Generated see-also references are not hierarchy or topic membership. |
| Xcode 27.2 local template, fixture source, installed plist and installer placeholder | Both keys directly under `NSExtension`; no Attributes dictionary | Local evidence, recorded in `local-trees.json` | The current declaration survives into the placeholder. Internal normalized PlugInKit/FileProvider attributes were **not observed**. |

`docc-extracts.json` retains the four schemas' external IDs, platform metadata and complete hierarchy paths, plus the relevant parent topic identifiers and counts. `guide-declaration.json` is parsed from the archived XML. `archive-attributes-sentence.txt` retains the short placement-rule excerpt. `local-trees.json` is an unchanged copy of the separate static comparison, including input hashes and the exact placeholder event.

## Relationship to the retained AD28 failure

The second discovery log contains a successful MCM query for the expected group, followed by rejection of the extension's association with that document group. `discovery-observation.json` retains zero-based source indices 961–963, timestamps, process/thread IDs and messages with the source file's hash. The daemon already names the exact declared Group value. This does not demonstrate an unread or missing key.

A successful identifier lookup and an issued token do not by themselves establish that the extension's document-group association is accepted. Likewise, a signed entitlement declaration, an earlier empty appinfo map and installer entitlement-extraction messages concern different representations and consumers. The cause of their divergence remains unresolved. The native differential remains a separate **FAIL**; this documentation package adds no native result.

## Compatibility question: unexecuted and deprioritized

`compatibility-probe.json` records a **deprioritized, unexecuted** proposal, without an execution recommendation: retain both current direct keys, and add only an identical Group declaration under `NSExtensionAttributes`. Keep SupportsEnumeration in its current location. The conflicting archived rule motivates this narrow compatibility question; it does not establish that duplication is supported or required.

The planned semantic delta is one additional keypath. Code/build inputs, class, IDs, entitlements, signing identity/configuration, group/backend and harness would need independent comparisons. Full executable and signature fingerprints must be recorded separately; re-signing may change an embedded Mach-O signature even when code inputs remain fixed. Changing Info.plist also changes sealed resources and signature bytes, so the probe necessarily needs a newly fingerprinted fixture artifact. It cannot be described as byte-identical 11fc or as changing no signature bytes.

A successful fresh descriptor/registration would support a compatibility effect of the metadata/installation change, without identifying the internal consumer or cause. The same failure would not prove a signing defect, platform incompatibility or an invalid current keypath. Without fresh discovery evidence, the mechanism remains inconclusive. No patch, build, install, device query, retry or native run was performed for this package.

## Offline verification

```sh
python3 docs/validation/ios-fileprovider-keypath-conflict-2026-11-05/verify.py
```

This portable check uses only the compact package. To compare the extracts against the retained original HTTP bodies, supply the directory recorded in `raw-pages-manifest.json`:

```sh
python3 docs/validation/ios-fileprovider-keypath-conflict-2026-11-05/verify.py --raw-dir /absolute/path/to/ios-provider-keypath-primary-pages-001
```

Neither mode performs network or device access. The raw replay checks all ten response hashes, recomputes DocC and declaration extracts, and checks the archived rule in its original section. It verifies documentary evidence, not File Provider behavior. The raw directory may be relocated; its original absolute pointer is provenance only.
