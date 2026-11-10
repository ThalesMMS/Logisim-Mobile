# Qualify authentication denial after native document selection

Use the reserved target and the selected signed fixture artifact. Keep the Logisim artifact fingerprint and the fixture fingerprint with the run evidence. The fixture controls never declare the native scenario passed.

1. Install and open the fixture. Tap **Prepare backend**, then **Register fixture domain**. Tap **Inspect fixture domain** and confirm its location appears in the real Files picker. If registration, enumeration or App Group access fails, record the failure and stop.
2. Capture the initial backend fingerprint and fixture evidence. In Logisim, choose **Edit external file** and select the provider's `revocation.circ` through Files. Confirm the document and external association.
3. Change a component label through the editor. Capture the complete local XML and an untouched control document. Wait for the local workspace checkpoint to commit.
4. Terminate Logisim to release its scopes and file descriptors. Preserve its workspace and bookmark. Do not reinstall the app or modify file modes.
5. Open the fixture and tap **Evict, then deny authentication**. Require `eviction.completed`, followed by `authentication.changed` with `allowed=false`. Confirm the backend SHA-256 is unchanged. An eviction error is a failed precondition; the control app leaves authentication allowed in that case.
6. Relaunch Logisim without another picker or Open With request. Confirm its complete local XML and external association are restored. Tap **Save File**.
7. Require a system-dispatched `provider.fetch.denied` or `provider.modify.denied` event for the same stable item. Its error must be `NSFileProviderErrorDomain / -1000`. Require an actual editor error or native authorization error. Do not infer success from the authentication toggle.
8. Dismiss the error and capture the complete local XML. Compare it with the pre-access XML. Confirm the backend fingerprint and control bytes are unchanged. A successful fetch between eviction and the attempted access invalidates the cache-removal precondition.
9. Refresh and share fixture evidence. Correlate request IDs, event sequences, process identities, timestamps, item identifiers and errors with the UI action. Keep screenshots, complete XML, original fingerprints and the XCTest receipt. Record incomplete assertions as incomplete.

If the editor reports Save success before a later provider upload failure, preserve both observations. That result fails the immediate-error assertion. It does not become a pass by observing the upload failure alone.

After evidence is preserved, **Allow authentication** restores fixture state and signals error resolution to its domain. Recovery does not prove the earlier denial. The [fixture reference](README.md) describes supported operations and qualification limits.
