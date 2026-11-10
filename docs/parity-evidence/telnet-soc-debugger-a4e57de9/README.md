# Telnet and SoC debugger compared with Evolution (campaign #331)

Mobile `a4e57de9eb3555fb5e6eed78ab6d5470e43ee229`, core JAR `3121d939a28779c1f82f02303debcc76c8f963e71a34ec63af2a5c53a16ddefc` (built from `a4e57de9` with the uncommitted #331 CLI edits to `HeadlessCircuitCli.kt` and `HeadlessLegacyTty.kt`, which no Telnet or SoC code uses). Evolution `d58a61e0`, JAR `d0b287d1695bc12f276f42b8db39af9a00e266ce0e0b53472b5aeab795a7a038`. macOS (Apple silicon), Homebrew OpenJDK 21, JVM and host only. Evolution's preferences live in memory (`MemoryPreferencesFactory`), so the user's Java preferences are never read or written.

```sh
scripts/oracle/telnet/run.sh EVOLUTION_JAR docs/parity-evidence/telnet-soc-debugger-a4e57de9/logs        # about 6 minutes
scripts/oracle/soc-debugger/run.sh EVOLUTION_JAR docs/parity-evidence/telnet-soc-debugger-a4e57de9/logs  # about 10 seconds
```

## `integration/telnet`: divergente

`scripts/oracle/telnet/TelnetOracle.java` builds one Telnet component at (200,200) with labelled pins on its six ports, in a `.circ` read by both programs. Each scenario runs three times:

- **Evolution**: `Telnet` and `TelnetServer` in Evolution's simulator (`scripts/oracle/output-devices/DesktopCircuit.java`).
- **Mobile, companion**: the app path. `companion/telnet.py` `TelnetManager` (through `companion_bridge.py`) owns the socket. The oracle applies `ExternalIoRuntimeBridge.synchronize`'s poll and send rules, and core `TelnetBehavior` runs in `CircuitSimulator`.
- **Mobile, JVM**: `JvmTelnetTransport` and `TelnetSession` with the same `TelnetBehavior`.

Real loopback sockets act as clients. Scenarios: raw read and write (bytes 0 and 255 included), falling edge, Telnet mode (negotiation, IAC split across reads, IAC IAC), a two-byte input buffer, a write without a peer, a second peer, a peer reset and reconnecting after a close. Results are in `logs/telnet-results.json`, and each `logs/telnet-<case>.txt` holds the three traces.

Environment note: host tools on this machine probe every new localhost listener, with an HTTP GET and then a TLS hello that stays open for about 10 s. Both programs accept these peers. Every run therefore waits 12.5 s after the listener opens (`TELNET_ORACLE_WARMUP_MS`) and discards the bytes that arrived before the scenario connects (logged as `discarded N bytes from pre-scenario peers`). The Mobile side polls until its accept backlog is empty.

**Equal** when outputs are sampled just before each read or write rising edge, together with the bytes every client saw:
- `dataOut`, `available` and the bytes consumed, in rising- and falling-edge modes;
- the bytes written, including 0 and 255;
- Telnet negotiation `FF FB 03 FF FB 01`, and the two bytes dropped after each IAC (also across reads);
- a full input buffer drops later bytes;
- a write with no peer is lost;
- a closed peer's data is kept and the next peer is served.

Companion path: 7 of 8 scenarios equal.

**Differences**
- **T1/T2 edge timing** (every scenario, step by step).
  - Evolution's `Telnet.propagate` sets `dataOut` and `available` before `deleteOldest()` and does not run again until an input changes. So after a consuming edge, the old byte stays on `dataOut` until the next clock transition. Bytes that arrive also leave `available` at 0 until the next input change, because `fireInvalidated` only repaints.
  - Mobile re-evaluates the component after its state changes. It shows the next byte at the edge itself and raises `available` when the bytes arrive.
  - A circuit that samples on the same edge reads the same values (see the sampled comparison).
- **T3 second peer.** Evolution's `setClient` points output at the newest peer but leaves the earlier connection open, and still reads its input into the buffer. Companion and `JvmTelnetTransport` close the replaced peer (EOF), so its later bytes are lost (`logs/telnet-second-peer.txt`).
- **T4 bind address.** Evolution binds `0.0.0.0` (`new ServerSocket(port)`). Mobile binds `127.0.0.1` unless LAN is enabled.
- **T5 JVM transport reset (defect).**
  - In `JvmTelnetTransport.poll`, a single try covers accept, read and flush. An `IOException` from the client, such as `Connection reset` from a peer that closes with RST, calls `fail()` → `close()`, which also closes the **listener**. Later clients get `Connection refused` (`logs/telnet-peer-reset.txt`; the probe triggers the same fault in `telnet-mode` and `input-buffer-limit`).
  - Evolution's `ClientThread` and the companion `_pump` only drop that peer.
  - Small fix: catch the client-side `IOException` separately (close `client`, clear `output`, set `Listening`, keep `listener`), with a focused `JvmTelnetTransportTest` that uses an RST close.

## `integration/soc-debugger`: divergente

`scripts/oracle/soc-debugger/SocDebuggerOracle.java` runs both models in one JVM. Evolution runs without `java.awt.headless`, because `SocBusStateInfo` builds a `JDialog`, as in `scripts/SocProgramDesktopOracle.java`. No window is shown.

| Comparison | Result |
| --- | --- |
| ELF load of `fixtures/soc/sum-{rv32,nios2}.elf` into `program-{rv32,nios2}.circ` (Evolution `ProcessorReadElf`, Mobile `SocLoadExecutableEvent`) | equal: entry, then the same execution |
| Register view after load and after each of 22 steps: pc, 32 registers, unknown registers `????????` (Evolution `getRegisterValueHex`, Mobile `knownRegisters`) | equal, RV32 and Nios II |
| Trace rows (pc, instruction word, newest first) | equal, RV32 and Nios II |
| Trace assembly text | Nios II equal (`movi      r2,0`). **RV32 differs on 22/22 rows**: Evolution writes `li        ra,0`, `add       ra,ra,sp`, `blt       sp,gp,pc-8`; Mobile writes only the mnemonic (`addi`, `add`, `blt`) |
| Run mode with a clock pin, breakpoint at `0x14` and two resumes (Evolution `BreakpointPanel` map injected; Mobile `SocSetBreakpointEvent` / `SocSetRunningEvent`) | equal: halts at ticks 6, 4 and 4 with the same registers, RV32 and Nios II |
| RV32 assembler, 85 statements one per case (Evolution `soc.util.Assembler` on a headless `RSyntaxTextArea`, Mobile `Rv32Assembler`) | 67 encoded identically and none with different words; 5 rejected by both |
| Accepted syntax | Evolution only: `jalr rd, rs1, imm`, `not`, `jr`, `seqz`, `snez`. Mobile only: `jalr rd, imm(rs1)`, `fence`, the `fp` alias, `0b` literals, uppercase |
| Whole sources | the sum program is equal word for word; `.data` with `.word` and a source using `not` are accepted only by Evolution |
| Nios II assembler | Evolution's `AssemblerPanel` assembles Nios II; Mobile has no Nios II assembler, which `docs/soc.md` documents as "Nios II programs can be loaded as ELF". Not run |

Fixes:
- RV32 trace text: format the operands in `Rv32Cpu` like Evolution's `getAsmInstruction()`, with ABI register names, `li` for `addi rd,zero,imm` and `pc±offset` targets. This is moderate work.
- Assembler syntax: accept `jalr rd, rs1, imm`, `not`, `jr`, `seqz` and `snez`. This is small.
- `.data`/`.word` directives and a Nios II assembler need their own issue.
