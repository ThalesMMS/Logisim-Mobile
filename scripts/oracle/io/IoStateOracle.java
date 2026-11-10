import com.cburch.logisim.data.Value;
import dev.logisim.mobile.core.IoInputEvent;
import dev.logisim.mobile.core.IoKeyboardState;
import dev.logisim.mobile.core.IoLogic;
import dev.logisim.mobile.core.IoMatrixState;
import dev.logisim.mobile.core.IoTerminalState;
import dev.logisim.mobile.core.KeyboardCommand;
import dev.logisim.mobile.core.LogicValue;
import dev.logisim.mobile.core.LogicVector;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/** Compares the ported state machines to the desktop implementation without a GUI. */
public class IoStateOracle {
    private static long comparisons;
    private static final Random random = new Random(440071L);

    private static Method method(Class<?> type, String name, Class<?>... parameters) throws Exception {
        Method result = type.getDeclaredMethod(name, parameters);
        result.setAccessible(true);
        return result;
    }

    private static Constructor<?> constructor(Class<?> type, Class<?>... parameters) throws Exception {
        Constructor<?> result = type.getDeclaredConstructor(parameters);
        result.setAccessible(true);
        return result;
    }

    private static void equal(Object expected, Object actual, String context) {
        comparisons++;
        if (!expected.equals(actual)) throw new AssertionError(context + ": expected " + expected + ", got " + actual);
    }

    private static void keyboards() throws Exception {
        Class<?> desktop = Class.forName("com.cburch.logisim.std.io.KeyboardData");
        var make = constructor(desktop, int.class);
        var insert = method(desktop, "insert", char.class);
        var delete = method(desktop, "delete");
        var move = method(desktop, "moveCursorBy", int.class);
        var set = method(desktop, "setCursor", int.class);
        var resize = method(desktop, "updateBufferLength", int.class);
        var get = method(desktop, "getChar", int.class);
        var cursor = method(desktop, "getCursorPosition");
        for (int sample = 0; sample < 100; sample++) {
            int capacity = 1 + random.nextInt(32);
            Object reference = make.newInstance(capacity);
            IoKeyboardState actual = new IoKeyboardState("", 0, LogicValue.UNKNOWN);
            for (int step = 0; step < 200; step++) {
                var events = new ArrayList<IoInputEvent>();
                switch (random.nextInt(8)) {
                    case 0, 1 -> {
                        char c = "Az 09é\b\n\f".charAt(random.nextInt(9));
                        insert.invoke(reference, c);
                        events.add(new IoInputEvent.InsertText(Character.toString(c)));
                    }
                    case 2 -> { delete.invoke(reference); events.add(new IoInputEvent.EditKeyboard(KeyboardCommand.DELETE)); }
                    case 3 -> { move.invoke(reference, -1); events.add(new IoInputEvent.EditKeyboard(KeyboardCommand.LEFT)); }
                    case 4 -> { move.invoke(reference, 1); events.add(new IoInputEvent.EditKeyboard(KeyboardCommand.RIGHT)); }
                    case 5 -> { set.invoke(reference, 0); events.add(new IoInputEvent.EditKeyboard(KeyboardCommand.HOME)); }
                    case 6 -> { capacity = 1 + random.nextInt(32); resize.invoke(reference, capacity); }
                    case 7 -> { set.invoke(reference, Integer.MAX_VALUE); events.add(new IoInputEvent.EditKeyboard(KeyboardCommand.END)); }
                }
                actual = IoLogic.INSTANCE.editKeyboard(actual, capacity, events);
                equal(cursor.invoke(reference), actual.getCursor(), "keyboard cursor " + sample + "/" + step);
                for (int index = 0; index <= capacity; index++) {
                    char mobile = index < actual.getBuffer().length() ? actual.getBuffer().charAt(index) : '\0';
                    equal(get.invoke(reference, index), mobile, "keyboard data " + sample + "/" + step + "/" + index);
                }
            }
        }
    }

    private static void terminals() throws Exception {
        Class<?> desktop = Class.forName("com.cburch.logisim.std.io.TtyState");
        var make = constructor(desktop, int.class, int.class);
        var add = method(desktop, "add", char.class);
        var resize = method(desktop, "updateSize", int.class, int.class);
        var rowString = method(desktop, "getRowString", int.class);
        var row = method(desktop, "getCursorRow");
        var column = method(desktop, "getCursorColumn");
        for (int sample = 0; sample < 100; sample++) {
            int rows = 2 + random.nextInt(8);
            int columns = 1 + random.nextInt(20);
            Object reference = make.newInstance(rows, columns);
            IoTerminalState actual = new IoTerminalState(rows, columns, List.of(), "", LogicValue.UNKNOWN);
            for (int step = 0; step < 200; step++) {
                if (random.nextInt(10) == 0) {
                    rows = 2 + random.nextInt(8);
                    columns = 1 + random.nextInt(20);
                    resize.invoke(reference, rows, columns);
                    actual = IoLogic.INSTANCE.resizeTerminal(actual, rows, columns);
                } else {
                    char c = "AB z09\b\n\r\f\0".charAt(random.nextInt(11));
                    add.invoke(reference, c);
                    actual = IoLogic.INSTANCE.addTerminal(actual, c);
                }
                equal(row.invoke(reference), actual.getCursorRow(), "TTY cursor row " + sample + "/" + step);
                equal(column.invoke(reference), actual.getCursorColumn(), "TTY cursor column " + sample + "/" + step);
                for (int index = 0; index < rows; index++) {
                    equal(rowString.invoke(reference, index), actual.getDisplayRows().get(index), "TTY row " + sample + "/" + step + "/" + index);
                }
            }
        }
    }

    private static LogicVector vector(int width) {
        var bits = new ArrayList<LogicValue>();
        for (int index = 0; index < width; index++) bits.add(LogicValue.values()[random.nextInt(4)]);
        return LogicVector.Companion.fromBits(bits);
    }

    private static Value desktopValue(LogicVector vector) {
        var bits = new Value[vector.getWidth()];
        for (int i = 0; i < bits.length; i++) bits[i] = switch (vector.bit(i)) {
            case LOW -> Value.FALSE;
            case HIGH -> Value.TRUE;
            case UNKNOWN -> Value.UNKNOWN;
            case ERROR -> Value.ERROR;
        };
        return Value.create(bits);
    }

    private static void matrices() throws Exception {
        Class<?> desktop = Class.forName("com.cburch.logisim.std.io.DotMatrixBase$State");
        var make = constructor(desktop, int.class, int.class, long.class);
        var setRow = method(desktop, "setRow", int.class, Value.class, long.class);
        var setColumn = method(desktop, "setColumn", int.class, Value.class, long.class);
        var select = method(desktop, "setSelect", Value.class, Value.class, long.class);
        var get = method(desktop, "get", int.class, int.class, long.class);
        for (int sample = 0; sample < 50; sample++) {
            int rows = 1 + random.nextInt(6);
            int columns = 1 + random.nextInt(6);
            long ticks = 0;
            Object reference = make.newInstance(rows, columns, ticks);
            IoMatrixState actual = new IoMatrixState(rows, columns,
                    new ArrayList<>(java.util.Collections.nCopies(rows * columns, LogicValue.UNKNOWN)),
                    new ArrayList<>(java.util.Collections.nCopies(rows * columns, -1L)));
            for (int step = 0; step < 100; step++) {
                ticks += random.nextInt(4);
                int persistence = random.nextInt(5);
                String mode = List.of("row", "column", "select").get(random.nextInt(3));
                List<LogicVector> inputs = new ArrayList<>();
                if (mode.equals("row")) {
                    for (int r = 0; r < rows; r++) {
                        var value = vector(columns); inputs.add(value);
                        setRow.invoke(reference, r, desktopValue(value), ticks + persistence);
                    }
                } else if (mode.equals("column")) {
                    for (int c = 0; c < columns; c++) {
                        var value = vector(rows); inputs.add(value);
                        setColumn.invoke(reference, c, desktopValue(value), ticks + persistence);
                    }
                } else {
                    inputs = List.of(vector(columns), vector(rows));
                    select.invoke(reference, desktopValue(inputs.get(1)), desktopValue(inputs.get(0)), ticks + persistence);
                }
                actual = IoLogic.INSTANCE.matrixUpdate(actual, mode, inputs, ticks, persistence);
                for (int delta = 0; delta <= 5; delta++) for (int r = 0; r < rows; r++) for (int c = 0; c < columns; c++) {
                    Value expected = (Value) get.invoke(reference, r, c, ticks + delta);
                    String expectedText = expected == Value.FALSE ? "LOW" : expected == Value.TRUE ? "HIGH" : expected == Value.ERROR ? "ERROR" : "UNKNOWN";
                    equal(expectedText, actual.visible(r, c, ticks + delta).name(), "matrix " + sample + "/" + step + "/" + r + "/" + c);
                }
            }
        }
    }

    public static void main(String[] args) throws Exception {
        keyboards();
        terminals();
        matrices();
        System.out.println("PASS: " + comparisons + " I/O state observations match the desktop, seed=440071");
    }
}
