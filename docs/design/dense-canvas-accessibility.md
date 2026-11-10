# Dense canvas accessibility decision

Status: prototype comparison completed; production integration and native reader validation remain pending. Source baseline: `a0462cc809f38a22fc1d41a0ab695e369231de8b`.

## Problem and evidence

At the SAP-1 fit view, 111 components compete for a 390 by 484 point canvas. Their 48 point targets require 255,744 square points before accounting for terminals, exceeding the 188,760 square points available. Compose iOS 1.12.2 subtracts important sibling touch bounds before exporting native accessibility frames. Increasing or reordering the targets therefore changes which targets are clipped; it cannot make every overlapping target independently usable.

The native audit on the baseline reported 42 canvas hit-target findings. For example, Comando's 48 by 48 rectangle became 29.3 by 4 after overlap with Valor and its selected terminal. These are interior targets, so viewport edge clamping does not address this failure. The complete graph and editing actions remain available through the existing Circuit explorer.

Issues [#28](https://github.com/ThalesMMS/logisim-mobile-dev/issues/28) and [#12](https://github.com/ThalesMMS/logisim-mobile-dev/issues/12) require equivalent actions, accessible values and connections, useful announcements, and continuity on resize. They do not prescribe simultaneous spatial targets at every zoom. Complete logical navigation through Explorer can satisfy that outcome, subject to real workflow validation.

## Decision

Use candidate C as the base for a **permanent, explicit Circuit explorer entry adjacent to the canvas**. Refine it into a list-first route before integration. Keep the drawing, canvas gestures, viewport and EditorState selection unchanged. The complete Explorer supplies component, terminal, wire, value and editing access at every zoom.

Do not automatically replace a focused spatial subtree when density changes. The initial integration should use the same persistent entry at every zoom, with a localized explanation of the route. A later spatial mode requires an explicit user choice and a separately validated focus-transfer contract. Removing the old spatial nodes is a presentation change disclosed by this route; it must not filter the graph or remove any action.

The current candidate C commit is not ready for production. It still switches between spatial nodes and ListRequired without a focus handoff, and its Show on canvas command can discard a pending connection. Its rationale also describes a node inside the canvas although the delivered entry is an ordinary button outside it. Correct these discrepancies before the native gate.

## Alternatives compared

All three prototypes use the complete CircuitAccessibilityGraph and real EditorState action/history adapters. They are preserved on isolated branches; no prototype was pushed or merged into main by this comparison.

| Candidate | Local commits | What it demonstrates | Cost or missing behavior |
|---|---|---|---|
| A: spatial groups | `d7196d7f` | Disjoint 48 dp cells with 4 dp gaps; every visible component and terminal remains a group member; a reserved entry reaches the full Explorer. | Additional grouping/modal state and uncertain focus after regrouping. The chooser receives frozen readings during RUN, so Refresh and automatic values cannot observe the current simulation. |
| B: fixed focus navigator | `22ee5c31` | Constant controls visit every component, terminal and wire; logical identity survives unrelated changes; actions edit the real project. | An additional tool does not replace the overlapping canvas overlay. It duplicates navigation controls, adds sequential travel and has unvalidated native focus/return behavior. |
| C: zoom/list route | `21fb9791`, `bb0c3b10` | A permanent ordinary button opens the existing Explorer; Show on canvas uses real selection/fit and the active instance path. | Automatic tree replacement and pending-connection exits need correction. Returning to the whole canvas does not identify a focused entity. |

An independent source review scored the delivered candidates against action parity (30%), target geometry (25%), focus/selection (20%), bounded work (15%) and integration cost (10%). A scored 2.65/5, B 3.20/5 and C 3.40/5. The coordinator read their production and test diffs and agrees with C as the smaller integration. These scores are design judgments, not native usability measurements.

Reuse A's shared connection-session principle and B's explicit missing-entity handling. Do not add their grid or navigator to the first integration. A wire index is a position in one revision, not identity. Endpoint identity must reject ambiguous duplicate changes rather than silently act on another wire.

## Caller contract for the next unit

The App owns an immutable context key consisting of documentIdentity, circuit ID and instance path. The Explorer receives this key and a shared connection session. It receives the real simulation, retaining manual capture and optional automatic values. It never edits a filtered document or owns a second selection model.

1. Open the existing accessibility tool from the permanent entry. Retain the current tool-opening behavior that pauses RUN. Report the complete component/terminal/wire coverage and selected context without announcing ticks.
2. Perform all selection, connection, insertion, activation, properties and deletion operations through the existing real adapters. Revalidate the context and entity at callback execution. Reject stale contexts and removed entities without choosing a substitute.
3. Show on canvas accepts a typed component or wire target. It validates the target, selects and fits it through EditorState.focusLocation, closes the tool, then requests focus on the same permanent entry. Its state identifies the selected entity. Keep selection and viewport ownership in EditorState.
4. Choose one explicit pending-connection exit policy. For this first list-first unit, leaving the Explorer cancels once, with visible localized wording before exit and a notice that survives dismissal. Apply the policy to Show, Close, scrim/back/drag dismiss, properties and tool/context replacement. Hiding the keyboard is not an exit. Alternatively, preservation requires keeping the same visible, reachable session across all those paths; silently losing it is unacceptable.
5. Keep list item identities stable while resizing and while values change. An initial entity anchor must resolve by logical identity; never encode a hard-coded lazy-list header offset. A removed entity becomes an explicit unavailable state.

## Focus and activation limits

The persistent entry is an ordinary button in its own layout slot, with a minimum 48 dp native target. It must remain mounted and reachable on small landscape screens and at enlarged font sizes. Requesting focus on this entry after dismissal has a smaller contract than restoring a dynamically removed component node.

In the exact iOS 1.12.2 SDK source, a focused Compose semantics node causes the accessibility mediator to post UIAccessibilityLayoutChangedNotification with the target element. FocusRequester is therefore an indirect native focus request on iOS; its successful return still does not prove VoiceOver accepted or retained that focus. Android separates input focus from accessibility focus, so the same request does not establish TalkBack focus. Validate each platform separately.

Virtual semantics.onClick is also distinct from an ordinary pointer hit. The iOS mediator invokes SemanticsActions.OnClick directly from accessibilityActivate. The prototypes' semantic callbacks are real, but an XCTest coordinate tap through a non-pointer overlay does not prove reader activation. Preserve ordinary pan/zoom and test reader activation separately.

## Verification completed

Direct Kotlin/JVM compilation with the Compose compiler plugin passed for A and B against cached classes from the stated baseline. A's five behavioral tests cover real SAP-1 with 111 components and every terminal, 4,000 components under several node budgets, disjoint geometry, logical regrouping and real selection/activation/connect/remove/undo. B's four tests cover every entity in every SAP-1 circuit, 4,000 components, wire-index changes and real editing/simulation/undo.

C's policy, overlay, tool, Explorer and Canvas compiled through its direct runner; its 15 model tests passed, including SAP-1. Its isolated App compilation was not validated because that runner lacked lifecycle dependencies and contained stale generated/context classes. This is an incomplete integration check, not a native result.

No candidate has completed a full Gradle build, native hit-frame audit, reader action workflow, focus continuity test or hardware VoiceOver/TalkBack qualification. No audit finding was suppressed or filtered for this comparison.

## Next acceptance gate

Build the refined unit from a pinned source and record the exact app/code hashes. On SAP-1 and a large circuit, verify the permanent entry and all reached controls have complete labels, actions and native minimum target frames. Use the full Explorer to insert, connect, select, edit, activate, remove, undo, inspect values, simulate and save. Check context mismatch/deletion, connection exits, repeated open/close, zoom, pan, rotation, keyboard and enlarged fonts. Observe actual reader focus and announcements separately from keyboard focus. Keep the existing canvas drawing and gestures usable.

The outstanding potential-text audit findings in overflow menus and Inspector are separate from this geometry decision. Their active visible text was present in same-context trees, but the findings lacked element/rectangle data. This comparison does not classify them as false positives or authorize suppressing them.
