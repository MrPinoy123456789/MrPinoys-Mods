package chartercurrency;

import chartercurrency.core.KingdomSource;
import chartercurrency.core.ManualKingdomSource;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Which {@link KingdomSource} is answering right now. The manual stand-in is always
 * built (its state lives in {@code kingdoms.json}); when Charter binds a view, the
 * Charter source takes over and the manual one is ignored.
 */
final class KingdomRuntime {

    /** The shape of {@code kingdoms.json}. Used only by the manual stand-in. */
    public static final class File {
        public String source = "manual";
        public Map<String, ManualKingdomSource.Kingdom> kingdoms = new LinkedHashMap<>();

        public File() {}
    }

    private final ManualKingdomSource manual;
    private volatile KingdomSource active;
    private volatile String activeName = "manual";

    KingdomRuntime(ManualKingdomSource manual) {
        this.manual = manual;
        this.active = manual;
    }

    KingdomSource source() {
        return active;
    }

    ManualKingdomSource manual() {
        return manual;
    }

    /** True while the manual stand-in is answering, so manual edit commands apply. */
    boolean manualActive() {
        return active == manual;
    }

    String activeName() {
        return activeName;
    }

    /** Switch to another source (Charter), or back to the manual one. */
    void use(KingdomSource source, String name) {
        this.active = source == null ? manual : source;
        this.activeName = source == null ? "manual" : name;
    }
}
