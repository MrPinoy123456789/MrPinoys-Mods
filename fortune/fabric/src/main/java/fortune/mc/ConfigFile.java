package fortune.mc;

import fortune.core.Machine;
import fortune.core.Settings;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * {@code config/fortune/fortune.json}. Written with the defaults on first start. A file that cannot be read, or that
 * fails the machine's checks (a paytable that does not sum to 100 percent, a table paying Coin), is refused whole and
 * the previous machine stays in use.
 */
final class ConfigFile {

    private ConfigFile() {
    }

    static Path path(Path configDir) {
        return configDir.resolve("fortune.json");
    }

    /** The machine from the file, or the defaults when the file is missing. Never null. */
    static Machine loadOrDefault(Path configDir) {
        Machine loaded = load(configDir, null);
        return loaded != null ? loaded : new Machine(new Settings());
    }

    /**
     * The machine from the file, writing the defaults first if there is none. Returns {@code fallback} and logs the
     * reason when the file is refused.
     */
    static Machine load(Path configDir, Machine fallback) {
        Path file = path(configDir);
        try {
            if (!Files.exists(file)) {
                Settings defaults = new Settings();
                AtomicJson.write(file, defaults);
                return new Machine(defaults);
            }
            Settings settings = AtomicJson.read(file, Settings.class);
            if (settings == null) settings = new Settings();
            return new Machine(settings);
        } catch (Exception e) {
            FortuneMod.LOG.error("fortune.json was refused, keeping the {} settings: {}",
                    fallback == null ? "default" : "previous", e.getMessage());
            return fallback;
        }
    }

    /** A plain reason the file would be refused, or null when it is fine. For /fortune admin reload. */
    static String problem(Path configDir) {
        try {
            Settings settings = AtomicJson.read(path(configDir), Settings.class);
            new Machine(settings == null ? new Settings() : settings);
            return null;
        } catch (Exception e) {
            return e.getMessage();
        }
    }
}
