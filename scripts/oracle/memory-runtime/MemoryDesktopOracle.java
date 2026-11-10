import com.cburch.logisim.Main;
import com.cburch.logisim.circuit.Circuit;
import com.cburch.logisim.circuit.CircuitState;
import com.cburch.logisim.comp.Component;
import com.cburch.logisim.data.*;
import com.cburch.logisim.instance.*;
import com.cburch.logisim.prefs.AppPreferences;
import com.cburch.logisim.proj.Project;
import com.cburch.logisim.std.memory.*;
import java.nio.file.*;
import java.util.*;

public final class MemoryDesktopOracle {
  static final class State implements InstanceState {
    final InstanceFactory factory;
    final Component component;
    final AttributeSet attrs;
    final Map<Integer,Value> inputs = new HashMap<>(), outputs = new HashMap<>();
    InstanceData data;
    State(String kind, Map<String,String> raw) {
      factory = kind.equals("ROM") ? new Rom() : kind.equals("RAM") ? new Ram() : new DualRam();
      attrs = factory.createAttributeSet();
      for (var entry : raw.entrySet()) {
        for (var attr : new ArrayList<>(attrs.getAttributes())) {
          if (attr.getName().equals(entry.getKey())) { set(attr, entry.getValue()); break; }
        }
      }
      component = factory.createComponent(Location.create(100,200,true),attrs);
      for (int i=0;i<component.getEnds().size();i++) outputs.put(i,Value.createUnknown(component.getEnd(i).getWidth()));
    }
    @SuppressWarnings({"rawtypes","unchecked"}) void set(Attribute attr,String raw) { attrs.setValue(attr,attr.parse(raw)); }
    public void fireInvalidated() {}
    public AttributeSet getAttributeSet() { return attrs; }
    public <E>E getAttributeValue(Attribute<E> attr) { return attrs.getValue(attr); }
    public InstanceData getData() { return data; }
    public InstanceFactory getFactory() { return factory; }
    public Instance getInstance() { return Instance.getInstanceFor(component); }
    public int getPortIndex(Port port) { return getInstance().getPorts().indexOf(port); }
    public Value getPortValue(int index) { return inputs.getOrDefault(index, outputs.getOrDefault(index,Value.UNKNOWN)); }
    public Project getProject() { return null; }
    public int getTickCount() { return 0; }
    public boolean isCircuitRoot() { return true; }
    public boolean isPortConnected(int index) { return inputs.containsKey(index); }
    public CircuitState createCircuitSubstateFor(Circuit circuit) { throw new UnsupportedOperationException(); }
    public void setData(InstanceData value) { data=value; }
    public void setPort(int index,Value value,int delay) { outputs.put(index,value); }
    int index(String name) throws Exception {
      String[] parts=name.split("_");
      String first=parts[0];
      int start=0;while(start<first.length()&&!Character.isDigit(first.charAt(start)))start++;
      String prefix=first.substring(0,start);
      int port=start==first.length()?0:Integer.parseInt(first.substring(start));
      int line=parts.length>1?Integer.parseInt(parts[1]):0;
      boolean dual=factory instanceof DualRam;
      int lines=dual?DualRamAppearance.getNrDataOutPorts(attrs)/2:RamAppearance.getNrDataOutPorts(attrs);
      String method=switch(prefix) {
        case "a" -> "getAddrIndex"; case "d" -> "getDataInIndex";
        case "we" -> "getWEIndex"; case "oe" -> "getOEIndex"; case "clk" -> "getClkIndex";
        case "be" -> "getBEIndex"; case "le" -> "getLEIndex"; case "clear" -> "getClrIndex";
        default -> throw new IllegalArgumentException(name);
      };
      int ordinal=prefix.equals("d")||prefix.equals("le")?port*lines+line:prefix.equals("be")?
        port*(dual?DualRamAppearance.getNrBEPorts(attrs)/2:RamAppearance.getNrBEPorts(attrs))+line:port;
      return (int)(dual?DualRamAppearance.class:RamAppearance.class).getMethod(method,int.class,AttributeSet.class).invoke(null,ordinal,attrs);
    }
    void drive(String raw) throws Exception {
      inputs.clear();
      if(raw.isEmpty())return;
      for(var entry:raw.split(";")) {
        var pair=entry.split("=",2);int index=index(pair[0]);if(index<0)continue;
        var width=component.getEnd(index).getWidth();
        var value=pair[1].equals("U")?Value.createUnknown(width):pair[1].equals("E")?Value.createError(width):
          Value.createKnown(width,Long.parseUnsignedLong(pair[1],16));
        inputs.put(index,value);
      }
    }
    String geometry() {
      var b=component.getBounds();var text=new StringBuilder().append(b.getX()).append(',').append(b.getY()).append(',').append(b.getWidth()).append(',').append(b.getHeight());
      for(var port:component.getEnds()) text.append(';').append(port.getLocation().getX()).append(',').append(port.getLocation().getY()).append(',').append(port.getWidth().getWidth()).append(',').append(port.getType());
      return text.toString();
    }
    String values() {
      var text=new StringJoiner(";");
      for(int i=0;i<component.getEnds().size();i++) if((component.getEnd(i).getType()&2)!=0) text.add(bits(outputs.get(i)));
      return text.toString();
    }
  }
  static String bits(Value value) {
    var bits=value.getAll();var text=new StringBuilder();
    for(int i=bits.length-1;i>=0;i--) text.append(bits[i]==Value.TRUE?'1':bits[i]==Value.FALSE?'0':bits[i]==Value.ERROR?'E':'?');
    return text.toString();
  }
  public static void main(String[] args) throws Exception {
    Main.headless=true;AppPreferences.Memory_Startup_Unknown.set(false);
    State state=null;String id="";int step=0;
    for(var line:Files.readAllLines(Path.of(args[0]))) {
      var fields=line.split("\\t",-1);
      if(fields[0].equals("@")) {
        id=fields[1];var attrs=new LinkedHashMap<String,String>();
        for(var item:fields[3].split(";")) {var pair=item.split("=",2);attrs.put(pair[0],pair[1]);}
        state=new State(fields[2],attrs);step=0;System.out.println(id+"\tG\t"+state.geometry());
      } else {
        state.drive(fields[1]);state.factory.propagate(state);System.out.println(id+"\t"+(step++)+"\t"+state.values());
      }
    }
    System.exit(0);
  }
}
