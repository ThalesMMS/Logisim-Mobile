package dev.logisim.mobile.core

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals

class TtlFixtureTest {
    @Test fun everyDesktopModelMatchesItsPinFixtures() {
        val directory=listOf(File("src/jvmTest/resources/ttl-fixtures"),File("core/src/jvmTest/resources/ttl-fixtures")).first{it.isDirectory}
        val fixtures=directory.listFiles()!!.filter{it.extension=="tsv"}.sortedBy{it.name}
        assertEquals(85,fixtures.size)
        for(file in fixtures) {
            val lines=file.readLines()
            val sequential=lines.first()=="# sequential"
            var state=TtlState()
            for((row,line)in lines.drop(1).withIndex()) {
                val columns=line.split('\t')
                val model=columns[0]
                val pins=TtlCatalog.spec(model).signalPins.mapIndexed{index,pin->pin to decode(columns[1][index])}.toMap()
                val result=TtlLogic.evaluate(model,pins,if(sequential)state else TtlState(),elapsedTimeMillis=row.toLong())
                state=result.state
                val delays=columns[3].split(',').map(String::toLong)
                for((index,expected)in columns[2].split(',').withIndex()) {
                    val pin=expected.substringBefore('=').toInt()
                    assertEquals(decode(expected.last()),result.outputs[pin],"${file.name}:$row pin$pin")
                    assertEquals(delays[index],result.outputDelays[pin]?:result.delay,"${file.name}:$row pin$pin delay")
                }
            }
        }
    }
    private fun decode(value:Char):LogicValue=when(value){'0'->LogicValue.LOW;'1'->LogicValue.HIGH;'E'->LogicValue.ERROR;else->LogicValue.UNKNOWN}
}
