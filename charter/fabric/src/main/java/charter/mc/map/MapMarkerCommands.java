package charter.mc.map;

import charter.api.MapMarkers;
import charter.mc.CharterServer;
import charter.mc.Text;
import charter.mc.cmd.Chat;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.world.phys.Vec3;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The operator's map marker tools, for testing markers before another mod (supply drops) adds its own through
 * {@link MapMarkers}. A separate {@code /charter admin} tree that Brigadier merges with the others.
 *
 * <pre>
 *   /charter admin mapmarker add &lt;id&gt; &lt;kind&gt; &lt;colour&gt; &lt;minutes&gt; &lt;label&gt;
 *        a marker where you stand; colour is six hex digits (FFAA00) or a name, minutes 0 for no expiry
 *   /charter admin mapmarker remove &lt;id&gt;
 *   /charter admin mapmarker list          every marker, and how many players receive the map
 * </pre>
 */
public final class MapMarkerCommands {

    /** Colour names the command knows, besides six hex digits. */
    static final Map<String, Integer> COLOURS = new LinkedHashMap<>();

    static {
        COLOURS.put("gold", 0xFFAA00);
        COLOURS.put("yellow", 0xFFFF55);
        COLOURS.put("orange", 0xFF8C00);
        COLOURS.put("red", 0xFF5555);
        COLOURS.put("green", 0x55FF55);
        COLOURS.put("aqua", 0x55FFFF);
        COLOURS.put("blue", 0x5555FF);
        COLOURS.put("purple", 0xAA00AA);
        COLOURS.put("pink", 0xFF69B4);
        COLOURS.put("white", 0xFFFFFF);
    }

    private MapMarkerCommands() {
    }

    static void register() {
        CommandRegistrationCallback.EVENT.register((dispatcher, registry, environment) -> dispatcher.register(
                Commands.literal("charter")
                        .then(Commands.literal("admin")
                                .requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                                .then(Commands.literal("mapmarker")
                                        .requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                                        .then(Commands.literal("add")
                                                .then(Commands.argument("id", StringArgumentType.word())
                                                        .then(Commands.argument("kind", StringArgumentType.word())
                                                                .then(Commands.argument("colour", StringArgumentType.word())
                                                                        .then(Commands.argument("minutes", IntegerArgumentType.integer(0, 60 * 24 * 30))
                                                                                .then(Commands.argument("label", StringArgumentType.greedyString())
                                                                                        .executes(MapMarkerCommands::add)))))))
                                        .then(Commands.literal("remove")
                                                .then(Commands.argument("id", StringArgumentType.word())
                                                        .executes(MapMarkerCommands::remove)))
                                        .then(Commands.literal("list").executes(MapMarkerCommands::list))))));
    }

    /** Six hex digits, with or without a leading #, or a known name; -1 when it is neither. */
    static int colour(String text) {
        Integer named = COLOURS.get(text.toLowerCase(Locale.ROOT));
        if (named != null) return named;
        return MapView.parseColour(text);
    }

    private static int add(CommandContext<CommandSourceStack> ctx) {
        if (!CharterServer.running()) return fail(ctx, Text.get("teleport.not_running"));
        String id = StringArgumentType.getString(ctx, "id");
        String kind = StringArgumentType.getString(ctx, "kind");
        String colourText = StringArgumentType.getString(ctx, "colour");
        int minutes = IntegerArgumentType.getInteger(ctx, "minutes");
        String label = StringArgumentType.getString(ctx, "label");
        int colour = colour(colourText);
        if (colour < 0) return fail(ctx, Text.get("mapmarker.bad_colour", "colour", colourText, "names", String.join(", ", COLOURS.keySet())));
        Vec3 at = ctx.getSource().getPosition();
        String dimension = ctx.getSource().getLevel().dimension().identifier().toString();
        long expires = minutes == 0 ? 0 : CharterServer.runtime().clock.now() + minutes * 60_000L;
        MapMarkers.Marker marker = new MapMarkers.Marker(id, kind, label, dimension,
                (int) Math.floor(at.x), (int) Math.floor(at.y), (int) Math.floor(at.z), colour, "", expires);
        if (!MapMarkers.add(marker)) return fail(ctx, Text.get("mapmarker.refused"));
        String line = Text.get("mapmarker.added", "id", id, "x", marker.x(), "y", marker.y(), "z", marker.z(), "dimension", dimension)
                + (minutes == 0 ? "" : " " + Text.get("mapmarker.added_timed", "minutes", minutes));
        ctx.getSource().sendSuccess(() -> Chat.good(line), true);
        return 1;
    }

    private static int remove(CommandContext<CommandSourceStack> ctx) {
        String id = StringArgumentType.getString(ctx, "id");
        if (!MapMarkers.remove(id)) return fail(ctx, Text.get("mapmarker.none", "id", id));
        ctx.getSource().sendSuccess(() -> Chat.good(Text.get("mapmarker.removed", "id", id)), true);
        return 1;
    }

    private static int list(CommandContext<CommandSourceStack> ctx) {
        if (!CharterServer.running()) return fail(ctx, Text.get("teleport.not_running"));
        long now = CharterServer.runtime().clock.now();
        List<MapMarkers.Marker> all = MapMarkers.list();
        int players = MapSync.receivers();
        if (!CharterServer.runtime().knobs.mapSyncEnabled) {
            ctx.getSource().sendSuccess(() -> Chat.line(Text.get("mapmarker.sync_off")), false);
        } else if (!CharterServer.runtime().knobs.mapSyncMarkers) {
            ctx.getSource().sendSuccess(() -> Chat.line(Text.get("mapmarker.markers_off")), false);
        }
        if (all.isEmpty()) {
            ctx.getSource().sendSuccess(() -> Chat.line(Text.get("mapmarker.list_none", "players", players)), false);
            return 0;
        }
        ctx.getSource().sendSuccess(() -> Chat.header(Text.get("mapmarker.list_head", "count", all.size(), "players", players)), false);
        for (MapMarkers.Marker m : all) {
            String left = m.expiresAt() == 0 ? Text.get("mapmarker.never")
                    : Text.get("mapmarker.left", "left", Chat.duration(Math.max(0, m.expiresAt() - now)));
            String text = Text.get("mapmarker.list_line", "id", m.id(), "kind", m.kind(), "label", m.label(),
                    "x", m.x(), "y", m.y(), "z", m.z(), "dimension", m.dimension(), "left", left);
            ctx.getSource().sendSuccess(() -> Chat.line(text), false);
        }
        return all.size();
    }

    private static int fail(CommandContext<CommandSourceStack> ctx, String text) {
        ctx.getSource().sendFailure(Chat.bad(text));
        return 0;
    }
}
