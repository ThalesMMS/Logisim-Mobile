
import java.nio.file.*;
import java.util.*;
import dev.logisim.mobile.*;
import dev.logisim.mobile.core.*;
public class BoundsReplay {
 static String bounds(AccessibilityScreenBounds b) {
  return String.format(Locale.ROOT,"[%.9f,%.9f,%.9f,%.9f]",b.getLeft(),b.getTop(),b.getRight(),b.getBottom());
 }
 public static void main(String[] args)throws Exception {
  CircProjectImportResult parsed=CircCodec.INSTANCE.readProject(Files.readString(Path.of(args[0])));
  if(!(parsed instanceof CircProjectImportResult.Success)) throw new AssertionError(parsed);
  CircuitProject p=((CircProjectImportResult.Success)parsed).getProject();
  float[][] states={{1.1855534f,402.47583f,586.02747f},{0.94844276f,429.98065f,616.022f},{1.896886f,258.4614f,521.0438f}};
  for(int s=0;s<states.length;s++) {
   float[] v=states[s];
   CircuitAccessibilityViewport vp=new CircuitAccessibilityViewport(v[0],v[1],v[2],1080,1472);
   System.out.printf(Locale.ROOT,"STATE %d scale=%.9f panX=%.9f panY=%.9f min=%.9f%n",s,v[0],v[1],v[2],48f*2.625f);
   for(CircuitComponent c:p.getMainCircuit().getComponents()) {
    ComponentBounds world=ComponentGeometryKt.bounds(c);
    AccessibilityScreenBounds raw=CircuitSemanticOverlayKt.accessibilityScreenBounds(world,vp,126f);
    AccessibilityScreenBounds target=CircuitSemanticOverlayKt.accessibilityTargetBounds(world,vp,126f);
    int left=Math.round(target.getLeft()),top=Math.round(target.getTop());
    int width=Math.round(target.getWidth()),height=Math.round(target.getHeight());
    System.out.printf(Locale.ROOT,"%s world=[%d,%d,%d,%d] raw=%s target=%s placed=[%d,%d,%d,%d] screen=[%d,%d,%d,%d]%n",c.getId(),world.getLeft(),world.getTop(),world.getRight(),world.getBottom(),bounds(raw),bounds(target),left,top,left+width,top+height,left,top+647,left+width,top+height+647);
   }
  }
 }
}
