import java.lang.reflect.Proxy;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.Clip;
import javax.sound.sampled.Line;
import javax.sound.sampled.Mixer;
import javax.sound.sampled.spi.MixerProvider;

/**
 * A Java Sound mixer that records each clip instead of playing it. `run.sh` registers it as a
 * service and selects it with the `javax.sound.sampled.Clip` property, so Evolution's Buzzer
 * thread hands over the exact PCM buffer it builds, without an audio device.
 */
public class OracleMixerProvider extends MixerProvider {
    public record Capture(AudioFormat format, byte[] bytes) {}

    public static final BlockingQueue<Capture> CAPTURES = new LinkedBlockingQueue<>();
    private static final Mixer.Info INFO = new Mixer.Info("LogisimOracleCapture", "logisim-mobile",
        "Records clips for the output-device oracle", "1") {};
    private static final Mixer MIXER = (Mixer) Proxy.newProxyInstance(Mixer.class.getClassLoader(),
        new Class<?>[] {Mixer.class}, (proxy, method, args) -> switch (method.getName()) {
            case "getMixerInfo" -> INFO;
            case "isLineSupported" -> Clip.class.isAssignableFrom(((Line.Info) args[0]).getLineClass());
            case "getLine" -> clip();
            case "getMaxLines" -> AudioSystem.NOT_SPECIFIED;
            case "getSourceLineInfo", "getTargetLineInfo" -> new Line.Info[] {new Line.Info(Clip.class)};
            case "getSourceLines", "getTargetLines" -> new Line[0];
            case "hashCode" -> System.identityHashCode(proxy);
            case "equals" -> proxy == args[0];
            case "toString" -> "OracleMixer";
            default -> defaultValue(method.getReturnType());
        });

    private static Clip clip() {
        return (Clip) Proxy.newProxyInstance(Clip.class.getClassLoader(), new Class<?>[] {Clip.class},
            (proxy, method, args) -> {
                if (method.getName().equals("open") && args != null && args.length == 1
                        && args[0] instanceof AudioInputStream stream) {
                    CAPTURES.add(new Capture(stream.getFormat(), stream.readAllBytes()));
                    return null;
                }
                return switch (method.getName()) {
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == args[0];
                    case "toString" -> "OracleClip";
                    default -> defaultValue(method.getReturnType());
                };
            });
    }

    private static Object defaultValue(Class<?> type) {
        if (type == boolean.class) return false;
        if (type == int.class) return 0;
        if (type == long.class) return 0L;
        if (type == float.class) return 0f;
        if (type == double.class) return 0d;
        return null;
    }

    @Override
    public Mixer.Info[] getMixerInfo() {
        return new Mixer.Info[] {INFO};
    }

    @Override
    public Mixer getMixer(Mixer.Info info) {
        if (info == null || info.equals(INFO)) return MIXER;
        throw new IllegalArgumentException("Unknown mixer " + info);
    }
}
