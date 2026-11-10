package dev.logisim.mobile.core

data class JarCircuitInput(val label: String, val width: Int)

/** Reads the main circuit's input controls without loading or evaluating an extension. */
fun readJarCircuitInputs(xml: String): List<JarCircuitInput> {
    val root = CircXmlParser(xml).parse()
    require(root.name == "project")
    val circuits = root.children.filter { it.name == "circuit" }
    val mainName = root.children.singleOrNull { it.name == "main" }?.attributes?.get("name")
    val main = requireNotNull(if (mainName == null) circuits.firstOrNull()
        else circuits.singleOrNull { it.attributes["name"] == mainName })
    val wiringIds = root.children.filter { it.name == "lib" && it.attributes["desc"] == "#Wiring" }
        .mapNotNull { it.attributes["name"] }.toSet()
    val labels = mutableSetOf<String>()
    return main.children.mapNotNull { pin ->
        if (pin.name != "comp" || pin.attributes["name"] != "Pin" || pin.attributes["lib"] !in wiringIds) {
            return@mapNotNull null
        }
        val attrs = mutableMapOf<String, String>()
        pin.children.filter { it.name == "a" }.forEach { attribute ->
            val name = requireNotNull(attribute.attributes["name"])
            require(attrs.put(name, attribute.attributes["val"] ?: attribute.text) == null)
        }
        val label = attrs["label"].orEmpty()
        require(label.isNotBlank() && label.length <= 128 && labels.add(label))
        val type = attrs["type"]
        require(type == null || type == "input" || type == "output")
        if (type == "output" || (type == null && attrs["output"] == "true")) return@mapNotNull null
        val width = attrs["width"]?.let { requireNotNull(it.toIntOrNull()) } ?: 1
        require(width in 1..64)
        JarCircuitInput(label, width)
    }.also { require(it.size <= 64) }
}
