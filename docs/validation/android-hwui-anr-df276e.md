# Android HWUI ANR during the file workflow

The `df276e39` application APK (`600f656ac6cbb4661bbff880995b77a1fc240bb0607671bb96d0a010b234a834`) recorded a real ANR during the Files workflow on the API 35 emulator. That run remains **FAILED**. There is no demonstrated production fix or exclusive environmental attribution.

The preserved Main stack waits in `HardwareRenderer.syncAndDrawFrame`. RenderThread waits for the `rcMakeCurrent` readback over the guest-to-host `qemu_pipe` transport. This is context activation/readback; the sample does not establish a GPU draw-completion wait or its duration. It is distinct from the earlier startup parser sample.

The contemporaneous window timeline separates the immediate focus transition from older activity history:

| Device time | Observation |
| --- | --- |
| 19:38:10.248 | Clipboard reader activity finished |
| 19:38:13.417 | Main activity regained focus |
| 19:38:29.302 | Another window of the same MainActivity requested focus |
| 19:38:29.757 | Base window `885af60` lost focus to `78f9aaf` |
| 19:38:34.761 | Focus-event ANR recorded |
| 19:38:36.548 | Focus loss processed, 6.791 seconds after the event |
| 19:38:44.402 | A further activity window `88f7932` appeared |
| 19:38:45.261 | ANR overlay received focus |

The exact role of window `78f9aaf` is not proven by a contemporaneous semantic tag. It must not be labelled as the clipboard reader or assumed to be the Files dialog. Shared SystemUI/Launcher jank is present in the interval, but does not prove the app was uninvolved. Later host/GPU configuration samples are recorded separately from contemporaneous evidence.

A subsequent controlled menu/Share/chooser/cancel run passed in 99.658 seconds using the same application, test and helper APKs, backend, flags and readiness budgets. It preserved the XML exactly, and the last-ANR timestamp remained unchanged. That control is a separate result, not a correction of the failed workflow.

The [targeted external share delivery](android-share-df276e.md) also passed: the receiver read the full restricted content URI with the expected bytes/digest and the editor retained identical XML. Together with the [fresh import receipts](android-documents-df276e.md), this completes the document adapter's functional evidence. The unresolved ANR and full-workflow acceptance remain tracked in issues #35 and #48.

Local evidence is retained under `/Users/thales/GitHub/logisim-mobile-worktrees/android-e2e-df276e/outputs/android-e2e/`: `df276e-files-en/anr-20261104-193835`, `df276e-menu-share-controlled` and `df276e-share-delivery-en`. The independent attribution report is `/tmp/logisim-anr-df276e-attribution.md`, SHA-256 `2c78eb02a245bea7e49dfc040625f965ac555a0f957816d83a81bcdf2319bbd2`.
