package charter.mc;

import charter.config.Knobs;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static charter.mc.FabricSuite.check;

/** Read-or-create for {@code charter.json} against a real temporary folder. */
final class ConfigTest {

    private ConfigTest() {
    }

    static void run() throws Exception {
        missingFileIsWrittenWithDefaults();
        missingKeysKeepTheirDefaults();
        brokenFileRunsOnDefaultsAndIsLeftAlone();
        unusableValuesRunOnDefaultsAndAreLeftAlone();
    }

    static void missingFileIsWrittenWithDefaults() throws Exception {
        Path dir = Files.createTempDirectory("charter-config").resolve("charter");
        List<String> info = new ArrayList<>();
        Knobs knobs = CharterConfig.readOrCreate(dir, info::add, s -> { });
        Path file = dir.resolve(CharterConfig.FILE_NAME);
        check("config: a missing file is created", Files.exists(file));
        check("config: defaults are returned", knobs.keepPriceDiamonds == 64 && knobs.timeScale == 1.0);
        String text = Files.readString(file, StandardCharsets.UTF_8);
        check("config: the written file names every knob", text.contains("\"granaryMaxChests\"") && text.contains("\"labourTerms\""));
        check("config: writing the defaults is logged", info.stream().anyMatch(s -> s.contains("Wrote default config")));
        Knobs again = CharterConfig.readOrCreate(dir, s -> { }, s -> { });
        check("config: the written file reads back to the same values", again.storageLimitHours == 84 && again.campCapacityClaims == 4);
    }

    static void missingKeysKeepTheirDefaults() throws Exception {
        Path dir = Files.createTempDirectory("charter-config");
        Files.writeString(dir.resolve(CharterConfig.FILE_NAME), "{ \"timeScale\": 24, \"keepPriceDiamonds\": 10 }");
        Knobs knobs = CharterConfig.readOrCreate(dir, s -> { }, s -> { });
        check("config: a value in the file is used", knobs.timeScale == 24 && knobs.keepPriceDiamonds == 10);
        check("config: a key missing from the file keeps its default", knobs.storageLimitHours == 84 && knobs.drainPerMouthPerHour == 10);
        check("config: a missing list keeps its default", knobs.claimDimensions.equals(List.of("minecraft:overworld")));
    }

    static void brokenFileRunsOnDefaultsAndIsLeftAlone() throws Exception {
        Path dir = Files.createTempDirectory("charter-config");
        Path file = dir.resolve(CharterConfig.FILE_NAME);
        String broken = "{ \"timeScale\": 24, oops";
        Files.writeString(file, broken);
        List<String> warn = new ArrayList<>();
        Knobs knobs = CharterConfig.readOrCreate(dir, s -> { }, warn::add);
        check("config: a broken file falls back to defaults", knobs.timeScale == 1.0);
        check("config: a broken file is not overwritten", Files.readString(file).equals(broken));
        check("config: a broken file is reported", !warn.isEmpty());
    }

    static void unusableValuesRunOnDefaultsAndAreLeftAlone() throws Exception {
        Path dir = Files.createTempDirectory("charter-config");
        Path file = dir.resolve(CharterConfig.FILE_NAME);
        String bad = "{ \"timeScale\": 0, \"starvationThreshold\": 2 }";
        Files.writeString(file, bad);
        List<String> warn = new ArrayList<>();
        Knobs knobs = CharterConfig.readOrCreate(dir, s -> { }, warn::add);
        check("config: unusable values fall back to defaults", knobs.timeScale == 1.0 && knobs.starvationThreshold == 0.25);
        check("config: each unusable value is named", warn.stream().anyMatch(s -> s.contains("timeScale")) && warn.stream().anyMatch(s -> s.contains("starvationThreshold")));
        check("config: a file with unusable values is not overwritten", Files.readString(file).equals(bad));
    }
}
