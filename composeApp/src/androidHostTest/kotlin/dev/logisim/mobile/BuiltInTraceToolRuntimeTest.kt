package dev.logisim.mobile

import androidx.compose.runtime.AbstractApplier
import androidx.compose.runtime.BroadcastFrameClock
import androidx.compose.runtime.Composition
import androidx.compose.runtime.Recomposer
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import dev.logisim.mobile.core.ComponentCatalog
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import kotlin.test.Test
import kotlin.test.assertSame

class BuiltInTraceToolRuntimeTest {
    @Test
    fun registryUpdateRetainsTheTraceTool() = runBlocking {
        val frames = BroadcastFrameClock()
        val recomposer = Recomposer(coroutineContext + frames)
        val composition = Composition(object : AbstractApplier<Unit>(Unit) {
            override fun insertBottomUp(index: Int, instance: Unit) = Unit
            override fun insertTopDown(index: Int, instance: Unit) = Unit
            override fun move(from: Int, to: Int, count: Int) = Unit
            override fun remove(index: Int, count: Int) = Unit
            override fun onClear() = Unit
        }, recomposer)
        val recomposing = launch(frames) { recomposer.runRecomposeAndApplyChanges() }
        var registry by mutableStateOf(ComponentCatalog.registry)
        var appliedRegistry = registry
        lateinit var trace: EditorTool
        try {
            composition.setContent {
                val current = registry
                val tool = rememberBuiltInEditorTools(current).single { it.id == "trace" }
                SideEffect {
                    trace = tool
                    appliedRegistry = current
                }
            }
            val initialTrace = trace
            registry = registry.withAdditional(emptyList())
            withTimeout(5_000) {
                while (appliedRegistry !== registry) {
                    Snapshot.sendApplyNotifications()
                    frames.sendFrame(System.nanoTime())
                    yield()
                }
            }
            assertSame(initialTrace, trace)
        } finally {
            composition.dispose()
            recomposer.close()
            recomposing.cancelAndJoin()
        }
    }
}
