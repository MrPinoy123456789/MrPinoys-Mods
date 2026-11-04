package pocketdungeons;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Random;
import java.util.UUID;

/**
 * {@code /dungeon admin wild ...}, the operator side of wild mode, rebuilt as screens (2026-11-04): an overview, a list
 * with filters and pages, nearest first, an info card per lodestone with its actions as buttons, and the actions
 * themselves (place, teleport, omen, reset, collapse, rebuild, top up, remove). Anything that closes, collapses or
 * removes asks for {@code confirm}; its button puts the command in the chat box rather than running it. Every id and
 * offering argument suggests what exists. Only registered when the server runs wild mode.
 */
final class WildCommands {

    private static final Random RANDOM = new Random();
    private static final String ROOT = "/dungeon admin wild";

    private WildCommands() {}

    // ---- the tree ------------------------------------------------------------------------------------------------------

    private static final SuggestionProvider<CommandSourceStack> IDS = (ctx, builder) -> {
        WildState state = WildStore.state();
        List<String> ids = new ArrayList<>();
        if (state != null) {
            for (WildState.Lodestone l : state.all()) {
                ids.add(l.id);
            }
        }
        return SharedSuggestionProvider.suggest(ids, builder);
    };

    private static final SuggestionProvider<CommandSourceStack> OFFERINGS = (ctx, builder) -> {
        List<String> ids = new ArrayList<>();
        for (WildDungeons.Offering o : WildDungeons.all()) {
            ids.add(o.id());
        }
        return SharedSuggestionProvider.suggest(ids, builder);
    };

    private static RequiredArgumentBuilder<CommandSourceStack, String> id() {
        return Commands.argument("id", StringArgumentType.word()).suggests(IDS);
    }

    private static String id(com.mojang.brigadier.context.CommandContext<CommandSourceStack> ctx) {
        return StringArgumentType.getString(ctx, "id");
    }

    static void register(LiteralArgumentBuilder<CommandSourceStack> admin) {
        LiteralArgumentBuilder<CommandSourceStack> list = Commands.literal("list")
                .executes(ctx -> list(ctx.getSource(), AdminText.Filter.ALL, 1))
                .then(Commands.argument("page", IntegerArgumentType.integer(1))
                        .executes(ctx -> list(ctx.getSource(), AdminText.Filter.ALL,
                                IntegerArgumentType.getInteger(ctx, "page"))));
        for (AdminText.Filter filter : AdminText.Filter.values()) {
            list.then(Commands.literal(filter.word)
                    .executes(ctx -> list(ctx.getSource(), filter, 1))
                    .then(Commands.argument("page", IntegerArgumentType.integer(1))
                            .executes(ctx -> list(ctx.getSource(), filter, IntegerArgumentType.getInteger(ctx, "page")))));
        }
        admin.then(Commands.literal("wild")
                .executes(ctx -> overview(ctx.getSource()))
                .then(list)
                .then(Commands.literal("near").executes(ctx -> near(ctx.getSource())))
                .then(Commands.literal("info").then(id().executes(ctx -> info(ctx.getSource(), id(ctx)))))
                .then(Commands.literal("tp").then(id().executes(ctx -> tp(ctx.getSource(), id(ctx)))))
                .then(Commands.literal("offerings").executes(ctx -> offerings(ctx.getSource())))
                .then(Commands.literal("place")
                        .executes(ctx -> place(ctx.getSource(), 0, null))
                        .then(Commands.argument("level", IntegerArgumentType.integer(1, 1000))
                                .executes(ctx -> place(ctx.getSource(),
                                        IntegerArgumentType.getInteger(ctx, "level"), null))
                                .then(Commands.argument("offering", StringArgumentType.greedyString())
                                        .suggests(OFFERINGS)
                                        .executes(ctx -> place(ctx.getSource(),
                                                IntegerArgumentType.getInteger(ctx, "level"),
                                                StringArgumentType.getString(ctx, "offering"))))))
                .then(Commands.literal("omen").then(id()
                        .then(Commands.literal("on").executes(ctx -> omen(ctx.getSource(), id(ctx), true)))
                        .then(Commands.literal("off").executes(ctx -> omen(ctx.getSource(), id(ctx), false)))))
                .then(Commands.literal("reset").then(id()
                        .executes(ctx -> ask(ctx.getSource(), id(ctx), "reset",
                                "closes its dungeon now; anyone inside is sent out and loose items go to lost and found"))
                        .then(Commands.literal("confirm").executes(ctx -> reset(ctx.getSource(), id(ctx))))))
                .then(Commands.literal("collapse").then(id()
                        .executes(ctx -> ask(ctx.getSource(), id(ctx), "collapse",
                                "closes its dungeon, turns the ruin to rubble and retires it; a new one appears elsewhere"))
                        .then(Commands.literal("confirm").executes(ctx -> collapse(ctx.getSource(), id(ctx))))))
                .then(Commands.literal("remove").then(id()
                        .executes(ctx -> ask(ctx.getSource(), id(ctx), "remove",
                                "removes it and its ruin with no lost and found; only when nobody is inside"))
                        .then(Commands.literal("confirm").executes(ctx -> remove(ctx.getSource(), id(ctx))))))
                .then(Commands.literal("rebuild")
                        .then(Commands.literal("all").executes(ctx -> rebuildAll(ctx.getSource())))
                        .then(id().executes(ctx -> rebuild(ctx.getSource(), id(ctx), true))))
                .then(Commands.literal("topup")
                        .executes(ctx -> topUp(ctx.getSource(), WildTopUp.ATTEMPTS_PER_PASS))
                        .then(Commands.argument("attempts", IntegerArgumentType.integer(1, 500))
                                .executes(ctx -> topUp(ctx.getSource(), IntegerArgumentType.getInteger(ctx, "attempts"))))));
    }

    // ---- shared bits ---------------------------------------------------------------------------------------------------

    /** A lodestone's position relative to the caller, or {@code null} for the console or another dimension. */
    private static Vec3 viewer(CommandSourceStack source, WildState.Lodestone l) {
        ServerPlayer player = source.getPlayer();
        if (player == null || !WildLodestones.dimensionKey(player.level()).equals(l.dimension)) {
            return null;
        }
        return player.position();
    }

    private static double distance(Vec3 from, WildState.Lodestone l) {
        return Math.hypot(l.x + 0.5 - from.x, l.z + 0.5 - from.z);
    }

    /** "340m NE", or the coordinates when the caller is not in the lodestone's dimension. */
    private static String where(CommandSourceStack source, WildState.Lodestone l) {
        Vec3 from = viewer(source, l);
        if (from == null) {
            return l.x + ", " + l.y + ", " + l.z;
        }
        return AdminText.distance(distance(from, l)) + " " + AdminText.compass(l.x + 0.5 - from.x, l.z + 0.5 - from.z);
    }

    private static ChatFormatting colourOf(WildState.Lodestone l) {
        if (l.ominous) {
            return ChatFormatting.LIGHT_PURPLE;
        }
        return switch (l.phase) {
            case READY -> ChatFormatting.GREEN;
            case IN_PROGRESS -> ChatFormatting.YELLOW;
            case CLEARED -> ChatFormatting.GRAY;
        };
    }

    private static String name(WildState.Lodestone l) {
        WildDungeons.Offering offering = WildDungeons.byId(l.dungeon);
        return offering == null ? l.dungeon : offering.name();
    }

    /** One list row: the state dot, the id, the dungeon and level, the state, where, and TP and Info. */
    private static MutableComponent row(CommandSourceStack source, WildState.Lodestone l, long now) {
        int inside = WildRun.insideCount(l.id);
        String state = AdminText.state(l, now) + (inside > 0 ? ", " + inside + " inside" : "")
                + (l.ominous ? ", ominous" : "");
        return AdminUi.line(
                AdminUi.text("●", colourOf(l)),
                AdminUi.run(l.id, ROOT + " info " + l.id, "Everything about " + l.id),
                AdminUi.value(name(l)),
                AdminUi.label("L" + l.level),
                AdminUi.text(state, colourOf(l)),
                AdminUi.label(where(source, l)),
                AdminUi.run("TP", ROOT + " tp " + l.id, "Go there"));
    }

    /** Every lodestone, nearest to the caller first (by id for the console). */
    private static List<WildState.Lodestone> sorted(CommandSourceStack source, WildState state) {
        List<WildState.Lodestone> all = new ArrayList<>(state.all());
        ServerPlayer player = source.getPlayer();
        if (player == null) {
            all.sort(Comparator.comparingInt(l -> idNumber(l.id)));
            return all;
        }
        all.sort(Comparator.comparingDouble(l -> {
            Vec3 from = viewer(source, l);
            return from == null ? Double.MAX_VALUE : distance(from, l);
        }));
        return all;
    }

    private static int idNumber(String id) {
        try {
            return Integer.parseInt(id.replaceAll("\\D", ""));
        } catch (NumberFormatException e) {
            return Integer.MAX_VALUE;
        }
    }

    private static WildState.Lodestone find(CommandSourceStack source, String id) {
        WildState state = WildStore.state();
        WildState.Lodestone l = state == null ? null : state.get(id);
        if (l == null) {
            AdminUi.fail(source, state == null ? "Wild mode is off." : "No wild lodestone " + id + ".");
        }
        return l;
    }

    private static ServerLevel levelOf(MinecraftServer server, WildState.Lodestone l) {
        ResourceKey<Level> dimension = ResourceKey.create(Registries.DIMENSION, Identifier.parse(l.dimension));
        return server.getLevel(dimension);
    }

    // ---- the screens ---------------------------------------------------------------------------------------------------

    /** {@code /dungeon admin wild}: the counts, the live dungeons, the nearest lodestone and the main buttons. */
    static int overview(CommandSourceStack source) {
        WildState state = WildStore.state();
        if (state == null) {
            return AdminUi.fail(source, "Wild mode is off.");
        }
        long now = WildClock.now();
        int ready = 0;
        int progress = 0;
        int cooling = 0;
        int ominous = 0;
        int inside = 0;
        for (WildState.Lodestone l : state.all()) {
            switch (l.phase) {
                case READY -> ready++;
                case IN_PROGRESS -> progress++;
                case CLEARED -> cooling++;
            }
            if (l.ominous) {
                ominous++;
            }
            inside += WildRun.insideCount(l.id);
        }
        AdminUi.send(source, AdminUi.title("Wild lodestones"));
        AdminUi.send(source, AdminUi.dotted(List.of(
                AdminUi.value(state.size() + " of " + PocketDungeonsConfig.wildTargetActive() + " standing"),
                AdminUi.text(ready + " Ready", ChatFormatting.GREEN),
                AdminUi.text(progress + " In progress", ChatFormatting.YELLOW),
                AdminUi.text(cooling + " Cooling", ChatFormatting.GRAY),
                AdminUi.text(ominous + " Ominous", ChatFormatting.LIGHT_PURPLE))));
        AdminUi.send(source, AdminUi.dotted(List.of(
                AdminUi.label("Live dungeons ").append(AdminUi.value(WildRun.liveCount() + " of "
                        + PocketDungeonsConfig.wildMaxActive())),
                AdminUi.label("Players inside ").append(AdminUi.value(Integer.toString(inside))),
                AdminUi.label("Collapsed ").append(AdminUi.value(Integer.toString(state.retired().size()))))));
        List<WildState.Lodestone> all = sorted(source, state);
        if (!all.isEmpty() && source.getPlayer() != null && viewer(source, all.get(0)) != null) {
            AdminUi.send(source, AdminUi.label("  Nearest:"));
            AdminUi.send(source, row(source, all.get(0), now));
        }
        AdminUi.send(source, AdminUi.line(
                AdminUi.run("List", ROOT + " list", "Every lodestone, nearest first"),
                AdminUi.run("Near", ROOT + " near", "The eight nearest"),
                AdminUi.run("Offerings", ROOT + " offerings", "The dungeons a lodestone can hold"),
                AdminUi.run("Top up", ROOT + " topup", "Try to place lodestones now"),
                AdminUi.suggest("Place here", ROOT + " place ", "Place one in front of you (level, offering)"),
                AdminUi.run("Help", "/dungeon admin help", "Every admin command")));
        return state.size();
    }

    /** {@code /dungeon admin wild list [filter] [page]}. */
    static int list(CommandSourceStack source, AdminText.Filter filter, int page) {
        WildState state = WildStore.state();
        if (state == null) {
            return AdminUi.fail(source, "Wild mode is off.");
        }
        long now = WildClock.now();
        List<WildState.Lodestone> rows = new ArrayList<>();
        for (WildState.Lodestone l : sorted(source, state)) {
            if (filter.matches(l)) {
                rows.add(l);
            }
        }
        int p = AdminText.clampPage(page, rows.size());
        int pages = AdminText.pages(rows.size());
        AdminUi.send(source, AdminUi.title("Wild lodestones: " + filter.label)
                .append(AdminUi.label("  " + rows.size() + (rows.size() == 1 ? " lodestone" : " lodestones"))));
        MutableComponent filters = AdminUi.line(AdminUi.label("Show"));
        for (AdminText.Filter f : AdminText.Filter.values()) {
            filters.append(" ").append(f == filter ? AdminUi.text(f.label, ChatFormatting.WHITE).withStyle(ChatFormatting.UNDERLINE)
                    : AdminUi.run(f.label, ROOT + " list " + f.word, "Show " + f.label.toLowerCase(java.util.Locale.ROOT)));
        }
        AdminUi.send(source, filters);
        if (rows.isEmpty()) {
            AdminUi.send(source, AdminUi.line(AdminUi.label("None.")));
        }
        for (WildState.Lodestone l : AdminText.page(rows, p)) {
            AdminUi.send(source, row(source, l, now));
        }
        if (pages > 1) {
            String base = ROOT + " list " + filter.word + " ";
            AdminUi.send(source, AdminUi.line(
                    p > 1 ? AdminUi.run("« Prev", base + (p - 1), "Page " + (p - 1)) : AdminUi.label("« Prev"),
                    AdminUi.label("Page " + p + " of " + pages),
                    p < pages ? AdminUi.run("Next »", base + (p + 1), "Page " + (p + 1)) : AdminUi.label("Next »")));
        }
        if (filter == AdminText.Filter.ALL && p == pages && !state.retired().isEmpty()) {
            AdminUi.send(source, AdminUi.label("  Collapsed, with lost and found chests:"));
            for (WildState.Retired r : state.retired()) {
                if (r.chests().isEmpty()) {
                    continue;
                }
                int[] at = r.chests().get(0);
                String go = "/execute in " + r.dimension() + " run tp @s " + at[0] + " " + (at[1] + 1) + " " + at[2];
                AdminUi.send(source, AdminUi.line(AdminUi.label(r.id()),
                        AdminUi.value(r.chests().size() + (r.chests().size() == 1 ? " chest" : " chests")),
                        AdminUi.label(r.x() + ", " + r.y() + ", " + r.z()), AdminUi.run("TP", go, "Go to the chests")));
            }
        }
        return rows.size();
    }

    /** {@code /dungeon admin wild near}: the eight nearest to the caller. */
    static int near(CommandSourceStack source) {
        WildState state = WildStore.state();
        if (state == null) {
            return AdminUi.fail(source, "Wild mode is off.");
        }
        if (source.getPlayer() == null) {
            return AdminUi.fail(source, "Near needs a player; use the list from the console.");
        }
        long now = WildClock.now();
        AdminUi.send(source, AdminUi.title("Nearest wild lodestones"));
        List<WildState.Lodestone> all = sorted(source, state);
        int shown = 0;
        for (WildState.Lodestone l : all) {
            if (shown == 8 || viewer(source, l) == null) {
                break;
            }
            AdminUi.send(source, row(source, l, now));
            shown++;
        }
        if (shown == 0) {
            AdminUi.send(source, AdminUi.line(AdminUi.label("None in this dimension.")));
        }
        return shown;
    }

    /** {@code /dungeon admin wild info <id>}: everything about one lodestone, and its actions. */
    static int info(CommandSourceStack source, String id) {
        WildState.Lodestone l = find(source, id);
        if (l == null) {
            return 0;
        }
        long now = WildClock.now();
        WildDungeons.Offering offering = WildDungeons.byId(l.dungeon);
        AdminUi.send(source, AdminUi.title(l.id + "  " + name(l)));
        if (offering != null) {
            AdminUi.send(source, AdminUi.line(AdminUi.text(offering.promise(), ChatFormatting.ITALIC)));
            AdminUi.send(source, AdminUi.dotted(List.of(
                    AdminUi.label("Kind ").append(AdminUi.value(offering.type().replace('_', ' ') + " (" + offering.variety() + ")")),
                    AdminUi.label("Level ").append(AdminUi.value(Integer.toString(l.level))),
                    AdminUi.label("Cooldown ").append(AdminUi.value(WildText.duration(offering.cooldownMinutes() * 60L))))));
        }
        AdminUi.send(source, AdminUi.dotted(List.of(
                AdminUi.label("At ").append(AdminUi.value(l.x + ", " + l.y + ", " + l.z)),
                AdminUi.label(where(source, l)))));
        List<Component> state = new ArrayList<>();
        state.add(AdminUi.text(AdminText.state(l, now), colourOf(l)));
        if (l.ominous) {
            state.add(AdminUi.text("Ominous", ChatFormatting.LIGHT_PURPLE));
        }
        state.add(AdminUi.label("Collapses in ").append(AdminUi.value(WildText.duration(l.expiresAt - now))));
        AdminUi.send(source, AdminUi.dotted(state));
        InstanceRecord record = WildRun.liveRecord(l.id);
        if (record != null && !record.members.isEmpty()) {
            List<String> names = new ArrayList<>();
            for (UUID member : record.members.keySet()) {
                ServerPlayer p = source.getServer().getPlayerList().getPlayer(member);
                names.add(p == null ? "(offline)" : p.getName().getString());
            }
            AdminUi.send(source, AdminUi.line(AdminUi.label("Inside:"), AdminUi.value(String.join(", ", names))));
        }
        String last = WildText.lastCleared(l.lastClearedBy, now - l.lastClearedAt);
        if (last != null) {
            AdminUi.send(source, AdminUi.line(Component.literal(last).withStyle(style -> l.lastClearedColour >= 0
                    ? style.withColor(l.lastClearedColour) : style.withColor(ChatFormatting.GRAY))));
        }
        AdminUi.send(source, AdminUi.dotted(List.of(
                AdminUi.label("Ruin ").append(AdminUi.value(l.ruin >= WildRuin.VERSION ? "current" : "old 5 by 5")),
                AdminUi.label("Lost and found ").append(AdminUi.value(l.lostAndFound.size()
                        + (l.lostAndFound.size() == 1 ? " chest" : " chests"))))));
        String self = ROOT + " ";
        AdminUi.send(source, AdminUi.line(
                AdminUi.run("TP", self + "tp " + l.id, "Go there"),
                l.ominous ? AdminUi.run("Omen off", self + "omen " + l.id + " off", "Take the omen back")
                        : AdminUi.run("Omen on", self + "omen " + l.id + " on", "Make the next run ominous"),
                AdminUi.run("Rebuild ruin", self + "rebuild " + l.id, "Build the current ruin around it; player blocks stay"),
                AdminUi.danger("Reset", self + "reset " + l.id + " confirm", "Close its dungeon now and make it Ready"),
                AdminUi.danger("Collapse", self + "collapse " + l.id + " confirm", "Collapse it now"),
                AdminUi.danger("Remove", self + "remove " + l.id + " confirm", "Remove it and its ruin")));
        return 1;
    }

    /** {@code /dungeon admin wild offerings}: what the lodestones can hold. */
    static int offerings(CommandSourceStack source) {
        List<WildDungeons.Offering> all = new ArrayList<>(WildDungeons.all());
        all.sort(Comparator.comparingInt(WildDungeons.Offering::minDistance).thenComparing(WildDungeons.Offering::id));
        AdminUi.send(source, AdminUi.title("Wild offerings").append(AdminUi.label("  " + all.size() + " loaded")));
        for (WildDungeons.Offering o : all) {
            AdminUi.send(source, AdminUi.line(
                    AdminUi.value(o.name()),
                    AdminUi.label(o.type().replace('_', ' ') + ", levels to " + o.maxLevel() + ", from "
                            + AdminText.distance(o.minDistance()) + ", weight " + o.weight() + ", cools "
                            + WildText.duration(o.cooldownMinutes() * 60L)),
                    AdminUi.suggest("Place", ROOT + " place 1 " + o.id(), "Place one in front of you; set the level")));
        }
        return all.size();
    }

    // ---- the actions ---------------------------------------------------------------------------------------------------

    /** The warning a destructive action shows before it is confirmed, with a button that types the confirm. */
    private static int ask(CommandSourceStack source, String id, String verb, String effect) {
        WildState.Lodestone l = find(source, id);
        if (l == null) {
            return 0;
        }
        AdminUi.send(source, AdminUi.title(Character.toUpperCase(verb.charAt(0)) + verb.substring(1) + " " + l.id + "?"));
        int inside = WildRun.insideCount(l.id);
        AdminUi.send(source, AdminUi.line(AdminUi.label("This " + effect + "."
                + (inside > 0 ? " " + inside + " inside now." : ""))));
        AdminUi.send(source, AdminUi.line(
                AdminUi.danger("Confirm", ROOT + " " + verb + " " + l.id + " confirm", "Puts the command in chat; send it"),
                AdminUi.run("Back", ROOT + " info " + l.id, "Back to " + l.id)));
        return 1;
    }

    /** Places a lodestone three blocks in front of the caller. {@code levelOverride} 0 means by distance from spawn. */
    private static int place(CommandSourceStack source, int levelOverride, String offeringId) {
        WildState state = WildStore.state();
        ServerPlayer player = source.getPlayer();
        if (state == null || player == null) {
            return AdminUi.fail(source, "Wild mode is off, or there is no player.");
        }
        if (!(player.level() instanceof ServerLevel level) || DungeonWorldRules.inDungeon(level)) {
            return AdminUi.fail(source, "Stand in the overworld.");
        }
        BlockPos pos = player.blockPosition().relative(player.getDirection(), 3);
        double distance = Math.sqrt(pos.distSqr(source.getServer().overworld().getRespawnData().pos()));
        String wanted = offeringId == null ? null : offeringId.trim();
        WildDungeons.Offering offering = wanted != null ? WildDungeons.byId(wanted)
                : WildDungeons.pick(WildDungeons.all(), distance, RANDOM);
        if (offering == null) {
            return AdminUi.fail(source, wanted != null ? "No offering " + wanted + "; see " + ROOT + " offerings."
                    : "No wild dungeon offering fits. Loaded: " + WildDungeons.all().size() + ".");
        }
        int lodestoneLevel = levelOverride > 0 ? levelOverride : WildLevel.of(distance, offering.maxLevel());
        long now = WildClock.now();
        String newId = state.nextId();
        WildState.Lodestone lodestone = new WildState.Lodestone(newId, WildLodestones.dimensionKey(level),
                pos.getX(), pos.getY(), pos.getZ(), offering.id(), lodestoneLevel, now,
                WildLifespan.expiresAt(now, PocketDungeonsConfig.wildLifespanDays(), newId));
        WildLodestones.stamp(level, pos, WildRuin.Theme.of(offering.variety(), offering.type()));
        lodestone.ruin = WildRuin.VERSION;
        state.add(lodestone);
        WildStore.save();
        source.sendSuccess(() -> AdminUi.line(AdminUi.text("Placed", ChatFormatting.GREEN),
                AdminUi.run(lodestone.id, ROOT + " info " + lodestone.id, "Everything about it"),
                AdminUi.value(offering.name()), AdminUi.label("level " + lodestoneLevel + " at " + pos.toShortString())), true);
        return 1;
    }

    private static int tp(CommandSourceStack source, String id) {
        WildState.Lodestone l = find(source, id);
        ServerPlayer player = source.getPlayer();
        if (l == null) {
            return 0;
        }
        if (player == null) {
            return AdminUi.fail(source, "Only a player can teleport.");
        }
        ResourceKey<Level> dimension = ResourceKey.create(Registries.DIMENSION, Identifier.parse(l.dimension));
        ServerLevel level = source.getServer().getLevel(dimension);
        ReturnPoint safe = level == null ? null : WildReset.safePoint(level, l);
        // A safe spot beside it, else in front of the entrance outside the plaza; either way facing the lodestone.
        Vec3 at = safe != null ? safe.pos() : new Vec3(l.x + 0.5, l.y, l.z + WildRuin.RADIUS + 1.5);
        float yaw = (float) Math.toDegrees(Math.atan2(-(l.x + 0.5 - at.x), l.z + 0.5 - at.z));
        Instances.teleport(source.getServer(), player, dimension, at, yaw, 0.0F);
        return 1;
    }

    private static int omen(CommandSourceStack source, String id, boolean on) {
        WildState.Lodestone l = find(source, id);
        if (l == null) {
            return 0;
        }
        if (on && l.phase != WildState.Phase.READY) {
            return AdminUi.fail(source, l.id + " is not Ready; the clear would spend the omen. Reset it first.");
        }
        l.ominous = on;
        WildStore.save();
        source.sendSuccess(() -> AdminUi.line(AdminUi.text(on ? "Ominous now:" : "No longer ominous:",
                on ? ChatFormatting.LIGHT_PURPLE : ChatFormatting.GREEN), AdminUi.value(l.id + " " + name(l))), true);
        return 1;
    }

    private static int reset(CommandSourceStack source, String id) {
        WildState.Lodestone l = find(source, id);
        if (l == null) {
            return 0;
        }
        InstanceRecord record = WildRun.liveRecord(l.id);
        if (record == null && l.phase == WildState.Phase.READY) {
            return AdminUi.fail(source, l.id + " is already Ready.");
        }
        WildReset.reset(source.getServer(), l, record);
        source.sendSuccess(() -> AdminUi.line(AdminUi.text("Reset", ChatFormatting.GREEN),
                AdminUi.value(l.id + " " + name(l)), AdminUi.label("is Ready.")), true);
        return 1;
    }

    private static int collapse(CommandSourceStack source, String id) {
        WildState.Lodestone l = find(source, id);
        if (l == null) {
            return 0;
        }
        WildCollapse.collapse(source.getServer(), l);
        source.sendSuccess(() -> AdminUi.line(AdminUi.text("Collapsed", ChatFormatting.GOLD),
                AdminUi.value(l.id + " " + name(l)), AdminUi.label("A new lodestone will appear elsewhere.")), true);
        return 1;
    }

    private static int remove(CommandSourceStack source, String id) {
        WildState state = WildStore.state();
        WildState.Lodestone l = find(source, id);
        if (l == null) {
            return 0;
        }
        if (WildRun.insideCount(id) > 0) {
            return AdminUi.fail(source, id + " has players inside; reset it first.");
        }
        ServerLevel level = levelOf(source.getServer(), l);
        if (level != null) {
            WildLodestones.rubble(level, new BlockPos(l.x, l.y, l.z), l.ruin);
        }
        WildLodestones.forgetExits(l.id);
        state.remove(id);
        WildStore.save();
        source.sendSuccess(() -> AdminUi.line(AdminUi.text("Removed", ChatFormatting.GOLD), AdminUi.value(id)), true);
        return 1;
    }

    /** Builds the current ruin around a lodestone; a player's blocks there stay. */
    static int rebuild(CommandSourceStack source, String id, boolean tell) {
        WildState.Lodestone l = find(source, id);
        if (l == null) {
            return 0;
        }
        ServerLevel level = levelOf(source.getServer(), l);
        if (level == null) {
            return AdminUi.fail(source, "The dimension of " + l.id + " is not loaded.");
        }
        BlockPos pos = new BlockPos(l.x, l.y, l.z);
        level.getChunk(pos.getX() >> 4, pos.getZ() >> 4);
        WildLodestones.stamp(level, pos, WildLodestones.themeOf(l.dungeon));
        l.ruin = WildRuin.VERSION;
        WildBanner.ensure(level, l);
        WildStore.save();
        if (tell) {
            source.sendSuccess(() -> AdminUi.line(AdminUi.text("Rebuilt", ChatFormatting.GREEN),
                    AdminUi.value(l.id + " " + name(l))), true);
        }
        return 1;
    }

    private static int rebuildAll(CommandSourceStack source) {
        WildState state = WildStore.state();
        if (state == null) {
            return AdminUi.fail(source, "Wild mode is off.");
        }
        int done = 0;
        for (WildState.Lodestone l : new ArrayList<>(state.all())) {
            if (l.ruin < WildRuin.VERSION) {
                done += rebuild(source, l.id, false);
            }
        }
        int count = done;
        source.sendSuccess(() -> AdminUi.line(AdminUi.text("Rebuilt", ChatFormatting.GREEN),
                AdminUi.value(count + (count == 1 ? " old ruin" : " old ruins"))), true);
        return done;
    }

    private static int topUp(CommandSourceStack source, int attempts) {
        WildState state = WildStore.state();
        if (state == null) {
            return AdminUi.fail(source, "Wild mode is off.");
        }
        int target = PocketDungeonsConfig.wildTargetActive();
        int placed = 0;
        for (int i = 0; i < attempts && state.size() < target; i++) {
            if (WildTopUp.attempt(source.getServer(), source.getServer().overworld(), state) != null) {
                placed++;
            }
        }
        int count = placed;
        source.sendSuccess(() -> AdminUi.line(AdminUi.text("Placed " + count, ChatFormatting.GREEN),
                AdminUi.label("in " + attempts + " attempts. " + state.size() + " of " + target + " stand."),
                AdminUi.run("List", ROOT + " list", "Every lodestone")), true);
        return placed;
    }
}
