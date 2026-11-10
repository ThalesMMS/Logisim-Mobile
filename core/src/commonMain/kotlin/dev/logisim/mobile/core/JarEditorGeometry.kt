package dev.logisim.mobile.core

internal data class JarEditorInstance(
    val eventAddress: String,
    val geometry: JarObservedInstance,
)

internal data class JarEditorInputFrame(
    val revision: Long,
    val values: Map<String, LogicVector>,
)

/** Geometry belongs to the original graph and the approved worker that supplied it. */
class JarEditorGeometry internal constructor(
    val project: CircuitProject,
    val registry: ComponentRegistry,
    internal val instances: Map<String, JarEditorInstance>,
    val sourceXml: String,
    private val sessionIdentity: Any,
    private val driverBinding: Boolean,
    private val isCurrent: () -> Boolean,
) {
    private val inputPins = project.mainCircuit.components.filter { it.kind == ComponentKind.INPUT }
    private val inputs = readJarCircuitInputs(sourceXml).associate { it.label to it.width }

    init {
        require(inputPins.size == inputs.size && inputPins.map { it.label }.toSet() == inputs.keys)
    }

    internal fun isOwnedBy(identity: Any): Boolean = sessionIdentity === identity

    fun inputRevisionEvents(current: CircuitProject, revision: Long): Map<String, List<ComponentEvent>> {
        check(driverBinding && isCurrent() && current === project)
        require(revision >= 0)
        return instances.values.associate { instance ->
            instance.eventAddress to listOf(JarInputRevisionEvent(sessionIdentity, instance.eventAddress, revision))
        }
    }

    fun outputEvents(current: CircuitProject, root: SimulationState, revision: Long,
                              sample: JarLiveSample): Map<String, List<ComponentEvent>> {
        check(driverBinding && sample.sessionIdentity === sessionIdentity)
        require(sample.inputRevision == revision && captureInputFrame(current, root, revision).values == sample.inputs) {
            "The JAR reply belongs to another editor input revision."
        }
        val trace = requireNotNull(sample.trace)
        require(trace.instances.associateBy { it.id } == instances.mapValues { it.value.geometry }) {
            "The JAR reply changed its approved instance geometry."
        }
        val drivers = requireNotNull(trace.drives?.singleOrNull()) { "The JAR reply has no exact driver sample." }
        require(drivers.keys == instances.keys)
        return instances.map { (id, instance) ->
            val terminals = instance.geometry.layout.ports
            val observed = drivers.getValue(id)
            require(observed.size == terminals.size)
            val ports = terminals.zip(observed).mapNotNull { (terminal, drive) ->
                if (terminal.direction == PortDirection.INPUT) {
                    require(drive == null)
                    null
                } else {
                    val value = requireNotNull(drive)
                    require(value !is JarTerminalDrive.Driven || value.value.width == terminal.width)
                    terminal.name to value
                }
            }.toMap()
            instance.eventAddress to listOf(JarDriveReplyEvent(sessionIdentity, instance.eventAddress,
                sample.sequence, sample.inputRevision, ports))
        }.toMap()
    }

    internal fun captureInputFrame(current: CircuitProject, root: SimulationState, revision: Long): JarEditorInputFrame {
        check(isCurrent() && current === project) { "The approved JAR session no longer owns this project." }
        require(root.instancePath.isEmpty() && root.circuitName == project.mainCircuit.name && revision >= 0)
        val values = inputPins.associate { pin ->
            val value = requireNotNull(root.inputVectors[pin.id]) { "The native JAR input is not available." }
            require(value.width == inputs.getValue(pin.label)) { "The native JAR input width changed." }
            pin.label to value
        }
        return JarEditorInputFrame(revision, values)
    }
}

/**
 * Called only after the session has checked the project digest and ownership of the worker reply.
 * The temporary registry decodes the original factory names. Only the returned registry can
 * read a component's verified geometry, and it does not resolve those names for another import.
 */
internal fun resolveJarEditorGeometry(
    xml: String,
    libraryClass: String,
    trace: JarInstanceTrace,
    baseRegistry: ComponentRegistry,
    sessionIdentity: Any,
    isCurrent: () -> Boolean,
): JarEditorGeometry {
    check(isCurrent()) { "The approved JAR session ended." }
    val reference = ExtensionReferenceScanner.inspect(xml).jarLibraries.distinctBy { it.descriptor }.singleOrNull()
    require(reference != null && reference.className == libraryClass) { "The JAR project has a different library reference." }
    require(trace.instances.isNotEmpty() && trace.instances.size <= 128)
    val layouts = trace.instances.associateBy { it.id }
    require(layouts.size == trace.instances.size) { "Duplicate JAR instance metadata." }

    val factories = trace.instances.map { it.factory }.distinct()
    require(baseRegistry.definitions.none { it.library == reference.descriptor }) {
        "The editor already has another definition for this JAR factory."
    }
    val definitions = factories.mapIndexed { index, factory ->
        ComponentDefinition(
            kind = ComponentKind("JAR:${ExtensionDigest.sha256(reference.descriptor.encodeToByteArray())}:$index"),
            fileName = factory,
            library = reference.descriptor,
            supportsRotation = false,
            layout = ::jarComponentLayout,
        )
    }
    val factoryNames = definitions.associate { it.kind to it.fileName }
    val decoded = CircCodec.readProject(xml, baseRegistry.withAdditional(definitions))
    val project = (decoded as? CircProjectImportResult.Success)?.project
        ?: throw IllegalArgumentException("The approved JAR project cannot be opened with these factory definitions.")

    val instances = linkedMapOf<String, JarEditorInstance>()
    val placementLayouts = mutableMapOf<Pair<String, String>, ComponentLayout>()
    var visits = 0

    fun bind(document: CircuitDocument, path: List<GridPoint>, parentAddress: List<String>) {
        require(path.size <= 24)
        for (component in document.components) {
            require(++visits <= 8192) { "The JAR project exceeds the approved hierarchy limits." }
            if (component.kind == ComponentKind.SUBCIRCUIT) {
                bind(requireNotNull(project.circuit(component.circuitName.orEmpty())),
                    path + component.position, parentAddress + component.id)
                continue
            }
            val factory = factoryNames[component.kind] ?: continue
            val coordinate = path + component.position
            val id = coordinate.joinToString("") { "/${it.x},${it.y}" }
            val observed = requireNotNull(layouts[id]) { "A JAR instance is missing its approved geometry." }
            require(observed.path == path && observed.circuit == document.name && observed.location == component.position &&
                observed.factory == factory) { "The JAR geometry does not match the native instance." }
            require(observed.layout.ports.size == observed.exclusiveTerminals.size &&
                observed.layout.ports.all { it.width in 1..64 }) { "The JAR terminal geometry is incomplete." }
            val instance = JarEditorInstance((parentAddress + component.id).joinToString("/"), observed)
            require(instances.put(id, instance) == null) { "Multiple native instances match the same JAR geometry." }
            val placement = document.id to component.id
            val previous = placementLayouts.put(placement, observed.layout)
            require(previous == null || previous == observed.layout) {
                "A shared circuit has incompatible JAR geometry across its instances."
            }
        }
    }

    bind(project.mainCircuit, emptyList(), emptyList())
    require(instances.keys == layouts.keys) { "The trace contains JAR instances outside the approved native graph." }
    val complete = project.copy(circuits = project.circuits.map { document ->
        document.copy(components = document.components.map { component ->
            if (component.kind !in factoryNames) component
            else {
                val layout = requireNotNull(placementLayouts[document.id to component.id]) {
                    "A JAR component has no geometry in the approved trace."
                }
                component.withJarGeometry(layout)
            }
        })
    })
    check(isCurrent()) { "The approved JAR session ended." }
    val driverBinding = trace.drives != null
    val behavior = if (driverBinding) JarDriverBehavior(sessionIdentity, isCurrent) else JarGeometryOnly
    return JarEditorGeometry(complete, baseRegistry.withAdditional(definitions.map {
        it.copy(matchesFileAttributes = { false }, behavior = behavior)
    }), instances, xml, sessionIdentity, driverBinding, isCurrent)
}

private fun CircuitComponent.withJarGeometry(layout: ComponentLayout): CircuitComponent {
    fun GridPoint.relative() = GridPoint(
        (x.toLong() - position.x).jarCoordinate(),
        (y.toLong() - position.y).jarCoordinate(),
    )
    val bounds = layout.bounds
    val upper = GridPoint(bounds.left, bounds.top).relative()
    val lower = GridPoint(bounds.right, bounds.bottom).relative()
    return copy(
        explicitPorts = layout.ports.map {
            ComponentPort(it.name, it.position.relative(), it.direction, it.width, it.label)
        },
        explicitBounds = ComponentBounds(upper.x, upper.y, lower.x, lower.y),
    )
}

private fun jarComponentLayout(component: CircuitComponent, @Suppress("UNUSED_PARAMETER") attributes: AttributeValues): ComponentLayout {
    fun GridPoint.absolute() = GridPoint(
        (x.toLong() + component.position.x).jarCoordinate(),
        (y.toLong() + component.position.y).jarCoordinate(),
    )
    val bounds = requireNotNull(component.explicitBounds) { "JAR geometry has not been approved for this component." }
    val upper = GridPoint(bounds.left, bounds.top).absolute()
    val lower = GridPoint(bounds.right, bounds.bottom).absolute()
    return ComponentLayout(component.explicitPorts.map {
        ComponentTerminal(it.name, it.position.absolute(), it.direction, it.width, it.label)
    }, ComponentBounds(upper.x, upper.y, lower.x, lower.y))
}

private fun Long.jarCoordinate(): Int {
    require(this in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong())
    return toInt()
}

/** Resolved network values in the worker trace cannot substitute for actual driver outputs. */
private object JarGeometryOnly : ComponentBehavior {
    override fun evaluate(context: ComponentEvaluationContext): ComponentEvaluation =
        throw IllegalStateException("This JAR geometry has no verified live driver binding.")
}
