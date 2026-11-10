import com.cburch.logisim.data.Value;
import dev.logisim.mobile.core.LogicValue;
import dev.logisim.mobile.core.LogicVector;
import java.util.Arrays;
import java.util.Random;

/** Runs the same inputs through the desktop JAR and the compiled mobile core. */
class LogicVectorOracle {
    private static final int[] WIDTHS = {1, 8, 16, 32, 64};
    private static final Random RANDOM = new Random(64832803L);
    private static int comparisons;

    private static Value desktop(LogicValue[] bits) {
        Value[] values = new Value[bits.length];
        for (int i = 0; i < bits.length; i++) {
            values[i] = switch (bits[i]) {
                case LOW -> Value.FALSE;
                case HIGH -> Value.TRUE;
                case UNKNOWN -> Value.UNKNOWN;
                case ERROR -> Value.ERROR;
            };
        }
        return Value.create(values);
    }

    private static LogicValue[] bits(int width) {
        LogicValue[] bits = new LogicValue[width];
        for (int i = 0; i < width; i++) {
            bits[i] = LogicValue.values()[RANDOM.nextInt(4)];
        }
        return bits;
    }

    private static LogicVector mobile(LogicValue[] bits) {
        return LogicVector.Companion.fromBits(Arrays.asList(bits));
    }

    private static void check(String operation, Value expected, LogicVector actual) {
        comparisons++;
        if (expected.getWidth() != actual.getWidth()) {
            throw new AssertionError(operation + ": widths differ");
        }
        for (int i = 0; i < actual.getWidth(); i++) {
            Value bit = expected.get(i);
            boolean equal = switch (actual.bit(i)) {
                case LOW -> bit == Value.FALSE;
                case HIGH -> bit == Value.TRUE;
                case UNKNOWN -> bit == Value.UNKNOWN;
                case ERROR -> bit == Value.ERROR;
            };
            if (!equal) {
                throw new AssertionError(operation + " at bit " + i
                    + ": desktop=" + expected.toBinaryString()
                    + ", mobile=" + actual.getDisplay());
            }
        }
    }

    public static void main(String[] args) {
        for (int width : WIDTHS) {
            for (int sample = 0; sample < 5000; sample++) {
                LogicValue[] first = bits(width);
                LogicValue[] second = bits(width);
                Value a = desktop(first);
                Value b = desktop(second);
                LogicVector x = mobile(first);
                LogicVector y = mobile(second);
                check("identity", a, x);
                check("and", a.and(b), x.and(y));
                check("or", a.or(b), x.or(y));
                check("xor", a.xor(b), x.xor(y));
                check("not", a.not(), x.inverted());
                check("combine", a.combine(b), x.combine(y));
                check("controls", a.controls(b), x.controls(y));
                int resized = 1 + RANDOM.nextInt(64);
                LogicValue fill = LogicValue.values()[RANDOM.nextInt(4)];
                check("resize", a.extendWidth(resized, desktop(new LogicValue[] {fill})),
                    x.resize(resized, fill));
            }
        }

        for (int width : WIDTHS) {
            for (int otherWidth : WIDTHS) {
                if (width == otherWidth) continue;
                for (int sample = 0; sample < 1000; sample++) {
                    LogicValue[] first = bits(width);
                    LogicValue[] second = bits(otherWidth);
                    Value a = desktop(first);
                    Value b = desktop(second);
                    LogicVector x = mobile(first);
                    LogicVector y = mobile(second);
                    check("mixed-width and", a.and(b), x.and(y));
                    check("mixed-width or", a.or(b), x.or(y));
                    check("mixed-width xor", a.xor(b), x.xor(y));
                }
            }
        }
        System.out.println("PASS " + comparisons + " desktop/mobile value comparisons, seed=64832803");
    }
}
