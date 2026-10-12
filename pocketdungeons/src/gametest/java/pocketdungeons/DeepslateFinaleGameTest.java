package pocketdungeons;

import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.stats.Stats;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.monster.warden.Warden;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.List;
import java.util.Set;

/**
 * Roadmap T31: the Silent Deep's Hush driven through {@link FinaleWave#step} as the finale-script skill
 * describes. A sensor room is armed at the test origin with the Silent Deep's template id, the script takes
 * its Heard meter, and the tests fill it with each kind of noise, run the tell and the answer, and check the
 * whelp, the darkness, that no real Warden ever comes, and that the room is given back when the floor ends.
 */
public final class DeepslateFinaleGameTest {

    private static final DungeonDef.Finale FINALE = new DungeonDef.Finale(
            List.of(new DungeonDef.FinaleMob("minecraft:pig", 4)), 0, null);

    @GameTest(maxTicks = 80)
    public void theScriptTakesTheRoomsMeter(GameTestHelper helper) {
        helper.assertTrue(FinaleScripts.forDungeon("pocketdungeons:deepslate") instanceof DeepslateFinale,
                "the Deepslate dungeon has a finale script");
        Run run = new Run(helper, 9965, true);
        try {
            run.start();
            int[] meter = PressureSources.heardAt(run.record, run.origin.offset(8, 1, 8));
            helper.assertTrue(meter != null, "the room has a Heard meter");
            helper.assertValueEqual(meter[0], 0, "the fight starts empty");
            helper.assertValueEqual(meter[1], PocketDungeonsConfig.hushCeiling(), "the ceiling is the knob");
            FinaleWave.floorEnded(run.level, run.record);
            int[] after = PressureSources.heardAt(run.record, run.origin.offset(8, 1, 8));
            helper.assertValueEqual(after[1], SculkOmen.heardMax(false), "the room is its own again");
        } finally {
            run.close();
        }
        helper.succeed();
    }

    @GameTest(maxTicks = 80)
    public void eachNoiseFillsTheMeter(GameTestHelper helper) {
        Run run = new Run(helper, 9966, true);
        try {
            run.start();
            run.step();
            helper.assertValueEqual(run.heard(), 0, "a quiet second adds nothing");
            run.level.getEntity(run.state().mobs.get(0)).discard();
            run.step();
            helper.assertValueEqual(run.heard(), PocketDungeonsConfig.hushDeathRise(), "a death");
            int before = run.heard();
            run.member.setHealth(run.member.getHealth() - 2.0f);
            run.step();
            helper.assertValueEqual(run.heard(), before + PocketDungeonsConfig.hushHitRise(), "a hit taken");
            before = run.heard();
            run.member.getStats().increment(run.member, Stats.ITEM_USED.get(Items.BOW), 1);
            run.step();
            helper.assertValueEqual(run.heard(), before + PocketDungeonsConfig.hushBowRise(), "a bow shot");
            before = run.heard();
            run.member.setSprinting(true);
            run.step();
            run.member.setSprinting(false);
            helper.assertValueEqual(run.heard(), before + PocketDungeonsConfig.hushSprintRise(), "a sprint");
            before = run.heard();
            run.step();
            helper.assertValueEqual(run.heard(), before, "standing still adds nothing");
        } finally {
            run.close();
        }
        helper.succeed();
    }

    @GameTest(maxTicks = 100)
    public void aFullMeterTellsThenAnswersWithAWhelpAndNeverTheWarden(GameTestHelper helper) {
        Run run = new Run(helper, 9967, true);
        try {
            run.start();
            PressureSources.addHeard(run.room(), PocketDungeonsConfig.hushCeiling());
            run.step();
            DeepslateFinale.Run script = run.script();
            helper.assertTrue(script.tellTick != DeepslateFinale.NO_TELL, "a full meter starts the tell");
            helper.assertTrue(!Whelp.active(run.level, run.record), "no whelp during the tell");
            helper.assertTrue(!run.member.hasEffect(MobEffects.DARKNESS), "no darkness during the tell");
            helper.assertTrue(DeepslateFinaleRules.TELL_TICKS >= 40, "the tell is at least 2 seconds");
            // Noise during the tell is ignored.
            run.member.setHealth(run.member.getHealth() - 2.0f);
            run.step();
            helper.assertTrue(script.tellTick != DeepslateFinale.NO_TELL, "the answer waits for the tell to run");
            // Move the clock: the tell began a full tell ago.
            script.tellTick = run.server.getTickCount() - DeepslateFinaleRules.TELL_TICKS;
            run.step();
            helper.assertTrue(script.tellTick == DeepslateFinale.NO_TELL, "the tell is over");
            helper.assertValueEqual(script.answers, 1, "one answer");
            helper.assertValueEqual(run.heard(), 0, "the meter starts again");
            helper.assertTrue(run.member.hasEffect(MobEffects.DARKNESS), "darkness settles");
            helper.assertTrue(Whelp.active(run.level, run.record), "a whelp comes");
            helper.assertValueEqual(run.wardens(), DeepslateFinaleRules.whelps(PocketDungeonsConfig.hushWhelps()),
                    "as many Wardens as whelps, and each is a whelp");
            // The meter is deaf while the whelp is out.
            run.member.setHealth(run.member.getHealth() - 2.0f);
            run.step();
            helper.assertValueEqual(run.heard(), 0, "deaf while the whelp is out");
            // Winning sends the whelp away.
            for (int i = 0; i < run.state().mobs.size(); i++) {
                Entity mob = run.level.getEntity(run.state().mobs.get(i));
                if (mob != null) {
                    mob.discard();
                }
            }
            run.step();
            helper.assertTrue(run.state().done, "the finale is won");
            helper.assertTrue(!Whelp.active(run.level, run.record), "the whelp is gone with the win");
            helper.assertValueEqual(run.wardens(), 0, "no Warden is left");
        } finally {
            run.close();
        }
        helper.succeed();
    }

    @GameTest(maxTicks = 80)
    public void aFloorWithoutTheRoomIsThePlainFinale(GameTestHelper helper) {
        Run run = new Run(helper, 9968, false);
        try {
            run.start();
            helper.assertTrue(run.script().room == null, "no listening room, nothing taken");
            run.member.setHealth(run.member.getHealth() - 2.0f);
            run.step();
            for (int i = 0; i < run.state().mobs.size(); i++) {
                run.level.getEntity(run.state().mobs.get(i)).discard();
            }
            run.step();
            helper.assertTrue(run.state().done, "the data wave still ends the finale");
            helper.assertValueEqual(run.wardens(), 0, "and no Warden came");
        } finally {
            run.close();
        }
        helper.succeed();
    }

    /** One scripted finale on a hand built record with one mock member and, optionally, a sensor room. */
    private static final class Run {
        final GameTestHelper helper;
        final ServerLevel level;
        final MinecraftServer server;
        final ServerPlayer member;
        final InstanceRecord record;
        final BlockPos origin;
        final String dungeonId;

        Run(GameTestHelper helper, int slot, boolean room) {
            this.helper = helper;
            this.level = helper.getLevel();
            this.server = level.getServer();
            this.dungeonId = "pocketdungeons:test_deepslate_" + slot;
            @SuppressWarnings("removal")
            ServerPlayer player = helper.makeMockServerPlayerInLevel();
            this.member = player;
            player.setGameMode(GameType.SURVIVAL);
            BlockPos where = helper.absolutePos(new BlockPos(1, 2, 1));
            player.teleportTo(level, where.getX() + 0.5, where.getY(), where.getZ() + 0.5, Set.of(), 0.0F, 0.0F,
                    false);
            this.origin = helper.absolutePos(BlockPos.ZERO);
            PlanGeometry geometry = PlanGeometry.of(origin, List.of(new PlanCell(0, 0)));
            InstanceLayout layout = new InstanceLayout(origin, geometry, origin.offset(3, 1, 3), 0.0f,
                    origin.offset(8, 1, 8), geometry.bounds(), 1L, 4, 4, 1, true, Set.of(), 3, origin,
                    0, 0, Set.of(), null, Set.of());
            this.record = new InstanceRecord(slot, origin, server.getTickCount(), layout, Set.of(),
                    player.getUUID(), false);
            record.members.put(player.getUUID(), new ReturnPoint(Level.OVERWORLD, Vec3.ZERO, 0.0f, 0.0f));
            record.phase = RunSession.Phase.ACTIVE;
            record.interval.dungeonId = dungeonId;
            // Open ground under the member, so a whelp has somewhere to stand.
            for (int dx = -12; dx <= 14; dx++) {
                for (int dz = -12; dz <= 14; dz++) {
                    level.setBlock(origin.offset(dx, 1, dz), Blocks.STONE.defaultBlockState(), 3);
                }
            }
            if (room) {
                level.setBlock(origin.offset(5, 1, 5), Blocks.SCULK_SENSOR.defaultBlockState(), 3);
                level.setBlock(origin.offset(10, 1, 10), Blocks.SCULK_SENSOR.defaultBlockState(), 3);
                level.setBlock(origin.offset(7, 5, 7), Blocks.SCULK_SHRIEKER.defaultBlockState(), 3);
                PressureSources.arm(level, origin, new DungeonRoomMeta(DeepslateFinaleRules.ROOM_TEMPLATE, 1, 1,
                        List.of("encounter"), 1, 0, -1, null), null);
            }
            FinaleScripts.register(dungeonId, new DeepslateFinale());
        }

        void step() {
            FinaleWave.step(server, level, record, FINALE, List.of(member), false, "Test Deep");
        }

        /** Triggers, skips the countdown and lets the wave spawn. */
        void start() {
            FinaleWave.step(server, level, record, FINALE, List.of(member), true, "Test Deep");
            state().spawnTick = 0;
            step();
            helper.assertValueEqual(state().mobs.size(), 4, "the data wave stands");
        }

        FinaleWave.State state() {
            return FinaleWave.stateOf(record);
        }

        DeepslateFinale.Run script() {
            return (DeepslateFinale.Run) state().scriptState.get(DeepslateFinale.Run.class);
        }

        BlockPos room() {
            return script().room;
        }

        int heard() {
            return PressureSources.heardOf(room());
        }

        /** Wardens near the room; each must carry the whelp tag. */
        int wardens() {
            List<Warden> found = level.getEntitiesOfClass(Warden.class, new AABB(origin).inflate(64));
            for (Warden warden : found) {
                helper.assertTrue(warden.entityTags().contains(Whelp.TAG), "every Warden here is a whelp");
            }
            return found.size();
        }

        void close() {
            FinaleWave.teardown(level, record.slot);
            FinaleScripts.unregister(dungeonId);
            Whelp.clear(level, record.slot);
            PressureSources.clear(origin);
            for (Warden warden : level.getEntitiesOfClass(Warden.class, new AABB(origin).inflate(64))) {
                warden.discard();
            }
        }
    }
}
