package pocketdungeons;

import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemLore;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Campaign and endgame T2 (decision 2026-10-18): the compass in the campaign is a quest line and a campaign
 * floor clear pays no scrap, while the endgame keeps the scrap curve and the compass bar. Each test uses its
 * own mock player so the shared log is not disturbed, and unregisters its record in a {@code finally}.
 */
public final class QuestCompassGameTest {

    private static final String COPPER_WORKS = "pocketdungeons:copper_works";
    private static final String MINE = "pocketdungeons:endless_mine";
    private static final String SPAWNER = "pocketdungeons:spawner_dungeon";
    private static final String ANCIENT_CITY = "pocketdungeons:ancient_city";

    /** A campaign floor clear pays 0 scrap to the haul; the same clear in the endgame dive pays the curve. */
    @GameTest(maxTicks = 20)
    public void aCampaignFloorClearPaysNoScrapAndAnEndgameOneDoes(GameTestHelper helper) {
        ServerPlayer owner = standInALoadedChunk(helper);
        MinecraftServer server = owner.level().getServer();
        DungeonLog log = DungeonLog.forServer(server);
        log.setCompass(owner.getUUID(), 1, 0);

        InstanceRecord campaign = floorRecord(helper, server, 9951, owner, COPPER_WORKS, false);
        register(campaign, 9951, owner);
        try {
            helper.assertTrue(Campaign.isCampaignTrip(campaign), "a dungeon floor is a campaign trip");
            RunLifecycle.payFloorMembers(server, campaign);
            helper.assertValueEqual(log.haulOf(owner.getUUID()), 0, "a campaign floor clear pays no scrap");
        } finally {
            unregister(9951, owner);
        }

        InstanceRecord dive = floorRecord(helper, server, 9952, owner, MINE, true);
        register(dive, 9952, owner);
        try {
            helper.assertTrue(!Campaign.isCampaignTrip(dive), "the Endless Mine is the endgame dive");
            RunLifecycle.payFloorMembers(server, dive);
            helper.assertTrue(log.haulOf(owner.getUUID()) > 0, "an endgame floor clear pays scrap into the haul");
        } finally {
            unregister(9952, owner);
            log.setHaul(owner.getUUID(), 0);
        }
        helper.succeed();
    }

    /** The sidebar facts mark a campaign trip, so the haul and compass lines drop out; the dive keeps them. */
    @GameTest(maxTicks = 20)
    public void theSidebarDropsScrapOnACampaignTripOnly(GameTestHelper helper) {
        ServerPlayer owner = standInALoadedChunk(helper);
        MinecraftServer server = owner.level().getServer();

        InstanceRecord campaign = floorRecord(helper, server, 9953, owner, COPPER_WORKS, false);
        InstanceRecord dive = floorRecord(helper, server, 9954, owner, MINE, true);
        SidebarLines.Facts facts = SidebarDisplay.factsFor(server, campaign, owner.getUUID());
        helper.assertTrue(facts.campaign(), "a campaign floor is read as a campaign trip");
        for (SidebarLines.Line line : SidebarLines.lines(facts)) {
            helper.assertTrue(!line.text().startsWith("Haul") && !line.text().startsWith("Compass"),
                    "no scrap line in the campaign: " + line.text());
        }
        SidebarLines.Facts endgame = SidebarDisplay.factsFor(server, dive, owner.getUUID());
        helper.assertTrue(!endgame.campaign(), "the dive is not a campaign trip");
        boolean compass = false;
        for (SidebarLines.Line line : SidebarLines.lines(endgame)) {
            compass |= line.text().startsWith("Compass");
        }
        helper.assertTrue(compass, "the endgame sidebar keeps the compass line");
        helper.succeed();
    }

    /** The item is a quest line while the campaign runs and the scrap compass once it is done. */
    @GameTest(maxTicks = 20)
    public void theCompassIsAQuestLineUntilTheCampaignIsDone(GameTestHelper helper) {
        ServerPlayer player = standInALoadedChunk(helper);
        MinecraftServer server = player.level().getServer();
        DungeonLog log = DungeonLog.forServer(server);
        java.util.UUID id = player.getUUID();
        log.setTaskProgress(id, DungeonProgress.PROLOGUE_KEY, 1);
        log.setCompass(id, 3, 1);
        log.setKeystone(id, 3, Set.of());
        player.getInventory().setItem(0, Keystone.mint(3));

        Keystone.showCompass(player, log.get(id), false);
        ItemStack stack = player.getInventory().getItem(0);
        helper.assertValueEqual(stack.get(DataComponents.CUSTOM_NAME).getString(), "Compass",
                "the campaign item is named Compass");
        List<String> lore = lore(stack);
        helper.assertTrue(lore.stream().anyMatch(l -> l.startsWith("Next: ")), "the first quest line shows: " + lore);
        helper.assertTrue(lore.stream().anyMatch(l -> l.endsWith(" more for the Spawner Dungeon")),
                "the second line counts toward the capstone: " + lore);
        helper.assertTrue(lore.stream().noneMatch(l -> l.startsWith("Compass ") || l.startsWith("Haul ")),
                "no number and no haul in the campaign: " + lore);
        helper.assertTrue(lore.stream().noneMatch(l -> l.contains("Clear floors for scrap")),
                "the old scrap hint is gone: " + lore);

        // Showing it again does not stack lines.
        Keystone.showCompass(player, log.get(id), true);
        helper.assertValueEqual(lore(player.getInventory().getItem(0)).size(), lore.size(),
                "a second paint replaces its own lines");

        // A fresh stack minted on a trip entry wears the quest line too.
        ItemStack minted = Keystone.mint(3);
        Keystone.paintCampaign(player, minted);
        helper.assertValueEqual(minted.get(DataComponents.CUSTOM_NAME).getString(), "Compass",
                "a freshly minted stack is painted for the campaign");

        // Three Act 1 dungeons open the capstone line.
        for (DungeonDef def : ActProgress.ordinaryDungeons(1, DungeonDefs.current().all())) {
            if (log.get(id).dungeonsFinished().size() < 3) {
                log.addDungeonFinished(id, def.id());
            }
        }
        Keystone.showCompass(player, log.get(id), false);
        helper.assertTrue(lore(player.getInventory().getItem(0)).contains("Next: the Spawner Dungeon"),
                "three finished: the capstone is the next step: " + lore(player.getInventory().getItem(0)));

        // The campaign is done: the stored compass shows as it was, with the scrap bar.
        log.addDungeonFinished(id, ANCIENT_CITY);
        helper.assertTrue(Campaign.done(log.get(id)), "the last capstone on the server ends the campaign");
        Keystone.showCompass(player, log.get(id), false);
        ItemStack after = player.getInventory().getItem(0);
        String name = after.get(DataComponents.CUSTOM_NAME).getString();
        helper.assertTrue(name.contains("Compass") && name.contains("[3]"), "the endgame name carries the level: " + name);
        List<String> endgame = lore(after);
        helper.assertTrue(endgame.stream().anyMatch(l -> l.startsWith("Compass 3: 1/")),
                "the stored level and bar are kept untouched: " + endgame);
        helper.assertTrue(endgame.stream().noneMatch(l -> l.startsWith("Next: ") || l.contains(" more for ")),
                "the quest line is gone: " + endgame);
        helper.assertValueEqual(log.get(id).highestCharts(), 3, "the wake leaves the stored compass as it is");
        helper.succeed();
    }

    /** The migration line is handled once per player; the wake beat fires only for the finish that ends the campaign. */
    @GameTest(maxTicks = 20)
    public void theMigrationFlagAndTheWakeBeatFireOnce(GameTestHelper helper) {
        ServerPlayer player = standInALoadedChunk(helper);
        MinecraftServer server = player.level().getServer();
        DungeonLog log = DungeonLog.forServer(server);
        java.util.UUID id = player.getUUID();
        log.setTaskProgress(id, DungeonProgress.PROLOGUE_KEY, 1);
        // The mock player's own join may already have handled it; the golden fixture covers the old shape.
        RunLifecycle.onJoinHaul(server, player);
        helper.assertTrue(log.get(id).questLineSeen(), "the first join handles the message and records it");
        helper.assertTrue(!log.markQuestLineSeen(id), "and it is not handled again");
        helper.assertValueEqual(RunLifecycle.questLineJoinMessage().get(0),
                "Your compass now shows the way. It will count again when the campaign is done.",
                "the owner's wording");

        // The wake: the capstone of the last act, finished for the first time.
        InstanceRecord record = floorRecord(helper, server, 9955, player, ANCIENT_CITY, false);
        Set<String> before = new java.util.HashSet<>(Set.of(SPAWNER));
        Set<String> after = new java.util.HashSet<>(before);
        after.add(ANCIENT_CITY);
        java.util.Collection<DungeonDef> all = DungeonDefs.current().all();
        helper.assertTrue(DungeonProgress.compassWoke(after, record, all), "the last capstone wakes the compass");
        helper.assertTrue(!DungeonProgress.compassWoke(before, record, all), "not before it is finished");
        InstanceRecord replay = floorRecord(helper, server, 9956, player, COPPER_WORKS, false);
        helper.assertTrue(!DungeonProgress.compassWoke(after, replay, all),
                "a later finish of another dungeon does not wake it again");
        helper.succeed();
    }

    /** Owner ask 2026-10-19: in a party every member's compass reads the owner's quest line. */
    @GameTest(maxTicks = 20)
    @SuppressWarnings("removal")
    public void aPartysCompassesShareTheLeadersQuestLine(GameTestHelper helper) {
        ServerPlayer leader = standInALoadedChunk(helper);
        ServerPlayer member = helper.makeMockServerPlayerInLevel();
        member.setGameMode(net.minecraft.world.level.GameType.SURVIVAL);
        MinecraftServer server = leader.level().getServer();
        DungeonLog log = DungeonLog.forServer(server);

        // The leader is three act-1 dungeons deep: the capstone is their next step.
        java.util.UUID leaderId = leader.getUUID();
        log.setTaskProgress(leaderId, DungeonProgress.PROLOGUE_KEY, 1);
        log.setCompass(leaderId, 3, 1);
        log.setKeystone(leaderId, 3, Set.of());
        leader.getInventory().setItem(0, Keystone.mint(3));
        for (DungeonDef def : ActProgress.ordinaryDungeons(1, DungeonDefs.current().all())) {
            if (log.get(leaderId).dungeonsFinished().size() < 3) {
                log.addDungeonFinished(leaderId, def.id());
            }
        }

        // The member is fresh; on their own their tracker knows nothing of the capstone.
        java.util.UUID memberId = member.getUUID();
        member.getInventory().setItem(0, Keystone.mint(1));
        Keystone.showCompass(member, log.get(memberId), false);
        helper.assertTrue(lore(member.getInventory().getItem(0)).stream()
                        .noneMatch(l -> l.contains("Spawner Dungeon")),
                "solo the member does not read the leader's step");

        InstanceRecord record = floorRecord(helper, server, 9959, leader, COPPER_WORKS, false);
        record.members.put(memberId, new ReturnPoint(Level.OVERWORLD, Vec3.ZERO, 0.0f, 0.0f));
        register(record, 9959, leader, member);
        try {
            Keystone.showCompass(leader, log.get(leaderId), true);
            Keystone.showCompass(member, log.get(memberId), true);
            List<String> leaderLore = lore(leader.getInventory().getItem(0));
            List<String> memberLore = lore(member.getInventory().getItem(0));
            helper.assertTrue(leaderLore.contains("Next: the Spawner Dungeon"),
                    "the leader's next step is the capstone: " + leaderLore);
            helper.assertValueEqual(questLines(memberLore), questLines(leaderLore),
                    "the member reads the leader's quest line");
            helper.assertTrue(memberLore.contains("Next: the Spawner Dungeon"),
                    "one tracker for the party: " + memberLore);
        } finally {
            unregister(9959, leader, member);
        }
        helper.succeed();
    }

    /** The door board names the items and no scrap for a campaign door; a dive door still names its scrap. */
    @GameTest(maxTicks = 20)
    public void theBoardNamesNoScrapForACampaignDoor(GameTestHelper helper) {
        ServerPlayer owner = standInALoadedChunk(helper);
        MinecraftServer server = owner.level().getServer();
        DungeonLog.forServer(server).setCompass(owner.getUUID(), 1, 0);

        DungeonDef def = DungeonDefs.current().byId(COPPER_WORKS);
        InstanceRecord campaign = floorRecord(helper, server, 9957, owner, COPPER_WORKS, false);
        campaign.interval.nodeId = def.entry().id();
        register(campaign, 9957, owner);
        try {
            String board = DungeonScreen.previewContent(helper.getLevel(), campaign, 1).body().getString();
            helper.assertTrue(!board.contains("scrap"), "a campaign door's board names no scrap: " + board);
        } finally {
            unregister(9957, owner);
        }

        InstanceRecord dive = floorRecord(helper, server, 9958, owner, MINE, true);
        register(dive, 9958, owner);
        try {
            String board = DungeonScreen.previewContent(helper.getLevel(), dive, 1).body().getString();
            helper.assertTrue(board.contains("scrap"), "an endgame door's board still names its scrap: " + board);
        } finally {
            unregister(9958, owner);
        }
        helper.succeed();
    }

    // ---- scaffolding ---------------------------------------------------------------------------

    /** The lines the quest line writes (the {@code Next:} and {@code more for} lines), in order. */
    private static List<String> questLines(List<String> lore) {
        List<String> out = new ArrayList<>();
        for (String line : lore) {
            if (line.startsWith("Next: ") || line.contains(" more for ")) {
                out.add(line);
            }
        }
        return out;
    }

    private static List<String> lore(ItemStack stack) {
        ItemLore lore = stack.get(DataComponents.LORE);
        List<String> out = new ArrayList<>();
        if (lore != null) {
            for (Component line : lore.lines()) {
                out.add(line.getString());
            }
        }
        return out;
    }

    private static InstanceRecord floorRecord(GameTestHelper helper, MinecraftServer server, int slot,
                                              ServerPlayer owner, String dungeonId, boolean mine) {
        BlockPos origin = helper.absolutePos(BlockPos.ZERO);
        PlanGeometry geometry = PlanGeometry.of(origin, List.of(new PlanCell(0, 0)));
        InstanceLayout layout = new InstanceLayout(origin, geometry, origin.offset(3, 1, 3), 0.0f,
                origin.offset(8, 1, 8), geometry.bounds(), 1L, 4, 4, 1, true, Set.of(), 3, origin,
                0, 0, Set.of(), null, Set.of());
        InstanceRecord record = new InstanceRecord(slot, origin, server.getTickCount(), layout,
                Set.of(), owner.getUUID(), false);
        record.floor.theme = "pocketdungeons:test_quest_compass";
        record.members.put(owner.getUUID(), new ReturnPoint(Level.OVERWORLD, Vec3.ZERO, 0.0f, 0.0f));
        record.phase = RunSession.Phase.FLOOR_CLEARED;
        record.floor.completed.add(owner.getUUID());
        record.floor.chosenStep = 3;
        record.floor.chosenLevel = 10;
        record.interval.dungeonId = dungeonId;
        record.interval.endlessMine = mine;
        record.interval.floorIndex = 1;
        return record;
    }

    private static void register(InstanceRecord record, int slot, ServerPlayer... members) {
        InstanceRegistry.bySlot.put(slot, record);
        InstanceRegistry.usedSlots.add(slot);
        for (ServerPlayer member : members) {
            InstanceRegistry.byMember.put(member.getUUID(), record);
        }
    }

    private static void unregister(int slot, ServerPlayer... members) {
        InstanceRegistry.bySlot.remove(slot);
        InstanceRegistry.usedSlots.remove(slot);
        for (ServerPlayer member : members) {
            InstanceRegistry.byMember.remove(member.getUUID());
        }
    }

    private static ServerPlayer standInALoadedChunk(GameTestHelper helper) {
        @SuppressWarnings("removal")
        ServerPlayer player = helper.makeMockServerPlayerInLevel();
        player.setGameMode(net.minecraft.world.level.GameType.SURVIVAL);
        BlockPos where = helper.absolutePos(new BlockPos(1, 2, 1));
        player.teleportTo(helper.getLevel(), where.getX() + 0.5, where.getY(), where.getZ() + 0.5,
                Set.of(), 0.0F, 0.0F, false);
        return player;
    }
}
