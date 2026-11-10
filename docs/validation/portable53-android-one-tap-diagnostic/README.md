# Android portable folder single-tap diagnostic

These two frozen diagnostics follow failed attempt 14368. They are separate evidence and do not change its `FAILED_AFTER_EXPORTS_BEFORE_REOPEN` status. No wrapper or roundtrip was rerun.

The first diagnostic, `one-tap-diagnostic`, abstained with zero taps. Its `dumpsys window windows` output lacked the `mCurrentFocus` field required by its guard. The original receipt records `ABSTAINED` and `The expected native picker is not uniquely focused`. That guard failure describes the available query output, not proof that the native picker had lost focus.

The second diagnostic, `one-tap-diagnostic-retry2`, performed one explicitly authorized tap on the canonical Downloads node inside the native roots list. Before the tap, it captured two identical XML trees and two identical screenshots. The receipt records the same native PID `12229`, focused PickActivity window and target bounds `[168,617][714,668]` across the two observations. The roots-list bounds were `[0,254,735,2337]`.

After the tap, the native PID and focused window were unchanged. The recorded XML has zero `roots_list` and one `dir_list`, and the Downloads file list includes both actual before/after-restart exports from attempt 14368. No export was reopened, no fresh workspace was created by this diagnostic, and no native scenario receipt marked `PASSED` was produced.

This observation establishes the result of this one diagnostic tap. The cause of the earlier failed tap remains undetermined because its trace did not record tap bounds or coordinates. This package does not derive behavior from AOSP source or claim that the installed Google DocumentsUI APK was decompiled.

All 18 original files are copied unchanged. `copy-chain.json` binds their paths, byte lengths and SHA-256 hashes to the frozen source directories. `SHA256SUMS` covers the copied files and this package's authored records. The two original receipts preserve their installed-APK hashes and diagnostic status. No APK or JAR is included. The archived `diagnostic-source.py` files are records of native execution and are not offline replay commands.

Verify the retained bytes offline from this directory:

```sh
shasum -a 256 -c SHA256SUMS
```
