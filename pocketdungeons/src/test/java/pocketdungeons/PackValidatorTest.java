package pocketdungeons;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.stream.Stream;

/**
 * M72: headless regression for {@link PackValidator}.
 *
 * <p>The live validation runs against a real server (it builds a
 * {@link ContentSnapshot} from the resource manager), so it is exercised by
 * the integration test's dedicated-server run, not here. This test covers the
 * pure-JDK surface that does not need a server:
 * <ul>
 *   <li>The {@link PackValidator.Finding} seed rule (a static finding carries
 *       no seed; a plan finding does, and is replayable).</li>
 *   <li>The safe-replace decision: a fresh destination proceeds; an existing
 *       one refuses unless the caller passes an explicit confirm.</li>
 *   <li>The pack.mcmeta this build writes uses the game's current data pack
 *       format (the 26.2 pack format for this build), verified by booting the
 *       game's constants the same way the other headless tests do.</li>
 *   <li>The starter pack resource tree is bundled and reachable on the
 *       classpath, which holds in both the development (file:) and packaged
 *       jar (jar:) export paths.</li>
 * </ul>
 */
public class PackValidatorTest {

    private static final List<String> STARTER_FILES = List.of(
            "/pack_starter/README.txt",
            "/pack_starter/data/starter/dungeon_room/example_room.json",
            "/pack_starter/data/starter/dungeon_theme/example_theme.json",
            "/pack_starter/data/starter/dungeon_adventure/example_theme.json",
            "/pack_starter/data/starter/dungeon_affix/example_affix.json",
            "/pack_starter/data/starter/dungeon_bag/example_bag.json",
            "/pack_starter/data/starter/dungeon_role/example_role.json",
            "/pack_starter/data/starter/cube_recipe/example_recipe.json",
            "/pack_starter/data/starter/diary/example_diary.json",
            "/pack_starter/data/starter/anomaly_room/example_anomaly.json");

    public static void main(String[] args) {
        net.minecraft.SharedConstants.setVersion(net.minecraft.DetectedVersion.BUILT_IN);
        net.minecraft.server.Bootstrap.bootStrap();

        testFindingSeedRule();
        testSafeReplaceDecision();
        testPackMetaFormat();
        testStarterResourcesBundled();

        System.out.println("PackValidatorTest passed");
    }

    private static void testFindingSeedRule() {
        PackValidator.Finding staticFinding = new PackValidator.Finding(
                "starter:example_room", "parse", "rejected at load");
        check(!staticFinding.hasSeed(), "a static finding carries no seed");
        check(staticFinding.seed() == PackValidator.NO_SEED, "static finding seed is NO_SEED");

        PackValidator.Finding planFinding = new PackValidator.Finding(
                "plan", "shape", "seed 0 exhausted the budget", 0L);
        check(planFinding.hasSeed(), "a plan finding carries its seed");
        check(planFinding.seed() == 0L, "plan finding seed is preserved");
    }

    private static void testSafeReplaceDecision() {
        check(PackValidator.decideReplace(false, false) == PackValidator.ReplaceDecision.PROCEED,
                "fresh destination proceeds without confirm");
        check(PackValidator.decideReplace(false, true) == PackValidator.ReplaceDecision.PROCEED,
                "fresh destination proceeds with confirm");
        check(PackValidator.decideReplace(true, false) == PackValidator.ReplaceDecision.REFUSE,
                "existing destination refuses without confirm");
        check(PackValidator.decideReplace(true, true) == PackValidator.ReplaceDecision.PROCEED,
                "existing destination proceeds with explicit confirm (after backup)");
    }

    /**
     * The pack.mcmeta written by a release jar uses the game's current data
     * pack format. For this build that is the 26.2 format, so the written
     * pack_format must equal {@code SharedConstants.DATA_PACK_FORMAT_MAJOR}
     * and be a positive, plausible number.
     */
    private static void testPackMetaFormat() {
        int format = PackValidator.packFormat();
        check(format == net.minecraft.SharedConstants.DATA_PACK_FORMAT_MAJOR,
                "packFormat() matches SharedConstants.DATA_PACK_FORMAT_MAJOR");
        check(format > 0, "pack format is positive");

        String json = PackValidator.packMetaJson(format, "Pocket Dungeons starter pack");
        JsonObject root = JsonParser.parseString(json).getAsJsonObject();
        JsonObject pack = root.getAsJsonObject("pack");
        check(pack.get("pack_format").getAsInt() == format,
                "pack.mcmeta pack_format matches the written format");
        check("Pocket Dungeons starter pack".equals(pack.get("description").getAsString()),
                "pack.mcmeta description is preserved");
    }

    /**
     * The starter pack files are bundled into the jar under /pack_starter and
     * reachable on the classpath. getResourceAsStream resolves in both the
     * development environment (file: resources on disk) and a packaged jar
     * (jar: resources), so this is the packaged-jar and development-resource
     * export presence check.
     */
    private static void testStarterResourcesBundled() {
        for (String path : STARTER_FILES) {
            try (var in = PackValidator.class.getResourceAsStream(path)) {
                if (in == null) {
                    throw new AssertionError("starter resource missing on classpath: " + path);
                }
                String text = new BufferedReader(
                        new InputStreamReader(in, StandardCharsets.UTF_8)).readLine();
                if (text == null || text.isBlank()) {
                    throw new AssertionError("starter resource is empty: " + path);
                }
            } catch (Exception e) {
                throw new AssertionError("could not read starter resource " + path + ": " + e);
            }
        }
        // One example file parses as JSON, so the starter ships valid content.
        try (var in = PackValidator.class.getResourceAsStream(
                "/pack_starter/data/starter/dungeon_room/example_room.json")) {
            if (in == null) {
                throw new AssertionError("example_room.json missing");
            }
            JsonObject room = JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8))
                    .getAsJsonObject();
            check(room.has("template"), "starter room names a template");
            check(room.has("roles"), "starter room declares roles");
        } catch (Exception e) {
            throw new AssertionError("starter room is not valid JSON: " + e);
        }
    }

    private static void check(boolean condition, String what) {
        if (!condition) {
            throw new AssertionError(what);
        }
    }
}
