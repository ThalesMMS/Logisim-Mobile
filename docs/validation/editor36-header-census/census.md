# Editor36 header census

A generic forward-then-backward reveal can reach navigation controls earlier in the same LazyColumn.

Retained evidence of connection preparation from contract step16 onward; absent measurements stay unknown.

| Run | Actor | Queries | Taps | Requested drags in order | Visibility / viewport |
| --- | --- | ---: | ---: | --- | --- |
| R1 | Components | 2 | 2 | none recorded | not measured |
| R1 | Search | 14 | 1 | 322.61:up, 326.57:up, 330.52:up, 334.50:up, 338.47:up, 342.44:up, 346.43:up, 350.43:up, 354.43:up, 358.43:up, 362.43:up, 366.43:up, 370.45:down | not measured |
| R1 | Ports | 1 | 1 | none recorded | not measured |
| R1 | Start | 2 | 1 | 313.51:up | not measured |
| R1 | Complete | 0 | 0 | none recorded | not measured |
| R2 | Components | 16 | 3 | 365.16:up, 369.14:up, 373.09:up, 377.07:up, 381.05:up, 385.01:up, 389.01:up, 392.98:up, 396.98:up, 400.99:up, 404.99:up, 408.96:up, 412.95:down | not measured |
| R2 | Search | 8 | 3 | 312.99:down | retained measurements; JSON distinguishes field/viewport |
| R2 | Ports | 4 | 3 | 326.95:up | not measured |
| R2 | Start | 4 | 2 | 305.99:up, 358.52:up | not measured |
| R2 | Complete | 2 | 1 | 333.53:up | not measured |

Both retained final trees lack the sheet/list. This does not establish when or how they were dismissed.

Source positions:

- Search: LazyColumn item 2, before commands; source/AccessibleCircuitExplorer.kt:179 at 3a0a01f1af05dd3fea75d5a1499c762d039c478a.
- Components: commands item 3; selected semantics = !showWires; source/AccessibleCircuitExplorer.kt:186 at 3a0a01f1af05dd3fea75d5a1499c762d039c478a.
- Ports: inside a filtered component card, after header items; source/AccessibleCircuitExplorer.kt:286 at 3a0a01f1af05dd3fea75d5a1499c762d039c478a.
- Start: expanded component terminal, after header items; source/AccessibleCircuitExplorer.kt:322 at 3a0a01f1af05dd3fea75d5a1499c762d039c478a.
- Complete: same terminal action switches with pending start; source/AccessibleCircuitExplorer.kt:322 at 3a0a01f1af05dd3fea75d5a1499c762d039c478a.
