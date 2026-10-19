package pocketdungeons;

import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.SignBlockEntity;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Task T5 (campaign endgame, 2026-10-18): the Chart Room and its uncharted doors. The astrolabe shows the Chart
 * Room as its last position only once the campaign is done; its three doors lead to generated dungeons the whole
 * pipeline (offers, affixes, the trip's own dungeon, scrap rules, the finale) can find; and the final floor's
 * guardian spawns under a phased boss fight and keeps the pad shut until it falls.
 *
 * <p>The gametest world has no dungeon dimension, so the commit's stamping is covered by the plan-level sweep in
 * {@code UnchartedSoakGameTest} and by the live check; here the commit is proven up to the stamp.
 */
public final class ChartRoomGameTest {

    private static final String SPAWNER = "pocketdungeons:spawner_dungeon";
    private static final String ANCIENT_CITY = "pocketdungeons:ancient_city";

    /** A player whose campaign is done (the last capstone finished) at the given stored compass. */
    private static UUID doneOwner(MinecraftServer server, int compass) {
        UUID id = UUID.randomUUID();
        DungeonLog log = DungeonLog.forServer(server);
        log.setTaskProgress(id, DungeonProgress.PROLOGUE_KEY, 1);
        log.unlockAct(id, 2);
        log.addDungeonFinished(id, SPAWNER);
        log.addDungeonFinished(id, ANCIENT_CITY);
        log.setCompass(id, compass, 0);
        return id;
    }

    // ---- the astrolabe ------------------------------------------------------------------------------

    @GameTest(maxTicks = 40)
    public void theChartRoomShowsOnlyAfterTheCampaignIsDone(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        // A player in the middle of the campaign: act 2 open, the last capstone not finished.
        UUID midway = UUID.randomUUID();
        DungeonLog log = DungeonLog.forServer(server);
        log.setTaskProgress(midway, DungeonProgress.PROLOGUE_KEY, 1);
        log.unlockAct(midway, 2);
        log.addDungeonFinished(midway, SPAWNER);
        helper.assertTrue(!Campaign.done(log.get(midway)), "the campaign is not done");
        HallOffers.Hall before = HallOffers.build(server, midway, HallLayout.CHART_ACT);
        helper.assertTrue(!before.isChart() && !before.chartOpen(),
                "asking a campaign player for the Chart Room shows an act instead");
        for (int act : before.openActs()) {
            helper.assertTrue(HallLayout.nextPosition(before.openActs(), act, false, Campaign.releaseMaxAct(),
                    before.chartOpen()) != HallLayout.CHART_ACT, "the astrolabe never turns to it");
        }

        // The same player once the last capstone is finished.
        UUID owner = doneOwner(server, 12);
        HallOffers.Hall hall = HallOffers.build(server, owner, HallLayout.CHART_ACT);
        helper.assertTrue(Campaign.done(log.get(owner)) && hall.chartOpen(), "the campaign is done: the room is open");
        helper.assertTrue(hall.isChart(), "and the astrolabe can stand on it");
        helper.assertValueEqual(hall.doors().size(), 3, "the main wall holds three uncharted doors");
        for (int slot = 1; slot <= hall.doors().size(); slot++) {
            DungeonDef door = hall.dungeon(slot);
            helper.assertTrue(Campaign.isUncharted(door), "slot " + slot + " is an uncharted door");
            helper.assertTrue(!hall.status(slot).locked(), "slot " + slot + " is open");
        }
        DungeonDef dive = HallOffers.chartDive(DungeonDefs.current());
        helper.assertValueEqual(hall.specials().size(), dive == null ? 0 : 1,
                "the dive stands on the room's end exactly when its dungeon is loaded");
        int lastAct = Campaign.cap(hall.openActs(), Campaign.releaseMaxAct()).stream().max(Integer::compare).orElse(1);
        helper.assertValueEqual(HallLayout.nextPosition(hall.openActs(), lastAct, false, Campaign.releaseMaxAct(), true),
                HallLayout.CHART_ACT, "the Chart Room is the astrolabe's last position");
        helper.assertValueEqual(HallLayout.nextPosition(hall.openActs(), HallLayout.CHART_ACT, false,
                Campaign.releaseMaxAct(), true), Campaign.cap(hall.openActs(), Campaign.releaseMaxAct()).stream()
                .min(Integer::compare).orElse(1), "and the cycle wraps to the first act");
        // Asked for no act in particular, the room opens on an act that still has something to do.
        HallOffers.Hall opened = HallOffers.build(server, owner, 0);
        helper.assertTrue(opened.chartOpen(), "the cycle includes the Chart Room whichever position it opens on");
        helper.succeed();
    }

    @GameTest(maxTicks = 60)
    public void theRoomPaintsThreeOpenDoorsSignedUncharted(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        MinecraftServer server = level.getServer();
        UUID owner = doneOwner(server, 9);
        DoorMask.Direction wall = DoorMask.Direction.SOUTH;
        BlockPos o = helper.absolutePos(new BlockPos(0, 1, 0));
        InstanceRecord record = new InstanceRecord(9801, o, server.getTickCount(), null, Set.of(), owner, false);
        record.stagingCellOrigin = o;
        record.roomDungeonDoor = wall;
        record.interval.hallAct = HallLayout.CHART_ACT;
        try {
            HallRoom.arm(server, level, o, wall, owner, record);
            helper.assertValueEqual(record.interval.hallAct, HallLayout.CHART_ACT, "the room stays on the Chart Room");
            HallOffers.Hall hall = HallOffers.of(server, record);
            int[] alongs = HallOffers.absoluteAlongs(wall, hall.doors().size(), hall.specials().size());
            for (int slot = 1; slot <= hall.doors().size(); slot++) {
                int along = alongs[slot - 1];
                String door = BuiltInRegistries.BLOCK.getKey(level.getBlockState(StagingFurniture.hallDoorPos(o, wall, along))
                        .getBlock()).getPath();
                helper.assertTrue(!door.equals("iron_door"), "slot " + slot + " is not locked: " + door);
                BlockPos signPos = StagingFurniture.hallFrontPos(o, wall, along, 1);
                helper.assertTrue(level.getBlockEntity(signPos) instanceof SignBlockEntity, "slot " + slot + " has a sign");
                SignBlockEntity sign = (SignBlockEntity) level.getBlockEntity(signPos);
                helper.assertValueEqual(sign.getFrontText().getMessage(0, false).getString(), "Uncharted",
                        "the sign says Uncharted on slot " + slot);
                helper.assertTrue(!sign.getFrontText().getMessage(1, false).getString().isEmpty(),
                        "and names the door's theme on slot " + slot);
            }
            List<Entity> parts = level.getEntitiesOfClass(Entity.class, CellGeometry.cellBounds(o),
                    e -> e.entityTags().contains(HallRoom.TAG));
            helper.assertValueEqual(parts.size(), 3, "the astrolabe stands in the Chart Room too");
        } finally {
            HallRoom.dismantle(level, o, wall);
        }
        helper.succeed();
    }

    // ---- the doors ------------------------------------------------------------------------------------

    @GameTest(maxTicks = 40)
    public void aDoorCommitsToItsOwnGeneratedDungeon(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        UUID owner = doneOwner(server, 14);
        DungeonLog log = DungeonLog.forServer(server);
        InstanceRecord record = new InstanceRecord(9802, helper.absolutePos(BlockPos.ZERO), server.getTickCount(),
                null, Set.of(), owner, false);
        record.members.put(owner, new ReturnPoint(Level.OVERWORLD, Vec3.ZERO, 0.0f, 0.0f));
        record.interval.hallAct = HallLayout.CHART_ACT;

        Keystone.Offer[] offers = Keystone.offers(server, record, owner, log.get(owner).keystoneLevel());
        helper.assertTrue(offers.length >= 3, "three uncharted offers stand (and the dive when loaded)");
        Set<String> ids = new java.util.HashSet<>();
        for (int i = 0; i < 3; i++) {
            Keystone.Offer offer = offers[i];
            helper.assertTrue(UnchartedGen.isUnchartedId(offer.dungeonId()), "offer " + i + " is a generated door");
            DungeonDef def = DungeonDefs.current().byId(offer.dungeonId());
            helper.assertTrue(def != null && Campaign.isUncharted(def), "every lookup finds the generated dungeon");
            helper.assertValueEqual(offer.nodeId(), def.entry().id(), "and opens at its entry floor");
            helper.assertValueEqual(offer.level(), KeystoneMath.upgrade(14, offer.step(), PocketDungeonsConfig.keystoneMaxLevel()),
                    "the level is the compass plus the door step");
            Set<String> dealt = Keystone.dealtAffixes(owner, offer);
            helper.assertValueEqual(dealt.size(), AffixMath.seededCount(offer.level()),
                    "the affix count follows the level thresholds");
            helper.assertTrue(UnchartedGen.ruleIds(dealt, AffixManifest.current().definitions()).size()
                    >= (dealt.size() + 1) / 2, "at least half the affixes are rules: " + dealt);
            helper.assertTrue(Keystone.dealtAffixes(owner, offer).equals(dealt), "a preview and its commit deal the same set");
            ids.add(offer.dungeonId());
        }
        helper.assertValueEqual(ids.size(), 3, "three different dungeons");

        // The doors are stable while the player looks, and a trip rerolls them.
        Keystone.Offer[] again = Keystone.offers(server, record, owner, log.get(owner).keystoneLevel());
        helper.assertValueEqual(again[0].dungeonId(), offers[0].dungeonId(), "the same doors on a second look");
        log.beginTrip(owner);
        Keystone.Offer[] next = Keystone.offers(server, record, owner, log.get(owner).keystoneLevel());
        helper.assertTrue(!next[0].dungeonId().equals(offers[0].dungeonId()), "a trip rerolls the doors");

        // The commit pins the dungeon on the trip: it survives the registry forgetting it.
        Keystone.Offer taken = offers[1];
        record.interval.dungeonId = taken.dungeonId();
        record.interval.nodeId = taken.nodeId();
        record.interval.generatedDef = UnchartedDoors.pin(taken.dungeonId());
        helper.assertTrue(record.interval.generatedDef != null, "the commit pins the generated dungeon");
        UnchartedDoors.forgetAll();
        helper.assertTrue(DungeonDefs.current().byId(taken.dungeonId()) == null, "the registry forgot it");
        DungeonDef trip = TripView.def(record);
        helper.assertTrue(trip != null && trip.id().equals(taken.dungeonId()), "but the trip still finds its dungeon");
        helper.assertTrue(!Campaign.isCampaignTrip(record), "an uncharted trip is not a campaign trip: scrap pays");
        helper.assertTrue(!TripView.onFinal(record), "the entry floor is not the final");
        record.interval.nodeId = UnchartedGen.FINAL_NODE;
        helper.assertTrue(TripView.onFinal(record), "the third floor is");

        // It never counts toward the campaign.
        helper.assertTrue(DungeonDefs.current().all().stream().noneMatch(Campaign::isUncharted),
                "no generated dungeon is ever in the loaded set");
        helper.assertTrue(!ActProgress.progressLine(2, log.get(owner).dungeonsFinished(),
                log.get(owner).campaign().mineDepth(), DungeonDefs.current().all()).contains("ncharted"),
                "no act line names an uncharted door");
        helper.succeed();
    }

    // ---- the guardian -----------------------------------------------------------------------------------

    @GameTest(maxTicks = 100)
    public void theGuardianSpawnsOnTheFinalFloorAndFightsInPhases(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        MinecraftServer server = level.getServer();
        int slotNumber = 9803;
        for (int want = 1; want <= 3; want++) {
            DungeonDef def = defWithGuardianSlot(server, want);
            Run run = new Run(helper, slotNumber, def);
            try {
                helper.assertTrue(FinaleScripts.forRecord(run.record) == UnchartedFinale.INSTANCE,
                        "a generated dungeon runs the uncharted finale");
                helper.assertTrue(FinaleWave.finaleOf(run.record) != null, "its final floor holds a finale");
                run.step(false);
                helper.assertTrue(!run.state().triggered, "the finale waits for the spawners to clear");
                run.step(true);
                helper.assertTrue(run.state().triggered, "cleared spawners wake the guardian");
                run.state().spawnTick = 0;
                run.step(false);
                helper.assertTrue(run.state().spawned, "the guardian stands (slot " + want + ", act "
                        + UnchartedFinale.guardianAct(def) + ")");
                int standing = run.state().mobs.size();
                helper.assertTrue(standing >= 1, "its mobs count toward the finale: " + standing);
                helper.assertTrue(run.state().elite != null && run.state().bar != null,
                        "the strongest of them leads the boss bar");
                Mob boss = (Mob) level.getEntity(run.state().elite);
                helper.assertTrue(boss != null && boss.isAlive() && boss.entityTags().contains(MineGuardians.GUARDIAN_TAG),
                        "the elite is a guardian mob: " + boss + " " + (boss == null ? "" : boss.entityTags()));
                helper.assertTrue(FinaleWave.padRefusal(level, run.record) != null,
                        "the pad stays shut while the guardian lives");

                // Phase two: the guardian staggers and calls its guard.
                boss.setHealth(boss.getMaxHealth() * 0.5f);
                run.step(false);
                helper.assertTrue(boss.isInvulnerable() && boss.isNoAi(), "crossing two thirds staggers it");
                run.fight().staggerEnd = 0;
                run.step(false);
                helper.assertTrue(run.state().mobs.size() > standing, "and its guard arrives");

                // Phase three on its own stagger.
                boss.setHealth(boss.getMaxHealth() * 0.2f);
                run.step(false);
                run.fight().staggerEnd = 0;
                run.step(false);
                helper.assertTrue(boss.hasEffect(net.minecraft.world.effect.MobEffects.STRENGTH),
                        "the last phase enrages it");

                // Down: the pad opens.
                for (java.util.UUID id : new ArrayList<>(run.state().mobs)) {
                    Entity mob = level.getEntity(id);
                    if (mob != null) {
                        mob.discard();
                    }
                }
                run.step(false);
                helper.assertTrue(run.record.floor.finaleWon, "the last stand is won");
                helper.assertTrue(FinaleWave.padRefusal(level, run.record) == null, "and the pad opens");
            } finally {
                run.close();
            }
            slotNumber++;
        }
        helper.succeed();
    }

    /** A generated dungeon whose guardian slot is {@code want}, found by walking trips. */
    private static DungeonDef defWithGuardianSlot(MinecraftServer server, int want) {
        UUID owner = new UUID(77, want);
        for (int trip = 0; trip < 200; trip++) {
            for (DungeonDef def : UnchartedGen.doors(owner, trip, 12, 2,
                    Set.of(SPAWNER, ANCIENT_CITY), DungeonDefs.current().all())) {
                if (UnchartedGen.guardianSlot(def.id()) == want) {
                    return def;
                }
            }
        }
        throw new IllegalStateException("no generated dungeon with guardian slot " + want);
    }

    /** One generated dungeon's final floor with one mock member on open ground. */
    private static final class Run {
        final GameTestHelper helper;
        final ServerLevel level;
        final MinecraftServer server;
        final ServerPlayer member;
        final InstanceRecord record;
        final DungeonDef def;
        final BlockPos origin;

        Run(GameTestHelper helper, int slot, DungeonDef def) {
            this.helper = helper;
            this.def = def;
            this.level = helper.getLevel();
            this.server = level.getServer();
            @SuppressWarnings("removal")
            ServerPlayer player = helper.makeMockServerPlayerInLevel();
            this.member = player;
            player.setGameMode(GameType.SURVIVAL);
            BlockPos where = helper.absolutePos(new BlockPos(1, 2, 1));
            player.teleportTo(level, where.getX() + 0.5, where.getY(), where.getZ() + 0.5, Set.of(), 0.0F, 0.0F, false);
            this.origin = helper.absolutePos(BlockPos.ZERO);
            PlanGeometry geometry = PlanGeometry.of(origin, List.of(new PlanCell(0, 0)));
            InstanceLayout layout = new InstanceLayout(origin, geometry, origin.offset(3, 1, 3), 0.0f,
                    origin.offset(8, 1, 8), geometry.bounds(), 1L, 4, 4, 1, true, Set.of(), 3, origin,
                    0, 0, Set.of(), null, Set.of());
            this.record = new InstanceRecord(slot, origin, server.getTickCount(), layout, Set.of(), player.getUUID(), false);
            record.members.put(player.getUUID(), new ReturnPoint(Level.OVERWORLD, Vec3.ZERO, 0.0f, 0.0f));
            record.phase = RunSession.Phase.ACTIVE;
            record.interval.dungeonId = def.id();
            record.interval.generatedDef = def;
            record.interval.nodeId = UnchartedGen.FINAL_NODE;
            record.floor.chosenLevel = 14;
            for (int dx = -12; dx <= 14; dx++) {
                for (int dz = -12; dz <= 14; dz++) {
                    // The terminal cell's floor is the origin's own layer; the guardian stands one block above it.
                    level.setBlock(origin.offset(dx, 0, dz), Blocks.STONE.defaultBlockState(), 3);
                    for (int dy = 1; dy <= 5; dy++) {
                        level.setBlock(origin.offset(dx, dy, dz), Blocks.AIR.defaultBlockState(), 3);
                    }
                }
            }
        }

        void step(boolean due) {
            FinaleWave.step(server, level, record, def.finale(), List.of(member), due, "The Guardian");
        }

        FinaleWave.State state() {
            return FinaleWave.stateOf(record);
        }

        BossFight fight() {
            return ((UnchartedFinale.State) state().scriptState.get(UnchartedFinale.State.class)).fight;
        }

        void close() {
            FinaleWave.State state = FinaleWave.stateOf(record);
            if (state != null) {
                for (java.util.UUID id : new ArrayList<>(state.mobs)) {
                    Entity mob = level.getEntity(id);
                    if (mob != null) {
                        mob.discard();
                    }
                }
            }
            FinaleWave.teardown(level, record.slot);
        }
    }
}
