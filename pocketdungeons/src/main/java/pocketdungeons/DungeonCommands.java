package pocketdungeons;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.LongArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;

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
import net.minecraft.world.Container;
import net.minecraft.world.RandomizableContainer;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.item.ItemStack;
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

                    // Instantly fails the caller's own door instead of making them
                    // wait out the real-time clock for the same downgrade. All the
                    // logic lives in RunLifecycle.quitDoor; see its own javadoc.
                    .then(Commands.literal("quit")
                            .executes(ctx -> quit(ctx.getSource().getPlayerOrException())))

                    // Self-service despawn of the caller's own run, owner only.
                    // Distinct from exit/quit, neither of which tears the
                    // instance down: a solo run left behind stays live and
                    // free-re-enterable (U8 Stage 1), which is exactly the
                    // problem for a run that is stuck rather than merely
                    // unfinished. See abandon's own javadoc.
                    .then(Commands.literal("abandon")
                            .executes(ctx -> abandon(ctx.getSource().getPlayerOrException())))

                    // Self-service /dungeon admin resetkey: resets the caller's own
                    // progress and hands them a fresh keystone [1], safe to run
                    // mid-run (quits the door first if one is active). See
                    // resetOwnKey's own javadoc for how it differs from the admin
                    // command it is named after.
                    .then(Commands.literal("resetkey")
                            .executes(ctx -> resetOwnKey(ctx.getSource().getPlayerOrException())))

                    .then(Commands.literal("key")
                            .executes(ctx -> mintKey(ctx.getSource().getPlayerOrException()))
                            // Read-only companion to the lore tooltip and /dungeon log,
                            // in one screen. A chat trigger rather than a sneak-right-click
                            // because RitualListener's use handler is already carrying the
                            // ritual and the exit pad, and it does not
                            // need a fourth meaning for the same gesture.
                            .then(Commands.literal("info")
                                    .executes(ctx -> keyInfo(ctx.getSource().getPlayerOrException()))))

                    .then(Commands.literal("choose")
                            .then(Commands.argument("step", IntegerArgumentType.integer(1, 3))
                                    .executes(ctx -> choose(ctx.getSource().getPlayerOrException(),
                                            IntegerArgumentType.getInteger(ctx, "step")))))

                    .then(Commands.literal("party")
                            // Bare /dungeon party opens the roster. New surface, not a
                            // replacement: before this there was no way to see a party at
                            // all, only to name somebody you already remembered. Adding an
                            // executes() to a literal that had none cannot shadow anything.
                            .executes(ctx -> partyRoster(ctx.getSource().getPlayerOrException()))
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

                    // M2 T2.2: an owner's own guest list for their room.
                    // M20: the lobby directory replaces the calling card, so the
                    // room subtree gains the visibility toggle and the display
                    // name, and loses the card-minting command.
                    .then(Commands.literal("room")
                            .then(Commands.literal("public")
                                    .executes(ctx -> roomPublic(ctx.getSource().getPlayerOrException())))
                            .then(Commands.literal("private")
                                    .executes(ctx -> roomPrivate(ctx.getSource().getPlayerOrException())))
                            .then(Commands.literal("name")
                                    .then(Commands.argument("text", StringArgumentType.greedyString())
                                            .executes(ctx -> roomName(ctx.getSource().getPlayerOrException(),
                                                    StringArgumentType.getString(ctx, "text")))))
                            .then(Commands.literal("whitelist")
                                    // Bare form opens the manager; add/remove/list keep
                                    // working exactly as they did, for console and for
                                    // anyone who prefers typing.
                                    .executes(ctx -> roomWhitelistScreen(
                                            ctx.getSource().getPlayerOrException()))
                                    .then(Commands.literal("add")
                                            .then(Commands.argument("target", EntityArgument.player())
                                                    .executes(ctx -> roomWhitelistAdd(
                                                            ctx.getSource().getPlayerOrException(),
                                                            EntityArgument.getPlayer(ctx, "target")))))
                                    .then(Commands.literal("remove")
                                            .then(Commands.argument("target", EntityArgument.player())
                                                    .executes(ctx -> roomWhitelistRemove(
                                                            ctx.getSource().getPlayerOrException(),
                                                            EntityArgument.getPlayer(ctx, "target")))))
                                    .then(Commands.literal("list")
                                            .executes(ctx -> roomWhitelistList(
                                                    ctx.getSource().getPlayerOrException())))))

                    // M67: room builder commands for creating, editing, loading
                    // and deleting room templates from inside the game.
                    .then(RoomBuilderCommands.branch())

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

                            .then(Commands.literal("buildroom")
                                    .executes(ctx -> buildRoom(ctx.getSource())))

                            .then(Commands.literal("saveroom")
                                    .then(Commands.argument("name", StringArgumentType.string())
                                            .executes(ctx -> saveRoom(ctx.getSource(),
                                                    StringArgumentType.getString(ctx, "name")))))

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

                            // M2 T2.1: not optional for a room blob. Restores this
                            // owner's live room from its backup file -- for a
                            // corrupted or accidentally-deleted live blob, or a
                            // player who wants their previous save back.
                            // A room blob is somebody's build, not disposable run state,
                            // and restoring one overwrites whatever is there now. The bare
                            // form asks first when a player ran it; "confirm" is the form
                            // that actually restores, and the one the console (which has no
                            // screen to be asked on) and the dialog's own button both use.
                            .then(Commands.literal("baserestore")
                                    .then(Commands.argument("target",
                                                    net.minecraft.commands.arguments.GameProfileArgument
                                                            .gameProfile())
                                            .executes(ctx -> baseRestoreAsk(ctx.getSource(),
                                                    net.minecraft.commands.arguments.GameProfileArgument
                                                            .getGameProfiles(ctx, "target")))
                                            .then(Commands.literal("confirm")
                                                    .executes(ctx -> baseRestore(ctx.getSource(),
                                                            net.minecraft.commands.arguments.GameProfileArgument
                                                                    .getGameProfiles(ctx, "target"))))))

                            // Wipes a player's saved room and closes anything of theirs
                            // still open in the world -- their lobby, an active run,
                            // a lingering quarry, any visit copy of the room. Offline-
                            // capable via GameProfileArgument, same as baserestore:
                            // the operator reaching for this may be doing it because
                            // the owner cannot fix it themselves.
                            .then(Commands.literal("resetroom")
                                    .then(Commands.argument("target",
                                                    net.minecraft.commands.arguments.GameProfileArgument
                                                            .gameProfile())
                                            .executes(ctx -> resetRoom(ctx.getSource(),
                                                    net.minecraft.commands.arguments.GameProfileArgument
                                                            .getGameProfiles(ctx, "target")))))

                            // Debug / moderation: reset a player's keystone progress to 0
                            // and confiscate any held keystones so the next /dungeon key gives
                            // a fresh level 1.
                            .then(Commands.literal("resetkey")
                                    .then(Commands.argument("target", EntityArgument.player())
                                            .executes(ctx -> resetKey(ctx.getSource(),
                                                    EntityArgument.getPlayer(ctx, "target")))))

                            .then(manifestBranch())
                            .then(Commands.literal("theme")
                                    .then(Commands.literal("list")
                                            .executes(ctx -> themeList(ctx.getSource()))))

                            // M27 27.1: the fixed test offer that stands in for door 3.
                            .then(Commands.literal("experiment")
                                    .then(Commands.literal("clear")
                                            .executes(ctx -> experimentClear(ctx.getSource())))
                                    .then(Commands.argument("theme", StringArgumentType.string())
                                            .executes(ctx -> experimentSet(ctx.getSource(),
                                                    StringArgumentType.getString(ctx, "theme"), "", null))
                                            .then(Commands.argument("affixes", StringArgumentType.string())
                                                    .executes(ctx -> experimentSet(ctx.getSource(),
                                                            StringArgumentType.getString(ctx, "theme"),
                                                            StringArgumentType.getString(ctx, "affixes"), null))
                                                    .then(Commands.argument("lootOverride",
                                                                    IntegerArgumentType.integer(1))
                                                            .executes(ctx -> experimentSet(ctx.getSource(),
                                                                    StringArgumentType.getString(ctx, "theme"),
                                                                    StringArgumentType.getString(ctx, "affixes"),
                                                                    IntegerArgumentType.getInteger(
                                                                            ctx, "lootOverride")))))))));
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
        // M6 T6.5: /dungeon is the route in, not a fallback behind the overworld
        // lodestone and not a fallback behind /dungeon key. A player holding no
        // keystone gets one here and goes in, in one command. That is what
        // "first-class" has to mean on a server whose overworld is empty, where
        // there is no lodestone to right-click and nobody to be told to go and
        // find one. This is the same free keystone /dungeon key already hands out
        // on the same terms, so it adds no way to farm one: the re-entry check
        // below is what stops a player who is standing outside a live run of their
        // own from minting a second key by walking back into it.
        if (Keystone.findHeld(player) == null && !RunLifecycle.ownsReenterableInstance(player)
                && mintKey(player) == 0) {
            return 0;
        }
        return RunLifecycle.enterWithKeystone(player) ? 1 : 0;
    }

    /**
     * {@code /dungeon choose <1|2|3>}: settle a completed run's door offer.
     * Player-only, not op-gated. M56: this now does preview then commit in
     * sequence, since the command bypasses the physical door-click and
     * lever-pull flow. M57: in a safe staging room, the command returns
     * the party to the safe room regardless of the step argument.
     */
    private static int choose(ServerPlayer player, int step) {
        InstanceRecord record = InstanceRegistry.byMember.get(player.getUUID());
        if (record != null && record.safeStaging) {
            return RunLifecycle.returnToSafe(player) ? 1 : 0;
        }
        if (!RunLifecycle.previewDoor(player, step)) {
            return 0;
        }
        return RunLifecycle.commitDoor(player) ? 1 : 0;
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
        if (!RunLifecycle.enterUntimed(player, keystoneLevel, ominous, theme)) {
            return 0;
        }
        source.sendSuccess(() -> Component.literal(
                "Untimed dungeon opened. It will not expire; close it with "
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
            Payout.deliver(player, Keystone.mint(level, AffixMath.effective(player.getUUID(), level,
                    AffixMath.parse(entry.keystoneAffix()))));
            player.sendSystemMessage(Component.literal(
                    "A replacement keystone [" + level + "]. Your progress was never on the item.")
                    .withStyle(ChatFormatting.AQUA));
            return 1;
        }

        log.setKeystone(player.getUUID(), 1, java.util.EnumSet.noneOf(Affix.class));
        Payout.deliver(player, Keystone.mint(1));
        player.sendSystemMessage(Component.literal(
                "Keystone [1]. Right-click a lodestone with it, or run /dungeon.")
                .withStyle(ChatFormatting.AQUA));
        return 1;
    }

    private static int exit(ServerPlayer player) {
        // A command exit is a retreat, not a completion -- it does not pay.
        return RunLifecycle.exit(player, RunLifecycle.ExitReason.COMMAND) ? 1 : 0;
    }

    /**
     * {@code /dungeon quit}: instantly fails the caller's own door instead of
     * making them wait out the real-time clock for the same downgrade, then
     * leaves. All of the actual work is {@link RunLifecycle#quitDoor}; this
     * is only the command binding.
     */
    private static int quit(ServerPlayer player) {
        return RunLifecycle.quitDoor(player) ? 1 : 0;
    }

    /**
     * {@code /dungeon abandon}: fully despawns the caller's own live run,
     * whether they are standing in it right now or it is sitting idle
     * waiting for free re-entry. Requested live: a player who gets stuck (a
     * completion that will not register, a soft-lock) can technically still
     * finish the run given enough persistence, but had no way to just throw
     * the whole thing away and start over with a clean layout.
     *
     * <p>{@code /dungeon exit} and {@code /dungeon quit} both only detach the
     * caller; the instance itself survives empty (U8 Stage 1: "an empty
     * instance is now normal"), so the same possibly-broken layout is what
     * free re-entry hands them right back. This instead calls the same
     * {@link InstanceTeardown#purge} every admin purge, timeout and normal
     * completion already funnel through: every stamped block is cleared,
     * the timer closes, any party members still inside are ejected, and the
     * keystone is refunded the same cost-free way a death or a timeout
     * already is (U8 Stage 1): the refund is what "start fresh" means
     * here, since the same keystone opens a brand new layout.
     *
     * <p>Owner only, the same restriction {@link RunLifecycle#leadershipChanged}
     * already enforces elsewhere: a guest abandoning the run would end it
     * for everyone else in the party too, which is not theirs to decide.
     * {@code /dungeon exit} already covers a guest removing just themselves.
     */
    private static int abandon(ServerPlayer player) {
        MinecraftServer server = player.level().getServer();
        InstanceRecord record = InstanceRegistry.byMember.get(player.getUUID());
        if (record == null) {
            for (InstanceRecord candidate : InstanceRegistry.bySlot.values()) {
                if (player.getUUID().equals(candidate.owner) && RunLifecycle.isReenterable(candidate)) {
                    record = candidate;
                    break;
                }
            }
        }
        if (record == null) {
            player.sendSystemMessage(Component.literal("You have no dungeon run to abandon.")
                    .withStyle(ChatFormatting.RED));
            return 0;
        }
        if (!player.getUUID().equals(record.owner)) {
            player.sendSystemMessage(Component.literal(
                    "Only the run's owner can abandon it. Use /dungeon exit to leave it yourself.")
                    .withStyle(ChatFormatting.RED));
            return 0;
        }
        InstanceTeardown.purge(server, record, "abandoned by owner");
        player.sendSystemMessage(Component.literal(
                "Dungeon abandoned. Your keystone is back in hand; open a new run whenever you're ready.")
                .withStyle(ChatFormatting.AQUA));
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
        String counts = entry.completedThemes().entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .map(value -> {
                    ThemeManifest.Entry theme = ThemeManifest.current().byId(value.getKey());
                    return (theme == null ? value.getKey() : theme.meta().name) + " " + value.getValue();
                })
                .collect(java.util.stream.Collectors.joining(", "));
        source.sendSuccess(() -> Component.literal("Themes completed: "
                + (counts.isEmpty() ? "none" : counts)).withStyle(ChatFormatting.GRAY), false);
        return entry.runsCompleted();
    }

    /**
     * Bare {@code /dungeon party}: who is coming with you, one "Kick" button each.
     *
     * <p>No leader check here on purpose. The buttons run
     * {@code /dungeon party kick <name>}, which lands in {@code Instances.stageKick}
     * and is checked there, once -- a second check in front of the screen could
     * disagree with the first, and this mod already spent a session on two things
     * that each looked locally correct disagreeing about the same concept.
     */
    private static int partyRoster(ServerPlayer leader) {
        DialogKit.show(leader, DialogScreens.partyRoster(leader.level().getServer(),
                PartyService.partyCompanions(leader.getUUID())));
        return 1;
    }

    /** {@code /dungeon key info}: the held keystone plus this player's run statistics. */
    private static int keyInfo(ServerPlayer player) {
        if (Keystone.findHeld(player) == null) {
            player.sendSystemMessage(Component.literal("You are not carrying a keystone.")
                    .withStyle(ChatFormatting.RED));
            return 0;
        }
        DialogKit.show(player, DialogScreens.inspectKeystone(player));
        return 1;
    }

    /** Bare {@code /dungeon room whitelist}: the guest list, as something you can edit. */
    private static int roomWhitelistScreen(ServerPlayer owner) {
        java.util.List<java.util.UUID> entries = new java.util.ArrayList<>(
                RoomWhitelist.forServer(owner.level().getServer()).get(owner.getUUID()));
        entries.sort(java.util.Comparator.comparing(java.util.UUID::toString));
        DialogKit.show(owner, DialogScreens.whitelist(owner.level().getServer(),
                owner.getUUID(), entries, null));
        return 1;
    }

    private static int party(ServerPlayer leader, ServerPlayer target) {
        return PartyService.party(leader, target) ? 1 : 0;
    }

    private static int kick(ServerPlayer leader, ServerPlayer target) {
        return PartyService.stageKick(leader, target.getUUID(), target.getName().getString()) ? 1 : 0;
    }

    private static int kickAll(ServerPlayer leader) {
        return PartyService.stageKick(leader, null, null) ? 1 : 0;
    }

    private static int kickConfirm(ServerPlayer leader) {
        return PartyService.confirmKick(leader) ? 1 : 0;
    }

    private static int invite(ServerPlayer inviter, ServerPlayer target) {
        return PartyService.invite(inviter, target) ? 1 : 0;
    }

    private static int join(ServerPlayer player, ServerPlayer leader) {
        if (player.level().dimension().equals(PocketDungeonsMod.DUNGEON_LEVEL)) {
            player.sendSystemMessage(Component.literal("You are already inside a dungeon.")
                    .withStyle(ChatFormatting.RED));
            return 0;
        }
        return PartyService.join(player, leader) ? 1 : 0;
    }

    // ---- room whitelist (M2 T2.2) ---------------------------------------------

    private static int roomWhitelistAdd(ServerPlayer owner, ServerPlayer target) {
        if (target.getUUID().equals(owner.getUUID())) {
            owner.sendSystemMessage(Component.literal("You already own your room.")
                    .withStyle(ChatFormatting.RED));
            return 0;
        }
        boolean added = RoomWhitelist.forServer(owner.level().getServer())
                .add(owner.getUUID(), target.getUUID());
        owner.sendSystemMessage(Component.literal(
                (added ? "Added " : "") + target.getName().getString()
                        + (added ? " to your room's whitelist." : " is already whitelisted."))
                .withStyle(added ? ChatFormatting.GREEN : ChatFormatting.YELLOW));
        return added ? 1 : 0;
    }

    private static int roomWhitelistRemove(ServerPlayer owner, ServerPlayer target) {
        boolean removed = RoomWhitelist.forServer(owner.level().getServer())
                .remove(owner.getUUID(), target.getUUID());
        owner.sendSystemMessage(Component.literal(
                removed ? "Removed " + target.getName().getString() + " from your room's whitelist."
                        : target.getName().getString() + " was not whitelisted.")
                .withStyle(removed ? ChatFormatting.GREEN : ChatFormatting.YELLOW));
        return removed ? 1 : 0;
    }

    private static int roomWhitelistList(ServerPlayer owner) {
        java.util.Set<java.util.UUID> whitelist = RoomWhitelist.forServer(owner.level().getServer())
                .get(owner.getUUID());
        if (whitelist.isEmpty()) {
            owner.sendSystemMessage(Component.literal("Your room's whitelist is empty.")
                    .withStyle(ChatFormatting.GRAY));
            return 1;
        }
        StringBuilder sb = new StringBuilder("Whitelisted: ");
        boolean first = true;
        for (java.util.UUID id : whitelist) {
            if (!first) {
                sb.append(", ");
            }
            first = false;
            ServerPlayer online = owner.level().getServer().getPlayerList().getPlayer(id);
            sb.append(online != null ? online.getName().getString() : id.toString());
        }
        owner.sendSystemMessage(Component.literal(sb.toString()).withStyle(ChatFormatting.GRAY));
        return 1;
    }

    /**
     * {@code /dungeon room public}: lists this player's room in the lobby
     * directory. Visibility, not permission: the whitelist still gates what a
     * visitor can do once inside.
     */
    private static int roomPublic(ServerPlayer owner) {
        DungeonLog.forServer(owner.level().getServer()).setPublicListed(owner.getUUID(), true);
        Chime.roomListed(owner);
        owner.sendSystemMessage(Component.literal(
                "Your room is now listed in the lobby directory.")
                .withStyle(ChatFormatting.GREEN));
        return 1;
    }

    /**
     * {@code /dungeon room private}: unlists this player's room. The default;
     * a room never appears in the directory until its owner opts in.
     */
    private static int roomPrivate(ServerPlayer owner) {
        DungeonLog.forServer(owner.level().getServer()).setPublicListed(owner.getUUID(), false);
        Chime.roomUnlisted(owner);
        owner.sendSystemMessage(Component.literal("Your room is no longer listed.")
                .withStyle(ChatFormatting.GRAY));
        return 1;
    }

    /**
     * {@code /dungeon room name <text>}: the display name shown in the lobby
     * directory. Capped at 16 characters, the same cap the whitelist-name
     * dialog's text input enforces; a longer string is trimmed, not refused.
     */
    private static int roomName(ServerPlayer owner, String text) {
        // PD-37: the section sign is what triggers a legacy formatting code;
        // stripping it neutralizes every code regardless of what follows,
        // so an obfuscated or colored name cannot reach the lobby directory
        // other players read.
        String name = text.trim().replace("§", "");
        if (name.length() > 16) {
            name = name.substring(0, 16);
        }
        DungeonLog.forServer(owner.level().getServer()).setRoomName(owner.getUUID(), name);
        owner.sendSystemMessage(Component.literal(
                "Room name set to " + name + ".").withStyle(ChatFormatting.GREEN));
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
     * Opens an empty 16x16x6 shell in the dungeon dimension for hand-authoring
     * a room template, marking the instance as an admin build room. One per
     * player: an existing build room is torn down first.
     */
    private static int buildRoom(CommandSourceStack source) throws CommandSyntaxException {
        ServerPlayer player = source.getPlayerOrException();
        int slot = Instances.adminBuildRoom(source.getServer(), player);
        if (slot == -2) {
            source.sendFailure(Component.literal(
                    "You are inside a live instance. Leave it before opening a build room."));
            return 0;
        }
        if (slot < 0) {
            source.sendFailure(Component.literal(
                    "Could not open a build room: the dungeon dimension is missing or stamping failed."));
            return 0;
        }
        source.sendSuccess(() -> Component.literal(
                "Build room at " + InstanceRegistry.slotOrigin(slot).toShortString()
                        + ". Build freely; save it with /dungeon admin saveroom <name>."), false);
        return 1;
    }

    /**
     * Captures the player's admin build room to
     * {@code src/main/resources/data/pocketdungeons/structure/rooms/} as
     * {@code <name>_<author>_<timestamp>.nbt}, sends them to the overworld
     * spawn, and tears the room down.
     */
    private static int saveRoom(CommandSourceStack source, String name) throws CommandSyntaxException {
        ServerPlayer player = source.getPlayerOrException();
        String path = Instances.adminSaveRoom(source.getServer(), player, name);
        if (path == null) {
            source.sendFailure(Component.literal(
                    "No build room is open for you. Open one with /dungeon admin buildroom."));
            return 0;
        }
        source.sendSuccess(() -> Component.literal(
                "Saved room " + name + " to " + path + "."), false);
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
        BlockPos origin = InstanceRegistry.slotOrigin(slot);
        InstanceLayout layout = Instances.adminLayout(slot);
        source.sendSuccess(() -> Component.literal(
                "Built slot " + slot + ": origin " + origin.toShortString()
                        + ", entrance " + layout.entrance().toShortString()
                        + ", exit pad " + layout.exitPad().toShortString()
                        + ", " + layout.roomCount() + " rooms, path " + layout.pathLength()
                        + ", tier " + layout.lootTier()
                        + ", keystone " + layout.keystoneLevel()
                        + (layout.ominous() ? ", OMINOUS" : "")
                        + (layout.procedural() ? ", seed " + layout.seed()
                                : ", STATIC FALLBACK: the planner failed")), false);
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
                    + (escaped ? ", SPAWN OUTSIDE CELL" : "")
                    + (ok ? ", OK" : ", MISMATCH"));
        }

        for (String line : report) {
            source.sendSuccess(() -> Component.literal(line), false);
        }
        // PD-39: the stamped blocks are the point (inspect them by hand), but
        // the force-load ticket is not; nothing released it, so repeated runs
        // accumulated forced chunks with no way to clear them but a restart.
        // The chunk stays loaded on its own once a player is standing nearby
        // to inspect it, the same as anywhere else in the world.
        for (int q = 0; q < 4; q++) {
            BlockPos cellOrigin = base.offset(q * RoomGeometry.CELL, 0, 0);
            level.setChunkForced(cellOrigin.getX() >> 4, cellOrigin.getZ() >> 4, false);
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
                        .append(" normal=").append(configSummary(tag, "normal_config"))
                        .append(" ominous_cfg=").append(configSummary(tag, "ominous_config"))
                        .append(" cooldown=").append(tag.getIntOr("target_cooldown_length", -1))
                        .append(" range=").append(tag.getIntOr("required_player_range", -1));
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

    /**
     * The trial spawner config field is either a plain id string, or -- for a
     * Swarming run (M4 T4.5) -- an inline {@code TrialSpawnerConfig} object with
     * its {@code total_mobs}/{@code simultaneous_mobs} scaled. A misspelt id or
     * a codec that rejected the inline blob does not throw; either drops the
     * field silently and the spawner keeps {@code FullConfig.DEFAULT}
     * (`TrialContent#writeInlineConfig`'s note). So this reads back whichever
     * shape is actually there rather than assuming the id form.
     */
    private static String configSummary(net.minecraft.nbt.CompoundTag tag, String key) {
        net.minecraft.nbt.Tag value = tag.get(key);
        if (value == null) {
            return "<DEFAULT>";
        }
        if (value instanceof net.minecraft.nbt.StringTag) {
            return tag.getStringOr(key, "<DEFAULT>");
        }
        if (value instanceof net.minecraft.nbt.CompoundTag inline) {
            return "inline(total_mobs=" + inline.get("total_mobs")
                    + " simultaneous_mobs=" + inline.get("simultaneous_mobs") + ")";
        }
        return value.toString();
    }

    private static int purge(CommandSourceStack source, int slot) {
        if (!Instances.adminPurge(source.getServer(), slot)) {
            source.sendFailure(Component.literal("Slot " + slot + " is not allocated."));
            return 0;
        }
        source.sendSuccess(() -> Component.literal("Purged slot " + slot + "."), true);
        return 1;
    }

    /**
     * M2 T2.1: "every write to a room blob is backed up first, and
     * {@code admin baserestore} recovers a room from that backup on demand."
     * Works for an offline owner -- {@code GameProfileArgument} resolves a name
     * to a profile without requiring the target to be connected, which matters
     * here more than anywhere else in this command tree: the operator reaching
     * for this is very possibly doing it *because* the owner cannot log in to
     * fix it themselves.
     */
    /**
     * The confirmation step in front of {@link #baseRestore}.
     *
     * <p>Only an operator with a screen can be asked, and only about one room at a
     * time: {@code GameProfileArgument} accepts a selector that resolves to many,
     * and there is no honest way to render "restore these fourteen rooms" as one
     * yes/no. Console, and any multi-target run, are told to use the explicit
     * {@code confirm} form instead -- which is the same command the dialog's own
     * button runs, not a second code path.
     */
    private static int baseRestoreAsk(CommandSourceStack source,
                                      java.util.Collection<net.minecraft.server.players.NameAndId> targets) {
        ServerPlayer operator = source.getPlayer();
        if (operator == null || targets.size() != 1) {
            source.sendFailure(Component.literal(
                    "Add \"confirm\" to restore: /dungeon admin baserestore <player> confirm"));
            return 0;
        }
        net.minecraft.server.players.NameAndId target = targets.iterator().next();
        java.time.Instant when = RoomStore.backupTime(source.getServer(), target.id());
        if (when == null) {
            source.sendFailure(Component.literal(
                    target.name() + " has no room backup to restore from."));
            return 0;
        }
        DialogKit.show(operator, DialogScreens.baseRestoreConfirm(target.name(),
                java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
                        .withZone(java.time.ZoneId.systemDefault()).format(when)));
        return 1;
    }

    private static int baseRestore(CommandSourceStack source,
                                   java.util.Collection<net.minecraft.server.players.NameAndId> targets) {
        int restored = 0;
        for (net.minecraft.server.players.NameAndId target : targets) {
            if (RoomStore.restoreFromBackup(source.getServer(), target.id())) {
                source.sendSuccess(() -> Component.literal(
                        "Restored " + target.name() + "'s room from its backup."), true);
                restored++;
            } else {
                source.sendFailure(Component.literal(
                        target.name() + " has no room backup to restore from."));
            }
        }
        return restored;
    }

    /**
     * {@code /dungeon admin resetroom <target>}: closes every instance
     * {@code target} has open (their lobby, an active run, a lingering quarry,
     * any visit copy of the room) and deletes their saved room, backing it up
     * first the same as every other {@link RoomStore} write. The next time they
     * open a lobby they get a fresh {@code entrance_hall}, same as a player who
     * has never saved a room at all.
     */
    private static int resetRoom(CommandSourceStack source,
                                 java.util.Collection<net.minecraft.server.players.NameAndId> targets) {
        MinecraftServer server = source.getServer();
        int handled = 0;
        for (net.minecraft.server.players.NameAndId target : targets) {
            int purged = Instances.adminPurgeByOwner(server, target.id());
            boolean hadRoom = RoomStore.reset(server, target.id());
            if (!hadRoom && purged == 0) {
                source.sendFailure(Component.literal(
                        target.name() + " has no saved room and nothing open to reset."));
                continue;
            }
            // M24: wiping the room wipes the prestige streak. The count is
            // "completions while holding the same room without resetting", and
            // this command is the reset, so the two move together.
            DungeonLog.forServer(server).setRoomCompletions(target.id(), 0);
            String detail = hadRoom
                    ? (purged > 0 ? "room and " + purged + " open instance(s)" : "room")
                    : purged + " open instance(s), no saved room";
            source.sendSuccess(() -> Component.literal(
                    "Reset " + target.name() + "'s " + detail + "."), true);
            handled++;
        }
        return handled;
    }

    private static int resetKey(CommandSourceStack source, ServerPlayer target) {
        MinecraftServer server = source.getServer();
        // M48: a moderation reset is the same full campaign reset a player can
        // do themselves, not just a keystone zeroing. Clears keystone progress,
        // themes, fuel, run stats, the bag, and any orphaned or stashed
        // inventory; preserves unlocked shells, diary bands, room settings and
        // recent visitors.
        DungeonLog.forServer(server).resetCampaign(target.getUUID());
        int cleared = clearKeystones(target);
        source.sendSuccess(() -> Component.literal(
                "Reset " + target.getName().getString() + "'s campaign"
                        + (cleared > 0 ? " and cleared " + cleared + " keystone(s)." : ".")), true);
        if (target != source.getPlayer()) {
            target.sendSystemMessage(Component.literal("Your keystone progress has been reset.")
                    .withStyle(ChatFormatting.YELLOW));
        }
        return 1;
    }

    /**
     * {@code /dungeon resetkey}: the player-facing, self-service version of
     * {@link #resetKey}. Two differences from the admin command, both by
     * request: it hands the caller a fresh keystone {@code [1]} afterward.
     * The admin version leaves the target with a reset log entry and nothing
     * in hand, which is the right call for moderation but not for a player
     * resetting their own progress on purpose, and it is safe to run from
     * inside an active run. {@link RunLifecycle#quitDoor} is only called when
     * there is actually something to quit, so a player resetting from the
     * overworld never sees an unrelated "you have left the dungeon" line.
     */
    private static int resetOwnKey(ServerPlayer player) {
        // 1. If in an instance, fail the door first so the keystone penalty
        //    applies and the room returns to its lobby state.
        if (InstanceRegistry.byMember.containsKey(player.getUUID())) {
            RunLifecycle.quitDoor(player);
        }
        MinecraftServer server = player.level().getServer();
        // 2. If still in the dungeon dimension, exit to the overworld before
        //    clearing the stash and orphan, so the live inventory swap is not
        //    racing the reset. exit is a no-op (returns false) from the overworld.
        if (player.level().dimension().equals(PocketDungeonsMod.DUNGEON_LEVEL)) {
            RunLifecycle.exit(player, RunLifecycle.ExitReason.COMMAND);
        }
        // 3. Full campaign reset: keystone progress, themes, fuel, run stats,
        //    bag, orphan and stash. Unlockables (shells, diary bands, room
        //    settings, recent visitors) are preserved.
        DungeonLog.forServer(server).resetCampaign(player.getUUID());
        // 4. Clear any keystones from the inventory and ender chest.
        int cleared = clearKeystones(player);
        // 5. Clear any bag-tagged items the player is still carrying (a reset
        //    from the overworld can leave bag loot in the survival inventory).
        int bagsCleared = clearBagTagged(player);
        // 6. A fresh keystone [1] in hand, the same as a brand-new player.
        //    setKeystone must be called so the server-side level matches the
        //    item: InventorySwap.enterVoid reads the server-side level to
        //    decide whether to place a keystone in the dungeon inventory, and
        //    applyKeystoneItem skips level 0. Without this, the player enters
        //    the dungeon with no compass after a resetkey.
        DungeonLog.forServer(server).setKeystone(player.getUUID(), 1,
                java.util.EnumSet.noneOf(Affix.class));
        Payout.deliver(player, Keystone.mint(1));
        player.sendSystemMessage(Component.literal(
                "Keystone progress reset. Bag cleared. Keystone [1] in hand."
                        + " Your unlocked shells and diary entries are preserved.")
                .withStyle(ChatFormatting.AQUA));
        if (cleared > 0 || bagsCleared > 0) {
            player.sendSystemMessage(Component.literal(
                    (cleared > 0 ? cleared + " old keystone(s)" : "")
                            + (cleared > 0 && bagsCleared > 0 ? ", " : "")
                            + (bagsCleared > 0 ? bagsCleared + " bag item(s)" : "")
                            + " cleared.")
                    .withStyle(ChatFormatting.GRAY));
        }
        return 1;
    }

    private static int clearBagTagged(ServerPlayer player) {
        int cleared = 0;
        net.minecraft.world.entity.player.Inventory inv = player.getInventory();
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack stack = inv.getItem(i);
            if (InventorySwap.isBagTagged(stack)) {
                inv.setItem(i, ItemStack.EMPTY);
                cleared++;
            }
        }
        return cleared;
    }

    private static int clearKeystones(ServerPlayer player) {
        return clearKeystones(player.getInventory()) + clearKeystones(player.getEnderChestInventory());
    }

    private static int clearKeystones(Container container) {
        int cleared = 0;
        for (int i = 0; i < container.getContainerSize(); i++) {
            ItemStack stack = container.getItem(i);
            if (Keystone.isKeystone(stack)) {
                container.setItem(i, ItemStack.EMPTY);
                cleared++;
            }
        }
        return cleared;
    }

    private static int themeList(CommandSourceStack source) {
        ThemeManifest manifest = ThemeManifest.current();
        for (ThemeManifest.Entry theme : manifest.themes()) {
            source.sendSuccess(() -> Component.literal(theme.id() + ": " + theme.meta().name), false);
        }
        for (String rejection : manifest.rejections()) {
            source.sendFailure(Component.literal("  rejected: " + rejection));
        }
        for (String rejection : AdventureGraphs.current().rejections()) {
            source.sendFailure(Component.literal("  adventure node rejected: " + rejection));
        }
        return manifest.themes().size();
    }

    /**
     * {@code /dungeon admin experiment <theme> [affixes] [lootOverride]}: sets
     * the fixed offer door 3 shows to every player until cleared or the server
     * restarts. {@code theme} is not validated against the loaded manifest here:
     * an unresolvable theme falls back the same way any other offer's theme
     * does when rendering ({@link DungeonScreen}'s {@code themeName}) or
     * generating ({@code Instances.generateBehindLobby}'s null-safe
     * {@code ThemeManifest.byId} lookup), so an operator sees the same
     * failure mode a bad theme id would produce anywhere else in the mod.
     */
    private static int experimentSet(CommandSourceStack source, String theme, String affixes,
                                     Integer lootOverride) {
        // PD-16: every other offer's level goes through KeystoneMath.upgrade,
        // which clamps to [1, keystoneMaxLevel]. This one skipped that,
        // so an operator's typo could hand a completed door 3 an unclamped
        // level, permanently downgrading (or overshooting) whoever took it.
        if (lootOverride != null
                && (lootOverride < 1 || lootOverride > PocketDungeonsConfig.keystoneMaxLevel())) {
            source.sendFailure(Component.literal(
                    "lootOverride must be between 1 and " + PocketDungeonsConfig.keystoneMaxLevel()
                            + " (keystoneMaxLevel)."));
            return 0;
        }
        var parsedAffixes = AffixMath.parse(affixes);
        ExperimentalDungeon.set(theme, parsedAffixes, lootOverride);
        // PD-36: echo what AffixMath.parse actually kept, not the operator's
        // raw string. parse silently drops an unrecognized token, so the raw
        // string could confirm an affix that was never applied.
        String appliedAffixes = AffixMath.join(parsedAffixes);
        source.sendSuccess(() -> Component.literal(
                "Door 3 now offers the experimental dungeon: theme " + theme
                        + (appliedAffixes.isEmpty() ? "" : ", affixes " + appliedAffixes)
                        + (lootOverride == null ? "" : ", loot level " + lootOverride)
                        + ". Clear with /dungeon admin experiment clear.")
                .withStyle(ChatFormatting.YELLOW), true);
        return 1;
    }

    private static int experimentClear(CommandSourceStack source) {
        ExperimentalDungeon.clear();
        source.sendSuccess(() -> Component.literal(
                "Door 3 is back to its normal offer."), true);
        return 1;
    }

    /**
     * {@code /dungeon admin manifest reload}. PD-29: used to reload rooms
     * only, while three other commands ({@code stampTest}, {@code coverage}/
     * {@code plan}/{@code planSurvey}) pointed operators here as the fix for
     * stale themes, adventure nodes, diaries or anomaly rooms it could never
     * actually clear. Reloads all five manifests and reports every count, the
     * same set {@code SERVER_STARTED} loads at boot.
     */
    private static int manifestReload(CommandSourceStack source) {
        MinecraftServer server = source.getServer();
        // M68: a single ContentReload.reload builds all five surfaces into one
        // snapshot and publishes them atomically, or keeps the last valid
        // snapshot if the candidate fails required coverage.
        ContentSnapshot snapshot = ContentReload.reload(server);
        ThemeManifest themes = snapshot.themes();
        AdventureGraphs adventure = snapshot.adventure();
        Diaries diaries = snapshot.diaries();
        RoomManifest manifest = snapshot.rooms();
        RoomManifest anomaly = snapshot.anomalyRooms();

        int loaded = manifest.rooms().size();
        List<String> rejections = new ArrayList<>();
        rejections.addAll(themes.rejections());
        rejections.addAll(adventure.rejections());
        rejections.addAll(diaries.rejections());
        rejections.addAll(manifest.rejections());
        rejections.addAll(anomaly.rejections());
        rejections.addAll(snapshot.errors());

        source.sendSuccess(() -> Component.literal(
                "Loaded " + loaded + " room(s), " + anomaly.rooms().size() + " anomaly room(s), "
                        + themes.themes().size() + " theme(s), " + adventure.graph().size()
                        + " adventure node(s), " + diaries.entries().size() + " diar"
                        + (diaries.entries().size() == 1 ? "y" : "ies") + "."), false);
        if (!snapshot.valid()) {
            source.sendFailure(Component.literal(
                    "Reload rejected: required coverage failed. Last valid content kept."));
        }
        if (!rejections.isEmpty()) {
            source.sendFailure(Component.literal(rejections.size() + " rejected:"));
            for (String reason : rejections) {
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
