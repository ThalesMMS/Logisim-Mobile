# Accessibility bounds and the pinch observer

The offline model reproduces the recorded accessibility rectangles without
changing the viewport or the gesture. This identifies a nonlinearity in the
observer: it uses the centre of an accessibility rectangle as a geometric
component centre. Overlapping accessibility targets can lose part of that
rectangle, so its centre need not follow the viewport transform.

The Native case remains `FAIL/NOT_QUALIFIED`. This analysis does not qualify the
pinch or exclude a temporal contribution to the original assertion failure.

## Geometry reproduced from the compiled producer

`BoundsReplay.java` invokes the existing `b06cefcc` implementations of
`ComponentGeometryKt.bounds`, `accessibilityScreenBounds`, and
`accessibilityTargetBounds`. It reads the actual fixture and uses the recorded
viewport values. It does not run Compose or Android.

The density is 2.625. The overlay sets its minimum target extent to 48 density
independent pixels, which produces a 126-pixel square for all three components
at both endpoint scales. The canvas origin is screen point `[0,647]`.

Before the pinch, the rounded targets are the following rectangles. Their common
vertical interval is `[1295,1421]`.

| Component | Full target x interval | Uncovered x interval | Centre x |
| --- | --- | --- | ---: |
| Input | `[452,578]` | `[452,542]` | 497 |
| NOT | `[542,668]` | `[542,661]` | 601.5 |
| Output | `[661,787]` | `[661,787]` | 724 |

The Input interval is observed in the last ZoomOut poll. The cropped NOT
rectangle is a model, not a directly retained pre-pinch rectangle. Its centre
is corroborated by the native gesture's two-pointer centroid, `[601.5,1358]`,
and the unchanged case source that obtains that point from the NOT node.

The output and NOT targets are later siblings. Removing their occupied regions
leaves exactly the Input width of 90 pixels and the modelled NOT width of 119
pixels. No viewport-edge clamp is needed for these rectangles.

After the pinch, the three targets are disjoint. The failure tree contains
Input `[366,492]`, NOT `[546,672]`, and Output `[783,909]`, with the same vertical
interval. These rectangles match the compiled-function output.

The endpoint accessibility span is therefore 227 pixels before and 417 pixels
after, a ratio of about 1.837. The viewport scale ratio is about 2.0000005.
The full rounded target centres instead produce spans of 209 and 417 pixels.

The literal `beforeDistance` and ratio-assertion span were not serialized.
The 227-pixel span comes from the last preparation poll, and the 417-pixel span
comes from the later failure tree. Their agreement with this model proves a
limit of the observer, but does not transcribe the scalar operands used by the
failed assertion.

## The next anchor check was not reached

The compiled functions put the geometric NOT centre at about 605.4426 before
and 609.3853 after the pinch. The modelled cropped accessibility centre starts
at 601.5, while the later observed accessibility centre is 609.

Those accessibility endpoint centres differ by 7.5 pixels. The original next
check permits 4 pixels. That makes the next check suspect under this observer,
but the ratio assertion stopped execution first. This analysis does not claim
that the anchor or final XML assertion failed or ran.

## The pinned Compose source explains the adjustment

The exact
[UI 1.13.0-beta02 sources archive](https://dl.google.com/dl/android/maven2/androidx/compose/ui/ui-android/1.13.0-beta02/ui-android-1.13.0-beta02-sources.jar)
has SHA-256
`dc792b09830252c0c9f285933172ed63a1e7c67c4ec7e1588a962e04c525241e`.
The archive is referenced, not included in this analysis packet.

`SemanticsOwner.kt` processes children in reverse order, intersects each touch
rectangle with the remaining uncovered region, and removes important siblings'
regions. `AndroidComposeViewAccessibilityDelegateCompat.android.kt` publishes
the resulting adjusted bounds as Android node bounds.

`compose-provenance.json` records exact source-file hashes and method line
ranges, the three resolved UI dependency records, and every raw file read.
The region subtraction here is an offline model of that source, not a recorded
invocation of the accessibility delegate.

## Freshness remains a separate observation

The fourth ZoomOut returned the older 267.5-pixel span in polls 1 through 101.
Poll 102 returned 227 pixels, about 6.1 seconds after the current viewport's
composition record. The reads and composition are not atomic.

That delay remains unexplained. This analysis does not identify a cache or
provider as its cause. The Canvas trace also reports six dropped early state
records, with no dropped input records or logging errors. It must not be
described as a complete trace of every state transition.

No device query, Native run, cache operation, Gradle build, application build,
or product or oracle edit belongs to this analysis.
