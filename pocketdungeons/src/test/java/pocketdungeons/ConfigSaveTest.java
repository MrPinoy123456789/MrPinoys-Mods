package pocketdungeons;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Regression for the config round trip: an operator's edit to
 * {@code pocketdungeons.json} survives a reboot. {@link PocketDungeonsConfig#load}
 * used to apply the file and then overwrite it with the defaults, so every
 * edit lasted exactly one boot.
 *
 * <p>Drives the real {@code load} against a temporary directory, twice, the
 * way two boots would, then checks both the values in memory and what is on
 * disk. Headless; the registry bootstrap is only there because the logger's
 * owning class carries a dimension key.
 */
public class ConfigSaveTest {

    public static void main(String[] args) throws Exception {
        net.minecraft.SharedConstants.setVersion(net.minecraft.DetectedVersion.BUILT_IN);
        net.minecraft.server.Bootstrap.bootStrap();

        testEditsSurviveTwoBoots();
        testMissingKeysAreAddedAndRetiredKeysDropped();
        testInvalidValueIsKeptInTheFile();
        testCompleteFileIsNotRewritten();
        testMergeIsPure();

        // Leave the static config as a fresh boot would have it for whatever runs next.
        Path fresh = Files.createTempDirectory("pd-config-reset");
        PocketDungeonsConfig.load(fresh);
        System.out.println("ConfigSaveTest passed");
    }

    private static void testEditsSurviveTwoBoots() throws Exception {
        Path dir = Files.createTempDirectory("pd-config");
        Path file = dir.resolve("pocketdungeons.json");
        // First boot writes the defaults.
        PocketDungeonsConfig.load(dir);
        check(Files.exists(file), "first boot writes the file");

        JsonObject edited = read(file);
        edited.addProperty("floorsPerSafeVisit", 5);
        edited.addProperty("timedOutDepletion", 4);
        edited.addProperty("payoutCommand", "say %player% banked");
        write(file, edited);

        for (int boot = 2; boot <= 3; boot++) {
            PocketDungeonsConfig.load(dir);
            check(PocketDungeonsConfig.floorsPerSafeVisit() == 5, "floorsPerSafeVisit applied on boot " + boot);
            check(PocketDungeonsConfig.timedOutDepletion() == 4, "timedOutDepletion applied on boot " + boot);
            check(PocketDungeonsConfig.payoutCommand().equals("say %player% banked"),
                    "payoutCommand applied on boot " + boot);
            JsonObject onDisk = read(file);
            check(onDisk.get("floorsPerSafeVisit").getAsInt() == 5, "floorsPerSafeVisit kept on disk after boot " + boot);
            check(onDisk.get("timedOutDepletion").getAsInt() == 4, "timedOutDepletion kept on disk after boot " + boot);
        }
    }

    private static void testMissingKeysAreAddedAndRetiredKeysDropped() throws Exception {
        Path dir = Files.createTempDirectory("pd-config");
        Path file = dir.resolve("pocketdungeons.json");
        JsonObject partial = new JsonObject();
        partial.addProperty("floorsPerSafeVisit", 4);
        partial.addProperty("timerBaseSeconds", 300);      // retired
        partial.addProperty("someFutureKey", "kept");       // unknown to this version
        write(file, partial);

        PocketDungeonsConfig.load(dir);
        JsonObject onDisk = read(file);
        check(onDisk.get("floorsPerSafeVisit").getAsInt() == 4, "the operator's value is kept");
        check(onDisk.has("keystoneMaxLevel") && onDisk.get("keystoneMaxLevel").getAsInt() == 100,
                "a missing key is added at its default");
        check(!onDisk.has("timerBaseSeconds"), "a retired key is dropped");
        check(onDisk.has("someFutureKey"), "an unknown key is left for the operator to remove");
        check(PocketDungeonsConfig.floorsPerSafeVisit() == 4, "the partial file still applies");
    }

    private static void testInvalidValueIsKeptInTheFile() throws Exception {
        Path dir = Files.createTempDirectory("pd-config");
        Path file = dir.resolve("pocketdungeons.json");
        PocketDungeonsConfig.load(dir);
        JsonObject edited = read(file);
        edited.addProperty("floorsPerSafeVisit", 0); // must be >= 1
        write(file, edited);

        PocketDungeonsConfig.load(dir);
        check(PocketDungeonsConfig.floorsPerSafeVisit() == 3, "an invalid value falls back to the default in memory");
        check(read(file).get("floorsPerSafeVisit").getAsInt() == 0,
                "the invalid value stays in the file so the next boot reports it again");
    }

    private static void testCompleteFileIsNotRewritten() throws Exception {
        Path dir = Files.createTempDirectory("pd-config");
        Path file = dir.resolve("pocketdungeons.json");
        PocketDungeonsConfig.load(dir);
        // An operator's own formatting: compact, one line. A complete file is
        // not touched, so the formatting survives too.
        JsonObject complete = read(file);
        complete.addProperty("floorsPerSafeVisit", 2);
        String compact = complete.toString();
        Files.writeString(file, compact, StandardCharsets.UTF_8);

        PocketDungeonsConfig.load(dir);
        check(Files.readString(file, StandardCharsets.UTF_8).equals(compact),
                "a complete file is left byte for byte as the operator wrote it");
        check(PocketDungeonsConfig.floorsPerSafeVisit() == 2, "and still applies");
    }

    private static void testMergeIsPure() {
        JsonObject parsed = new JsonObject();
        parsed.addProperty("slotPitch", 4096);
        JsonObject merged = PocketDungeonsConfig.effectiveForSave(parsed);
        check(merged != null, "an incomplete file needs a rewrite");
        check(merged.get("slotPitch").getAsInt() == 4096, "the merge keeps the set value");
        check(PocketDungeonsConfig.effectiveForSave(merged) == null, "the merged result is already complete");
    }

    private static JsonObject read(Path file) throws Exception {
        return JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8)).getAsJsonObject();
    }

    private static void write(Path file, JsonObject json) throws Exception {
        Files.writeString(file, json.toString(), StandardCharsets.UTF_8);
    }

    private static void check(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
