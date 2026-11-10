# Accessibility context ownership

Issue [#28](https://github.com/ThalesMMS/logisim-mobile-dev/issues/28) remains open. This change fixes ownership of retained actions and pending terminal connections. It preserves the spatial overlay, Explorer presentation and canvas gestures.

Before the change, an accessibility Remove callback retained from one circuit removed a component with the same ID in another circuit. A connection started before project replacement also connected the matching IDs in the replacement. Both expectations failed in the real EditorState. The original two-test source and complete output are retained under `red/`.

The editor now supplies a context containing the document session, circuit ID and instance path. Every accessibility action checks that context at invocation. Connection sessions bind their origin to it and reject another context. Rename and ordinary edits keep the same circuit ID; a pending connection resolves its terminal positions from the current graph. Both presentations reset their local connection state when this context changes.

The original two assertions are unchanged in the green tests. Their helpers now supply the explicit context and live guard used by App. Eight new tests cover those failures, all eight retained operations, same-context rename/edit/Undo, moved terminals, separate instances, project replacement with matching IDs and copying of the instance path. All 21 direct JUnit tests passed, including the 13 existing model tests. The changed App, Canvas and Overlay also compiled directly with the Compose compiler plugin. No renderer, EditorState, graph, history or simulator behavior was mocked.

The direct runs used copies of cached Android/JVM classes, Kotlin 2.4.21 and Compose 1.13.0-beta02 dependencies. Their reference trees were hashed before and after each run. The raw receipts record exact classpaths and hashes. This is not a full Gradle build or a native application result. The integrator must run the normal source and host gates after applying the commit.

An independent source review found no concrete blocker. No app was installed or launched; no simulator, emulator or physical device was controlled. VoiceOver/TalkBack creation, simulation and saving, focus behavior and the existing geometry/audit findings remain pending. This receipt does not qualify another artifact or close #28.
