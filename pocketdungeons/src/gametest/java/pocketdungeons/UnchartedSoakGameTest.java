package pocketdungeons;

import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Task T5 (campaign endgame, 2026-10-18), "never soft-lock": the floor soak of {@link FloorSoakGameTest} run over
 * generated Chart Room dungeons. Every theme a player can have finished is the entry and final theme of at least
 * one swept dungeon, and the middle floors borrow others, so each floor is planned the way a door preview plans
 * it (the real room manifest, the pilgrim bag, the node's theme and place in the graph) over many seeds. A
 * floor that cannot be planned, a cell the entrance cannot reach, a spawner room behind a rubble plug and the
 * other hard findings of the soak fail the test and name the seed.
 *
 * <p>Seeds per floor default to 6 here (the generator draws thousands of different floors, so breadth over depth);
 * set {@code PD_SOAK_SEEDS} for a longer soak.
 */
public final class UnchartedSoakGameTest {

    private static final int DEFAULT_SEEDS = 6;
    private static final Set<String> FINISHED = Set.of("pocketdungeons:spawner_dungeon", "pocketdungeons:ancient_city");

    private static int seeds() {
        String value = System.getenv("PD_SOAK_SEEDS");
        if (value != null) {
            try {
                return Math.max(1, Integer.parseInt(value.trim()));
            } catch (NumberFormatException ignored) {
                // fall through to the default
            }
        }
        return DEFAULT_SEEDS;
    }

    @GameTest(maxTicks = 100)
    public void everyThemeAGeneratedDoorCanStartOnPlansSolvably(GameTestHelper helper) {
        List<DungeonDef> all = new ArrayList<>(DungeonDefs.current().all());
        List<DungeonDef> sources = UnchartedGen.sources(UnchartedGen.finishedActs(FINISHED, all, 2), all);
        helper.assertTrue(sources.size() >= 3, "the finished acts offer themes: " + sources.size());
        Set<String> covered = new HashSet<>();
        Set<String> wanted = new HashSet<>();
        for (DungeonDef source : sources) {
            wanted.add(DungeonDef.qualify(source.mainTheme()));
        }
        List<String> hard = new ArrayList<>();
        int swept = 0;
        for (int trip = 0; trip < 300 && (!covered.containsAll(wanted) || swept < 12); trip++) {
            for (DungeonDef def : UnchartedGen.doors(new UUID(trip, 99), trip, 6 + trip % 40, 2, FINISHED, all)) {
                boolean fresh = covered.add(DungeonDef.qualify(def.mainTheme()));
                if (!fresh && swept >= 12) {
                    continue;
                }
                swept++;
                hard.addAll(FloorSoakGameTest.soakDef(def, def.id() + " (" + def.name() + ")", seeds()));
            }
        }
        helper.assertTrue(covered.containsAll(wanted), "every theme started a swept dungeon, missing: "
                + wanted.stream().filter(t -> !covered.contains(t)).toList());
        if (!hard.isEmpty()) {
            helper.fail(hard.size() + " hard finding(s) in generated dungeons; first: " + hard.get(0));
            return;
        }
        helper.succeed();
    }
}
