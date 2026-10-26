package charter.mc.chronicle;

import charter.id.OrgId;
import charter.id.PlayerId;
import charter.mc.CharterRuntime;
import charter.mc.CharterServer;
import charter.mc.Text;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.context.CommandContext;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LecternBlock;
import net.minecraft.world.level.block.entity.LecternBlockEntity;

import java.util.List;
import java.util.Optional;

/**
 * Commands of the Chronicle (M13). Merged into the {@code /charter} tree:
 *
 * <pre>
 *   /charter chronicle [volume n]           your faction's Chronicle as a book; older volumes on request
 *   /charter chronicle realm [volume n]     the server's Chronicle as a book
 *   /charter admin realm-chronicle here     place a lectern holding the Realm Chronicle where you stand (permission level 2)
 *   /charter admin realm-chronicle remove   stop a Realm lectern within 3 blocks keeping the Chronicle (the lectern stays)
 *   /charter admin realm-chronicle list     every Realm lectern
 * </pre>
 */
final class ChronicleCommands {

    private ChronicleCommands() {
    }

    static void register() {
        CommandRegistrationCallback.EVENT.register((dispatcher, registry, environment) -> dispatcher.register(
                Commands.literal("charter")
                        .then(Commands.literal("chronicle")
                                .executes(ctx -> give(ctx, false, 1))
                                .then(volume(false))
                                .then(Commands.literal("realm")
                                        .executes(ctx -> give(ctx, true, 1))
                                        .then(volume(true))))
                        .then(Commands.literal("admin")
                                .requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                                .then(Commands.literal("realm-chronicle")
                                        .then(Commands.literal("here").executes(ChronicleCommands::here))
                                        .then(Commands.literal("remove").executes(ChronicleCommands::remove))
                                        .then(Commands.literal("list").executes(ChronicleCommands::list))))));
    }

    private static com.mojang.brigadier.builder.LiteralArgumentBuilder<CommandSourceStack> volume(boolean realm) {
        return Commands.literal("volume").then(Commands.argument("n", IntegerArgumentType.integer(1, 10_000))
                .executes(ctx -> give(ctx, realm, IntegerArgumentType.getInteger(ctx, "n"))));
    }

    private static CharterRuntime rt() {
        return CharterServer.runtime();
    }

    private static int fail(CommandContext<CommandSourceStack> ctx, String text) {
        ctx.getSource().sendFailure(Component.literal(text).withStyle(ChatFormatting.RED));
        return 0;
    }

    private static int give(CommandContext<CommandSourceStack> ctx, boolean realm, int volume) {
        ServerPlayer player = ctx.getSource().getPlayer();
        if (player == null) return fail(ctx, Text.get("chronicle.players_only"));
        CharterRuntime rt = rt();
        boolean admin = rt.knobs.chronicleShowAdmin;
        ChronicleBooks.Volume book;
        String scope;
        if (realm) {
            book = ChronicleBooks.realm(rt.state, Chronicles.templates(), admin, volume);
            scope = "realm";
        } else {
            Optional<OrgId> org = rt.state.membership(new PlayerId(player.getUUID())).map(m -> m.org());
            if (org.isEmpty()) return fail(ctx, Text.get("chronicle.no_faction"));
            book = ChronicleBooks.faction(rt.state, Chronicles.templates(), admin, org.get(), volume);
            scope = "org:" + org.get().value();
        }
        ItemStack stack = Chronicles.book(book, scope);
        if (!player.getInventory().add(stack)) player.drop(stack, false);
        ctx.getSource().sendSuccess(() -> Component.literal(Text.get("chronicle.given", "title", book.title(),
                "pages", book.pages().size())).withStyle(ChatFormatting.GREEN), false);
        return 1;
    }

    private static int here(CommandContext<CommandSourceStack> ctx) {
        ServerLevel level = ctx.getSource().getLevel();
        BlockPos pos = BlockPos.containing(ctx.getSource().getPosition());
        if (!level.getBlockState(pos).isAir() && !level.getBlockState(pos).canBeReplaced()) {
            return fail(ctx, Text.get("chronicle.realm_blocked"));
        }
        ServerPlayer player = ctx.getSource().getPlayer();
        Direction facing = player == null ? Direction.NORTH : player.getDirection().getOpposite();
        level.setBlock(pos, Blocks.LECTERN.defaultBlockState().setValue(LecternBlock.FACING, facing), 3);
        RealmLecterns.of(rt().state).add(spot(level, pos));
        Chronicles.refresh(ctx.getSource().getServer(), rt());
        rt().siegeLog.admin(rt().clock.now(), ctx.getSource().getTextName(), ctx.getInput());
        ctx.getSource().sendSuccess(() -> Component.literal(Text.get("chronicle.realm_placed", "x", pos.getX(), "y", pos.getY(), "z", pos.getZ()))
                .withStyle(ChatFormatting.GREEN), true);
        return 1;
    }

    private static int remove(CommandContext<CommandSourceStack> ctx) {
        ServerLevel level = ctx.getSource().getLevel();
        BlockPos here = BlockPos.containing(ctx.getSource().getPosition());
        RealmLecterns realms = RealmLecterns.of(rt().state);
        for (RealmLecterns.Spot spot : realms.all()) {
            BlockPos pos = new BlockPos(spot.x(), spot.y(), spot.z());
            if (!spot.dimension().equals(level.dimension().identifier().toString()) || pos.distSqr(here) > 9) continue;
            realms.remove(spot);
            // The book goes with the registration; the lectern stays.
            if (level.getBlockEntity(pos) instanceof LecternBlockEntity lectern && lectern.hasBook()) {
                lectern.setBook(ItemStack.EMPTY);
                level.setBlock(pos, level.getBlockState(pos).setValue(LecternBlock.HAS_BOOK, false), 3);
            }
            rt().siegeLog.admin(rt().clock.now(), ctx.getSource().getTextName(), ctx.getInput());
            ctx.getSource().sendSuccess(() -> Component.literal(Text.get("chronicle.realm_removed")).withStyle(ChatFormatting.GREEN), true);
            return 1;
        }
        return fail(ctx, Text.get("chronicle.realm_none_near"));
    }

    private static int list(CommandContext<CommandSourceStack> ctx) {
        List<RealmLecterns.Spot> spots = RealmLecterns.of(rt().state).all();
        if (spots.isEmpty()) {
            ctx.getSource().sendSuccess(() -> Component.literal(Text.get("chronicle.realm_empty")), false);
            return 0;
        }
        for (RealmLecterns.Spot s : spots) {
            ctx.getSource().sendSuccess(() -> Component.literal(Text.get("chronicle.realm_line", "dimension", s.dimension(),
                    "x", s.x(), "y", s.y(), "z", s.z())), false);
        }
        return spots.size();
    }

    private static RealmLecterns.Spot spot(ServerLevel level, BlockPos pos) {
        return new RealmLecterns.Spot(level.dimension().identifier().toString(), pos.getX(), pos.getY(), pos.getZ());
    }
}
