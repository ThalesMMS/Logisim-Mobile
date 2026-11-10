package dev.logisim.mobile

import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.TextFieldColors
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation

/** Token colors of the RSyntaxTextArea 4.0.1 theme files that Evolution's EditorTheme loads. */
internal data class HdlEditorPalette(
    val background: Color,
    val caret: Color,
    val identifier: Color,
    val reserved: Color,
    val reservedBold: Boolean,
    val comment: Color,
    val commentItalic: Boolean,
    val dataType: Color,
    val number: Color,
    val string: Color,
    val stringBold: Boolean = false,
)

internal val HdlEditorTheme.palette: HdlEditorPalette get() = when (this) {
    HdlEditorTheme.DEFAULT -> HdlEditorPalette(Color(0xFFFFFFFF), Color(0xFFFF0000), Color(0xFF000000), Color(0xFF0000FF), true,
        Color(0xFF008000), true, Color(0xFF008080), Color(0xFF6400C8), Color(0xFFDC009C))
    HdlEditorTheme.DARK -> HdlEditorPalette(Color(0xFF293134), Color(0xFFC1CBC2), Color(0xFFA9B7C6), Color(0xFF93C763), true,
        Color(0xFF66747B), false, Color(0xFF678CB1), Color(0xFFFFCD22), Color(0xFFEC7600))
    HdlEditorTheme.MONOKAI -> HdlEditorPalette(Color(0xFF2E2E2E), Color(0xFFD2D2D2), Color(0xFFF9F9F9), Color(0xFFF92672), false,
        Color(0xFF6C6C6C), false, Color(0xFF52E3F6), Color(0xFFAE81FF), Color(0xFFE3ED77))
    HdlEditorTheme.ECLIPSE -> HdlEditorPalette(Color(0xFFFFFFFF), Color(0xFF000000), Color(0xFF000000), Color(0xFF7F0055), true,
        Color(0xFF3F7F5F), false, Color(0xFF7F0055), Color(0xFF000000), Color(0xFF2900FF))
    HdlEditorTheme.IDEA -> HdlEditorPalette(Color(0xFFFFFFFF), Color(0xFF000000), Color(0xFF000000), Color(0xFF000080), true,
        Color(0xFF808080), true, Color(0xFF000080), Color(0xFF0000FF), Color(0xFF008000), stringBold = true)
    HdlEditorTheme.VS -> HdlEditorPalette(Color(0xFFFFFFFF), Color(0xFF000000), Color(0xFF000000), Color(0xFF0000FF), true,
        Color(0xFF008000), false, Color(0xFF0000FF), Color(0xFF000000), Color(0xFFA31515))
    HdlEditorTheme.DRUID -> HdlEditorPalette(Color(0xFF001C00), Color(0xFF88EE88), Color(0xFF66AA66), Color(0xFF885522), true,
        Color(0xFF325232), false, Color(0xFF7F0055), Color(0xFFEEAACC), Color(0xFF009000))
}

private val HdlReservedWords = setOf(
    // VHDL
    "abs", "access", "after", "alias", "all", "and", "architecture", "array", "assert", "attribute", "begin", "block",
    "body", "buffer", "bus", "case", "component", "configuration", "constant", "disconnect", "downto", "else", "elsif",
    "end", "entity", "exit", "file", "for", "function", "generate", "generic", "group", "guarded", "if", "impure", "in",
    "inertial", "inout", "is", "label", "library", "linkage", "literal", "loop", "map", "mod", "nand", "new", "next",
    "nor", "not", "null", "of", "on", "open", "or", "others", "out", "package", "port", "postponed", "procedure",
    "process", "pure", "range", "record", "register", "reject", "rem", "report", "return", "rol", "ror", "select",
    "severity", "signal", "shared", "sla", "sll", "sra", "srl", "subtype", "then", "to", "transport", "type", "units",
    "until", "use", "variable", "wait", "when", "while", "with", "xnor", "xor",
    // Verilog
    "always", "assign", "casex", "casez", "default", "defparam", "endcase", "endfunction", "endgenerate", "endmodule",
    "endtask", "forever", "genvar", "initial", "input", "localparam", "module", "negedge", "output", "parameter",
    "posedge", "reg", "repeat", "task", "wire",
)
private val HdlDataTypes = setOf(
    "std_logic", "std_logic_vector", "std_ulogic", "std_ulogic_vector", "bit", "bit_vector", "boolean", "integer",
    "natural", "positive", "signed", "unsigned", "string", "character", "real", "time", "logic", "integer",
)
private val HdlToken = Regex("""--[^\n]*|//[^\n]*|/\*[\s\S]*?(\*/|$)|"[^"\n]*"?|'[01xXzZuUlLhHwW-]'|\b\d[\d_]*(\.\d+)?\b|[A-Za-z_][A-Za-z0-9_]*""")

/** Colors HDL source like RSyntaxTextArea's VHDL/Verilog token makers do in the chosen theme. */
internal fun highlightHdl(source: String, palette: HdlEditorPalette): AnnotatedString = buildAnnotatedString {
    append(source)
    addStyle(SpanStyle(color = palette.identifier), 0, source.length)
    for (match in HdlToken.findAll(source)) {
        val token = match.value
        val style = when {
            token.startsWith("--") || token.startsWith("//") || token.startsWith("/*") ->
                SpanStyle(color = palette.comment, fontStyle = if (palette.commentItalic) FontStyle.Italic else null)
            token.startsWith("\"") || token.startsWith("'") ->
                SpanStyle(color = palette.string, fontWeight = if (palette.stringBold) FontWeight.Bold else null)
            token.first().isDigit() -> SpanStyle(color = palette.number)
            token.lowercase() in HdlReservedWords ->
                SpanStyle(color = palette.reserved, fontWeight = if (palette.reservedBold) FontWeight.Bold else null)
            token.lowercase() in HdlDataTypes -> SpanStyle(color = palette.dataType, fontWeight = FontWeight.Bold)
            else -> null
        } ?: continue
        addStyle(style, match.range.first, match.range.last + 1)
    }
}

/** The HDL editor's theme for the current light or dark mode, as EditorTheme.apply picks it. */
internal class HdlEditorStyle(val theme: HdlEditorTheme) {
    val palette = theme.palette
    val transformation = VisualTransformation { text -> TransformedText(highlightHdl(text.text, palette), OffsetMapping.Identity) }

    @Composable
    fun colors(): TextFieldColors = OutlinedTextFieldDefaults.colors(
        focusedContainerColor = palette.background, unfocusedContainerColor = palette.background,
        disabledContainerColor = palette.background, cursorColor = palette.caret,
        focusedTextColor = palette.identifier, unfocusedTextColor = palette.identifier, disabledTextColor = palette.identifier,
    )
}

@Composable
internal fun rememberHdlEditorStyle(): HdlEditorStyle {
    val dark = LocalEditorDarkTheme.current
    val theme = LocalEditorPreferences.current?.preferences?.editorTheme(dark) ?: if (dark) HdlEditorTheme.DARK else HdlEditorTheme.DEFAULT
    return remember(theme) { HdlEditorStyle(theme) }
}
