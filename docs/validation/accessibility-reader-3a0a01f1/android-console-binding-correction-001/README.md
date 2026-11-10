# Correct the Android15 Bound event field

Status: `OFFLINE_PASS_NOT_EXECUTED_NEW_ROOT_AUTHORIZATION_REQUIRED`. This additive unit corrects the validation helper that stopped the [5556 unit before contact](../android-console-precondition-5556-001/README.md). It changes no product source, presentation, gesture, APK, device setting, or default observer behavior. The previous unit's parser rejection and incomplete reader baseline remain recorded. No later runtime, service, monitor, lease, or contact was started by this correction.

The Bound Service writer prints `eventTypes=` followed by `AccessibilityEvent.eventTypeToString`. The frozen helper and synthetic positive fixture had omitted the equals sign and required brackets. The public formatter returns empty text for zero, a bare name or unknown-bit hexadecimal atom for one bit, a bracketed ordered list for multiple bits, and `TYPES_ALL_MASK` for all 32 bits. `AccessibilityServiceInfo.toString` uses a separate colon/bracket representation. [Bound writer](https://android.googlesource.com/platform/frameworks/base/+/refs/tags/android-15.0.0_r1/services/accessibility/java/com/android/server/accessibility/AbstractAccessibilityServiceConnection.java#1592), [public event formatter](https://android.googlesource.com/platform/frameworks/base/+/refs/tags/android-15.0.0_r1/core/java/android/view/accessibility/AccessibilityEvent.java#1776).

The corrected helper requires the exact equals prefix and canonical Android15 event text. It rejects the former no-equals fixture, ServiceInfo syntax, empty or singleton brackets, unknown names, noncanonical hexadecimal, duplicate or reordered atoms, and the 32-atom list. The source's ALL special case prevents that last list. User scoping, one spoken/touch-capable Bound record, the exact AMS system-client binding, and the existing correlation-only limits are preserved. No label or absent Bound component is used as an identity join.

The new `run_contact.py` differs from the frozen 8dce wrapper only in its pinned helper hash. `contact.py` and `contact-plan.json` retain the original exact bytes. The fixed nine active frames and UP, single-use identity claim, 120-second lease limit, bounded monitor, cancellation and timing guards remain unchanged. Running without `--execute` still makes zero ADB calls, and execution still returns no reader acceptance. No valid lease is supplied.

## Offline evidence

`captured-red.log` retains the two `unknown_bound_format` errors over the exact before/after reader-on bytes. Its negative touch-exploration control passed. `green.log` and `green-receipt.json` record 71 passing checks with external process and socket boundaries blocked and zero attempted external calls: 32 binding tests, 27 wrapper tests, ten contact tests, and two previous independent regression reproductions. The nine captured/source binding tests supplement the 23 migrated adversarial binding tests. They cover both actual dumps, all 32 independently sourced atoms and their order, the official literal cases, invalid alternatives, and the unchanged spoken/touch requirements.

The first integrated offline runner attempt failed during unittest.mock's SSL import because the temporary socket guard replaced the constructor before that import. `guard-import-failure.log` retains that runner failure. The rerunnable script loads unittest.mock first, then blocks process and socket calls. Its default only prints a receipt. To keep a replay log, use a new scratch directory:

```sh
python3 docs/validation/accessibility-reader-3a0a01f1/android-console-binding-correction-001/run_offline_tests.py --output /tmp/binding-replay-unique
```

The four official source copies are retained in `official-format-sources.tar.gz` with URL and hash manifests. The independent source review and canonical JSON expectations derive from the tagged public source; they are not Java formatter execution or an installed-binary identity check. The pure Python replay parses archived bytes rather than a current service.

`independent-review.json` records a separate code review of the corrected grammar, unchanged binding controls, helper pin, identical contact/payload, and strengthened public-cycle preconditions. It found no blocker in that source scope and did not independently rerun the tests or operate a device.

## The next unit still needs a complete baseline

The [corrected public cycle](reader-cycle-plan.md) adds a settled post-enable screenshot and another screenshot at least two seconds later before the app launch. A notification prompt must be handled through its observed **Don't allow** action and result while preserving the original denied grant. Launching the app over the prompt does not qualify setup. A fresh ring and SDK focus still need a matching reader-produced caption before any lease or monitor. If that text is missing, the unit stops without Down.

Only a separate Root authorization can reserve a lane and execute this corrected artifact. It must revalidate the installed app/test/helper, public state, neutral contact region, complete baseline, and actual service evidence. The 120-second single-use lease is created just in time after the baseline is frozen. A later transport result still requires the full kernel sequence and UP. Reader navigation additionally requires a distinct labeled B, matching reader text, and unchanged viewport. A whole-Canvas focus or ambiguous touch exploration cannot qualify navigation. Issue #28 remains open.
