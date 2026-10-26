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
import charter.mc.granary.FoodConfig;
import charter.mc.granary.GranaryBook;
import charter.mc.granary.GranaryBook.Pos;
import charter.mc.granary.GranaryScreen;
import charter.mc.granary.GranaryService;
import charter.mc.granary.GranaryService.Access;
import charter.mc.granary.Granaries;
import charter.org.actions.AcceptInvite;
import charter.org.actions.Found;
import charter.org.actions.InviteMember;
import charter.siege.KeepSupply;
import charter.siege.SiegeEvents;
import charter.treasury.Currency;
import charter.treasury.actions.Deposit;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.Container;
import net.minecraft.world.entity.Display;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * M9: the granary as real chests (PLAN.md §6, test K3). Each test builds its own
 * pair of factions in a corner of the world nothing else uses, with real chests and
 * real items, and drives time through {@code /charter admin time advance} so the
 * path is the one an operator would use.
 *
 * <p>Gametests cannot unload the chunk they run in, so the unloaded chunk case sets
 * the service's {@code pretendUnloaded} seam and then runs the chunk-load hook
 * itself, the same method the Fabric event calls.
 */
public final class GranaryGameTest {

    private static final String OW = "minecraft:overworld";
    /**
     * Each world takes its own 40 chunk column so the keep gap rule never gets in the way,
     * and each run its own block of 300, because the test world (and its Charter state)
     * is kept between runs.
     */
    private static final int BASE_CHUNK_X = 3100 + 300 * (int) ((System.currentTimeMillis() / 1000) % 90);
    private static final int BASE_CHUNK_Z = 3100;
    private static final int Y = 100;

    private static final Item KELP = Items.DRIED_KELP;
    private static final Item BREAD = Items.BREAD;
    private static final Item BEEF = Items.COOKED_BEEF;

    /**
     * Every world shares one sim, so another test advancing time moves this one too: a world
     * that has to survive a tick boundary must not have a camp that could besiege it.
     * The knobs a world changes are restored when the last open world closes.
     */
    private static int OPEN;
    private static int savedProtection;
    private static int savedMaxChests;

    /** Two factions, Ashfall's keep with a chest granary, and Brinewatch's camp beside it. */
    private static final class World implements AutoCloseable {
        final GameTestHelper helper;
        final MinecraftServer server;
        final CharterRuntime rt = CharterServer.runtime();
        final GranaryService svc = Granaries.service();
        final ServerLevel level;
        final int cx;
        final int cz;
        final ServerPlayer lord;
        final PlayerId lordId;
        OrgId ashfall;
        OrgId brine;
        Core keep;
        Core camp;
        private final boolean withCamp;

        World(GameTestHelper helper, int slot) {
            this(helper, slot, true);
        }

        World(GameTestHelper helper, int slot, boolean withCamp) {
            this.withCamp = withCamp;
            this.helper = helper;
            this.server = helper.getLevel().getServer();
            this.level = server.overworld();
            this.cx = BASE_CHUNK_X + 40 * slot;
            this.cz = BASE_CHUNK_Z;
            this.lord = helper.makeMockServerPlayerInLevel();
            this.lordId = new PlayerId(lord.getUUID());
            helper.assertTrue(svc != null, "The granary service is not running");
            level.setChunkForced(cx, cz, true);
            level.getChunk(cx, cz);
            if (OPEN++ == 0) {
                savedProtection = rt.knobs.newFactionProtectionDays;
                savedMaxChests = rt.knobs.granaryMaxChests;
            }
            rt.knobs.newFactionProtectionDays = 0;
            build();
        }

        PlayerId seen(String name) {
            PlayerId id = new PlayerId(UUID.randomUUID());
            rt.state.touch(id, name, rt.clock.now());
            return id;
        }

        void build() {
            Actor admin = new Actor.Admin("m9");
            rt.state.touch(lordId, "Ashlord", rt.clock.now());
            String tag = Long.toString(lord.getUUID().getLeastSignificantBits() & 0xFFFFFF, 36);
            helper.assertTrue(rt.engine.run(admin, new Found(lordId, "Ashfall " + tag, Preset.LORDSHIP)).done(), "Ashfall was not founded");
            ashfall = rt.state.orgNamed("Ashfall " + tag).orElseThrow().id;
            PlayerId zed = seen("Brinelord");
            helper.assertTrue(rt.engine.run(new Actor.Player(zed), new Found(zed, "Brinewatch " + tag, Preset.LORDSHIP)).done(), "Brinewatch was not founded");
            brine = rt.state.orgNamed("Brinewatch " + tag).orElseThrow().id;
            join(ashfall, lordId, seen("Ashhand"));
            join(brine, zed, seen("Brinehand"));
            for (OrgId org : List.of(ashfall, brine)) {
                rt.engine.run(admin, new Deposit(org, Currency.DIAMOND, 400));
                rt.engine.run(admin, new Deposit(org, Currency.COBBLESTONE, 5000));
            }
            var placed = rt.engine.run(admin, new PlaceNexus(ashfall, new ChunkKey(OW, cx, cz)));
            helper.assertTrue(placed.done(), "The keep was refused: " + placed);
            keep = rt.state.coresOf(ashfall).getFirst();
            if (!withCamp) return;
            var planted = rt.engine.run(admin, new PlantCamp(brine, new ChunkKey(OW, cx + 2, cz)));
            helper.assertTrue(planted.done(), "The camp was refused: " + planted);
            camp = rt.state.coresOf(brine).stream().filter(c -> !c.isKeep()).findFirst().orElseThrow();
            var fed = rt.sim.supply().deposit("setup", camp.id, Map.of("minecraft:bread", 2000), new ArrayList<>());
            helper.assertTrue(fed.getOrDefault("minecraft:bread", 0) == 2000, "The camp store was not stocked");
        }

        void join(OrgId org, PlayerId lordOf, PlayerId other) {
            helper.assertTrue(rt.engine.run(new Actor.Player(lordOf), new InviteMember(org, other)).done(), "Invite refused");
            helper.assertTrue(rt.engine.run(new Actor.Player(other), new AcceptInvite(other, org)).done(), "Accept refused");
        }

        BlockPos chestAt(int index) {
            return new BlockPos(cx * 16 + 2 + index * 2, Y, cz * 16 + 4);
        }

        /** Places a real chest and registers it with the granary through the admin command. */
        Container chest(int index) {
            BlockPos at = chestAt(index);
            level.setBlock(at, Blocks.AIR.defaultBlockState(), 3); // an empty chest, never one left by an earlier test
            level.setBlock(at, Blocks.CHEST.defaultBlockState(), 3);
            command("charter admin granary set " + at.getX() + " " + at.getY() + " " + at.getZ());
            return container(at);
        }

        Container container(BlockPos at) {
            helper.assertTrue(level.getBlockEntity(at) instanceof ChestBlockEntity, "No chest at " + at);
            return (ChestBlockEntity) level.getBlockEntity(at);
        }

        void command(String command) {
            server.getCommands().performPrefixedCommand(server.createCommandSourceStack(), command);
        }

        /** Besieged: the camp raises (two siege hours), then counts against the keep. */
        void besiege() {
            command("charter admin time advance 2h");
            helper.assertTrue(rt.state.siege.keep(keep.id).supply == KeepSupply.BESIEGED,
                    "The keep is not besieged: " + rt.state.siege.keep(keep.id).supply);
        }

        void advance(String duration) {
            command("charter admin time advance " + duration);
        }

        @Override
        public void close() {
            svc.pretendUnloaded.clear();
            if (--OPEN == 0) {
                rt.knobs.newFactionProtectionDays = savedProtection;
                rt.knobs.granaryMaxChests = savedMaxChests;
            }
            svc.book().remove(keep.id);
            // Leave no keep or camp behind in the shared state.
            for (Core core : java.util.Arrays.asList(camp, keep)) {
                if (core != null && rt.state.core(core.id).isPresent()) {
                    rt.engine.run(new Actor.Admin("m9"), new LoseCore(core.id));
                }
            }
            level.setChunkForced(cx, cz, false);
        }
    }

    static void put(Container box, Item item, int count) {
        int left = count;
        for (int i = 0; i < box.getContainerSize() && left > 0; i++) {
            if (!box.getItem(i).isEmpty()) continue;
            int n = Math.min(left, item.getDefaultMaxStackSize());
            box.setItem(i, new ItemStack(item, n));
            left -= n;
        }
        box.setChanged();
    }

    static int count(Container box, Item item) {
        int n = 0;
        for (int i = 0; i < box.getContainerSize(); i++) {
            if (box.getItem(i).is(item)) n += box.getItem(i).getCount();
        }
        return n;
    }

    static int mirror(World w, Item item) {
        return w.rt.state.siege.store(w.keep.id).provisions.count(BuiltInRegistries.ITEM.getKey(item).toString());
    }

    private static InteractionResult rightClick(ServerPlayer player, ServerLevel level, BlockPos at) {
        return UseBlockCallback.EVENT.invoker().interact(player, level, InteractionHand.MAIN_HAND,
                new BlockHitResult(Vec3.atCenterOf(at), Direction.UP, at, false));
    }

    // ---- K3 ----

    @GameTest(maxTicks = 200)
    public void theRealChestLosesTheLeastNourishingFoodFirst(GameTestHelper helper) {
        try (World w = new World(helper, 0)) {
            Container chest = w.chest(0);
            put(chest, KELP, 40);
            put(chest, BREAD, 50);
            put(chest, BEEF, 30);
            w.svc.reconcile(w.svc.book().site(w.keep.id), null);
            helper.assertTrue(mirror(w, KELP) == 40 && mirror(w, BREAD) == 50 && mirror(w, BEEF) == 30,
                    "The mirror did not read the chest: kelp " + mirror(w, KELP) + ", bread " + mirror(w, BREAD) + ", beef " + mirror(w, BEEF));

            w.besiege();
            helper.assertTrue(count(chest, KELP) == 40 && count(chest, BREAD) == 50,
                    "The chest changed before the siege drained anything: kelp " + count(chest, KELP) + ", bread " + count(chest, BREAD));

            w.advance("2h");
            int kelp = count(chest, KELP);
            int bread = count(chest, BREAD);
            int beef = count(chest, BEEF);
            helper.assertTrue(kelp == 0, "Dried kelp (1 each) should have been eaten first, " + kelp + " left");
            helper.assertTrue(bread < 50 && bread >= 44, "Bread (5 each) should be eaten after the kelp, " + bread + " of 50 left");
            helper.assertTrue(beef == 30, "Cooked beef (8 each) should not be touched while cheaper food remains, " + beef + " of 30 left");
            helper.assertTrue(mirror(w, KELP) == kelp && mirror(w, BREAD) == bread && mirror(w, BEEF) == beef,
                    "The mirror disagrees with the real chest");

            Granaries.refreshDisplays();
            String shown = Granaries.displayText(w.keep.id);
            helper.assertTrue(shown != null && shown.contains("Granary") && shown.contains("Provisions") && shown.contains("Besieged"),
                    "The display does not show the time left: " + shown);
            BlockPos at = w.chestAt(0);
            Display.TextDisplay display = Granaries.displayEntity(w.keep.id);
            helper.assertTrue(display != null && !display.isRemoved(), "No display entity above the chest");
            helper.assertTrue(display.entityTags().contains("charter_granary") && display.level() == w.level
                    && Math.abs(display.getY() - (at.getY() + 1.6)) < 0.01, "The display is not above the chest");

            helper.assertTrue(rightClick(helper.makeMockServerPlayerInLevel(), w.level, at) == InteractionResult.FAIL,
                    "An outsider could open the granary while the keep stands");
            helper.assertTrue(rightClick(w.lord, w.level, at) == InteractionResult.SUCCESS, "A member could not open the granary");
            helper.succeed();
        }
    }

    @GameTest(maxTicks = 200)
    public void afterTheKeepFallsAnOutsiderCanOpenIt(GameTestHelper helper) {
        try (World w = new World(helper, 1)) {
            Container chest = w.chest(0);
            put(chest, BREAD, 30);
            w.svc.reconcile(w.svc.book().site(w.keep.id), null);
            w.besiege();
            ServerPlayer stranger = helper.makeMockServerPlayerInLevel();
            helper.assertTrue(rightClick(stranger, w.level, w.chestAt(0)) == InteractionResult.FAIL, "An outsider opened the granary before the fall");

            w.advance("4h");
            for (int minute = 0; minute < 120 && !w.rt.state.siege.falls.containsKey(w.keep.id); minute++) w.advance("1m");
            helper.assertTrue(w.rt.state.siege.falls.containsKey(w.keep.id), "The keep did not fall with a 30 bread granary");
            helper.assertTrue(w.rt.state.history.has(SiegeEvents.KeepFell.class), "No fall was recorded");
            helper.assertTrue(count(chest, BREAD) == 0, "The garrison left bread in the chest of a keep that starved: " + count(chest, BREAD));
            helper.assertTrue(mirror(w, BREAD) == 0, "The mirror still holds bread");
            helper.assertTrue(w.svc.access(new PlayerId(stranger.getUUID()), w.keep) == Access.FULL, "An outsider has no access after the fall");
            helper.assertTrue(rightClick(stranger, w.level, w.chestAt(0)) == InteractionResult.SUCCESS, "An outsider could not open the granary after the fall");

            // Once the besieger has chosen, or time runs out, the keep is razed and the chest is an ordinary one.
            w.advance("11m");
            helper.assertTrue(w.rt.state.core(w.keep.id).isEmpty(), "The fallen keep was not razed when the choice ran out");
            helper.assertTrue(w.svc.book().site(w.keep.id) == null, "A razed keep still has a registered granary");
            helper.succeed();
        }
    }

    // ---- the mirror ----

    @GameTest(maxTicks = 200)
    public void removalsQueuedWhileAwayAreAppliedWhenTheChunkLoads(GameTestHelper helper) {
        try (World w = new World(helper, 2)) {
            Container chest = w.chest(0);
            put(chest, KELP, 40);
            put(chest, BREAD, 50);
            w.svc.reconcile(w.svc.book().site(w.keep.id), null);
            w.besiege();

            w.svc.pretendUnloaded.add(w.keep.id);
            w.advance("2h");
            var site = w.svc.book().site(w.keep.id);
            helper.assertTrue(count(chest, KELP) == 40 && count(chest, BREAD) == 50, "A chest that is not loaded was touched");
            helper.assertTrue(mirror(w, KELP) == 0, "The sim did not eat the mirror of the unloaded granary");
            helper.assertTrue(site.queued.getOrDefault("minecraft:dried_kelp", 0) == 40, "The kelp eaten was not queued: " + site.queued);
            int queuedBread = site.queued.getOrDefault("minecraft:bread", 0);
            helper.assertTrue(queuedBread >= 1 && queuedBread <= 6, "The bread eaten was not queued: " + site.queued);

            // The chunk loads: the hook applies the queue before anyone can open the chest.
            w.svc.pretendUnloaded.clear();
            w.svc.onChunkLoad(OW, w.cx, w.cz, w.level.getChunk(w.cx, w.cz));
            helper.assertTrue(count(chest, KELP) == 0, "The queued kelp was not taken from the real chest");
            helper.assertTrue(count(chest, BREAD) == 50 - queuedBread, "The queued bread was not taken: " + count(chest, BREAD));
            helper.assertTrue(site.queued.isEmpty(), "The queue was not cleared: " + site.queued);
            helper.assertTrue(mirror(w, BREAD) == count(chest, BREAD), "The mirror and the chest disagree after the load");
            helper.succeed();
        }
    }

    @GameTest(maxTicks = 200)
    public void openingTheChestAppliesTheQueueFirst(GameTestHelper helper) {
        try (World w = new World(helper, 3)) {
            Container chest = w.chest(0);
            put(chest, KELP, 40);
            put(chest, BREAD, 50);
            w.svc.reconcile(w.svc.book().site(w.keep.id), null);
            w.besiege();
            w.svc.pretendUnloaded.add(w.keep.id);
            w.advance("1h");
            helper.assertTrue(count(chest, KELP) == 40, "A chest that is not loaded was touched");
            w.svc.pretendUnloaded.clear();
            helper.assertTrue(rightClick(w.lord, w.level, w.chestAt(0)) == InteractionResult.SUCCESS, "The lord could not open the granary");
            helper.assertTrue(count(chest, KELP) == 10, "Opening the chest did not apply the queue first: " + count(chest, KELP) + " kelp");
            helper.succeed();
        }
    }

    // ---- the screen ----

    @GameTest(maxTicks = 200)
    public void depositsGoThroughTheCarryInRule(GameTestHelper helper) {
        try (World w = new World(helper, 4)) {
            Container chest = w.chest(0);
            put(chest, BREAD, 20);
            w.svc.reconcile(w.svc.book().site(w.keep.id), null);
            w.besiege();
            var site = w.svc.book().site(w.keep.id);
            var screen = new GranaryScreen(w.lord, w.svc, site, w.keep, Access.FULL);

            // Under siege, food that was not carried in through an open side is handed back.
            int before = count(chest, BREAD);
            put(chest, BREAD, 30);
            screen.settle();
            helper.assertTrue(count(chest, BREAD) == before, "Food with no carry-in allowance stayed in the chest: " + count(chest, BREAD));
            helper.assertTrue(mirror(w, BREAD) == before, "The mirror took food that was refused");

            // Carried in through an open side, ten bread count.
            String carrier = "player:" + w.lord.getUUID();
            var entry = w.rt.sim.supply().enter(carrier, new ChunkKey(OW, w.cx - 2, w.cz), new ChunkKey(OW, w.cx - 1, w.cz), Map.of("minecraft:bread", 10));
            helper.assertTrue(entry == charter.siege.Supply.Entry.GRANTED, "The crossing did not grant an allowance: " + entry);
            put(chest, BREAD, 30);
            screen.settle();
            helper.assertTrue(count(chest, BREAD) == before + 10, "Ten carried-in bread should stay, found " + (count(chest, BREAD) - before));
            helper.assertTrue(mirror(w, BREAD) == before + 10, "The mirror does not match the chest after a deposit");
            helper.assertTrue(w.rt.state.history.has(SiegeEvents.FoodDeposited.class), "The deposit was not recorded");
            helper.succeed();
        }
    }

    /**
     * M8's hopper gate and the granary together: a real hopper above a granary chest
     * fills it in peace, and fills nothing while the keep is besieged, since the
     * mirror would otherwise take hopper-fed food as provisions (DESIGN.md §10.4).
     */
    @GameTest(maxTicks = 200, environment = "charter-gametest:long_siege")
    public void aHopperCannotFillABesiegedGranary(GameTestHelper helper) {
        // One tick throughout: other tests share the siege clock, so a besieged world must not wait.
        try (World w = new World(helper, 7)) {
            Container chest = w.chest(0);
            Container source = new net.minecraft.world.SimpleContainer(1);
            ItemStack left = net.minecraft.world.level.block.entity.HopperBlockEntity.addItem(
                    source, chest, new ItemStack(BREAD, 3), net.minecraft.core.Direction.DOWN);
            helper.assertTrue(left.isEmpty() && count(chest, BREAD) == 3, "In peace a hopper should fill the granary, left " + left.getCount());
            w.svc.reconcile(w.svc.book().site(w.keep.id), null);

            w.besiege();
            ItemStack refused = net.minecraft.world.level.block.entity.HopperBlockEntity.addItem(
                    source, chest, new ItemStack(BREAD, 5), net.minecraft.core.Direction.DOWN);
            helper.assertTrue(refused.getCount() == 5, "Under siege the hopper put " + (5 - refused.getCount()) + " bread into the granary");
            helper.assertTrue(count(chest, BREAD) == 3, "The granary chest changed under siege: " + count(chest, BREAD));

            Container ordinary = w.container(placeChest(w, w.chestAt(3).above(2)));
            ItemStack free = net.minecraft.world.level.block.entity.HopperBlockEntity.addItem(
                    source, ordinary, new ItemStack(BREAD, 4), net.minecraft.core.Direction.DOWN);
            helper.assertTrue(free.isEmpty() && count(ordinary, BREAD) == 4, "A chest that is not a granary should fill as usual");
            helper.succeed();
        }
    }

    static BlockPos placeChest(World w, BlockPos at) {
        w.level.setBlock(at, Blocks.AIR.defaultBlockState(), 3); // an empty chest, never one left by an earlier test
        w.level.setBlock(at, Blocks.CHEST.defaultBlockState(), 3);
        return at;
    }

    // ---- linking ----

    @GameTest(maxTicks = 200)
    public void chestsLinkToTheGranaryUpToTheLimit(GameTestHelper helper) {
        try (World w = new World(helper, 5)) {
            w.rt.knobs.granaryMaxChests = 2;
            BlockPos a = new BlockPos(w.cx * 16 + 3, Y, w.cz * 16 + 3);
            BlockPos next = a.east();
            BlockPos third = a.east(2);
            BlockPos apart = new BlockPos(w.cx * 16 + 10, Y, w.cz * 16 + 10);
            BlockPos elsewhere = new BlockPos((w.cx + 1) * 16 + 3, Y, w.cz * 16 + 3);
            for (BlockPos p : List.of(a, next, third, apart, elsewhere)) w.level.setBlock(p, Blocks.CHEST.defaultBlockState(), 3);

            helper.assertTrue(w.svc.link(w.keep, w.level, elsewhere, false, true) != null, "A chest outside the core chunk joined");
            helper.assertTrue(w.svc.link(w.keep, w.level, a, false, true) == null, "The first chest did not become the granary");
            helper.assertTrue(w.svc.link(w.keep, w.level, apart, false, true) != null, "A chest that does not touch the granary joined");
            helper.assertTrue(w.svc.link(w.keep, w.level, next, false, true) == null, "A touching chest did not join");
            helper.assertTrue("granary.full".equals(w.svc.link(w.keep, w.level, third, false, true)), "The limit of two chests did not hold");
            GranaryBook.Site site = w.svc.book().site(w.keep.id);
            helper.assertTrue(site.containers.equals(List.of(new Pos(a.getX(), a.getY(), a.getZ()), new Pos(next.getX(), next.getY(), next.getZ()))),
                    "The granary holds the wrong chests: " + site.containers);

            // Breaking the chests ends the granary on the next step, and its food with it.
            for (BlockPos p : List.of(a, next)) w.level.setBlock(p, Blocks.AIR.defaultBlockState(), 3);
            w.advance("1m");
            helper.assertTrue(w.svc.book().site(w.keep.id) == null, "A granary with no chests left is still registered");
            helper.succeed();
        }
    }

    @GameTest(maxTicks = 200)
    public void aMemberPlacingAChestInTheCoreChunkLinksIt(GameTestHelper helper) {
        World w = new World(helper, 6, false);
        BlockPos at = new BlockPos(w.cx * 16 + 5, Y, w.cz * 16 + 5);
        w.lord.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.CHEST));
        // The use event notes the placement; the block appears as vanilla would place it.
        rightClick(w.lord, w.level, at.below());
        w.level.setBlock(at, Blocks.AIR.defaultBlockState(), 3); // an empty chest, never one left by an earlier test
        w.level.setBlock(at, Blocks.CHEST.defaultBlockState(), 3);
        helper.runAfterDelay(3, () -> {
            try {
                helper.assertTrue(w.svc.book().at(OW, new Pos(at.getX(), at.getY(), at.getZ())) != null, "The member's chest did not become the granary");
                helper.assertTrue(w.svc.book().site(w.keep.id) != null, "The keep has no granary");
                helper.succeed();
            } finally {
                w.close();
            }
        });
    }

    // ---- the food table ----

    @GameTest
    public void everyItemInTheFoodTableIsARealFood(GameTestHelper helper) {
        List<String> warnings = new ArrayList<>();
        var table = new charter.food.FoodTable();
        FoodConfig.apply(FoodConfig.defaults(), table, Granaries::vanillaNourishment, warnings::add);
        helper.assertTrue(warnings.isEmpty(), "The shipped food.json names something that is not a food: " + warnings);
        helper.assertTrue(table.entries().size() >= 30, "The shipped table is suspiciously small: " + table.entries().size());
        for (var entry : table.entries().entrySet()) {
            Item item = BuiltInRegistries.ITEM.getValue(Identifier.parse(entry.getKey()));
            helper.assertTrue(item != null && item != Items.AIR, entry.getKey() + " is not in the item registry");
            var food = item.components().get(DataComponents.FOOD);
            helper.assertTrue(food != null, entry.getKey() + " has no food component");
            helper.assertTrue(entry.getValue().nourishment() == food.nutrition(),
                    entry.getKey() + " nourishment " + entry.getValue().nourishment() + " is not the vanilla " + food.nutrition());
        }
        var live = Granaries.service().food();
        helper.assertTrue(live.isFood("minecraft:bread") && live.isFood("minecraft:cooked_beef"),
                "The running server did not load food.json into the siege food table");
        helper.assertTrue(live == CharterServer.runtime().state.siege.food, "The siege book is not using the loaded food table");
        helper.succeed();
    }
}
