package charter.mc.cmd;

import charter.engine.Actor;
import charter.engine.Outcome;
import charter.engine.Reason;
import charter.gov.Preset;
import charter.id.ChunkKey;
import charter.id.OrgId;
import charter.id.PlayerId;
import charter.land.CampPhase;
import charter.land.Claim;
import charter.land.ClaimKind;
import charter.land.Core;
import charter.land.actions.ClaimLand;
import charter.land.actions.Unclaim;
import charter.mc.CharterRuntime;
import charter.mc.CharterServer;
import charter.mc.Text;
import charter.mc.land.LandItems;
import charter.mc.land.Protection;
import charter.org.OrgStatus;
import charter.org.actions.Found;
import charter.siege.SiegeRules;
import charter.time.Durations;
import charter.treasury.Currency;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.level.ServerPlayer;

import java.util.List;
import java.util.Optional;

/**
 * Land on the typed surface (PLAN.md §7.3): the clickable chunk map, and the
 * operator tools {@code faction spawn}, {@code camp raise-now}, {@code perimeter
 * show} and {@code give}. Registered as a second {@code /charter} tree that
 * Brigadier merges with the one in {@link CharterCommands}.
 *
 * <pre>
 *   /charter map                          11 by 11 chunks around you; click to claim or unclaim
 *   /charter admin faction spawn &lt;name&gt; &lt;player&gt;   a ready lordship, funded, with a nexus and a banner for the player
 *   /charter admin camp raise-now         finish raising the nearest raising camp
 *   /charter admin perimeter show         the sides of the nearest keep or camp, open and closed
 *   /charter admin give nexus|banner|book [player]
 * </pre>
 */
public final class LandCommands {

    public static final int MAP_RADIUS = 5;

    private LandCommands() {
    }

    public static void register() {
        CommandRegistrationCallback.EVENT.register((dispatcher, registry, environment) -> dispatcher.register(
                Commands.literal("charter")
                        .then(Commands.literal("map").executes(ctx -> map(ctx)))
                        .then(admin())));
    }

    private static LiteralArgumentBuilder<CommandSourceStack> admin() {
        return Commands.literal("admin")
                .requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                .then(Commands.literal("faction")
                        .then(Commands.literal("spawn")
                                .then(Commands.argument("name", StringArgumentType.string())
                                        .then(Commands.argument("player", StringArgumentType.string())
                                                .suggests((c, b) -> SharedSuggestionProvider.suggest(c.getSource().getOnlinePlayerNames(), b))
                                                .executes(LandCommands::spawn)))))
                .then(Commands.literal("camp")
                        .then(Commands.literal("raise-now").executes(LandCommands::raiseNow)))
                .then(Commands.literal("perimeter")
                        .then(Commands.literal("show").executes(LandCommands::perimeter)))
                .then(Commands.literal("give")
                        .then(Commands.argument("what", StringArgumentType.word())
                                .suggests((c, b) -> SharedSuggestionProvider.suggest(new String[]{"nexus", "banner", "book"}, b))
                                .executes(ctx -> give(ctx, ctx.getSource().getPlayerOrException()))
                                .then(Commands.argument("player", StringArgumentType.string())
                                        .suggests((c, b) -> SharedSuggestionProvider.suggest(c.getSource().getOnlinePlayerNames(), b))
                                        .executes(ctx -> give(ctx, online(ctx))))));
    }

    private static CharterRuntime rt() {
        return CharterServer.runtime();
    }

    // ---- the map ----

    private static int map(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        if (!CharterServer.running()) return 0;
        showMap(player, player.chunkPosition().x(), player.chunkPosition().z());
        return 1;
    }

    /** Draws the map centred on a chunk. Public so a gametest can open it at a chosen place. */
    public static void showMap(ServerPlayer player, int centerX, int centerZ) {
        CharterRuntime rt = rt();
        String dimension = Protection.dimension(player.level());
        OrgId home = rt.state.membership(new PlayerId(player.getUUID())).map(m -> rt.state.root(m.org())).orElse(null);
        int hereX = player.chunkPosition().x();
        int hereZ = player.chunkPosition().z();

        player.sendSystemMessage(Chat.header(Text.get("map.header", "x", centerX, "z", centerZ)));
        for (int dz = -MAP_RADIUS; dz <= MAP_RADIUS; dz++) {
            MutableComponent row = Component.empty();
            for (int dx = -MAP_RADIUS; dx <= MAP_RADIUS; dx++) {
                row.append(cell(rt, player, home, new ChunkKey(dimension, centerX + dx, centerZ + dz), hereX, hereZ));
            }
            player.sendSystemMessage(row);
        }
        player.sendSystemMessage(Chat.dim(Text.get("map.legend")));
        MutableComponent moves = Component.literal("  ");
        moves.append(recentre(player, "map.north", centerX, centerZ - 2 * MAP_RADIUS - 1)).append(" ");
        moves.append(recentre(player, "map.south", centerX, centerZ + 2 * MAP_RADIUS + 1)).append(" ");
        moves.append(recentre(player, "map.west", centerX - 2 * MAP_RADIUS - 1, centerZ)).append(" ");
        moves.append(recentre(player, "map.east", centerX + 2 * MAP_RADIUS + 1, centerZ));
        player.sendSystemMessage(moves);
    }

    private static MutableComponent recentre(ServerPlayer player, String key, int x, int z) {
        return Chat.act(player, Text.get(key), Text.get("map.recentre_hover", "x", x, "z", z), p -> showMap(p, x, z));
    }

    /** One chunk: a symbol, coloured by who holds it, with a hover and, where an action applies, a click. */
    private static MutableComponent cell(CharterRuntime rt, ServerPlayer player, OrgId home, ChunkKey chunk, int hereX, int hereZ) {
        var ref = rt.claims.at(chunk);
        boolean here = chunk.x() == hereX && chunk.z() == hereZ;
        String symbol;
        ChatFormatting colour;
        String hover;
        Runnable click = null;
        String label = null;
        if (ref == null) {
            boolean beside = home != null && java.util.stream.Stream.of(chunk.offset(1, 0), chunk.offset(-1, 0), chunk.offset(0, 1), chunk.offset(0, -1))
                    .anyMatch(n -> {
                        var next = rt.claims.at(n);
                        return next != null && rt.state.root(next.owner()).equals(home);
                    });
            symbol = beside ? "+" : ".";
            colour = beside ? ChatFormatting.YELLOW : ChatFormatting.DARK_GRAY;
            hover = Text.get(beside ? "map.free_claimable" : "map.free", "x", chunk.x(), "z", chunk.z());
            if (beside) {
                label = "claim " + chunk.x() + "," + chunk.z();
                click = () -> claim(player, chunk);
            }
        } else {
            boolean mine = home != null && rt.state.root(ref.owner()).equals(home);
            symbol = switch (ref.kind()) {
                case KEEP_CORE -> "K";
                case KEEP_RING -> "k";
                case CAMP -> "C";
                case OUTLYING -> "o";
            };
            colour = mine ? ChatFormatting.GREEN : ChatFormatting.RED;
            Claim claim = rt.state.claim(ref.claim()).orElse(null);
            hover = Text.get("map.claimed", "owner", rt.describe.org(ref.owner()), "kind", kindName(ref.kind()),
                    "detail", claim == null ? "" : detail(rt, claim));
            if (mine && ref.kind() == ClaimKind.OUTLYING) {
                label = "unclaim " + chunk.x() + "," + chunk.z();
                click = () -> unclaim(player, chunk);
                hover += " " + Text.get("map.click_unclaim");
            }
        }
        final String text = here ? "@" : symbol;
        MutableComponent out = Component.literal(" " + text + " ").withStyle(colour);
        if (here) out.withStyle(ChatFormatting.BOLD);
        final String finalHover = hover;
        if (click != null) {
            Runnable run = click;
            String token = CharterCommands.TOKENS.issue(player.getUUID(), label, System.currentTimeMillis(), p -> run.run());
            return out.withStyle(style -> style
                    .withClickEvent(new ClickEvent.RunCommand("/charter act " + token))
                    .withHoverEvent(new HoverEvent.ShowText(Component.literal(finalHover))));
        }
        return out.withStyle(style -> style.withHoverEvent(new HoverEvent.ShowText(Component.literal(finalHover))));
    }

    private static String kindName(ClaimKind kind) {
        return Text.get("map.kind." + kind.name().toLowerCase(java.util.Locale.ROOT));
    }

    /** The hold left on a farm, the phase of a camp, or nothing. */
    private static String detail(CharterRuntime rt, Claim claim) {
        if (claim.contested()) return Text.get("map.contested");
        if (claim.kind() == ClaimKind.OUTLYING) {
            Long left = rt.state.siege.holds.get(claim.id);
            if (left != null) return Text.get("map.hold", "time", Chat.duration(left));
        }
        if (claim.kind() == ClaimKind.CAMP && claim.core() != null) {
            return rt.state.core(claim.core()).map(c -> c.phase() == CampPhase.RAISING ? Text.get("map.raising") : "").orElse("");
        }
        return "";
    }

    private static void claim(ServerPlayer player, ChunkKey chunk) {
        CharterRuntime rt = rt();
        Optional<OrgId> org = CharterCommands.orgOf(rt, player);
        if (org.isEmpty()) {
            player.sendSystemMessage(Chat.bad(Text.get("page.no_org")));
            return;
        }
        int done = CharterCommands.perform(player, new ClaimLand(org.get(), chunk), Text.get("map.claimed_done", "x", chunk.x(), "z", chunk.z()));
        if (done == 1) showMap(player, player.chunkPosition().x(), player.chunkPosition().z());
    }

    private static void unclaim(ServerPlayer player, ChunkKey chunk) {
        CharterRuntime rt = rt();
        Optional<OrgId> org = CharterCommands.orgOf(rt, player);
        if (org.isEmpty()) {
            player.sendSystemMessage(Chat.bad(Text.get("page.no_org")));
            return;
        }
        int done = CharterCommands.perform(player, new Unclaim(org.get(), chunk), Text.get("map.unclaimed_done", "x", chunk.x(), "z", chunk.z()));
        if (done == 1) showMap(player, player.chunkPosition().x(), player.chunkPosition().z());
    }

    // ---- operator tools ----

    private static int spawn(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        CharterRuntime rt = rt();
        String name = StringArgumentType.getString(ctx, "name");
        ServerPlayer founder = online(ctx);
        if (founder == null) return fail(ctx, Text.get("land.admin.no_player", "name", StringArgumentType.getString(ctx, "player")));
        PlayerId id = new PlayerId(founder.getUUID());
        rt.state.touch(id, founder.getPlainTextName(), rt.clock.now());
        Outcome outcome = rt.engine.run(new Actor.Admin(ctx.getSource().getTextName()), new Found(id, name, Preset.LORDSHIP));
        if (outcome instanceof Outcome.Refused(Reason reason)) return fail(ctx, Chat.reason(reason));
        OrgId org = rt.state.membership(id).orElseThrow().org();
        rt.state.setStatus(org, OrgStatus.ACTIVE);
        var ledger = rt.state.org(org).orElseThrow().ledger;
        ledger.add(Currency.DIAMOND, rt.knobs.keepPriceDiamonds);
        ledger.add(Currency.COBBLESTONE, rt.knobs.warBannerPriceCobblestone);
        founder.getInventory().add(LandItems.nexus());
        founder.getInventory().add(LandItems.warBanner());
        return ok(ctx, Text.get("land.admin.spawned", "name", name.strip(), "player", founder.getPlainTextName()));
    }

    /** The online player a command names, by name or UUID (mock players share a name), or null. */
    private static ServerPlayer online(CommandContext<CommandSourceStack> ctx) {
        String name = StringArgumentType.getString(ctx, "player");
        var players = ctx.getSource().getServer().getPlayerList();
        ServerPlayer found = players.getPlayer(name);
        if (found == null) {
            try {
                found = players.getPlayer(java.util.UUID.fromString(name));
            } catch (IllegalArgumentException notAUuid) {
                // a name that is not online
            }
        }
        return found;
    }

    private static int raiseNow(CommandContext<CommandSourceStack> ctx) {
        CharterRuntime rt = rt();
        Core camp = nearest(ctx, false);
        if (camp == null) return fail(ctx, Text.get("land.admin.no_camp"));
        long raising = Durations.hours(rt.knobs.campRaisingHours);
        long left = Math.max(1, raising - (rt.state.siegeClock.now() - camp.phaseSince()));
        // The camp finishes in a normal sim pass, so everything else moves on by the same time.
        rt.sim.advance(left);
        return ok(ctx, Text.get("land.admin.raised", "camp", rt.describe.core(camp.id), "by", Chat.duration(left)));
    }

    private static int perimeter(CommandContext<CommandSourceStack> ctx) {
        CharterRuntime rt = rt();
        Core core = nearest(ctx, true);
        if (core == null) return fail(ctx, Text.get("land.admin.no_core"));
        SiegeRules rules = rt.sim.rules();
        var perimeter = core.isKeep() ? rules.keepPerimeter(core, rules.countingCamps(core)) : rules.campPerimeter(core);
        List<ChunkKey> chunks = perimeter.chunks();
        ctx.getSource().sendSuccess(() -> Chat.header(Text.get("land.admin.perimeter", "core", rt.describe.core(core.id),
                "open", perimeter.open().size(), "total", chunks.size())), false);
        for (ChunkKey chunk : chunks) {
            String owner = rt.claims.at(chunk) == null ? Text.get("land.admin.unclaimed") : rt.describe.org(rt.claims.at(chunk).owner());
            String state = perimeter.isClosed(chunk) ? Text.get("land.admin.closed") : Text.get("land.admin.open");
            ctx.getSource().sendSuccess(() -> Chat.line("  " + chunk.x() + ", " + chunk.z() + ": " + state + " (" + owner + ")"), false);
        }
        return ok(ctx, Text.get("land.admin.perimeter_done"));
    }

    private static int give(CommandContext<CommandSourceStack> ctx, ServerPlayer target) {
        if (target == null) return fail(ctx, Text.get("land.admin.no_player", "name", StringArgumentType.getString(ctx, "player")));
        String what = StringArgumentType.getString(ctx, "what");
        switch (what) {
            case "nexus" -> target.getInventory().add(LandItems.nexus());
            case "banner" -> target.getInventory().add(LandItems.warBanner());
            case "book" -> target.getInventory().add(charter.mc.book.CharterBook.create());
            default -> {
                return fail(ctx, Text.get("land.admin.bad_item"));
            }
        }
        return ok(ctx, Text.get("land.admin.gave", "item", what, "player", target.getPlainTextName()));
    }

    /** The core nearest the sender: any core when {@code any}, else only a camp still raising. Null when there is none. */
    private static Core nearest(CommandContext<CommandSourceStack> ctx, boolean any) {
        CharterRuntime rt = rt();
        var pos = ctx.getSource().getPosition();
        Core best = null;
        double bestDistance = Double.MAX_VALUE;
        for (Core core : rt.state.cores()) {
            if (!any && (core.isKeep() || core.phase() != CampPhase.RAISING)) continue;
            double dx = core.center.x() * 16.0 + 8 - pos.x;
            double dz = core.center.z() * 16.0 + 8 - pos.z;
            double d = dx * dx + dz * dz;
            if (d < bestDistance) {
                bestDistance = d;
                best = core;
            }
        }
        return best;
    }

    private static int ok(CommandContext<CommandSourceStack> ctx, String text) {
        rt().siegeLog.admin(rt().clock.now(), ctx.getSource().getTextName(), ctx.getInput());
        ctx.getSource().sendSuccess(() -> Chat.good(text), true);
        return 1;
    }

    private static int fail(CommandContext<CommandSourceStack> ctx, String text) {
        ctx.getSource().sendFailure(Chat.bad(text));
        return 0;
    }
}
