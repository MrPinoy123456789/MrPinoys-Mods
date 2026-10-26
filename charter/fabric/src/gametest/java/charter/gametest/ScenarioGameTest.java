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
import charter.siege.KeepSupply;
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
    public void theRaidPutsACampBesideTheFarthestFarm(GameTestHelper helper) throws Exception {
        try (Run r = new Run(helper, 0)) {
            r.run("raid");
            r.check(r.made().size() == 2, "The raid should make a keep and a camp, made " + r.made().size());
            Core camp = r.camps().getFirst();
            r.check(camp.phase() == CampPhase.ACTIVE, "The raid's camp is not raised: " + camp.phase());
            long farms = r.rt.state.claimsOf(r.keep().owner).stream().filter(c -> c.kind() == ClaimKind.OUTLYING).count();
            r.check(farms == 3, "Ashfall should hold three farms, holds " + farms);
            r.check(r.rt.state.siege.camp(camp.id).targets.contains(r.keep().owner), "The camp does not raid Ashfall's farm");
            r.check(r.level.getBlockState(r.ground(4, 0).offset(1, 0, 1)).is(Blocks.WHEAT), "The far farm has no wheat");
            r.check(r.rt.knobs.timeScale == 24, "The scenario did not set the time scale");
        }
        helper.succeed();
    }

    @GameTest(maxTicks = 200, environment = "charter-gametest:long_siege")
    public void thePartialSiegeLeavesSidesOpenAndARailLine(GameTestHelper helper) throws Exception {
        try (Run r = new Run(helper, 1)) {
            r.run("partial-siege");
            r.check(r.camps().size() == 2, "The partial siege should raise two camps, made " + r.camps().size());
            KeepSupply supply = r.rt.state.siege.keep(r.keep().id).supply;
            r.check(supply == KeepSupply.BESIEGED, "The keep should be besieged with sides open, is " + supply);
            double share = r.granaryShare();
            r.check(share > 0.45 && share <= 0.51, "The granary should start near half, is " + share);
            BlockPos start = r.ground(-4, 0).offset(0, 0, 2);
            var carts = r.level.getEntitiesOfClass(MinecartChest.class, new AABB(start).inflate(3));
            r.check(!carts.isEmpty(), "No chest cart at the start of the rails");
            boolean rail = false;
            for (int dy = -2; dy <= 2 && !rail; dy++) rail = r.level.getBlockState(start.offset(16, dy, 0)).is(Blocks.RAIL);
            r.check(rail, "No rail along the line west of the keep");
            for (var cart : carts) cart.discard();
        }
        helper.succeed();
    }

    @GameTest(maxTicks = 200, environment = "charter-gametest:long_siege")
    public void theEncirclementClosesEverySide(GameTestHelper helper) throws Exception {
        try (Run r = new Run(helper, 2)) {
            r.run("encirclement");
            r.check(r.camps().size() == 4, "The ring needs four camps, made " + r.camps().size());
            KeepSupply supply = r.rt.state.siege.keep(r.keep().id).supply;
            r.check(supply == KeepSupply.ENCIRCLED, "The keep should be encircled, is " + supply);
            double share = r.granaryShare();
            r.check(share > 0.25 && share <= 0.31, "The granary should start near 30 percent, is " + share);

            // The debrief log gets the keep's drain every siege hour and its multiplier when it moves.
            r.server.getCommands().performPrefixedCommand(r.server.createCommandSourceStack(), "charter admin time advance 2h");
            String id = r.keep().id.value().toString();
            String log;
            try (var files = Files.list(r.rt.siegeLog.dir())) {
                StringBuilder all = new StringBuilder();
                for (var file : files.toList()) all.append(Files.readString(file));
                log = all.toString();
            }
            long hours = log.lines().filter(l -> l.contains("\"ev\":\"drain_hour\"") && l.contains(id)).count();
            r.check(hours >= 2, "The log should hold a drain snapshot each siege hour, has " + hours);
            r.check(log.lines().anyMatch(l -> l.contains("\"ev\":\"presence\"") && l.contains(id)), "The log has no presence line");
            r.check(log.lines().anyMatch(l -> l.contains("\"ev\":\"admin\"") && l.contains("scenario runat")), "The scenario run was not logged");
        }
        helper.succeed();
    }

    @GameTest(maxTicks = 200, environment = "charter-gametest:long_siege")
    public void theSallyIsTheRingWithFoodBeyondTheEastCamp(GameTestHelper helper) throws Exception {
        try (Run r = new Run(helper, 3)) {
            r.run("sally");
            r.check(r.rt.state.siege.keep(r.keep().id).supply == KeepSupply.ENCIRCLED, "The sally's keep is not encircled");
            r.check(r.level.getBlockState(r.ground(5, 0).below()).is(Blocks.CHEST)
                    || r.level.getBlockState(r.ground(5, 0)).is(Blocks.CHEST), "No food chest east of the ring");
        }
        helper.succeed();
    }

    @GameTest(maxTicks = 200, environment = "charter-gametest:long_siege")
    public void anExposedCampsBannerBreaksAndEndsTheCamp(GameTestHelper helper) throws Exception {
        try (Run r = new Run(helper, 5)) {
            r.run("sally");
            Core east = r.camps().stream().filter(c -> c.center.x() == r.chunkX + 2 && c.center.z() == r.chunkZ)
                    .findFirst().orElseThrow(() -> new AssertionError("No east camp"));
            var banner = charter.mc.land.Fixtures.of(r.rt.state).first(east.id, charter.mc.land.Fixtures.Kind.BANNER);
            r.check(banner != null, "The east camp has no banner");
            BlockPos at = new BlockPos(banner.x(), banner.y(), banner.z());
            net.minecraft.server.level.ServerPlayer defender = helper.makeMockServerPlayerInLevel();
            defender.setGameMode(net.minecraft.world.level.GameType.SURVIVAL);
            defender.teleportTo(at.getX() - 1.5, at.getY(), at.getZ() + 0.5);

            defender.gameMode.destroyBlock(at);
            r.check(r.rt.state.core(east.id).isPresent(), "A protected camp's banner came down");
            r.check(!r.level.getBlockState(at).isAir(), "The protected banner block was broken");

            // The sally hold has run out (core test X drains it): the camp is exposed.
            r.rt.state.siege.camp(east.id).exposed = true;
            defender.gameMode.destroyBlock(at);
            r.check(r.rt.state.core(east.id).isEmpty(), "Breaking an exposed camp's banner did not end the camp");
            r.check(r.level.getBlockState(at).isAir(), "The exposed camp's banner still stands");
            r.server.getPlayerList().remove(defender);
        }
        helper.succeed();
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
