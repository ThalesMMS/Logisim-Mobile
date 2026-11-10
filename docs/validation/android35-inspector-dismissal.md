# Prepare Inspector dismissal before XML export

The single Android editing run from harness `bdc390d1` failed after 205.805 seconds in the first of its four groups. `editing-empty` and `editing-components` passed. The next Copy XML checkpoint, `editing-property-north`, was not produced. The other three groups did not run.

The failure occurred during `Scenarios.export_xml` receiver preparation and its 30-second `wait_editor`. The terminal 0.462-second CLI dump timeout is the last observation failure, not an established cause. Both retained terminal trees show the E2E_NOT Inspector with Facing set to North. The collector's extracted private `failure-checkpoint-36.circ` also passes the North document oracle. It is a private autosave extraction, not a successful North Copy XML export.

The passive command log retained these requests:

| Request | Elapsed seconds |
| --- | --- |
| Apply tap | 167.788970916 |
| Back key | 167.814440833 |
| Start the XML receiver | 167.839587291 |

The driver did not observe the applied Inspector state before requesting Back, or observe the editor before starting its receiver. These requests do not prove delivery, callback order, keyboard state at the requests, or the cause of the sheet remaining open. The failed run and its original bytes remain preserved.

This source preparation adds `close_inspector` to the editing scenario. It first handles an observed IME through the existing helper, then waits for the app's Inspector, the expected label, no presented Cancel action from a property dialog, and the applied Facing value when specified. Even a partially visible Cancel blocks the background Inspector; an explicitly hidden Cancel does not. It requests Back once to dismiss that Inspector and requires the existing positive `wait_editor` before starting XML export. Insertion checks its applied label and Output's West value; the North edit checks E2E_NOT and North. The four original groups, their shared document contract, XML oracles, text injection and deadlines remain unchanged.

Twenty-eight offline selector checks pass. Three focused controls restore an immediate Back without observing applied state; each new admission check then fails as expected. Independent review found and resolved the partial Cancel counterexample. The final source review is approved. No application code, APK, fixture, native retry or device action changed in this unit. Native execution of this preparation remains `NOT_RUN`.
