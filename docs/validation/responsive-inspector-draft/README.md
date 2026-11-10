# Inspector drafts across responsive layouts

Editing the inline Inspector and crossing from a wide layout to the compact layout removed the old field. The compact sheet was closed, and the old label draft and property dialog belonged to that removed subtree.

The Inspector now owns one session above the layout breakpoint. Its context includes the editor and registry, document identity, circuit, instance path and selected component snapshots. Labels and property drafts keep `TextFieldValue`, including selection and composition. The property dialog has a stable root call site, keyed by the session. Resizing does not reparent that dialog or apply its pending value.

The inline label has a separate focus handoff. It captures intent before the old host is disposed, opens the corresponding compact sheet or returns to the inline host, and directs a request only to that host. A rapid second resize retargets an outstanding request. Focus and bring-into-view finish before its token is consumed. Blur, explicit sheet dismissal and session disposal cancel the matching request. This does not force a new sheet when an unfocused panel or an existing property dialog is resized.

Apply revalidates the current owner, registry, document, circuit, instance, selection and session. Property Apply also requires the same active edit and valid typed value. Cancel changes no document. A disposed session cannot apply through a retained callback, even if that callback still holds its old editor and session.

The final complete Android host task passed **543 tests in 91 suites**, with zero failures, errors or skips. These include 16 session/ownership checks and four actual Compose runtime checks using an empty applier. The latter exercise composition replacement and disposal; they do not mount TextFields, measure layout or execute `FocusRequester` against a native window. A test-only no-op Android profiling Trace lets this empty runtime run on the JVM. It does not emulate focus or IME behavior. The initial profiling-stub failure and subsequent logs are retained separately.

The scoped source review approved the final context and focus state machine. Source formatting and the 2,497-key localization check passed. No app APK, framework, device or Native UI test was built or run for this patch. Actual focus, keyboard, visibility and selection behavior at the real breakpoint remain for Native validation.

The Explorer stays in `activeTool` outside this width branch, so it does not have this reparenting path. Its Native focus and enlarged text are not qualified here. The interactive I/O Keyboard has a separate local draft in `IoInputPanel`; this patch does not claim that subtree survives a presentation transfer. Issues #12 and #28 remain open.

`receipt.json` records the final source hashes and exact host command. `junit-reports.tar.gz` preserves all 91 final JUnit XML reports. The compressed logs preserve their original bytes. Source hashes were collected after the run; no before/after source-hash comparison is claimed.
