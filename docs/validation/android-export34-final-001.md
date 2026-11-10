# Android final mounted export attempt

**FAIL, with the whole-PNG subflow verified.** One run used producer `85da031e`, production-equivalent source `6cef0542`, harness `5cb2fead` and its own frozen APK triplet. Package identities, instrumentation targets and hashes were checked with aapt2 before ADB. See [the receipt](android-export34-final-001.json).

The real editor saved the complete drawing through DocumentsUI. Its PNG is **1508 × 908**, contains **5,478,460 bytes**, and has SHA-256 `467feda7339d9a710cb5d70a1c7d6efe74dc8f52a5641f230e06baa118934a4d`. All color samples and all 78 regions passed, including both texts, all 72 symbols and four distant frame edges. The original saved file and independent decoder postflight are retained.

After selecting the image and reopening export in PDF mode, the scenario set filename and scale. The first hierarchy read for Margin then failed with a typed `IncompleteTreeException`: child 11 of 15 was unavailable under path `0/0/0/0/0/0/0/1/0/1`, window 9484, after 42 nodes. Selection-only had not yet been toggled and PDF Save had not run. Sharing and printing were not reached.

The preserved Bundle records one capture attempt lasting 1796 ms, including 796 ms overrun of the unchanged 1000 ms deadline. Root discovery took 96 ms over three root probes. The sole attempt was `INCOMPLETE_TREE`; no retry began after the budget expired. App PID 3378 remained the same before and after instrumentation. This is a typed observer failure with a complete Bundle, not an outer timeout without metadata. These observations do not establish why that child was unavailable.

Collected project documents remain canonically equal through the failure. That partial equality does not qualify preservation after the unexecuted share/print sequence. The full scenario remains failed. SVG was unavailable for this complete fixture under the declared renderer profile; no SVG runtime pass is claimed.

Emulator 5558 was explicitly returned after terminal exit 1. No further ADB/gesture, second run, oracle change or timeout adjustment occurred. Emulator 5556 was not touched. The receipt makes no physical-device or performance claim. Original PNG, document observations, typed observer history and checksums are retained under [artifacts/android-export34-final-001](artifacts/android-export34-final-001/).
