package pocketdungeons;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

/**
 * T50 (docs/wager-draft-2026-10-12.md): a floor whose affixes wager something pays one extra piece into its copper
 * chest, the piece its door board names; a floor that wagers nothing pays nothing extra. The new gear tables roll.
 * Blood costs its life at the door's price path, and the last life is never for sale.
 */
public final class WagerGearGameTest {

    private static final String DUNGEON = "infestation";
    private static final String NODE = "hollow_walls";

    private static InstanceRecord record(GameTestHelper helper, UUID owner, Set<String> board) {
        BlockPos origin = helper.absolutePos(BlockPos.ZERO);
        PlanGeometry geometry = PlanGeometry.of(origin, List.of(new PlanCell(0, 0)));
        InstanceLayout layout = new InstanceLayout(origin, geometry, origin.offset(3, 1, 3), 0.0f,
                origin.offset(8, 1, 8), geometry.bounds(), 1L, 4, 4, 1, true, Set.of(), 3, origin,
                0, 0, Set.of(), null, Set.of());
        InstanceRecord record = new InstanceRecord(970, origin, helper.getLevel().getServer().getTickCount(), layout,
                Set.of(), owner, false);
        record.members.put(owner, new ReturnPoint(Level.OVERWORLD, Vec3.ZERO, 0.0f, 0.0f));
        record.interval.dungeonId = DUNGEON;
        record.interval.nodeId = NODE;
        record.floor.boardAffixes = board;
        return record;
    }

    @GameTest(maxTicks = 40)
    public void theChestPaysTheExtraPieceTheBoardNames(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        DungeonDef def = DungeonDefs.current().byId(DUNGEON);
        helper.assertTrue(def != null && def.node(NODE) != null, "the test floor exists");
        DungeonDef.Node node = def.node(NODE);
        UUID owner = UUID.randomUUID();
        Set<String> board = Set.of(AffixIds.OMINOUS, AffixIds.MOLTEN, AffixIds.BLOOD);
        InstanceRecord wagered = record(helper, owner, board);

        WagerGear.Pick pick = PromisedGear.extraPick(def, node, owner, wagered.interval, NODE, board);
        helper.assertTrue(pick != null, "wager 8 pays an extra piece");
        helper.assertTrue(pick.tier() >= def.lootBand().min() && pick.tier() <= def.lootBand().max(),
                "the piece stays inside the loot band: " + pick);
        ItemStack onBoard = PromisedGear.rollExtra(level, owner, wagered.interval, NODE, pick);
        helper.assertTrue(!onBoard.isEmpty(), "the piece rolls: " + pick);

        List<ItemStack> chest = RunLifecycle.promisedItems(level, def, wagered);
        boolean paid = false;
        for (ItemStack stack : chest) {
            paid |= ItemStack.isSameItemSameComponents(stack, onBoard) && stack.getCount() == onBoard.getCount();
        }
        helper.assertTrue(paid, "the copper chest pays the piece the board names: " + onBoard + " in " + chest);
        helper.assertTrue(!PromisedGear.describe(onBoard).isBlank(), "the board has words for it");

        InstanceRecord bare = record(helper, owner, Set.of());
        InstanceRecord noDoor = record(helper, owner, null);
        helper.assertValueEqual(RunLifecycle.promisedItems(level, def, bare).size(),
                RunLifecycle.promisedItems(level, def, noDoor).size(),
                "wager 0 pays nothing extra");
        helper.assertValueEqual(chest.size(), RunLifecycle.promisedItems(level, def, bare).size() + 1,
                "wager 8 pays exactly one extra piece");
        helper.succeed();
    }

    @GameTest(maxTicks = 40)
    public void everyExtraTableRolls(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        for (String slot : WagerGear.SLOTS) {
            for (int tier = 1; tier <= 4; tier++) {
                ItemStack piece = LibrarianNPC.rollGear(level, LootTables.gearTable(slot, tier), 1234L + tier);
                helper.assertTrue(!piece.isEmpty(), slot + " tier " + tier + " rolls a piece");
                ItemStack again = LibrarianNPC.rollGear(level, LootTables.gearTable(slot, tier), 1234L + tier);
                helper.assertTrue(ItemStack.isSameItemSameComponents(piece, again) && piece.getCount() == again.getCount(),
                        slot + " tier " + tier + " rolls the same piece for the same seed");
            }
        }
        helper.succeed();
    }

    @GameTest(maxTicks = 40)
    public void aHighWagerFloorRollsHigherTiersMoreOften(GameTestHelper helper) {
        DungeonDef def = DungeonDefs.current().byId(DUNGEON);
        DungeonDef.Node node = def.node(NODE);
        IntervalState trip = new IntervalState();
        Set<String> none = Set.of();
        Set<String> low = Set.of(AffixIds.SWARMING);
        Set<String> high = Set.of(AffixIds.OMINOUS, AffixIds.VOIDED, AffixIds.BLOOD);
        int base = node.rewards().stream().map(r -> PromisedGear.parse(r.item()))
                .filter(p -> p.isPresent()).map(p -> Integer.parseInt(p.get()[1])).findFirst()
                .orElse(def.lootBand().min());
        int lowUps = 0;
        int highUps = 0;
        for (int i = 0; i < 600; i++) {
            UUID owner = new UUID(i * 7919L + 1, 31L);
            helper.assertTrue(PromisedGear.extraPick(def, node, owner, trip, NODE, none) == null,
                    "wager 0 never pays an extra piece");
            WagerGear.Pick l = PromisedGear.extraPick(def, node, owner, trip, NODE, low);
            WagerGear.Pick h = PromisedGear.extraPick(def, node, owner, trip, NODE, high);
            helper.assertTrue(l != null && h != null, "a wagered floor always pays");
            helper.assertTrue(h.tier() <= def.lootBand().max() && l.tier() <= def.lootBand().max(),
                    "never above the act's loot band");
            lowUps += l.tier() > base ? 1 : 0;
            highUps += h.tier() > base ? 1 : 0;
        }
        helper.assertTrue(highUps > lowUps * 2, "a wager 9 floor pays the better tier far more often than wager 1: "
                + highUps + " vs " + lowUps + " of 600");
        helper.assertTrue(highUps > 450, "wager 9 is near the 90 percent cap: " + highUps + " of 600");
        helper.succeed();
    }

    @GameTest(maxTicks = 40)
    public void theBoardShowsLootPercentAndBloodCostsItsLife(GameTestHelper helper) {
        helper.assertValueEqual(DungeonScreen.lootPercent(Set.of()).getString(), "Loot 100%",
                "a floor with no affixes still reads Loot 100%");
        helper.assertValueEqual(DungeonScreen.lootPercent(Set.of(AffixIds.MOLTEN)).getString(), "Loot 200%",
                "wager 2 reads Loot 200%");

        UUID owner = UUID.randomUUID();
        Keystone.Offer blood = new Keystone.Offer(4, Set.of(AffixIds.BLOOD), 1, "pocketdungeons:test", Keystone.Tier.FREE);
        Keystone.Offer plain = new Keystone.Offer(4, Set.of(), 1, "pocketdungeons:test", Keystone.Tier.FREE);
        helper.assertValueEqual(Keystone.livesCost(owner, blood), 1, "a Blood door costs one life, once");
        helper.assertValueEqual(Keystone.livesCost(owner, plain), 0, "a door without Blood costs none");
        helper.assertTrue(!DoorLives.affordable(1, 1), "the last life is never for sale");
        helper.assertTrue(DoorLives.affordable(2, 1), "two lives can pay one");
        helper.succeed();
    }
}
