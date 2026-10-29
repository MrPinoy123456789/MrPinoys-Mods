package charter.gametest;

import charter.id.OrgId;
import charter.land.Core;
import charter.land.LandEvents;
import charter.mc.CharterMod;
import charter.mc.CharterRuntime;
import charter.mc.CharterServer;
import charter.mc.chronicle.ChronicleBooks;
import charter.mc.chronicle.Chronicles;
import charter.mc.chronicle.RealmLecterns;
import charter.siege.SiegeEvents;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.WrittenBookContent;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LecternBlock;
import net.minecraft.world.level.block.entity.LecternBlockEntity;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * M13: the Chronicle in the world. A lectern in a keep's core chunk holds that faction's
 * Chronicle as a written book; the Realm Chronicle lectern holds the server's; both are
 * rewritten when history grows; admin events stay out unless the server opts in; no page
 * passes the limits. Events are recorded straight into the history (no siege clock moves),
 * so these run in the default batch.
 */
public final class ChronicleGameTest {

    private static BlockPos lecternAt(LandKit kit) {
        BlockPos pos = kit.nexusPos().offset(2, 0, 2);
        kit.level.setBlockAndUpdate(pos, Blocks.AIR.defaultBlockState());
        kit.level.setBlockAndUpdate(pos, Blocks.LECTERN.defaultBlockState());
        return pos;
    }

    private static List<String> pagesOf(LandKit kit, BlockPos lectern) {
        List<String> out = new ArrayList<>();
        if (!(kit.level.getBlockEntity(lectern) instanceof LecternBlockEntity be) || !be.hasBook()) return out;
        WrittenBookContent content = be.getBook().get(DataComponents.WRITTEN_BOOK_CONTENT);
        if (content == null) return out;
        content.pages().forEach(p -> out.add(p.raw().getString()));
        return out;
    }

    private static void refresh(LandKit kit) {
        Chronicles.refresh(kit.server, kit.rt);
    }

    /** A keep for the kit's faction and a rival to besiege it, both with their lords. */
    private record Duel(OrgId defender, OrgId attacker, Core keep, ServerPlayer lord) {
    }

    private static Duel duel(LandKit kit) {
        ServerPlayer lord = kit.faction("Def");
        ServerPlayer rival = kit.faction("Att");
        kit.keep(lord);
        OrgId defender = kit.orgOf(lord);
        OrgId attacker = kit.orgOf(rival);
        Core keep = kit.rt.state.coresOf(defender).getFirst();
        return new Duel(defender, attacker, keep, lord);
    }

    /** A keep's granary running down: two warnings, then the lapse. */
    private static void shortUpkeep(LandKit kit, Duel d) {
        CharterRuntime rt = kit.rt;
        long now = rt.clock.now();
        long siege = rt.state.siegeClock.now();
        rt.state.history.record(new SiegeEvents.KeepRunningLow(now, siege, d.keep().id, d.defender(), 7), "sim", false);
        rt.state.history.record(new SiegeEvents.KeepRunningLow(now + 1, siege, d.keep().id, d.defender(), 3), "sim", false);
        rt.state.history.record(new SiegeEvents.KeepLapsed(now + 2, siege, d.keep().id, d.defender(), d.keep().center), "sim", false);
    }

    @GameTest(maxTicks = 100)
    public void aLecternInTheCoreChunkShowsAGranaryRunningDownAsAStory(GameTestHelper helper) {
        try (LandKit kit = new LandKit(helper)) {
            Duel d = duel(kit);
            BlockPos lectern = lecternAt(kit);
            shortUpkeep(kit, d);
            refresh(kit);

            List<String> pages = pagesOf(kit, lectern);
            helper.assertTrue(!pages.isEmpty(), "The lectern holds no book");
            helper.assertTrue(kit.level.getBlockState(lectern).getValue(LecternBlock.HAS_BOOK), "The lectern does not show a book");
            String text = String.join("\n", pages);
            CharterMod.LOG.info("Chronicle sample (lectern):\n{}", String.join("\n---\n", pages));
            helper.assertTrue(pages.getFirst().startsWith("Chronicle of "), "The first page should name the Chronicle: " + pages.getFirst());
            helper.assertTrue(text.contains("was down to 7 days of food"), "The first warning is not told: " + text);
            helper.assertTrue(text.contains("was down to 3 days of food"), "The second warning is not told");
            helper.assertTrue(text.contains("its claim lapsed"), "The lapse is not told");
            helper.assertTrue(text.contains("every block stood where it was"), "The blocks standing is not told");
            helper.assertTrue(!text.contains("unknown") && !text.contains("{"), "A name or hole is missing: " + text);
            int lapsed = text.indexOf("its claim lapsed");
            int warned = text.indexOf("7 days of food");
            helper.assertTrue(lapsed >= 0 && warned >= 0 && lapsed < warned, "The newest tale should come first");

            ItemStack book = ((LecternBlockEntity) kit.level.getBlockEntity(lectern)).getBook();
            helper.assertTrue(book.is(Items.WRITTEN_BOOK), "The lectern's book is not a written book");
            helper.succeed();
        }
    }

    @GameTest(maxTicks = 200)
    public void theBookIsRegeneratedWhenAnEventArrives(GameTestHelper helper) {
        LandKit kit = new LandKit(helper);
        try {
            Duel d = duel(kit);
            BlockPos lectern = lecternAt(kit);
            refresh(kit);
            List<String> before = pagesOf(kit, lectern);
            helper.assertTrue(!before.isEmpty(), "The first book was not written");
            helper.assertTrue(!String.join("\n", before).contains("days of food"), "The upkeep was told before it began");

            // The real tick path: the event arrives and nothing calls refresh.
            shortUpkeep(kit, d);
            helper.runAfterDelay(Chronicles.CHANGE_CHECK_TICKS + 10, () -> {
                try {
                    String after = String.join("\n", pagesOf(kit, lectern));
                    helper.assertTrue(after.contains("days of food"), "The book did not change when the event arrived");
                    helper.assertTrue(!after.equals(String.join("\n", before)), "The book is the same as before");
                    helper.succeed();
                } finally {
                    kit.close();
                }
            });
        } catch (RuntimeException | Error e) {
            kit.close();
            throw e;
        }
    }

    @GameTest(maxTicks = 100)
    public void adminEventsStayOutOfTheBookUnlessTheServerOptsIn(GameTestHelper helper) {
        try (LandKit kit = new LandKit(helper)) {
            Duel d = duel(kit);
            BlockPos lectern = lecternAt(kit);
            kit.rt.state.history.record(new LandEvents.Rescued(kit.rt.clock.now(), d.defender(), 77), "console", true);
            kit.rt.state.history.record(new LandEvents.Rescued(kit.rt.clock.now() + 1, d.defender(), 78), "sim", false);
            refresh(kit);
            String hidden = String.join("\n", pagesOf(kit, lectern));
            helper.assertTrue(hidden.contains("78 claims"), "An ordinary event is missing from the book");
            helper.assertTrue(!hidden.contains("77 claims"), "An admin event reached a player's book");

            boolean saved = kit.rt.knobs.chronicleShowAdmin;
            try {
                kit.rt.knobs.chronicleShowAdmin = true;
                refresh(kit);
                String shown = String.join("\n", pagesOf(kit, lectern));
                helper.assertTrue(shown.contains("77 claims") && shown.contains("clerk"), "The opt in did not show the admin event: " + shown);
            } finally {
                kit.rt.knobs.chronicleShowAdmin = saved;
            }
            helper.succeed();
        }
    }

    @GameTest(maxTicks = 200)
    public void booksKeepWithinThePageLimitsAndOlderVolumesComeOnRequest(GameTestHelper helper) {
        try (LandKit kit = new LandKit(helper)) {
            Duel d = duel(kit);
            BlockPos lectern = lecternAt(kit);
            long at = kit.rt.clock.now();
            for (int i = 0; i < 1500; i++) {
                kit.rt.state.history.record(new LandEvents.Rescued(at + i, d.defender(), i + 2000), "sim", false);
            }
            refresh(kit);
            List<String> pages = pagesOf(kit, lectern);
            helper.assertTrue(pages.size() <= ChronicleBooks.VOLUME_PAGES, "The lectern book has " + pages.size() + " pages");
            helper.assertTrue(pages.size() <= ChronicleBooks.MAX_PAGES, "The book passes the vanilla limit");
            helper.assertTrue(pages.size() == ChronicleBooks.VOLUME_PAGES, "A long history should fill the volume, got " + pages.size());
            for (String page : pages) {
                helper.assertTrue(page.length() <= ChronicleBooks.MAX_PAGE_CHARS, "A page has " + page.length() + " characters");
            }
            helper.assertTrue(pages.getFirst().contains("/charter chronicle volume 2"), "The title page does not point at older volumes");
            helper.assertTrue(pages.get(1).contains("3499 claims"), "The newest tale should open the book: " + pages.get(1));

            // An older volume, on request, is a real book in the player's hands.
            String title = "Chronicle of " + kit.rt.state.orgIncludingDissolved(d.defender()).orElseThrow().name();
            kit.server.getCommands().performPrefixedCommand(d.lord().createCommandSourceStack(), "charter chronicle volume 2");
            ItemStack given = ItemStack.EMPTY;
            for (int i = 0; i < d.lord().getInventory().getContainerSize(); i++) {
                ItemStack s = d.lord().getInventory().getItem(i);
                if (s.is(Items.WRITTEN_BOOK)) given = s;
            }
            helper.assertTrue(!given.isEmpty(), "No book was handed over");
            WrittenBookContent content = given.get(DataComponents.WRITTEN_BOOK_CONTENT);
            helper.assertTrue(content != null && content.pages().size() <= ChronicleBooks.VOLUME_PAGES, "The volume is too long");
            helper.assertTrue(content.title().raw().startsWith(title.substring(0, Math.min(title.length(), 28))) || content.title().raw().contains("2"),
                    "The volume is not marked as the second: " + content.title().raw());
            helper.assertTrue(!content.pages().get(1).raw().getString().equals(pages.get(1)), "Volume 2 repeats volume 1");
            helper.succeed();
        }
    }

    @GameTest(maxTicks = 100)
    public void theRealmChronicleLecternHoldsTheServersStory(GameTestHelper helper) {
        try (LandKit kit = new LandKit(helper)) {
            Duel d = duel(kit);
            shortUpkeep(kit, d);
            BlockPos pos = kit.pos(-10, 0);
            kit.level.setBlockAndUpdate(pos, Blocks.AIR.defaultBlockState());
            RealmLecterns realms = RealmLecterns.of(kit.rt.state);
            RealmLecterns.Spot spot = new RealmLecterns.Spot("minecraft:overworld", pos.getX(), pos.getY(), pos.getZ());
            try {
                kit.server.getCommands().performPrefixedCommand(
                        kit.server.createCommandSourceStack().withLevel(kit.level).withPosition(Vec3.atCenterOf(pos)),
                        "charter admin realm-chronicle here");
                helper.assertTrue(kit.level.getBlockState(pos).is(Blocks.LECTERN), "The command placed no lectern");
                helper.assertTrue(realms.has(spot), "The lectern was not registered");
                List<String> pages = pagesOf(kit, pos);
                helper.assertTrue(!pages.isEmpty(), "The Realm lectern holds no book");
                helper.assertTrue(pages.getFirst().startsWith("The Realm Chronicle"), "Wrong title page: " + pages.getFirst());
                String text = String.join("\n", pages);
                helper.assertTrue(text.contains("its claim lapsed") && text.contains("days of food"), "The realm book does not tell the granary running down");
                helper.assertTrue(pages.size() <= ChronicleBooks.VOLUME_PAGES, "The realm book is too long");
                helper.succeed();
            } finally {
                realms.remove(spot);
                kit.level.setBlockAndUpdate(pos, Blocks.AIR.defaultBlockState());
            }
        }
    }

    @GameTest(maxTicks = 100)
    public void aPlayersOwnBookInTheLecternIsLeftAlone(GameTestHelper helper) {
        try (LandKit kit = new LandKit(helper)) {
            duel(kit);
            BlockPos lectern = lecternAt(kit);
            ItemStack own = new ItemStack(Items.WRITTEN_BOOK);
            own.set(DataComponents.WRITTEN_BOOK_CONTENT, new WrittenBookContent(
                    net.minecraft.server.network.Filterable.passThrough("My Notes"), "Someone", 0,
                    List.of(net.minecraft.server.network.Filterable.passThrough(net.minecraft.network.chat.Component.literal("Do not touch."))), true));
            kit.level.setBlockAndUpdate(lectern, kit.level.getBlockState(lectern).setValue(LecternBlock.HAS_BOOK, true));
            ((LecternBlockEntity) kit.level.getBlockEntity(lectern)).setBook(own);
            refresh(kit);
            List<String> pages = pagesOf(kit, lectern);
            helper.assertTrue(pages.size() == 1 && pages.getFirst().equals("Do not touch."), "A player's book was overwritten: " + pages);
            helper.succeed();
        }
    }
}
