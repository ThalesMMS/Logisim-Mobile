package dev.logisim.mobile.core

import java.io.File

/** Writes artifacts for independent HDL compilers; no expected result is computed from generated RTL. */
fun main(arguments: Array<String>) {
    require(arguments.size in 1..2 && arguments.getOrNull(1) in setOf(null, "upper", "lower")) {
        "Usage: HdlExportOracleMain <artifact-directory> [upper|lower]"
    }
    // Evolution's VhdlKeywordsUpperCase preference; uppercase is its default.
    val options = FpgaGenerationOptions(vhdlKeywordsUpperCase = arguments.getOrNull(1) != "lower")
    val destination = File(arguments[0]).absoluteFile.apply { mkdirs() }
    File(destination, "result.json").delete()
    val manifests = mutableListOf<String>()
    for (fixture in HdlFixtureCases.all()) for (language in HdlLanguage.entries) {
        val result = HdlExporter.export(fixture.project, language, options = options)
        check(result.successful) { "${fixture.id}: ${result.diagnostics}" }
        val directory = File(destination, "${fixture.id}/${language.name.lowercase()}").apply { mkdirs() }
        for (file in result.files) File(directory, file.path).writeText(file.content)
        val benchName = "tb.${language.extension}"
        File(directory, benchName).writeText(when (language) {
            HdlLanguage.VERILOG -> verilogBench(fixture, result)
            HdlLanguage.VHDL -> vhdlBench(fixture, result)
        })
        File(directory, "source.circ").writeText(CircCodec.writeProject(fixture.project))
        val files = (result.files.map { it.path } + benchName).joinToString(",") { "\"$it\"" }
        val outputChecks = fixture.vectors.sumOf { it.hdlOutputs.size + it.errorOutputs.size }
        manifests += "{\"fixture\":\"${fixture.id}\",\"language\":\"${language.name}\",\"directory\":\"${fixture.id}/${language.name.lowercase()}\",\"files\":[$files],\"vectors\":${fixture.vectors.size},\"outputChecks\":$outputChecks}"
    }
    File(destination, "fixtures.json").writeText("{\"schema\":1,\"fixtures\":[${manifests.joinToString(",")}]}\n")
    println("Generated ${manifests.size} HDL fixture artifacts in $destination")
}

private fun verilogBench(fixture: HdlFixtureCase, result: HdlExportResult): String = buildString {
    append("`timescale 1ns/1ps\nmodule tb;\n")
    for (port in result.ports) append("  ${if (port.direction == PortDirection.INPUT) "reg" else "wire"} [${port.width - 1}:0] ${port.name};\n")
    append("  ${result.topLevelName} dut(${result.ports.joinToString(", ") { ".${it.name}(${it.name})" }});\n")
    append("  initial begin\n")
    val ports = result.ports.associateBy { it.sourceComponentId }
    fixture.vectors.forEachIndexed { index, vector ->
        for ((id, value) in vector.inputs) {
            val port = ports.getValue(id)
            append("    ${port.name} = ${verilogLiteral(port.width, value)};\n")
        }
        append("    #10;\n")
        for ((id, expected) in vector.hdlOutputs) {
            val port = ports.getValue(id)
            append("    if (${port.name} !== ${verilogLiteral(port.width, expected)}) \$fatal(1, \"${fixture.id} vector $index output $id\");\n")
        }
        for (id in vector.errorOutputs) {
            val port = ports.getValue(id)
            append("    if (${port.name} !== {${port.width}{1'bx}}) \$fatal(1, \"${fixture.id} vector $index output $id\");\n")
        }
    }
    append("    \$display(\"HDL_EXPORT_PASS ${fixture.id}\");\n    \$finish;\n  end\nendmodule\n")
}

private fun vhdlBench(fixture: HdlFixtureCase, result: HdlExportResult): String = buildString {
    append("library ieee;\nuse ieee.std_logic_1164.all;\nuse ieee.numeric_std.all;\nentity tb is end entity;\narchitecture test of tb is\n")
    for (port in result.ports) append("  signal ${port.name} : std_logic_vector(${port.width - 1} downto 0) := (others => '0');\n")
    append("begin\n  dut : entity work.${result.topLevelName} port map (${result.ports.joinToString(", ") { "${it.name} => ${it.name}" }});\n")
    append("  process\n  begin\n")
    val ports = result.ports.associateBy { it.sourceComponentId }
    fixture.vectors.forEachIndexed { index, vector ->
        for ((id, value) in vector.inputs) {
            val port = ports.getValue(id)
            append("    ${port.name} <= ${vhdlLiteral(port.width, value)};\n")
        }
        append("    wait for 10 ns;\n")
        for ((id, expected) in vector.hdlOutputs) {
            val port = ports.getValue(id)
            append("    assert ${port.name} = ${vhdlLiteral(port.width, expected)} report \"${fixture.id} vector $index output $id\" severity failure;\n")
        }
        for (id in vector.errorOutputs) {
            val port = ports.getValue(id)
            val expected = "\"${"X".repeat(port.width)}\""
            append("    assert ${port.name} = $expected report \"${fixture.id} vector $index output $id\" severity failure;\n")
        }
    }
    append("    report \"HDL_EXPORT_PASS ${fixture.id}\";\n    std.env.stop;\n    wait;\n  end process;\nend architecture;\n")
}

private fun verilogLiteral(width: Int, value: ULong) = "${width}'b${value.toString(2).takeLast(width).padStart(width, '0')}"
private fun vhdlLiteral(width: Int, value: ULong) = "\"${value.toString(2).takeLast(width).padStart(width, '0')}\""
