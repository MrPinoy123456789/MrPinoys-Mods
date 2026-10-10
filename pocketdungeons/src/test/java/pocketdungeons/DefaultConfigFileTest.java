package pocketdungeons;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * The shipped {@code config/pocketdungeons.default.json} is what an operator copies, so it must be exactly what
 * the code writes for a first boot (audit 2026-10-10: it had drifted to 27 keys against 100, seven wrong values and
 * five retired keys). Loads a fresh directory, which writes the defaults, and compares.
 *
 * <p>To regenerate the shipped file after a default changes, run the suite once with the environment variable
 * {@code PD_WRITE_DEFAULTS=1}.
 */
public class DefaultConfigFileTest {

    public static void main(String[] args) throws Exception {
        net.minecraft.SharedConstants.setVersion(net.minecraft.DetectedVersion.BUILT_IN);
        net.minecraft.server.Bootstrap.bootStrap();

        Path fresh = Files.createTempDirectory("pd-default-config");
        PocketDungeonsConfig.load(fresh);
        Path written = fresh.resolve("pocketdungeons.json");
        String generated = Files.readString(written, StandardCharsets.UTF_8);

        Path shipped = Path.of("config", "pocketdungeons.default.json");
        if ("1".equals(System.getenv("PD_WRITE_DEFAULTS"))) {
            Files.writeString(shipped, generated, StandardCharsets.UTF_8);
            System.out.println("DefaultConfigFileTest wrote " + shipped);
        }
        JsonObject expected = JsonParser.parseString(generated).getAsJsonObject();
        JsonObject actual = JsonParser.parseString(Files.readString(shipped, StandardCharsets.UTF_8)).getAsJsonObject();
        if (!expected.equals(actual)) {
            for (String key : expected.keySet()) {
                if (!actual.has(key)) {
                    throw new AssertionError("config/pocketdungeons.default.json is missing " + key);
                }
                if (!expected.get(key).equals(actual.get(key))) {
                    throw new AssertionError("config/pocketdungeons.default.json differs at " + key + ": file "
                            + actual.get(key) + ", code " + expected.get(key));
                }
            }
            for (String key : actual.keySet()) {
                if (!expected.has(key)) {
                    throw new AssertionError("config/pocketdungeons.default.json has a key the code does not write: " + key);
                }
            }
            throw new AssertionError("config/pocketdungeons.default.json differs from the code defaults");
        }
        // Leave the static config as a fresh boot would have it for whatever runs next.
        PocketDungeonsConfig.load(Files.createTempDirectory("pd-config-reset"));
        System.out.println("DefaultConfigFileTest passed");
    }
}
