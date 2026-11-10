import com.cburch.logisim.circuit.DesktopCircuit;
import com.cburch.logisim.data.Location;
import com.cburch.logisim.data.Value;
import dev.logisim.mobile.core.CircCodec;
import dev.logisim.mobile.core.CircProjectImportResult;
import dev.logisim.mobile.core.CircuitComponent;
import dev.logisim.mobile.core.CircuitProject;
import dev.logisim.mobile.core.CircuitSimulator;
import dev.logisim.mobile.core.ComponentCatalog;
import dev.logisim.mobile.core.ComponentEvent;
import dev.logisim.mobile.core.ComponentKind;
import dev.logisim.mobile.core.GridPoint;
import dev.logisim.mobile.core.JvmTelnetTransport;
import dev.logisim.mobile.core.LogicValue;
import dev.logisim.mobile.core.LogicVector;
import dev.logisim.mobile.core.SimulationState;
import dev.logisim.mobile.core.TelnetBytesReceived;
import dev.logisim.mobile.core.TelnetLinkChanged;
import dev.logisim.mobile.core.TelnetLinkState;
import dev.logisim.mobile.core.TelnetListenConfiguration;
import dev.logisim.mobile.core.TelnetOutputAccepted;
import dev.logisim.mobile.core.TelnetPacket;
import dev.logisim.mobile.core.TelnetRuntimeEvents;
import dev.logisim.mobile.core.TelnetSession;
import dev.logisim.mobile.core.TelnetState;
import java.io.BufferedReader;
import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.lang.reflect.Field;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Runs the same Telnet scenarios on Evolution's Telnet/TelnetServer and on the two Mobile paths:
 * the app's companion path (companion/telnet.py TelnetManager + the ExternalIoRuntimeBridge sync rules + core
 * TelnetBehavior) and the JVM host path (JvmTelnetTransport + TelnetSession + TelnetBehavior). Each scenario
 * produces a trace of circuit outputs after every input change and of the bytes each socket client observes.
 *
 * Usage: java TelnetOracle REPO_ROOT OUTPUT_DIR
 */
public final class TelnetOracle {
    static final int SETTLE_MS = 300;
    static final int READ_MS = 400;
    /**
     * Host tools on the comparison machine probe every new localhost listener (an HTTP GET, then a TLS hello that
     * stays open about ten seconds). Both programs accept such peers, so each run waits this long after the
     * listener opens and discards what arrived before the scenario connects its own clients.
     */
    static final int WARMUP_MS = Integer.parseInt(System.getenv().getOrDefault("TELNET_ORACLE_WARMUP_MS", "12500"));

    // Telnet at (200,200); Evolution's ports: in (-30,10), out (10,10), clk (-30,20), wr (-30,30), rd (10,30), av (10,20).
    static final Map<String, int[]> INPUTS = new LinkedHashMap<>();
    static final Map<String, int[]> OUTPUTS = new LinkedHashMap<>();
    static {
        INPUTS.put("in", new int[] {170, 210, 8});
        INPUTS.put("clk", new int[] {170, 220, 1});
        INPUTS.put("wr", new int[] {170, 230, 1});
        INPUTS.put("rd", new int[] {210, 230, 1});
        OUTPUTS.put("out", new int[] {210, 210, 8});
        OUTPUTS.put("av", new int[] {210, 220, 1});
    }

    static String circ(int port, boolean telnetMode, int buffer, String trigger) {
        StringBuilder body = new StringBuilder();
        body.append("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"no\"?>\n")
            .append("<project source=\"5.1.0dev\" version=\"1.0\"><lib desc=\"#Wiring\" name=\"0\"/><lib desc=\"#I/O\" name=\"5\"/>")
            .append("<main name=\"main\"/><circuit name=\"main\">")
            .append("<comp lib=\"5\" loc=\"(200,200)\" name=\"Telnet\">")
            .append("<a name=\"telnetMode\" val=\"").append(telnetMode).append("\"/>")
            .append("<a name=\"port\" val=\"").append(port).append("\"/>")
            .append("<a name=\"buflen\" val=\"").append(buffer).append("\"/>")
            .append("<a name=\"trigger\" val=\"").append(trigger).append("\"/></comp>");
        for (var entry : INPUTS.entrySet()) {
            int[] p = entry.getValue();
            String facing = p[0] < 200 ? "east" : "west";
            body.append("<comp lib=\"0\" loc=\"(").append(p[0]).append(',').append(p[1]).append(")\" name=\"Pin\">")
                .append("<a name=\"facing\" val=\"").append(facing).append("\"/>")
                .append("<a name=\"width\" val=\"").append(p[2]).append("\"/><a name=\"label\" val=\"").append(entry.getKey()).append("\"/></comp>");
        }
        for (var entry : OUTPUTS.entrySet()) {
            int[] p = entry.getValue();
            body.append("<comp lib=\"0\" loc=\"(").append(p[0]).append(',').append(p[1]).append(")\" name=\"Pin\">")
                .append("<a name=\"facing\" val=\"west\"/><a name=\"output\" val=\"true\"/>")
                .append("<a name=\"width\" val=\"").append(p[2]).append("\"/><a name=\"label\" val=\"").append(entry.getKey()).append("\"/></comp>");
        }
        return body.append("</circuit></project>\n").toString();
    }

    /** One simulator plus its network side. */
    interface Side extends AutoCloseable {
        /** Applies one input change and returns the observed outputs. */
        String set(String pin, long value) throws Exception;
        /** Lets the network side catch up (Mobile: one bridge sync round). */
        void sync() throws Exception;
        /** Outputs without any input change (informational). */
        String probe() throws Exception;
        /** Discards bytes from connections made before the scenario starts; returns how many. */
        int drain() throws Exception;
        @Override void close() throws Exception;
    }

    // ------------------------------------------------------------------ Evolution

    static final class EvolutionSide implements Side {
        final DesktopCircuit circuit;
        final Map<String, Integer> widths = new HashMap<>();

        EvolutionSide(File file) throws Exception {
            circuit = new DesktopCircuit(file);
            INPUTS.forEach((name, p) -> widths.put(name, p[2]));
            for (String name : INPUTS.keySet()) circuit.drive(name, Value.createKnown(widths.get(name), 0));
            circuit.propagate();
        }

        @Override public String set(String pin, long value) {
            circuit.drive(pin, Value.createKnown(widths.get(pin), value));
            circuit.propagate();
            return probe();
        }

        @Override public void sync() throws Exception { Thread.sleep(SETTLE_MS); }

        @Override public int drain() throws Exception {
            var server = (com.cburch.logisim.std.io.TelnetServer) circuit.data(circuit.component("Telnet"));
            Field buffer = server.getClass().getDeclaredField("buffer");
            buffer.setAccessible(true);
            Object bytes = buffer.get(server);
            Field count = bytes.getClass().getDeclaredField("inBuffer");
            count.setAccessible(true);
            int stray = count.getInt(bytes);
            server.deleteAll();
            circuit.propagate();
            return stray;
        }

        @Override public String probe() {
            StringBuilder result = new StringBuilder();
            for (var entry : OUTPUTS.entrySet()) {
                int[] p = entry.getValue();
                Value value = circuit.value(Location.create(p[0], p[1], false));
                result.append(entry.getKey()).append('=').append(display(mobile(value, p[2]))).append(' ');
            }
            return result.toString().trim();
        }

        String bindAddress() throws Exception {
            var server = circuit.data(circuit.component("Telnet"));
            Field field = server.getClass().getDeclaredField("serverSocket");
            field.setAccessible(true);
            return ((ServerSocket) field.get(server)).getInetAddress().getHostAddress();
        }

        @Override public void close() { circuit.close(); }
    }

    static LogicVector mobile(Value value, int width) {
        List<LogicValue> bits = new ArrayList<>();
        Value[] all = value.getAll();
        for (int index = 0; index < width; index++) {
            Value bit = index < all.length ? all[index] : Value.NIL;
            bits.add(bit == Value.TRUE ? LogicValue.HIGH : bit == Value.FALSE ? LogicValue.LOW
                : bit == Value.ERROR ? LogicValue.ERROR : LogicValue.UNKNOWN);
        }
        return LogicVector.Companion.fromBits(bits);
    }

    static String display(LogicVector vector) {
        return vector.getDisplay();
    }

    // ------------------------------------------------------------------ Mobile

    /** Network path feeding TelnetRuntimeEvents into the Mobile simulator. */
    interface MobileNetwork extends AutoCloseable {
        TelnetLinkState start(int port, boolean telnetMode, int buffer) throws Exception;
        List<ComponentEvent> sync(TelnetState state, int buffer) throws Exception;
        String bindAddress();
        @Override void close() throws Exception;
    }

    /** companion/telnet.py over JSON lines, with ExternalIoRuntimeBridge.synchronize's poll and send rules. */
    static final class CompanionNetwork implements MobileNetwork {
        final Process process;
        final PrintWriter requests;
        final BufferedReader replies;
        String sessionId;
        String bind;

        CompanionNetwork(String repo) throws Exception {
            process = new ProcessBuilder("python3", "-I", repo + "/scripts/oracle/telnet/companion_bridge.py", repo)
                .redirectError(ProcessBuilder.Redirect.INHERIT).start();
            requests = new PrintWriter(process.getOutputStream(), true, StandardCharsets.UTF_8);
            replies = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8));
        }

        String call(String json) throws Exception {
            requests.println(json);
            String reply = replies.readLine();
            if (reply == null) throw new IllegalStateException("companion bridge exited");
            return reply;
        }

        static String field(String json, String name) {
            Matcher m = Pattern.compile("\"" + name + "\": (\"([^\"]*)\"|[^,}]+)").matcher(json);
            if (!m.find()) return null;
            return m.group(2) != null ? m.group(2) : m.group(1).trim();
        }

        static List<Integer> bytes(String json) {
            Matcher m = Pattern.compile("\"bytes\": \\[([^\\]]*)\\]").matcher(json);
            List<Integer> result = new ArrayList<>();
            if (m.find() && !m.group(1).isBlank()) for (String part : m.group(1).split(",")) result.add(Integer.parseInt(part.trim()));
            return result;
        }

        static TelnetLinkState link(String name) {
            return "connected".equals(name) ? TelnetLinkState.Connected.INSTANCE : TelnetLinkState.Listening.INSTANCE;
        }

        @Override public TelnetLinkState start(int port, boolean telnetMode, int buffer) throws Exception {
            String reply = call("{\"op\":\"start\",\"port\":" + port + ",\"telnetMode\":" + telnetMode + ",\"bufferSize\":" + buffer + "}");
            sessionId = field(reply, "sessionId");
            bind = field(reply, "bindAddress");
            if (sessionId == null) throw new IllegalStateException("start failed: " + reply);
            return link(field(reply, "link"));
        }

        @Override public List<ComponentEvent> sync(TelnetState state, int buffer) throws Exception {
            int maximum = Math.max(0, Math.min(4096, buffer - state.getReceived().size()));
            String reply = call("{\"op\":\"poll\",\"id\":\"" + sessionId + "\",\"max\":" + maximum + "}");
            TelnetLinkState link = link(field(reply, "link"));
            List<ComponentEvent> events = new ArrayList<>();
            if (!link.equals(state.getLink())) events.add(new TelnetLinkChanged(link));
            List<Integer> received = bytes(reply);
            if (!received.isEmpty()) events.add(new TelnetBytesReceived(received));
            if (link.equals(TelnetLinkState.Connected.INSTANCE) && !state.getPendingOutput().isEmpty()) {
                List<Integer> data = new ArrayList<>();
                long last = 0;
                for (TelnetPacket packet : state.getPendingOutput()) { data.addAll(packet.getBytes()); last = packet.getId(); }
                String sent = call("{\"op\":\"send\",\"id\":\"" + sessionId + "\",\"packetId\":" + last + ",\"data\":" + data + "}");
                if (field(sent, "error") == null) events.add(new TelnetOutputAccepted(last));
            }
            return events;
        }

        @Override public String bindAddress() { return bind; }

        @Override public void close() throws Exception {
            if (sessionId != null) call("{\"op\":\"stop\",\"id\":\"" + sessionId + "\"}");
            call("{\"op\":\"quit\"}");
            process.waitFor();
        }
    }

    /** JvmTelnetTransport + TelnetSession, the JVM companion-host transport in core. */
    static final class JvmNetwork implements MobileNetwork {
        final JvmTelnetTransport transport = new JvmTelnetTransport();
        final TelnetSession session = new TelnetSession(transport);

        @Override public TelnetLinkState start(int port, boolean telnetMode, int buffer) {
            return session.start(new TelnetListenConfiguration(port, false, telnetMode, 65_536)).getLink();
        }

        @Override public List<ComponentEvent> sync(TelnetState state, int buffer) {
            List<ComponentEvent> events = session.synchronize(state);
            if (System.getenv("TELNET_ORACLE_DEBUG") != null) System.err.println("jvm sync " + state.getLink() + " pending=" + state.getPendingOutput() + " -> " + events);
            return events;
        }

        @Override public String bindAddress() { return "127.0.0.1"; }

        @Override public void close() { session.stop(); }
    }

    static final class MobileSide implements Side {
        final CircuitProject project;
        final CircuitSimulator simulator = new CircuitSimulator();
        final Map<String, String> pinIds = new HashMap<>();
        final Map<String, Integer> widths = new HashMap<>();
        final Map<String, LogicVector> supplied = new HashMap<>();
        final String telnetId;
        final MobileNetwork network;
        final int buffer;
        SimulationState state;
        long sequence = 0;

        MobileSide(File file, MobileNetwork network, int port, boolean telnetMode, int buffer) throws Exception {
            Object result = CircCodec.INSTANCE.readProject(Files.readString(file.toPath()), ComponentCatalog.INSTANCE.getRegistry());
            if (!(result instanceof CircProjectImportResult.Success success)) throw new AssertionError("Mobile rejected " + file + ": " + result);
            project = success.getProject();
            String telnet = null;
            for (CircuitComponent component : project.getMainCircuit().getComponents()) {
                if (component.getKind() == ComponentKind.INPUT) pinIds.put(component.getLabel(), component.getId());
                if (component.getKind().getName().equals("TELNET")) telnet = component.getId();
            }
            telnetId = telnet;
            INPUTS.forEach((name, p) -> widths.put(name, p[2]));
            for (String name : INPUTS.keySet()) supplied.put(pinIds.get(name), vector(widths.get(name), 0));
            this.network = network;
            this.buffer = buffer;
            state = simulator.settle(project, new SimulationState(), Map.of(), false, supplied);
            TelnetLinkState link = network.start(port, telnetMode, buffer);
            publish(List.of(new TelnetLinkChanged(link)));
        }

        static LogicVector vector(int width, long value) {
            List<LogicValue> bits = new ArrayList<>();
            for (int index = 0; index < width; index++) bits.add(((value >> index) & 1) == 1 ? LogicValue.HIGH : LogicValue.LOW);
            return LogicVector.Companion.fromBits(bits);
        }

        void publish(List<ComponentEvent> events) {
            if (events.isEmpty()) return;
            sequence++;
            state = simulator.settle(project, state, Map.of(), false, supplied,
                Map.of(telnetId, List.of(new TelnetRuntimeEvents(sequence, events))));
        }

        TelnetState telnet() { return (TelnetState) state.getComponentStates().get(telnetId); }

        @Override public String set(String pin, long value) {
            supplied.put(pinIds.get(pin), vector(widths.get(pin), value));
            state = simulator.settle(project, state, Map.of(), false, supplied);
            return probe();
        }

        @Override public void sync() throws Exception {
            Thread.sleep(SETTLE_MS);
            publish(network.sync(telnet(), buffer));
            Thread.sleep(50);
            // A second round delivers what the first one queued (negotiation and output flushes).
            publish(network.sync(telnet(), buffer));
        }

        @Override public int drain() throws Exception {
            // Both Mobile listeners accept one queued peer per poll, so poll until the backlog is empty.
            int stray = 0;
            for (int quiet = 0; quiet < 5; ) {
                List<ComponentEvent> kept = new ArrayList<>();
                List<ComponentEvent> events = network.sync(telnet(), buffer);
                for (ComponentEvent event : events) {
                    if (event instanceof TelnetBytesReceived bytes) stray += bytes.getBytes().size(); else kept.add(event);
                }
                publish(kept);
                quiet = events.isEmpty() ? quiet + 1 : 0;
                Thread.sleep(50);
            }
            return stray;
        }

        @Override public String probe() {
            StringBuilder result = new StringBuilder();
            for (var entry : OUTPUTS.entrySet()) {
                int[] p = entry.getValue();
                LogicVector value = state.getPortVectors().get(new GridPoint(p[0], p[1]));
                result.append(entry.getKey()).append('=').append(value == null ? "null" : display(value)).append(' ');
            }
            return result.toString().trim();
        }

        @Override public void close() throws Exception { network.close(); }
    }

    // ------------------------------------------------------------------ scenarios

    static final class Client implements AutoCloseable {
        final Socket socket;
        final InputStream in;
        final OutputStream out;
        Client(int port) throws Exception {
            socket = new Socket("127.0.0.1", port);
            socket.setSoTimeout(READ_MS);
            in = socket.getInputStream();
            out = socket.getOutputStream();
        }
        /** Closes with a TCP reset instead of a FIN. */
        void reset() throws Exception {
            socket.setSoLinger(true, 0);
            socket.close();
        }
        void send(int... values) throws Exception {
            for (int value : values) out.write(value);
            out.flush();
        }
        /** Bytes available within READ_MS, then "EOF" if the peer closed. */
        String read() throws Exception {
            List<Integer> result = new ArrayList<>();
            boolean eof = false;
            try {
                while (true) {
                    int value = in.read();
                    if (value < 0) { eof = true; break; }
                    result.add(value);
                }
            } catch (SocketTimeoutException ignored) {
            } catch (java.net.SocketException reset) {
                eof = true;
            }
            return result + (eof ? " EOF" : "");
        }
        @Override public void close() throws Exception { socket.close(); }
    }

    interface Scenario { void run(Side side, int port, List<String> trace) throws Exception; }

    static void clockEdge(Side side, List<String> trace, String label) throws Exception {
        trace.add(label + " clk=1: " + side.set("clk", 1));
        trace.add(label + " clk=0: " + side.set("clk", 0));
    }

    static void readAll(Side side, List<String> trace, int count) throws Exception {
        trace.add("rd=1: " + side.set("rd", 1));
        for (int index = 0; index < count; index++) clockEdge(side, trace, "read " + index);
        trace.add("rd=0: " + side.set("rd", 0));
    }

    static void write(Side side, List<String> trace, int... values) throws Exception {
        trace.add("wr=1: " + side.set("wr", 1));
        for (int value : values) {
            trace.add("in=" + value + ": " + side.set("in", value));
            clockEdge(side, trace, "write " + value);
        }
        trace.add("wr=0: " + side.set("wr", 0));
    }

    static final class Case {
        final String name; final boolean telnetMode; final int buffer; final String trigger; final Scenario scenario;
        Case(String name, boolean telnetMode, int buffer, String trigger, Scenario scenario) {
            this.name = name; this.telnetMode = telnetMode; this.buffer = buffer; this.trigger = trigger; this.scenario = scenario;
        }
    }

    static final List<Case> CASES = List.of(
        new Case("raw-read-write", false, 1024, "rising", (side, port, trace) -> {
            trace.add("initial: " + side.probe());
            try (Client client = new Client(port)) {
                side.sync();
                trace.add("client receives on connect: " + client.read());
                client.send('A', 'B', 'C');
                side.sync();
                trace.add("clk=0 after bytes: " + side.set("clk", 0));
                readAll(side, trace, 4);
                write(side, trace, 'H', 'I', 0, 255);
                side.sync();
                trace.add("client receives: " + client.read());
            }
        }),
        new Case("falling-edge", false, 1024, "falling", (side, port, trace) -> {
            try (Client client = new Client(port)) {
                side.sync();
                client.send('x', 'y');
                side.sync();
                trace.add("clk=0 after bytes: " + side.set("clk", 0));
                readAll(side, trace, 2);
                write(side, trace, 'Q');
                side.sync();
                trace.add("client receives: " + client.read());
            }
        }),
        new Case("telnet-mode", true, 1024, "rising", (side, port, trace) -> {
            try (Client client = new Client(port)) {
                side.sync();
                trace.add("client receives on connect: " + client.read());
                // IAC DO ECHO, IAC WILL SGA split, then IAC IAC: Evolution drops the two bytes after each IAC.
                client.send('A', 255, 253, 1, 'B');
                client.send(255);
                side.sync();
                client.send(251, 3, 'C', 255, 255, 'D', 'E');
                side.sync();
                trace.add("clk=0 after bytes: " + side.set("clk", 0));
                readAll(side, trace, 4);
                write(side, trace, 255, 'z');
                side.sync();
                trace.add("client receives: " + client.read());
            }
        }),
        new Case("input-buffer-limit", false, 2, "rising", (side, port, trace) -> {
            try (Client client = new Client(port)) {
                side.sync();
                client.send(1, 2, 3, 4);
                side.sync();
                trace.add("clk=0 after bytes: " + side.set("clk", 0));
                readAll(side, trace, 3);
                client.send(5, 6, 7);
                side.sync();
                trace.add("clk=0 after more bytes: " + side.set("clk", 0));
                readAll(side, trace, 3);
            }
        }),
        new Case("write-without-peer", false, 1024, "rising", (side, port, trace) -> {
            write(side, trace, 'Z');
            try (Client client = new Client(port)) {
                side.sync();
                trace.add("late client receives: " + client.read());
                write(side, trace, 'Y');
                side.sync();
                trace.add("client receives: " + client.read());
            }
        }),
        new Case("second-peer", false, 1024, "rising", (side, port, trace) -> {
            try (Client first = new Client(port)) {
                side.sync();
                first.send('1');
                side.sync();
                try (Client second = new Client(port)) {
                    side.sync();
                    write(side, trace, 'W');
                    side.sync();
                    trace.add("first client receives: " + first.read());
                    trace.add("second client receives: " + second.read());
                    try { first.send('p'); } catch (Exception closed) { trace.add("first client send failed"); }
                    second.send('q');
                    side.sync();
                    trace.add("clk=0 after bytes: " + side.set("clk", 0));
                    readAll(side, trace, 3);
                }
            }
        }),
        new Case("peer-reset", false, 1024, "rising", (side, port, trace) -> {
            Client first = new Client(port);
            side.sync();
            first.send('r');
            side.sync();
            first.reset();
            side.sync();
            try (Client second = new Client(port)) {
                side.sync();
                second.send('s');
                side.sync();
                trace.add("clk=0 after bytes: " + side.set("clk", 0));
                readAll(side, trace, 3);
                write(side, trace, 'T');
                side.sync();
                trace.add("second client receives: " + second.read());
            } catch (java.net.ConnectException refused) {
                trace.add("second client: connection refused");
            }
        }),
        new Case("reconnect-after-close", false, 1024, "rising", (side, port, trace) -> {
            try (Client first = new Client(port)) {
                side.sync();
                first.send('a');
                side.sync();
            }
            side.sync();
            try (Client second = new Client(port)) {
                side.sync();
                second.send('b');
                side.sync();
                trace.add("clk=0 after bytes: " + side.set("clk", 0));
                readAll(side, trace, 3);
                write(side, trace, 'R');
                side.sync();
                trace.add("second client receives: " + second.read());
            }
        })
    );

    static final java.util.Random PORTS = new java.util.Random();

    /** A free port below the OS ephemeral range, so stale clients of recycled ephemeral ports do not connect. */
    /**
     * What a synchronous circuit observes: the outputs just before each read or write rising edge (Evolution and
     * Mobile settle the edge at different moments, see README), the bytes each socket client saw, and failures.
     */
    static List<String> normalized(List<String> trace) {
        List<String> result = new ArrayList<>();
        for (int line = 0; line < trace.size(); line++) {
            String value = trace.get(line);
            if (value.contains(" clk=1: ") && line > 0) {
                String before = trace.get(line - 1);
                result.add(value.substring(0, value.indexOf(" clk=1: ")) + " sampled " + before.substring(before.indexOf(": ") + 2));
            } else if (value.contains("client") || value.startsWith("FAILURE")) {
                result.add(value);
            }
        }
        return result;
    }

    static int freePort() throws Exception {
        while (true) {
            int port = 20_000 + PORTS.nextInt(10_000);
            try (ServerSocket socket = new ServerSocket(port)) { return port; } catch (java.io.IOException used) { }
        }
    }

    public static void main(String[] args) throws Exception {
        String repo = args[0];
        File out = new File(args[1]);
        out.mkdirs();
        StringBuilder json = new StringBuilder("{\n  \"cases\": [\n");
        int same = 0, different = 0, sampledSame = 0, sampledDifferent = 0;
        String evolutionBind = null, companionBind = null;
        for (int index = 0; index < CASES.size(); index++) {
            Case c = CASES.get(index);
            Map<String, List<String>> traces = new LinkedHashMap<>();
            for (String sideName : List.of("evolution", "mobile-companion", "mobile-jvm")) {
                int port = freePort();
                File file = File.createTempFile("telnet-" + c.name + "-", ".circ");
                file.deleteOnExit();
                Files.writeString(file.toPath(), circ(port, c.telnetMode, c.buffer, c.trigger));
                List<String> trace = new ArrayList<>();
                Side side = switch (sideName) {
                    case "evolution" -> new EvolutionSide(file);
                    case "mobile-companion" -> new MobileSide(file, new CompanionNetwork(repo), port, c.telnetMode, c.buffer);
                    default -> new MobileSide(file, new JvmNetwork(), port, c.telnetMode, c.buffer);
                };
                try {
                    Thread.sleep(WARMUP_MS);
                    int stray = side.drain();
                    if (stray > 0) System.err.println(c.name + " " + sideName + ": discarded " + stray + " bytes from pre-scenario peers");
                    if (side instanceof EvolutionSide e && evolutionBind == null) evolutionBind = e.bindAddress();
                    if (side instanceof MobileSide m && m.network instanceof CompanionNetwork n && companionBind == null) companionBind = n.bindAddress();
                    c.scenario.run(side, port, trace);
                } catch (Exception failure) {
                    trace.add("FAILURE " + failure);
                } finally {
                    side.close();
                }
                traces.put(sideName, trace);
            }
            List<String> reference = traces.get("evolution");
            List<String> normalizedReference = normalized(reference);
            StringBuilder log = new StringBuilder("case: " + c.name + " telnetMode=" + c.telnetMode + " buflen=" + c.buffer
                + " trigger=" + c.trigger + "\n");
            json.append("    {\"case\": \"").append(c.name).append("\"");
            for (var entry : traces.entrySet()) {
                log.append("\n=== ").append(entry.getKey()).append('\n');
                for (int line = 0; line < entry.getValue().size(); line++) {
                    String value = entry.getValue().get(line);
                    String mark = entry.getKey().equals("evolution") ? "  " : (line < reference.size() && reference.get(line).equals(value) ? "  " : "! ");
                    log.append(mark).append(value).append('\n');
                }
                if (!entry.getKey().equals("evolution")) {
                    boolean equal = entry.getValue().equals(reference);
                    List<String> normalized = normalized(entry.getValue());
                    boolean sampledEqual = normalized.equals(normalizedReference);
                    if (equal) same++; else different++;
                    if (sampledEqual) sampledSame++; else sampledDifferent++;
                    json.append(", \"").append(entry.getKey()).append("\": \"").append(equal ? "SAME" : "DIFF")
                        .append("\", \"").append(entry.getKey()).append("-sampled\": \"").append(sampledEqual ? "SAME" : "DIFF").append("\"");
                    log.append("--- sampled at rising edges and socket bytes: ").append(sampledEqual ? "SAME" : "DIFF").append('\n');
                    for (int line = 0; line < normalized.size(); line++) {
                        String value = normalized.get(line);
                        boolean ok = line < normalizedReference.size() && normalizedReference.get(line).equals(value);
                        log.append(ok ? "  " : "! ").append(value).append('\n');
                    }
                }
            }
            json.append(", \"steps\": ").append(reference.size()).append('}').append(index + 1 < CASES.size() ? ",\n" : "\n");
            Files.writeString(new File(out, "telnet-" + c.name + ".txt").toPath(), log.toString());
            System.out.print(log);
            System.out.println();
        }
        json.append("  ],\n  \"bindAddress\": {\"evolution\": \"").append(evolutionBind).append("\", \"mobile-companion\": \"")
            .append(companionBind).append("\"},\n  \"same\": ").append(same).append(",\n  \"different\": ").append(different)
            .append(",\n  \"sampledSame\": ").append(sampledSame).append(",\n  \"sampledDifferent\": ").append(sampledDifferent).append("\n}\n");
        Files.writeString(new File(out, "telnet-results.json").toPath(), json.toString());
        System.out.println("bind address: evolution=" + evolutionBind + " mobile-companion=" + companionBind);
        System.out.println("Telnet: " + same + " Mobile traces equal Evolution step by step, " + different + " differ; sampled at"
            + " rising edges with socket bytes: " + sampledSame + " equal, " + sampledDifferent + " differ");
        System.exit(0);
    }
}
