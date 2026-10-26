package charter.gametest;

import charter.contract.Basis;
import charter.contract.Contract;
import charter.contract.ContractEvents;
import charter.contract.ContractState;
import charter.contract.Justice;
import charter.contract.Party;
import charter.contract.actions.Confine;
import charter.engine.Actor;
import charter.gov.Preset;
import charter.id.ChunkKey;
import charter.id.OrgId;
import charter.id.PlayerId;
import charter.land.Core;
import charter.land.actions.LoseCore;
import charter.land.actions.PlaceNexus;
import charter.land.actions.PlantCamp;
import charter.mc.CharterRuntime;
import charter.mc.CharterServer;
import charter.mc.granary.Granaries;
import charter.mc.granary.GranaryService;
import charter.mc.justice.CellBook;
import charter.mc.justice.Cells;
import charter.mc.justice.Jail;
import charter.mc.land.Fixtures;
import charter.org.actions.AcceptInvite;
import charter.org.actions.Found;
import charter.org.actions.InviteMember;
import charter.siege.FallChoice;
import charter.time.Durations;
import charter.treasury.Currency;
import charter.treasury.actions.Deposit;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.ChestBlockEntity;

import java.util.ArrayList;
import java.util.Map;
import java.util.UUID;

/**
 * M12, the world halves of tests V and T that need a keep to fall. A prisoner is
 * held in a cell of a keep that is then starved out: the prisoner was a mouth on
 * its granary while held, walks free when the keep falls, and leaves the cell; and
 * an occupation that the owner starves out is resisted, with no breach mark on
 * anyone (test T).
 *
 * <p>These advance the siege clock, so they run in their own batch.
 */
public final class JusticeSiegeGameTest {

    private static final String OW = "minecraft:overworld";
    private static final int BASE_CHUNK_X = 7000 + 300 * (int) ((System.currentTimeMillis() / 1000) % 90);
    private static final int BASE_CHUNK_Z = -9200;
    private static final int Y = 100;

    private static final class World implements AutoCloseable {
        final GameTestHelper helper;
        final MinecraftServer server;
        final CharterRuntime rt = CharterServer.runtime();
        final GranaryService svc = Granaries.service();
        final ServerLevel level;
        final int cx;
        final int cz;
        final ServerPlayer lord;
        final ServerPlayer zedPlayer;
        final ServerPlayer prisoner;
        final PlayerId lordId;
        final PlayerId zed;
        final PlayerId prisonerId;
        final OrgId ashfall;
        final OrgId brine;
        final Core keep;
        Core camp;
        final BlockPos nexus;
        final BlockPos chestAt;
        private final int savedProtection;

        World(GameTestHelper helper, int slot, int bread) {
            this.helper = helper;
            this.server = helper.getLevel().getServer();
            this.level = server.overworld();
            this.cx = BASE_CHUNK_X + 40 * slot;
            this.cz = BASE_CHUNK_Z;
            for (int x = cx - 4; x <= cx + 4; x++) {
                for (int z = cz - 3; z <= cz + 3; z++) level.setChunkForced(x, z, true);
            }
            savedProtection = rt.knobs.newFactionProtectionDays;
            rt.knobs.newFactionProtectionDays = 0;
            lord = helper.makeMockServerPlayerInLevel();
            zedPlayer = helper.makeMockServerPlayerInLevel();
            prisoner = helper.makeMockServerPlayerInLevel();
            lordId = new PlayerId(lord.getUUID());
            zed = new PlayerId(zedPlayer.getUUID());
            prisonerId = new PlayerId(prisoner.getUUID());
            Actor admin = new Actor.Admin("m12");
            rt.state.touch(lordId, "Cellkeeper", rt.clock.now());
            rt.state.touch(zed, "Cellbreaker", rt.clock.now());
            rt.state.touch(prisonerId, "Captive" + Integer.toHexString(prisoner.getUUID().hashCode()), rt.clock.now());
            String tag = Long.toString(lord.getUUID().getLeastSignificantBits() & 0xFFFFFF, 36);
            check(rt.engine.run(admin, new Found(lordId, "Gaolers " + tag, Preset.LORDSHIP)).done(), "The jailers were not founded");
            ashfall = rt.state.orgNamed("Gaolers " + tag).orElseThrow().id;
            check(rt.engine.run(admin, new Found(zed, "Breakers " + tag, Preset.LORDSHIP)).done(), "The besiegers were not founded");
            brine = rt.state.orgNamed("Breakers " + tag).orElseThrow().id;
            join(ashfall, lordId, seen("Gaolhand"));
            join(brine, zed, seen("Breakhand"));
            for (OrgId org : java.util.List.of(ashfall, brine)) {
                rt.engine.run(admin, new Deposit(org, Currency.DIAMOND, 400));
                rt.engine.run(admin, new Deposit(org, Currency.COBBLESTONE, 5000));
            }

            check(rt.engine.run(admin, new PlaceNexus(ashfall, new ChunkKey(OW, cx, cz))).done(), "The keep was refused");
            keep = rt.state.coresOf(ashfall).getFirst();
            nexus = new BlockPos(cx * 16 + 8, Y, cz * 16 + 8);
            level.setBlock(nexus, Blocks.LODESTONE.defaultBlockState(), 3);
            Fixtures.of(rt.state).add(new Fixtures.Fixture(Fixtures.Kind.NEXUS, OW, nexus.getX(), nexus.getY(), nexus.getZ(), keep.id));

            chestAt = new BlockPos(cx * 16 + 3, Y, cz * 16 + 3);
            level.setBlock(chestAt, Blocks.AIR.defaultBlockState(), 3);
            level.setBlock(chestAt, Blocks.CHEST.defaultBlockState(), 3);
            command("charter admin granary set " + chestAt.getX() + " " + chestAt.getY() + " " + chestAt.getZ());
            ((ChestBlockEntity) level.getBlockEntity(chestAt)).setItem(0, new ItemStack(Items.BREAD, bread));
            svc.reconcile(svc.book().site(keep.id), null);

            check(rt.engine.run(admin, new PlantCamp(brine, new ChunkKey(OW, cx + 2, cz))).done(), "The camp was refused");
            camp = rt.state.coresOf(brine).stream().filter(c -> !c.isKeep()).findFirst().orElseThrow();
            rt.sim.supply().deposit("setup", camp.id, Map.of("minecraft:bread", 2000), new ArrayList<>());
        }

        /** A stone cell in the keep's west chunk, marked by the Lord. Returns the cell. */
        CellBook.Cell cell() {
            BlockPos at = new BlockPos((cx - 1) * 16 + 4, Y, cz * 16 + 4);
            for (int x = -1; x <= 3; x++) {
                for (int y = -1; y <= 2; y++) {
                    for (int z = -1; z <= 3; z++) {
                        boolean inside = x >= 0 && x < 3 && y >= 0 && y < 2 && z >= 0 && z < 3;
                        level.setBlockAndUpdate(at.offset(x, y, z), inside ? Blocks.AIR.defaultBlockState() : Blocks.STONE.defaultBlockState());
                    }
                }
            }
            BlockPos stand = at.offset(1, 0, 1);
            lord.teleportTo(stand.getX() + 0.5, stand.getY(), stand.getZ() + 0.5);
            Cells.Done done = Cells.mark(lord, "Gaol");
            check(done.ok(), "The cell was not marked: " + done.text());
            return Cells.book().get(keep.id, "Gaol");
        }

        /** Arrests the prisoner (a lead, as in the real thing) and confines them in the cell for an hour. */
        void jail(CellBook.Cell cell) {
            BlockPos outside = new BlockPos((cx - 1) * 16 + 10, Y, cz * 16 + 10);
            lord.teleportTo(outside.getX() + 0.5, outside.getY(), outside.getZ() - 0.5);
            prisoner.teleportTo(outside.getX() + 0.5, outside.getY(), outside.getZ() + 0.5);
            lord.setItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND, new ItemStack(Items.LEAD));
            net.fabricmc.fabric.api.event.player.UseEntityCallback.EVENT.invoker().interact(lord, level,
                    net.minecraft.world.InteractionHand.MAIN_HAND, prisoner, null);
            check(rt.state.contracts.custodyOf(prisonerId).isPresent(), "The lead did not arrest");
            check(rt.engine.run(new Actor.Player(lordId), new Confine(ashfall, prisonerId, Durations.hours(1), keep.id, cell.name)).done(),
                    "The confinement was refused");
            Jail.enforce(prisoner);
            check(Jail.inside(cell, prisoner), "The prisoner was not put in the cell");
        }

        /** Runs the siege in ten minute steps until the keep falls. */
        void starve() {
            command("charter admin time advance 2h");
            for (int i = 0; i < 60 && !rt.state.siege.falls.containsKey(keep.id); i++) command("charter admin time advance 10m");
            check(rt.state.siege.falls.containsKey(keep.id), "The keep never fell");
        }

        PlayerId seen(String name) {
            PlayerId id = new PlayerId(UUID.randomUUID());
            rt.state.touch(id, name, rt.clock.now());
            return id;
        }

        void join(OrgId org, PlayerId lordOf, PlayerId other) {
            check(rt.engine.run(new Actor.Player(lordOf), new InviteMember(org, other)).done(), "Invite refused");
            check(rt.engine.run(new Actor.Player(other), new AcceptInvite(other, org)).done(), "Accept refused");
        }

        void command(String command) {
            server.getCommands().performPrefixedCommand(server.createCommandSourceStack(), command);
        }

        void check(boolean ok, String why) {
            helper.assertTrue(ok, why);
        }

        boolean mailed(PlayerId player, String fragment) {
            return rt.mail.peek(player).stream().anyMatch(m -> m.text().contains(fragment));
        }

        @Override
        public void close() {
            rt.knobs.newFactionProtectionDays = savedProtection;
            Jail.forget(prisoner.getUUID());
            for (CellBook.Cell c : Cells.book().of(keep.id)) Cells.book().remove(c.keep, c.name);
            svc.book().remove(keep.id);
            for (Core core : java.util.Arrays.asList(camp, keep)) {
                if (core != null && rt.state.core(core.id).isPresent()) rt.engine.run(new Actor.Admin("m12"), new LoseCore(core.id));
            }
            for (int x = cx - 4; x <= cx + 4; x++) {
                for (int z = cz - 3; z <= cz + 3; z++) level.setChunkForced(x, z, false);
            }
        }
    }

    @GameTest(maxTicks = 200, environment = "charter-gametest:long_siege")
    public void aPrisonerIsAMouthWhileHeldAndWalksFreeWhenTheKeepFalls(GameTestHelper helper) {
        try (World w = new World(helper, 0, 12)) {
            CellBook.Cell cell = w.cell();
            w.jail(cell);
            long now = w.rt.clock.now();
            w.check(Justice.prisoners(w.rt.state, w.rt.knobs, w.keep.id, now) == 1, "A held prisoner should be one mouth on the keep");

            w.starve();
            w.check(w.rt.state.history.has(ContractEvents.PrisonersFreed.class), "The prisoners freed by the fall were not recorded");
            Contract sentence = null;
            for (Contract c : w.rt.state.contracts.all()) {
                if (c.basis == Basis.CUSTODY && c.subject.equals(Party.of(w.prisonerId))) sentence = c;
            }
            w.check(sentence != null && sentence.state() == ContractState.ENDED, "The sentence should end when the keep falls, it is "
                    + (sentence == null ? "missing" : sentence.state()));
            w.check(Justice.holding(w.rt.state, w.rt.knobs, w.prisonerId, w.rt.clock.now()).isEmpty(), "The prisoner is still held after the fall");
            w.check(Justice.prisoners(w.rt.state, w.rt.knobs, w.keep.id, w.rt.clock.now()) == 0, "A fallen keep still counts a prisoner");
            w.check(w.rt.state.contracts.breachMarks(Party.of(w.prisonerId)) == 0, "Being freed marked the prisoner");

            // Occupied, the keep still stands: the prisoner is led out of the cell, and a jump is no longer put back.
            SiegeWorldTestHooks.choose(w.zedPlayer, w.brine, w.keep, FallChoice.OCCUPY);
            Jail.enforce(w.prisoner);
            w.check(!Jail.inside(cell, w.prisoner), "A freed prisoner was left in the cell");
            helper.succeed();
        }
    }

    @GameTest(maxTicks = 200, environment = "charter-gametest:long_siege")
    public void starvingOutAnOccupationLeavesNoBreachMark(GameTestHelper helper) {
        try (World w = new World(helper, 1, 12)) {
            w.starve();
            SiegeWorldTestHooks.choose(w.zedPlayer, w.brine, w.keep, FallChoice.OCCUPY);
            Contract occupation = null;
            for (Contract c : w.rt.state.contracts.live()) {
                if (c.basis == Basis.OCCUPATION) occupation = c;
            }
            w.check(occupation != null, "The occupation was not recorded as a contract");

            // The owner plants a camp beside the occupied keep and starves the occupiers out.
            Actor admin = new Actor.Admin("m12");
            w.check(w.rt.engine.run(admin, new PlantCamp(w.ashfall, new ChunkKey(OW, w.cx - 2, w.cz))).done(), "The owner's camp was refused");
            Core ownerCamp = w.rt.state.coresOf(w.ashfall).stream().filter(c -> !c.isKeep()).findFirst().orElseThrow();
            w.rt.sim.supply().deposit("setup", ownerCamp.id, Map.of("minecraft:bread", 2000), new ArrayList<>());
            try {
                w.command("charter admin time advance 2h");
                for (int i = 0; i < 60 && occupation.live(); i++) w.command("charter admin time advance 10m");
                w.check(occupation.state() == ContractState.RESISTED, "Starving the occupiers out should resist the occupation, it is " + occupation.state());
                w.check(w.rt.state.contracts.breachMarks(Party.of(w.brine)) == 0, "A starved out occupier was marked as a breaker");
                w.check(w.rt.state.contracts.breachMarks(Party.of(w.ashfall)) == 0, "The owner was marked as a breaker");
                helper.succeed();
            } finally {
                if (w.rt.state.core(ownerCamp.id).isPresent()) w.rt.engine.run(admin, new LoseCore(ownerCamp.id));
            }
        }
    }
}
