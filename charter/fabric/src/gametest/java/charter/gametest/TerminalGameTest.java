package charter.gametest;

import charter.land.Core;
import charter.mc.CharterServer;
import charter.mc.Text;
import charter.mc.book.BedrockForms;
import charter.mc.book.BookDialogs;
import charter.mc.book.BookScreen;
import charter.mc.book.CharterMenu;
import charter.mc.chronicle.Chronicles;
import charter.mc.land.SpawnRules;
import charter.mc.terminal.TerminalBook;
import charter.mc.terminal.Terminals;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.LecternBlockEntity;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Charter terminals: a right-click on a registered block (or a member's own Nexus) opens that player's Home, through the
 * real use block event, ahead of the affiliation and spawn rules. Terminals are shared by every test running at once,
 * so each test removes the ones it made before it ends; every test finishes in one tick.
 */
public final class TerminalGameTest {

    /**
     * A right-click on the top of a block, through the game mode's use item on path a client's click takes (every mixin
     * and the whole use block event), then the off hand's half of the same click.
     */
    static InteractionResult click(LandKit kit, ServerPlayer player, BlockPos at) {
        BlockHitResult hit = new BlockHitResult(Vec3.atCenterOf(at).add(0, 0.5, 0), Direction.UP, at, false);
        InteractionResult main = player.gameMode.useItemOn(player, kit.level, player.getMainHandItem(), InteractionHand.MAIN_HAND, hit);
        InteractionResult off = UseBlockCallback.EVENT.invoker().interact(player, kit.level, InteractionHand.OFF_HAND, hit);
        if (main == InteractionResult.SUCCESS_SERVER && off != InteractionResult.SUCCESS_SERVER) {
            kit.helper.fail("The off hand half of a terminal click answered " + off);
        }
        return main;
    }

    /** A lectern in the arena, registered as a terminal. */
    private static TerminalBook.Spot terminal(LandKit kit, BlockPos at) {
        kit.level.setBlockAndUpdate(at, Blocks.LECTERN.defaultBlockState());
        TerminalBook.Spot spot = Terminals.spot(kit.level, at);
        kit.helper.assertTrue(Terminals.add(spot, null), "The terminal was not made");
        return spot;
    }

    /** The title and button labels of the last screen, which is all a test can see of the menu. */
    private static List<String> shape(ServerPlayer player) {
        BookScreen screen = BookDialogs.LAST.get(player.getUUID());
        List<String> out = new ArrayList<>();
        if (screen == null) return out;
        out.add(screen.title());
        for (var b : BookDialogs.buttons(screen)) out.add(b.label());
        return out;
    }

    /** The menu the book or the command would open for this player now. */
    private static List<String> menuOf(ServerPlayer player) {
        BookDialogs.LAST.remove(player.getUUID());
        CharterMenu.open(player);
        return shape(player);
    }

    private static void opensHome(LandKit kit, ServerPlayer player, BlockPos at, String who) {
        List<String> expected = menuOf(player);
        BookDialogs.LAST.remove(player.getUUID());
        Terminals.forget(player.getUUID());
        InteractionResult result = click(kit, player, at);
        kit.helper.assertTrue(result == InteractionResult.SUCCESS_SERVER, "A terminal click by " + who + " answered " + result);
        kit.helper.assertTrue(Terminals.opened(player) == 1, "A terminal did not open the menu for " + who);
        kit.helper.assertTrue(!expected.isEmpty() && expected.equals(shape(player)),
                "A terminal opened something other than the Home for " + who + ": " + shape(player) + " instead of " + expected);
    }

    @GameTest(maxTicks = 100)
    public void aTerminalOpensTheirOwnHomeForAMemberANewcomerAndADrifter(GameTestHelper helper) {
        try (LandKit kit = new LandKit(helper)) {
            ServerPlayer lord = kit.faction("Term");
            kit.keep(lord);
            BlockPos at = kit.pos(-6, 0);
            TerminalBook.Spot spot = terminal(kit, at);
            try {
                opensHome(kit, lord, at, "a member");
                helper.assertTrue(shape(lord).get(0).equals(menuOf(lord).get(0))
                        && !shape(lord).get(0).equals(Text.get("book.newcomer.title")), "The member did not get the member Home");
                ServerPlayer newcomer = kit.outsider();
                opensHome(kit, newcomer, at, "a newcomer");
                helper.assertTrue(shape(newcomer).get(0).equals(Text.get("book.newcomer.title")), "The newcomer did not get the newcomer Home");

                kit.rt.knobs.requireAffiliation = true;
                ServerPlayer drifter = kit.outsider();
                drifter.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(net.minecraft.world.item.Items.DIRT, 4));
                opensHome(kit, drifter, at, "a drifter holding a block");
                BlockPos plain = kit.pos(-8, 0);
                kit.level.setBlockAndUpdate(plain, Blocks.LECTERN.defaultBlockState());
                helper.assertTrue(click(kit, drifter, plain) == InteractionResult.FAIL, "A drifter could use a lectern that is not a terminal");
                // Twice in the same tick opens the menu once: a held button does not flood the player.
                click(kit, drifter, at);
                helper.assertTrue(Terminals.opened(drifter) == 1, "A second click in the same tick opened the menu again");
                // Sneaking with something in hand places against the terminal, as vanilla does.
                drifter.setShiftKeyDown(true);
                helper.assertTrue(Terminals.useBlock(drifter, kit.level, InteractionHand.MAIN_HAND,
                        new BlockHitResult(Vec3.atCenterOf(at), Direction.UP, at, false)) == null, "A sneaking click with a block opened the menu");
            } finally {
                Terminals.remove(spot);
            }
            helper.succeed();
        }
    }

    /** Records native forms, standing in for Floodgate (which a gametest never loads). */
    private static final class Fake implements BedrockForms.Sender {
        final UUID bedrock;
        final List<BedrockForms.Form> forms = new ArrayList<>();

        Fake(UUID bedrock) {
            this.bedrock = bedrock;
        }

        @Override
        public boolean isBedrock(UUID id) {
            // Floodgate's own rule (a zero high half), and the mock player who stands in for one.
            return id.getMostSignificantBits() == 0L || id.equals(bedrock);
        }

        @Override
        public boolean send(UUID id, BedrockForms.Form form, BedrockForms.Handler handler) {
            forms.add(form);
            return true;
        }
    }

    @GameTest(maxTicks = 100)
    public void aTerminalSendsABedrockPlayerTheNativeHome(GameTestHelper helper) {
        try (LandKit kit = new LandKit(helper)) {
            BlockPos at = kit.pos(-6, 2);
            TerminalBook.Spot spot = terminal(kit, at);
            ServerPlayer bedrock = kit.outsider();
            Fake fake = new Fake(bedrock.getUUID());
            boolean knob = kit.rt.knobs.bedrockNativeForms;
            kit.rt.knobs.bedrockNativeForms = true;
            BedrockForms.install(fake);
            BedrockForms.executor(Runnable::run);
            try {
                helper.assertTrue(click(kit, bedrock, at) == InteractionResult.SUCCESS_SERVER, "The terminal did not answer a Bedrock player");
                helper.assertTrue(fake.forms.size() == 1, "The terminal sent " + fake.forms.size() + " native forms, not one");
                helper.assertTrue(fake.forms.get(0).title().equals(Text.get("book.newcomer.title"))
                        && fake.forms.get(0) instanceof BedrockForms.Form.Simple, "The native form is not the newcomer Home: " + fake.forms.get(0));
                helper.assertTrue(fake.isBedrock(new UUID(0L, 42L)), "A Floodgate style id is not taken for Bedrock");
            } finally {
                BedrockForms.install(null);
                BedrockForms.executor(null);
                BedrockForms.forget(bedrock.getUUID());
                kit.rt.knobs.bedrockNativeForms = knob;
                Terminals.remove(spot);
            }
            helper.succeed();
        }
    }

    @GameTest(maxTicks = 100)
    public void aTerminalWorksInsideTheSpawnWhereALecternIsRefused(GameTestHelper helper) {
        try (LandKit kit = new LandKit(helper)) {
            ServerPlayer lord = kit.faction("TSpawn");
            kit.keep(lord);
            var org = kit.rt.state.org(kit.orgOf(lord)).orElseThrow();
            boolean rules = kit.rt.knobs.spawnRulesEnabled;
            BlockPos at = kit.pos(8, 2);
            BlockPos plain = kit.pos(8, -2);
            TerminalBook.Spot spot = terminal(kit, at);
            kit.level.setBlockAndUpdate(plain, Blocks.LECTERN.defaultBlockState());
            org.adminKingdom = true;
            kit.rt.knobs.spawnRulesEnabled = true;
            SpawnRules.refresh();
            try {
                helper.assertTrue(SpawnRules.inSpawn(kit.level, at), "The terminal is not inside the spawn");
                ServerPlayer visitor = kit.outsider();
                helper.assertTrue(click(kit, visitor, plain) == InteractionResult.FAIL, "A plain lectern was usable in the spawn");
                opensHome(kit, visitor, at, "a visitor in the spawn");
                kit.rt.knobs.requireAffiliation = true;
                ServerPlayer drifter = kit.outsider();
                opensHome(kit, drifter, at, "a drifter in the spawn");
            } finally {
                org.adminKingdom = false;
                kit.rt.knobs.spawnRulesEnabled = rules;
                SpawnRules.refresh();
                Terminals.remove(spot);
            }
            helper.succeed();
        }
    }

    @GameTest(maxTicks = 100)
    public void theNexusOpensTheMenuForItsOwnMembersOnly(GameTestHelper helper) {
        try (LandKit kit = new LandKit(helper)) {
            ServerPlayer lord = kit.faction("TNex");
            kit.keep(lord);
            BlockPos nexus = kit.nexusPos();
            opensHome(kit, lord, nexus, "the Lord at their Nexus");
            ServerPlayer stranger = kit.outsider();
            Terminals.forget(stranger.getUUID());
            click(kit, stranger, nexus);
            helper.assertTrue(Terminals.opened(stranger) == 0, "A stranger opened the menu at someone else's Nexus");
            ServerPlayer rival = kit.faction("TNexR");
            Terminals.forget(rival.getUUID());
            click(kit, rival, nexus);
            helper.assertTrue(Terminals.opened(rival) == 0, "Another kingdom's Lord opened the menu at this Nexus");
            // A compass in hand is left to vanilla, which points it at the lodestone.
            lord.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(net.minecraft.world.item.Items.COMPASS));
            Terminals.forget(lord.getUUID());
            helper.assertTrue(Terminals.useBlock(lord, kit.level, InteractionHand.MAIN_HAND,
                    new BlockHitResult(Vec3.atCenterOf(nexus), Direction.UP, nexus, false)) == null, "A compass click opened the menu");
            helper.succeed();
        }
    }

    @GameTest(maxTicks = 100)
    public void aNewKeepGetsALecternTerminalBesideItsNexusThatHoldsNoChronicle(GameTestHelper helper) {
        TerminalBook.Spot placed;
        try (LandKit kit = new LandKit(helper)) {
            ServerPlayer lord = kit.faction("TAuto");
            kit.keep(lord);
            Core core = kit.rt.state.coresOf(kit.orgOf(lord)).get(0);
            List<TerminalBook.Terminal> mine = Terminals.book().ofCore(core.id.value().toString());
            helper.assertTrue(mine.size() == 1, "The keep has " + mine.size() + " terminals, not one");
            placed = mine.get(0).spot();
            BlockPos at = new BlockPos(placed.x(), placed.y(), placed.z());
            BlockPos nexus = kit.nexusPos();
            helper.assertTrue(kit.level.getBlockState(at).is(Blocks.LECTERN), "No lectern stands at the keep's terminal");
            helper.assertTrue(at.distManhattan(nexus) == 1 && (at.getX() >> 4) == (nexus.getX() >> 4) && (at.getZ() >> 4) == (nexus.getZ() >> 4),
                    "The lectern is not beside the Nexus in its chunk: " + at + " and " + nexus);
            Chronicles.refresh(kit.server, kit.rt);
            helper.assertTrue(!(kit.level.getBlockEntity(at) instanceof LecternBlockEntity le) || !le.hasBook(),
                    "The Chronicle was put into the terminal lectern");
            opensHome(kit, lord, at, "the Lord at the keep lectern");

            // A keep from before terminals has none: the operator's "keeps" command gives it its lectern back.
            Terminals.dropKeep(kit.server, core.id, true);
            helper.assertTrue(Terminals.book().ofCore(core.id.value().toString()).isEmpty() && !kit.level.getBlockState(at).is(Blocks.LECTERN),
                    "Dropping the keep's terminal left it behind");
            kit.server.getCommands().performPrefixedCommand(kit.server.createCommandSourceStack(), "charter admin terminal keeps");
            mine = Terminals.book().ofCore(core.id.value().toString());
            helper.assertTrue(mine.size() == 1 && kit.level.getBlockState(new BlockPos(mine.get(0).spot().x(), mine.get(0).spot().y(),
                    mine.get(0).spot().z())).is(Blocks.LECTERN), "terminal keeps did not give the keep its lectern back");
            placed = mine.get(0).spot();
        }
        // The keep is gone once the kit closes: its terminal is forgotten.
        helper.assertTrue(Terminals.book().at(placed) == null, "A lost keep's terminal is still registered");
        helper.succeed();
    }

    @GameTest(maxTicks = 100)
    public void theOperatorCommandsPlaceListAndRemoveTerminals(GameTestHelper helper) throws Exception {
        try (LandKit kit = new LandKit(helper)) {
            ServerPlayer op = kit.outsider();
            kit.server.getPlayerList().op(op.nameAndId(), java.util.Optional.of(
                    net.minecraft.server.permissions.LevelBasedPermissionSet.GAMEMASTER), java.util.Optional.empty());
            BlockPos at = kit.pos(-14, 0);
            op.teleportTo(at.getX() + 0.5, at.getY(), at.getZ() + 0.5);
            kit.server.getCommands().performPrefixedCommand(op.createCommandSourceStack(), "charter admin terminal here");
            TerminalBook.Spot spot = Terminals.spot(kit.level, at);
            try {
                helper.assertTrue(kit.level.getBlockState(at).is(Blocks.LECTERN), "terminal here placed no lectern");
                helper.assertTrue(Terminals.book().at(spot) != null, "terminal here did not register the lectern");

                // The registry round trips through its file.
                Path dir = Files.createTempDirectory("charter-terminals-gametest");
                Terminals.book().save(dir, w -> { throw new AssertionError(w); });
                TerminalBook back = new TerminalBook();
                back.load(dir, w -> { throw new AssertionError(w); });
                helper.assertTrue(back.all().equals(Terminals.book().all()), "The terminals did not round trip through terminals.json");

                op.teleportTo(at.getX() + 0.5, at.getY() + 1, at.getZ() + 0.5);
                kit.server.getCommands().performPrefixedCommand(op.createCommandSourceStack(), "charter admin terminal remove");
                helper.assertTrue(Terminals.book().at(spot) == null, "terminal remove left the terminal registered");
                helper.assertTrue(kit.level.getBlockState(at).is(Blocks.LECTERN), "terminal remove took the block");
            } finally {
                Terminals.remove(spot);
                kit.server.getPlayerList().deop(op.nameAndId());
            }
            helper.succeed();
        }
    }
}
