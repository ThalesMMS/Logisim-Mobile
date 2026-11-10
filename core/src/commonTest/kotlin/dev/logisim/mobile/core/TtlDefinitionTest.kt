package dev.logisim.mobile.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TtlDefinitionTest {
    @Test fun everyModelHasAUniqueRegisteredDefinitionAndExactPins() {
        val registry=ComponentRegistry(TtlComponents.definitions)
        assertEquals(85,registry.definitions.size)
        for(spec in TtlCatalog.specs) {
            val component=registry.create(ComponentKind("TTL_${spec.model}"),"chip",GridPoint(100,200))
            val layout=registry.layout(component)
            assertEquals(spec.signalPins.map { "pin$it" },layout.ports.map{it.name},spec.model)
            assertEquals(spec.pinCount*10,layout.bounds.width,spec.model)
        }
    }
    @Test fun powerPinsKeepPhysicalLocationAndAppendToSignalOrder() {
        val pins=TtlGeometry.pins("7493",GridPoint(100,200),powerPins=true)
        assertEquals(listOf(10,5),pins.takeLast(2).map{it.number})
        assertEquals(GridPoint(190,170),pins.single{it.number==10}.position)
        assertEquals(GridPoint(190,230),pins.single{it.number==5}.position)
        val north=TtlGeometry.pins("7486",GridPoint(100,200),"north",true)
        assertEquals(GridPoint(130,190),north.single{it.number==1}.position)
    }
    @Test fun resetAndLoadOperateThroughNamedRegistryPorts() {
        val registry=ComponentRegistry(TtlComponents.definitions)
        val component=registry.create(ComponentKind("TTL_74175"),"register",GridPoint(0,0))
        val definition=registry.requireDefinition(component.kind)
        val attributes=registry.attributes(component)
        val behavior=definition.behavior!!
        var state=behavior.initialState(component,attributes)
        fun step(clock:Boolean):ComponentEvaluation {
            val inputs=mapOf("pin1" to LogicVector.high(1),"pin9" to if(clock)LogicVector.high(1)else LogicVector.low(1),
                "pin4" to LogicVector.high(1),"pin5" to LogicVector.low(1),"pin12" to LogicVector.high(1),"pin13" to LogicVector.low(1))
            return behavior.evaluate(ComponentEvaluationContext(component,attributes,inputs,inputs.keys,state)).also {state=it.state}
        }
        step(false)
        val result=step(true)
        assertEquals(LogicValue.HIGH,result.outputs.getValue("pin2").bit(0))
        assertEquals(LogicValue.LOW,result.outputs.getValue("pin7").bit(0))
        assertEquals(LogicValue.HIGH,result.outputs.getValue("pin10").bit(0))
        assertEquals(LogicValue.LOW,result.outputs.getValue("pin15").bit(0))
        assertTrue(TtlComponents.definitions.all { it.behavior!=null })
    }
}
