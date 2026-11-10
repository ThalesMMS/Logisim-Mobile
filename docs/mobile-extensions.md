# Mobile extensions and desktop JAR libraries

The supported on-device extension is a **static component provider compiled into the application**. Its manifest describes API version, component identities, capabilities, publisher and license. Reading a manifest or opening a circuit never loads code. Android and iOS do not load desktop JAR bytecode.

`MobileExtensionCatalog` wraps an immutable `ComponentRegistry`. Installation requires an explicit `ExtensionApproval` containing the current canonical manifest SHA-256, the complete capability set and the accepted license ID. An API mismatch, unreviewed license, missing capability, duplicate identity or mismatch between the manifest and compiled definitions rejects the entire installation. Previously created catalogs stay unchanged. API **1.0** is supported; newer minor or different major versions require an application update.

The default host policy permits `pure-logic`, `stateful-logic` and `custom-rendering`. `companion-tcl` and `companion-jar` describe remote dependencies and are denied unless the application explicitly enables them under its companion policy. A manifest cannot grant process, network or filesystem access. Providers are application code and share its privileges; the registry does not provide an operating-system sandbox. State belongs to a circuit instance through `ComponentState`, never to a shared provider singleton.

Each provider must include its full license notice. The default reviewed SPDX IDs are GPL-3.0-only, GPL-3.0-or-later, MIT, Apache-2.0, BSD-2-Clause, BSD-3-Clause, ISC and 0BSD. This list records distribution review policy; it does not replace review of dependencies and notices. A changed notice changes the approval fingerprint. The extension panel shows these details and requires the user's explicit license acceptance before registration.

## Provider registration

Create a `StaticMobileExtension` from a `MobileExtensionManifest` and the matching list of `ComponentDefinition`s. Add the provider to the application build and pass it to `extensionsEditorTool`. Each declared kind, file name and library descriptor must exactly match its definition. A provider with stateful behavior must declare `stateful-logic`. Apply the returned catalog to the editor through the injected callback; registration itself does not rewrite a document.

The XML format is deliberately data-only. For example:

```xml
<mobile-extension id="org.example.counter" version="1.0.0" name="Example counter" publisher="Example Author" license="MIT" api="1.0">
  <notice>Copyright Example Author. Include the full MIT license text here.</notice>
  <capability name="stateful-logic"/>
  <component kind="ExampleCounter" name="Counter" library="#org.example.counter"/>
  <legacy-jar class="org.example.CounterLibrary"/>
</mobile-extension>
```

Unknown fields and capabilities are rejected. XML entities and external documents cannot execute code. This example is a schema illustration, not a bundled provider or a complete MIT notice.

## Desktop migration and unavailable libraries

`ExtensionReferenceScanner.inspect` records `jar#path#class` references without opening the path. `ExtensionDocumentEnvelope.exportOriginal()` returns the original XML verbatim, including library configuration, unknown component attributes and references. Paths can contain `#`; the last delimiter separates the class name. Unknown libraries are reported explicitly. Preserve that original envelope whenever an unavailable library prevents a faithful edited export.

For a ported static implementation, the publisher declares the old Java library class in `legacy-jar`. After installing that provider, an explicit `bindLegacyLibrary` action creates aliases for the original library descriptor. The source `.circ` keeps its desktop descriptor. An unrecognized class is rejected; there is no name-only substitution.

The application includes the Gray Code Incrementer provider, adapted from the Logisim 2.7.1 example. Open **Tools → Extensions**, accept its license and capabilities, then select **Use this extension for the legacy library** for `com.cburch.gray.Components`. This supports widths of 1 to 32 bits, with a default of four bits. The catalog keeps the approval and library binding while switching projects in that window. A new application session requires approval again. The component retains its original JAR descriptor on export.

If a project cannot be imported, the extension panel can inspect its retained XML and display the original text for copying. Selecting a matching legacy binding retries the import. Until that succeeds, an import attempt leaves the current document intact, and opening an unavailable project keeps its original XML available.

A library without a provider can be migrated from its source or a behavioral specification by writing a static provider. To use a companion instead, the selected host must advertise a fixed operation for that library and obtain the required trust approval. The `companion-jar` capability alone cannot authorize execution.

### Port an external 2.7.1 library

This example ports the four-bit Bus Inverter in [`org.example.InverterLibrary`](../core/src/jvmTest/resources/legacy-jar-migration/InverterLibrary.java). The [project fixture](../core/src/jvmTest/resources/legacy-jar-migration/inverter.circ) references the Java library by class. The [Kotlin provider](../core/src/jvmTest/kotlin/dev/logisim/mobile/core/BusInverterMigrationProvider.kt) is built by the JVM test target. To expose this example in an app build, copy the provider to `core/src/commonMain` under the same package and pass its `provider` in the list supplied to [`extensionsEditorTool`](../composeApp/src/commonMain/kotlin/dev/logisim/mobile/App.kt).

1. Use the library source to identify component file names, attributes, port offsets and widths. Run representative stimuli in the original and record the results. Preserve the original `.circ` before attempting conversion.
2. Implement the behavior and geometry in Kotlin. Use the fully qualified library class from the original `jar#path#class` descriptor in `legacyJarClasses`. Give the provider its own native library descriptor so it can also place new components.
3. Build the reviewed provider into the app. Open the project and go to **Tools → Extensions**. Review the license and capabilities, install the provider and select **Use this extension for the legacy library** beside its matching class. Retry the import through that action.
4. Exercise the same stimuli in the Mobile simulator, export the project and reopen it with the binding. Check port locations, widths, results and the retained JAR descriptor. A different class with an identically named component needs its own reviewed migration.

To reproduce the baseline, use JDK 21 and the [original Logisim 2.7.1 JAR](https://sourceforge.net/projects/circuit/files/2.7.x/2.7.1/logisim-generic-2.7.1.jar/download) with SHA-256 `362a78c12ad18c203fed868872c4a01cd9c12141379d92e892bbe2c37e627bc2`. From the repository root, set `original_jar` to the absolute path of that JAR and run:

```sh
fixture=core/src/jvmTest/resources/legacy-jar-migration
scratch=$(mktemp -d)
javac --release 8 -cp "$original_jar" -d "$scratch/library" "$fixture/InverterLibrary.java"
jar --create --file "$scratch/inverter.jar" -C "$scratch/library" .
cp "$fixture/inverter.circ" "$scratch/"
javac -cp "$original_jar" -d "$scratch/probe" scripts/oracle/OriginalCircuitCorpus.java
java -Djava.awt.headless=true -cp "$original_jar:$scratch/probe" OriginalCircuitCorpus \
  "$scratch/inverter.circ" "$fixture/inverter.ops.tsv" > "$scratch/original.tsv"
diff -u "$fixture/inverter.original.tsv" "$scratch/original.tsv"
./gradlew :core:jvmTest --tests '*LegacyJarMigrationExampleTest'
```

The JVM test uses the recorded 2.7.1 observations. It checks that import fails before binding, rejects a different class, then compares both ports and all 16 input/output pairs before and after exporting and reopening. The original XML is available unchanged before migration and the edited export retains the JAR reference and library tool.

## Verification

`MobileExtensionsTest` exercises SHA-256 vectors, manifest round trips, changed approvals, API/license/capability rejection, atomic registration, evaluation, catalog isolation, unavailable-library preservation and explicit JAR alias migration. These are JVM core checks. Native execution, panel interaction and distribution review are separate integration checks.

## Executing an uploaded Evolution library on the companion

The macOS companion can run a staged circuit containing one uploaded Evolution
JAR library. A host operator enables it with `--allow jar-run`, sets
`LOGISIM_COMPANION_EVOLUTION_JAR` to the absolute path of the trusted Evolution
runtime JAR, and supplies a dedicated JDK 21 with
`--tool jar-java=/path/to/jdk/bin/java`. The worker does not read libraries by
following the original file paths in the circuit. It loads the named library
class from the approved upload, even if the host runtime includes a bundled
class with the same name.

A request supplies `project.circ`, `library.jar`, a `libraryClass`, and up
to 256 `frames`. Each frame maps every input pin label to its logic bits in
most significant bit order, using `0`, `1`, `?`, or `E`. Set `circuit`
to select a named circuit, or omit it for the main circuit. The response
contains the library ID and SHA-256, circuit port directions and widths, and
one map of labeled outputs per frame. The runner keeps the same Evolution
circuit state for all frames in one approved job, including separate states
for multiple instances of the same component. Each job starts fresh.

In Mobile, pair the host in **Tools → Companion**. Open the circuit, then
open **Project → JAR execution** and select its JAR. If the editor cannot
open the JAR component, use **JAR execution** in the import error. The panel lists the main
circuit's labeled inputs. Set their logic bits and choose **Record step**,
or choose **Pulse** next to a one-bit input to record low, high, then low.
For manual input, switch to JSON and enter an array such as
`[{"CLK":"0"},{"CLK":"1"}]`. Review the file hashes and the host sandbox,
then approve the job to see the ports and outputs. Choose **Save project copy**
to save the original circuit bytes with their JAR reference.

Review the server's plan before submitting. Poll the job to renew its lease,
or cancel it with the same companion API used for other isolated workers.
The reply includes the original project bytes and hash, preserving its
external library descriptor for saving. Unsupported projects, missing
libraries, and incompatible classes fail explicitly. This operation currently
runs a finite sequence on the companion. Enable **Trace JAR terminals**
to inspect per-instance readings. The reply identifies the component by
its path and location and includes bounds, terminal directions and
widths, and resolved wire values after each recorded step. The trace
describes settled values, including on bidirectional ports.

### Leased live input protocol

A host can enable `jar-live` independently of the finite `jar-run`
operation. The plan approves the same two uploads and named library class,
plus the circuit, optional instance trace and a frame limit of 1–256.
The job stays active while the client renews its lease through the usual
status endpoint. Closing the lease or cancelling the job terminates the
sandboxed worker and discards its live outputs.

Once status contains the pin interface in `live.ports`, send one frame to
`POST /v1/jobs/{jobId}/input` with `sequence`, `revision` and `inputs`.
The first sequence is zero. Each frame assigns every input at its declared
width and advances the editor revision. Wait for status to report the same
`live.sequence` and `live.revision` before sending the next frame. Repeating
the same request retrieves the same receipt. Changing an accepted sequence
or sending steps out of order fails. End the run with the next sequence
and `finish: true` to collect the saved project and full trace. Input traffic
cannot renew the lease.

The Kotlin `CompanionJarLiveSession` checks job identity, order and revision,
and validates the final artifact hashes. The core can resolve the approved
project into native component layouts when the worker reports every JAR
instance. It matches the hierarchy path, circuit, factory and location,
preserves the original library identity, and binds native input vectors
to the same live job. An edited graph or a retired session requires a new
approval.

With `instanceTrace: true`, the plan can also approve `driverTrace: true`.
The fixed Evolution runtime reports each output and bidirectional terminal's
drive by component identity and location. The trace keeps this in
`instanceTrace.drives` alongside the settled wire values in
`instanceTrace.frames`. An input-only terminal has a null drive, a released
output has `Z`, and driven values use `0`, `1`, `?` and `E`. An unavailable
or incompatible driver trace fails the operation.

The core routes approved driver samples to the normal simulator using
the worker epoch, native instance address, and input revision. A pending
revision keeps the previous drive. A verified release removes the driver
from the wire, and reset or cancellation retires the binding. The simulator
recomputes the local network from these drives.

To run the circuit in the Mobile editor, enter the initial pin values in
the JAR panel and choose **Review live simulation**. Review the hashes,
host sandbox, and frame limit, then choose **Approve and open live editor**.
The session opens after the host returns verified instance layouts and
drives. Use the Poke tool on root input pins to send ordered updates. Close
or reset the live editor to stop the worker and return to the original
document. Backgrounding the app also ends the session. Each new session
requires its own approval.
