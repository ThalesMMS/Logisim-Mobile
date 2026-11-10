# .circ document preservation

The codec imports a complete project before the open document is replaced. A structural or reference error or unsupported behavior returns `Failure`. The caller decides whether to replace the project.

| Accepted content | Export behavior |
| --- | --- |
| Root attributes, source version, and descriptive text | Kept in the project. |
| Libraries, attributes, and tool preferences | Opaque XML is preserved. Palette preferences do not override instances. |
| `options`, `mappings`, `toolbar`, and other project elements | Opaque XML is preserved. The reader rejects unsupported simulation policies. |
| Attributes and inner content of `main` | XML is preserved. Only `name` follows the current choice of main circuit. |
| Circuit identity | `id` is stored separately from the name. Renaming preserves the identity. |
| Additional circuit XML attributes | Preserved without interpretation. |
| Circuit attributes defined by `a` elements | Values are preserved, including spaces and line breaks. Structured elements remain opaque XML. |
| Appearance, board maps, and other circuit elements | Opaque XML is preserved. |
| Component identity | `logisim-mobile-id` stores the opaque ID, unique within the circuit and independent of order and position. |
| Component XML attributes | Preserved. Name, library, position, and mobile identity follow the current component fields. |
| `labelfont`, `labelcolor`, `labelvisible`, `labelloc`, `radix`, and other component attributes | Values are preserved, including those the UI cannot edit. |
| `mapinfo`, structured attributes, and auxiliary component elements | Opaque XML is preserved. |
| RAM and ROM contents | The text of the `contents` attribute is preserved. |
| Wires | Positions are preserved. The reader rejects additional attributes, inner content, and invalid segments. |

The codec preserves descriptive text before the project elements. It rejects meaningful text interleaved with those elements before replacing the document.

A round trip preserves document information. XML formatting details such as spacing, quotation marks, and comments may change. The reader rejects DTDs, external entities, and internal processing instructions. It also rejects components or references that the registry cannot recognize before replacing the project.

The `read` API accepts a single circuit and preserves its file context. Use `readProject` and `writeProject` for projects with multiple definitions. Both APIs preserve `main` metadata.

`CircProjectCodecTest` covers preservation of visual attributes, libraries, mappings, the toolbar, memory, and `main` content, including mixed text and main circuit changes. `CircCodecTest` covers explicit rejection of unsupported structures.

## Component identities

The reserved `logisim-mobile-id` XML attribute on `comp` stores `CircuitComponent.id`. IDs are opaque, nonempty, and unique within each circuit. `/` is reserved as the separator for instances and events, so the reader rejects local IDs containing it and never renames them silently. Unicode, `:`, `.`, spaces, and other valid XML characters are preserved. A third-party attribute named `id` remains opaque metadata.

Files without the extension receive legacy `import-N` IDs on the first read. The reader reserves all explicit IDs before assigning generated ones to avoid collisions in mixed files. The first mobile save stores each identity. Removing, moving, reordering, or duplicating components leaves surviving IDs intact. A duplicate uses the new ID assigned by the editor even if copied XML attributes still contain the original value.

The qualified desktop version opens the extension without changing components or geometry, but drops unknown attributes on save. Component IDs may therefore be lost on desktop save. Restoring selections and instance paths from persisted XML requires evidence of IDs in the source. Generated `import-N` values from a file without the extension cannot establish the prior identity. The restore flow applies this check while allowing new models that have yet to be serialized.

`CircComponentIdentityTest` covers a regression in which a path previously went from `import-2@(200,100)` to a different instance at `(300,100)` after the first instance was deleted. The oracle and its receipt are in `scripts/verify-circ-component-identities.sh` and `docs/validation/component-identities-desktop.json`.

The impact check in `docs/validation/component-identity-scope.json` reproduced a collision between local instance `a/b` and the path through `a` followed by `b`. The component constructor and codec boundaries enforce the separator restriction. `ComponentIdentityScopeTest` verifies that IDs containing `:`, `.`, or Unicode and their corresponding hierarchical paths keep state and signals separate.
