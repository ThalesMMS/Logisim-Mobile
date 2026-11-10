# Bound-service eventTypes format: AOSP 15 source review

Status: source and archived-byte inspection only. No device, ADB, GUI, build, Java formatter, project helper or parser execution. No repository files were changed. The official source tag is `android-15.0.0_r1`; this does not establish that every local runtime implementation is identical. Source copies, byte counts, URLs and SHA-256 values are in `source-manifest.json`.

The observed failure is a parser-format mismatch. The archived fixture's line 18 contains `eventTypes=TYPES_ALL_MASK`; the reviewed helper's line 175 instead requires a bracketed field with no equals sign. The helper therefore rejects that actual record before any contact. This report addresses that format only.

Archived fixture: `/Users/thales/GitHub/logisim-mobile-worktrees/accessibility28-reader-plan/outputs/android-console-reader-5556-001/baseline-after-sdk-a11y.stdout.bin`, 6,791 bytes, SHA-256 `528fe8625c6abb80ef6de2a5908db4c84a4316294f45f45978cb336dde514ee6`. Reviewed helper: `/Users/thales/GitHub/logisim-mobile-worktrees/accessibility28-reader-plan/docs/validation/accessibility-reader-3a0a01f1/android-console-one-gesture-prepared-001/check_binding.py`, SHA-256 `b91247f094e6d8d8e8ed7a64f6ca655fef2d49f5569e7c325b3988cdc6530fe3`.

## Exact writer and separate grammar

`AbstractAccessibilityServiceConnection.dump` writes the Bound Service record. Its event field always has the equals sign, and its value is the result of the public `AccessibilityEvent.eventTypeToString(mEventTypes)`. The following delimiter is the notification-timeout field. Feedback uses a different caller prefix and a formatter that always adds brackets; its lack of an equals sign is intentional in this source. [Bound writer, lines 1592–1605](https://android.googlesource.com/platform/frameworks/base/+/refs/tags/android-15.0.0_r1/services/accessibility/java/com/android/server/accessibility/AbstractAccessibilityServiceConnection.java#1592), [feedback formatter, lines 1562–1608](https://android.googlesource.com/platform/frameworks/base/+/refs/tags/android-15.0.0_r1/core/java/android/accessibilityservice/AccessibilityServiceInfo.java#1562).

`AccessibilityServiceInfo.toString` is a separate representation: its append helper uses a colon and always surrounds the event list with brackets. It is not the function called for the Bound Service event field. A parser for this dump should not accept that alternate prefix or borrow its singleton/zero rules. [ServiceInfo entry, lines 1451–1453](https://android.googlesource.com/platform/frameworks/base/+/refs/tags/android-15.0.0_r1/core/java/android/accessibilityservice/AccessibilityServiceInfo.java#1451), [separate append helper, lines 1513–1525](https://android.googlesource.com/platform/frameworks/base/+/refs/tags/android-15.0.0_r1/core/java/android/accessibilityservice/AccessibilityServiceInfo.java#1513).

## Complete public-formatter cases

The total mask is all 32 bits. The public formatter handles it before the loop. Otherwise the loop consumes set bits from lowest to highest, joins atoms with comma and one space, and adds brackets only for two or more atoms. Zero skips the loop and returns empty text. [Mask constant, line 1002](https://android.googlesource.com/platform/frameworks/base/+/refs/tags/android-15.0.0_r1/core/java/android/view/accessibility/AccessibilityEvent.java#1002), [public formatter, lines 1776–1798](https://android.googlesource.com/platform/frameworks/base/+/refs/tags/android-15.0.0_r1/core/java/android/view/accessibility/AccessibilityEvent.java#1776).

These are source-derived expectations, not executed output:

| Input bits | Public formatter text | Bound field form |
| --- | --- | --- |
| `00000000` | empty string | `eventTypes=` |
| `ffffffff` | `TYPES_ALL_MASK` | `eventTypes=TYPES_ALL_MASK` |
| `00000001` | `TYPE_VIEW_CLICKED` | `eventTypes=TYPE_VIEW_CLICKED` |
| `00000003` | `[TYPE_VIEW_CLICKED, TYPE_VIEW_LONG_CLICKED]` | equals sign followed by that list |
| `08000000` | `8000000` | `eventTypes=8000000` |
| `80000000` | `80000000` | `eventTypes=80000000` |
| `80000001` | `[TYPE_VIEW_CLICKED, 80000000]` | equals sign followed by that list |

The switch names bits 0 through 26. The five remaining one-bit values use unsigned lowercase hexadecimal without `0x` or leading zeros: `8000000`, `10000000`, `20000000`, `40000000`, `80000000`, in that order. `canonical-atoms.json` records all 32 atoms, bit positions, definition lines and source identity. [Definitions, lines 467–649](https://android.googlesource.com/platform/frameworks/base/+/refs/tags/android-15.0.0_r1/core/java/android/view/accessibility/AccessibilityEvent.java#467), [single-bit switch/default, lines 1800–1836](https://android.googlesource.com/platform/frameworks/base/+/refs/tags/android-15.0.0_r1/core/java/android/view/accessibility/AccessibilityEvent.java#1800), [Integer unsigned-hex contract and implementation, lines 250–294](https://android.googlesource.com/platform/libcore/+/refs/tags/android-15.0.0_r1/ojluni/src/main/java/java/lang/Integer.java#250).

The private single-bit method's behavior for a direct zero, multi-bit or total-mask call is not the public mask formatter's output: the public function sends it only individual set bits and handles zero/ALL itself.

## Smallest robust parser change

Require the exact `eventTypes=` prefix in the existing Bound Service structure. Isolate its value from the following timeout delimiter; validate that value with a small pure function using four forms: literal ALL, empty zero, one bare atom, or a bracket list of 2–31 atoms. Use the frozen ordered 32-atom table for this Android 15 profile; require strictly increasing bit positions and the exact separator. This checks canonical output without admitting arbitrary text or a legacy no-equals fallback. Future format changes should fail closed until that source profile is reviewed.

Reject empty brackets, a bracketed singleton, reordered/duplicate atoms, noncanonical hexadecimal, unknown names, alternate prefixes, and the 32-atom list (the public ALL special case prevents that representation). The old synthetic positive `eventTypes[TYPE_VIEW_CLICKED]` must become `eventTypes=TYPE_VIEW_CLICKED`; merely inserting an equals sign before the old singleton brackets is still wrong. `canonical-cases.json` provides positive and negative source-derived cases for a later isolated helper test change.

Retain the existing user, active bound record, spoken-feedback, capability and AMS cross-proof checks and their stated limits. Accepting this field's format proves no identity join, Binder callback completion/liveness, event delivery, focus, navigation, speech, gesture or Reader PASS. No such qualification is supplied by this report.
