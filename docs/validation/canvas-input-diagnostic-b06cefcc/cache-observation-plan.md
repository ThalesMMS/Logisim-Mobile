# ZoomOut observations before another pinch

This is a source review and a proposal. No harness, product, or cache behavior
has been changed, and no additional runtime execution is authorized by this text.

## Retain the actual comparison

The current `horizontalSpan()` queries output and input through separate roots.
`zoomOut()` saves the before-span only in a local variable. The generic readiness
ring cannot preserve all values from the failed comparison.

A dedicated observation for each ZoomOut should retain the following values from
the same queries already used by the predicate:

- Zoom ordinal, before-span, threshold, and action emission result.
- Each poll ordinal, returned span, Boolean outcome, or exception classification.
- Root acquisition IDs, window IDs, input and output rectangles, and their query
  start and end times in elapsed realtime.
- Whether an anchor was missing or a tree was incomplete. Missing data must not
  become a zero span or a successful predicate.

The record must state that the two roots are separate. A subsequent single-tree
observation would be a separately reviewed change, not an implied atomic capture.
Logging must not issue extra SDK queries or change the current 15-second deadline,
50-millisecond interval, input sequence, or comparison.

The proposed storage has four ZoomOut groups, each with its before-record,
terminal record, and up to 320 polls. The current interval allows about 300 polls
in 15 seconds. A compact scalar format can cap each record at 512 bytes and the
whole observation at 768 KiB. The implementation must test its encoded limit.
It must preserve records from the start, rather than overwrite the target in a
generic ring. Any limit or encoding failure sets an explicit incomplete flag.

This proposal does not implement that storage or weaken the original oracle.

## Public SDK35 APIs and their limits

`UiAutomation.clearCache()` is public from API 34 and is present in the inspected
SDK35 `android.jar`. The [official reference](https://developer.android.com/reference/android/app/UiAutomation#clearCache())
and the [API 34 diff](https://developer.android.com/sdk/api_diff/34/changes/android.app.UiAutomation)
confirm that boundary. APK minimum API 26 does not permit an unguarded call.

In [AOSP Android 15](https://android.googlesource.com/platform/frameworks/base/+/android-15.0.0_r1/core/java/android/app/UiAutomation.java#523),
the method requires an established connection and clears the cache associated
with that connection. Previously returned node instances are not refreshed.
Its Boolean result reports whether a cache existed to clear, not whether the
provider or rendered image has become current.
The [cache implementation](https://android.googlesource.com/platform/frameworks/base/+/android-15.0.0_r1/core/java/android/view/accessibility/AccessibilityCache.java#557)
removes window, node, and focus entries. Clearing a cache is not a composition or
pixel-capture barrier.

[`AccessibilityNodeInfo.refresh()`](https://developer.android.com/reference/android/view/accessibility/AccessibilityNodeInfo#refresh())
is public from API 18. Its [AOSP implementation](https://android.googlesource.com/platform/frameworks/base/+/android-15.0.0_r1/core/java/android/view/accessibility/AccessibilityNodeInfo.java#1196)
queries with cache bypass and updates that node instance. It does not clear an
entire descendant tree. [`getChild()`](https://android.googlesource.com/platform/frameworks/base/+/android-15.0.0_r1/core/java/android/view/accessibility/AccessibilityNodeInfo.java#1290)
uses cache lookup even with prefetch strategy zero. The strategy overload is
public from API 33.

The public [UiAutomation root method](https://android.googlesource.com/platform/frameworks/base/+/android-15.0.0_r1/core/java/android/app/UiAutomation.java#923)
has no prefetch argument. Its strategy overload is hidden and absent from the
SDK35 public surface. This proposal does not use reflection or a hidden API.

The archived [SDK declarations](data/api-audit/SDK35-UiAutomation.javap.txt) and
[source provenance](data/api-audit/audit-provenance.json) retain the inspected
SDK hash, pinned AOSP URLs, and source hashes.

## Continuous and new connections differ

The b06 runner stores `instrumentation.uiAutomation` in `MixedInputScenarios`.
The injector also obtains UiAutomation. [Instrumentation reuses the instance
when the requested flags match](https://android.googlesource.com/platform/frameworks/base/+/android-15.0.0_r1/core/java/android/app/Instrumentation.java#2540).

The helper `504c…` obtains UiAutomation in its own instrumentation with
`FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES`. A new connection initializes a
separate cache through [AccessibilityService](https://android.googlesource.com/platform/frameworks/base/+/android-15.0.0_r1/core/java/android/accessibilityservice/AccessibilityService.java#2944)
and [AccessibilityInteractionClient](https://android.googlesource.com/platform/frameworks/base/+/android-15.0.0_r1/core/java/android/view/accessibility/AccessibilityInteractionClient.java#252).
Connection lifetime, flags, and capture time can all differ. That distinction
does not establish stale cache behavior in the recorded failure.

After root review, a diagnostic comparison could retain literal observations
before and after an explicitly selected cache operation or from a new connection.
Such a comparison would need its own authorization, timestamps, unchanged
viewport evidence, and discarded old node references. It must not silently add
cache clearing to the normal oracle or run a pinch before the preparation
measurement is understood.
