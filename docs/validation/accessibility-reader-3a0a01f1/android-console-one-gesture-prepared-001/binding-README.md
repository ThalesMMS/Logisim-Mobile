# Source-derived AMS binding and accessibility-state checker

Status: `PREPARED_OFFLINE_TESTED`, runtime `NOT_RUN`. No device query, reader operation, GUI, build or wrapper edit was performed. The only executed checks were local Python tests over synthetic literal text. `check_binding.py` is a separate pure helper. Its functions perform no process, device, network or file I/O.

## Integration surface

Call `check_cross_evidence(activity_dump, accessibility_dump, system_server_pid=<fresh externally observed PID>, user_id=0)`. It returns `PASS_AMS_BINDING_AND_A11Y_STATE_ONLY` or raises `BindingError`. The expected system-server PID is mandatory. The framework ProcessRecord name is `system/1000`, even though the OS process name is system_server. Do not infer its PID from arbitrary text in the dump being checked.

The source-supported read-only profile is `dumpsys activity services <exact TalkBack component>`. Do not use `-c` or `-p`: those options set dumpClient and can request a remote application dump. The services branch already requests the complete server-side service detail without `-a`. The framework's ItemMatcher uses ComponentName equality for a valid flattened component, rather than a package substring match. This preparation supplies no command launcher.

The helper scopes fields to one active ServiceRecord in the selected user's active-services section. It requires the exact component in that record and its intent, correct package, a non-null matching service process, and one corresponding IntentBindRecord with a non-null BinderProxy, requested/received/hasBound true and doRebind false. The same binding must contain the externally attributed PID:system/1000 client and its non-DEAD ConnectionRecord. That connection must also occur in the ServiceRecord's All Connections list. Flags, binder presence, a client name elsewhere, installed services or Enabled settings are insufficient individually.

The normalizer accepts only these complete equivalent tokens:

```
com.google.android.marvin.talkback/com.google.android.marvin.talkback.TalkBackService
com.google.android.marvin.talkback/.TalkBackService
```

Separately, the accessibility dump must identify the same selected user, touch exploration true and one actual Bound record with spoken feedback and touch-exploration capability. The user-state footer is parsed only in its Client list / Registered clients context. Java package arrays may be concatenated directly with the final `}]`, as in the already captured OFF dump. Client arrays delimit the state and never identify TalkBack. The label is retained as observation only. No label, Registered clients or Enabled value is used to identify TalkBack. Multiple opaque bound records are rejected because this dump has no component field for a reliable join.

## Limits of the cross-evidence

The exact component is proven only in the observed AMS record. AOSP15's accessibility Bound record contains no component. The helper returns `component_in_bound_dump=None` and `identity_join=CORRELATION_ONLY`; it does not manufacture an identifier. Controlled public enablement, the exclusive lane and nearby captures can support that correlation, but separate dumps are not atomic and do not expose a shared component key in Bound.

AMS sets binder/received before it attempts the client's connected callback, and catches callback errors. Thus those fields do not prove callback completion or current Binder liveness. They establish the published-binder and registered-client state represented by the dump. This result is preflight evidence only. It does not qualify delivery of input, a reader gesture, focus, speech, traversal order or a workflow. The reader's independent focus/caption/viewport oracles remain required.

The AOSP source pin is Android15r1. These fixtures have not been compared with a reader-on Google AVD dump. Unknown or OEM formatting fails without a label/setting/registered-client fallback. Preserve exact command/serial/user/PID attribution, raw stdout/stderr, timestamps and the original files beside the parsed result. No initial-slot or clock claim is added by this helper.

## Verification

The 23 tests contain literal source-derived positive text and negatives for false binding states, null Binder, wrong component, installed/started records without a binding, wrong system PID/name/UID, DEAD or unlinked connections, cross-binding field mixing, stale/other-user records, missing actual Bound state and false touch exploration. They also show that Enabled/Registered tokens can be absent without replacing the actual evidence. Tokens, PIDs, labels and UIDs in the fixtures are synthetic examples, not captured runtime values.

Run `python3 run_offline_tests.py` in this directory. It blocks subprocess/network boundaries and writes a complete log and receipt. The final guarded run passed 23 tests with zero failures, errors, skips or external process/network attempts. No monitor, sender or device launcher is supplied.

## Primary sources, Android15r1

- [ServiceRecord.dump and nested Bindings/All Connections](https://android.googlesource.com/platform/frameworks/base/+/refs/tags/android-15.0.0_r1/services/core/java/com/android/server/am/ServiceRecord.java#1083), process fields at 930–949 and short component at 1150.
- [IntentBindRecord fields and dump](https://android.googlesource.com/platform/frameworks/base/+/refs/tags/android-15.0.0_r1/services/core/java/com/android/server/am/IntentBindRecord.java#39), state fields at 39–48 and dump at 57–71.
- [AppBindRecord per-process connections](https://android.googlesource.com/platform/frameworks/base/+/refs/tags/android-15.0.0_r1/services/core/java/com/android/server/am/AppBindRecord.java#36), including client and connection scoping.
- [ConnectionRecord component and DEAD token](https://android.googlesource.com/platform/frameworks/base/+/refs/tags/android-15.0.0_r1/services/core/java/com/android/server/am/ConnectionRecord.java#276).
- [ActiveServices publication](https://android.googlesource.com/platform/frameworks/base/+/refs/tags/android-15.0.0_r1/services/core/java/com/android/server/am/ActiveServices.java#4449), binding request at 5205–5232 and service-dump formatting at 7827–7869.
- [AMS services dispatch](https://android.googlesource.com/platform/frameworks/base/+/refs/tags/android-15.0.0_r1/services/core/java/com/android/server/am/ActivityManagerService.java#11008), ItemMatcher at 11702–11749 and system registration at 2063–2078.
- [ProcessRecord formatting](https://android.googlesource.com/platform/frameworks/base/+/refs/tags/android-15.0.0_r1/services/core/java/com/android/server/am/ProcessRecord.java#1371).
- [Framework process name system](https://android.googlesource.com/platform/frameworks/base/+/refs/tags/android-15.0.0_r1/core/res/AndroidManifest.xml#8405), [OS system_server UID1000](https://android.googlesource.com/platform/frameworks/base/+/refs/tags/android-15.0.0_r1/core/java/com/android/internal/os/ZygoteInit.java#661).
- [AccessibilityServiceConnection binding](https://android.googlesource.com/platform/frameworks/base/+/refs/tags/android-15.0.0_r1/services/accessibility/java/com/android/server/accessibility/AccessibilityServiceConnection.java#157), [ContextImpl caller](https://android.googlesource.com/platform/frameworks/base/+/refs/tags/android-15.0.0_r1/core/java/android/app/ContextImpl.java#2156).
- [AccessibilityUserState Bound formatting](https://android.googlesource.com/platform/frameworks/base/+/refs/tags/android-15.0.0_r1/services/accessibility/java/com/android/server/accessibility/AccessibilityUserState.java#582), [connection dump without ID](https://android.googlesource.com/platform/frameworks/base/+/refs/tags/android-15.0.0_r1/services/accessibility/java/com/android/server/accessibility/AbstractAccessibilityServiceConnection.java#1592), [component shortening](https://android.googlesource.com/platform/frameworks/base/+/refs/tags/android-15.0.0_r1/core/java/android/content/ComponentName.java#234).

The adjacent source manifest hashes the downloaded official source text. Additional independently inspected source URLs are listed above. No installed binary match is claimed.
