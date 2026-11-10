package dev.logisim.mobile.core

import dev.logisim.mobile.core.localization.AppLanguage
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.Properties

internal class HeadlessStartupError(message: String) : IllegalArgumentException(message)

internal class HeadlessStartup {
    private val options = mutableMapOf<String, String>()

    val canRunWithoutCircuit get() = options.isNotEmpty()
    val help get() = "--help" in options
    val version get() = "--version" in options
    val showSettings get() = "--startup-settings" in options
    val newProject get() = "--new-project" in options
    val noSplash get() = "--no-splash" in options

    /** Consume one startup option at an option boundary, leaving command values to the command parser. */
    fun consume(arguments: Array<String>, index: Int): Int? {
        val token = arguments[index]
        val name = token.substringBefore('=')
        val option = when (name) {
            "-h" -> "--help"
            "-v" -> "--version"
            "-g" -> "--gates"
            "-o" -> "--locale"
            "-u" -> "--user-template"
            "-m" -> "--geometry"
            else -> name
        }
        val needsValue = when (option) {
            "--gates", "--locale", "--user-template", "--geometry" -> true
            "--help", "--version", "--clear-prefs", "--no-splash", "--startup-settings", "--new-project" -> false
            else -> return null
        }
        val inlineValue = token.substringAfter('=', "").takeIf { '=' in token }
        if (!needsValue && inlineValue != null) throw HeadlessStartupError("Option '$option' does not take a value.")
        if (option in options) throw HeadlessStartupError("Repeated option '$option'.")
        val value = if (needsValue) inlineValue ?: arguments.getOrNull(index + 1)
            ?.takeUnless { it.startsWith('-') }
            ?: throw HeadlessStartupError("Missing value for '$option'.")
        else ""
        if (needsValue && value.isBlank()) throw HeadlessStartupError("Missing value for '$option'.")
        if (option == "--geometry") {
            throw HeadlessStartupError("--geometry requires a window; the headless JVM launcher has no window.")
        }
        options[option] = value
        return index + if (needsValue && inlineValue == null) 2 else 1
    }

    fun apply(): HeadlessStartupSettings {
        val gateShape = options["--gates"]?.lowercase()?.also {
            if (it != "ansi" && it != "iec") throw HeadlessStartupError("--gates must be ansi or iec.")
        }
        val locale = options["--locale"]?.let { value ->
            val tag = value.substringBefore('-').substringBefore('_').lowercase()
            if (tag !in setOf("en", "pt")) throw HeadlessStartupError("--locale must be en or pt (regional variants are accepted).")
            AppLanguage.fromTag(value).tag
        }
        val template = options["--user-template"]?.let { value ->
            when (val builtIn = value.lowercase()) {
                "empty", "plain" -> builtIn
                else -> Path.of(value).toAbsolutePath().normalize().also { readStartupTemplate(it) }.toString()
            }
        }

        val profile = System.getenv("LOGISIM_MOBILE_CLI_PROFILE")?.takeIf(String::isNotBlank)?.let { Path.of(it) }
            ?: Path.of(System.getProperty("user.home"), ".logisim-mobile", "cli.properties")
        val clear = "--clear-prefs" in options
        val properties = Properties()
        if (!clear && Files.exists(profile)) Files.newInputStream(profile).use(properties::load)
        gateShape?.let { properties.setProperty("gates", it) }
        locale?.let { properties.setProperty("locale", it) }
        template?.let { properties.setProperty("template", it) }
        if (gateShape != null || locale != null || template != null) writeProfile(profile, properties)
        else if (clear) Files.deleteIfExists(profile)
        return HeadlessStartupSettings(
            gates = properties.getProperty("gates", "ansi"),
            language = AppLanguage.fromTag(properties.getProperty("locale")),
            template = properties.getProperty("template", "plain"),
        )
    }

    private fun writeProfile(path: Path, properties: Properties) {
        val target = path.toAbsolutePath()
        Files.createDirectories(target.parent)
        val temporary = Files.createTempFile(target.parent, ".logisim-cli-", ".tmp")
        try {
            Files.newOutputStream(temporary).use { properties.store(it, "Logisim Mobile JVM launcher") }
            Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } finally {
            Files.deleteIfExists(temporary)
        }
    }
}

internal data class HeadlessStartupSettings(val gates: String, val language: AppLanguage, val template: String) {
    fun print(output: PrintStream) {
        output.println("gates=$gates")
        output.println("locale=${language.tag}")
        output.println("template=$template")
    }

    fun newProjectXml(): String {
        val empty = CircuitProject(listOf(CircuitDocument()))
        return when (template) {
            "empty" -> CircCodec.writeProject(empty)
            "plain" -> CircCodec.writeProject(SimulationProjectOptions().applyTo(empty))
            else -> readStartupTemplate(Path.of(template))
        }
    }
}

private fun readStartupTemplate(path: Path): String {
    val xml = try {
        readHeadlessCircuitFile(path)
    } catch (error: IllegalArgumentException) {
        throw HeadlessStartupError("Invalid user template '$path': ${error.message}")
    }
    val imported = CircCodec.readProject(xml)
    if (imported is CircProjectImportResult.Failure) {
        throw HeadlessStartupError("Invalid user template '$path': ${imported.diagnostics.joinToString("; ")}")
    }
    return xml
}
