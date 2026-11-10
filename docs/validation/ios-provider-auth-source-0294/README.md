# Provider authentication source preparation

Source `0294ef605e5b78fa441461e978071cf0cee74b07` adds the isolated target described in `scripts/ios_provider_auth_ui/ORACLES.md`. The preparation passed 24 runner checks, 36 pure host oracle checks, six-source iOS Simulator SDK typechecking and XcodeGen resource staging. Independent reviewers checked the public routes, producer events, request window, XML comparison and admission gates.

This archive records source preparation only. It contains no iOS authentication run, new Files-location observation, device installation, native build or registration. The earlier fixture bootstrap remains a separate result. Issue #26 remains open.

Verify the archived bytes and their original Git source objects from any checkout sharing this repository's object database:

```sh
python3 docs/validation/ios-provider-auth-source-0294/verify.py
```

The verifier reads files and Git objects. It does not run Simulator tools, the test binary, the fixture or Main. The source README records the commands for independently repeating the host checks and SDK typecheck. Repeating prepare requires the two preserved artifact producers; it still does not authorize Native execution.

`prepare-results.json` captures a clean source pin, exact Main3a and fixture601 hashes, Simulator sections, staging hashes, null runtime lease and null Simulator UUID. `prepare-commands.json` and its raw stdout/stderr retain the six read-only codesign operations and XcodeGen invocation. The full producer manifests and entitlement review are byte-exact resources in the source commit.

Two review findings were corrected before freezing: a newly observed snapshot could contain an earlier launch denial, and a component-only comparison omitted project/circuit metadata. The final oracle requires request start and terminal timestamps inside the public Save/error window and compares the entire XML element tree with only the selected input's Facing removed. It preserves byte-identical unsaved XML after the denied Save. The temporal association is explicit; Main is not instrumented with a request ID.
