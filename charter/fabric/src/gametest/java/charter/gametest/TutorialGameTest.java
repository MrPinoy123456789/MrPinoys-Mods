package charter.gametest;

import charter.config.Knobs;
import charter.id.OrgId;
import charter.id.PlayerId;
import charter.land.CampPhase;
import charter.land.Core;
import charter.mc.CharterRuntime;
import charter.mc.CharterServer;
import charter.mc.granary.Granaries;
import charter.mc.granary.GranaryService;
import charter.mc.land.LandItems;
import charter.mc.scenario.ScenarioCommands;
import charter.mc.scenario.ScenarioFiles;
import charter.org.MemberRecord;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

import java.lang.reflect.Field;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The tutorial, played page by page as its book tells a new player to, through the
 * real commands and the real item placements: found a faction, fill the vault,
 * place the Nexus, make a granary from a chest, find the rival's land protected,
 * plant the War Banner at the gold pillar, make a camp store from the barrel, and
 * declare war on Brinewatch's outpost and take it.
 *
 * <p>Granary and camp store chests are linked on the tick after they are placed, so
 * this test runs over a few ticks. It runs in a batch of its own ({@code
 * tutorial_alone}): {@code scenario clear} takes down the faction a named player
 * founded, so a scenario test clearing beside it would take down this one's.
 */
public final class TutorialGameTest {

    private static final int CHUNK_X = 14_500;
    private static final int CHUNK_Z = 12_000;

    @GameTest(maxTicks = 100, environment = "charter-gametest:tutorial_alone")
    public void aNewPlayerFollowsTheTutorialBookToTheFall(GameTestHelper helper) throws Exception {
        MinecraftServer server = helper.getLevel().getServer();
        ServerLevel level = server.overworld();
        CharterRuntime rt = CharterServer.runtime();
        GranaryService granaries = Granaries.service();
        Map<Field, Object> knobs = new HashMap<>();
        for (Field f : Knobs.class.getFields()) {
            if (!java.lang.reflect.Modifier.isStatic(f.getModifiers())) knobs.put(f, f.get(rt.knobs));
        }
        for (String name : ScenarioFiles.DEFAULTS) Files.deleteIfExists(ScenarioCommands.dir().resolve(name + ".json"));
        for (int x = CHUNK_X - 2; x <= CHUNK_X + 14; x++) {
            for (int z = CHUNK_Z - 2; z <= CHUNK_Z + 2; z++) level.setChunkForced(x, z, true);
        }

        ServerPlayer you = helper.makeMockServerPlayerInLevel();
        you.setGameMode(net.minecraft.world.level.GameType.SURVIVAL);
        PlayerId me = new PlayerId(you.getUUID());
        server.getCommands().performPrefixedCommand(server.createCommandSourceStack(),
                "charter admin scenario runat " + (CHUNK_X * 16 + 8) + " " + (CHUNK_Z * 16 + 8) + " tutorial with " + you.getUUID());
        // The tutorial's settings go back at once: tests beside this one run during its ticks,
        // and nothing below needs them (the clock is moved by command).
        restore(rt, knobs);

        // What the scenario hands over: the makings of a Nexus, not a Nexus.
        helper.assertTrue(count(you, s -> LandItems.NEXUS.equals(LandItems.kindOf(s))) == 0, "You were given a ready Nexus");
        helper.assertTrue(count(you, s -> s.is(net.minecraft.tags.ItemTags.BANNERS) && LandItems.kindOf(s) == null) == 1, "You were not given a banner");
        helper.assertTrue(count(you, s -> s.is(Items.LODESTONE)) == 1, "You were not given a lodestone");
        helper.assertTrue(count(you, s -> LandItems.WAR_BANNER.equals(LandItems.kindOf(s))) == 1, "You were not given a War Banner");
        helper.assertTrue(count(you, s -> s.is(Items.DIAMOND)) == 72, "You were not given 72 diamonds");
        helper.assertTrue(you.experienceLevel >= 1, "You were given no level for the anvil");
        ItemStack book = first(you, s -> s.is(Items.WRITTEN_BOOK));
        helper.assertTrue(book != null, "You were not given the tutorial book");
        var content = book.get(DataComponents.WRITTEN_BOOK_CONTENT);
        helper.assertTrue(content != null && content.title().raw().equals("Charter: A First Hour") && content.pages().size() == 14,
                "The tutorial book is not the 14 page guide");
        Core rivalKeep = rt.state.cores().stream().filter(c -> c.isKeep() && c.center.x() == CHUNK_X + 12 && c.center.z() == CHUNK_Z)
                .findFirst().orElseThrow(() -> new AssertionError("Brinewatch has no keep 12 chunks east"));

        // Pages 1 and 2: name the banner at the anvil, craft it with the lodestone into a Nexus.
        String name = "Tutorial " + Long.toString(you.getUUID().getLeastSignificantBits() & 0xFFFFF, 36);
        ItemStack plainBanner = first(you, s -> s.is(net.minecraft.tags.ItemTags.BANNERS) && LandItems.kindOf(s) == null);
        plainBanner.set(DataComponents.CUSTOM_NAME, net.minecraft.network.chat.Component.literal(name));
        ItemStack lodestone = first(you, s -> s.is(Items.LODESTONE));
        net.minecraft.world.item.crafting.CraftingInput grid = net.minecraft.world.item.crafting.CraftingInput.of(2, 1, List.of(plainBanner, lodestone));
        charter.mc.book.NexusRecipe recipe = new charter.mc.book.NexusRecipe();
        helper.assertTrue(recipe.matches(grid, level), "The banner and the lodestone do not make a Nexus");
        ItemStack crafted = recipe.assemble(grid);
        plainBanner.shrink(1);
        lodestone.shrink(1);
        you.getInventory().add(crafted);

        // Page 3: the Nexus near the emerald pillar founds the faction and raises its keep.
        BlockPos nexus = ground(level, CHUNK_X, CHUNK_Z, 0, 0);
        you.teleportTo(nexus.getX() + 0.5, nexus.getY(), nexus.getZ() - 2.5);
        place(you, level, LandItems.NEXUS, nexus);
        OrgId mine = rt.state.membership(me).map(MemberRecord::org).orElse(null);
        helper.assertTrue(mine != null, "Placing the Nexus did not found a faction");
        helper.assertTrue(rt.state.org(mine).orElseThrow().name().equals(name), "The faction is not named after the banner");
        Core keep = rt.state.coresOf(mine).stream().filter(Core::isKeep).findFirst().orElse(null);
        helper.assertTrue(keep != null, "Placing the Nexus did not raise your keep");
        helper.assertTrue(count(you, s -> s.is(Items.DIAMOND)) == 8, "The keep did not take exactly its price from the diamonds you carried");

        // Page 5: the Pay button on the faction page, not a command.
        charter.mc.book.MemberPages.faction(you, null);
        String pay = charter.mc.Text.get("book.button.pay", "amount", 8, "currency",
                charter.mc.Describe.currency(charter.treasury.Currency.DIAMOND, 8));
        String token = charter.mc.cmd.CharterCommands.tokens().newest(you.getUUID(), pay).orElse(null);
        helper.assertTrue(token != null, "The faction page offers no [" + pay + "] button");
        charter.mc.cmd.CharterCommands.tokens().take(token, you.getUUID(), System.currentTimeMillis()).orElseThrow().accept(you);
        helper.assertTrue(rt.state.org(mine).orElseThrow().ledger.balance(charter.treasury.Currency.DIAMOND) == 8,
                "The diamonds did not reach the treasury");

        // Page 4: a chest in the Nexus's chunk.
        BlockPos chest = nexus.offset(2, 0, 0);
        placeItem(you, level, new ItemStack(Items.CHEST), chest);

        // Page 6: Brinewatch's land is protected.
        BlockPos theirs = ground(level, CHUNK_X + 13, CHUNK_Z, 0, 0).below();
        you.teleportTo(theirs.getX() + 0.5, theirs.getY() + 1, theirs.getZ() + 2.5);
        you.gameMode.destroyBlock(theirs);
        helper.assertTrue(!level.getBlockState(theirs).isAir(), "You broke a block on Brinewatch's land");

        // Page 7: the War Banner at the gold pillar.
        BlockPos banner = ground(level, CHUNK_X + 10, CHUNK_Z, 0, 0);
        you.teleportTo(banner.getX() + 0.5, banner.getY(), banner.getZ() - 2.5);
        place(you, level, LandItems.WAR_BANNER, banner);
        Core camp = rt.state.coresOf(mine).stream().filter(c -> !c.isKeep()).findFirst().orElse(null);
        helper.assertTrue(camp != null && camp.phase() == CampPhase.RAISING, "Planting the War Banner did not raise a camp");

        // Page 8: the barrel next to the banner, on the side the signal fire left free.
        BlockPos barrel = null;
        for (Direction side : List.of(Direction.SOUTH, Direction.WEST, Direction.EAST, Direction.NORTH)) {
            BlockPos at = banner.relative(side);
            if (level.getBlockState(at).canBeReplaced() && level.getBlockState(at.below()).isSolid()) {
                barrel = at;
                break;
            }
        }
        helper.assertTrue(barrel != null, "No room beside the banner for the barrel");
        placeItem(you, level, new ItemStack(Items.BARREL), barrel);
        BlockPos barrelAt = barrel;

        helper.runAfterDelay(3, () -> {
            var site = granaries.book().site(keep.id);
            helper.assertTrue(site != null && !site.containers.isEmpty(), "The chest in the Nexus's chunk did not become your granary: the block there is "
                    + level.getBlockState(chest) + ", and linking it now says " + granaries.link(keep, level, chest, true, false));
            var store = granaries.book().site(camp.id);
            helper.assertTrue(store != null && !store.containers.isEmpty(), "The barrel beside the banner did not become the camp store");
            ((Container) level.getBlockEntity(chest)).setItem(0, take(you, Items.BREAD, 40));
            ((Container) level.getBlockEntity(barrelAt)).setItem(0, take(you, Items.BREAD, 48));
            granaries.reconcile(site, null);
            granaries.reconcile(store, null);
            helper.assertTrue(rt.state.siege.store(camp.id).provisions.count("minecraft:bread") == 48, "The camp store does not hold the 48 bread");

            // Page 8 to 9: the outposts raise. Brinewatch has one too, beyond its keep.
            server.getCommands().performPrefixedCommand(server.createCommandSourceStack(), "charter admin time advance 2h");
            Core raised = rt.state.core(camp.id).orElseThrow();
            helper.assertTrue(raised.phase() == CampPhase.ACTIVE, "Your outpost did not finish raising");
            helper.assertTrue(rt.state.core(rivalKeep.id).isPresent(), "Brinewatch's keep is gone though keeps cannot fall");
            Core rivalOutpost = rt.state.cores().stream().filter(c -> !c.isKeep() && c.center.x() == CHUNK_X + 16 && c.center.z() == CHUNK_Z)
                    .findFirst().orElse(null);
            helper.assertTrue(rivalOutpost != null && rivalOutpost.activeCamp(), "Brinewatch has no raised outpost 16 chunks east");

            // Page 10: War, from the Charter, names their outpost and confirms.
            charter.mc.book.WarPages.page(you, null);
            String named = charter.mc.Text.get("book.war.declare_button", "org", rt.describe.org(rt.state.root(rivalOutpost.owner)),
                    "x", rivalOutpost.center.x() * 16 + 8, "z", rivalOutpost.center.z() * 16 + 8);
            String named1 = charter.mc.cmd.CharterCommands.tokens().newest(you.getUUID(), named).orElse(null);
            helper.assertTrue(named1 != null, "The War page offers no [" + named + "] button; the page says: " + warPageText(you));
            charter.mc.cmd.CharterCommands.tokens().take(named1, you.getUUID(), System.currentTimeMillis()).orElseThrow().accept(you);
            String yes = charter.mc.Text.get("book.war.declare_yes");
            String yes1 = charter.mc.cmd.CharterCommands.tokens().newest(you.getUUID(), yes).orElse(null);
            helper.assertTrue(yes1 != null, "The confirm page offers no [" + yes + "] button");
            charter.mc.cmd.CharterCommands.tokens().take(yes1, you.getUUID(), System.currentTimeMillis()).orElseThrow().accept(you);
            var goal = rt.state.wars.against(rivalOutpost.id).orElse(null);
            helper.assertTrue(goal != null, "Declaring war from the book made no goal");

            // Page 11: the notice is over (set by hand, the clock is shared), you hold their chunk and take it.
            goal.opensAt = rt.state.siegeClock.now();
            BlockPos theirBanner = new BlockPos((CHUNK_X + 16) * 16 + 8, level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,
                    (CHUNK_X + 16) * 16 + 8, CHUNK_Z * 16 + 8), CHUNK_Z * 16 + 8);
            you.teleportTo(theirBanner.getX() + 0.5, theirBanner.getY(), theirBanner.getZ() + 3.5);
            int savedMinSeconds = rt.knobs.presenceMinMemberSeconds;
            rt.knobs.presenceMinMemberSeconds = 0;
            charter.mc.war.SiegeWorld.beforeStep(server, rt);
            rt.sim.advance(charter.time.Durations.MINUTE);
            helper.assertTrue(goal.opened, "Capture did not open");
            goal.progress = charter.time.Durations.minutes(rt.knobs.outpostCaptureMinutes) - charter.time.Durations.MINUTE / 2;
            long diamonds = rt.state.org(mine).orElseThrow().ledger.balance(charter.treasury.Currency.DIAMOND);
            charter.mc.war.SiegeWorld.beforeStep(server, rt);
            rt.sim.advance(charter.time.Durations.MINUTE);
            helper.assertTrue(rt.state.core(rivalOutpost.id).isEmpty(), "Holding their chunk did not capture the outpost: attackers "
                    + charter.mc.war.SiegeWorld.presence().attackers(goal, rivalOutpost) + ", defenders " + charter.mc.war.SiegeWorld.presence().defenders(rivalOutpost)
                    + ", progress " + goal.progress + ", you at " + you.position() + " chunk " + (you.getBlockX() >> 4) + "," + (you.getBlockZ() >> 4)
                    + ", outpost chunk " + rivalOutpost.center.x() + "," + rivalOutpost.center.z() + ", minDays " + rt.knobs.presenceMinMemberSeconds
                    + ", alive " + you.isAlive() + ", spectator " + you.isSpectator());
            helper.assertTrue(rt.state.org(mine).orElseThrow().ledger.balance(charter.treasury.Currency.DIAMOND) > diamonds,
                    "Their deposit did not reach your treasury");
            rt.knobs.presenceMinMemberSeconds = savedMinSeconds;

            // scenario clear resets the tutorial whole: Brinewatch, and the faction you founded in it.
            server.getCommands().performPrefixedCommand(server.createCommandSourceStack(), "charter admin scenario clear");
            helper.assertTrue(rt.state.membership(me).isEmpty(), "scenario clear left you in the tutorial faction");
            helper.assertTrue(rt.state.coresOf(mine).isEmpty() && rt.state.claimsOf(mine).isEmpty(), "scenario clear left your tutorial keep or camp");
            server.getPlayerList().remove(you);
            for (int x = CHUNK_X - 2; x <= CHUNK_X + 14; x++) {
                for (int z = CHUNK_Z - 2; z <= CHUNK_Z + 2; z++) level.setChunkForced(x, z, false);
            }
            helper.succeed();
        });
    }

    private static String warPageText(ServerPlayer player) {
        var dialog = charter.mc.book.BookDialogs.LAST.get(player.getUUID());
        StringBuilder out = new StringBuilder();
        if (dialog == null) return "(no dialog)";
        for (var b : dialog.common().body()) {
            if (b instanceof net.minecraft.server.dialog.body.PlainMessage m) out.append(m.contents().getString()).append(" | ");
        }
        out.append("buttons: ");
        for (var button : charter.mc.book.BookDialogs.buttons(dialog)) out.append("[").append(button.button().label().getString()).append("] ");
        return out.toString();
    }

    private static void restore(CharterRuntime rt, Map<Field, Object> knobs) {
        for (Map.Entry<Field, Object> e : knobs.entrySet()) {
            try {
                e.getKey().set(rt.knobs, e.getValue());
            } catch (IllegalAccessException bad) {
                throw new IllegalStateException(bad);
            }
        }
    }

    private static void type(MinecraftServer server, ServerPlayer player, String command) {
        server.getCommands().performPrefixedCommand(player.createCommandSourceStack(), command);
    }

    private static BlockPos ground(ServerLevel level, int chunkX, int chunkZ, int dx, int dz) {
        int x = chunkX * 16 + 8 + dx;
        int z = chunkZ * 16 + 8 + dz;
        return new BlockPos(x, level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z), z);
    }

    /** Places the first of the mod's items of this kind from the inventory, by a right click on the block below. */
    private static void place(ServerPlayer player, ServerLevel level, String kind, BlockPos at) {
        ItemStack stack = first(player, s -> kind.equals(LandItems.kindOf(s)));
        if (stack == null) throw new AssertionError("You hold no " + kind);
        use(player, level, stack, at);
    }

    private static void placeItem(ServerPlayer player, ServerLevel level, ItemStack stack, BlockPos at) {
        use(player, level, stack, at);
    }

    private static void use(ServerPlayer player, ServerLevel level, ItemStack stack, BlockPos at) {
        player.setItemInHand(InteractionHand.MAIN_HAND, stack);
        BlockPos support = at.below();
        BlockHitResult hit = new BlockHitResult(Vec3.atCenterOf(support).add(0, 0.5, 0), Direction.UP, support, false);
        player.gameMode.useItemOn(player, level, stack, InteractionHand.MAIN_HAND, hit);
    }

    private static ItemStack first(ServerPlayer player, java.util.function.Predicate<ItemStack> match) {
        var inv = player.getInventory();
        for (int i = 0; i < inv.getContainerSize(); i++) {
            if (match.test(inv.getItem(i))) return inv.getItem(i);
        }
        return null;
    }

    private static int count(ServerPlayer player, java.util.function.Predicate<ItemStack> match) {
        int n = 0;
        var inv = player.getInventory();
        for (int i = 0; i < inv.getContainerSize(); i++) {
            if (match.test(inv.getItem(i))) n += inv.getItem(i).getCount();
        }
        return n;
    }

    /** Takes {@code n} of an item out of the inventory, as a player moving it into a chest would. */
    private static ItemStack take(ServerPlayer player, Item item, int n) {
        var inv = player.getInventory();
        int left = n;
        for (int i = 0; i < inv.getContainerSize() && left > 0; i++) {
            ItemStack s = inv.getItem(i);
            if (!s.is(item)) continue;
            int k = Math.min(left, s.getCount());
            s.shrink(k);
            left -= k;
        }
        return new ItemStack(item, n - left);
    }
}
