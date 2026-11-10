# Android reader transport stopped after one gesture

Result: `FAIL_NOT_QUALIFIED`. This was one authorized unit on emulator-5556, Android 15/API 35, with the existing 3a app and installed TalkBack 15.0.0.639625893. It stopped after its first app swipe. No second app swipe, double-tap, menu activation or reader workflow followed. Issue #28 remains open.

The app, test and snapshot-helper SHA-256 values matched the authorized preflight before setup and after restoration. No app was rebuilt or installed. The existing paused `IO_Showcase` circuit was reused. An old import report was closed through its observed public button while the reader was disabled. The target at this phone width was `editor-project-toolbar-overflow`, described as "Show more commands: Project tools", with bounds `[882,265][1008,392]`. The intended menu was never opened.

## Observed sequence

The observer used `AndroidUi(..., preserve_accessibility_services=True)` from original commits `96a59ba4` and `6b246c1b`. App trees always used the SDK helper with `FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES`. Settings and reader overlays used screenshots. No generic UI Automator dump or MixedInput instrumentation ran during this unit. The subprocess stdin fix prevented adb from consuming queued observation requests; it did not change app or reader behavior.

While TalkBack was disabled, its public developer Settings showed Display speech output off and log output level ERROR. Only Display speech output was enabled. TalkBack was then enabled through its public switch and standard full-control confirmation.

An unplanned notifications prompt appeared. The unit paused for the lane owner's recovery instruction. A read-only package query showed `POST_NOTIFICATIONS: granted=false`. The observed public "Don't allow" button dismissed the prompt. No notification permission was granted and no tutorial appeared. This denial added the public `USER_SET` permission flag; no claim of identical opaque internal state follows.

After public app launch, the first SDK snapshot preceded settled accessibility focus. A later baseline snapshot recorded exactly one accessibility-focused Projects container at `[21,137][222,263]`; the screenshot showed its green focus rectangle. At 08:54:27.938 UTC, the only app gesture request was:

```text
adb -s emulator-5556 shell input touchscreen swipe 300 800 700 800 120
```

The next screenshot showed horizontal canvas pan. The SDK snapshot recorded accessibility focus on the canvas at `[0,655][1080,2119]`, with its complete circuit description. The unit stopped because that did not qualify the required linear reader navigation. A command exit code of zero did not qualify the gesture.

`boundServices` and `touchExplorationEnabled` were not recorded immediately before or after the SDK captures while TalkBack was on. Public enabled-service settings were captured later during cleanup, but do not fill that gap. Focus and panning alone do not show that the reader processed the gesture. No app or TalkBack defect is established by this attempt.

Three raw video segments are retained, with gaps between recording processes. They contain H.264 video and metadata, with no audio stream. They are not a continuous recording of the full session. Later offline review of all 27 frames in segment 003 found the reader's speech display with the Canvas description, including "Circuit area. OUT_BUTTON: 0" and "Use two fingers to zoom and pan." It is visible in frames 013 through 023, at presentation timestamps 60.834456 through 96.271544 seconds. This is positive evidence of reader-produced text associated with Canvas focus, although the later sampled screenshot missed it. It does not establish that the shell swipe was processed as a TalkBack navigation gesture. [Frame 015](reader-caption-frame015.png) is retained without image edits. No audio or continuous service-binding claim follows.

## Public restoration

TalkBack was disabled through its public switch and "Turn off" confirmation. Display speech output was disabled through the same public developer Settings. Screenshot `025-cleanup-talkback-off-confirmed.png` shows the reader switch off; screenshot `032-cleanup-speech-display-off-confirmed.png` shows speech display off and ERROR unchanged. The final read-only receipt records:

- `accessibility_enabled=0`, no enabled accessibility service, and `touchExplorationEnabled=false` in the final dumpsys.
- Speech display off, TalkBack shortcut off, single-tap activation off, and unchanged log verbosity. No gesture mappings or shortcuts were changed.
- Notifications still `granted=false`, now with `USER_SET|USER_SENSITIVE_WHEN_GRANTED`.
- Unchanged font scale, IME, rotation, locale, screen size and density.
- Exact preflight app, test and helper APK hashes.

Public disable changed `enabled_accessibility_services` from the initial `null` representation to an empty string. The viewport pan was not reversed. Neither exact viewport nor byte-identical opaque settings are claimed. The observation process exited zero and the lane was returned after restoration, with no further device actions.

## Input path and next step

The reviewed AOSP Android 15 tag explicitly documents that externally injected events do not reach `InputFilter`. The shell swipe follows `InputManager.injectInputEvent`; the accessibility input filter is invoked on the separate device-event path. See [source findings](aosp15-input-research.md) and its source hashes. This explains why shell-command success is insufficient. Applying that AOSP behavior to this Google AVD is an inference; its exact platform binary and live reader binding were not traced.

The local emulator process is headless with `-no-window`, explicitly linked to port 5556. A Qt/CUA gesture would require a separate runtime arrangement. The installed Emulator 37.1.11 SDK declares experimental `EmulatorController.sendTouch` with Linux multitouch Protocol B, a candidate virtual-hardware input route. No controller endpoint, credential or gesture was exercised. A new unit requires a separate approved transport plan and actual reader-output evidence.

`observations.tar.gz` retains the raw preflight, commands, SDK captures, screenshots, public restoration, videos and sanitized local process/SDK inventory. `archive-manifest.json` records every included file's size and SHA-256. APK copies and reproducible extracted video frames are excluded. `archive-evidence.py` rebuilds this archive from the original ignored output directories. Historical preparation JSON files retain their original prepared status; this receipt records the subsequent stopped execution.
