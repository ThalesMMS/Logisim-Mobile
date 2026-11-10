# Android mounted visual export passed

**PASS.** The real-editor scenario completed in **223.55 seconds** on emulator 5558 with the unchanged final APK triplet. Source `85da031e` is production-equivalent to `6cef0542`. The test-client correction permits retries of authenticated incomplete snapshots inside each screen call's original deadline. See [the receipt](android-export34-final-002.json).

The mounted editor verified all of these operations:

- Saved the complete **1508 × 908 PNG** through DocumentsUI. Colors and all **78 ink regions** passed, including both texts, all 72 symbols and the four frame edges.
- Selected the image and saved a **one-page PDF with a 140 × 100 raster**. Independent page/raster/color checks passed.
- Opened the real Android share chooser and cancelled it. The staged share bytes exactly matched the saved PDF.
- Opened the native print preview showing **Page 1 of 1**. The observed page crop contained 8.74% red and 6.84% green samples. The print adapter's staged bytes exactly matched the saved PDF, and Back returned to the editor.
- Exported XML after those actions. Before and after XML have the same SHA-256, `85d27df58497fdf252feadf9b6e854bb9d05996a7d8f0893f50c23e0c2e466d4`. App, test and helper APK hashes were verified before and after.

The run receipt counts 26 complete SDK captures and one authenticated incomplete capture. The retained incomplete traversal took 3,290 ms and reported a 2,290 ms overrun of the helper's unchanged 1,000 ms budget. A subsequent retained SDK capture was complete. Both receipts report app PID 6144 and `expectedScreenPid=null`, and their `startedUptimeMillis` values are 38,222 ms apart. These records do not establish that the captures share one `screen()` invocation or deadline, or which observation route ran between them. Both raw Bundles and the complete XML are retained.

The source policy keeps one absolute caller-provided deadline per `screen()` call, with a 45-second default. Each SDK command uses the smaller of eight seconds and the remaining time. The client permits a retry only after authenticating the incomplete result and rejects partial XML. These source rules do not prove that the two retained captures occurred within the same call.

No recipient was selected and no printer job was sent. SVG remains unavailable for this complete fixture under the declared renderer profile. Earlier failures remain preserved as failures.

The manifest's literal start is **2026-11-05T05:27:41Z**. The Android owner reports a dimensions test began about 05:28 UTC on emulator 5556; this receipt does not assert overlap with the earlier keyboard test. The run makes no physical-device or performance claim. Emulator 5558 was explicitly returned after exit 0, and no later ADB action or gesture was performed by this executor.

Actual exported files, native surfaces, complete result, document comparison, APK checks and snapshot recovery evidence are retained under [artifacts/android-export34-final-002](artifacts/android-export34-final-002/). This Android pass alone does not close issue #34 while iOS qualification remains pending.
