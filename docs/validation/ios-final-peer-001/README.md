# FINAL 6cef local peer-file acceptance

One selected native testcase, `ExternalProviderEndToEndTests/testPeerDocumentCopyEditRestartAndConflict`, passed in **274.482 seconds** on iPad Simulator B25. Xcresult records one pass, zero failures and zero skips; the wrapper exited 0. The raw results retain **`qualification_gate=PARTIAL`**.

The recorded producer is **`6cef05429cf66be23569aaebc3c72950cf3fe53a`**, executable SHA-256 `198a06dc847cc0fd8717b7c05b06f334b8a1e701aaf63aed5a6012699be5b946`, bundle tree `d987d43d6587c0139ff0ecddca2d4ae95c9bad66ba6cd19d29c6452214be3080`. Archived preflight and [independent postflight](evidence/independent-postflight.json) identity records agree. This archival review did not read the live installed app or any current-run fixture.

Run **`5f364710641a`** selected `peer.circ` through native Files in the separate local **Clipboard Receiver** app container. The preserved source, log and UI evidence support:

1. Import a copy, edit only the input-pin label, keep Save disabled and preserve the peer/control originals during Copy.
2. Select Edit external through Files, reload the original baseline, enable Save and write the complete edited XML into the actual peer file.
3. Terminate and launch Logisim without another picker or Open With request; restore the complete XML and write association, then edit and save again into the actual peer.
4. Append a run-specific comment to the peer **from the harness**, attempt a stale Save and display the production conflict notice. Preserve the injected revision, the complete local XML and the untouched control.

| Observed editor XML | Bytes | SHA-256 |
| --- | ---: | --- |
| Copy/external baseline | 1567 | `48e5ea375be43cdc473f951927d492a834d5c1cbe7bc2c17c5c6616ec2d47354` |
| Edited copy | 1583 | `f67433f0968fc5964a28e41253c9fbc6dda10ce0a1076c82f942f978a7699989` |
| First save / restored after restart | 1583 | `7a2447c4aef8bd89d19d936c1e9eae43c0e4acc6d2add2fc8f9eb149e88b2b31` |
| Second save / local after conflict | 1586 | `77113b1fb40fd34f87eab526654d2515a17916820f30634790d504b186f39c15` |

All **seven complete UI XML attachments** are archived. Copy/external baselines are byte-identical; first save/restored XML are byte-identical; second save/local-after-conflict XML are byte-identical. The portable verifier independently checks the full structure and complete XML bytes across each edit: exactly one intended Pin `(100,100)` label attribute is replaced.

The exact initial input and actual final control are 1,375 bytes, SHA-256 `f0cd987911c1a965ca95f1a42c3287bee70ba3d08900a5d09a7d33eea0da1604`. Local XML after conflict is 1,586 bytes, SHA-256 `77113b1fb40fd34f87eab526654d2515a17916820f30634790d504b186f39c15`. The actual final peer and injected revision are 1,650 bytes, SHA-256 `a0ea1ac33f3041d3fc2a05c089a2f4d096e12f38648b71cfda3de20a793362d7`: exactly the second saved XML plus the deliberate comment. The independent postflight confirms these relations. Raw input and observed editor serialization have different formatting; no byte-equality claim is made between those distinct representations.

The archive includes raw results/PARTIAL status, local xcresult summary, original native receipt attachment **A7D4B37F…**, independent postflight, input/control/revision bytes, six executed helper/source snapshots, test-bundle configuration, native-picker screenshots/trees for both modes, two saved-notice screenshots/trees and conflict evidence. Files displays the exact selected Cell as `peer, circ`, with visible stem `peer`; this matches the frozen navigator's extension-hiding rule.

[Minimal artifacts](minimal-artifacts.json) records each copied path, size, SHA-256 and purpose. `SHA256SUMS` additionally covers all committed files except itself. The scoped `.gitattributes` preserves the original XCTest log, including its three trailing-space lines, without treating archived bytes as authored whitespace errors. Run:

```sh
python3 docs/validation/ios-final-peer-001/verify-archived-evidence.py
```

The check reads only archived evidence. It also checks the three native Save taps around process restart, both saved notices, recorded FINAL identity before/after execution, and the coordinator's postflight. It does not access historical Simulator paths contained in JSON metadata.

This qualifies **local other-app Files only**. It does not qualify cloud, a FileProvider extension, authentication/security-scope revocation, Open With, phone, physical hardware or the whole app. The peer is intentionally changed after Copy; only the control remains original at the end. No current Missing fixture, simulator, cache, registration, canonical checkout or production source was touched while preparing this docs-only archive based on `1ea1b4db`.
