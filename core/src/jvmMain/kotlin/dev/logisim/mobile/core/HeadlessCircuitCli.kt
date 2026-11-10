package dev.logisim.mobile.core

import dev.logisim.mobile.core.localization.AppLanguage
import dev.logisim.mobile.core.localization.Messages
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.PrintStream
import kotlin.system.exitProcess

/** Product CLI for user-supplied circuits. See docs/headless-cli.md for its output contract. */
object HeadlessCircuitCli {
    private const val DEFAULT_TICKS = 10_000L
    private const val USAGE = """Usage: HeadlessCircuitCli temporal|truth-table|stats file.circ [options]
       HeadlessCircuitCli file.circ -tty table,halt,speed,stats,tty [options]
       HeadlessCircuitCli --test-vector CIRCUIT VECTOR_FILE file.circ [options] (alias -w)
       HeadlessCircuitCli --test-circuit file.circ [--ticks COUNT] (alias -b)
       HeadlessCircuitCli --new-file-format input.circ output.circ (alias -n)
       HeadlessCircuitCli --test-fpga PROJECT CIRCUIT BOARD_XML HDLONLY --output FILE [--mapping FILE] [--hdl-language verilog|vhdl] (alias -f)
       HeadlessCircuitCli --test-fpga PROJECT CIRCUIT BOARD_XML --companion URL --approve-build --output FILE [--mapping FILE] [--request-id ID]
  --circuit NAME       Select a circuit instead of the project's main circuit (alias --toplevel-circuit)
  --ticks COUNT        Temporal half-cycle limit (default 10000, unlimited with -tty)
  --load FILE [LABEL]  Load a memory image into one labeled RAM/ROM, or every remaining RAM (alias -l)
  --save FILE          Save the final contents of the last reachable RAM
  --sub OLD NEW        Use NEW for the .circ library reference OLD (repeatable, aliases -sub, -s, --substitute)
  --cancel-file FILE   Cancel when this path exists (exit 130)
  --terminal          Feed finite ASCII stdin to Keyboard and stream TTY bytes instead of CSV
  --tty-evolution     Match Evolution -tty table (accepts csv, tabs, binary and hex)
  -t, --tty MODES     Evolution's -tty: same as --tty-evolution -tty MODES, without requiring table
  --help              Show this help
"""
    private const val USAGE_PT = """Uso: HeadlessCircuitCli temporal|truth-table|stats arquivo.circ [opções]
     HeadlessCircuitCli arquivo.circ -tty table,halt,speed,stats,tty [opções]
     HeadlessCircuitCli --test-vector CIRCUIT VECTOR_FILE arquivo.circ [opções] (atalho -w)
     HeadlessCircuitCli --test-circuit arquivo.circ [--ticks COUNT] (atalho -b)
     HeadlessCircuitCli --new-file-format entrada.circ saída.circ (atalho -n)
     HeadlessCircuitCli --test-fpga PROJETO CIRCUITO PLACA_XML HDLONLY --output ARQUIVO [--mapping ARQUIVO] [--hdl-language verilog|vhdl] (atalho -f)
     HeadlessCircuitCli --test-fpga PROJETO CIRCUITO PLACA_XML --companion URL --approve-build --output ARQUIVO [--mapping ARQUIVO] [--request-id ID]
  --circuit NAME       Seleciona um circuito em vez do circuito principal (atalho --toplevel-circuit)
  --ticks COUNT        Limite de meios ciclos (padrão 10000, sem limite com -tty)
  --load FILE [LABEL]  Carrega uma imagem na RAM ou ROM indicada, ou nas demais RAMs (atalho -l)
  --save FILE          Salva o conteúdo final da última RAM alcançável
  --sub OLD NEW        Substitui a referência OLD por NEW (repetível, atalhos -sub, -s, --substitute)
  --cancel-file FILE   Cancela quando o caminho existe (código 130)
  --terminal          Envia o stdin ASCII ao teclado e escreve bytes do TTY em vez de CSV
  --tty-evolution     Usa a tabela do Evolution com -tty (csv, tabs, binary e hex)
  -t, --tty MODOS     O -tty do Evolution: igual a --tty-evolution -tty MODOS, sem exigir table
  --help              Mostra esta ajuda
"""
    private const val STARTUP_USAGE = """Startup options:
  --version             Show the application version (alias -v)
  --clear-prefs         Reset this JVM launcher's saved preferences
  --gates ansi|iec      Save the gate style in the launcher profile (alias -g)
  --locale en|pt        Save the language for launcher help and notices (alias -o)
  --user-template FILE|empty|plain
                        Select a template for new projects (alias -u)
  --new-project         Write a new .circ using the selected template
  --startup-settings    Show the effective launcher profile
  --geometry WxH        Requires a window and exits with an error on this host
  --no-splash           Report that this host already runs without a splash
"""
    private const val STARTUP_USAGE_PT = """Opções de inicialização:
  --version             Mostra a versão do aplicativo (atalho -v)
  --clear-prefs         Restaura as preferências salvas deste launcher JVM
  --gates ansi|iec      Salva o estilo de portas no perfil (atalho -g)
  --locale en|pt        Salva o idioma da ajuda e dos avisos (atalho -o)
  --user-template FILE|empty|plain
                        Seleciona o modelo para novos projetos (atalho -u)
  --new-project         Escreve um novo .circ com o modelo selecionado
  --startup-settings    Mostra o perfil efetivo do launcher
  --geometry WxH        Exige uma janela e retorna um erro neste host
  --no-splash           Informa que este host já inicia sem tela de abertura
"""

    @JvmStatic
    fun main(arguments: Array<String>) {
        exitProcess(run(arguments))
    }

    /** Streams the selected report or terminal bytes to stdout. Diagnostics go to stderr. */
    fun run(
        arguments: Array<String>,
        stdout: PrintStream = System.out,
        stderr: PrintStream = System.err,
        cancelled: () -> Boolean = { Thread.currentThread().isInterrupted },
    ): Int = run(arguments, stdout, stderr, System.`in`, cancelled)

    fun run(
        arguments: Array<String>,
        stdout: PrintStream,
        stderr: PrintStream,
        stdin: InputStream,
        cancelled: () -> Boolean = { Thread.currentThread().isInterrupted },
    ): Int {
        return try {
            val startup = HeadlessStartup()
            var commandStart = 0
            while (commandStart < arguments.size) {
                commandStart = startup.consume(arguments, commandStart) ?: break
            }
            val command = arguments.copyOfRange(commandStart, arguments.size)
            val fpga = HeadlessFpga.parse(command, startup)
            val options = if (fpga != null || (command.isEmpty() && startup.canRunWithoutCircuit)) null else parse(command, startup)
            val settings = startup.apply()
            if (startup.noSplash) {
                stderr.println(if (settings.language == AppLanguage.PORTUGUESE)
                    "warning=--no-splash: o launcher JVM já inicia sem tela de abertura."
                else "warning=--no-splash: the headless JVM launcher already runs without a splash.")
            }
            when {
                startup.help -> {
                    if (settings.language == AppLanguage.PORTUGUESE) {
                        stdout.print(USAGE_PT)
                        stdout.print(STARTUP_USAGE_PT)
                    } else {
                        stdout.print(USAGE)
                        stdout.print(STARTUP_USAGE)
                    }
                    return 0
                }
                startup.version -> {
                    val identity = HeadlessCircuitCli::class.java.`package`
                    val version = identity.implementationVersion ?: throw HeadlessStartupError("Application version is missing from the JVM JAR manifest.")
                    stdout.println("${identity.implementationTitle ?: "Logisim Mobile"} $version")
                    return 0
                }
                startup.showSettings -> { settings.print(stdout); return 0 }
                startup.newProject -> { stdout.print(settings.newProjectXml()); return 0 }
                fpga != null -> {
                    fpga.generate(stdout, stderr, settings.language) {
                        if (cancelled() || fpga.cancelFile?.toFile()?.exists() == true) throw Cancelled()
                    }
                    return 0
                }
                options == null -> return 0
            }
            fun checkCancellation() {
                if (cancelled() || options.cancelFile?.exists() == true) throw Cancelled()
            }
            checkCancellation()
            val resolved = if (options.substitutions.isEmpty()) null else try {
                readHeadlessProjectWithSubstitutions(options.file.toPath(), options.substitutions)
            } catch (error: IllegalArgumentException) {
                throw InputError(error.message ?: "Invalid library substitution.")
            }
            val project = when (val imported = resolved?.let { CircProjectImportResult.Success(it.project, it.migrations) }
                ?: try {
                    CircCodec.readProject(readHeadlessCircuitFile(options.file.toPath()))
                } catch (error: IllegalArgumentException) {
                    throw InputError(error.message ?: "Invalid circuit file.")
                }) {
                is CircProjectImportResult.Success -> {
                    imported.migrations.firstOrNull { it.notice.key == Messages.CodecRandomSimulationEnabled }?.let {
                        stderr.println("warning=${it.notice.resolve(settings.language)}")
                    }
                    imported.project
                }
                is CircProjectImportResult.Failure -> throw InputError(imported.diagnostics.joinToString("; "))
            }
            options.convertTo?.let { target ->
                try {
                    HeadlessAutomation.convert(project, options.file.toPath(), target.toPath(), ::checkCancellation)
                } catch (error: IllegalArgumentException) {
                    throw InputError(error.message ?: "Cannot convert this project.")
                }
                return 0
            }
            val circuitName = if (options.mode == "--test-circuit") HeadlessAutomation.BENCH_NAME
                else options.circuit ?: project.mainCircuitName
            if (project.circuit(circuitName) == null) throw InputError("No circuit named '$circuitName'.")
            val selected = project.copy(mainCircuitName = circuitName)
            if (options.mode == "stats" || LegacyTtyMode.STATS in options.legacyModes) {
                val statsOrigins = resolved?.takeIf {
                    options.legacyModes.isNotEmpty() || CircFormatProfile.parse(project.sourceVersion).originalLogisim
                }
                val rows = try {
                    HeadlessComponentStatistics.rows(selected, ComponentCatalog.registry, statsOrigins, ::checkCancellation)
                } catch (error: IllegalArgumentException) {
                    throw InputError(error.message ?: "Cannot count components in this circuit.")
                }
                if (options.mode == "stats") {
                    writeCsv(stdout, listOf("kind", "library", "component", "local", "unique", "recursive"))
                    rows.forEach { row ->
                        checkCancellation()
                        writeCsv(stdout, row)
                    }
                    stderr.println("status=stats")
                    return 0
                }
                HeadlessLegacyTty.writeStatistics(selected, options.file.name.removeSuffix(".circ"), statsOrigins, rows, stdout,
                    ::checkCancellation, options.evolutionProfile)
                if (options.legacyModes.size == 1) return 0
            }
            val simulationOptions = try {
                SimulationProjectOptions.read(project)
            } catch (error: IllegalArgumentException) {
                throw InputError(error.message ?: "Invalid project simulation options.")
            }
            val simulator = CircuitSimulator(simulationOptions.propagationLimit, cancellationCheck = ::checkCancellation)
            checkCancellation()
            if (options.mode == "--test-circuit") {
                val passed = try {
                    HeadlessAutomation.testBench(selected, simulator, simulationOptions.asMap(), options.ticks,
                        ::checkCancellation, ::requireSettled)
                } catch (error: IllegalArgumentException) {
                    throw InputError(error.message ?: "Invalid test bench.")
                }
                stdout.println(if (passed) "Test bench pass" else "Test bench fail")
                stdout.println()
                return if (passed) 0 else 1
            }
            options.evolutionTable?.let { table ->
                val written = try {
                    table.writeWithoutHalt(selected, simulator, simulationOptions.asMap(), stdout, ::checkCancellation)
                } catch (error: IllegalArgumentException) {
                    throw InputError(error.message ?: "Cannot write the Evolution table.")
                }
                if (written) return 0
            }
            if (options.legacyModes.isNotEmpty()) {
                val memory = prepareMemory(selected, options, ::checkCancellation)
                return HeadlessLegacyTty.simulate(selected, options.legacyModes, options.ticks, simulator,
                    simulationOptions.asMap(), memory.initialState, stdout, stdin, ::checkCancellation, memory::save,
                    options.evolutionProfile, stderr)
            }
            options.vectorFile?.let { file ->
                val session = try {
                    TestVectorRunSession(selected, circuitName, TestVectorFormat.read(file.readText()), simulator)
                } catch (error: VectorFormatException) {
                    throw InputError(error.message ?: "Invalid test vector file.")
                } catch (error: VectorBindingException) {
                    throw InputError(error.message ?: "Cannot bind test vector pins.")
                }
                repeat(session.table.rows.size) {
                    checkCancellation()
                    session.advance()
                }
                stdout.print(session.exportCsv())
                stdout.flush()
                check(!stdout.checkError()) { "Cannot write CSV output." }
                stderr.println("status=test-vector passed=${session.passedCount} failed=${session.failedCount}")
                return if (session.failedCount == 0) 0 else 1
            }
            if (options.mode == "truth-table") {
                val table = try {
                    val source = CircuitAnalyzer.pagedTruthTable(selected, registry = ComponentCatalog.registry,
                        simulator = simulator, options = simulationOptions.asMap())
                    val batch = 4_096
                    // Keep stdout empty when an invalid small circuit cannot produce a complete table.
                    val first = source.csvRows(0, minOf(batch, source.rowCount)) { checkCancellation(); false }
                    stdout.print(source.csvHeader())
                    stdout.print(first)
                    for (row in batch until source.rowCount step batch) {
                        stdout.print(source.csvRows(row, minOf(batch, source.rowCount - row)) { checkCancellation(); false })
                        check(!stdout.checkError()) { "Cannot write CSV output." }
                    }
                    check(!stdout.checkError()) { "Cannot write CSV output." }
                    source
                } catch (error: IllegalArgumentException) {
                    throw InputError(error.message ?: "Cannot analyze this circuit.")
                }
                stderr.println("status=truth-table rows=${table.rowCount}")
            } else {
                temporal(selected, options, simulationOptions, simulator, stdout, stderr, stdin, ::checkCancellation)
            }
            0
        } catch (_: Cancelled) {
            stderr.println("status=cancelled")
            130
        } catch (error: InputError) {
            stderr.println("error=${error.message}")
            2
        } catch (error: HeadlessFpgaError) {
            stderr.println("error=fpga stage=${error.stage} ${error.message}")
            2
        } catch (error: HeadlessStartupError) {
            stderr.println("error=${error.message}")
            2
        } catch (error: IOException) {
            stderr.println("error=${error.message}")
            2
        } catch (error: RuntimeException) {
            stderr.println("error=${error.message ?: error.javaClass.simpleName}")
            3
        }
    }

    private fun temporal(
        project: CircuitProject,
        options: Options,
        simulationOptions: SimulationProjectOptions,
        simulator: CircuitSimulator,
        stdout: PrintStream,
        stderr: PrintStream,
        stdin: InputStream,
        checkCancellation: () -> Unit,
    ) {
        val outputs = project.mainCircuit.components.filter { it.kind == ComponentKind.OUTPUT }
        if (outputs.isEmpty() && !options.terminal) throw InputError("The selected circuit has no output pins.")
        val haltPins = outputs.filter { it.label == "halt" }
        if (haltPins.size > 1 || haltPins.any { it.width != 1 }) {
            throw InputError("The selected circuit must have at most one one-bit output named 'halt'.")
        }
        val halt = haltPins.singleOrNull()
        val labels = outputs.map { it.label.ifBlank { it.id } }
        if (!options.terminal && labels.distinct().size != labels.size) throw InputError("Output pin names must be distinct.")
        if (!options.terminal && labels.any { it == "tick" || it == "propagation_time" }) {
            throw InputError("Output pin names 'tick' and 'propagation_time' are reserved in temporal mode.")
        }
        val memory = prepareMemory(project, options, checkCancellation)
        val settings = simulationOptions.asMap()
        var state = simulator.settle(project, memory.initialState, options = settings)
        requireSettled(state)
        val terminal = if (options.terminal) HeadlessTerminal(project, state, stdin, stdout, checkCancellation) else null
        if (terminal == null) writeCsv(stdout, listOf("tick", "propagation_time") + labels)
        var previous: List<String>? = null
        fun observe(snapshot: SimulationState, time: Long) {
            val values = outputs.map { pin ->
                (snapshot.portVectors[pin.position] ?: LogicVector.unknown(pin.width)).display
            }
            if (values != previous) {
                writeCsv(stdout, listOf(snapshot.ticks.toString(), time.toString()) + values)
                previous = values
            }
        }
        fun halted() = halt != null && state.portVectors[halt.position]?.toULongOrNull() == 1uL

        if (terminal == null) observe(state, state.propagationTime)
        while (!halted() && state.ticks < options.ticks) {
            checkCancellation()
            if (terminal == null) {
                state = simulator.settle(project, state, tick = true, observer = ::observe, options = settings)
            } else {
                val events = terminal.keyboardEvents(state)
                if (events.isNotEmpty()) {
                    state = simulator.settleTerminal(project, state, tick = false, events = events,
                        options = settings, onTerminalWrite = terminal::write)
                    requireSettled(state)
                    if (halted()) break
                }
                state = simulator.settleTerminal(project, state, tick = true, options = settings,
                    onTerminalWrite = terminal::write)
            }
            requireSettled(state)
        }
        terminal?.finish()
        memory.save(state)
        val reason = if (halted()) "halt" else "tick-limit"
        stderr.println("status=$reason ticks=${state.ticks}")
    }

    private fun requireSettled(state: SimulationState) {
        check(!state.oscillating && state.diagnostics.isEmpty()) {
            state.diagnostics.joinToString("; ").ifBlank { "Circuit did not settle within its configured propagation limit." }
        }
    }

    private fun prepareMemory(project: CircuitProject, options: Options, checkCancellation: () -> Unit): HeadlessMemory = try {
        HeadlessMemory(project, options.loads, options.save, checkCancellation, options.evolutionProfile)
    } catch (error: IllegalArgumentException) {
        throw InputError(error.message ?: "Cannot prepare memory images.")
    }

    private fun parse(arguments: Array<String>, startup: HeadlessStartup): Options {
        if (arguments.size < 2) throw InputError(USAGE.trimEnd())
        val first = when (arguments[0]) {
            "-b" -> "--test-circuit"
            "-n" -> "--new-file-format"
            else -> arguments[0]
        }
        val native = first in setOf("temporal", "truth-table", "stats", "--test-circuit", "--new-file-format")
        val mode = if (native) first else "legacy"
        val convertTo = if (mode == "--new-file-format") {
            File(arguments.getOrNull(2)?.takeUnless { it.startsWith('-') }
                ?: throw InputError("Missing output file for '$mode'."))
        } else null
        var file = if (native) File(arguments[1]) else null
        var ticks = if (native) DEFAULT_TICKS else Long.MAX_VALUE
        var circuit: String? = null
        val loads = mutableListOf<Pair<File, String?>>()
        var save: File? = null
        var cancelFile: File? = null
        var vectorFile: File? = null
        val substitutions = mutableListOf<HeadlessLibrarySubstitution>()
        val ttyArguments = mutableListOf<String>()
        var evolutionAlias = false
        val seen = mutableSetOf<String>()
        var index = if (convertTo != null) 3 else if (native) 2 else 0
        while (index < arguments.size) {
            val afterStartup = startup.consume(arguments, index)
            if (afterStartup != null) {
                index = afterStartup
                continue
            }
            val argument = arguments[index++]
            if (!native && (argument == "--test-vector" || argument == "-w")) {
                if (vectorFile != null) throw InputError("Repeated option '--test-vector'.")
                circuit = arguments.getOrNull(index++) ?: throw InputError("Missing circuit name for '$argument'.")
                vectorFile = File(arguments.getOrNull(index++) ?: throw InputError("Missing vector file for '$argument'."))
                continue
            }
            if (!native && (argument == "-tty" || argument == "-t" || argument == "--tty")) {
                // Evolution spells its -tty option -t or --tty; the literal -tty keeps the 2.7.1 profile.
                if (argument != "-tty") evolutionAlias = true
                val value = arguments.getOrNull(index++) ?: throw InputError("Missing modes for '$argument'.")
                ttyArguments += value.split(',').map(String::trim)
                continue
            }
            if (!native && !argument.startsWith('-') && file == null) {
                file = File(argument)
                continue
            }
            // Evolution option spellings: -l/--load, -s/--substitute and --toplevel-circuit.
            val option = when {
                !native && argument == "-load" -> "--load"
                argument == "-l" -> "--load"
                argument == "-s" || argument == "--substitute" -> "--sub"
                argument == "--toplevel-circuit" -> "--circuit"
                else -> argument
            }
            if (mode == "--test-circuit" && option !in setOf("--ticks", "--cancel-file", "--sub", "-sub")) {
                throw InputError("Option '$option' does not apply to '$mode'.")
            }
            if (convertTo != null && option != "--cancel-file") {
                throw InputError("Option '$option' does not apply to '$mode'.")
            }
            if (option == "--sub" || option == "-sub") {
                val original = arguments.getOrNull(index++) ?: throw InputError("Missing OLD path for '$option'.")
                val replacement = arguments.getOrNull(index++) ?: throw InputError("Missing NEW path for '$option'.")
                substitutions += HeadlessLibrarySubstitution(original, replacement)
                continue
            }
            if (option == "--load") {
                val image = arguments.getOrNull(index++) ?: throw InputError("Missing value for '$option'.")
                val label = arguments.getOrNull(index)?.takeUnless { it.startsWith('-') }
                if (label != null) index++
                loads += File(image) to label
                continue
            }
            if (!seen.add(option)) throw InputError("Repeated option '$option'.")
            if (option == "--terminal" || option == "--tty-evolution") continue
            if (option !in setOf("--ticks", "--circuit", "--save", "--cancel-file")) {
                throw InputError("Unknown option '$option'.")
            }
            val value = arguments.getOrNull(index++) ?: throw InputError("Missing value for '$option'.")
            when (option) {
                "--ticks" -> ticks = value.toLongOrNull()?.takeIf { it >= 0 }
                    ?: throw InputError("--ticks must be an integer in 0..${Long.MAX_VALUE}.")
                "--circuit" -> circuit = value
                "--save" -> save = File(value)
                "--cancel-file" -> cancelFile = File(value)
            }
        }
        if (file == null) {
            // Preserve legacy invocations that place the circuit after an unlabeled --load.
            val last = loads.indexOfLast { it.second != null }
            if (last >= 0) {
                val (image, circuitFile) = loads[last]
                file = File(checkNotNull(circuitFile))
                loads[last] = image to null
            }
        }
        val loadsByLabel = mutableMapOf<String, File>()
        for ((image, label) in loads) {
            if (loadsByLabel.putIfAbsent(label.orEmpty(), image) != null) {
                throw InputError(if (label.isNullOrEmpty()) "Repeated unlabeled --load." else "Repeated --load label '$label'.")
            }
        }
        val terminal = "--terminal" in seen
        if (vectorFile != null && (ttyArguments.isNotEmpty() || loadsByLabel.isNotEmpty() || seen.any { it != "--cancel-file" })) {
            throw InputError("--test-vector accepts only --sub and --cancel-file options.")
        }
        val explicitEvolution = "--tty-evolution" in seen
        val evolution = explicitEvolution || evolutionAlias
        val legacyModes = mutableSetOf<LegacyTtyMode>()
        val evolutionFormats = mutableSetOf<EvolutionTtyFormat>()
        for (name in ttyArguments) {
            val modeArgument = LegacyTtyMode.entries.firstOrNull { it.argument == name }
            val formatArgument = EvolutionTtyFormat.entries.firstOrNull { it.argument == name }
            when {
                modeArgument != null -> legacyModes += modeArgument
                evolution && formatArgument != null -> evolutionFormats += formatArgument
                else -> throw InputError("Unknown -tty mode '$name'. Use table, halt, speed, stats or tty." +
                    if (evolution) " Table modifiers are csv, tabs, binary and hex." else "")
            }
        }
        if (explicitEvolution && LegacyTtyMode.TABLE !in legacyModes) throw InputError("--tty-evolution requires -tty table.")
        if (evolution && evolutionFormats.isNotEmpty() && LegacyTtyMode.TABLE !in legacyModes) {
            throw InputError("Table modifiers csv, tabs, binary and hex require the table mode.")
        }
        if (!native && legacyModes.isEmpty() && vectorFile == null) throw InputError(USAGE.trimEnd())
        if (mode in setOf("truth-table", "stats") && ("--ticks" in seen || loadsByLabel.isNotEmpty() || save != null || terminal)) {
            throw InputError("--ticks, --load, --save and --terminal apply only to temporal execution.")
        }
        if (!native && terminal) throw InputError("Use -tty tty to enable terminal I/O in the 2.7.1 mode.")
        if (legacyModes == setOf(LegacyTtyMode.STATS) && ("--ticks" in seen || loads.isNotEmpty() || save != null)) {
            throw InputError("--ticks, --load and --save require temporal simulation.")
        }
        return Options(mode, file ?: throw InputError("The circuit file is required."), circuit, ticks, loadsByLabel, save,
            cancelFile, substitutions, terminal, legacyModes,
            if (evolution && LegacyTtyMode.TABLE in legacyModes) HeadlessEvolutionTtyTable(evolutionFormats) else null,
            vectorFile, convertTo, evolution)
    }

    private fun writeCsv(output: PrintStream, fields: List<String>) {
        output.println(fields.joinToString(",") { value ->
            if (value.any { it == ',' || it == '"' || it == '\n' || it == '\r' }) "\"${value.replace("\"", "\"\"")}\""
            else value
        })
        output.flush()
        check(!output.checkError()) { "Cannot write CSV output." }
    }

    private data class Options(
        val mode: String,
        val file: File,
        val circuit: String?,
        val ticks: Long,
        val loads: Map<String, File>,
        val save: File?,
        val cancelFile: File?,
        val substitutions: List<HeadlessLibrarySubstitution>,
        val terminal: Boolean,
        val legacyModes: Set<LegacyTtyMode>,
        val evolutionTable: HeadlessEvolutionTtyTable?,
        val vectorFile: File?,
        val convertTo: File?,
        val evolutionProfile: Boolean,
    )

    private class InputError(message: String) : IllegalArgumentException(message)
    internal class Cancelled : RuntimeException()
}
