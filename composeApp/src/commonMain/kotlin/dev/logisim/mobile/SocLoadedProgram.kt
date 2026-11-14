package dev.logisim.mobile

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import dev.logisim.mobile.core.Nios2Cpu
import dev.logisim.mobile.core.Nios2Disassembler
import dev.logisim.mobile.core.Rv32Disassembler
import dev.logisim.mobile.core.SocCpuDebugState
import dev.logisim.mobile.core.SocSetBreakpointEvent
import dev.logisim.mobile.localization.rememberLocalizer

private const val INSTRUCTIONS_PER_PAGE = 16

@Composable
internal fun SocLoadedProgram(
    debugger: SocCpuDebugState,
    pc: UInt,
    nios2: Boolean,
    onBreakpoint: (SocSetBreakpointEvent) -> Unit,
) {
    val strings = rememberLocalizer()
    debugger.breakpointHit?.let {
        Text(strings("soc_breakpoint_hit", "pc" to it.socHex()), color = MaterialTheme.colorScheme.primary)
    }
    val program = debugger.program ?: return
    var open by remember { mutableStateOf(false) }
    OutlinedButton(onClick = { open = !open }) {
        Text(strings(if (open) "soc_program_hide" else "soc_program_show"))
    }
    if (!open) return
    if (program.size == 0) {
        Text(strings("soc_program_no_code"))
        return
    }

    var manualPage by remember(program, debugger.breakpointHit) { mutableStateOf<Int?>(null) }
    val pcIndex = program.indexOf(pc)
    val page = manualPage ?: (pcIndex ?: 0) / INSTRUCTIONS_PER_PAGE
    val lastPage = (program.size - 1) / INSTRUCTIONS_PER_PAGE
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(enabled = page > 0, onClick = { manualPage = page - 1 }) {
            Text(strings("soc_program_previous"))
        }
        OutlinedButton(enabled = page < lastPage, onClick = { manualPage = page + 1 }) {
            Text(strings("soc_program_next"))
        }
        OutlinedButton(enabled = pcIndex != null && manualPage != null, onClick = { manualPage = null }) {
            Text(strings("soc_program_go_to_pc"))
        }
    }
    Text(strings("soc_program_page", "page" to page + 1, "total" to lastPage + 1), style = MaterialTheme.typography.bodySmall)
    Text(strings("soc_program_breakpoint_help"), style = MaterialTheme.typography.bodySmall)

    val first = page * INSTRUCTIONS_PER_PAGE
    // BreakpointPanel lists the disassembly: each word of the page is decoded once, as the trace writes it.
    val assembly = remember(program, page, nios2) {
        (first until minOf(first + INSTRUCTIONS_PER_PAGE, program.size)).map { index ->
            socDisassembly(program[index].instruction, nios2).orEmpty()
        }
    }
    Column {
        for (index in first until minOf(first + INSTRUCTIONS_PER_PAGE, program.size)) {
            val (address, instruction) = program[index]
            val marked = address in debugger.breakpoints
            val current = address == pc
            Row(
                Modifier.fillMaxWidth()
                    // The PC row: the selected tint and a leading accent bar (3:1), not a tint alone.
                    .background(MaterialTheme.colorScheme.surface).leadingSelectionCue(current)
                    .toggleable(marked, role = Role.Checkbox) { onBreakpoint(SocSetBreakpointEvent(address, it)) }
                    .heightIn(min = 48.dp).padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Checkbox(checked = marked, onCheckedChange = null)
                // Address and word on one line, the instruction under them: a phone needs no sideways scroll.
                Column {
                    Text(
                        strings(if (current) "soc_program_instruction_at_pc" else "soc_program_instruction",
                            "address" to address.socHex(), "word" to instruction.socHex()),
                        fontFamily = FontFamily.Monospace,
                    )
                    assembly[index - first].takeIf { it.isNotEmpty() }?.let {
                        Text(it, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

/** The trace text of an instruction word, or null for a data word neither processor decodes. */
internal fun socDisassembly(instruction: UInt, nios2: Boolean): String? =
    if (nios2) Nios2Disassembler.format(instruction) else Rv32Disassembler.format(instruction)

/** `x5  t0` / `r1  at`: the register number with its ABI name, padded so a grid of them lines up. */
internal fun socRegisterName(index: Int, nios2: Boolean): String {
    val number = (if (nios2) "r" else "x") + index
    val abi = if (nios2) Nios2Cpu.registerNames[index] else Rv32Disassembler.abiNames[index]
    return if (abi == number) number else "${number.padEnd(3)} $abi"
}
