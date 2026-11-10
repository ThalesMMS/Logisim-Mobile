# Offline bounds replay

The replay calls the existing `b06cefcc` compiled geometry functions. It does not
compile the app or invoke Compose rendering, Android, Gradle, or a device.
`BoundsReplay.java` reads the captured fixture with `CircCodec`, obtains each
component's bounds, and calls `accessibilityScreenBounds` and
`accessibilityTargetBounds` directly. The Java probe runs on a host JVM.

The final replay used 1,867 inputs. Their bytes and hashes were equal before and
after the run. The compressed report retains the exact commands, source files,
class files, dependency, and raw input identities. No class or JAR binary is part
of the recommended archive inventory in `math-index.json`.

```sh
python3 run.py --output /tmp/a-new-offline-bounds-output
```

The command requires the existing local b06 classes, Kotlin standard library,
and captured native files at the paths declared in `run.py`. It refuses to replace
an existing output directory. The compiled Java probe goes into the new output.

The fixture's component bounds are Input `[80,90,100,110]`, NOT
`[170,91,200,109]`, and Output `[300,90,320,110]`. Density `2.625` makes the
48 dp interaction target 126 pixels. Each component body's projected bounds remain
smaller than that target before and after the pinch.

The probe produces the following rounded targets in screen coordinates. The
screen projection uses the captured final canvas origin `[0,647]`; the earlier
anchor rectangles agree with that origin.

| State | Input | NOT | Output |
| --- | --- | --- | --- |
| Zoom3 composition | `[446,1289,572,1415]` | `[559,1289,685,1415]` | `[707,1289,833,1415]` |
| Zoom4 composition | `[452,1295,578,1421]` | `[542,1295,668,1421]` | `[661,1295,787,1421]` |
| Final pinch composition | `[366,1295,492,1421]` | `[546,1295,672,1421]` | `[783,1295,909,1421]` |

`run.py` models the pinned Compose region algorithm with integer rectangles.
It visits Output, NOT, and Input in reverse sibling order. Each important
component subtracts its full touch bounds from the unaccounted region.
This is a model, not execution of Android's region implementation.

At Zoom4, Output removes the right seven pixels of NOT. NOT then removes the
right 36 pixels of Input. The predicted exposed Input becomes
`[452,1295,542,1421]`, exactly matching the last ready ZoomOut poll. The predicted
NOT center is `601.5`, matching the captured two-pointer centroid. The full
pre-pinch NOT rectangle was not recorded by the dedicated collector.

The final targets do not overlap. Their predicted bounds match all three nodes
in the failure tree. The observed endpoint spans are 227 pixels in the last
ready ZoomOut poll and 417 pixels in the later failure tree. Their ratio is
`1.8370044`, while the retained viewport scale ratio is `2.000000506`.

Those endpoints are not the literal operands of the failed ratio assertion.
The later `beforeDistance` and ratio queries did not serialize their rectangles.
The replay demonstrates that centers of exposed accessibility bounds are not a
linear proxy for scale in this recorded geometry. It does not isolate the exact
value of every query that led to the failure.

The next anchor check did not execute because the ratio assertion failed first.
Its source tolerance is 4 pixels. The model's adjusted NOT center changes from
`601.5` to `609`, or 7.5 pixels. Its geometric body center changes from
`605.442565918` to `609.385314941`, or 3.942749 pixels. These counterfactual values
do not change the recorded test outcome.

Poll101 still matches the prior Zoom3 geometry, while Poll102 matches Zoom4.
No cache operation or cause-isolation experiment occurred. The replay cannot
exclude timing or cache effects in the unretained queries. The native test
remains `FAIL`; no pinch or issue acceptance is inferred from this offline run.
