import dev.logisim.mobile.core.*;
import java.nio.file.*;
import java.util.*;
public class Layout12FixtureGateFixed {
 public static void main(String[] args) throws Exception {
  String xml=Files.readString(Path.of(args[0]));
  var result=CircCodec.INSTANCE.readProject(xml);
  if (!(result instanceof CircProjectImportResult.Success ok)) throw new AssertionError(result);
  var project=ok.getProject(); if(project.getCircuits().size()!=1) throw new AssertionError("circuit count");
  var doc=project.getCircuits().get(0); if(doc.getComponents().size()!=3) throw new AssertionError("component count");
  int images=0; boolean reset=false;
  for(var c:doc.getComponents()) {
   if(c.getKind().getName().equals("IMAGE")) {
    var image=EmbeddedImage.Companion.fromDataUri(c.getAttributes().get("image"));
    if(image.getWidth()!=1||image.getHeight()!=1||!image.getMimeType().equals("image/png")) throw new AssertionError("image payload");
    var b=AnnotationLibrary.INSTANCE.bounds(c);
    if(images==0 && !b.equals(new ComponentBounds(100,100,132,124))) throw new AssertionError(b);
    if(images==1 && !b.equals(new ComponentBounds(260,180,300,212))) throw new AssertionError(b);
    if(!"CC0-1.0".equals(c.getAttributes().get("license"))) throw new AssertionError("license");
    images++;
   } else {reset=c.getKind().getName().equals("INPUT")&&c.getLabel().equals("Reset");}
  }
  if(images!=2||!reset) throw new AssertionError("kinds");
  String written=CircCodec.INSTANCE.writeProject(project);
  var second=CircCodec.INSTANCE.readProject(written);
  if(!(second instanceof CircProjectImportResult.Success next)) throw new AssertionError(second);
  var after=next.getProject().getCircuits().get(0);
  if(!after.getWires().equals(doc.getWires())) throw new AssertionError("roundtrip wires");
  for(int i=0;i<3;i++) {
   var before=doc.getComponents().get(i);var actual=after.getComponents().get(i);
   for(var method:CircuitComponent.class.getMethods()) {
    if(method.getName().startsWith("get")&&method.getParameterCount()==0&&!method.getName().equals("getClass")&&!method.getName().equals("getXmlAttributes"))
     if(!Objects.deepEquals(method.invoke(before),method.invoke(actual))) throw new AssertionError(method.getName());
   }
   var expectedXml=new LinkedHashMap<>(before.getXmlAttributes());
   expectedXml.put(CircCodec.COMPONENT_ID_ATTRIBUTE,before.getId());
   if(!actual.getXmlAttributes().equals(expectedXml)) throw new AssertionError("unexpected XML attribute changes");
  }
  var pin=doc.getComponents().get(2);
  if(!"1".equals(pin.getAttributes().get("width"))||!"0x0".equals(pin.getAttributes().get("initial"))) throw new AssertionError("Reset defaults");

  System.out.println("PASS: one circuit, two embedded 1x1 PNGs with exact bounds and license, Reset INPUT, component/wire roundtrip");
 }
}
