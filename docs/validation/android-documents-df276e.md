# Android circuit import qualification

The API 35 emulator imported SAP-1 through external ACTION_VIEW, external ACTION_SEND, and the Storage Access Framework. Each operation used a distinct filename and required the native callback naming that file. Its source also contained a new random root XML attribute, which had to appear in the complete project copied through the editor UI. All three exports retained seven circuits, 156 components and 318 wires, with three distinct source markers.

[The receipt](android-documents-df276e.json) records each exported XML digest, observed marker, captured harness digests, and installed APK digests checked after the three operations. The product is pinned to `df276e39`; its isolated application APK has SHA-256 `600f656ac6cbb4661bbff880995b77a1fc240bb0607671bb96d0a010b234a834`. The test package and auxiliary snapshot APK were also verified unchanged.

These checks replace the earlier positive-import oracle that could accept an already open SAP-1 project. Cancellation, rejection, sharing, saving and recovery retain their separate receipts and scopes. The larger file-workflow run was still active when this receipt was collected; neither that run nor issue #35 is declared complete here.

Raw exports and the corresponding source snapshots remain under the receipt's recorded local artifact directory. The helper snapshot APK is included in provenance; focused-field editing is a separate qualification.
