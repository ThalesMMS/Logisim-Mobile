# Prepare the observed numeric Delete labels

Phone004 on FINAL `6cef05429cf66be23569aaebc3c72950cf3fe53a` remains a failed run. Its wrapper exited 65, and the selected Inspector method failed after 122.972 seconds before its first Delete. Evidence remains under `outputs/ios-final-6cef-inspector-phone-004/`. This source preparation is **Native NOT_RUN**.

The field capture `31820051-26CB-4D7B-9829-6B65DBFB95F1.txt` records a TextView with exact value `1` and `Keyboard Focused` on its root Attributes. The keyboard tree `D97F41AC-0CC5-47E5-9316-0D17AF857BF0.txt`, line 64, exposes a `Key` labelled exactly `Delete`. Screenshot `EE3712D7-9233-4EE3-A242-D70AB3249E2F.png` preserves that state. The lowercase-only predicate found no matching key.

Only the private Inspector single-digit policy changes. Its existing `keyboard.keys` query now accepts the two exact labels `delete` and `Delete` through `label IN`. It does not use a case-insensitive comparison, a wildcard, or a global key query.

The previously observed single keyboard, Key role, unique match, enabled state, hittability, frame bounds and exact focused value remain required. The policy still performs no second field tap, sends one Delete, requires an exactly empty String, and only then types `0` or `4`. The complete XML, invalid-value, disabled Apply, cancellation, width-only change and Undo assertions are unchanged. Default text replacement and all other cases are unchanged.

The failed run does not qualify the deletion or the later assertions. This delta does not establish that the next native execution will pass.
