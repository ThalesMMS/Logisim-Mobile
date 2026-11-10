# Prepare the missing-document case through Files

This source-only preparation starts from harness base `a515f94d`. Native execution is **NOT_RUN**. The earlier missing-document attempt crashed in `backboardd` during system Open With, before the missing-file target. This preparation does not diagnose that crash, prove its resolution, or replace its failed receipt.

Only `FilesEndToEndTests.testMissingExternalDocumentReportsFailureAndPreservesProject` changes its loading route. It creates the same `missing-external.circ` copy inside the run-owned Documents directory, then uses the shipping **Files → Edit external** action and `NativeFilesPeerNavigator`. The navigator selects the file from the Logisim app's real Files container. This is a local document test, not provider authentication or grant revocation.

Before deleting anything, the setup requires these observations:

1. Confirm the exact selected filename in the import report's origin, if a report appears. Close only that report.
2. Wait for the exact opened-file status for `missing-external.circ` and the staged circuit title.
3. Confirm **Save File** is enabled, proving that this route retained an external association rather than importing a copy.
4. Confirm the selected file and untouched control fixture still contain their original bytes.

The test then captures the same complete XML, removes only its copied fixture and attempts the same Save. Its contextual error, missing-file-versus-conflict, absent destination, unchanged XML, untouched control and usable-editor assertions remain unchanged. It performs no preliminary Save that could modify the input.

Cold and warm Open With remain separate tests and still use `XCUIDevice.shared.system.open`. Their implementation and receipts are unchanged. The shared navigator, default text helper, runner, product code, timeouts and error oracle are also unchanged by this preparation. Execution waits for the coordinated Phone21 and #53/#34 leases to finish.
