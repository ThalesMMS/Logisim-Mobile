# Editor36 header premise and census

Premise under review: **a generic forward-then-backward reveal can reach navigation controls earlier in the same LazyColumn**. No next behavior patch was started while preparing this census.

The [rerunnable script](census.py) reads the original R1/R2 logs, attachment manifests, retained Search geometry, executed harness and final trees. It emits the [actor table](census.md) and [full records](census.json). It does not reconstruct missing intermediate trees, gesture delivery or selected state.

```sh
python3 docs/validation/editor36-header-census/census.py \
  --r1 docs/validation/ios-editor36-phone-3a-failure \
  --r2 docs/validation/ios-editor36-phone-3a-r2-failure
```

Add `--markdown` for the table. Inputs may be the portable package directories or the original native output directories. The script does not use a device, Git, an app binary or the locally retained videos. Owning product source snapshots are pinned to the supplied app's `3a0a01f1` revision and checked by hash.

The nine archive tests use the same `--r1` and `--r2` arguments with `test_census.py`. They cover the literal actor counts, missing tap/geometry/tree evidence, a missing earlier attachment and unknown timestamps. Geometry attachments are associated only when each named group's cardinality and timestamp order are unambiguous. Otherwise that group stays unknown rather than shifting later measurements into earlier actions.

## What the census establishes

- R1 reaches source Ports and Start, then fails while revealing Search. Its Search queries are followed by twelve requested upward drags and one downward drag. No Complete tap is recorded.
- R2 records the new short backward Search reveal and a complete first connection UI sequence. It then reaches the next source Ports and Start, but its destination preparation first tries Components. Those Components queries receive twelve requested upward drags and one downward drag before the list becomes unavailable. The new Search helper is not called at that failure point.
- The imbalance belongs to earlier navigation items, rather than all actors. Ports and terminal actions live later in the component cards; upward scrolls do reach them. Changing the global reveal direction would trade one assumption for another.
- Product source puts Search in item 2 and Components in the following commands item. Both precede the expanded terminal cards. The fixed tool header is a separate product area. Components exposes `selected = !showWires`, but neither run retained a native readback of that selected property.

R2 therefore supplies native evidence that the Search preparation reached its intended control. It remains a failed full scenario, with six XMLs captured before connections and no step-18 wired XML. R1 and R2 use the same app but different harness bases, with 17 and 18 prepared files respectively. Their result difference is not an isolated causal experiment.

## Evidence boundaries

The table's query and tap counts describe XCTest requests in connection preparation from contract step 16 onward. A tap request does not establish an entire control frame inside the viewport. Only R2 Search has retained per-field viewport geometry; the other actors' intermediate frames remain unknown. Final trees show the sheet/list absent, without proving a dismissal callback or its exact time. Keyboard Return requests do not establish later keyboard absence. No per-action keyboard/inputView/assistant frame census was retained.

Sampled video frames are useful context in the individual receipts, but their media offsets are not independently synchronized to the native log clock. They are not used to fill missing AX fields in this census.

## Candidate joint unit, not implemented

| Option | Work and risk | Evidence still needed |
| --- | --- | --- |
| Observe Components selection before acting | A private preparation can avoid a redundant touch only when one canonical native Components button publicly reports selected. Missing or ambiguous state must remain unknown. This adds no product layout change. | A native selected-state readback, with correct scope and frame evidence. Source defaults alone are insufficient. |
| Joint preparation of earlier header controls | Reuse the short geometry policy for both Components and Search. Preserve the text policy and Start/Complete commands. Choose direction from the known earlier-section relationship and observed frames, rather than applying a global forward/backward sweep. | Candidate frame, viewport, native input regions, selection state and request order around each transition; a complete unchanged Editor62 run. |
| Stable product header | Moving Search and navigation out of the scrolling body may improve reachability, but consumes vertical space, especially with IME, large text or landscape. Product shortcuts currently call `scrollToItem(2)` for Search; moving the item also changes focus/bring-into-view and layout semantics. This belongs to the layout/accessibility fronts and has a wider validation cost. | A responsive header design and phone/tablet/split, keyboard, font and reader validation. No App change is proposed in this unit. |

The next smallest reviewable unit is joint private preparation with observed selection and geometry, retaining the existing Search result and all 62 oracles. This document does not implement or qualify that unit.
