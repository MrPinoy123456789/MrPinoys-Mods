package pocketdungeons;

import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.monster.cubemob.Slime;
import net.minecraft.world.entity.projectile.throwableitemprojectile.ThrownSplashPotion;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Roadmap T23: the Lush Caves finale script and the potion drip. The gametest world has no dungeon dimension,
 * so the finale is driven through {@link FinaleWave#step} as {@link FinaleScriptGameTest} does. A real slime
 * splits about a second after it dies; the test stands in for that by spawning the vanilla children by hand
 * while the dead boss is still in the world, then removing it, which is the order vanilla uses. What only a
 * live server can show is a real kill producing the children.
 */
public final class LushFinaleGameTest {

    private static final String DUNGEON = "pocketdungeons:test_lush_finale";
    private static final DungeonDef.Finale FINALE = new DungeonDef.Finale(
            List.of(new DungeonDef.FinaleMob("minecraft:drowned", 4), new DungeonDef.FinaleMob("minecraft:slime", 2)),
            50, null);

    @GameTest(maxTicks = 60)
    public void theBossTakesTheWaveAndSplitsIntoDouble(GameTestHelper helper) {
        Run run = new Run(helper, 9981, false);
        try {
            run.step(true);
            run.state().spawnTick = 0;
            run.step(false);
            helper.assertValueEqual(run.state().mobs.size(), 1, "one giant slime replaces the data wave");
            Slime boss = run.slime(0);
            helper.assertValueEqual(boss.getSize(), PocketDungeonsConfig.lushSlimeSize(), "the boss is the knob size");
            helper.assertTrue(run.state().bar != null && run.state().elite != null, "it leads the boss bar");
            helper.assertTrue(PotionDrip.isBoss(run.record), "the drip is on its boss rate");

            // The boss dies. Its body is still in the world; the wave must not read as won.
            boss.setHealth(0.0f);
            run.step(false);
            helper.assertTrue(!run.state().done, "a dying boss whose children are not out yet does not win the finale");
            // Vanilla now removes the body and has spawned three children of half the size.
            int childSize = LushFinaleRules.childSize(boss.getSize());
            List<Slime> vanilla = run.spawnChildren(3, childSize, boss.blockPosition());
            boss.discard();
            run.clearStrangers(boss.blockPosition(), vanilla);
            run.step(false);
            helper.assertValueEqual(run.countTagged(), 6, "three vanilla children are topped up to six");
            helper.assertTrue(!run.state().done, "the children keep the pad shut");
            for (Slime child : vanilla) {
                helper.assertTrue(run.state().mobs.contains(child.getUUID()), "a vanilla child counts toward the wave");
            }

            // A child dies and splits: later splits are vanilla (two grandchildren stay two).
            Slime child = vanilla.get(0);
            child.setHealth(0.0f);
            run.step(false);
            List<Slime> grand = run.spawnChildren(2, LushFinaleRules.childSize(childSize), child.blockPosition());
            child.discard();
            run.clearStrangers(child.blockPosition(), grand);
            run.step(false);
            helper.assertValueEqual(run.countTagged(), 7, "five children left plus two grandchildren, no doubling");
            helper.assertTrue(!run.state().done, "still not won");

            // Everything dies; the finale is won and the drip goes back to normal.
            for (int round = 0; round < 6 && !run.state().done; round++) {
                for (Slime slime : run.liveSlimes()) {
                    slime.discard();
                }
                run.step(false);
            }
            helper.assertTrue(run.state().done && run.record.floor.finaleWon, "the last slime wins the finale");
            helper.assertTrue(!PotionDrip.isBoss(run.record), "the drip returns to its normal rate");
        } finally {
            run.close();
        }
        helper.succeed();
    }

    @GameTest(maxTicks = 40)
    public void aBossThatLeavesNoChildrenEndsTheFinale(GameTestHelper helper) {
        Run run = new Run(helper, 9982, false);
        try {
            run.step(true);
            run.state().spawnTick = 0;
            run.step(false);
            Slime boss = run.slime(0);
            boss.discard();
            run.clearStrangers(boss.blockPosition(), List.of());
            run.step(false);
            // A removed body (not killed through the normal path) still resolves to a finished wave.
            for (int i = 0; i < 4 && !run.state().done; i++) {
                run.step(false);
            }
            helper.assertTrue(run.state().done, "a boss that leaves no children ends the finale once the split window is checked");
        } finally {
            run.close();
        }
        helper.succeed();
    }

    @GameTest(maxTicks = 40)
    public void theDripNeverFallsInTheEntranceRoomAndFallsElsewhere(GameTestHelper helper) {
        Run entrance = new Run(helper, 9983, true);
        Run elsewhere = new Run(helper, 9984, false);
        try {
            helper.assertTrue(PotionDrip.release(entrance.level, entrance.record, entrance.member,
                    PotionDripRules.Brew.POISON) == null, "no drip in the entrance room");
            UUID id = PotionDrip.release(elsewhere.level, elsewhere.record, elsewhere.member,
                    PotionDripRules.Brew.POISON);
            helper.assertTrue(id != null, "a brew falls in another room");
            Entity potion = elsewhere.level.getEntity(id);
            helper.assertTrue(potion instanceof ThrownSplashPotion && potion.entityTags().contains(PotionDrip.TAG),
                    "it is a tagged vanilla splash potion");
            helper.assertTrue(potion.getY() > elsewhere.member.getY() + 2.0, "it starts well above the player");
            helper.assertTrue(potion.getDeltaMovement().y < 0.0 && potion.getDeltaMovement().y > -0.5,
                    "it falls at a visible speed, not at once");
            PotionDrip.floorEnded(elsewhere.level, elsewhere.record);
            Entity after = elsewhere.level.getEntity(id);
            helper.assertTrue(after == null || !after.isAlive(), "floor end removes a falling potion");
        } finally {
            entrance.close();
            elsewhere.close();
        }
        helper.succeed();
    }

    /** One scripted Lush Caves finale on a hand built two cell record with one mock member. */
    private static final class Run {
        final GameTestHelper helper;
        final ServerLevel level;
        final MinecraftServer server;
        final ServerPlayer member;
        final InstanceRecord record;

        Run(GameTestHelper helper, int slot, boolean entranceHere) {
            this.helper = helper;
            this.level = helper.getLevel();
            this.server = level.getServer();
            @SuppressWarnings("removal")
            ServerPlayer player = helper.makeMockServerPlayerInLevel();
            this.member = player;
            player.setGameMode(GameType.SURVIVAL);
            BlockPos where = helper.absolutePos(new BlockPos(1, 2, 1));
            player.teleportTo(level, where.getX() + 0.5, where.getY(), where.getZ() + 0.5, Set.of(), 0.0F, 0.0F,
                    false);
            BlockPos origin = helper.absolutePos(BlockPos.ZERO);
            PlanGeometry geometry = PlanGeometry.of(origin, List.of(new PlanCell(0, 0), new PlanCell(1, 0)));
            BlockPos entrance = entranceHere ? origin.offset(3, 1, 3) : origin.offset(RoomGeometry.CELL + 3, 1, 3);
            InstanceLayout layout = new InstanceLayout(origin, geometry, entrance, 0.0f,
                    origin.offset(8, 1, 8), geometry.bounds(), 1L, 4, 4, 1, true, Set.of(), 3, origin,
                    0, 0, Set.of(), null, Set.of());
            this.record = new InstanceRecord(slot, origin, server.getTickCount(), layout, Set.of(),
                    player.getUUID(), false);
            record.members.put(player.getUUID(), new ReturnPoint(Level.OVERWORLD, Vec3.ZERO, 0.0f, 0.0f));
            record.phase = RunSession.Phase.ACTIVE;
            record.interval.dungeonId = DUNGEON;
            record.interval.floorIndex = 3;
            if (!FinaleScripts.has(DUNGEON)) {
                FinaleScripts.register(DUNGEON, new LushFinale());
            }
        }

        void step(boolean due) {
            FinaleWave.step(server, level, record, FINALE, List.of(member), due, "Test Room");
        }

        FinaleWave.State state() {
            return FinaleWave.stateOf(record);
        }

        Slime slime(int n) {
            Entity entity = level.getEntity(state().mobs.get(n));
            helper.assertTrue(entity instanceof Slime, "mob " + n + " is a slime");
            return (Slime) entity;
        }

        /** Spawns untagged children of a dead slime, as vanilla's split does. */
        List<Slime> spawnChildren(int count, int size, BlockPos at) {
            List<Slime> out = new ArrayList<>();
            for (int i = 0; i < count; i++) {
                Mob mob = FinaleWave.spawn(level, "minecraft:slime", at, level.getRandom());
                helper.assertTrue(mob instanceof Slime, "a child spawned");
                Slime child = (Slime) mob;
                child.removeTag(FinaleWave.TAG);
                child.setSize(size, true);
                child.setPos(at.getX() + 0.5 + i * 0.3, at.getY(), at.getZ() + 0.5);
                out.add(child);
            }
            return out;
        }

        /** Removes every slime near {@code at} that is not in {@code keep} or the wave: the test world spawns strays at this depth. */
        void clearStrangers(BlockPos at, List<Slime> keep) {
            for (Slime slime : level.getEntitiesOfClass(Slime.class, new net.minecraft.world.phys.AABB(at).inflate(24.0))) {
                if (!keep.contains(slime) && !state().mobs.contains(slime.getUUID())) {
                    slime.discard();
                }
            }
        }

        /** The slimes of the wave that are alive. */
        List<Slime> liveSlimes() {
            List<Slime> out = new ArrayList<>();
            for (UUID id : state().mobs) {
                Entity entity = level.getEntity(id);
                if (entity instanceof Slime slime && slime.isAlive()) {
                    out.add(slime);
                }
            }
            return out;
        }

        /** How many slimes of the wave stand alive (the guard bat is not one). */
        int countTagged() {
            return liveSlimes().size();
        }

        void close() {
            FinaleWave.teardown(level, record.slot);
            PotionDrip.teardown(level, record.slot, record.layout);
            FinaleScripts.unregister(DUNGEON);
        }
    }
}
