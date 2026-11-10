# Preserve document context in native provider errors

This isolated preparation starts from producer source `6cef0542`. The observed Missing Files run reached Edit external, an enabled Save action, complete XML capture, deletion and Save. Its alert contained `The file doesn’t exist.` without a filename. The exact native error domain and code remain unknown. This change does not classify that observation as a particular Foundation error.

The boundary still returns the identical fallback object when NSError is absent. Cocoa errors 4 and 260 retain their specific typed missing-document notice. Every other NSError now produces a typed English or Portuguese context containing the selected filename and the original native description. The reason is literal user data, so braces in either argument do not substitute each other or become message identifiers.

The operation flow, writes, storage, security scopes, diagnostic logging and Missing UI test are unchanged. The failed run remains failed. The eight iOS boundary tests cover the specific mappings, contextual unknown and permission errors, literal filename/reason data, fallback identity and real missing-file cases. They were updated but not run in this source-only preparation. **NativeUI_NOT_RUN**. Localization and source checks are separate from native compilation and execution.

Offline checks passed: `generate-localization.py --check --check-ui` (2,497 complete English and Portuguese keys), all 10 `test-localization.py` tests, `check-source.py` and `git diff --check`. No native build, Simulator or device was used.
