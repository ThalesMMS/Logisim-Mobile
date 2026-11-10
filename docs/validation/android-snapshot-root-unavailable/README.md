# Unavailable Android accessibility root

The Android external-I/O and FPGA cases both stopped before pairing because the snapshot helper could not obtain an accessibility root within its existing nominal 1,000 ms capture budget. The failure returned no XML and traversed no nodes. The application PID remained unchanged. The cause of the missing root in Android or Compose remains undetermined.

The preserved external-I/O capture 47 reports nine root attempts, 1,167 ms of root acquisition and 1,168 ms total capture time. The FPGA capture 1 reports nine root attempts and 1,003 ms for both acquisition and capture. Both helpers returned terminal code `0`, state `ERROR` and `IllegalStateException`. Complete captures collected after each failure came from failure-artifact collection. They do not establish a retry or recovery inside the failed screen call. The scenarios remain failed.

The helper change emits `ROOT_UNAVAILABLE` and `RootUnavailableException` only from the branch where root acquisition finishes without a root. It preserves the existing capture deadline and records any overrun from in-flight Android calls. It does not start another helper attempt for this condition. Generic exceptions, including one with the same message, remain `ERROR`.

The client accepts this new state as eligible for another capture only after checking the current invocation's nonce, expected package, unchanged PID, terminal protocol, typed error, root source/window, zero traversal nodes and a consistent single-attempt history. XML, success metadata, incomplete-tree diagnostics, mixed history and conflicting timing are rejected. The next invocation has a fresh nonce and uses the same absolute screen deadline, with each instrumentation command bounded by the smaller of eight seconds and the remaining time. A changed foreground package after this condition fails rather than switching to the CLI. The earlier incomplete-tree policy is retained.

The original helpers still emit `ERROR`; the updated client deliberately rejects their recorded failures. Recovery requires a rebuilt helper APK. No APK, app framework, native execution or device action was performed for this change.

Validation used 33 client tests and 11 pure Java policy tests. Three selected behavioral expectations failed against the original client and passed with the corrected client. The first baseline invocation had incomplete unittest module names and did not execute tests. Its log is retained separately. Java compilation used only the pure policy and its JUnit tests, with `javac --release 17`; it did not compile Android instrumentation.

The packet's `receipt.json` fixes hashes and sizes for the captured input, collector receipts and test logs. The collector JSON files retain their original references and bundles; only the listed files are archived here. Existing limitations in attributing every node of a successful `COMPLETE` tree to an application window are outside this patch.
