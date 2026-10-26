package charter.gametest;

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
import charter.mc.granary.GranaryService;
import charter.mc.granary.Granaries;
import charter.mc.land.Fixtures;
import charter.mc.land.Protection;
import charter.mc.war.SiegeWorld;
import charter.org.actions.AcceptInvite;
import charter.org.actions.CreateSubOrg;
import charter.org.actions.Found;
import charter.org.actions.InviteMember;
import charter.siege.FallChoice;
import charter.treasury.Currency;
import charter.treasury.actions.Deposit;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.phys.AABB;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * M11: the siege in the world (PLAN.md §11; test W and the full siege). A whole
 * siege runs from the camp's raising to the fall, then both endings: razed (the
 * nexus breaks, plunder spills as real items, the granary is loot) and occupied
 * (the occupier holds the keep, the owner does not, until the cap runs out).
 * Presence is counted from real players standing in real chunks.
 *
 * <p>Each test runs in one tick: the siege clock is shared by every gametest.
 */
public final class SiegeWorldGameTest {

    private static final String OW = "minecraft:overworld";
    private static final int BASE_CHUNK_X = 6400 + 300 * (int) ((System.currentTimeMillis() / 1000) % 90);
    private static final int BASE_CHUNK_Z = -5200;
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
        final PlayerId lordId;
        final PlayerId zed;
        final PlayerId hand;
        OrgId ashfall;
        OrgId guard;
        OrgId brine;
        Core keep;
        Core camp;
        BlockPos nexus;
        BlockPos chestAt;
        private final int savedProtection;

        World(GameTestHelper helper, int slot, int bread) {
            this.helper = helper;
            this.server = helper.getLevel().getServer();
            this.level = server.overworld();
            this.cx = BASE_CHUNK_X + 40 * slot;
            this.cz = BASE_CHUNK_Z;
            for (int x = cx - 3; x <= cx + 3; x++) {
                for (int z = cz - 3; z <= cz + 3; z++) level.setChunkForced(x, z, true);
            }
            savedProtection = rt.knobs.newFactionProtectionDays;
            rt.knobs.newFactionProtectionDays = 0;
            lord = helper.makeMockServerPlayerInLevel();
            zedPlayer = helper.makeMockServerPlayerInLevel();
            lordId = new PlayerId(lord.getUUID());
            zed = new PlayerId(zedPlayer.getUUID());
            Actor admin = new Actor.Admin("m11");
            rt.state.touch(lordId, "Ashlord", rt.clock.now());
            rt.state.touch(zed, "Brinelord", rt.clock.now());
            String tag = Long.toString(lord.getUUID().getLeastSignificantBits() & 0xFFFFFF, 36);
            check(rt.engine.run(admin, new Found(lordId, "Holdfast " + tag, Preset.LORDSHIP)).done(), "Ashfall was not founded");
            ashfall = rt.state.orgNamed("Holdfast " + tag).orElseThrow().id;
            check(rt.engine.run(admin, new Found(zed, "Reavers " + tag, Preset.LORDSHIP)).done(), "Brinewatch was not founded");
            brine = rt.state.orgNamed("Reavers " + tag).orElseThrow().id;
            hand = seen("Ashhand");
            join(ashfall, lordId, hand);
            join(ashfall, lordId, seen("Ashsmith"));
            join(brine, zed, seen("Brinehand"));
            // A subordinate, so the alerts can be shown to reach the whole faction.
            check(rt.engine.run(admin, new CreateSubOrg(ashfall, "Guard " + tag, Preset.COMMUNE, hand)).done(), "The guard was not created");
            guard = rt.state.orgNamed("Guard " + tag).orElseThrow().id;
            for (OrgId org : List.of(ashfall, brine)) {
                rt.engine.run(admin, new Deposit(org, Currency.DIAMOND, 400));
                rt.engine.run(admin, new Deposit(org, Currency.COBBLESTONE, 5000));
            }

            check(rt.engine.run(admin, new PlaceNexus(ashfall, new ChunkKey(OW, cx, cz))).done(), "The keep was refused");
            keep = rt.state.coresOf(ashfall).getFirst();
            nexus = new BlockPos(cx * 16 + 8, Y, cz * 16 + 8);
            level.setBlock(nexus, Blocks.LODESTONE.defaultBlockState(), 3);
            Fixtures.of(rt.state).add(new Fixtures.Fixture(Fixtures.Kind.NEXUS, OW, nexus.getX(), nexus.getY(), nexus.getZ(), keep.id));

            chestAt = new BlockPos(cx * 16 + 3, Y, cz * 16 + 3);
            level.setBlock(chestAt, Blocks.AIR.defaultBlockState(), 3); // an empty chest, never one left by an earlier test
            level.setBlock(chestAt, Blocks.CHEST.defaultBlockState(), 3);
            command("charter admin granary set " + chestAt.getX() + " " + chestAt.getY() + " " + chestAt.getZ());
            ((ChestBlockEntity) level.getBlockEntity(chestAt)).setItem(0, new ItemStack(Items.BREAD, bread));
            svc.reconcile(svc.book().site(keep.id), null);

            check(rt.engine.run(admin, new PlantCamp(brine, new ChunkKey(OW, cx + 2, cz))).done(), "The camp was refused");
            camp = rt.state.coresOf(brine).stream().filter(c -> !c.isKeep()).findFirst().orElseThrow();
            rt.sim.supply().deposit("setup", camp.id, Map.of("minecraft:bread", 2000), new ArrayList<>());
        }

        /** Raises the camp, then runs the siege in ten minute steps until the keep falls. */
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

        long dropped(net.minecraft.world.item.Item item) {
            long n = 0;
            for (ItemEntity e : level.getEntitiesOfClass(ItemEntity.class, new AABB(nexus).inflate(4))) {
                if (e.getItem().is(item)) n += e.getItem().getCount();
            }
            return n;
        }

        @Override
        public void close() {
            rt.knobs.newFactionProtectionDays = savedProtection;
            for (ItemEntity e : level.getEntitiesOfClass(ItemEntity.class, new AABB(nexus).inflate(6))) e.discard();
            svc.book().remove(keep.id);
            for (Core core : java.util.Arrays.asList(camp, keep)) {
                if (core != null && rt.state.core(core.id).isPresent()) rt.engine.run(new Actor.Admin("m11"), new LoseCore(core.id));
            }
            for (int x = cx - 3; x <= cx + 3; x++) {
                for (int z = cz - 3; z <= cz + 3; z++) level.setChunkForced(x, z, false);
            }
        }
    }

    @GameTest(maxTicks = 200, environment = "charter-gametest:long_siege")
    public void aFullSiegeEndsRazed(GameTestHelper helper) {
        try (World w = new World(helper, 0, 12)) {
            w.starve();
            helper.assertTrue(w.mailed(w.hand, w.rt.describe.core(w.keep.id)), "A member of the faction's subordinate was not told of the siege");
            long diamonds = w.rt.state.org(w.ashfall).orElseThrow().ledger.balance(Currency.DIAMOND);

            SiegeWorldTestHooks.choose(w.zedPlayer, w.brine, w.keep, FallChoice.RAZE);
            helper.assertTrue(w.rt.state.core(w.keep.id).isEmpty(), "The keep still stands after the raze");
            helper.assertTrue(w.level.getBlockState(w.nexus).isAir(), "The nexus block was not broken");
            long plundered = diamonds - w.rt.state.org(w.ashfall).orElseThrow().ledger.balance(Currency.DIAMOND);
            helper.assertTrue(plundered == diamonds / 4, "A quarter of the diamonds should be plundered, was " + plundered);
            helper.assertTrue(w.dropped(Items.DIAMOND) == plundered, "The plunder was not spilled at the nexus: " + w.dropped(Items.DIAMOND));
            helper.assertTrue(w.dropped(Items.COBBLESTONE) == 1250, "The cobblestone plunder was not spilled: " + w.dropped(Items.COBBLESTONE));
            helper.assertTrue(Protection.mayAct(w.zedPlayer, w.level, w.chestAt), "The fallen keep's granary should be loot for the besieger");
            helper.assertTrue(w.mailed(w.hand, "razed"), "The faction was not told its keep was razed");
            helper.succeed();
        }
    }

    @GameTest(maxTicks = 200, environment = "charter-gametest:long_siege")
    public void anOccupiedKeepIsHeldByTheOccupierUntilTheCap(GameTestHelper helper) {
        try (World w = new World(helper, 1, 12)) {
            w.starve();
            SiegeWorldTestHooks.choose(w.zedPlayer, w.brine, w.keep, FallChoice.OCCUPY);
            BlockPos ring = new BlockPos((w.cx - 1) * 16 + 4, Y, w.cz * 16 + 4);
            helper.assertTrue(w.rt.state.core(w.keep.id).map(c -> c.owner.equals(w.ashfall)).orElse(false), "An occupied keep must stay recorded to its owner");
            helper.assertTrue(w.level.getBlockState(w.nexus).is(Blocks.LODESTONE), "Occupying must not break the nexus");
            helper.assertTrue(Protection.mayAct(w.zedPlayer, w.level, ring), "The occupier should hold the keep's walls");
            helper.assertTrue(!Protection.mayAct(w.lord, w.level, ring), "The owner should not act in an occupied keep");
            helper.assertTrue(w.svc.access(w.zed, w.keep) == GranaryService.Access.FULL, "The occupier should hold the granary");

            w.command("charter admin time advance " + w.rt.knobs.occupationCapDays + "d1h");
            helper.assertTrue(!w.rt.state.siege.occupations.containsKey(w.keep.id), "The occupation did not end at the cap");
            helper.assertTrue(Protection.mayAct(w.lord, w.level, ring), "The owner did not get the keep back");
            helper.assertTrue(!Protection.mayAct(w.zedPlayer, w.level, ring), "The occupier kept control after the cap");
            helper.assertTrue(w.mailed(w.hand, "yours again"), "The owner's faction was not told the keep came back");
            helper.succeed();
        }
    }

    @GameTest(maxTicks = 200, environment = "charter-gametest:long_siege")
    public void presenceCountsPlayersOfStandingWhereTheyStand(GameTestHelper helper) {
        try (World w = new World(helper, 2, 2000)) {
            w.command("charter admin time advance 2h");
            ServerPlayer fresh = helper.makeMockServerPlayerInLevel();
            PlayerId freshId = new PlayerId(fresh.getUUID());
            w.rt.state.touch(freshId, "Recruit", w.rt.clock.now());
            w.join(w.ashfall, w.lordId, freshId);
            for (PlayerId p : List.of(w.lordId, w.zed)) w.command("charter admin age " + p.value() + " 4");

            w.zedPlayer.teleportTo((w.cx + 2) * 16 + 8, Y, w.cz * 16 + 8);
            w.lord.teleportTo(w.cx * 16 + 2, Y, w.cz * 16 + 2);
            fresh.teleportTo(w.cx * 16 + 4, Y, w.cz * 16 + 4);
            SiegeWorld.presence().refresh(w.server, w.rt);
            helper.assertTrue(SiegeWorld.presence().attackersInCamp(w.camp.id) == 1, "The attacker in the camp was not counted");
            helper.assertTrue(SiegeWorld.presence().defendersInKeep(w.keep.id) == 1,
                    "Exactly one defender of standing should count, not the new recruit: " + SiegeWorld.presence().defendersInKeep(w.keep.id));

            w.zedPlayer.teleportTo(w.cx * 16 + 6, Y, w.cz * 16 + 6);
            SiegeWorld.presence().refresh(w.server, w.rt);
            helper.assertTrue(SiegeWorld.presence().attackersInsideKeep(w.keep.id) == 1, "An attacker inside the keep was not counted");
            helper.assertTrue(SiegeWorld.presence().attackersInCamp(w.camp.id) == 0, "An attacker who left the camp still counted there");
            helper.succeed();
        }
    }
}
