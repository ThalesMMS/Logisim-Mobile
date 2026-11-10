import com.cburch.logisim.Main;
import com.cburch.logisim.circuit.CircuitState;
import com.cburch.logisim.circuit.RadixOption;
import com.cburch.logisim.data.Value;
import com.cburch.logisim.file.Loader;
import com.cburch.logisim.instance.Instance;
import com.cburch.logisim.instance.InstanceState;
import com.cburch.logisim.instance.StdAttr;
import com.cburch.logisim.proj.Project;
import com.cburch.logisim.std.wiring.Pin;
import java.io.File;
import java.lang.reflect.Method;

/**
 * Pokes input pins through Evolution's own Pin.PinPoker (the PokeTool's per-component handler) and
 * prints the pin value after each poke. Usage: PokeDesktopOracle poke-pins.circ
 * A poke is PinPoker.handleBitPress(state, bit, radix, null, '\0'): the mouse release on the bit (or
 * hex/octal digit) under the pointer. Bit -1 is what getBit returns for decimal radix.
 */
public final class PokeDesktopOracle {
    private static final String[][] PLAN = {
        {"p1", "0", "0", "0", "0"},
        {"t1", "0", "0", "0", "0"},
        {"b4", "0", "0", "2", "2", "3"},
        {"h8", "0", "0", "4", "0"},
        {"t3", "0", "0", "0", "1"},
    };

    public static void main(String[] args) throws Exception {
        Main.headless = true;
        var file = new Loader(null).openLogisimFile(new File(args[0]));
        var project = new Project(file);
        Method press = Pin.PinPoker.class.getDeclaredMethod("handleBitPress", InstanceState.class, int.class,
            RadixOption.class, java.awt.Component.class, char.class);
        press.setAccessible(true);
        try {
            var circuit = file.getMainCircuit();
            var state = CircuitState.createRootState(project, circuit, Thread.currentThread());
            state.getPropagator().propagate();
            for (var plan : PLAN) {
                Instance pin = null;
                for (var component : circuit.getNonWires()) {
                    if (component instanceof com.cburch.logisim.instance.InstanceComponent ic
                        && plan[0].equals(ic.getInstance().getAttributeValue(StdAttr.LABEL))) pin = ic.getInstance();
                }
                var instanceState = state.getInstanceState(pin);
                var radix = instanceState.getAttributeValue(RadixOption.ATTRIBUTE);
                var line = new StringBuilder(plan[0]).append("\tinitial=").append(text(Pin.FACTORY.getValue(instanceState)));
                for (var index = 1; index < plan.length; index++) {
                    var poker = new Pin.PinPoker();
                    press.invoke(poker, instanceState, Integer.parseInt(plan[index]), radix, null, (char) 0);
                    state.getPropagator().propagate();
                    line.append("\tpoke(bit ").append(plan[index]).append(")=").append(text(Pin.FACTORY.getValue(instanceState)));
                }
                System.out.println(line);
            }
        } finally {
            project.getSimulator().shutDown();
        }
        System.exit(0);
    }

    private static String text(Value value) {
        var output = new StringBuilder();
        for (var index = value.getWidth() - 1; index >= 0; index--) {
            var bit = value.get(index);
            output.append(bit.equals(Value.TRUE) ? '1' : bit.equals(Value.FALSE) ? '0' : bit.equals(Value.ERROR) ? 'E' : 'U');
        }
        return output.toString();
    }
}
