package pocketdungeons;

import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.storage.loot.LootParams;
import net.minecraft.world.level.storage.loot.LootTable;
import net.minecraft.world.level.storage.loot.parameters.LootContextParamSets;
import net.minecraft.world.level.storage.loot.parameters.LootContextParams;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

/**
 * The Endless Mine's guardian engine (decision 2026-10-11, task T40): a guardian on every third floor, the pad
 * shut while it lives, the hook the real guardians register into, and the A, B, C loot tier rule per act.
 *
 * <p>Same package rationale as {@link FloorLoopGameTest}. What the harness cannot reach: a real descent
 * (commit, clear, the pad, the checkpoint line), which needs the dungeon dimension this server does not have;
 * the pieces that decide each outcome are driven directly ({@link MineGuardians#spawnIfGuardianFloor},
 * {@link MineGuardians#padRefusal}, {@link MineGuardians#payingTable}, {@link TrialContent#placeRewardContainers}).
 */
public final class MineGuardianGameTest {

    private static final String ACT1 = EndlessMineRules.MINE_DUNGEON_ID;
    private static final String ACT2 = "pocketdungeons:endless_mine_deep";

    private static InstanceRecord mineRecord(GameTestHelper helper, String mineId) {
        BlockPos origin = helper.absolutePos(BlockPos.ZERO);
        PlanGeometry geometry = PlanGeometry.of(origin, List.of(new PlanCell(0, 0)));
        InstanceLayout layout = new InstanceLayout(origin, geometry, origin.offset(3, 1, 3), 0.0f,
                origin.offset(8, 1, 8), geometry.bounds(), 1L, 4, 4, 1, true, Set.of(), 3, origin,
                0, 0, Set.of(), null, Set.of());
        InstanceRecord record = new InstanceRecord(9980, origin, helper.getLevel().getServer().getTickCount(),
                layout, Set.of(), UUID.randomUUID(), false);
        record.interval.endlessMine = true;
        record.interval.mineDungeonId = mineId;
        return record;
    }

    private static List<Mob> guardians(ServerLevel level, BlockPos terminalOrigin) {
        AABB bounds = CellGeometry.cellBounds(terminalOrigin).inflate(RoomGeometry.CELL * 2.0);
        List<Mob> out = new ArrayList<>();
        for (Mob mob : level.getEntitiesOfClass(Mob.class, bounds)) {
            if (mob.entityTags().contains(MineGuardians.GUARDIAN_TAG)) {
                out.add(mob);
            }
        }
        return out;
    }

    private static void clear(ServerLevel level, BlockPos terminalOrigin) {
        for (Mob mob : guardians(level, terminalOrigin)) {
            mob.discard();
        }
    }

    /** Floors 3, 6 and 9 hold a guardian, no other floor does, and the pad stays shut while one lives. */
    @GameTest(maxTicks = 40)
    public void aGuardianWaitsOnEveryThirdFloor(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        InstanceRecord record = mineRecord(helper, ACT1);
        BlockPos terminal = record.layout.terminal();
        MineGuardians.clear();
        try {
            int previousCount = 0;
            for (int floor = 1; floor <= 12; floor++) {
                clear(level, terminal);
                MineGuardians.spawnIfGuardianFloor(level, record, terminal, floor, 5);
                int count = guardians(level, terminal).size();
                boolean guardianFloor = floor % 3 == 0;
                helper.assertTrue(guardianFloor == (count > 0), "floor " + floor + (guardianFloor
                        ? " should hold a guardian" : " should hold none") + ", saw " + count + " mobs");
                helper.assertTrue(guardianFloor == (MineGuardians.padRefusal(level, record) != null),
                        "floor " + floor + (guardianFloor ? ": the pad stays shut while the guardian lives"
                                : ": no guardian, the pad is not held"));
                if (guardianFloor) {
                    int number = floor / 3;
                    helper.assertValueEqual(count, MineGuardians.placeholderCount(EndlessMineRules.hardness(number)),
                            "guardian " + number + " wave size");
                    helper.assertTrue(count >= previousCount, "a later guardian is never a smaller wave");
                    previousCount = count;
                }
            }
            clear(level, terminal);
            helper.assertTrue(MineGuardians.padRefusal(level, record) == null, "with the guardian down the pad opens");
            helper.succeed();
        } finally {
            clear(level, terminal);
            MineGuardians.clear();
        }
    }

    /** Lays a solid floor under the cell so bricks and coffins can stand on it. */
    private static void floor(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                level.setBlock(helper.absolutePos(new BlockPos(x, 0, z)),
                        net.minecraft.world.level.block.Blocks.STONE.defaultBlockState(), 3);
            }
        }
    }

    /**
     * Act 1 slot 1 (roadmap T41): the young queen spawns burrowed and tagged, her bricks stand, the pad
     * stays shut while she or her brood lives, and a later guardian plants more of them.
     */
    @GameTest(maxTicks = 60)
    public void theYoungQueenSurfacesOutOfTheFloor(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        floor(helper);
        InstanceRecord record = mineRecord(helper, ACT1);
        BlockPos terminal = record.layout.terminal();
        MineGuardians.clear();
        try {
            MineGuardiansAct1.registerAll();
            long now = level.getServer().getTickCount();
            MineGuardians.spawnIfGuardianFloor(level, record, terminal, 3, 5);
            List<Mob> wave = guardians(level, terminal);
            helper.assertTrue(!wave.isEmpty(), "floor 3 spawned the queen's wave");
            Mob queen = null;
            for (Mob mob : wave) {
                helper.assertTrue(mob.entityTags().contains(MineGuardians.GUARDIAN_TAG),
                        "every guardian mob carries the guardian tag");
                if (mob.getType() == EntityTypes.SILVERFISH) {
                    queen = mob;
                }
            }
            helper.assertTrue(queen != null, "the wave is a silverfish queen");
            helper.assertTrue(queen.isInvisible(), "she starts burrowed");
            helper.assertTrue(MineGuardianQueen.standingBricks(terminal) == MineGuardianRules.bricksFor(1.0),
                    "her bricks are planted: " + MineGuardianQueen.standingBricks(terminal));
            helper.assertTrue(MineGuardians.padRefusal(level, record) != null,
                    "the pad stays shut while she is burrowed");

            MineGuardianQueen.pollForTest(now + MineGuardianRules.TELL_BASE_TICKS + 10);
            helper.assertTrue(MineGuardianQueen.surfaced(terminal), "the tell ends and she surfaces");
            helper.assertTrue(queen.isAlive() && !queen.isInvisible(), "the surfaced queen fights");
            helper.assertTrue(MineGuardians.padRefusal(level, record) != null,
                    "the pad stays shut while she lives");

            // A lap later (floor 12, guardian 4) she is bigger and plants more bricks.
            double firstScale = MineGuardianRules.queenScale(EndlessMineRules.hardness(1));
            int firstBricks = MineGuardianQueen.standingBricks(terminal);
            MineGuardianQueen.clear(terminal);
            clear(level, terminal);
            MineGuardians.spawnIfGuardianFloor(level, record, terminal, 12, 5);
            helper.assertTrue(MineGuardianQueen.standingBricks(terminal) > firstBricks,
                    "a later guardian plants more bricks: " + MineGuardianQueen.standingBricks(terminal));
            helper.assertTrue(MineGuardianRules.queenScale(EndlessMineRules.hardness(4)) > firstScale,
                    "a later queen is bigger");
            clear(level, terminal);
            helper.assertTrue(MineGuardians.padRefusal(level, record) == null,
                    "with the wave down the pad opens");
            helper.succeed();
        } finally {
            clear(level, terminal);
            MineGuardianQueen.clear(terminal);
            MineGuardianCoffins.clear(terminal);
            MineGuardians.clear();
        }
    }

    /**
     * Act 1 slot 2 (roadmap T41): the packmaster spawns with marked wolves, every mob tagged, and the
     * pad stays shut until the whole pack is dead.
     */
    @GameTest(maxTicks = 60)
    public void thePackmasterDrivesHisWolves(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        InstanceRecord record = mineRecord(helper, ACT1);
        BlockPos terminal = record.layout.terminal();
        MineGuardians.clear();
        try {
            MineGuardiansAct1.registerAll();
            MineGuardians.spawnIfGuardianFloor(level, record, terminal, 6, 5);
            List<Mob> wave = guardians(level, terminal);
            int wolves = 0;
            int pillagers = 0;
            for (Mob mob : wave) {
                helper.assertTrue(mob.entityTags().contains(MineGuardians.GUARDIAN_TAG),
                        "every pack mob carries the guardian tag");
                if (mob instanceof net.minecraft.world.entity.animal.wolf.Wolf) {
                    wolves++;
                    helper.assertTrue(mob.entityTags().contains(HostileWolves.HOSTILE),
                            "each wolf is marked hostile");
                } else if (mob.getType() == EntityTypes.PILLAGER) {
                    pillagers++;
                }
            }
            helper.assertValueEqual(pillagers, 1, "one packmaster");
            helper.assertValueEqual(wolves, MineGuardianRules.wolvesFor(EndlessMineRules.hardness(2)),
                    "the pack's size for guardian 2");
            helper.assertTrue(MineGuardians.padRefusal(level, record) != null,
                    "the pad stays shut while the pack lives");
            for (Mob mob : wave) {
                if (mob.getType() == EntityTypes.PILLAGER) {
                    mob.discard();
                }
            }
            helper.assertTrue(MineGuardians.padRefusal(level, record) != null,
                    "the wolves hold the pad after the packmaster falls");
            clear(level, terminal);
            helper.assertTrue(MineGuardians.padRefusal(level, record) == null,
                    "the last wolf down and the pad opens");
            helper.succeed();
        } finally {
            clear(level, terminal);
            MineGuardians.clear();
        }
    }

    /**
     * Act 1 slot 3 (roadmap T41): sealed coffins stand in the cell holding tagged sleepers, they rise
     * one at a time, a broken lid opens its coffin on the spot, and the pad stays shut until every
     * sleeper is dead.
     */
    @GameTest(maxTicks = 60)
    public void theCoffinsRiseOneAtATime(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        floor(helper);
        InstanceRecord record = mineRecord(helper, ACT1);
        BlockPos terminal = record.layout.terminal();
        MineGuardians.clear();
        try {
            MineGuardiansAct1.registerAll();
            long now = level.getServer().getTickCount();
            MineGuardians.spawnIfGuardianFloor(level, record, terminal, 9, 5);
            List<Mob> wave = guardians(level, terminal);
            helper.assertValueEqual(wave.size(), MineGuardianRules.coffinsFor(EndlessMineRules.hardness(3)),
                    "one sleeper per coffin for guardian 3");
            for (Mob mob : wave) {
                helper.assertTrue(mob.entityTags().contains(MineGuardians.GUARDIAN_TAG),
                        "every sleeper carries the guardian tag");
                helper.assertTrue(mob.isInvisible(), "every sleeper is still sealed in");
            }
            helper.assertTrue(MineGuardians.padRefusal(level, record) != null,
                    "the pad stays shut while any sleeper lies sealed");
            helper.assertValueEqual(MineGuardianCoffins.phases(terminal).size(), wave.size(),
                    "every sleeper has a coffin");

            // The first coffin cracks after the gap, then opens after its tell; the rest wait.
            int tell = MineGuardianRules.tellTicks(MineGuardianRules.TELL_BASE_TICKS,
                    EndlessMineRules.hardness(3));
            MineGuardianCoffins.pollForTest(now + MineGuardianRules.COFFIN_GAP_TICKS);
            List<String> phases = MineGuardianCoffins.phases(terminal);
            helper.assertValueEqual(countOf(phases, "CRACKING"), 1, "one coffin cracks at a time");
            helper.assertValueEqual(countOf(phases, "SEALED"), wave.size() - 1, "the rest stay sealed");
            MineGuardianCoffins.pollForTest(now + MineGuardianRules.COFFIN_GAP_TICKS + tell + 5);
            phases = MineGuardianCoffins.phases(terminal);
            helper.assertValueEqual(countOf(phases, "OPEN"), 1, "the first coffin opened after its tell");
            helper.assertTrue(MineGuardians.padRefusal(level, record) != null,
                    "the pad stays shut while sleepers still lie sealed");

            // A broken lid opens its coffin on the spot.
            for (int x = 0; x < 16; x++) {
                for (int z = 0; z < 16; z++) {
                    BlockPos at = terminal.offset(x, 1, z);
                    if (level.getBlockState(at).is(net.minecraft.world.level.block.Blocks.POLISHED_BLACKSTONE)) {
                        level.destroyBlock(at, false);
                    }
                }
            }
            MineGuardianCoffins.pollForTest(now + MineGuardianRules.COFFIN_GAP_TICKS + tell + 10);
            phases = MineGuardianCoffins.phases(terminal);
            helper.assertTrue(countOf(phases, "SEALED") == 0,
                    "every broken lid opened its coffin: " + phases);
            helper.assertTrue(MineGuardians.padRefusal(level, record) != null,
                    "the pad stays shut while any sleeper stands");
            clear(level, terminal);
            helper.assertTrue(MineGuardians.padRefusal(level, record) == null,
                    "the last sleeper down and the pad opens");
            helper.succeed();
        } finally {
            clear(level, terminal);
            MineGuardianCoffins.clear(terminal);
            MineGuardianQueen.clear(terminal);
            MineGuardians.clear();
        }
    }

    private static int countOf(List<String> phases, String phase) {
        int count = 0;
        for (String p : phases) {
            if (p.equals(phase)) {
                count++;
            }
        }
        return count;
    }

    /** Each act's mine spawns its own placeholder wave, and a mine that is not flagged spawns none. */
    @GameTest(maxTicks = 40)
    public void theWaveFollowsTheMinesAct(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        InstanceRecord act1 = mineRecord(helper, ACT1);
        InstanceRecord act2 = mineRecord(helper, ACT2);
        BlockPos terminal = act1.layout.terminal();
        MineGuardians.clear();
        try {
            helper.assertValueEqual(EndlessMineRules.actOf(act1), 1, "the Act 1 mine's act");
            helper.assertValueEqual(EndlessMineRules.actOf(act2), 2, "the Deep Mine's act");
            MineGuardians.spawnIfGuardianFloor(level, act1, terminal, 3, 5);
            for (Mob mob : guardians(level, terminal)) {
                helper.assertTrue(mob.getType() == EntityTypes.ZOMBIE, "the Act 1 placeholder is a zombie wave");
            }
            clear(level, terminal);
            MineGuardians.spawnIfGuardianFloor(level, act2, terminal, 3, 6);
            List<Mob> wave = guardians(level, terminal);
            helper.assertTrue(!wave.isEmpty(), "the Deep Mine has a wave on floor 3");
            for (Mob mob : wave) {
                helper.assertTrue(mob.getType() == EntityTypes.SKELETON, "the Act 2 placeholder is a skeleton wave");
            }
            clear(level, terminal);
            helper.succeed();
        } finally {
            clear(level, terminal);
            MineGuardians.clear();
        }
    }

    /** A registered guardian replaces the placeholder, per act and slot, with the hardness dial filled in. */
    @GameTest(maxTicks = 40)
    public void aRegisteredGuardianReplacesThePlaceholder(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        InstanceRecord record = mineRecord(helper, ACT1);
        BlockPos terminal = record.layout.terminal();
        List<MineGuardians.Spawn> seen = new ArrayList<>();
        MineGuardians.clear();
        try {
            MineGuardians.register(1, 1, seen::add);
            MineGuardians.register(1, 2, seen::add);
            for (int floor : new int[]{3, 6, 9, 12, 15, 18, 21}) {
                MineGuardians.spawnIfGuardianFloor(level, record, terminal, floor, 5);
            }
            // Slots 1 and 2 are registered, slot 3 (floors 9, 18) falls back to the placeholder wave.
            helper.assertValueEqual(seen.size(), 5, "floors 3, 6, 12, 15 and 21 reach the registered guardians");
            clear(level, terminal);
            int[] numbers = {1, 2, 4, 5, 7};
            int[] slots = {1, 2, 1, 2, 1};
            int[] laps = {0, 0, 1, 1, 2};
            double previous = 0.0;
            for (int i = 0; i < seen.size(); i++) {
                MineGuardians.Spawn spawn = seen.get(i);
                helper.assertValueEqual(spawn.number(), numbers[i], "guardian number " + i);
                helper.assertValueEqual(spawn.slot(), slots[i], "guardian slot " + i);
                helper.assertValueEqual(spawn.lap(), laps[i], "guardian lap " + i);
                helper.assertValueEqual(spawn.act(), 1, "the act is passed through");
                helper.assertTrue(spawn.hardness() > previous, "hardness rises with every guardian: " + spawn.hardness());
                helper.assertTrue(spawn.level() == level && spawn.centre().getY() == terminal.getY() + 1,
                        "the hook is handed the level and the cell centre");
                previous = spawn.hardness();
            }
            helper.assertValueEqual(seen.get(0).hardness(), 1.0, "guardian 1 is the baseline");
            helper.succeed();
        } finally {
            clear(level, terminal);
            MineGuardians.clear();
        }
    }

    private static Set<String> sample(ServerLevel level, String tableId, int draws) {
        MinecraftServer server = level.getServer();
        ResourceKey<LootTable> key = ResourceKey.create(Registries.LOOT_TABLE, Identifier.parse(tableId));
        LootTable table = server.reloadableRegistries().getLootTable(key);
        LootParams.Builder params = new LootParams.Builder(level)
                .withParameter(LootContextParams.ORIGIN, Vec3.ZERO);
        Set<String> items = new TreeSet<>();
        for (int i = 0; i < draws; i++) {
            for (ItemStack stack : table.getRandomItems(params.create(LootContextParamSets.CHEST), (long) i * 7919L + 13L)) {
                items.add(BuiltInRegistries.ITEM.getKey(stack.getItem()).getPath());
            }
        }
        return items;
    }

    private static boolean has(Set<String> items, String... wanted) {
        return items.containsAll(List.of(wanted));
    }

    private static boolean hasNone(Set<String> items, String... banned) {
        for (String item : banned) {
            if (items.contains(item)) {
                return false;
            }
        }
        return true;
    }

    /** Guardian 1 pays A, guardian 2 pays B, guardian 3 and every later one pays C; each act has its own tables. */
    @GameTest(maxTicks = 40)
    public void theTiersRiseAndStopAtC(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        InstanceRecord act1 = mineRecord(helper, ACT1);
        InstanceRecord act2 = mineRecord(helper, ACT2);
        // Which table pays which floor.
        String[][] expected = {{"3", "a"}, {"6", "b"}, {"9", "c"}, {"12", "c"}, {"30", "c"}};
        for (String[] row : expected) {
            int floor = Integer.parseInt(row[0]);
            helper.assertValueEqual(MineGuardians.payingTable(act1, floor, null),
                    "pocketdungeons:endless_mine/act1_" + row[1], "act 1 floor " + floor + " pays");
            helper.assertValueEqual(MineGuardians.payingTable(act2, floor, null),
                    "pocketdungeons:endless_mine/act2_" + row[1], "act 2 floor " + floor + " pays");
        }
        helper.assertTrue(MineGuardians.payingTable(act1, 4, "theme:table").equals("theme:table")
                        && MineGuardians.payingTable(act1, 5, null) == null,
                "a floor between guardians pays the ordinary table");
        InstanceRecord notMine = mineRecord(helper, ACT1);
        notMine.interval.endlessMine = false;
        helper.assertTrue(MineGuardians.payingTable(notMine, 3, null) == null, "an ordinary dungeon is untouched");

        // What each table holds (the act's loot band: act 1 coal, iron, copper to redstone and diamond).
        Set<String> a1a = sample(level, "pocketdungeons:endless_mine/act1_a", 400);
        Set<String> a1b = sample(level, "pocketdungeons:endless_mine/act1_b", 400);
        Set<String> a1c = sample(level, "pocketdungeons:endless_mine/act1_c", 400);
        helper.assertTrue(has(a1a, "coal", "iron_ingot", "copper_ingot")
                        && hasNone(a1a, "gold_ingot", "lapis_lazuli", "redstone", "diamond"),
                "act 1 tier A is coal, iron and copper only: " + a1a);
        helper.assertTrue(has(a1b, "coal", "iron_ingot", "copper_ingot", "gold_ingot", "lapis_lazuli")
                        && hasNone(a1b, "redstone", "diamond"),
                "act 1 tier B adds gold and lapis: " + a1b);
        helper.assertTrue(has(a1c, "gold_ingot", "lapis_lazuli", "redstone", "diamond"),
                "act 1 tier C adds redstone and a diamond chance: " + a1c);
        Set<String> a2a = sample(level, "pocketdungeons:endless_mine/act2_a", 400);
        Set<String> a2b = sample(level, "pocketdungeons:endless_mine/act2_b", 400);
        Set<String> a2c = sample(level, "pocketdungeons:endless_mine/act2_c", 400);
        helper.assertTrue(has(a2a, "iron_ingot", "gold_ingot", "lapis_lazuli")
                        && hasNone(a2a, "redstone", "diamond", "coal", "copper_ingot"),
                "act 2 tier A is iron, gold and lapis: " + a2a);
        helper.assertTrue(has(a2b, "iron_ingot", "gold_ingot", "lapis_lazuli", "redstone") && hasNone(a2b, "diamond"),
                "act 2 tier B adds redstone: " + a2b);
        helper.assertTrue(has(a2c, "iron_ingot", "gold_ingot", "lapis_lazuli", "redstone", "diamond"),
                "act 2 tier C adds the diamond chance: " + a2c);
        helper.succeed();
    }

    /** The reward barrel itself pays from the guardian's table, through the same override the run uses. */
    @GameTest(maxTicks = 40)
    public void theRewardBarrelPaysTheGuardiansTier(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        InstanceRecord record = mineRecord(helper, ACT1);
        BlockPos origin = helper.absolutePos(BlockPos.ZERO);
        BlockPos barrel = origin.offset(8, 1, 12);
        for (int floor : new int[]{3, 6}) {
            TrialContent.placeRewardContainers(level, origin, DoorMask.Direction.NORTH,
                    new TrialContent.RewardRolls(8, 1, 0, 0),
                    new TrialContent.RewardLoot(false, 1234L + floor, null, MineGuardians.payingTable(record, floor, null)),
                    List.of());
            Set<String> held = new TreeSet<>();
            if (level.getBlockEntity(barrel) instanceof Container container) {
                for (int slot = 0; slot < container.getContainerSize(); slot++) {
                    ItemStack stack = container.getItem(slot);
                    if (!stack.isEmpty()) {
                        held.add(BuiltInRegistries.ITEM.getKey(stack.getItem()).getPath());
                    }
                }
            }
            helper.assertTrue(!held.isEmpty(), "floor " + floor + " filled the barrel");
            helper.assertTrue(hasNone(held, "redstone", "diamond"), "floor " + floor + " pays only A or B: " + held);
            if (floor == 3) {
                helper.assertTrue(hasNone(held, "gold_ingot", "lapis_lazuli"),
                        "the first guardian pays tier A, no gold or lapis: " + held);
            }
            level.setBlock(barrel, net.minecraft.world.level.block.Blocks.AIR.defaultBlockState(), 3);
        }
        helper.succeed();
    }
}
