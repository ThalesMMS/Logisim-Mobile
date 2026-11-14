package dev.logisim.mobile

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import dev.logisim.mobile.core.ComponentEvent
import dev.logisim.mobile.core.Nios2Assembler
import dev.logisim.mobile.core.Rv32Assembler
import dev.logisim.mobile.core.SocAssemblyResult
import dev.logisim.mobile.core.SocLoadAssemblyEvent
import dev.logisim.mobile.core.localization.message
import dev.logisim.mobile.localization.rememberLocalizer

@Composable
internal fun SocAssemblerTool(address: UInt, nios2: Boolean, send: (ComponentEvent) -> Unit) {
    val strings = rememberLocalizer()
    var expanded by remember { mutableStateOf(false) }
    var source by remember { mutableStateOf("") }
    var diagnostic by remember(address) { mutableStateOf<SocAssemblyResult.Rejected?>(null) }

    OutlinedButton(onClick = { expanded = !expanded }) {
        Text(strings(if (expanded) "soc_asm_hide" else "soc_asm_open"))
    }
    if (!expanded) return
    Text(strings("soc_asm_help", "address" to address.toString(16).padStart(8, '0').uppercase()))
    OutlinedTextField(
        source,
        { source = it; diagnostic = null },
        modifier = Modifier.fillMaxWidth(),
        label = { Text(strings("soc_asm_source")) },
        textStyle = TextStyle(fontFamily = FontFamily.Monospace),
        placeholder = { Text(if (nios2) "addi r5, zero, 7" else strings("soc_asm_example"), fontFamily = FontFamily.Monospace) },
        minLines = 3,
        maxLines = 8,
        keyboardOptions = FieldKeyboards.Code,
    )
    OutlinedButton(onClick = {
        val result = if (nios2) Nios2Assembler.assemble(source, address) else Rv32Assembler.assemble(source, address)
        when (val assembled = result) {
            is SocAssemblyResult.Rejected -> diagnostic = assembled
            is SocAssemblyResult.Assembled -> {
                diagnostic = null
                send(SocLoadAssemblyEvent(assembled))
            }
        }
    }) { Text(strings("soc_asm_load")) }
    diagnostic?.let { rejected ->
        val text = rejected.line?.let { message("soc_asm_line", "line" to it, "error" to rejected.notice) }
            ?: rejected.notice
        Text(strings(text), color = MaterialTheme.colorScheme.error)
    }
}
