import com.cburch.logisim.data.Attribute;
import com.cburch.logisim.data.AttributeSet;
import com.cburch.logisim.data.BitWidth;
import com.cburch.logisim.data.Value;
import com.cburch.logisim.instance.InstanceFactory;
import com.cburch.logisim.instance.InstanceState;
import com.cburch.logisim.std.io.extra.Buzzer;
import com.cburch.logisim.std.io.extra.DigitalOscilloscope;
import com.cburch.logisim.circuit.DesktopCircuit;
import com.cburch.logisim.soc.data.SocBusTransaction;
import com.cburch.logisim.soc.vga.VgaAttributes;
import com.cburch.logisim.soc.vga.VgaState;
import dev.logisim.mobile.core.BuzzerPcm;
import dev.logisim.mobile.core.CircCodec;
import dev.logisim.mobile.core.CircProjectImportResult;
import dev.logisim.mobile.core.CircuitComponent;
import dev.logisim.mobile.core.CircuitProject;
import dev.logisim.mobile.core.CircuitSimulator;
import dev.logisim.mobile.core.ComponentCatalog;
import dev.logisim.mobile.core.ComponentEvent;
import dev.logisim.mobile.core.ComponentKind;
import dev.logisim.mobile.core.GridPoint;
import dev.logisim.mobile.core.SimulationState;
import dev.logisim.mobile.core.SocAccessSize;
import dev.logisim.mobile.core.SocBusRequest;
import dev.logisim.mobile.core.SocDeviceComponentState;
import dev.logisim.mobile.core.SocTransactionEvent;
import dev.logisim.mobile.core.SocVgaDevice;
import dev.logisim.mobile.core.VideoState;
import java.awt.image.BufferedImage;
import java.io.File;
import java.lang.reflect.Constructor;
import java.nio.file.Files;
import java.util.LinkedHashMap;
import dev.logisim.mobile.core.BuzzerTone;
import dev.logisim.mobile.core.BuzzerWaveform;
import dev.logisim.mobile.core.ExtraOscilloscopeState;
import dev.logisim.mobile.core.LogicValue;
import dev.logisim.mobile.core.LogicVector;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Drives Evolution's output-device classes and the Mobile core with the same inputs.
 * Usage: see run.sh. Each section prints a PASS line with its comparison count.
 */
class OutputDevicesOracle {
    static final long SEED = 31_707_2026L;
    static int comparisons;

    // ---------------------------------------------------------------- shared helpers

    static final class Fixture implements InvocationHandler {
        final InstanceFactory factory;
        final AttributeSet attributes;
        final Map<Integer, Value> inputs = new HashMap<>();
        Object data;

        Fixture(InstanceFactory factory) {
            this.factory = factory;
            attributes = factory.createAttributeSet();
        }

        @SuppressWarnings({"rawtypes", "unchecked"})
        void attribute(String name, String value) {
            for (Attribute key : attributes.getAttributes()) {
                if (key.getName().equals(name)) {
                    attributes.setValue(key, key.parse(value));
                    return;
                }
            }
            throw new AssertionError("Missing desktop attribute " + name);
        }

        @SuppressWarnings({"rawtypes", "unchecked"})
        public Object invoke(Object proxy, java.lang.reflect.Method method, Object[] args) {
            return switch (method.getName()) {
                case "getAttributeValue" -> attributes.getValue((Attribute) args[0]);
                case "getAttributeSet" -> attributes;
                case "getPortValue" -> inputs.getOrDefault((Integer) args[0], Value.UNKNOWN);
                case "getData" -> data;
                case "setData" -> { data = args[0]; yield null; }
                case "getFactory" -> factory;
                case "isCircuitRoot", "isPortConnected" -> true;
                case "getTickCount" -> 0;
                case "toString" -> "OutputDevicesOracleFixture";
                case "hashCode" -> System.identityHashCode(this);
                case "equals" -> proxy == args[0];
                default -> null;
            };
        }

        void evaluate() {
            factory.propagate((InstanceState) Proxy.newProxyInstance(
                InstanceState.class.getClassLoader(), new Class<?>[] {InstanceState.class}, this));
        }
    }

    static LogicValue mobileBit(Value bit) {
        return bit == Value.TRUE ? LogicValue.HIGH : bit == Value.FALSE ? LogicValue.LOW
            : bit == Value.ERROR ? LogicValue.ERROR : LogicValue.UNKNOWN;
    }

    static LogicVector mobile(Value value) {
        List<LogicValue> bits = new ArrayList<>();
        for (Value bit : value.getAll()) bits.add(mobileBit(bit));
        return LogicVector.Companion.fromBits(bits);
    }

    static void check(boolean same, String message) {
        comparisons++;
        if (!same) throw new AssertionError(message);
    }

    static Value known(int width, long value) { return Value.createKnown(BitWidth.create(width), value); }

    /** Known values most of the time, otherwise fully or partly unknown, or error. */
    static Value vector(Random random, int width, long value) {
        int roll = random.nextInt(20);
        if (roll == 0) return Value.createUnknown(BitWidth.create(width));
        if (roll == 1) return Value.createError(BitWidth.create(width));
        Value result = known(width, value);
        if (roll == 2) {
            Value[] bits = result.getAll();
            bits[random.nextInt(width)] = random.nextBoolean() ? Value.UNKNOWN : Value.ERROR;
            result = Value.create(bits);
        }
        return result;
    }

    static Value bit(Random random, int high, int low, int unknown) {
        int roll = random.nextInt(high + low + unknown + 1);
        if (roll < high) return Value.TRUE;
        if (roll < high + low) return Value.FALSE;
        if (roll < high + low + unknown) return Value.UNKNOWN;
        return Value.ERROR;
    }

    static Field field(Class<?> type, String name) throws ReflectiveOperationException {
        Field field = type.getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }

    // ---------------------------------------------------------------- Buzzer

    static final String[] WAVEFORMS = {"Sine", "Square", "Triangle", "Sawtooth", "Noise"};
    static final int[] CHANNELS = {3, 1, 2};

    record BuzzerCase(boolean decihertz, String waveform, int channels, int smoothLevel, int smoothWidth,
                      int volumeWidth, Value frequency, Value enable, Value volume, Value duty) {}

    static final class BuzzerHarness {
        final Class<?> dataClass = Class.forName("com.cburch.logisim.std.io.extra.Buzzer$Data");
        final Field isOn = field(dataClass, "isOn"), hz = field(dataClass, "hz"), vol = field(dataClass, "vol"),
            wf = field(dataClass, "wf"), channels = field(dataClass, "channels"), pw = field(dataClass, "pw"),
            smoothLevel = field(dataClass, "smoothLevel"), smoothWidth = field(dataClass, "smoothWidth"),
            thread = field(dataClass, "thread");
        final java.lang.reflect.Constructor<?> constructor = dataClass.getDeclaredConstructor();
        final Thread parked = new Thread(() -> {
            try { Thread.sleep(Long.MAX_VALUE); } catch (InterruptedException ignored) { }
        }, "oracle-parked-sound-thread");

        BuzzerHarness() throws ReflectiveOperationException {
            constructor.setAccessible(true);
            parked.setDaemon(true);
            parked.start();
        }

        /** A fresh Data whose sound thread has ended, so the next active propagate starts a new one. */
        Object freshData() throws Exception {
            Object data = constructor.newInstance();
            ((Thread) thread.get(data)).join(5_000);
            return data;
        }

        Fixture fixture(BuzzerCase sample) {
            Fixture fixture = new Fixture(new Buzzer());
            fixture.attribute("freq_measure", sample.decihertz ? "dHz" : "Hz");
            fixture.attribute("waveform", sample.waveform);
            fixture.attribute("channel", Integer.toString(sample.channels));
            fixture.attribute("smooth_level", Integer.toString(sample.smoothLevel));
            fixture.attribute("smooth_width", Integer.toString(sample.smoothWidth));
            fixture.attribute("vol_width", Integer.toString(sample.volumeWidth));
            fixture.inputs.put(0, sample.frequency);
            fixture.inputs.put(1, sample.enable);
            fixture.inputs.put(2, sample.volume);
            fixture.inputs.put(3, sample.duty);
            return fixture;
        }

        BuzzerTone mobile(BuzzerCase sample) {
            return BuzzerTone.Companion.fromInputs(OutputDevicesOracle.mobile(sample.frequency), mobileBit(sample.enable),
                OutputDevicesOracle.mobile(sample.volume), OutputDevicesOracle.mobile(sample.duty), sample.decihertz,
                BuzzerWaveform.valueOf(sample.waveform), sample.channels, sample.smoothLevel, sample.smoothWidth);
        }

        void compareState(BuzzerCase sample, Object data, BuzzerTone tone) throws IllegalAccessException {
            String label = "Buzzer " + sample;
            check(((AtomicBoolean) isOn.get(data)).get() == tone.getEnabled(), label + " enabled");
            check(hz.getInt(data) == tone.getFrequencyHz(), label + " hz desktop=" + hz.getInt(data) + " mobile=" + tone.getFrequencyHz());
            check(Double.compare(vol.getDouble(data), tone.getAmplitude()) == 0,
                label + " volume desktop=" + vol.getDouble(data) + " mobile=" + tone.getAmplitude());
            check(((Enum<?>) wf.get(data)).name().equals(tone.getWaveform().name()), label + " waveform");
            check(channels.getInt(data) == tone.getChannels(), label + " channels");
            check(pw.getInt(data) == tone.getDuty(), label + " duty desktop=" + pw.getInt(data) + " mobile=" + tone.getDuty());
            check(smoothLevel.getInt(data) == tone.getSmoothLevel(), label + " smooth level");
            check(smoothWidth.getInt(data) == tone.getSmoothWidth(), label + " smooth width");
        }

        int smoothingFailures;

        /** Evolution copies values.length - 2 * smoothWidth samples; a negative length throws. */
        static boolean desktopSmoothingFails(BuzzerTone tone) {
            int hz = tone.getFrequencyHz();
            if (!tone.getAudible() || tone.getWaveform() == BuzzerWaveform.Sine || tone.getSmoothLevel() == 0) return false;
            int sampleRate = (int) Math.ceil(44100.0 / hz) * hz;
            return 4 * Math.max(1, sampleRate / hz) < 2 * tone.getSmoothWidth();
        }

        /** Propagates with the sound thread blocked, so only the state model is exercised. */
        void stateOnly(BuzzerCase sample) throws Exception {
            Fixture fixture = fixture(sample);
            Object data = freshData();
            thread.set(data, parked);
            fixture.data = data;
            fixture.evaluate();
            compareState(sample, data, mobile(sample));
        }

        /** Lets Evolution's own sound thread build its looping clip, and compares it with Mobile's PCM. */
        void withPcm(BuzzerCase sample) throws Exception {
            OracleMixerProvider.CAPTURES.clear();
            Fixture fixture = fixture(sample);
            Object data = freshData();
            fixture.data = data;
            fixture.evaluate();
            BuzzerTone tone = mobile(sample);
            compareState(sample, data, tone);
            Thread sound = (Thread) thread.get(data);
            OracleMixerProvider.Capture capture = null;
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
            while (capture == null && sound.isAlive() && System.nanoTime() < deadline) {
                capture = OracleMixerProvider.CAPTURES.poll(10, TimeUnit.MILLISECONDS);
            }
            if (capture == null) capture = OracleMixerProvider.CAPTURES.poll();
            ((AtomicBoolean) isOn.get(data)).set(false);
            sound.join(5_000);
            check(!sound.isAlive(), "Buzzer sound thread did not stop for " + sample);
            BuzzerPcm pcm = tone.pcm(() -> 0.0);
            short[] samples = pcm.getStereoSamples();
            String label = "Buzzer PCM " + sample;
            if (capture == null) {
                if (desktopSmoothingFails(tone)) {
                    // Evolution's thread throws in System.arraycopy and stays silent; Mobile plays the unsmoothed wave.
                    check(samples.length > 0, label + ": expected Mobile to play when the desktop smoothing fails");
                    smoothingFailures++;
                    return;
                }
                check(samples.length == 0, label + ": desktop built no clip, Mobile built " + samples.length + " samples");
                return;
            }
            check(!desktopSmoothingFails(tone), label + ": desktop played although its smoothing should fail");
            check((int) capture.format().getSampleRate() == pcm.getSampleRate(),
                label + " rate desktop=" + capture.format().getSampleRate() + " mobile=" + pcm.getSampleRate());
            byte[] bytes = capture.bytes();
            if (samples.length == 0) {
                // Mobile leaves a zero-volume tone silent instead of looping a buffer of zeros.
                boolean silent = true;
                for (byte value : bytes) silent &= value == 0;
                check(silent && tone.getAmplitude() == 0.0, label + ": desktop clip is not silent");
                return;
            }
            check(bytes.length == samples.length * 2, label + " length desktop=" + bytes.length + " mobile=" + samples.length * 2);
            for (int index = 0; index < samples.length; index++) {
                short desktop = (short) ((bytes[2 * index] & 0xff) | (bytes[2 * index + 1] << 8));
                comparisons++;
                if (desktop != samples[index]) {
                    throw new AssertionError(label + " sample " + index + " desktop=" + desktop + " mobile=" + samples[index]);
                }
            }
        }
    }

    static BuzzerCase randomBuzzer(Random random, boolean pcmFriendly) {
        int volumeWidth = 1 + random.nextInt(32);
        long volumeMax = volumeWidth == 64 ? -1 : (1L << volumeWidth) - 1;
        long frequency = switch (random.nextInt(4)) {
            case 0 -> random.nextInt(20);
            case 1 -> 20 + random.nextInt(200);
            case 2 -> 200 + random.nextInt(2_000);
            default -> random.nextInt(1 << 14);
        };
        String waveform = WAVEFORMS[random.nextInt(pcmFriendly ? 4 : WAVEFORMS.length)];
        Value frequencyValue = pcmFriendly ? known(14, frequency) : vector(random, 14, frequency);
        Value enable = pcmFriendly ? Value.TRUE : bit(random, 6, 3, 1);
        Value volume = pcmFriendly && random.nextInt(8) != 0
            ? known(volumeWidth, 1 + (long) (random.nextDouble() * volumeMax))
            : vector(random, volumeWidth, (long) (random.nextDouble() * (volumeMax + 1.0)));
        return new BuzzerCase(random.nextInt(4) == 0, waveform, CHANNELS[random.nextInt(3)], random.nextInt(11),
            1 + random.nextInt(10), volumeWidth, frequencyValue, enable, volume, vector(random, 8, random.nextInt(256)));
    }

    static void buzzer() throws Exception {
        int before = comparisons;
        BuzzerHarness harness = new BuzzerHarness();
        Random random = new Random(SEED);
        for (int index = 0; index < 20_000; index++) harness.stateOnly(randomBuzzer(random, false));
        int stateComparisons = comparisons - before;
        int clips = 0;
        // Every periodic waveform with and without smoothing, each channel mask, both units, edge frequencies.
        for (String waveform : List.of("Sine", "Square", "Triangle", "Sawtooth")) {
            for (int channels : CHANNELS) {
                for (int[] smoothing : new int[][] {{0, 1}, {2, 2}, {10, 1}, {3, 10}}) {
                    for (long frequency : new long[] {20, 441, 997, 16_383}) {
                        harness.withPcm(new BuzzerCase(false, waveform, channels, smoothing[0], smoothing[1], 7,
                            known(14, frequency), Value.TRUE, known(7, 100), known(8, (frequency * 7) & 255)));
                        clips++;
                    }
                }
            }
        }
        // Below-range, dHz, undefined duty and zero-volume tones.
        for (BuzzerCase sample : List.of(
                new BuzzerCase(false, "Square", 3, 2, 2, 7, known(14, 19), Value.TRUE, known(7, 100), known(8, 128)),
                new BuzzerCase(true, "Square", 3, 2, 2, 7, known(14, 4_410), Value.TRUE, known(7, 127), known(8, 64)),
                new BuzzerCase(true, "Sine", 1, 0, 1, 7, known(14, 199), Value.TRUE, known(7, 127), known(8, 64)),
                new BuzzerCase(false, "Square", 2, 0, 1, 8, known(14, 300), Value.TRUE, known(8, 255),
                    Value.createUnknown(BitWidth.create(8))),
                new BuzzerCase(false, "Triangle", 3, 1, 3, 4, known(14, 300), Value.TRUE, known(4, 0), known(8, 10)),
                new BuzzerCase(false, "Sawtooth", 3, 0, 1, 32, known(14, 300), Value.TRUE, known(32, 0xFFFF_FFFFL), known(8, 10)))) {
            harness.withPcm(sample);
            clips++;
        }
        Random pcmRandom = new Random(SEED + 1);
        for (int index = 0; index < 40; index++, clips++) harness.withPcm(randomBuzzer(pcmRandom, true));
        System.out.println("PASS Buzzer: 20000 random input states (" + stateComparisons + " field comparisons) and "
            + clips + " sound-thread runs (" + (comparisons - before - stateComparisons)
            + " comparisons, Noise excluded from PCM); " + harness.smoothingFailures
            + " clips where Evolution's smoothing throws and the desktop stays silent while Mobile plays");
    }

    // ---------------------------------------------------------------- Digital oscilloscope

    static Value clockValue(Random random, Value previous) {
        int roll = random.nextInt(100);
        if (roll < 3) return Value.UNKNOWN;
        if (roll < 5) return Value.ERROR;
        if (roll < 65) return previous == Value.TRUE ? Value.FALSE : Value.TRUE;
        return previous;
    }

    static void oscilloscope() throws ReflectiveOperationException {
        int before = comparisons;
        Random random = new Random(SEED + 2);
        int steps = 0;
        for (int inputs : new int[] {1, 2, 3, 8, 32}) {
            for (int states : new int[] {4, 5, 10, 35}) {
                for (int run = 0; run < 3; run++) {
                    Fixture desktop = new Fixture(new DigitalOscilloscope());
                    desktop.attribute("inputs", Integer.toString(inputs));
                    desktop.attribute("nState", Integer.toString(states));
                    ExtraOscilloscopeState mobile = new ExtraOscilloscopeState(inputs, states, List.of(), LogicValue.UNKNOWN, states);
                    Value clock = Value.FALSE;
                    int clearRate = run == 0 ? 0 : run == 1 ? 2 : 6;
                    for (int step = 0; step < 600; step++, steps++) {
                        clock = clockValue(random, clock);
                        List<LogicValue> data = new ArrayList<>();
                        desktop.inputs.put(0, clock);
                        for (int index = 0; index < inputs; index++) {
                            Value value = bit(random, 10, 10, 1);
                            desktop.inputs.put(index + 1, value);
                            data.add(mobileBit(value));
                        }
                        Value enable = bit(random, 90, 7, 2);
                        Value clear = random.nextInt(100) < clearRate ? bit(random, 6, 0, 3) : Value.FALSE;
                        desktop.inputs.put(inputs + 1, enable);
                        desktop.inputs.put(inputs + 2, clear);
                        desktop.evaluate();
                        mobile = mobile.sample(mobileBit(clock), data, mobileBit(enable), mobileBit(clear));
                        compareScope(desktop.data, mobile, inputs,
                            "Oscilloscope inputs=" + inputs + " states=" + states + " run=" + run + " step=" + step);
                    }
                }
            }
        }
        System.out.println("PASS DigitalOscilloscope: " + steps + " steps (" + (comparisons - before)
            + " comparisons of trace length, every captured cell and the clock counter)");
    }

    static java.lang.reflect.Method scopeMethod(String name, Class<?>... parameters) {
        try {
            var method = Class.forName("com.cburch.logisim.std.io.extra.DiagramState").getDeclaredMethod(name, parameters);
            method.setAccessible(true);
            return method;
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException(failure);
        }
    }

    static final java.lang.reflect.Method USED_CELL = scopeMethod("getusedcell"),
        CELL = scopeMethod("getState", int.class, int.class), CLOCK_NUMBER = scopeMethod("getclocknumber");

    /** DiagramState is package-private in Evolution; its getters are read reflectively. */
    static void compareScope(Object desktop, ExtraOscilloscopeState mobile, int inputs, String label) throws ReflectiveOperationException {
        List<List<LogicValue>> samples = mobile.getSamples();
        int used = (Byte) USED_CELL.invoke(desktop) + 1;
        check(samples.size() == used, label + " length desktop=" + used + " mobile=" + samples.size());
        for (int column = 0; column < used; column++) {
            for (int row = 0; row <= inputs; row++) {
                Boolean cell = (Boolean) CELL.invoke(desktop, row, column);
                LogicValue expected = cell == null ? LogicValue.UNKNOWN : cell ? LogicValue.HIGH : LogicValue.LOW;
                LogicValue actual = samples.get(column).get(row);
                check(expected == actual, label + " cell row=" + row + " column=" + column + " desktop=" + expected + " mobile=" + actual);
            }
        }
        byte clockNumber = (Byte) CLOCK_NUMBER.invoke(desktop);
        check(clockNumber == mobile.getClockNumber(),
            label + " clock number desktop=" + clockNumber + " mobile=" + mobile.getClockNumber());
    }


    // ---------------------------------------------------------------- circuits shared by both programs

    static final String HEADER = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"no\"?>\n"
        + "<project source=\"5.1.0\" version=\"1.0\"><lib desc=\"#Wiring\" name=\"0\"/><lib desc=\"#I/O\" name=\"5\"/>"
        + "<lib desc=\"#Soc\" name=\"8\"/><main name=\"main\"/><circuit name=\"main\">";
    static final String FOOTER = "</circuit></project>\n";

    static File write(String name, String body) throws Exception {
        File file = File.createTempFile("output-devices-" + name + "-", ".circ");
        file.deleteOnExit();
        Files.writeString(file.toPath(), HEADER + body + FOOTER);
        return file;
    }

    static String attributes(Map<String, String> values) {
        StringBuilder result = new StringBuilder();
        values.forEach((key, value) -> result.append("<a name=\"").append(key).append("\" val=\"").append(value).append("\"/>"));
        return result.toString();
    }

    static CircuitProject mobileProject(File file) throws Exception {
        Object result = CircCodec.INSTANCE.readProject(Files.readString(file.toPath()), ComponentCatalog.INSTANCE.getRegistry());
        if (!(result instanceof CircProjectImportResult.Success success)) throw new AssertionError("Mobile rejected " + file + ": " + result);
        return success.getProject();
    }

    static CircuitComponent mobileComponent(CircuitProject project, String kind) {
        return project.getMainCircuit().getComponents().stream()
            .filter(component -> component.getKind().getName().equals(kind)).findFirst().orElseThrow();
    }

    static Map<String, String> mobilePins(CircuitProject project) {
        Map<String, String> result = new HashMap<>();
        for (CircuitComponent component : project.getMainCircuit().getComponents()) {
            if (component.getKind() == ComponentKind.INPUT) result.put(component.getLabel(), component.getId());
        }
        return result;
    }

    // ---------------------------------------------------------------- RGB Video

    static final String[] VIDEO_COLORS = {"888 RGB (24 bit)", "555 RGB (15 bit)", "565 RGB (16 bit)", "444 RGB (12 bit)",
        "8-Color RGB (3 bit)", "Atari 2600 (7 bit)", "XTerm16 (4 bit)", "XTerm256 (8 bit)", "Grayscale (4 bit)", "VGA256 (8 bit)"};
    static final String[] VIDEO_PORTS = {"rst", "clk", "we", "x", "y", "data"};

    static void video() throws Exception {
        int before = comparisons;
        Random random = new Random(SEED + 3);
        int steps = 0, circuits = 0;
        for (String color : VIDEO_COLORS) {
            for (String reset : new String[] {"Asynchronous", "Synchronous"}) {
                for (int[] size : new int[][] {{16, 8}, {7, 5}, {2, 2}, {33, 17}}) {
                    circuits++;
                    Map<String, String> videoAttributes = new LinkedHashMap<>();
                    videoAttributes.put("width", Integer.toString(size[0]));
                    videoAttributes.put("height", Integer.toString(size[1]));
                    videoAttributes.put("scale", "1");
                    videoAttributes.put("color", color);
                    videoAttributes.put("reset", reset);
                    videoAttributes.put("cursor", "No Cursor");
                    String video = "<comp lib=\"5\" loc=\"(200,300)\" name=\"RGB Video\">" + attributes(videoAttributes) + "</comp>";
                    // Evolution decides the terminal positions; Mobile must accept pins at the same points.
                    int[][] ends = new int[6][3];
                    try (DesktopCircuit probe = new DesktopCircuit(write("video-probe", video))) {
                        var component = probe.component("RGB Video");
                        for (int index = 0; index < 6; index++) {
                            var end = component.getEnd(index);
                            ends[index] = new int[] {end.getLocation().getX(), end.getLocation().getY(), end.getWidth().getWidth()};
                        }
                    }
                    StringBuilder body = new StringBuilder(video);
                    for (int index = 0; index < 6; index++) {
                        body.append("<comp lib=\"0\" loc=\"(").append(ends[index][0]).append(',').append(ends[index][1])
                            .append(")\" name=\"Pin\">").append(attributes(Map.of("label", VIDEO_PORTS[index],
                                "width", Integer.toString(ends[index][2])))).append("</comp>");
                    }
                    File file = write("video", body.toString());
                    CircuitProject project = mobileProject(file);
                    String videoId = mobileComponent(project, "RGB_VIDEO").getId();
                    Map<String, String> pins = mobilePins(project);
                    CircuitSimulator simulator = new CircuitSimulator();
                    try (DesktopCircuit desktop = new DesktopCircuit(file)) {
                        var component = desktop.component("RGB Video");
                        Map<String, LogicVector> supplied = new HashMap<>();
                        SimulationState mobile = simulator.settle(project, new SimulationState(), Map.of(), false, supplied);
                        compareVideo(desktop.data(component), mobile.getComponentStates().get(videoId), size, color + " initial");
                        Value clock = Value.FALSE;
                        for (int step = 0; step < 160; step++, steps++) {
                            Value[] values = new Value[6];
                            values[0] = random.nextInt(25) == 0 ? bit(random, 1, 0, 1) : Value.FALSE;
                            clock = random.nextInt(10) < 7 ? (clock == Value.TRUE ? Value.FALSE : Value.TRUE)
                                : random.nextInt(8) == 0 ? bit(random, 1, 1, 1) : clock;
                            values[1] = clock;
                            values[2] = bit(random, 8, 2, 1);
                            values[3] = vector(random, ends[3][2], random.nextInt(1 << ends[3][2]));
                            values[4] = vector(random, ends[4][2], random.nextInt(1 << ends[4][2]));
                            values[5] = vector(random, ends[5][2], random.nextLong() & ((1L << ends[5][2]) - 1));
                            for (int index = 0; index < 6; index++) {
                                desktop.drive(VIDEO_PORTS[index], values[index]);
                                supplied.put(pins.get(VIDEO_PORTS[index]), mobile(values[index]));
                            }
                            desktop.propagate();
                            mobile = simulator.settle(project, mobile, Map.of(), false, supplied);
                            compareVideo(desktop.data(component), mobile.getComponentStates().get(videoId), size,
                                color + " " + reset + " " + size[0] + "x" + size[1] + " step " + step + " inputs "
                                    + java.util.Arrays.toString(values));
                        }
                    }
                }
            }
        }
        System.out.println("PASS RGB Video: " + circuits + " circuits, " + steps + " input steps (" + (comparisons - before)
            + " pixel and cursor comparisons; the blinking cursor follows the wall clock and is not compared)");
    }

    static void compareVideo(Object desktop, Object mobileState, int[] size, String label) throws Exception {
        VideoState mobile = (VideoState) mobileState;
        BufferedImage image = (BufferedImage) field(desktop.getClass(), "img").get(desktop);
        check(image.getWidth() == size[0] && image.getHeight() == size[1] && mobile.getFrame().getWidth() == size[0]
            && mobile.getFrame().getHeight() == size[1], label + " size");
        for (int y = 0; y < size[1]; y++) {
            for (int x = 0; x < size[0]; x++) {
                int expected = image.getRGB(x, y), actual = mobile.getFrame().argbAt(x, y);
                check(expected == actual, label + " pixel (" + x + "," + y + ") desktop=" + Integer.toHexString(expected)
                    + " mobile=" + Integer.toHexString(actual));
            }
        }
        check(field(desktop.getClass(), "lastX").getInt(desktop) == mobile.getCursorX()
            && field(desktop.getClass(), "lastY").getInt(desktop) == mobile.getCursorY()
            && field(desktop.getClass(), "color").getInt(desktop) == mobile.getCursorColor(), label + " cursor registers");
    }

    // ---------------------------------------------------------------- RealTimeClock

    static void realTimeClock() throws Exception {
        int before = comparisons;
        int samples = 0;
        for (String type : new String[] {"1", "2"}) {
            String clock = "<comp lib=\"5\" loc=\"(200,200)\" name=\"RealTimeClock\">" + attributes(Map.of("timeType", type)) + "</comp>";
            File file = write("rtc", clock);
            CircuitProject project = mobileProject(file);
            CircuitSimulator simulator = new CircuitSimulator();
            try (DesktopCircuit desktop = new DesktopCircuit(file)) {
                var component = desktop.component("RealTimeClock");
                var end = component.getEnd(0).getLocation();
                check(component.getEnd(0).getWidth().getWidth() == 64, "RealTimeClock desktop width");
                GridPoint point = new GridPoint(end.getX(), end.getY());
                long divisor = type.equals("1") ? 1 : 1000;
                SimulationState mobile = new SimulationState();
                for (int sample = 0; sample < 200; sample++, samples++) {
                    long start = System.currentTimeMillis();
                    desktop.state.markComponentAsDirty(component);
                    desktop.propagate();
                    long desktopValue = desktop.value(end).toLongValue();
                    mobile = simulator.settle(project, mobile, Map.of(), true, Map.of());
                    LogicVector output = mobile.getPortVectors().get(point);
                    long end2 = System.currentTimeMillis();
                    check(output != null && output.getWidth() == 64, "RealTimeClock Mobile has no 64-bit output at " + point);
                    long mobileValue = Long.parseUnsignedLong(output.getDisplay(), 2);
                    long low = Math.floorDiv(start, divisor), high = Math.floorDiv(end2, divisor);
                    check(desktopValue >= low && desktopValue <= high && mobileValue >= low && mobileValue <= high
                            && mobileValue >= desktopValue,
                        "RealTimeClock type " + type + " desktop=" + desktopValue + " mobile=" + mobileValue + " window=" + low + ".." + high);
                    if (sample % 20 == 0) Thread.sleep(3);
                }
            }
        }
        System.out.println("PASS RealTimeClock: " + samples + " paired readings in both time types (" + (comparisons - before)
            + " comparisons; each value lies in the same wall-clock window and Mobile never reads earlier than Evolution)");
    }

    // ---------------------------------------------------------------- SocVga

    static Object socRequest(long address, long data, String size, boolean read, boolean write) throws Exception {
        for (Constructor<?> constructor : SocBusRequest.class.getDeclaredConstructors()) {
            Class<?>[] types = constructor.getParameterTypes();
            if (types.length == 8 && types[0] == int.class) {
                constructor.setAccessible(true);
                return constructor.newInstance((int) address, (int) data, SocAccessSize.valueOf(size), read, write, false, "", false);
            }
        }
        throw new IllegalStateException("SocBusRequest constructor not found");
    }

    static void socVga() throws Exception {
        int before = comparisons;
        String body = "<comp lib=\"8\" loc=\"(20,20)\" name=\"SocBus\"><a name=\"SocBusIdentifier\" val=\"vga-bus\"/></comp>"
            + "<comp lib=\"8\" loc=\"(20,250)\" name=\"Socmem\"><a name=\"SocBusSelection\" val=\"vga-bus\"/><a name=\"MemSize\" val=\"19\"/></comp>"
            + "<comp lib=\"8\" loc=\"(400,250)\" name=\"SocVga\"><a name=\"SocBusSelection\" val=\"vga-bus\"/>"
            + "<a name=\"StartAddress\" val=\"0x100000\"/><a name=\"BufferAddress\" val=\"0x40\"/><a name=\"DisplayMode\" val=\"160x120\"/>"
            + "<a name=\"soft640x480\" val=\"false\"/></comp>";
        File file = write("vga", body);
        CircuitProject project = mobileProject(file);
        String busId = mobileComponent(project, "SOC_BUS").getId();
        String vgaId = mobileComponent(project, "SOC_VGA").getId();
        CircuitSimulator simulator = new CircuitSimulator();
        int transactions = 0, checkpoints = 0;
        Random random = new Random(SEED + 4);
        try (DesktopCircuit desktop = new DesktopCircuit(file)) {
            var component = desktop.component("SocVga");
            VgaState vga = component.getAttributeSet().getValue(VgaAttributes.VGA_STATE);
            SimulationState mobile = simulator.settle(project);
            // Evolution returns a new unseeded random word for every read of unwritten SoC memory, so both
            // programs first write the whole 320x240 frame buffer. Reloads then read the same words.
            List<ComponentEvent> fill = new ArrayList<>();
            for (long address = 0x40; address < 0x40 + 320 * 240 * 4; address += 4) {
                long word = random.nextInt();
                transact(desktop, vga, SocBusTransaction.WORD_ACCESS, address, word, true);
                fill.add(new SocTransactionEvent((SocBusRequest) socRequest(address, word, "WORD", false, true)));
                transactions++;
                if (fill.size() == 4096 || address + 4 == 0x40 + 320 * 240 * 4) {
                    mobile = simulator.settle(project, mobile, Map.of(), false, Map.of(), Map.of(busId, List.copyOf(fill)));
                    fill.clear();
                }
            }
            checkpoints += compareVga(desktop, vga, mobile, vgaId, "after filling the frame buffer");
            String[] sizes = {"WORD", "HALF_WORD", "BYTE"};
            int[] desktopSizes = {SocBusTransaction.WORD_ACCESS, SocBusTransaction.HALF_WORD_ACCESS, SocBusTransaction.BYTE_ACCESS};
            // 640x480 is disabled, and 800x600 and 1024x768 are disabled by default: those masks select the initial mode.
            long[] modeWrites = {2, 1, 4, 2, 8, 2, 16, 3, 0, 2, 1};
            for (int round = 0; round < modeWrites.length; round++) {
                for (int index = 0; index < 120; index++, transactions++) {
                    long address;
                    int roll = random.nextInt(20);
                    if (roll == 0) address = 0x100000;                      // the VGA mode register
                    else if (roll == 1) address = 0x80000 + random.nextInt(64) * 4; // no responder
                    else address = 0x40 + random.nextInt(320 * 240) * 4L;
                    int size = roll < 2 ? 0 : random.nextInt(10) == 0 ? 1 + random.nextInt(2) : 0;
                    if (size == 1) address &= ~1L;
                    long data = random.nextInt();
                    boolean write = random.nextInt(6) != 0;
                    if (address == 0x100000 && write) data = modeWrites[round];
                    transact(desktop, vga, desktopSizes[size], address, data, write);
                    mobile = simulator.settle(project, mobile, Map.of(), false, Map.of(), Map.of(busId,
                        List.of(new SocTransactionEvent((SocBusRequest) socRequest(address, data, sizes[size], !write, write)))));
                }
                checkpoints += compareVga(desktop, vga, mobile, vgaId, "round " + round);
                transact(desktop, vga, SocBusTransaction.WORD_ACCESS, 0x100000, modeWrites[round], true);
                mobile = simulator.settle(project, mobile, Map.of(), false, Map.of(), Map.of(busId,
                    List.of(new SocTransactionEvent((SocBusRequest) socRequest(0x100000, modeWrites[round], "WORD", false, true)))));
                transactions++;
                checkpoints += compareVga(desktop, vga, mobile, vgaId, "mode write " + modeWrites[round]);
            }
        }
        System.out.println("PASS SocVga: " + transactions + " bus transactions and " + checkpoints + " display checkpoints ("
            + (comparisons - before) + " comparisons of mode, size and every displayed pixel)");
    }

    static void transact(DesktopCircuit desktop, VgaState vga, int size, long address, long data, boolean write) {
        var transaction = new SocBusTransaction(write ? SocBusTransaction.WRITE_TRANSACTION : SocBusTransaction.READ_TRANSACTION,
            (int) address, (int) data, size, "oracle");
        vga.getBusInfo().getSocSimulationManager().initializeTransaction(transaction, vga.getBusInfo().getBusId(), desktop.state);
        // Evolution reloads a pending frame when it paints. Painting after every transaction stands for the
        // canvas refresh; Mobile performs the same reload in the transaction that changes the mode.
        ((VgaState.VgaDisplayState) desktop.data(vga.getComponent())).getImage(desktop.state);
    }

    static int compareVga(DesktopCircuit desktop, VgaState vga, SimulationState mobileState, String vgaId, String label) {
        SocVgaDevice mobile = (SocVgaDevice) ((SocDeviceComponentState) mobileState.getComponentStates().get(vgaId)).getDevice();
        // The display state that SocVga.propagate created in this circuit state; transactions sniff into it.
        VgaState.VgaDisplayState display = (VgaState.VgaDisplayState) desktop.data(vga.getComponent());
        BufferedImage image = display.getImage(desktop.state);
        check(image.getWidth() == mobile.getWidth() && image.getHeight() == mobile.getHeight(),
            "SocVga " + label + " size desktop=" + image.getWidth() + "x" + image.getHeight() + " mobile=" + mobile.getWidth() + "x" + mobile.getHeight());
        Map<Integer, ?> pixels = mobile.getArgbPixels();
        for (int y = 0; y < image.getHeight(); y++) {
            for (int x = 0; x < image.getWidth(); x++) {
                Object value = pixels.get(y * image.getWidth() + x);
                int actual = value == null ? 0xFF000000 : (int) Long.parseLong(String.valueOf(value));
                int expected = image.getRGB(x, y);
                comparisons++;
                if (expected != actual) {
                    throw new AssertionError("SocVga " + label + " pixel (" + x + "," + y + ") desktop=" + Integer.toHexString(expected)
                        + " mobile=" + Integer.toHexString(actual));
                }
            }
        }
        return 1;
    }

    // ---------------------------------------------------------------- entry point

    public static void main(String[] args) {
        // Evolution's simulator and autosave threads are not daemons; always end the JVM explicitly.
        try {
            run(args);
        } catch (Throwable failure) {
            failure.printStackTrace();
            System.exit(1);
        }
        System.exit(0);
    }

    static void run(String[] args) throws Exception {
        List<String> sections = args.length == 0 ? List.of("buzzer", "oscilloscope", "video", "rtc", "vga") : List.of(args);
        for (String section : sections) {
            switch (section) {
                case "buzzer" -> buzzer();
                case "oscilloscope" -> oscilloscope();
                case "video" -> video();
                case "rtc" -> realTimeClock();
                case "vga" -> socVga();
                default -> throw new IllegalArgumentException("Unknown section " + section);
            }
        }
        System.out.println("PASS " + comparisons + " output-device desktop/mobile comparisons, seed " + SEED);
    }
}
