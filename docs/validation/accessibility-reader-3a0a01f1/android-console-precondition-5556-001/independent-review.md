# Offline review of console-reader unit 5556-001

Result: `NOT_QUALIFIED_STOPPED_BEFORE_HARDWARE_CONTACT`. No Down, passive getevent capture or runtime lease artifact was reached. The 49 recorded argv contain no console event send, getevent or --execute. Public Settings actions are setup/restoration, not a reader-gesture result. No device, reader, GUI, build or shared-file change was performed by this review.

The before/after SDK gate stopped on the same representation mismatch: the real Bound record contains `eventTypes=TYPES_ALL_MASK`, while the frozen parser requires a bracketed event-types list. Both dumps show touch exploration true, spoken feedback and capabilities251. Their AMS records contain the exact TalkBack component, published BinderProxy and the system PID582 client connection. The local AOSP15 dump callsite delegates event formatting to AccessibilityEvent.eventTypeToString; the callee was not newly fetched in this offline pass. This is a validation-parser failure, without attribution to the app or reader.

The three SDK captures have distinct nonces, complete metadata/XML, PID9541 stable before/after, instrumentation code-1, ADB exit0 and empty stderr. XML011 has one focused container at [21,137][222,263] with Projects text/button descendants; PNG012 independently shows its Projects ring. Matching Projects caption/speech has not been demonstrated. No audio stream exists in the private MP4. A remains unqualified, and no B or kernel sequence exists to assess.

The private frame named frame-055.png shows the Android Accessibility Suite notification prompt with a green ring on Allow. PNG010 has no prompt, so that still image could not establish a prompt-free setup throughout the interval. The operator reports no notification-button click and that app launch covered the prompt. Neither app foreground nor final denied permission proves that the prompt was dismissed, denied or closed. Exact UTC alignment is not inferred.

Timing correction: the operator extracted frame-055.png using ffmpeg -ss55. Its name is the requested offset55seconds; its exact source PTS was not archived. My earlier association with27.596478seconds was wrong: that value belongs to source frame index55 from ffprobe, not demonstrably to the seek-extracted image. The association is withdrawn and is not used in this review.

Restoration is supported by the eight saved public getters returning to their original values, reader0/services empty, final Bound empty/touch exploration false, and speech display OFF in PNG022 with ERROR visible. The three APK hash records match before/after and POST_NOTIFICATIONS remains denied with the same flags. These are captured hash records, not a new independent rehash of remote APK bytes. Opaque preferences and the pending dialog lifecycle are not claimed byte-identical.

The before/final app trees contain100entries including hierarchy and the same shape. Their only non-root attribute difference is transient text: Circuit Sem título. becomes Operation cancelled. Root nonce/uptime also change. All other retained attributes, including geometry, match. This does not mean identical XML bytes or all internal application state.

Observer session6428 exit0 is operator-reported; the saved finish receipt alone explicitly requires independent restoration evidence. private-video.json records screenrecord session52513 exit0. The operator reports lane return. This offline review does not query it.

review.json carries exact paths and limits. evidence-hashes.json hashes the selected existing evidence. The private video and image are referenced only and are not copied here. No gesture, traversal order, reader workflow or issue28 closure is qualified.
