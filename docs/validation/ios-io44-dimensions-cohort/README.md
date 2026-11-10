# Dedicated M5 cohort for the I/O dimensions case

The new Simulator `32AB2376-28FA-4DF3-BA0B-A79A1A71A593`, named `Logisim IO44 Dimensions M5`, reached Booted/AVAILABLE on 5 November 2026. It uses the installed `com.apple.CoreSimulator.SimDeviceType.iPad-Pro-11-inch-M5-12GB` and `com.apple.CoreSimulator.SimRuntime.iOS-27-0`, build `24A434`. The runtime inventory explicitly supports this type, and the IDs match the existing 52FE and B25 cohorts. The before/after device inventories show exactly this new UUID added.

This unit created one device and booted only that new UUID. XcodeBuildMCP inventory was read. Its attempted boot invocation failed validation before operating because session defaults were absent. No shared defaults were changed. The explicit `simctl bootstatus <new UUID> -b` call then booted the device and awaited its terminal Finished state in 28.339 seconds. No application, fixture, test, Metal setting, domain or runtime installation was performed by this task.

`du` reported 18,341,888 bytes in the device directory after creation and 416,854,016 bytes after first boot. These are directory-size observations, not physical APFS allocation measurements or a disk-allocation delta. The operating system populated its normal first-boot data.

The device was handed to the parent and I/O execution owner as READY for a separate root admission. Provisioning does not authorize application installation or Native execution, qualify issue #44, or provide a pure runtime A/B comparison. Concurrent authorized owners may change other devices; the scope claim here concerns this task's commands only.

The local originals were retained in `/tmp/logisim-io44-dimensions-cohort`. `cohort.json` summarizes the result, and `raw/` preserves exact command outputs and tool records, including the failed MCP validation. Run `shasum -a 256 -c SHA256SUMS` from this directory to check the archived bytes. No Xcode, device or repository access is required for that checksum check.
