# Offline event-types correction review

Result: `PASS_OFFLINE_PARSER_CORRECTION_ONLY`. The candidate helper matches `AccessibilityEvent.eventTypeToString` in the pinned AOSP Android 15 r1 source: empty zero, literal ALL, unbracketed singleton and canonical lists of 2–31 atoms. Known event bits 0–26 match the declared Java constants; bits 27–31 use lowercase hexadecimal. A 32-atom list is rejected because all 32 bits produce the ALL literal.

The fix also restores the actual `eventTypes=` separator. The previous fixture/profile used the ServiceInfo-style bracket-only shape, which differs from the connection dump callsite. Duplicate, unknown, reordered and malformed tokens remain rejected. Component, PID, user, binder, connection, footer, touch and capability guards are unchanged.

All four official source members match the archive manifest. The wrapper differs only in its helper SHA pin; contact.py, contact-plan.json and numeric-capabilities.fixture.txt remain byte-identical to the frozen unit. The candidate receipt reports 71 guarded checks passing with zero external attempts and retains the two captured RED cases. This reviewer did not rerun the suite or perform any runtime operation. An independent source reviewer found no blocker.

Corrected parsing does not retroactively qualify the stopped unit. Matching Projects speech/caption, B and a gesture remain absent. No stronger accessibility Bound identity, reader behavior or workflow claim is added.

The final README and reader cycle require a settled post-enable screen plus another screenshot at least two seconds later, observed Don't allow and its result if the notification prompt appears, a real matching A caption before any lease or monitor, and separate new Root authorization. These are prepared controls, not evidence that another runtime occurred. The replay runner's default does not overwrite frozen logs; --output requires a new directory.
