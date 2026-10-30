package pocketdungeons;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Random;

/**
 * {@code /dungeon admin wild ...}, the operator side of wild mode. Phase 1 has {@code place}, {@code list}, {@code tp}
 * and {@code remove}; the run and the sweep add {@code ready}, {@code reset} and {@code collapse} later. Only registered
 * when the server runs wild mode.
 */
final class WildCommands {

    private static final Random RANDOM = new Random();

    private WildCommands() {}

    static void register(LiteralArgumentBuilder<CommandSourceStack> admin) {
        admin.then(Commands.literal("wild")
                .then(Commands.literal("place")
                        .executes(ctx -> place(ctx.getSource(), 0, null))
                        .then(Commands.argument("level", IntegerArgumentType.integer(1, 1000))
                                .executes(ctx -> place(ctx.getSource(),
                                        IntegerArgumentType.getInteger(ctx, "level"), null))
                                .then(Commands.argument("offering", StringArgumentType.word())
                                        .executes(ctx -> place(ctx.getSource(),
                                                IntegerArgumentType.getInteger(ctx, "level"),
                                                StringArgumentType.getString(ctx, "offering"))))))
                .then(Commands.literal("list").executes(ctx -> list(ctx.getSource())))
                .then(Commands.literal("tp")
                        .then(Commands.argument("id", StringArgumentType.word())
                                .executes(ctx -> tp(ctx.getSource(), StringArgumentType.getString(ctx, "id")))))
                .then(Commands.literal("remove")
                        .then(Commands.argument("id", StringArgumentType.word())
                                .executes(ctx -> remove(ctx.getSource(),
                                        StringArgumentType.getString(ctx, "id"))))));
    }

    /** Places a lodestone three blocks in front of the caller. {@code levelOverride} 0 means by distance from spawn. */
    private static int place(CommandSourceStack source, int levelOverride, String offeringId) {
        WildState state = WildStore.state();
        ServerPlayer player = source.getPlayer();
        if (state == null || player == null) {
            return fail(source, "Wild mode is off, or there is no player.");
        }
        if (!(player.level() instanceof ServerLevel level) || DungeonWorldRules.inDungeon(level)) {
            return fail(source, "Stand in the overworld.");
        }
        BlockPos pos = player.blockPosition().relative(player.getDirection(), 3);
        double distance = Math.sqrt(pos.distSqr(source.getServer().overworld().getRespawnData().pos()));
        WildDungeons.Offering offering = offeringId != null ? WildDungeons.byId(offeringId)
                : WildDungeons.pick(WildDungeons.all(), distance, RANDOM);
        if (offering == null) {
            return fail(source, "No wild dungeon offering fits. Loaded: " + WildDungeons.all().size() + ".");
        }
        int lodestoneLevel = levelOverride > 0 ? levelOverride
                : Math.min(offering.maxLevel(), 1 + (int) (distance / 400.0));
        long now = WildClock.now();
        long lifespan = PocketDungeonsConfig.wildLifespanDays() * 86400L;
        WildState.Lodestone lodestone = new WildState.Lodestone(state.nextId(), WildLodestones.dimensionKey(level),
                pos.getX(), pos.getY(), pos.getZ(), offering.id(), lodestoneLevel, now, now + lifespan);
        WildLodestones.stamp(level, pos);
        state.add(lodestone);
        WildStore.save();
        source.sendSuccess(() -> Component.literal("Placed " + lodestone.id + " (" + offering.name() + ", level "
                + lodestoneLevel + ") at " + pos.toShortString()), true);
        return 1;
    }

    private static int list(CommandSourceStack source) {
        WildState state = WildStore.state();
        if (state == null) {
            return fail(source, "Wild mode is off.");
        }
        long now = WildClock.now();
        List<WildState.Lodestone> all = new ArrayList<>(state.all());
        all.sort(Comparator.comparing(l -> l.id));
        source.sendSuccess(() -> Component.literal(all.size() + " wild lodestone(s)"), false);
        for (WildState.Lodestone l : all) {
            StringBuilder line = new StringBuilder(l.id).append(" ").append(l.x).append(",").append(l.y).append(",")
                    .append(l.z).append(" ").append(l.dungeon).append(" L").append(l.level).append(" ")
                    .append(l.phase);
            if (l.phase == WildState.Phase.CLEARED) {
                line.append(" resets in ").append(WildText.duration(l.resetAt - now));
            }
            line.append(" collapses in ").append(WildText.duration(l.expiresAt - now));
            if (!l.lostAndFound.isEmpty()) {
                line.append(" lost and found: ").append(l.lostAndFound.size()).append(" chest(s)");
            }
            source.sendSuccess(() -> Component.literal(line.toString()), false);
        }
        return all.size();
    }

    private static int tp(CommandSourceStack source, String id) {
        WildState state = WildStore.state();
        ServerPlayer player = source.getPlayer();
        MinecraftServer server = source.getServer();
        WildState.Lodestone l = state == null ? null : state.get(id);
        if (l == null || player == null) {
            return fail(source, "No wild lodestone " + id + ".");
        }
        ResourceKey<Level> dimension = ResourceKey.create(Registries.DIMENSION, Identifier.parse(l.dimension));
        // Beside the entrance, on the open side of the ruin (the floor ring is 5 by 5, so 3 out is clear ground).
        Instances.teleport(server, player, dimension, new Vec3(l.x + 0.5, l.y, l.z + 3.5), 180.0F, 0.0F);
        return 1;
    }

    private static int remove(CommandSourceStack source, String id) {
        WildState state = WildStore.state();
        WildState.Lodestone l = state == null ? null : state.get(id);
        if (l == null) {
            return fail(source, "No wild lodestone " + id + ".");
        }
        if (WildRun.insideCount(id) > 0) {
            return fail(source, id + " has players inside.");
        }
        ResourceKey<Level> dimension = ResourceKey.create(Registries.DIMENSION, Identifier.parse(l.dimension));
        ServerLevel level = source.getServer().getLevel(dimension);
        if (level != null) {
            WildLodestones.rubble(level, new BlockPos(l.x, l.y, l.z));
        }
        state.remove(id);
        WildStore.save();
        source.sendSuccess(() -> Component.literal("Removed " + id), true);
        return 1;
    }

    private static int fail(CommandSourceStack source, String message) {
        source.sendFailure(Component.literal(message).withStyle(ChatFormatting.RED));
        return 0;
    }
}
