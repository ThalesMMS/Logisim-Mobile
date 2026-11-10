# Android Files qualification for issue #35

The next unit runs the complete existing `--scenario files` workflow once on `emulator-5558`. This preparation has not contacted Android. The production app, document instrumentation and snapshot helper retain their approved APKs. No APK build is needed.

The current base is `c3d7e000289115582ce901702e32268fce439bb2`. It includes the bounded Downloads navigation fix, the stdin fix and the Search header fix already used by editing R3. Test-only commit `a39f7e140ffb136d91b98868ffb4b2ca61d86da7`, applied locally as `e7933b187d886bf29764b565c0a4a030e520d999`, now requires positive native presentation before cancellation and uses the existing receiver click poll. Nine offline checks pass. Four behavior checks fail against the actual original case bodies. All 34 other methods and all document oracles remain unchanged. The preparation receipt pins the final source bytes.

The earlier import qualification [retains its own scope](android-documents-df276e.md). The existing Save As and process recovery evidence for issue #25 also retains its scope. This unit qualifies the integrated workflow required by #35. The four editing groups already passed in editing R3, archived by commit `22a81c458532952ea4d0cbd77450bbf3f8899aa4` in the separate evidence worktree.

The unit starts with a fresh marked SAP-1 source imported through ACTION_VIEW. Complete copied XML must contain its unique marker, seven circuits, 156 components and 318 wires. Reading the provider's original bytes must show that import preserved the source. The nine existing cases then run in this order:

1. `incoming-share-imports-a-copy`
2. `saf-local-import-preserves-source`
3. `cancel-open-preserves-project`
4. `cancel-export-preserves-project`
5. `cancel-share-preserves-project`
6. `external-receiver-reads-share-grant`
7. `invalid-and-incompatible-files-preserve-project`
8. `save-as-local-document`
9. `recover-create-document-after-process-death`

The SEND and local SAF imports require distinct new XML markers. Each cancellation preserves the complete project bytes. The external receiver must read the temporary URI and report the exact exported byte count and SHA-256. Rejection covers malformed XML, an unavailable external circuit library and invalid UTF-8. All three must preserve the valid project's XML. Both Save As cases read the actual file from Downloads and compare its full bytes and SHA-256 with the editor export. The recovery case kills the background app while CreateDocument is foreground and requires a different nonempty app PID after return. A constant app PID is therefore not an oracle for this unit.

There is one outer result, `native-file-workflow`, plus the nine inner results. Passing only the imports, cancellation or saving subgroup does not qualify this unit. No case is omitted or retried after failure.

The APK SHA-256 values are:

| Artifact | SHA-256 |
| --- | --- |
| App | `10e2b026ad2a398d86a50d12cdb9499b6a03fb89c507cc8a219143f917956973` |
| Document test | `19ab4b9d97f740464e77a6754c8afcf3c1b80d86054f30a28af20ce472f0aa2b` |
| Snapshot helper | `504cbdf78311ede3955cefc748b29e76b3f5f600aa5de25e5974660e9dab3d3c` |
| App build receipt | `e3e70e4c479d82fb8e6eb369100e297a2e8e9bea82d8710233db93b15e55e775` |

The local artifact directory is `/Users/thales/GitHub/logisim-mobile-worktrees/android-io-keyboard-3a-exec/outputs/artifacts-5c-keyboard`. The receipt binds the app to source `3a0a01f1af05dd3fea75d5a1499c762d039c478a`. Local aapt2 inspection confirms the expected app, document instrumentation target and independent snapshot target. The [preparation JSON](android35-files-c3d7-preparation.json) records those identities and the exact source, fixture, localization, APK and receipt hashes.

The controller is prepared at `/Users/thales/GitHub/logisim-mobile-worktrees/android35-files-final/outputs/android35-files-c3d7-5558-001-control/driver.py`. It uses the editing R3 controller's passive subprocess audit hook and does not modify the scenario argv. The planned inner invocation is:

```sh
python3 scripts/e2e-android.py \
  --serial emulator-5558 --scenario files \
  --artifacts /Users/thales/GitHub/logisim-mobile-worktrees/android-io-keyboard-3a-exec/outputs/artifacts-5c-keyboard \
  --build-receipt /Users/thales/GitHub/logisim-mobile-worktrees/android-io-keyboard-3a-exec/outputs/artifacts-5c-keyboard/app-build-receipt.json \
  --language en \
  --output /Users/thales/GitHub/logisim-mobile-worktrees/android35-files-final/outputs/android35-files-c3d7-5558-001
```

Execution waits for the performance owner to return 5558 and for the root's explicit review of this source and plan. Preflight requires a clean frozen source commit, every prepared source hash, all three installed APK hashes, API 35, an English system locale, accessibility disabled, no enabled or bound services and touch exploration false. The controller records device fingerprint, physical size/density, font scale, rotation, locale, IME, activity/window dumps and the three package PIDs. It does not change these settings.

After the single invocation, the controller reads the same settings and installed APK hashes, retains last-ANR output before and after, and verifies every prepared source file again. Setting changes or a changed last-ANR receipt prevent qualification. It preserves the scenario's error and failure artifacts. It also verifies the unchanged local APK and receipt bytes. It then force-stops only the owned isolated app, independently of readback errors, and records the empty app PID. Helper PIDs are observations rather than a new requirement.

The audit log records requested adb commands and monotonic times. A request timestamp does not prove command completion or input delivery. XML from an observed native window proves only the captured window state. This run does not make a causal comparison with the earlier Files failure or claim physical device coverage.
