package dev.logisim.mobile

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.sp
import dev.logisim.mobile.core.CircuitComponent
import dev.logisim.mobile.core.TtlCatalog

/** Draws the physical TTL package; logical ports come from the same catalog used by simulation. */
internal fun DrawScope.drawTtlPackage(component: CircuitComponent, measurer: TextMeasurer, outline: Color = Color.Black) {
    val model=component.kind.name.removePrefix("TTL_")
    val spec=TtlCatalog.spec(model)
    val width=spec.pinCount*10f
    val height=spec.height.toFloat()
    val internal=component.attributes["ShowInternalStructure"]=="true"
    val angle=when(component.facing){"west"->180f;"north"->270f;"south"->90f;else->0f}
    val ink=if(internal)outline else Color.White
    val fill=if(internal)Color.White else Color(0xFF353535)
    withTransform({translate(component.position.x.toFloat(),component.position.y.toFloat());rotate(angle,Offset.Zero)}) {
        drawRoundRect(fill,Offset(0f,-23f),Size(width,height-14f),cornerRadius=androidx.compose.ui.geometry.CornerRadius(4f,4f))
        drawRoundRect(outline,Offset(0f,-23f),Size(width,height-14f),cornerRadius=androidx.compose.ui.geometry.CornerRadius(4f,4f),style=Stroke(1.5f))
        drawCircle(if(internal)Color.White else outline,4f,Offset(2f,height/2-30f))
        drawCircle(outline,4f,Offset(2f,height/2-30f),style=Stroke(1f))
        val title=measurer.measure(AnnotatedString(model),TextStyle(color=ink,fontSize=13.sp,fontFamily=FontFamily.Monospace))
        drawCircuitText(title,topLeft=Offset((width-title.size.width)/2,(height-60-title.size.height)/2))
        for(pin in 1..spec.pinCount) {
            val low=pin<=spec.pinCount/2
            val x=if(low)(pin-1)*20f+10f else (spec.pinCount-pin)*20f+10f
            val y=if(low)height-30f else -30f
            val visible=pin !in spec.unusedPins && ((pin!=spec.gndPin&&pin!=spec.vccPin)||component.attributes["VccGndPorts"]=="true")
            val inner=if(low)y-7 else y+7
            drawLine(if(visible)outline else Color.Gray,Offset(x,y),Offset(x,inner),2f)
            val text=if(internal)spec.pinNames[pin]?.substringBefore(' ')?:pin.toString() else pin.toString()
            val label=measurer.measure(AnnotatedString(text),TextStyle(color=ink,fontSize=if(internal)6.sp else 5.sp,fontFamily=FontFamily.Monospace))
            drawCircuitText(label,topLeft=Offset(x-label.size.width/2,if(low)inner-label.size.height-1 else inner+1))
        }
    }
}
