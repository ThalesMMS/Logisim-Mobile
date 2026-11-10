# Editor chrome and toolbars

The editor follows Logisim Evolution's window: a menu bar, a tool toolbar, an explorer with the attribute table, the canvas and a status line with the zoom control. On mobile, each of these is a single compact bar so that the circuit keeps most of the screen. Before this layout, up to five stacked bars (workspace, project, commands, simulation and signals) took about 270 points of an iPad's height; the chrome now takes one 50-point header, one 50-point toolbar and a 34-point status line.

## Header

`EditorHeader` (`editor-project-toolbar`) holds, from left to right:

- the explorer button: on wide windows it shows or hides the sidebar; on narrow ones it opens the explorer sheet;
- the menus **File**, **Edit**, **View**, **Project**, **Simulate** and **Help** (`menu-title-<id>`, menus `editor-menu-<id>`). Below 720 points, or when the simulation controls share the header below 1000 points, they fold into one compact menu (`editor-menu`) with drill-down submenus;
- the circuit name, with the workspace project and its save state below it; tapping it opens the project list;
- the simulation controls when the toolbar is a side rail or hidden (see below);
- undo and redo.

The workspace host passes its project name, save state and project list through `EditorWorkspaceHeader` instead of drawing its own bar.

Menu items carry the commands that used to be toolbar buttons: file actions, examples and preferences (File); clipboard, selection and properties (Edit); explorer, signals, zoom, fit and printer view (View); library management and every editor tool (Project); run, step, tick, automatic ticks, reset, tick frequency, simulation options and warnings (Simulate); help and licenses (Help).

Menus use non-focusable popups. On iOS, a focusable popup over the editor was dismissed as soon as it appeared, and the editor keeps keyboard focus for its shortcuts.

## Tool toolbar

`ChromeToolbar` (`editor-command-toolbar`) shows the project's own `<toolbar>` from the `.circ` file. A project without one gets Evolution's default toolbar from `default.templ`: Poke, Edit, Wiring and Text tools, input and output pins, and the NOT, AND, OR, XOR, NAND and NOR gates. The touch-only Move mode joins the first group of tools.

Icons are vector glyphs drawn on Evolution's 16-unit icon grid (`EditorIcons.kt`): the poke hand and edit arrow use Evolution's own outlines, pins use the classic box and circle with the true-value green, and the simulation controls use Evolution's play, step and tick icons. Actions without a glyph show their name.

When the tools do not fit, the toolbar places whole buttons and ends with an expand button (`editor-command-toolbar-overflow`). Expanding wraps every tool into extra rows; the same button collapses them. Buttons are 40 points square inside a 50-point bar. Android reports a 48 dp touch area for every clickable, so the bar leaves room for it; automation checks that each button lies wholly inside its toolbar.

The `toolbarPlacement` preference keeps Evolution's options. A top or bottom toolbar spans the whole window, above or below the explorer, as in Evolution. Left, right and down-middle placements become a 50-point scrolling rail beside the canvas; down-middle becomes left when there is no sidebar. On short landscape windows (height below 480 points) the default top toolbar moves to the left rail.

## Simulation controls and status line

`simulationControlsPlacement` decides where run, event step, tick, automatic ticks, reset and frequency go:

| Window | Toolbar | Controls |
| --- | --- | --- |
| At least 600 points wide | Top or bottom | End of the tool toolbar |
| Any | Side rail or hidden, at least 600 points wide | Header |
| Below 600 points wide | Any | Bottom bar (`editor-simulation-toolbar`), which also expands |

The status line shows the status message, and on wide windows the steps, component and wire counts. It ends with the signals button and Evolution's zoom control: zoom out, the current scale (tap to fit) and zoom in. The canvas no longer draws its own zoom buttons. The signal panel stays hidden until the signals button opens it; on short windows it opens as a sheet.

## Automation

The chrome keeps stable tags for UI automation:

| Element | Tag |
| --- | --- |
| Header | `editor-project-toolbar` |
| Compact menu button and popup | `editor-project-toolbar-overflow`, `editor-project-toolbar-menu` |
| Menu bar titles and popups | `menu-title-<id>`, `editor-menu-<id>` |
| File and Project menus, in either form | `editor-file-menu`, `editor-tool-menu` |
| Compact submenu back row | `menu-back` |
| Tool toolbar and its expand button | `editor-command-toolbar`, `editor-command-toolbar-overflow` |
| Simulation controls | `editor-simulation-toolbar` |
| Status message | `editor-status` |

The File and Project menu titles carry the former Files and Tools descriptions, and toolbar buttons keep their earlier accessibility names, so scenarios still address actions by the shared catalog. `AndroidUi.toolbar_action` and `NativeUIHarness.reveal` follow a toolbar that expands in place, then search the menu bar or the compact menu's submenus. On iOS, Compose flattens tagged inner columns into their siblings, so menu tags sit on the popup itself. iOS menus close by a tap at the trailing end of the status line; Android menus take focus and close with Back.

The step counter sits beside the simulation controls in every layout. The signal panel stays folded until its toggle opens it; the harnesses open it before using an input.

## Validation

- `EditorToolbarLayoutTest` covers placement fallbacks, the short landscape rail, where the simulation controls go and the whole-button overflow arithmetic. `scripts/test_android_toolbar.py` covers in-place expansion, the menu bar, compact submenus, icon-only buttons and nested simulation controls with literal hierarchies.
- The layout was checked on the iOS simulator on an iPhone 17e in portrait and landscape and on an iPad Pro 11-inch (M5) with the explorer shown and hidden.
- On 2026-11-06, with the updated scripts on an iPhone 17e simulator (iOS 27.0), `testSap1AutomaticRunPause`, `testEditingConnectionsHistoryAndViewport` and `testXmlActionsAreAccessibleAcrossRotation` passed. The editing scenario takes about 12.5 minutes on a debug build of the narrow layout, so it needs `--test-timeout 900`. On an API 35 phone emulator, the Android `sequential` scenario passed. In the `editing` group, `editing-create-properties`, `editing-wires-truth-table` and `editing-selection-history` passed in two runs; `editing-viewport-touch-parity` reached its menu-only Fit selection step, then later runs stopped on `uiautomator` dump and focus-snapshot timeouts of the software-rendered emulator, before any chrome action.
- Full iOS suite on 2026-11-06 (iPhone 17e, `outputs/ios-e2e/chrome-8eb49cd-iphone-full-001`, then focused reruns): after the chrome fixes in `4fc3a858` through `b76aaf78`, the editing, SAP-1 run/pause, XML, Files, focus, signals and tablet inspector (iPad mini) cases pass. Remaining failures outside the chrome: Command+A and native text-menu focus (inspector roundtrips, Y replacement, visual export, HDL), the explorer's Components button never reported hittable in the I/O cases, the Files picker navigation in the portable cases, and the test receiver app in the large XML and external peer cases. `testSap1SequentialExecution` computes `08` but the native tree omits both hex displays: Compose iOS drops overlapping 48 pt canvas targets (see `docs/design/dense-canvas-accessibility.md`), and the larger canvas changes which ones survive.
- iOS accessibility suite: at the extra-extra-extra-large text size, reachability, audit, tools menu and viewport cases pass; the signal sheet's default drag handle has no description, and the XML dialog does not fit at that size (both predate the chrome). The provider-authentication runner was not run: its pinned artifacts and ROOT lease are unavailable.
- The full Android suite was stopped at the maintainer's request; only `sequential` and the first three `editing` cases are proven on the new chrome.
