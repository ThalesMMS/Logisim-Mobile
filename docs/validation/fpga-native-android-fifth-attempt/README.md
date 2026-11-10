# Android FPGA attempt 005 stopped in Downloads navigation

The single `--case all` invocation failed after 130.269 seconds on emulator-5558.
The app reached the verified companion card. No board was selected, mapped, or synthesized.
Issue #65 remains open.

The harness source is `aea3a63f5c2d90acec8786456c328cb0d2821f9f`.
The app and test APKs came from `3a0a01f1af05dd3fea75d5a1499c762d039c478a`.
The snapshot helper came from `5c2abc607d3a734cf8f499c7b3690ba9ffacbc1c`.
The local preflight verified all 247 frozen files, the three APKs, and the immutable catalog checkout.
No product build or installation occurred.

The driver uploaded the complete 536-byte nonce-named board XML.
The uploaded SHA-256 matches the staged file.
MediaStore reported `text/xml`, `is_download=1`, and `is_pending=0`.
This establishes publication, not selection or import.

The first retained DocumentsUI tree already contains one Downloads breadcrumb,
one file list, and no roots drawer. `choose_downloads` nevertheless requested
**Show roots**, then requested **Downloads** once. Twelve subsequent trees retain
the drawer. The last hierarchy query timed out with 1.1226 seconds remaining.
The trace retains both requested taps, their source trees, and every completed query.
It does not establish callback delivery or the window token and process at either tap.
The terminal error identifies `choose_downloads`, before `choose_native_file`.
It does not establish a snapshot helper or application importer defect.

The host recorded zero plans, submissions, cancellations, execution events, and jobs.
The synthesis case failed during preparation. The tool-error and cancellation cases did not run.
Project ZIP export, bitstream generation, iOS execution, and physical programming remain unqualified.
No retry followed this result.

Cleanup verified that port 55307 has no listener and its reverse mapping is absent.
The three installed APK hashes remained exact. The nine recorded settings and
the reported display size and density match their initial values.
The last-ANR output is byte-identical before and after.
Cleanup stopped only the three owned E2E packages.
The system file picker remained foreground, and the nonce XML remained in Downloads.
No full device-preference or viewport restoration is claimed.

The archive contains 136 byte-identical source copies totaling 2,369,131 bytes.
`copy-chain.json` identifies each original file. The public packet includes the
raw failure, screenshots, hierarchy trace, source snapshot, preflight, invocation,
host audit, and cleanup. No APK or executable binary is duplicated here.

`python3 verify.py` verifies the portable packet. Adding `--check-originals`
also verifies every original file when the local producer paths are available.
