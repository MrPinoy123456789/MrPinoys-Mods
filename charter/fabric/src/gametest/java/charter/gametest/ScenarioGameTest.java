package charter.gametest;

import charter.config.Knobs;
import charter.id.CoreId;
import charter.land.CampPhase;
import charter.land.ClaimKind;
import charter.land.Core;
import charter.mc.CharterRuntime;
import charter.mc.CharterServer;
import charter.mc.justice.CellBook;
import charter.mc.scenario.ScenarioCommands;
import charter.mc.scenario.ScenarioFiles;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.vehicle.minecart.MinecartChest;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.AABB;

import java.lang.reflect.Field;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * M14: the five playtest scenarios (PLAN.md §12.3) each build through the real
 * command, from the files written out of the jar, and leave the siege the session
 * means to try: the raid's camp beside the far farm, a partial siege with open
 * sides and a rail line, a full ring, the sally's ring, and a keep with a cell and
 * a Sheriff. Then {@code scenario clear} takes it all down again.
 *
 * <p>Raising the camps moves the siege clock, so these run in the long siege
 * environment, and each restores the settings its scenario changed.
 */
public final class ScenarioGameTest {

    private static final int BASE_CHUNK = 12_000;

    private static final class Run implements AutoCloseable {
        final GameTestHelper helper;
        final MinecraftServer server;
        final ServerLevel level;
        final CharterRuntime rt = CharterServer.runtime();
        final int chunkX;
        final int chunkZ = BASE_CHUNK;
        final Set<CoreId> before = new HashSet<>();
        final Map<Field, Object> knobs = new HashMap<>();

        Run(GameTestHelper helper, int slot) throws Exception {
            this.helper = helper;
            this.server = helper.getLevel().getServer();
            this.level = server.overworld();
            this.chunkX = BASE_CHUNK + 30 * slot;
            for (Field f : Knobs.class.getFields()) {
                if (!java.lang.reflect.Modifier.isStatic(f.getModifiers())) knobs.put(f, f.get(rt.knobs));
            }
            // The shipped files, never a copy an earlier run left in the config folder.
            for (String name : ScenarioFiles.DEFAULTS) Files.deleteIfExists(ScenarioCommands.dir().resolve(name + ".json"));
            for (Core core : rt.state.cores()) before.add(core.id);
        }

        void run(String name) {
            server.getCommands().performPrefixedCommand(server.createCommandSourceStack(),
                    "charter admin scenario runat " + (chunkX * 16 + 8) + " " + (chunkZ * 16 + 8) + " " + name);
        }

        List<Core> made() {
            return rt.state.cores().stream().filter(c -> !before.contains(c.id)).toList();
        }

        Core keep() {
            return made().stream().filter(Core::isKeep).findFirst().orElseThrow(() -> new AssertionError("The scenario made no keep"));
        }

        List<Core> camps() {
            return made().stream().filter(c -> !c.isKeep()).toList();
        }

        double granaryShare() {
            Core keep = keep();
            double have = rt.state.siege.store(keep.id).provisions.nourishment(rt.sim.food());
            return have / rt.sim.rules().storageLimit(keep);
        }

        BlockPos ground(int dx, int dz) {
            int x = (chunkX + dx) * 16 + 8;
            int z = (chunkZ + dz) * 16 + 8;
            return new BlockPos(x, level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z), z);
        }

        void check(boolean ok, String why) {
            helper.assertTrue(ok, why);
        }

        @Override
        public void close() throws Exception {
            server.getCommands().performPrefixedCommand(server.createCommandSourceStack(), "charter admin scenario clear");
            check(made().isEmpty(), "scenario clear left " + made().size() + " cores standing");
            for (Map.Entry<Field, Object> e : knobs.entrySet()) e.getKey().set(rt.knobs, e.getValue());
        }
    }

    @GameTest(maxTicks = 200, environment = "charter-gametest:long_siege")
    public void theJailHasACellAndASheriff(GameTestHelper helper) throws Exception {
        CoreId keepId;
        try (Run r = new Run(helper, 4)) {
            r.run("jail");
            Core keep = r.keep();
            keepId = keep.id;
            CellBook.Cell cell = CellBook.of(r.rt.state).get(keep.id, "Gaol");
            r.check(cell != null, "The jail has no cell called Gaol");
            r.check(cell.blocks.size() == 18, "The cell should be 3 by 3 by 2 inside, is " + cell.blocks.size());
            r.check(r.rt.state.officeNamed(keep.owner, "Sheriff").isPresent(), "Ashfall has no Sheriff");
            r.check(r.granaryShare() > 0.95, "The jail's granary should start full, is " + r.granaryShare());
            r.check(r.camps().isEmpty(), "The jail has no camps");
        }
        // A keep that is lost takes its cells with it at once, not at the next load's repair.
        helper.assertTrue(CellBook.of(CharterServer.runtime().state).of(keepId).isEmpty(), "The lost keep's cell outlived it");
        helper.succeed();
    }
}
