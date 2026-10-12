package pocketdungeons;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;

/**
 * Affix workshop 2026-10-11 row 11: the Blackout affix. The {@code dark_cells} operation is bounded at
 * load, the shipped file parses, and the cell choice is deterministic from the plan seed, never touches the
 * entrance, the terminal or a room that must stay lit, and never darkens a lit room.
 */
public class BlackoutTest {

    private static final Path AFFIXES = Paths.get("src/main/resources/data/pocketdungeons/dungeon_affix");

    public static void main(String[] args) throws IOException {
        manifest();
        cells();
        light();
        System.out.println("BlackoutTest passed");
    }

    private static AffixDefinition parse(String json) {
        return AffixMeta.fromJson(JsonParser.parseString(json).getAsJsonObject(), "pocketdungeons:test");
    }

    private static String affix(String effects) {
        return "{\"label\": \"Test\", \"blurb\": \"Test.\", \"effects\": " + effects + "}";
    }

    private static void rejected(String effects, String why) {
        try {
            parse(affix(effects));
        } catch (IllegalArgumentException expected) {
            return;
        }
        throw new AssertionError(why);
    }

    private static void manifest() throws IOException {
        JsonObject file;
        try (Reader reader = Files.newBufferedReader(AFFIXES.resolve("blackout.json"))) {
            file = JsonParser.parseReader(reader).getAsJsonObject();
        }
        AffixDefinition blackout = AffixMeta.fromJson(file, AffixIds.BLACKOUT);
        check(blackout.label.equals("Blackout") && blackout.shortName.equals("Blkt"), "label and short name");
        check(blackout.order == 11 && blackout.minLevel == 0 && blackout.weight == 1, "order after Restless, level 0, weight 1");
        check(blackout.effects.darkCells > 0.0 && blackout.effects.darkCells <= 1.0, "a fraction of cells");
        check(blackout.effects.hasAnyOperation(), "it bends a rule");
        check(AffixIds.resolve("blackout").equals(AffixIds.BLACKOUT), "the bare name resolves");
        check(AffixIds.BUILT_IN_ORDER.contains(AffixIds.BLACKOUT), "built in, so the coverage gate wants it");
        check(Keystone.colourOf(AffixIds.BLACKOUT) != Keystone.colourOf("pocketdungeons:unknown"), "it has its own colour");

        check(parse(affix("{\"dark_cells\": 1.0}")).effects.darkCells == 1.0, "all cells is allowed");
        check(parse(affix("{\"dark_cells\": 0.5}")).effects.darkCells == 0.5, "half is allowed");
        rejected("{\"dark_cells\": 1.01}", "more than all the cells");
        rejected("{\"dark_cells\": -0.1}", "a negative fraction");
        rejected("{\"dark_cells\": 0}", "zero darkens nothing, so the affix bends nothing");

        // The Restless fields keep their names and keep loading.
        AffixDefinition restless;
        try (Reader reader = Files.newBufferedReader(AFFIXES.resolve("restless.json"))) {
            restless = AffixMeta.fromJson(JsonParser.parseReader(reader).getAsJsonObject(), AffixIds.RESTLESS);
        }
        check(restless.effects.undeadRiseChance == 0.3 && restless.effects.undeadRiseChanceOminous == 0.5,
                "undead_rise_chance and undead_rise_chance_ominous still load");
        check(restless.blurb.contains("any fallen mob"), "the blurb says every mob");
    }

    private static List<PlanCell> grid(int w, int h) {
        List<PlanCell> cells = new ArrayList<>();
        for (int x = 0; x < w; x++) {
            for (int z = 0; z < h; z++) {
                cells.add(new PlanCell(x, z));
            }
        }
        return cells;
    }

    private static void cells() {
        List<PlanCell> cells = grid(5, 4);
        PlanCell entrance = new PlanCell(0, 0);
        PlanCell terminal = new PlanCell(4, 3);
        Set<PlanCell> lit = Set.of(new PlanCell(1, 1), new PlanCell(2, 2), new PlanCell(3, 0));

        Set<PlanCell> picked = DungeonLight.blackoutCells(cells, entrance, terminal, lit::contains, 0.5, 42L);
        check(picked.size() == 8, "half of the 15 eligible cells (20 less entrance, terminal and 3 lit), rounded: " + picked.size());
        check(!picked.contains(entrance) && !picked.contains(terminal), "never the entrance or the terminal");
        for (PlanCell cell : lit) {
            check(!picked.contains(cell), "a room that requires light stays lit: " + cell);
        }

        // Deterministic from the seed whatever order the cells arrive in.
        List<PlanCell> shuffled = new ArrayList<>(cells);
        Collections.shuffle(shuffled, new java.util.Random(7));
        check(picked.equals(DungeonLight.blackoutCells(shuffled, entrance, terminal, lit::contains, 0.5, 42L)),
                "the same seed darkens the same cells in any iteration order");
        check(picked.equals(DungeonLight.blackoutCells(Set.copyOf(cells), entrance, terminal, lit::contains, 0.5, 42L)),
                "and from a Set.copyOf, whose order differs per JVM");
        boolean differs = false;
        for (long seed = 1; seed < 20 && !differs; seed++) {
            differs = !picked.equals(DungeonLight.blackoutCells(cells, entrance, terminal, lit::contains, 0.5, seed));
        }
        check(differs, "another seed darkens other cells");

        check(DungeonLight.blackoutCells(cells, entrance, terminal, lit::contains, 0.0, 42L).isEmpty(), "no fraction, no dark");
        check(DungeonLight.blackoutCells(cells, entrance, terminal, lit::contains, 1.0, 42L).size() == 15, "all eligible at 1.0");
        check(DungeonLight.blackoutCells(cells, entrance, terminal, c -> true, 1.0, 42L).isEmpty(), "nothing eligible, nothing dark");
        check(DungeonLight.blackoutCells(cells, entrance, terminal, lit::contains, 0.01, 42L).size() == 1,
                "a small fraction still darkens one cell");
        check(DungeonLight.blackoutCells(List.of(entrance), entrance, entrance, c -> false, 1.0, 1L).isEmpty(),
                "a one cell plan has none to darken");

        // The lever Ordeal rooms are named so the stamper can keep them lit.
        check(DungeonLight.LEVER_ORDEAL_CONTENT.containsAll(Set.of("rising_lava", "collapsing_bridge", "hold_the_plate")),
                "the lever ordeals stay lit");
    }

    private static void light() {
        check(DungeonRoomMeta.LIGHT_DARK.equals(DungeonLight.withBlackout(DungeonRoomMeta.LIGHT_LIT, true, false)),
                "a blacked out lit room goes dark");
        check(DungeonRoomMeta.LIGHT_DARK.equals(DungeonLight.withBlackout(DungeonRoomMeta.LIGHT_DIM, true, false)),
                "a blacked out dim room goes dark");
        check(DungeonRoomMeta.LIGHT_LIT.equals(DungeonLight.withBlackout(DungeonRoomMeta.LIGHT_LIT, true, true)),
                "a room that requires light is never blacked out");
        check(DungeonRoomMeta.LIGHT_DIM.equals(DungeonLight.withBlackout(DungeonRoomMeta.LIGHT_DIM, false, false)),
                "a cell outside the blackout keeps its light");
        // Dark removes every light, so the existing removal logic does the work.
        List<int[]> lights = List.of(new int[]{1, 1, 1}, new int[]{2, 1, 1}, new int[]{3, 1, 1});
        check(DungeonLight.removals(DungeonRoomMeta.LIGHT_DARK, lights).size() == 3, "dark removes every light");
    }

    private static void check(boolean ok, String what) {
        if (!ok) {
            throw new AssertionError(what);
        }
    }
}
