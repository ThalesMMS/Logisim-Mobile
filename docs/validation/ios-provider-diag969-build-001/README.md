# Provider modify diagnostic build evidence

This archive records the single Simulator build of producer `969f2d08010a22abbd8620f87ca9e382684b4b3f`. Xcode build and ad-hoc signature checks passed. The builder records Native execution and domain registration as `NOT_RUN`. This archive unit did not install, launch, register, sign, or rebuild the fixture.

The diagnostic records the incoming modify field mask, decoded names, unknown bits, options mask, contents URL presence, and requested optional content policy. It also records the fixture-declared policy. The mutation work body and its contents guard remain byte-identical to producer `601cc3733be5aafe4e4d6ef5b565ffb337eeb1cd`. The diagnostic does not implement metadata-only writes or establish the inputs of the earlier failing request.

## Frozen producers

| Evidence | Producer 969 | Reference 601 |
| --- | --- | --- |
| Manifest SHA-256 | `0b8a220201e98ee40e5f0b8a4b56fda97464d97719ae3804f493d3dff8169763` | `c2e976f7ed41dfa60fcd48ef8a4e8defcaadbb7f8042ebd08669dbdf4b778159` |
| App tree SHA-256 | `dc6dd5ac1115cc15d806ebd9330074078b73dcf8352b3907f3b4698995529d04` | `e24e3fb58b07439ba1f9e50e955c76db72509dbf07ba0d8ea19ecce7f3f038d5` |
| Provider tree SHA-256 | `0224d5c6f1add718783039f979e1c40c494baadb3246fdc7caec765c6a506f0d` | `e91733f45ac08a55374bce04083b333004ae4eda1d7bd83f758f84956ecc9f7f` |
| Recursive app and provider file counts | 11 and 5 | 11 and 5 |

`bundle-index.json` records every regular bundle file, executable digest, Mach-O section, and the post-read stability check. The app index includes the nested provider files. The independent index passed 196 static checks.

The complete scan found the Simulator XML and DER section pair in each main executable and each `__preview.dylib`. Debug dylibs contain neither section. The manifest's `sectionOwners: ["main"]` statement covers only its declared main and debug images. It does not cover every Mach-O in the bundle. The retained main section dumps and generated inputs match the preview section digests in the complete index.

Both producers have the same exact app group and generated additional entitlement values. Xcode generated `application-identifier` with the literal prefix `P4VGX44GD8.`. The source entitlements do not declare that prefix or a team. The build command has an empty development team and an ad-hoc identity. The CodeDirectory reports no team identifier. These observations do not establish the prefix's origin, runtime trust, provider discovery, or permission to execute producer 969. Approval of producer 601 is not reused.

## Retained evidence

`producer969` contains the original manifest, all 27 manifest-listed fixture source files, seven original command logs, four main section dumps, four generated entitlement inputs, code-signature entitlement output, and bundle metadata. `reference601` contains its original manifest and manifest-listed source. Absolute historical paths inside evidence are provenance strings. The portable verifier does not open them.

`sdk-review` retains the SDK declaration excerpts, header digests, field decoding specification, and the source-only review. The original SDK checker is historical evidence and depends on its original environment. The portable verifier checks the retained declaration excerpts instead.

The signed executables, complete app bundles, and DerivedData remain outside this archive. Their hashes and complete relative file indices are retained. Replay can verify the archived statements and selected bytes. It cannot independently reconstruct or execute the omitted binaries.

## Post-build test addendum

`post-build-test-addendum-0605` retains commit `0605b5a58c9539509c287134bc36d28c3aeec778`, its two changed files, patch, and the later check logs. Those logs report 28 host tests and 79 literal diagnostic checks passing. The addendum was not used to build producer 969, and no rebuild followed it.

The original 969 source-proof script used `read_text()`, which normalizes CRLF. The addendum corrects that limitation. This archive also compares raw bytes, including the 2,045-byte mutation work body with SHA-256 `d4a8b5c759f149b7a5b380247787a6e422eb253dc45a0b284dbb70566d11b53e`, and the 22 protected reference files. A CRLF mutation is a negative replay case. Historical source and logs remain unchanged.

## Portable replay

From this directory, run the Python-only verifier and mutation tests:

```sh
python3 verify.py
python3 -m unittest -v test_verify.py
```

The default verifier reads only this archive. It does not use Git, the original artifact paths, an SDK, a signing tool, or a device tool. The test suite also copies the archive to an unrelated temporary directory and checks relocated replay.

The optional host replay compiles only the archived Foundation diagnostic helper and literal checks with the host `swiftc`, then runs the resulting host executable:

```sh
python3 verify.py --host-replay
```

Host replay is not Simulator execution. No command above installs the fixture or registers a File Provider domain. `inventory.json` hashes every retained payload file except itself and Python bytecode caches. Changing the inventory does not bypass the semantic negative checks.
