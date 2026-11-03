package fortune.mc;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import fortune.core.Machine;
import fortune.core.PlayerRecord;
import fortune.core.SpinStats;
import net.fabricmc.fabric.api.permission.v1.PermissionContextOwner;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.PermissionLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.function.Predicate;

/**
 * {@code /spin} (alias {@code /daily}) and {@code /fortune}. The admin branch needs the permission node
 * {@code fortune:admin} through the Fabric permission API (LuckPerms answers it), falling back to operator level 2.
 */
final class FortuneCommands {

    static final Identifier ADMIN = Identifier.fromNamespaceAndPath("fortune", "admin");

    private FortuneCommands() {
    }

    static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("spin").executes(FortuneCommands::open));
        dispatcher.register(Commands.literal("daily").executes(FortuneCommands::open));
        dispatcher.register(Commands.literal("fortune")
                .executes(FortuneCommands::help)
                .then(Commands.literal("odds").executes(FortuneCommands::odds))
                .then(Commands.literal("later").executes(ctx -> {
                    ctx.getSource().sendSuccess(() -> Messages.plain("The reels will wait. /spin any time today."), false);
                    return 1;
                }))
                .then(Commands.literal("admin").requires(admin())
                        .then(Commands.literal("machine")
                                .then(Commands.literal("here").executes(FortuneCommands::machineHere))
                                .then(Commands.literal("add").executes(FortuneCommands::machineAdd))
                                .then(Commands.literal("remove").executes(FortuneCommands::machineRemove))
                                .then(Commands.literal("list").executes(FortuneCommands::machineList))
                                .then(Commands.literal("keeps").executes(ctx -> {
                                    // TODO: placing a machine beside every keep's Nexus needs Charter's areas API, and
                                    // Fortune imports nothing from Charter. Not built.
                                    ctx.getSource().sendFailure(Messages.bad("Not built yet. Stand beside each keep's Nexus and use /fortune admin machine here."));
                                    return 0;
                                })))
                        .then(Commands.literal("reset")
                                .then(Commands.argument("player", StringArgumentType.word())
                                        .suggests((ctx, b) -> SharedSuggestionProvider.suggest(
                                                ctx.getSource().getServer().getPlayerList().getPlayers().stream()
                                                        .map(ServerPlayer::getPlainTextName), b))
                                        .executes(FortuneCommands::reset)))
                        .then(Commands.literal("stats").executes(FortuneCommands::stats))
                        .then(Commands.literal("reload").executes(FortuneCommands::reload))));
    }

    /** {@code fortune:admin} through the Fabric permission API, or operator level 2 where that cannot be asked. */
    private static Predicate<CommandSourceStack> admin() {
        Predicate<CommandSourceStack> op = Commands.hasPermission(Commands.LEVEL_GAMEMASTERS);
        return source -> {
            try {
                return ((PermissionContextOwner) (Object) source).checkPermission(ADMIN, PermissionLevel.GAMEMASTERS);
            } catch (ClassCastException e) {
                return op.test(source);
            }
        };
    }

    private static FortuneService service(CommandContext<CommandSourceStack> ctx) {
        FortuneService service = Fortune.service();
        if (service == null) ctx.getSource().sendFailure(Messages.bad("Fantasy's Fortune has not started yet."));
        return service;
    }

    private static int open(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        FortuneService service = service(ctx);
        if (service == null) return 0;
        service.openFor(player, null);
        return 1;
    }

    private static int help(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        source.sendSuccess(() -> Messages.info("Fantasy's Fortune"), false);
        source.sendSuccess(() -> Messages.plain("/spin or /daily: open the machine, one pull a day"), false);
        source.sendSuccess(() -> Messages.plain("/fortune odds: the paytable"), false);
        return 1;
    }

    private static int odds(CommandContext<CommandSourceStack> ctx) {
        FortuneService service = service(ctx);
        if (service == null) return 0;
        ServerPlayer player = ctx.getSource().getPlayer();
        PlayerRecord record = player == null ? null : service.record(player.getUUID());
        for (Component line : Odds.lines(service, record)) ctx.getSource().sendSuccess(() -> line, false);
        return 1;
    }

    // ---- machines ----

    private static int machineHere(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        FortuneService service = service(ctx);
        if (service == null) return 0;
        BlockPos at = player.blockPosition();
        if (!player.level().getBlockState(at).isAir() || service.machines().isMachine(player.level(), at)) {
            ctx.getSource().sendFailure(Messages.bad("Stand on an empty block: the machine is placed where your feet are."));
            return 0;
        }
        player.level().setBlockAndUpdate(at, Blocks.GOLD_BLOCK.defaultBlockState());
        service.machines().add(player.level(), at);
        ctx.getSource().sendSuccess(() -> Messages.good("Placed a Fortune Machine at " + text(at) + ". Step back and right-click it."), true);
        return 1;
    }

    private static int machineAdd(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        FortuneService service = service(ctx);
        if (service == null) return 0;
        BlockPos at = lookedAt(player);
        if (at == null) {
            ctx.getSource().sendFailure(Messages.bad("Look at a block within 8 blocks."));
            return 0;
        }
        if (!service.machines().add(player.level(), at)) {
            ctx.getSource().sendFailure(Messages.bad("That block is already a Fortune Machine."));
            return 0;
        }
        ctx.getSource().sendSuccess(() -> Messages.good("The block at " + text(at) + " is now a Fortune Machine."), true);
        return 1;
    }

    private static int machineRemove(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        FortuneService service = service(ctx);
        if (service == null) return 0;
        BlockPos looked = lookedAt(player);
        BlockPos under = player.blockPosition().below();
        BlockPos at = looked != null && service.machines().isMachine(player.level(), looked) ? looked : under;
        if (!service.machines().remove(player.level(), at)) {
            ctx.getSource().sendFailure(Messages.bad("Look at a Fortune Machine, or stand on one."));
            return 0;
        }
        ctx.getSource().sendSuccess(() -> Messages.good("Removed the Fortune Machine at " + text(at) + ". The block stays."), true);
        return 1;
    }

    private static int machineList(CommandContext<CommandSourceStack> ctx) {
        FortuneService service = service(ctx);
        if (service == null) return 0;
        List<FortuneStore.MachineSpot> spots = service.machines().all();
        if (spots.isEmpty()) {
            ctx.getSource().sendSuccess(() -> Messages.plain("No Fortune Machines yet. Stand somewhere and use /fortune admin machine here."), false);
            return 1;
        }
        ctx.getSource().sendSuccess(() -> Messages.info(spots.size() + " Fortune Machine" + (spots.size() == 1 ? "" : "s") + ":"), false);
        for (FortuneStore.MachineSpot spot : spots) {
            String tp = "/execute in " + spot.dimension + " run tp @s " + spot.x + " " + (spot.y + 1) + " " + spot.z;
            Component line = Component.literal("  " + spot.dimension + " " + spot.x + ", " + spot.y + ", " + spot.z)
                    .withStyle(s -> s.withColor(ChatFormatting.AQUA).withClickEvent(new ClickEvent.RunCommand(tp)));
            ctx.getSource().sendSuccess(() -> line, false);
        }
        return 1;
    }

    private static BlockPos lookedAt(ServerPlayer player) {
        HitResult hit = player.pick(8, 0, false);
        return hit instanceof BlockHitResult block && hit.getType() == HitResult.Type.BLOCK ? block.getBlockPos() : null;
    }

    private static String text(BlockPos pos) {
        return pos.getX() + ", " + pos.getY() + ", " + pos.getZ();
    }

    // ---- players and stats ----

    private static int reset(CommandContext<CommandSourceStack> ctx) {
        FortuneService service = service(ctx);
        if (service == null) return 0;
        String name = StringArgumentType.getString(ctx, "player");
        UUID id = null;
        ServerPlayer online = ctx.getSource().getServer().getPlayerList().getPlayer(name);
        if (online != null) {
            id = online.getUUID();
        } else {
            for (Map.Entry<String, PlayerRecord> e : service.store().allPlayers().entrySet()) {
                if (e.getValue().name.equalsIgnoreCase(name)) id = UUID.fromString(e.getKey());
            }
        }
        if (id == null) {
            ctx.getSource().sendFailure(Messages.bad("No Fortune record for " + name + "."));
            return 0;
        }
        if (!service.reset(id)) {
            ctx.getSource().sendFailure(Messages.bad(name + " has not spun today, so there is nothing to give back."));
            return 0;
        }
        ctx.getSource().sendSuccess(() -> Messages.good("Gave " + name + " today's spin back. What they won stays theirs."), true);
        if (online != null) online.sendSystemMessage(Messages.info("Fantasy's Fortune: an operator gave you today's spin back. /spin"));
        return 1;
    }

    private static int stats(CommandContext<CommandSourceStack> ctx) {
        FortuneService service = service(ctx);
        if (service == null) return 0;
        List<SpinStats.Entry> entries = new ArrayList<>();
        for (String raw : service.store().spinLines()) {
            if (raw.isBlank()) continue;
            try {
                JsonObject o = JsonParser.parseString(raw).getAsJsonObject();
                entries.add(new SpinStats.Entry(o.get("day").getAsLong(), o.get("uuid").getAsString(),
                        o.has("streak") ? o.get("streak").getAsInt() : 0,
                        o.has("welcomeBack") && o.get("welcomeBack").getAsBoolean(),
                        o.has("stakeMarks") ? o.get("stakeMarks").getAsLong() : 0));
            } catch (RuntimeException e) {
                // A damaged line is skipped, not fatal.
            }
        }
        SpinStats.Report r = SpinStats.of(entries, service.today());
        CommandSourceStack source = ctx.getSource();
        source.sendSuccess(() -> Messages.info("Fantasy's Fortune: the last 14 days (" + entries.size() + " spins logged)"), false);
        StringBuilder daily = new StringBuilder();
        for (int n : r.spinnersByDay().values()) daily.append(daily.isEmpty() ? "" : " ").append(n);
        source.sendSuccess(() -> Messages.plain("Spinners per day, oldest first: " + daily), false);
        source.sendSuccess(() -> Messages.plain("Back the next day (D1): " + percent(r.d1()) + ". Back the next week (D7): " + percent(r.d7()) + "."), false);
        StringBuilder spread = new StringBuilder();
        r.streakSpread().forEach((k, v) -> spread.append(spread.isEmpty() ? "" : ", ").append(k).append(": ").append(v));
        source.sendSuccess(() -> Messages.plain("Streaks: " + spread + "."), false);
        source.sendSuccess(() -> Messages.plain("Welcome Back spins: " + r.welcomeBacks() + ". Stakes: " + r.stakedSpins()
                + " spins drained " + r.stakeMarks() + " Marks."), false);
        source.sendSuccess(() -> Messages.plain("Kingdom muster counts: not built yet."), false);
        return 1;
    }

    private static String percent(double share) {
        return share < 0 ? "no data" : String.format(Locale.ROOT, "%.0f%%", share * 100);
    }

    private static int reload(CommandContext<CommandSourceStack> ctx) {
        FortuneService service = service(ctx);
        if (service == null) return 0;
        String problem = ConfigFile.problem(service.configDir());
        if (problem != null) {
            ctx.getSource().sendFailure(Messages.bad("fortune.json was refused, the old settings stay: " + problem));
            return 0;
        }
        Machine fresh = ConfigFile.load(service.configDir(), service.machine());
        service.reload(fresh);
        ctx.getSource().sendSuccess(() -> Messages.good("Reloaded fortune.json."), true);
        return 1;
    }
}
