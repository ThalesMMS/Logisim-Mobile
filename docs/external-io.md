# External I/O component contracts

`ExternalIoComponents.definitions()` supplies descriptors and behavior for the I/O family. The caller adds these definitions to its `ComponentRegistry`. Resources belong to a runtime instance, not to a document or a global component catalog.

| Component | Implemented behavior | Host integration |
| --- | --- | --- |
| PortIO | Local pin bank, manual values, single or per-bit enables, contention | Deliver `PortIoSetPin` or `PortIoTogglePin` events from the editor. |
| RGB Video | Immutable framebuffer, desktop color modes, clocked writes and reset | Render `VideoState.frame` and the optional cursor. No camera or network permission is needed. |
| RealTimeClock | Epoch milliseconds or seconds, injectable clock, scheduled refresh | Uses `SystemEpochClock` by default; tests supply an `EpochClock`. |
| Telnet | Clocked byte input/output, bounded input queue, explicit connection status | `CompanionTelnetClient` connects the mobile runtime to the explicitly paired Python host. `TelnetSession` also supports an injected JVM transport. |
| ReptarLB | Original port geometry and explicit unsupported diagnostic | The reference desktop also has no simulation implementation. |

The catalog registers this family. `ExternalIoRenderer.kt` draws evaluated state, `ExternalIoInteraction.kt` maps PortIO pin taps, and `externalIoEditorTools` supplies the touch controls and a scene-owned runtime. The editor host mounts each tool runtime even while its panel is closed and supplies its paired `CompanionClient` provider.

## Telnet resource ownership

Loading a `Telnet` component never starts a listener. The user starts a `TelnetSession` explicitly. Its default listener binds to loopback; `allowLan=true` is a separate choice. The session returns `ComponentEvent` values for the simulator. The caller delivers them once to the instance that owns the session, then passes its latest `TelnetState` back to `synchronize`.

With `telnetMode=true`, framing follows the pinned Evolution component. The receiver consumes IAC and the next two wire bytes, including across reads, and the sender transmits byte 255 once. Raw mode carries every byte literally. Replacing the peer resets a partial command.

Output packet identifiers make repeated synchronization idempotent. A successful queue operation yields `TelnetOutputAccepted`; it confirms acceptance by the host transport, not delivery to the remote application. Input bytes remain queued until the circuit reads them on its configured clock edge. A full input buffer drops new bytes, matching the desktop. Pending circuit output is also bounded by the buffer length and reports a diagnostic instead of growing without limit. Transport errors appear as component diagnostics.

Call `stop()` when a document closes, the user disconnects, or the owning runtime is discarded. The JVM transport uses nonblocking channels, closes the replaced peer when a new peer connects, and releases both listener and peer on stop. Its output queue has a configured byte limit. An unavailable companion must remain an unavailable capability rather than producing fabricated values.

`companion/telnet.py` supplies real host operation handlers. The authenticated companion server must call `telnet.register(registry, allow_lan=operator_grant)` and close its registry on shutdown. The module imports `companion.api` only during registration and opens no listener until a `telnet.start` operation runs. No shell or external executable is involved.

The Telnet resource socket is separate from the authenticated companion API. It binds loopback by default. Every `telnet.start` job requires the companion's confirmed plan flow, including loopback listeners. A LAN resource listener additionally needs the host operator's `allow_lan` grant. Supplying a manager with a broader grant cannot bypass the registration-level grant.

| Operation | Parameters | Result |
| --- | --- | --- |
| `telnet.start` | `port` defaults to 8000; zero selects a free port. Optional `bufferSize`, `maximumOutputBytes`, `telnetMode`, `allowLan`, `requestId`. | Session ID, bound port, connection status and lease duration. An active `requestId` reuses the same configuration and listener. |
| `telnet.poll` | `sessionId`, optional `maximumBytes` from 0 to 65536 and positive `pollId`. Zero queries status without draining input. | Received unsigned bytes, connection status, peer generation and dropped-byte counters. A repeated latest `pollId` replays its reply. A resize clears any bytes in that reply. Changed limits and older IDs are rejected. |
| `telnet.resize` | `sessionId`, `bufferSize` from 1 to 16777216 and a positive `resizeId`. Enable with `--allow telnet.resize`. | Preserves the active listener and peer and clears queued input when the size changes. Retrying the latest resize ID does not clear subsequent bytes. |
| `telnet.send` | `sessionId`, positive `packetId`, `bytes` with at most 65536 values from 0 to 255. | Accepted packet ID and duplicate status. |
| `telnet.stop` | `sessionId`. | `stopped`; repeated stops are idempotent. |

The host defaults to eight sessions and a combined 32 MiB reservation for configured buffers. A session lease expires after 300 seconds without a poll or send. A single reaper releases expired listeners even when the client disappears. The packet retry window retains the latest 1024 accepted packet hashes. Changed content for a retained packet ID is rejected, and expired packet IDs are rejected rather than replayed. A full output queue does not accept the packet ID, so the same packet can be retried when space becomes available.

`ExternalIoRuntimeBridge` keys each session by document identity, circuit, instance path and component ID. Preparing a listener only requests a plan. The separate confirmation button submits that reviewed plan with its original retry ID. Polls use a bounded per-session `pollId` response slot rather than filling the global job receipt store. This permits retry after a lost HTTP response without consuming the same input twice. A full circuit input buffer requests a zero-byte status poll.

Each remote round publishes one `TelnetRuntimeEvents` event through the instance-bound editor sender. Its sequence acknowledgement prevents another receive batch from overtaking a simulator transaction. Pending output packets are grouped into at most 65536 bytes and removed only after the host acknowledges their stable packet ID. Changing only `buflen` clears the companion and circuit input queues without replacing the listener or peer. The change does not discard pending output. Bytes still in the socket are read using the new size on the next poll. Changing the port, Telnet mode or network permission stops the listener and requires a new confirmed start. Simulation reset, removed instances, pairing changes and backgrounding also stop the listener. If a confirmed start has an unknown result, cleanup resolves the same retry ID before stopping it. Failed shutdown retains its recovery state and exposes a retry control; the host lease remains the fallback when communication is unavailable.

Ownership and reset checks continue while network polling is paused. They also cover a confirmed start whose HTTP result was lost, using the retained request identity. A prepared but unconfirmed plan never creates a listener for cleanup. The [lifecycle validation](validation/external-io-lifecycle-2026-11-04.md) records the reproduced failures and real editor/HTTP/TCP regression.

The runtime uses the host paired for that scene. It does not create a direct socket on Android or iOS. Native transport compilation and device execution are separate validation steps.

## Clock scheduling

`RealTimeClockState` samples the epoch provider once for each `(ticks, elapsedTimeMillis)` pair. All delta evaluations in one outer simulation transaction reuse that sample. The engine must pass a fixed monotonic timestamp throughout that transaction and advance it for external wakeups. This prevents the wall clock itself from keeping a combinational settling loop active. Automatic propagation requests the next refresh after 10 ms; pausing removes that request.

## Reference behavior and validation

The implementation follows the local Logisim-evolution source revision `fbb1eca6ff72cb634b8dd1d72dc232b9838c9e01`:

- `std/io/PortIo.java` defines `number`, `direction`, port geometry, manual pin cycling, and tri-state behavior.
- `std/io/Video.java` defines color tables, dimensions, port positions, writes, and reset behavior. The palette data in `VideoPalettes.kt` comes from this GPLv3 source.
- `std/io/RealTimeClock.java` defines 64-bit epoch output and the 10 ms refresh period.
- `std/io/Telnet.java` and `TelnetServer.java` define clocked queue behavior and the basic Telnet negotiation.
- `std/io/ReptarLocalBus.java` rejects simulation with `UnsupportedOperationException`.

Run the focused JVM tests through the repository's build lock. `ExternalIoComponentsTest` exercises literal pin values, framebuffer state, color modes, reset, and clock injection. `VideoColorCompatibilityTest` compares the direct formats with the Java color models used by the desktop. `TelnetRuntimeTest` exercises framing, queue operations, idempotence, and explicit startup. `JvmTelnetTransportTest` opens a real loopback connection, exchanges bytes, closes it, and checks bind failure cleanup.

The JVM HTTP integration tests use the real Python companion and a real TCP peer. They check explicit plan confirmation, byte exchange, a deliberately discarded HTTP reply, more than 64 polls, Telnet negotiation and resource closure. Compose unit tests cover instance selection, atomic event delivery, receive backpressure, reset acknowledgement timeout and uncertain-start cleanup. These checks do not establish physical-device UI parity.

Run `python3 -m unittest discover -s companion/tests -p 'test_telnet*.py' -v` for the Python companion. These loopback tests exercise registered handlers through the real `companion.api`, packet retries, negotiation, peer replacement, bounded buffers, cancellation cleanup, lease expiry, shutdown, and refusal of LAN listeners without the two grants.

RGB cursor visibility follows the captured simulation elapsed time in 500 ms phases. Automatic propagation schedules its next phase; pausing preserves the current display. RTC drawing likewise uses only `RealTimeClockState.epochMillis`, with no wall-clock read during painting.

## Reptar Local Bus FPGA export

VHDL export generates a bridge for the `REPTAR_SPARTAN_6` board. The FPGA
panel selects VHDL when the circuit contains a Reptar Local Bus and fills the
31 board pins from the `LocalBus` resource in XML order. The 16 data pads
connect to Xilinx UNISIM IOBUF primitives at LVCMOS18, 12 mA, fast slew. The
scalar input and output banks follow the board's active-low convention. The
bidirectional data bank stays directly connected. Unwired data direction
defaults to high impedance.

The Evolution reference assigns eight host address bits to a nine-bit circuit
port. The Mobile bridge uses bits 12 through 4, matching the nine address pins
after the four controls in the board definition. The NVC fixture checks address
bit 8, all four controls, WAIT/IRQ, data in both directions, and bus release.
[Evolution bridge][reptar-hdl] · [Evolution board XML][reptar-board]

To run the bridge fixture with NVC in a temporary directory, start in the
repository root.

```sh
repo=$(pwd)
test_dir=$(mktemp -d)
python3 - "$test_dir" <<'PYTHON'
from pathlib import Path
from textwrap import dedent
import sys

source = Path("core/src/commonMain/kotlin/dev/logisim/mobile/core/ReptarLocalBusHdl.kt")
bridge = dedent(source.read_text().split('"""')[1]).strip()
(Path(sys.argv[1]) / "bridge.vhd").write_text(bridge + "\n")
PYTHON
(
    cd "$test_dir"
    nvc --std=1993 --work=unisim -a "$repo/fixtures/hdl/reptar/unisim-model.vhd" &&
    nvc --std=1993 -L "$test_dir" -a bridge.vhd "$repo/fixtures/hdl/reptar/local-bus-test.vhd" &&
    nvc --std=1993 -L "$test_dir" -e reptar_local_bus_test -r reptar_local_bus_test
)
```

The fixture uses a behavioral IOBUF model. For vendor validation, select
`REPTAR_SPARTAN_6` and Xilinx ISE in the FPGA panel. Validate the 31 mapped
LocalBus pins and export the project. Check `pins.ucf` and the VHDL sources in
the exported archive before preparing a bitstream. Pair an approved ISE 14.7
host with Spartan-6, UNISIM and an edition license covering XC6SLX150T.
Configure the companion with `--job-output-mib 6` or `7` to fit the bitstream
and its base64 representation. The companion selects the fixed
`XC6SLX150T-FGG900-3` target from the Reptar board profile.
[ISE device support][ise-license] · [Spartan-6 bitstream size][reptar-bitstream]

After approval, record the synthesis, translation, mapping, placement and
routing, and packing stages. Save the returned `design.bit` and compare its
SHA-256 with the artifact digest. [#219][reptar-issue] tracks this compilation.
The bitstream result reports programming and physical verification separately.
The shared ISE flow is tracked in [#181][ise-flow].

[reptar-hdl]: https://github.com/logisim-evolution/logisim-evolution/blob/d58a61e0c290361722b43df5b1a6b86c1f7c7f0d/src/main/java/com/cburch/logisim/std/io/ReptarLocalBusHdlGeneratorFactory.java
[reptar-board]: https://github.com/logisim-evolution/logisim-evolution/blob/d58a61e0c290361722b43df5b1a6b86c1f7c7f0d/src/main/resources/resources/logisim/boards/REPTAR_SPARTAN_6.xml
[ise-license]: https://docs.amd.com/v/u/en-US/irn
[reptar-bitstream]: https://docs.amd.com/api/khub/documents/~cZEJpQs2FyMldtiTCZfOQ/content
[ise-flow]: https://github.com/ThalesMMS/logisim-mobile-dev/issues/181
[reptar-issue]: https://github.com/ThalesMMS/logisim-mobile-dev/issues/219
