package dev.logisim.mobile.core

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

internal object HeadlessAutomation {
    const val BENCH_NAME = "logisim_test_verif"
    private const val DONE = "test_bench_done_o"
    private const val OK = "test_bench_ok_o"

    fun testBench(
        project: CircuitProject,
        simulator: CircuitSimulator,
        options: Map<String, String>,
        tickLimit: Long,
        checkCancellation: () -> Unit,
        requireSettled: (SimulationState) -> Unit,
    ): Boolean {
        fun output(label: String): CircuitComponent {
            val matches = project.mainCircuit.components.filter { it.kind == ComponentKind.OUTPUT && it.label == label }
            require(matches.size == 1 && matches.single().width == 1) {
                "Test bench '$BENCH_NAME' requires one one-bit output named '$label'."
            }
            return matches.single()
        }

        val done = output(DONE)
        val ok = output(OK)
        var state = simulator.settle(project, options = options)
        requireSettled(state)
        while (state.portVectors[done.position]?.toULongOrNull() != 1uL) {
            checkCancellation()
            if (state.ticks >= tickLimit) return false
            state = simulator.settle(project, state, tick = true, options = options)
            requireSettled(state)
        }
        return state.portVectors[ok.position]?.toULongOrNull() == 1uL
    }

    fun convert(project: CircuitProject, source: Path, destination: Path, checkCancellation: () -> Unit) {
        val target = destination.toAbsolutePath()
        require(!Files.exists(target) || !Files.isSameFile(source, target)) {
            "The output file must be different from the input file."
        }
        val xml = CircCodec.writeProject(project)
        checkCancellation()
        val temporary = Files.createTempFile(target.parent, ".logisim-convert-", ".tmp")
        try {
            Files.writeString(temporary, xml)
            checkCancellation()
            Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } finally {
            Files.deleteIfExists(temporary)
        }
    }
}
