import dev.logisim.mobile.core.*;
import java.nio.file.*;
import java.util.*;

/** Verifies compiled mobile TTL behavior against pin-level desktop observations. */
public final class TtlMobileCompare {
  static char digit(LogicValue v) { return v==LogicValue.HIGH?'1':v==LogicValue.LOW?'0':v==LogicValue.ERROR?'E':'x'; }
  static LogicValue value(char c) {return c=='1'?LogicValue.HIGH:c=='0'?LogicValue.LOW:c=='E'?LogicValue.ERROR:LogicValue.UNKNOWN;}
  public static void main(String[] args) throws Exception {
    boolean sequence=args.length>1 && args[1].equals("sequence");
    Map<String,Integer> perModel=new LinkedHashMap<>();
    String current=""; TtlState state=new TtlState(Map.of(),Map.of()); int row=0, comparisons=0, failures=0;
    for(String line:Files.readAllLines(Path.of(args[0]))) {
      String[] c=line.split("\\t",-1);
      String model=c[0];
      if(!model.equals(current)) {current=model;state=new TtlState(Map.of(),Map.of());row=0;}
      if(!sequence) state=new TtlState(Map.of(),Map.of());
      TtlChipSpec spec=TtlCatalog.INSTANCE.spec(model);
      Map<Integer,LogicValue> pins=new LinkedHashMap<>();
      for(int i=0;i<spec.getSignalPins().size();i++)pins.put(spec.getSignalPins().get(i),value(c[1].charAt(i)));
      TtlResult result=TtlLogic.INSTANCE.evaluate(model,pins,state,Map.of(),row);
      state=result.getState();
      String[] delays=c[3].split(",");int index=0;
      for(String pair:c[2].split(",")) {
        int pin=Integer.parseInt(pair.split("=")[0]);char expected=pair.charAt(pair.length()-1);
        char actual=digit(result.getOutputs().getOrDefault(pin,LogicValue.UNKNOWN));
        long delay=result.getOutputDelays().getOrDefault(pin,result.getDelay());
        if(actual!=expected || delay!=Long.parseLong(delays[index])) {
          if(perModel.getOrDefault(model,0)<3)System.err.println(model+" row="+row+" pins="+c[1]+" pin="+pin+" expected="+expected+"/"+delays[index]+" actual="+actual+"/"+delay);
          failures++;perModel.put(model,perModel.getOrDefault(model,0)+1);
        }
        index++;comparisons++;
      }
      row++;
    }
    System.out.println(perModel);
    System.out.println("TTL comparisons="+comparisons+" mismatches="+failures);
    if(failures>0)System.exit(1);
  }
}
