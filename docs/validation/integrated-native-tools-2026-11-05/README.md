# Integrated validation tools, 5 November 2026

At commit 2c5c1a14, the integrated harnesses passed 187 focused offline checks and the iOS SDK typecheck of the 19 Swift sources listed in that revision of the Editor target. The SDK receipt records the exact command and source hashes before and after the compiler. This check did not build or run the application.

The first combined layout check exposed a stale negative test. It expected the shared editing export to retain its old, obstructed receiver preparation, even after that shared route had been corrected. The updated test explicitly checks the obstructed-editor failure and then verifies complete XML through both current export routes. All 55 layout checks passed after this test correction.

Production files under core/src, composeApp/src, androidApp/src and iosApp remain byte-identical to producer 3a0a01f1. Localization checks cover 2,497 keys. Regenerated parity files change source fingerprints only; support declarations and verification statuses are unchanged.

Native receipts retain their own producers and results. These integration checks do not turn failed native runs into passes, qualify pending device interactions or close an issue.

A later integration at e0b58f2b passed the SDK typecheck of its 21 declared Editor sources with unchanged inputs. Focused follow-up checks passed: 38 provider-runner checks, 33 Android editing checks, 55 layout checks and 35 Files-source checks. The portable iPhone R4 verifier independently confirmed one selected Native case, 62 completed steps, 20 XML exports and zero failures. It preserves the selected-case scope.

The inventory now adds references to the historical corpus replay in four component entries. These references and source fingerprints do not change support declarations. The only new file under the application source trees is the JVM test workload PerformanceCorpusReplay.kt; application and simulation runtime sources remain unchanged from 3a0a01f1.
