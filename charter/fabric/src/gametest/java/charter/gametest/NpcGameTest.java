package charter.gametest;

import charter.engine.Actor;
import charter.id.ChunkKey;
import charter.id.OrgId;
import charter.land.Core;
import charter.land.actions.LoseCore;
import charter.mc.CharterRuntime;
import charter.mc.CharterServer;
import charter.mc.granary.Granaries;
import charter.mc.npc.NpcWorld;
import charter.npc.NpcBook;
import charter.time.Durations;
import charter.treasury.Currency;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * The raiders' strongholds and outposts in the world (protected keeps, N). A site is raised with a snapshot of the
 * ground, spawns a wave, is drained by killing its raiders and falls with the ground put back exactly; the failsafe
 * and the siege route clean up the same way. The automatic pass is off for the length of each test and the test
 * drives {@code runOnce} with a time of its own, so nothing happens between the lines.
 */
public final class NpcGameTest {

    private static final String OW = "minecraft:overworld";
    private static final int CHUNK_Z = -9000;
    private static final int FLOOR = 99;

    /** Sites open at once: tests run side by side, so the shared switches change only when the first opens and the last closes. */
    private static int open;
    private static int savedStrongholds;
    private static int savedOutposts;

    private static final class Site implements AutoCloseable {
        final GameTestHelper helper;
        final MinecraftServer server;
        final CharterRuntime rt = CharterServer.runtime();
        final ServerLevel level;
        final int cx;
        boolean closed;

        Site(GameTestHelper helper, int slot) {
            this.helper = helper;
            this.server = helper.getLevel().getServer();
            this.level = server.overworld();
            this.cx = 90_000 + slot * 60;
            if (open++ == 0) {
                savedStrongholds = rt.knobs.npcMaxStrongholds;
                savedOutposts = rt.knobs.npcMaxOutposts;
                rt.knobs.npcMaxStrongholds = 0;
                rt.knobs.npcMaxOutposts = 0;
                NpcWorld.AUTO = false;
            }
            for (int x = cx - 1; x <= cx + 1; x++) {
                for (int z = CHUNK_Z - 1; z <= CHUNK_Z + 1; z++) level.setChunkForced(x, z, true);
            }
            // The test world is empty: a stone floor, a little larger than the stronghold, for the raiders to build on.
            for (int x = cx * 16 - 8; x < cx * 16 + 24; x++) {
                for (int z = CHUNK_Z * 16 - 8; z < CHUNK_Z * 16 + 24; z++) {
                    level.setBlock(new BlockPos(x, FLOOR, z), Blocks.STONE.defaultBlockState(), 2);
                }
            }
        }

        ChunkKey chunk() {
            return new ChunkKey(OW, cx, CHUNK_Z);
        }

        BlockPos centre() {
            return new BlockPos(cx * 16 + 8, FLOOR + 1, CHUNK_Z * 16 + 8);
        }

        void command(String command) {
            server.getCommands().performPrefixedCommand(server.createCommandSourceStack(), command);
        }

        List<Mob> raiders() {
            BlockPos c = centre();
            AABB area = new AABB(c.getX() - 40, 90, c.getZ() - 40, c.getX() + 40, 140, c.getZ() + 40);
            return level.getEntitiesOfClass(Mob.class, area, m -> m.entityTags().contains(NpcWorld.TAG));
        }

        void killRaiders() {
            for (Mob m : raiders()) m.discard();
        }

        /** Every block state in a box well beyond the footprint. */
        Map<Long, BlockState> capture() {
            BlockPos c = centre();
            Map<Long, BlockState> all = new HashMap<>();
            for (BlockPos p : BlockPos.betweenClosed(c.offset(-14, -4, -14), c.offset(14, 10, 14))) all.put(p.asLong(), level.getBlockState(p));
            return all;
        }

        String differences(Map<Long, BlockState> before) {
            int n = 0;
            String first = "";
            for (Map.Entry<Long, BlockState> e : before.entrySet()) {
                BlockPos p = BlockPos.of(e.getKey());
                if (level.getBlockState(p) != e.getValue()) {
                    if (n++ == 0) first = p.toShortString() + " was " + e.getValue() + " is " + level.getBlockState(p);
                }
            }
            return n == 0 ? null : n + " blocks differ, first " + first;
        }

        /** A time of this test's own: the passes it drives use it, and its replacement timer is found by it. */
        long clock() {
            return rt.clock.now() + (cx - 90_000) * 1000L + 7;
        }

        long replacementDue(long now) {
            return now + Durations.minutes(rt.knobs.npcWaveMinutes);
        }

        boolean replacementAwaited(long now) {
            return rt.state.npc.respawns.stream().anyMatch(r -> r.due() == replacementDue(now));
        }

        long crumbling() {
            BlockPos c = centre();
            return rt.state.npc.crumbles.stream().filter(k -> Math.abs(k.homeX - c.getX()) <= 1 && Math.abs(k.homeZ - c.getZ()) <= 1).count();
        }

        NpcBook.Site standing() {
            return rt.state.npc.sites.stream().filter(x -> x.chunk().equals(chunk())).findFirst().orElse(null);
        }

        @Override
        public void close() {
            if (closed) return;
            closed = true;
            if (--open == 0) {
                NpcWorld.AUTO = true;
                rt.knobs.npcMaxStrongholds = savedStrongholds;
                rt.knobs.npcMaxOutposts = savedOutposts;
                rt.state.npc.respawns.clear();
            }
            for (int x = cx - 1; x <= cx + 1; x++) {
                for (int z = CHUNK_Z - 1; z <= CHUNK_Z + 1; z++) level.setChunkForced(x, z, false);
            }
        }
    }

    /** Runs a later stage; a failure in it still releases the site. Entities a pass spawns appear on the next tick, so stages wait. */
    private static void later(GameTestHelper helper, Site s, Runnable stage) {
        later(helper, s, 10, stage);
    }

    private static void later(GameTestHelper helper, Site s, int ticks, Runnable stage) {
        helper.runAfterDelay(ticks, () -> {
            try {
                stage.run();
            } catch (RuntimeException | Error e) {
                s.close();
                throw e;
            }
        });
    }

    @GameTest(maxTicks = 200, environment = "charter-gametest:long_siege")
    public void aStrongholdIsRaisedDrainedAndRestored(GameTestHelper helper) {
        Site s = new Site(helper, 0);
        try {
            CharterRuntime rt = s.rt;
            Map<Long, BlockState> before = s.capture();
            String done = NpcWorld.raise(s.server, NpcBook.Kind.STRONGHOLD, s.chunk(), false);
            NpcBook.Site site = s.standing();
            helper.assertTrue(site != null, "No stronghold was raised: " + done);
            OrgId org = site.org();
            helper.assertTrue(rt.state.org(org).orElseThrow().npc, "The stronghold's faction is not a faction of the raiders");
            Core keep = rt.state.core(site.core()).orElseThrow();
            helper.assertTrue(keep.isKeep(), "The stronghold has no keep");
            long diamonds = rt.knobs.npcDiamonds[site.tier()];
            helper.assertTrue(rt.state.org(org).orElseThrow().ledger.balance(Currency.DIAMOND) == diamonds, "The treasury is not stocked by tier");
            helper.assertTrue(s.differences(before) != null, "Raising a stronghold changed nothing in the world");

            NpcBook.Live live = rt.state.npc.live.get(site.id());
            int pool = rt.knobs.npcPoolByTier[site.tier()];
            helper.assertTrue(live != null && live.snapshot != null && !live.snapshot.blocks.isEmpty(), "The site took no snapshot");
            helper.assertTrue(live.raiders.size() == pool, "The first wave should fill the pool of " + pool + ", tracked " + live.raiders.size());
            helper.assertTrue(live.supply == rt.knobs.npcSupplyStronghold[site.tier()] - pool, "The wave did not cost supply: " + live.supply);
            BlockPos c = s.centre();

            later(helper, s, () -> {
                helper.assertTrue(s.raiders().size() == pool, "The raiders should stand in the world (" + pool + "), found " + s.raiders().size());
                // A raider granary shows the raiders left, never an upkeep countdown.
                Granaries.refreshDisplays();
                String granaryText = Granaries.displayText(keep.id);
                helper.assertTrue(granaryText != null && granaryText.contains("Raiders left:") && !granaryText.contains("Provisions"),
                        "The raider granary does not show the raiders left: " + granaryText);
                // Kill them all; the next wave replaces what the supply allows and a kill is not refunded.
                long due = live.nextWaveAt + 1;
                s.killRaiders();
                live.supply = 2;
                NpcWorld.tendSite(s.server, site, due);
                helper.assertTrue(live.raiders.size() == 2, "A short supply should spawn what is left (2), tracked " + live.raiders.size());
                helper.assertTrue(live.supply == 0, "The supply should be spent");
                helper.assertTrue(s.standing() != null, "The site fell while raiders still stood");

                later(helper, s, () -> {
                    // Kill those; with the supply spent and none left, the site falls.
                    s.killRaiders();
                    NpcWorld.tendSite(s.server, site, due + 1);
                    helper.assertTrue(s.standing() == null, "The site did not fall");
                    helper.assertTrue(rt.state.org(org).isEmpty(), "The raiders' faction is still there");
                    helper.assertTrue(rt.state.coresOf(org).isEmpty(), "The keep is still there");
                    helper.assertTrue(s.replacementAwaited(due + 1), "No replacement is awaited");
                    helper.assertTrue(s.crumbling() == 1, "The fallen site is not crumbling");
                    later(helper, s, 40, () -> {
                        try {
                            helper.assertTrue(s.crumbling() == 0, "The site is still crumbling");
                            String drift = s.differences(before);
                            helper.assertTrue(drift == null, "The ground was not restored: " + drift);
                            for (BlockPos p : BlockPos.betweenClosed(c.offset(-14, -4, -14), c.offset(14, 10, 14))) {
                                helper.assertTrue(!(s.level.getBlockEntity(p) instanceof Container), "A container is left at " + p.toShortString());
                            }
                            long spilledDiamonds = 0;
                            for (ItemEntity e : s.level.getEntitiesOfClass(ItemEntity.class, new AABB(c).inflate(12))) {
                                helper.assertTrue(!e.getItem().is(Items.BREAD), "The granary's bread was left on the ground");
                                if (e.getItem().is(Items.DIAMOND)) spilledDiamonds += e.getItem().getCount();
                                e.discard();
                            }
                            helper.assertTrue(spilledDiamonds == diamonds / 4, "The fall loot should be a quarter of the diamonds (" + diamonds / 4 + "), was " + spilledDiamonds);
                            helper.assertTrue(s.raiders().isEmpty(), "Raiders stayed after the site fell");
                            helper.succeed();
                        } finally {
                            s.close();
                        }
                    });
                });
            });
        } catch (RuntimeException | Error e) {
            s.close();
            throw e;
        }
    }

    @GameTest(maxTicks = 200, environment = "charter-gametest:long_siege")
    public void aSiteNobodyFoughtIsCleanedUpAndReplaced(GameTestHelper helper) {
        Site s = new Site(helper, 1);
        try {
            CharterRuntime rt = s.rt;
            Map<Long, BlockState> before = s.capture();
            NpcWorld.raise(s.server, NpcBook.Kind.STRONGHOLD, s.chunk(), false);
            NpcBook.Site site = s.standing();
            helper.assertTrue(site != null, "No stronghold was raised");
            NpcBook.Live live = rt.state.npc.live.get(site.id());
            long now = s.clock();
            live.lastFoughtAt = now - Durations.days(rt.knobs.npcExpireDays + 1);
            NpcWorld.tendSite(s.server, site, now);
            helper.assertTrue(s.standing() == null, "A site nobody fought for ten days still stands");
            helper.assertTrue(s.replacementAwaited(now), "No replacement is awaited");
            later(helper, s, 40, () -> {
                try {
                    String drift = s.differences(before);
                    helper.assertTrue(drift == null, "The ground was not restored: " + drift);
                    helper.assertTrue(s.raiders().isEmpty(), "Raiders stayed after the site was cleaned up");
                    helper.succeed();
                } finally {
                    s.close();
                }
            });
        } catch (RuntimeException | Error e) {
            s.close();
            throw e;
        }
    }

    @GameTest(maxTicks = 200, environment = "charter-gametest:long_siege")
    public void anOutpostFallsAndLeavesNothing(GameTestHelper helper) {
        Site s = new Site(helper, 2);
        try {
            CharterRuntime rt = s.rt;
            Map<Long, BlockState> before = s.capture();
            String done = NpcWorld.raise(s.server, NpcBook.Kind.OUTPOST, s.chunk(), false);
            NpcBook.Site site = s.standing();
            helper.assertTrue(site != null, "No outpost was raised: " + done);
            Core camp = rt.state.core(site.core()).orElseThrow();
            helper.assertTrue(!camp.isKeep() && camp.phase() == charter.land.CampPhase.ACTIVE, "The outpost is not a raised camp");
            helper.assertTrue(rt.state.siege.store(camp.id).provisions.count("minecraft:bread") > 0, "The outpost has no store");
            int pool = rt.knobs.npcOutpostPool[site.tier()];
            helper.assertTrue(rt.state.npc.live.get(site.id()).raiders.size() == pool && pool >= 1 && pool <= 3, "An outpost has one to three raiders");

            // The siege route: its core is lost, the next pass cleans it up the same way.
            rt.engine.run(new Actor.Admin("npc test"), new LoseCore(camp.id));
            later(helper, s, () -> {
                long now = s.clock();
                NpcWorld.sweepFallen(s.server, now);
                helper.assertTrue(s.standing() == null, "The fallen outpost is still listed");
                helper.assertTrue(s.replacementAwaited(now), "No replacement is awaited for the outpost");
                later(helper, s, 40, () -> {
                    try {
                        helper.assertTrue(s.raiders().isEmpty(), "The raiders stayed after the outpost fell");
                        String drift = s.differences(before);
                        helper.assertTrue(drift == null, "The ground was not restored: " + drift);
                        helper.succeed();
                    } finally {
                        s.close();
                    }
                });
            });
        } catch (RuntimeException | Error e) {
            s.close();
            throw e;
        }
    }

    @GameTest(maxTicks = 200, environment = "charter-gametest:long_siege")
    public void aFallenSiteCrumblesFromTheTopDown(GameTestHelper helper) {
        Site s = new Site(helper, 4);
        try {
            CharterRuntime rt = s.rt;
            BlockPos c = s.centre();
            // A block just outside the footprint: the crumble must never touch it.
            BlockPos marker = c.offset(13, 0, 0);
            s.level.setBlock(marker, Blocks.DIAMOND_BLOCK.defaultBlockState(), 2);
            Map<Long, BlockState> before = s.capture();
            NpcWorld.raise(s.server, NpcBook.Kind.STRONGHOLD, s.chunk(), false);
            NpcBook.Site site = s.standing();
            helper.assertTrue(site != null, "No stronghold was raised");
            BlockPos wallTop = c.offset(-8, 3, 0);
            BlockPos pad = c.offset(-8, -1, 0);
            helper.assertTrue(s.level.getBlockState(wallTop).is(Blocks.COBBLESTONE) && s.level.getBlockState(pad).is(Blocks.STONE_BRICKS),
                    "The stronghold's wall and pad are not standing");
            rt.knobs.npcCrumbleTicksPerLayer = 4;
            long now = s.clock();
            rt.state.npc.live.get(site.id()).lastFoughtAt = now - Durations.days(rt.knobs.npcExpireDays + 1);
            NpcWorld.tendSite(s.server, site, now);
            helper.assertTrue(s.standing() == null && s.crumbling() == 1, "The site did not begin to crumble");
            helper.assertTrue(s.level.getBlockState(wallTop).is(Blocks.COBBLESTONE), "Nothing may come down in the same tick the site falls");

            later(helper, s, 6, () -> {
                helper.assertTrue(s.level.getBlockState(wallTop).isAir(), "The top layer did not come down first");
                helper.assertTrue(s.level.getBlockState(pad).is(Blocks.STONE_BRICKS), "The ground layer came down with the top");
                helper.assertTrue(s.crumbling() == 1, "The crumble ended after one layer");
                later(helper, s, 40, () -> {
                    try {
                        helper.assertTrue(s.crumbling() == 0, "The crumble never finished");
                        String drift = s.differences(before);
                        helper.assertTrue(drift == null, "The ground was not restored: " + drift);
                        helper.assertTrue(s.level.getBlockState(marker).is(Blocks.DIAMOND_BLOCK), "A block outside the footprint was touched");
                        s.level.setBlock(marker, Blocks.AIR.defaultBlockState(), 2);
                        helper.succeed();
                    } finally {
                        s.close();
                    }
                });
            });
        } catch (RuntimeException | Error e) {
            s.close();
            throw e;
        }
    }

    @GameTest(maxTicks = 200, environment = "charter-gametest:long_siege")
    public void clearRuinsClearsWhatNothingClaims(GameTestHelper helper) {
        Site s = new Site(helper, 3);
        try {
            CharterRuntime rt = s.rt;
            NpcWorld.raise(s.server, NpcBook.Kind.STRONGHOLD, s.chunk(), false);
            NpcBook.Site site = s.standing();
            helper.assertTrue(site != null, "No stronghold was raised");
            BlockPos c = s.centre();
            // Inside the claim the command refuses and changes nothing.
            s.command("charter admin npc clearruins " + c.getX() + " " + c.getZ());
            helper.assertTrue(s.level.getBlockState(c.offset(-8, 0, 0)).is(Blocks.COBBLESTONE), "The command cleared a claimed stronghold");

            // Forget the site the way an old save would have: the world stays, the claim and the book go.
            s.killRaiders();
            rt.engine.run(new Actor.Admin("npc test"), new charter.org.actions.DissolveOrg(site.org()));
            rt.state.npc.sites.removeIf(x -> x.id() == site.id());
            rt.state.npc.live.remove(site.id());
            rt.claims.rebuild();
            helper.assertTrue(s.level.getBlockState(c.offset(-8, 0, 0)).is(Blocks.COBBLESTONE), "The ruin is not standing");
            s.command("charter admin npc clearruins " + c.getX() + " " + c.getZ());
            boolean left = false;
            for (BlockPos p : BlockPos.betweenClosed(c.offset(-10, -4, -10), c.offset(10, 8, 10))) {
                if (s.level.getBlockState(p).is(Blocks.COBBLESTONE) || s.level.getBlockState(p).is(Blocks.LODESTONE)
                        || s.level.getBlockState(p).is(Blocks.CHEST) || s.level.getBlockState(p).is(Blocks.STONE_BRICKS)) left = true;
            }
            helper.assertTrue(!left, "The ruin was not cleared");
            helper.succeed();
        } finally {
            s.close();
        }
    }
}
