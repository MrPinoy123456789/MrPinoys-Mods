package pocketdungeons;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.LongArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.RandomizableContainer;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * {@code /dungeon} to go in, {@code /dungeon exit} to come out, {@code invite}
 * and {@code join} to bring a party along, plus the operator subtree from spec
 * section 10.
 *
 * <p>{@code admin build} stamps an instance nobody owns. That exists so the
 * geometry can be built and inspected from the server console without a client
 * attached, which is the only way to test the stamper headlessly.
 */
final class DungeonCommands {

    private DungeonCommands() {}

    /** Registers player-facing dungeon commands and the operator diagnostics subtree. */
    static void register() {
        CommandRegistrationCallback.EVENT.register((dispatcher, registry, environment) -> {
            dispatcher.register(Commands.literal("dungeon")
                    .executes(ctx -> enter(ctx.getSource().getPlayerOrException()))

                    .then(Commands.literal("exit")
                            .executes(ctx -> exit(ctx.getSource().getPlayerOrException())))

                    .then(Commands.literal("key")
                            .executes(ctx -> mintKey(ctx.getSource().getPlayerOrException())))

                    .then(Commands.literal("choose")
                            .then(Commands.argument("step", IntegerArgumentType.integer(1, 3))
                                    .executes(ctx -> choose(ctx.getSource().getPlayerOrException(),
                                            IntegerArgumentType.getInteger(ctx, "step")))))

                    .then(Commands.literal("party")
                            // Literals are matched before arguments, so "kick" and
                            // "kickconfirm" win over a player who happens to be
                            // named either.
                            .then(Commands.literal("kick")
                                    .then(Commands.literal("all")
                                            .executes(ctx -> kickAll(
                                                    ctx.getSource().getPlayerOrException())))
                                    .then(Commands.argument("target", EntityArgument.player())
                                            .executes(ctx -> kick(
                                                    ctx.getSource().getPlayerOrException(),
                                                    EntityArgument.getPlayer(ctx, "target")))))
                            .then(Commands.literal("kickconfirm")
                                    .executes(ctx -> kickConfirm(
                                            ctx.getSource().getPlayerOrException())))
                            .then(Commands.argument("target", EntityArgument.player())
                                    .executes(ctx -> party(ctx.getSource().getPlayerOrException(),
                                            EntityArgument.getPlayer(ctx, "target")))))

                    .then(Commands.literal("invite")
                            .then(Commands.argument("target", EntityArgument.player())
                                    .executes(ctx -> invite(ctx.getSource().getPlayerOrException(),
                                            EntityArgument.getPlayer(ctx, "target")))))

                    .then(Commands.literal("log")
                            .executes(ctx -> log(ctx.getSource(),
                                    ctx.getSource().getPlayerOrException().getUUID(),
                                    ctx.getSource().getPlayerOrException().getName().getString()))
                            .then(Commands.argument("target", EntityArgument.player())
                                    .requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                                    .executes(ctx -> {
                                        ServerPlayer target = EntityArgument.getPlayer(ctx, "target");
                                        return log(ctx.getSource(), target.getUUID(),
                                                target.getName().getString());
                                    })))

                    .then(Commands.literal("join")
                            .then(Commands.argument("leader", EntityArgument.player())
                                    .executes(ctx -> join(ctx.getSource().getPlayerOrException(),
                                            EntityArgument.getPlayer(ctx, "leader")))))

                    .then(Commands.literal("admin")
                            .requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))

                            .then(Commands.literal("list")
                                    .executes(ctx -> list(ctx.getSource())))

                            .then(Commands.literal("build")
                                    .executes(ctx -> build(ctx.getSource(), null, 0, false))
                                    .then(Commands.argument("seed", LongArgumentType.longArg())
                                            .executes(ctx -> build(ctx.getSource(),
                                                    LongArgumentType.getLong(ctx, "seed"), 0, false))
                                            .then(Commands.argument("keystoneLevel",
                                                            IntegerArgumentType.integer(0, 1000))
                                                    .executes(ctx -> build(ctx.getSource(),
                                                            LongArgumentType.getLong(ctx, "seed"),
                                                            IntegerArgumentType.getInteger(
                                                                    ctx, "keystoneLevel"), false))
                                                    .then(Commands.argument("ominous",
                                                                    com.mojang.brigadier.arguments
                                                                            .BoolArgumentType.bool())
                                                            .executes(ctx -> build(ctx.getSource(),
                                                                    LongArgumentType.getLong(ctx, "seed"),
                                                                    IntegerArgumentType.getInteger(
                                                                            ctx, "keystoneLevel"),
                                                                    com.mojang.brigadier.arguments
                                                                            .BoolArgumentType.getBool(
                                                                            ctx, "ominous")))))))

                            .then(Commands.literal("untimed")
                                    .executes(ctx -> untimed(ctx.getSource(), 1, false, null))
                                    .then(Commands.argument("keystoneLevel",
                                                    IntegerArgumentType.integer(1, 1000))
                                            .executes(ctx -> untimed(ctx.getSource(),
                                                    IntegerArgumentType.getInteger(
                                                            ctx, "keystoneLevel"), false, null))
                                            .then(Commands.argument("ominous",
                                                            com.mojang.brigadier.arguments
                                                                    .BoolArgumentType.bool())
                                                    .executes(ctx -> untimed(ctx.getSource(),
                                                            IntegerArgumentType.getInteger(
                                                                    ctx, "keystoneLevel"),
                                                            com.mojang.brigadier.arguments
                                                                    .BoolArgumentType.getBool(
                                                                    ctx, "ominous"), null))
                                                    .then(Commands.argument("theme",
                                                                    com.mojang.brigadier.arguments
                                                                            .StringArgumentType.string())
                                                            .executes(ctx -> untimed(ctx.getSource(),
                                                                    IntegerArgumentType.getInteger(
                                                                            ctx, "keystoneLevel"),
                                                                    com.mojang.brigadier.arguments
                                                                            .BoolArgumentType.getBool(
                                                                            ctx, "ominous"),
                                                                    com.mojang.brigadier.arguments
                                                                            .StringArgumentType.getString(
                                                                            ctx, "theme")))))))

                            .then(Commands.literal("gentemplates")
                                    .executes(ctx -> generateTemplates(ctx.getSource())))

                            .then(Commands.literal("stamptest")
                                    .executes(ctx -> stampTest(ctx.getSource())))

                            .then(Commands.literal("cellreport")
                                    .then(Commands.argument("slot", IntegerArgumentType.integer(0))
                                            .executes(ctx -> cellReport(ctx.getSource(),
                                                    IntegerArgumentType.getInteger(ctx, "slot")))))

                            .then(Commands.literal("purge")
                                    .then(Commands.argument("slot", IntegerArgumentType.integer(0))
                                            .executes(ctx -> purge(ctx.getSource(),
                                                    IntegerArgumentType.getInteger(ctx, "slot")))))

                            .then(Commands.literal("coverage")
                                    .executes(ctx -> coverage(ctx.getSource())))

                            .then(Commands.literal("plan")
                                    .then(Commands.argument("seed", LongArgumentType.longArg())
                                            .executes(ctx -> plan(ctx.getSource(),
                                                    LongArgumentType.getLong(ctx, "seed")))))

                            .then(Commands.literal("plansurvey")
                                    .then(Commands.argument("count", IntegerArgumentType.integer(1, 1000))
                                            .executes(ctx -> planSurvey(ctx.getSource(),
                                                    IntegerArgumentType.getInteger(ctx, "count")))))

                            .then(Commands.literal("exportdata")
                                    .executes(ctx -> DatapackExporter.export(ctx.getSource())))

                            .then(manifestBranch())));
        });
    }

    private static LiteralArgumentBuilder<CommandSourceStack> manifestBranch() {
        return Commands.literal("manifest")
                .then(Commands.literal("reload")
                        .executes(ctx -> manifestReload(ctx.getSource())))
                .then(Commands.literal("list")
                        .executes(ctx -> manifestList(ctx.getSource())));
    }

    /**
     * {@code /dungeon}: open a fresh run, re-enter an owned one for free (T5), or
     * -- with no keystone at all -- fail with the usual message. Ominous is no
     * longer requested here (U8 Stage 6): it rides entirely on the spent
     * keystone's own affix.
     */
    private static int enter(ServerPlayer player) {
        if (player.level().dimension().equals(PocketDungeonsMod.DUNGEON_LEVEL)) {
            player.sendSystemMessage(Component.literal("You are already inside a dungeon.")
                    .withStyle(ChatFormatting.RED));
            return 0;
        }
        return Instances.enterWithKeystone(player) ? 1 : 0;
    }

    /**
     * {@code /dungeon choose <1|2|3>}: settle a completed run's door offer.
     * Player-only, not op-gated -- see {@link Instances#chooseOffer}, which does
     * the real validation against {@link DungeonLog} rather than trusting the
     * click that sent the player here.
     */
    private static int choose(ServerPlayer player, int step) {
        return Instances.chooseOffer(player, step) ? 1 : 0;
    }

    /**
     * {@code /dungeon admin untimed [level] [ominous]}: a dungeon with no clock.
     *
     * <p>The timer is what ends an ordinary run, so this one never expires and has
     * to be closed with {@code /dungeon admin purge}. That is the point -- an
     * operator walking a layout does not want it dissolving underneath them -- and
     * it is why both ends of its life are logged to the console and why
     * {@code admin list} marks it.
     */
    private static int untimed(CommandSourceStack source, int keystoneLevel, boolean ominous,
                                 String theme) {
        ServerPlayer player;
        try {
            player = source.getPlayerOrException();
        } catch (com.mojang.brigadier.exceptions.CommandSyntaxException e) {
            source.sendFailure(Component.literal(
                    "admin untimed opens a dungeon around you, so it needs a player."));
            return 0;
        }
        if (!Instances.enterUntimed(player, keystoneLevel, ominous, theme)) {
            return 0;
        }
        source.sendSuccess(() -> Component.literal(
                "Untimed dungeon opened. It will not expire -- close it with "
                        + "/dungeon admin purge <slot>.")
                .withStyle(ChatFormatting.YELLOW), true);
        return 1;
    }

    /**
     * {@code /dungeon key}: the first keystone, free and unlimited, but only for a
     * player holding none and with none pending.
     *
     * <p>That cannot dead-end a player and cannot be farmed -- a level 1 key is
     * worth less than the walk it takes to spend it -- which is the whole reason
     * it does not need a cooldown, a cost, or a permission node.
     */
    private static int mintKey(ServerPlayer player) {
        DungeonLog log = DungeonLog.forServer(player.level().getServer());
        DungeonLog.Entry entry = log.get(player.getUUID());
        int level = entry.keystoneLevel();

        if (level > 0 && Keystone.findHeld(player) != null) {
            player.sendSystemMessage(Component.literal(
                    "You already have a keystone [" + level + "]. Spend it before asking "
                            + "for another.")
                    .withStyle(ChatFormatting.RED));
            return 0;
        }

        // A player who owns a level but has lost the remote gets a replacement at
        // their real level, not a fresh level 1. The item is a view onto server
        // state, so replacing it costs nothing and losing it in lava is no longer
        // a way to lose a keystone.
        if (level > 0) {
            Payout.deliver(player, Keystone.mint(level, Keystone.Affix.parse(entry.keystoneAffix())));
            player.sendSystemMessage(Component.literal(
                    "A replacement keystone [" + level + "]. Your progress was never on the item.")
                    .withStyle(ChatFormatting.AQUA));
            return 1;
        }

        log.setKeystone(player.getUUID(), 1, Keystone.Affix.NONE);
        Payout.deliver(player, Keystone.mint(1));
        player.sendSystemMessage(Component.literal(
                "Keystone [1]. Right-click a lodestone with it, or run /dungeon.")
                .withStyle(ChatFormatting.AQUA));
        return 1;
    }

    private static int exit(ServerPlayer player) {
        // A command exit is a retreat, not a completion -- it does not pay.
        Instances.exit(player, Instances.ExitReason.COMMAND);
        return 1;
    }

    /**
     * {@code /dungeon log}. Reads the persistent history, not the live instance,
     * so it answers the same before and after a run and survives a restart.
     *
     * <p>U8 Stage 0 deletes the streak -- the keystone level is the ladder now.
     * This prints runs completed, the best keystone level, and the longest
     * dungeon cleared.
     */
    private static int log(CommandSourceStack source, java.util.UUID player, String name) {
        DungeonLog.Entry entry = DungeonLog.forServer(source.getServer()).get(player);
        if (entry.runsCompleted() == 0) {
            source.sendSuccess(() -> Component.literal(
                    name + " has not finished a dungeon yet.").withStyle(ChatFormatting.GRAY), false);
            return 0;
        }
        source.sendSuccess(() -> Component.literal(
                name + ": " + entry.runsCompleted() + " run(s) completed, best keystone ["
                        + entry.bestKeystoneLevel() + "], longest dungeon cleared "
                        + entry.bestPathLength() + " rooms deep")
                .withStyle(ChatFormatting.GOLD), false);
        return entry.runsCompleted();
    }

    private static int party(ServerPlayer leader, ServerPlayer target) {
        Instances.party(leader, target);
        return 1;
    }

    private static int kick(ServerPlayer leader, ServerPlayer target) {
        Instances.stageKick(leader, target.getUUID(), target.getName().getString());
        return 1;
    }

    private static int kickAll(ServerPlayer leader) {
        Instances.stageKick(leader, null, null);
        return 1;
    }

    private static int kickConfirm(ServerPlayer leader) {
        Instances.confirmKick(leader);
        return 1;
    }

    private static int invite(ServerPlayer inviter, ServerPlayer target) {
        Instances.invite(inviter, target);
        return 1;
    }

    private static int join(ServerPlayer player, ServerPlayer leader) {
        if (player.level().dimension().equals(PocketDungeonsMod.DUNGEON_LEVEL)) {
            player.sendSystemMessage(Component.literal("You are already inside a dungeon.")
                    .withStyle(ChatFormatting.RED));
            return 0;
        }
        Instances.join(player, leader);
        return 1;
    }

    // ---- admin --------------------------------------------------------------

    private static int list(CommandSourceStack source) {
        List<String> lines = Instances.adminList(source.getServer());
        if (lines.isEmpty()) {
            source.sendSuccess(() -> Component.literal("No dungeon instances are open."), false);
            return 0;
        }
        for (String line : lines) {
            source.sendSuccess(() -> Component.literal(line), false);
        }
        return lines.size();
    }

    private static int generateTemplates(CommandSourceStack source) {
        if (!FabricLoader.getInstance().isDevelopmentEnvironment()) {
            source.sendFailure(Component.literal(
                    "Room template generation is a development-only command and cannot be used on a production server."));
            return 0;
        }
        ServerLevel level = source.getServer().getLevel(PocketDungeonsMod.DUNGEON_LEVEL);
        if (level == null) {
            source.sendFailure(Component.literal("The dungeon dimension is not loaded."));
            return 0;
        }
        RoomTemplateGenerator.generate(level);
        source.sendSuccess(() -> Component.literal(
                "Queued room template generation; files will be written next tick."), true);
        return 1;
    }

    /**
     * Builds an unowned instance, optionally from a given seed.
     *
     * <p>The seed is reported either way, because a bad stamp that cannot be
     * reproduced is a bug report nobody can act on. It stays operator-only:
     * letting players pick a seed would let them scout a layout, find the
     * high-tier chest, and re-enter the same seed until their inventory filled up,
     * which is exactly the unbounded-per-unit-time shape DESIGN.md rules out.
     */
    private static int build(CommandSourceStack source, Long seed, int keystoneLevel,
                             boolean ominous) {
        MinecraftServer server = source.getServer();
        int slot = Instances.adminBuild(server, seed, keystoneLevel, ominous);
        if (slot < 0) {
            source.sendFailure(Component.literal(
                    "Could not build: the dungeon dimension is missing or stamping failed."));
            return 0;
        }
        BlockPos origin = Instances.slotOrigin(slot);
        InstanceLayout layout = Instances.adminLayout(slot);
        source.sendSuccess(() -> Component.literal(
                "Built slot " + slot + " -- origin " + origin.toShortString()
                        + ", entrance " + layout.entrance().toShortString()
                        + ", exit pad " + layout.exitPad().toShortString()
                        + ", " + layout.roomCount() + " rooms, path " + layout.pathLength()
                        + ", tier " + layout.lootTier()
                        + ", keystone " + layout.keystoneLevel()
                        + (layout.ominous() ? ", OMINOUS" : "")
                        + (layout.procedural() ? ", seed " + layout.seed()
                                : " -- STATIC FALLBACK, the planner failed")), false);
        // Printed so a headless check can aim a /fill sweep at the exact volume
        // without having to guess how far a procedural layout sprawled.
        source.sendSuccess(() -> Component.literal(
                "  bounds " + layout.geometry().spanX() + "x" + layout.geometry().spanZ()
                        + " cells, blocks " + origin.toShortString() + " .. "
                        + origin.offset(layout.geometry().spanX() * RoomGeometry.CELL - 1,
                                RoomGeometry.CEILING_Y,
                                layout.geometry().spanZ() * RoomGeometry.CELL - 1).toShortString()),
                false);
        return 1;
    }

    /**
     * Stamps one template into four adjacent cells at each of the four rotations
     * and reports what came back.
     *
     * <p>This is the check that proves the placement-offset table in
     * {@code TemplateStamper}. Two different bugs look identical in-world -- a
     * doorway one block outside its cell means the offsets are wrong, a doorway on
     * the wrong wall means the quarter-turn-to-{@code Rotation} mapping is
     * reversed -- and this separates them by printing the derived door edges and
     * spawn positions per rotation instead of making someone walk the rooms.
     */
    private static int stampTest(CommandSourceStack source) {
        if (!FabricLoader.getInstance().isDevelopmentEnvironment()) {
            source.sendFailure(Component.literal(
                    "stamptest is a development-only command."));
            return 0;
        }
        MinecraftServer server = source.getServer();
        int slot = Instances.adminBuild(server, null);
        if (slot < 0) {
            source.sendFailure(Component.literal("Could not allocate a slot to stamp into."));
            return 0;
        }
        // Purge the layout that build just made; this command wants the bare slot.
        Instances.adminPurge(server, slot);

        ServerLevel level = server.getLevel(PocketDungeonsMod.DUNGEON_LEVEL);
        if (level == null) {
            source.sendFailure(Component.literal("The dungeon dimension is not loaded."));
            return 0;
        }

        RoomManifest manifest = RoomManifest.current();
        RoomManifest.Entry entry = manifest.byName("hall_tee");
        if (entry == null) {
            source.sendFailure(Component.literal(
                    "hall_tee is not loaded; run /dungeon admin manifest reload."));
            return 0;
        }

        // Well clear of the slot grid, in the same scratch band the template
        // generator uses, so this never disturbs a live instance.
        BlockPos base = new BlockPos(512, 64, 1000512);
        for (int q = 0; q < 4; q++) {
            BlockPos cellOrigin = base.offset(q * RoomGeometry.CELL, 0, 0);
            level.setChunkForced(cellOrigin.getX() >> 4, cellOrigin.getZ() >> 4, true);
        }

        List<String> report = new ArrayList<>();
        Identifier processorList = entry.meta.processors == null
                ? null
                : Identifier.parse(entry.meta.processors);
        for (int q = 0; q < 4; q++) {
            BlockPos cellOrigin = base.offset(q * RoomGeometry.CELL, 0, 0);
            List<BlockPos> spawns = TemplateStamper.place(level, level.getStructureManager(),
                    cellOrigin, Identifier.parse(entry.meta.template),
                    q, 0L, processorList);

            int observed = 0;
            boolean escaped = false;
            for (BlockPos spawn : spawns) {
                int dx = spawn.getX() - cellOrigin.getX();
                int dz = spawn.getZ() - cellOrigin.getZ();
                if (dx < 0 || dx >= RoomGeometry.CELL || dz < 0 || dz >= RoomGeometry.CELL) {
                    escaped = true;
                }
            }
            // Read the doorways straight back out of the world: a door slot is
            // open air in the wall ring at floor + 1.
            for (int i = RoomGeometry.DOOR_MIN; i <= RoomGeometry.DOOR_MAX; i++) {
                if (level.getBlockState(cellOrigin.offset(i, 1, 0)).isAir()) observed |= DoorMask.NORTH;
                if (level.getBlockState(cellOrigin.offset(i, 1, RoomGeometry.CELL - 1)).isAir()) observed |= DoorMask.SOUTH;
                if (level.getBlockState(cellOrigin.offset(0, 1, i)).isAir()) observed |= DoorMask.WEST;
                if (level.getBlockState(cellOrigin.offset(RoomGeometry.CELL - 1, 1, i)).isAir()) observed |= DoorMask.EAST;
            }

            // Both sides rendered through DoorMask so they are directly comparable
            // rather than differing only by the order they were discovered in.
            int expected = DoorMask.rotateClockwise(entry.maskAtRotation0, q);
            boolean ok = observed == expected && !escaped;
            report.add("rotation " + q + " (" + TemplateStamper.ROTATIONS[q] + "): doors "
                    + DoorMask.toLetters(observed)
                    + ", expected " + DoorMask.toLetters(expected)
                    + ", " + spawns.size() + " spawn points"
                    + (escaped ? " -- SPAWN OUTSIDE CELL" : "")
                    + (ok ? " -- OK" : " -- MISMATCH"));
        }

        for (String line : report) {
            source.sendSuccess(() -> Component.literal(line), false);
        }
        source.sendSuccess(() -> Component.literal(
                "Stamped at " + base.toShortString() + "; purge by hand when done."), false);
        return 1;
    }

    /**
     * Dev-only U3 verification aid: one line per cell of a built instance,
     * reporting its chest(s) (loot table + seed) and live mob count. Without a
     * client attached there is no other way to confirm role dispatch
     * (corridor: no chest, no mobs; loot: chest, no mobs) or per-cell mob
     * counts against {@link DifficultyProfile}, since a room template's chest
     * position is not otherwise readable from the console.
     */
    private static int cellReport(CommandSourceStack source, int slot) {
        if (!FabricLoader.getInstance().isDevelopmentEnvironment()) {
            source.sendFailure(Component.literal("cellreport is a development-only command."));
            return 0;
        }
        ServerLevel level = source.getServer().getLevel(PocketDungeonsMod.DUNGEON_LEVEL);
        if (level == null) {
            source.sendFailure(Component.literal("The dungeon dimension is not loaded."));
            return 0;
        }
        InstanceLayout layout = Instances.adminLayout(slot);
        if (layout == null) {
            source.sendFailure(Component.literal("Slot " + slot + " is not allocated."));
            return 0;
        }

        List<String> lines = new ArrayList<>();
        for (BlockPos cellOrigin : layout.geometry().cellOrigins()) {
            List<BlockPos> chests = RoomContent.containers(level, cellOrigin);
            AABB cellBounds = new AABB(
                    cellOrigin.getX(), cellOrigin.getY(), cellOrigin.getZ(),
                    cellOrigin.getX() + RoomGeometry.CELL, cellOrigin.getY() + RoomGeometry.CEILING_Y + 1,
                    cellOrigin.getZ() + RoomGeometry.CELL);
            List<Mob> mobs = level.getEntitiesOfClass(Mob.class, cellBounds);

            StringBuilder sb = new StringBuilder("cell " + cellOrigin.toShortString() + ": "
                    + chests.size() + " chest(s), " + mobs.size() + " mob(s)");
            for (BlockPos chestPos : chests) {
                BlockEntity entity = level.getBlockEntity(chestPos);
                if (entity instanceof RandomizableContainer container) {
                    sb.append(" [").append(chestPos.toShortString())
                            .append(" -> ").append(container.getLootTable())
                            .append(" seed=").append(container.getLootTableSeed()).append("]");
                }
            }
            // U6: the two things a headless session cannot otherwise see. A
            // misspelt trial-spawner config id does not throw -- the codec drops
            // the field and the block quietly keeps FullConfig.DEFAULT -- so the
            // ids are read back out of the block entity rather than trusted.
            appendTrialBlocks(sb, level, cellOrigin);
            lines.add(sb.toString());
        }

        for (String line : lines) {
            source.sendSuccess(() -> Component.literal(line), false);
        }
        return lines.size();
    }

    /**
     * Appends every trial spawner and vault in the cell, with the configuration
     * that was actually written rather than the configuration that was intended.
     *
     * <p>Reads through the block entity's own saved NBT: {@code TrialSpawner}'s
     * config field is private with no getter for the ids, and
     * {@code VaultConfig} is reachable but printing it uniformly with the spawner
     * keeps one code path. This is the assertion U6 Stage 2 asks {@code stamptest}
     * for -- a block entity that survives rotation but loses its NBT is invisible
     * to every geometry check.
     */
    private static void appendTrialBlocks(StringBuilder sb, ServerLevel level, BlockPos cellOrigin) {
        for (BlockPos pos : BlockPos.betweenClosed(cellOrigin,
                cellOrigin.offset(RoomGeometry.CELL - 1, RoomGeometry.CEILING_Y,
                        RoomGeometry.CELL - 1))) {
            BlockState state = level.getBlockState(pos);
            boolean spawner = state.is(net.minecraft.world.level.block.Blocks.TRIAL_SPAWNER);
            boolean vault = state.is(net.minecraft.world.level.block.Blocks.VAULT);
            if (!spawner && !vault) {
                continue;
            }
            BlockEntity be = level.getBlockEntity(pos);
            if (be == null) {
                sb.append(" [").append(pos.toShortString())
                        .append(" -> ").append(spawner ? "trial_spawner" : "vault")
                        .append(" NO BLOCK ENTITY]");
                continue;
            }
            net.minecraft.nbt.CompoundTag tag = be.saveWithoutMetadata(level.registryAccess());
            sb.append(" [").append(pos.toShortString()).append(" -> ");
            if (spawner) {
                sb.append("trial_spawner ominous=")
                        .append(state.getValue(net.minecraft.world.level.block
                                .TrialSpawnerBlock.OMINOUS))
                        .append(" normal=").append(tag.getStringOr("normal_config", "<DEFAULT>"))
                        .append(" ominous_cfg=").append(tag.getStringOr("ominous_config", "<DEFAULT>"));
            } else {
                net.minecraft.nbt.CompoundTag config = tag.getCompoundOrEmpty("config");
                sb.append("vault ominous=")
                        .append(state.getValue(net.minecraft.world.level.block.VaultBlock.OMINOUS))
                        .append(" loot=").append(config.getStringOr("loot_table", "<DEFAULT>"))
                        .append(" key=").append(config.getCompoundOrEmpty("key_item")
                                .getStringOr("id", "<DEFAULT>"))
                        // The components matter as much as the item: vanilla
                        // matches a vault key with isSameItemSameComponents, so a
                        // token whose custom_data did not survive being written
                        // into the config would look identical here without them
                        // and open nothing in-world.
                        .append(" keytag=").append(config.getCompoundOrEmpty("key_item")
                                .getCompoundOrEmpty("components")
                                .getCompoundOrEmpty("minecraft:custom_data"));
            }
            sb.append("]");
        }
    }

    private static int purge(CommandSourceStack source, int slot) {
        if (!Instances.adminPurge(source.getServer(), slot)) {
            source.sendFailure(Component.literal("Slot " + slot + " is not allocated."));
            return 0;
        }
        source.sendSuccess(() -> Component.literal("Purged slot " + slot + "."), true);
        return 1;
    }

    private static int manifestReload(CommandSourceStack source) {
        RoomManifest manifest = RoomManifest.load(source.getServer());
        int loaded = manifest.rooms().size();
        int rejected = manifest.rejections().size();
        if (rejected == 0) {
            source.sendSuccess(() -> Component.literal(
                    "Loaded " + loaded + " room(s) into manifest."), false);
        } else {
            source.sendSuccess(() -> Component.literal(
                    "Loaded " + loaded + " room(s), rejected " + rejected + "."), false);
            for (String reason : manifest.rejections()) {
                source.sendFailure(Component.literal("  rejected: " + reason));
            }
        }
        return loaded;
    }

    private static int manifestList(CommandSourceStack source) {
        RoomManifest manifest = RoomManifest.current();
        if (manifest.rooms().isEmpty()) {
            source.sendSuccess(() -> Component.literal("No rooms loaded; run /dungeon admin manifest reload."), false);
            return 0;
        }
        for (RoomManifest.Entry room : manifest.rooms()) {
            StringBuilder sb = new StringBuilder();
            sb.append(room.name)
                    .append(" ")
                    .append(room.meta.footprintX).append("x").append(room.meta.footprintZ)
                    .append(" [");
            for (int i = 0; i < 4; i++) {
                if (i > 0) sb.append(" / ");
                sb.append(DoorMask.toLetters(room.maskAtRotation(i)));
            }
            sb.append("] ")
                    .append(String.join(", ", room.meta.roles));
            source.sendSuccess(() -> Component.literal(sb.toString()), false);
        }
        return manifest.rooms().size();
    }

    /**
     * Cross-checks the loaded manifest against every {@code (door mask, role)}
     * pair the planner can ask for, and names any hole.
     *
     * <p>A plan needs <em>every</em> cell to resolve, so one missing pair sinks a
     * whole layout -- that is exactly how M3 measured 0 of 200 seeds against the
     * original four rooms while the planner itself was correct. This command turns
     * that from a statistical mystery into a list, and it is the regression that
     * stops a later room edit from quietly reopening the gap.
     *
     * <p>{@code entrance} and {@code exit} are only checked against the four
     * single-door masks: the graph generator holds those two cells to one door
     * each, so a multi-door end cap is unreachable content, not a hole.
     */
    private static int coverage(CommandSourceStack source) {
        RoomManifest manifest = RoomManifest.current();
        if (manifest.rooms().isEmpty()) {
            source.sendFailure(Component.literal(
                    "No rooms loaded; run /dungeon admin manifest reload first."));
            return 0;
        }

        List<String> holes = new ArrayList<>();
        int checked = 0;
        for (int mask = 1; mask < 16; mask++) {
            boolean singleDoor = Integer.bitCount(mask) == 1;
            for (String role : List.of("encounter", "loot", "corridor", "entrance", "exit")) {
                if (!singleDoor && (role.equals("entrance") || role.equals("exit"))) {
                    continue;
                }
                checked++;
                if (manifest.queryAnyRotation(mask, role).isEmpty()) {
                    holes.add(DoorMask.toLetters(mask) + " / " + role);
                }
            }
        }

        int finalChecked = checked;
        if (holes.isEmpty()) {
            source.sendSuccess(() -> Component.literal(
                    "Coverage complete: all " + finalChecked + " (mask, role) pairs are satisfied.")
                    .withStyle(ChatFormatting.GREEN), false);
            return 1;
        }

        source.sendFailure(Component.literal(
                holes.size() + " of " + finalChecked + " (mask, role) pairs have no room:"));
        for (String hole : holes) {
            source.sendFailure(Component.literal("  " + hole));
        }
        return 0;
    }

    private static int plan(CommandSourceStack source, long seed) {
        RoomManifest manifest = RoomManifest.current();
        if (manifest.rooms().isEmpty()) {
            source.sendFailure(Component.literal(
                    "No rooms loaded; run /dungeon admin manifest reload first."));
            return 0;
        }

        // Single seed, no retry: this command exists to show one seed's exact
        // outcome, success or failure. The retry budget lives in LayoutPlanner
        // and is exercised by `plansurvey`.
        DungeonShape shape = LayoutGraphGenerator.generate(
                seed,
                PocketDungeonsConfig.pathLengthMin(), PocketDungeonsConfig.pathLengthMax(),
                PocketDungeonsConfig.branchProbability(), PocketDungeonsConfig.loopProbability());
        if (shape == null) {
            source.sendFailure(Component.literal(
                    "Seed " + seed + " exhausted the shape generator's backtracking budget."));
            return 0;
        }
        List<String> shapeProblems = LayoutGraphGenerator.validate(shape);
        if (!shapeProblems.isEmpty()) {
            source.sendFailure(Component.literal(
                    "Seed " + seed + " produced an invalid shape: "
                            + String.join("; ", shapeProblems)));
            return 0;
        }

        RoomSelector.Result result = RoomSelector.resolveDetailed(shape, manifest);
        if (result.plan() != null) {
            DungeonPlan plan = result.plan();
            List<String> problems = RoomSelector.validate(plan, PocketDungeonsConfig.maxGridSpan());
            if (!problems.isEmpty()) {
                source.sendFailure(Component.literal(
                        "Plan resolved for seed " + seed + " but failed validation: "
                                + String.join("; ", problems)));
                return 0;
            }
            source.sendSuccess(() -> Component.literal(
                    "Resolved plan for seed " + seed + " (" + plan.cells().size() + " rooms)"), false);
            for (String line : PlanRenderer.renderAscii(plan).split("\n")) {
                source.sendSuccess(() -> Component.literal(line), false);
            }
            return 1;
        } else {
            source.sendFailure(Component.literal("Could not resolve plan for seed " + seed + ":"));
            for (String line : PlanRenderer.renderFailure(shape, result.failure()).split("\n")) {
                source.sendFailure(Component.literal(line));
            }
            return 0;
        }
    }

    /**
     * Runs the full planner (with its retry budget) over a run of seeds and
     * reports how often it actually produces a buildable dungeon. This is the
     * measurement that says whether the room library is big enough for
     * procedural generation yet -- a low success rate is a content gap, not a
     * planner bug.
     */
    private static int planSurvey(CommandSourceStack source, int count) {
        RoomManifest manifest = RoomManifest.current();
        if (manifest.rooms().isEmpty()) {
            source.sendFailure(Component.literal(
                    "No rooms loaded; run /dungeon admin manifest reload first."));
            return 0;
        }

        int succeeded = 0;
        int totalAttempts = 0;
        Map<String, Integer> failureKinds = new LinkedHashMap<>();

        for (int i = 0; i < count; i++) {
            LayoutPlanner.Outcome outcome = LayoutPlanner.plan(
                    i, manifest, PocketDungeonsConfig.planAttemptBudget(),
                    PocketDungeonsConfig.pathLengthMin(), PocketDungeonsConfig.pathLengthMax(),
                    PocketDungeonsConfig.branchProbability(), PocketDungeonsConfig.loopProbability(),
                    PocketDungeonsConfig.maxGridSpan());
            totalAttempts += outcome.attemptsUsed();
            if (outcome.succeeded()) {
                succeeded++;
            } else {
                String reason = outcome.failureReason();
                // Collapse the cell-specific detail so the histogram stays readable.
                String kind = reason == null ? "unknown"
                        : reason.replaceAll("cell PlanCell\\[[^\\]]*\\]", "cell <n>")
                                .replaceAll("mask [A-Z]+", "mask <m>");
                failureKinds.merge(kind, 1, Integer::sum);
            }
        }

        int finalSucceeded = succeeded;
        int finalAttempts = totalAttempts;
        source.sendSuccess(() -> Component.literal(
                "Planned " + count + " seeds: " + finalSucceeded + " succeeded, "
                        + (count - finalSucceeded) + " failed after the full retry budget "
                        + "(" + finalAttempts + " total attempts)"), false);
        for (Map.Entry<String, Integer> e : failureKinds.entrySet()) {
            source.sendSuccess(() -> Component.literal(
                    "  " + e.getValue() + "x " + e.getKey()), false);
        }
        return succeeded;
    }
}
