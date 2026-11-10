# Partial first-export inspection

This is the first real bundle exported by the iOS Portable53 test, before process restart. Its 5,395 bytes and SHA-256 `930cebe2bffa952f917a6c7fff5b23fceb2444ea8a05b5c969d225df35ef4113` match both the native Files output archived by the test and the independently retained XCTest ZIP attachment.

The original native receipt remains FAILED: import, the first export and restart completed, but the second export stopped at an ambiguous native folder selector. There is no peer export or reopened workspace in this attempt. This directory does not qualify the complete native flow or issue 53.

The existing `Portable53Oracle.kt` artifact checks ran on this exact exported file. Its ZIP CRCs, mobile source graph, metadata, opaque entries and identities passed. The desktop Logisim JAR then reopened the actual source package and retained shared definitions and outputs 8 and 3. The report records the oracle source hashes and reference JAR hashes; it is explicitly `PARTIAL_REAL_APP_EXPORT_DESKTOP_VALIDATED` with gate `INCOMPLETE_EXPORT1_ONLY`.

The stricter `verify-portable53.py --origin app-export` workflow still requires both real exports bound to the same passed native receipt. It was not changed to accept this failed/incomplete run.
