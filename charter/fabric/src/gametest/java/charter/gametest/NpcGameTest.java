package charter.gametest;

import charter.engine.Actor;
import charter.id.ChunkKey;
import charter.id.OrgId;
import charter.id.PlayerId;
import charter.land.Core;
import charter.land.actions.LoseCore;
import charter.land.actions.PlantCamp;
import charter.mc.CharterRuntime;
import charter.mc.CharterServer;
import charter.mc.granary.GranaryService;
import charter.mc.granary.Granaries;
import charter.mc.npc.NpcWorld;
import charter.gov.Preset;
import charter.npc.NpcBook;
import charter.org.actions.Found;
import charter.treasury.Currency;
import charter.treasury.actions.Deposit;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;

/**
 * The raiders' strongholds and outposts in the world (NPC strongholds). A stronghold is raised, starved by a camp
 * and razed, its plunder spills and its defenders go; an outpost falls and leaves a chest. The scheduler's caps
 * are set to nothing for the length of each test so no other site appears beside them.
 */
public final class NpcGameTest {

    private static final String OW = "minecraft:overworld";
    private static final int CHUNK_Z = -9000;

    private static final class Site implements AutoCloseable {
        final GameTestHelper helper;
        final MinecraftServer server;
        final CharterRuntime rt = CharterServer.runtime();
        final ServerLevel level;
        final int cx;
        final int savedStrongholds;
        final int savedOutposts;

        Site(GameTestHelper helper, int slot) {
            this.helper = helper;
            this.server = helper.getLevel().getServer();
            this.level = server.overworld();
            this.cx = 90_000 + slot * 60;
            savedStrongholds = rt.knobs.npcMaxStrongholds;
            savedOutposts = rt.knobs.npcMaxOutposts;
            rt.knobs.npcMaxStrongholds = 0;
            rt.knobs.npcMaxOutposts = 0;
            for (int x = cx - 3; x <= cx + 4; x++) {
                for (int z = CHUNK_Z - 3; z <= CHUNK_Z + 3; z++) level.setChunkForced(x, z, true);
            }
            // The test world is empty: a stone floor for the raiders to build on.
            for (int x = cx * 16 - 24; x < cx * 16 + 90; x++) {
                for (int z = CHUNK_Z * 16 - 24; z < CHUNK_Z * 16 + 48; z++) {
                    level.setBlock(new BlockPos(x, 99, z), Blocks.STONE.defaultBlockState(), 2);
                }
            }
        }

        void command(String command) {
            server.getCommands().performPrefixedCommand(server.createCommandSourceStack(), command);
        }

        List<Mob> defenders() {
            AABB area = new AABB(cx * 16 - 40, 90, CHUNK_Z * 16 - 40, cx * 16 + 90, 140, CHUNK_Z * 16 + 60);
            return level.getEntitiesOfClass(Mob.class, area, m -> m.entityTags().contains(NpcWorld.TAG));
        }

        @Override
        public void close() {
            rt.knobs.npcMaxStrongholds = savedStrongholds;
            rt.knobs.npcMaxOutposts = savedOutposts;
            for (int x = cx - 3; x <= cx + 4; x++) {
                for (int z = CHUNK_Z - 3; z <= CHUNK_Z + 3; z++) level.setChunkForced(x, z, false);
            }
        }
    }

    @GameTest(maxTicks = 200, environment = "charter-gametest:long_siege")
    public void aStrongholdIsRaisedStarvedAndRazed(GameTestHelper helper) {
        Site s = new Site(helper, 0);
        CharterRuntime rt = s.rt;
        ChunkKey chunk = new ChunkKey(OW, s.cx, CHUNK_Z);
        String done = NpcWorld.raise(s.server, NpcBook.Kind.STRONGHOLD, chunk, false);
        NpcBook.Site site = rt.state.npc.sites.stream().filter(x -> x.chunk().equals(chunk)).findFirst().orElse(null);
        helper.assertTrue(site != null, "No stronghold was raised: " + done);
        OrgId org = site.org();
        helper.assertTrue(rt.state.org(org).orElseThrow().npc, "The stronghold's faction is not a faction of the raiders");
        Core keep = rt.state.core(site.core()).orElseThrow();
        helper.assertTrue(keep.isKeep(), "The stronghold has no keep");
        long expectedDiamonds = rt.knobs.npcDiamonds[site.tier()];
        helper.assertTrue(rt.state.org(org).orElseThrow().ledger.balance(Currency.DIAMOND) == expectedDiamonds, "The treasury is not stocked by tier");
        var nexus = charter.mc.land.Fixtures.of(rt.state).first(keep.id, charter.mc.land.Fixtures.Kind.NEXUS);
        helper.assertTrue(nexus != null && level(s).getBlockState(new BlockPos(nexus.x(), nexus.y(), nexus.z())).is(Blocks.LODESTONE), "The Nexus is not standing");
        BlockPos nexusAt = new BlockPos(nexus.x(), nexus.y(), nexus.z());
        GranaryService svc = Granaries.service();
        helper.assertTrue(svc.book().site(keep.id) != null, "The stronghold has no granary");

        // Almost empty its granary so the siege is short.
        for (int i = 0; i < 4; i++) {
            if (level(s).getBlockEntity(nexusAt.offset(2, 0, i - 1)) instanceof Container box) {
                box.clearContent();
                if (i == 0) box.setItem(0, new ItemStack(Items.BREAD, 12));
            }
        }
        svc.reconcile(svc.book().site(keep.id), null);

        // A camp of a player faction beside it.
        Actor admin = new Actor.Admin("npc test");
        PlayerId lord = new PlayerId(UUID.randomUUID());
        rt.state.touch(lord, "Besieger", rt.clock.now());
        String tag = Long.toString(lord.value().getLeastSignificantBits() & 0xFFFFFF, 36);
        helper.assertTrue(rt.engine.run(admin, new Found(lord, "Besiegers " + tag, Preset.LORDSHIP)).done(), "The besiegers were not founded");
        OrgId brine = rt.state.orgNamed("Besiegers " + tag).orElseThrow().id;
        rt.engine.run(admin, new Deposit(brine, Currency.COBBLESTONE, 5000));
        helper.assertTrue(rt.engine.run(admin, new PlantCamp(brine, new ChunkKey(OW, s.cx + 2, CHUNK_Z))).done(), "The camp was refused");
        Core camp = rt.state.coresOf(brine).stream().filter(c -> !c.isKeep()).findFirst().orElseThrow();
        rt.sim.supply().deposit("setup", camp.id, Map.of("minecraft:bread", 2000), new ArrayList<>());

        s.command("charter admin time advance 2h");
        for (int i = 0; i < 80 && rt.state.core(keep.id).isPresent(); i++) s.command("charter admin time advance 10m");
        helper.assertTrue(rt.state.core(keep.id).isEmpty(), "The stronghold never fell");
        helper.assertTrue(!rt.state.siege.falls.containsKey(keep.id), "The raiders were asked to choose a fall");
        helper.assertTrue(level(s).getBlockState(nexusAt).isAir(), "The Nexus was not broken");

        helper.runAfterDelay(4, () -> {
            long spilled = 0;
            for (ItemEntity e : level(s).getEntitiesOfClass(ItemEntity.class, new AABB(nexusAt).inflate(6))) {
                if (e.getItem().is(Items.DIAMOND)) spilled += e.getItem().getCount();
            }
            helper.assertTrue(spilled == expectedDiamonds / 4, "The plunder should be a quarter of the diamonds (" + expectedDiamonds / 4 + "), was " + spilled);
            helper.assertTrue(!s.defenders().isEmpty(), "The stronghold had no defenders");
            // The scheduler notices the fall: the defenders go and a replacement is awaited.
            NpcWorld.runOnce(s.server, new Random(1));
            helper.assertTrue(rt.state.npc.sites.stream().noneMatch(x -> x.id() == site.id()), "The fallen site is still listed");
            helper.assertTrue(rt.state.npc.waiting(NpcBook.Kind.STRONGHOLD) == 1, "No replacement is waiting");
            helper.runAfterDelay(3, () -> {
                helper.assertTrue(s.defenders().isEmpty(), "The defenders stayed after the fall");
                for (ItemEntity e : level(s).getEntitiesOfClass(ItemEntity.class, new AABB(nexusAt).inflate(8))) e.discard();
                if (svc != null) svc.book().remove(keep.id);
                rt.engine.run(admin, new LoseCore(camp.id));
                rt.state.npc.respawns.clear();
                s.close();
                helper.succeed();
            });
        });
    }

    private static ServerLevel level(Site s) {
        return s.level;
    }

    @GameTest(maxTicks = 200, environment = "charter-gametest:long_siege")
    public void anOutpostFallsAndLeavesAChest(GameTestHelper helper) {
        Site s = new Site(helper, 1);
        CharterRuntime rt = s.rt;
        ChunkKey chunk = new ChunkKey(OW, s.cx, CHUNK_Z);
        String done = NpcWorld.raise(s.server, NpcBook.Kind.OUTPOST, chunk, false);
        NpcBook.Site site = rt.state.npc.sites.stream().filter(x -> x.chunk().equals(chunk)).findFirst().orElse(null);
        helper.assertTrue(site != null, "No outpost was raised: " + done);
        Core camp = rt.state.core(site.core()).orElseThrow();
        helper.assertTrue(!camp.isKeep() && camp.phase() == charter.land.CampPhase.ACTIVE, "The outpost is not a raised camp");
        helper.assertTrue(rt.state.siege.store(camp.id).provisions.count("minecraft:bread") > 0, "The outpost has no store");
        helper.assertTrue(rt.state.org(site.org()).orElseThrow().npc, "The outpost's faction is not a faction of the raiders");

        helper.runAfterDelay(3, () -> {
            helper.assertTrue(!s.defenders().isEmpty() && s.defenders().size() <= 3, "An outpost has one to three defenders");
            rt.engine.run(new Actor.Admin("npc test"), new LoseCore(camp.id));
            NpcWorld.runOnce(s.server, new Random(1));
            helper.assertTrue(rt.state.npc.waiting(NpcBook.Kind.OUTPOST) == 1, "No replacement is waiting for the outpost");
            helper.runAfterDelay(3, () -> {
                helper.assertTrue(s.defenders().isEmpty(), "The defenders stayed after the outpost fell");
                BlockPos chest = new BlockPos(s.cx * 16 + 8, 100, CHUNK_Z * 16 + 8);
                helper.assertTrue(s.level.getBlockState(chest).is(Blocks.CHEST) || s.level.getBlockState(chest.above()).is(Blocks.CHEST),
                        "The outpost left no chest of its store");
                rt.state.npc.respawns.clear();
                s.close();
                helper.succeed();
            });
        });
    }
}
