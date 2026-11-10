# VoiceOver service capability on iPhone 17e Simulator

One selected native test passed on iOS 27.0 build 24A434 with product source `3a0a01f1af05dd3fea75d5a1499c762d039c478a`. Native case time was 9.248 seconds; the serialized command took 14.073 seconds. No retry ran.

The actual public VoiceOver service reported disabled initially, enabled while reading/navigating, then disabled again. Returned utterances were `Circuit Sem título.`, `Sem título` and `Projects Button` for current, forward and backward respectively. Both operation and restoration error flags were false. The XCTest export retains all five original service attachments and the exact selected method.

This establishes the local reader-service capability and nonempty speech responses. It does not establish the identity or order of focused destinations, activation/rotor actions, a complete app workflow, spoken audio quality or physical-device behavior. Issue #28 remains open.

The installed app's 12 files, executable and catalog matched the frozen producer before and after. Probe source and xctestrun hashes were unchanged. The final screenshot shows the main app in portrait, 1170 by 2532 pixels. The earlier screenshot is a white presentation surface and establishes only display dimensions, not a mounted editor. The method records initial foreground state and then actual reader output. The native final getter retained in the restoration attachment confirms the original disabled state; no second service probe was run. The lane was returned to Root and no further device action followed.

Preparation plans and SDK receipts in the parent directory preserve their earlier NOT_RUN phase. This native receipt records the later explicitly authorized execution. Full raw attachments, command, summary, log, before/after screenshots, preflight/postflight and frozen test source are retained here with size/SHA256 records in receipt.json. The compressed test log decompresses to the original bytes.
