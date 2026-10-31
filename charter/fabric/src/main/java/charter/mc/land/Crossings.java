package charter.mc.land;

import charter.mc.CharterRuntime;
import charter.mc.CharterServer;
import charter.mc.Text;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Tells a player on the action bar when they cross into a kingdom, an outpost, other held land or the wild, like the
 * town and faction plugins do. A line shows only when the kind of place or its owner changes, and once when they join.
 */
public final class Crossings {

    public static final int EVERY_TICKS = 10;

    private static final Map<UUID, String> LAST = new HashMap<>();
    private static long ticks;

    private Crossings() {
    }

    public static void register() {
        ServerTickEvents.END_SERVER_TICK.register(Crossings::tick);
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> LAST.remove(handler.player.getUUID()));
    }

    private static void tick(MinecraftServer server) {
        if (!CharterServer.running()) return;
        if (++ticks % EVERY_TICKS != 0) return;
        CharterRuntime rt = CharterServer.runtime();
        if (!rt.knobs.entryMessages) return;
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            Place place = placeOf(rt, player);
            if (place.key().equals(LAST.put(player.getUUID(), place.key()))) continue;
            player.sendSystemMessage(message(place), true);
        }
    }

    /** What a player is standing on: a kind of holding and the kingdom that owns it, or the wild. */
    record Place(Kind kind, String kingdom) {
        enum Kind { WILD, KEEP, OUTPOST, CLAIM }

        String key() {
            return kind + ":" + kingdom;
        }
    }

    static Place placeOf(CharterRuntime rt, ServerPlayer player) {
        var ref = rt.claims.at(Protection.dimension(player.level()), player.chunkPosition().x(), player.chunkPosition().z());
        if (ref == null) return new Place(Place.Kind.WILD, "");
        String kingdom = rt.state.org(rt.state.root(ref.owner())).map(charter.org.Organization::name).orElse("?");
        Place.Kind kind = switch (ref.kind()) {
            case KEEP_CORE, KEEP_RING -> Place.Kind.KEEP;
            case CAMP -> Place.Kind.OUTPOST;
            case OUTLYING -> Place.Kind.CLAIM;
        };
        return new Place(kind, kingdom);
    }

    /** The line for a place, coloured: gold for a keep, yellow for an outpost or other held land, green for the wild. */
    static Component message(Place place) {
        CharterRuntime rt = CharterServer.runtime();
        return switch (place.kind()) {
            case WILD -> Component.literal(Text.get("entry.wild")).withStyle(ChatFormatting.GREEN);
            case KEEP -> Component.literal(Text.get("entry.keep", "kingdom", place.kingdom(), "race", raceOf(rt, place.kingdom())))
                    .withStyle(ChatFormatting.GOLD);
            case OUTPOST -> Component.literal(Text.get("entry.outpost", "kingdom", place.kingdom())).withStyle(ChatFormatting.YELLOW);
            case CLAIM -> Component.literal(Text.get("entry.claim", "kingdom", place.kingdom())).withStyle(ChatFormatting.YELLOW);
        };
    }

    private static String raceOf(CharterRuntime rt, String kingdom) {
        return rt.state.orgNamed(kingdom).map(charter.org.Organization::raceLabel).orElse(kingdom);
    }

    /** The plain text a player at this spot would be told; for tests. */
    public static String describe(CharterRuntime rt, ServerPlayer player) {
        return message(placeOf(rt, player)).getString();
    }
}
