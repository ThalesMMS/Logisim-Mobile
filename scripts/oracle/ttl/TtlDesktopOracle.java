import com.cburch.logisim.data.Attribute;
import com.cburch.logisim.data.AttributeSet;
import com.cburch.logisim.data.Value;
import com.cburch.logisim.instance.InstanceData;
import com.cburch.logisim.instance.InstanceFactory;
import com.cburch.logisim.instance.InstanceState;
import java.lang.reflect.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;

/** Emits model metadata and deterministic pin-level fixtures from the desktop implementation. */
public final class TtlDesktopOracle {
  static Object field(Object target, String name) throws Exception {
    for (Class<?> type=target.getClass(); type!=null; type=type.getSuperclass()) {
      try { Field f=type.getDeclaredField(name); f.setAccessible(true); return f.get(target); }
      catch (NoSuchFieldException ignored) { }
    }
    throw new NoSuchFieldException(name);
  }
  static List<Integer> ints(Object value) {
    List<Integer> result = new ArrayList<>();
    for (Object n : (Set<?>) value) result.add(((Number)n).intValue());
    Collections.sort(result); return result;
  }
  static String join(List<Integer> list) { return String.join(",", list.stream().map(Object::toString).toList()); }
  static char digit(Value value) { return value==Value.TRUE?'1':value==Value.FALSE?'0':value==Value.ERROR?'E':'x'; }
  static Value val(char value) { return value=='1'?Value.TRUE:value=='0'?Value.FALSE:value=='E'?Value.ERROR:Value.UNKNOWN; }
  static final class Pins implements InvocationHandler {
    final InstanceFactory factory;
    final AttributeSet attributes;
    final Value[] inputs;
    final Map<Integer,Value> outputs = new TreeMap<>();
    final Map<Integer,Integer> delays = new TreeMap<>();
    InstanceData data;
    int tick;
    Pins(InstanceFactory f, int count) { factory=f; attributes=f.createAttributeSet(); inputs=new Value[count]; Arrays.fill(inputs,Value.FALSE); }
    public Object invoke(Object proxy,Method method,Object[] args) {
      return switch(method.getName()) {
        case "getPortValue" -> inputs[(Integer)args[0]];
        case "setPort" -> { outputs.put((Integer)args[0],(Value)args[1]); delays.put((Integer)args[0],(Integer)args[2]); yield null; }
        case "getAttributeValue" -> attributes.getValue((Attribute<?>)args[0]);
        case "getAttributeSet" -> attributes;
        case "getFactory" -> factory;
        case "getData" -> data;
        case "setData" -> { data=(InstanceData)args[0]; yield null; }
        case "getTickCount" -> tick;
        case "isPortConnected", "isCircuitRoot" -> true;
        case "toString" -> "TtlOracleState";
        default -> null;
      };
    }
    InstanceState state() { return (InstanceState)Proxy.newProxyInstance(InstanceState.class.getClassLoader(),new Class[]{InstanceState.class},this); }
  }
  public static void main(String[] args) throws Exception {
    String mode=args[0];
    List<String> models=Files.readAllLines(Path.of(args[1]));
    Random random=new Random(45007483L);
    for(String model:models) {
      InstanceFactory f=(InstanceFactory)Class.forName("com.cburch.logisim.std.ttl.Ttl"+model).getConstructor().newInstance();
      int count=((Number)field(f,"pinNumber")).intValue();
      int gnd=((Number)field(f,"gndPin")).intValue(), vcc=((Number)field(f,"vccPin")).intValue();
      AtomicLong clock=new AtomicLong();
      if(model.equals("74123")) { Method setter=f.getClass().getDeclaredMethod("setClock",LongSupplier.class);setter.setAccessible(true);setter.invoke(null,(LongSupplier)clock::get); }
      List<Integer> out=ints(field(f,"outputPorts")), io=ints(field(f,"inoutPorts")), unused=ints(field(f,"unusedPins"));
      List<Integer> pins=new ArrayList<>(), inputs=new ArrayList<>();
      for(int i=1;i<=count;i++) if(i!=gnd&&i!=vcc&&!unused.contains(i)) { pins.add(i); if(!out.contains(i)) inputs.add(i); }
      if(mode.equals("metadata")) {
        Object names=field(f,"portNames");
        System.out.println(model+"\t"+count+"\t"+gnd+"\t"+vcc+"\t"+field(f,"height")+"\t"+join(out)+"\t"+join(io)+"\t"+join(unused)+"\t"+(names==null?"":String.join("|",(String[])names)));
        continue;
      }
      int binary=Math.min(1<<Math.min(inputs.size(),12),4096);
      boolean sequence=mode.equals("sequence");
      if(sequence) binary=0;
      int samples=sequence?512:binary+512;
      Pins state=new Pins(f,pins.size()+2);
      for(int sample=0; sample<samples;sample++) {
        if(!sequence) state=new Pins(f,pins.size()+2);
        state.tick=sample;clock.set(sample*1000000L);
        StringBuilder stimulus=new StringBuilder();
        for(int pin:pins) {
          int index=pins.indexOf(pin);
          char d=sequence&&out.contains(pin)?digit(state.outputs.getOrDefault(index,Value.UNKNOWN)):'0';
          if(inputs.contains(pin)) d=sample<binary?(((sample>>inputs.indexOf(pin))&1)==0?'0':'1'):(sample<binary+256?"01":"01xE").charAt(random.nextInt(sample<binary+256?2:4));
          stimulus.append(d); state.inputs[index]=val(d);
        }
        state.inputs[pins.size()]=Value.FALSE; state.inputs[pins.size()+1]=Value.TRUE;
        f.propagate(state.state());
        StringBuilder result=new StringBuilder(), delay=new StringBuilder();
        for(int pin:pins) if(out.contains(pin)||io.contains(pin)) {
          int index=pins.indexOf(pin);
          if(result.length()>0) {result.append(',');delay.append(',');}
          result.append(pin).append('=').append(digit(state.outputs.getOrDefault(index,Value.UNKNOWN)));
          delay.append(state.delays.getOrDefault(index,0));
        }
        System.out.println(model+"\t"+stimulus+"\t"+result+"\t"+delay);
      }
    }
  }
}
