package foundry.mc;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import foundry.core.BedrockSkin;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The race looks for Bedrock players. Bedrock clients cannot draw the Java looks (item displays, head items without a
 * Bedrock model), so each look is added to the player's skin as Bedrock sees it (see foundry.core.BedrockSkin). The
 * bones and textures come from the jar (foundry/bedrock, written by tools/gen_bedrock_looks.py).
 *
 * <p>Which looks each player wears is set here from the server thread every scan ({@link WornDisplays}), and read by
 * {@link GeyserSkins} on Geyser's own threads, so it lives in a concurrent map. Geyser is optional: without it this only
 * keeps the map, and nothing else happens.
 */
public final class BedrockLooks {
    private static final Map<UUID, List<String>> WORN = new ConcurrentHashMap<>();
    private static volatile Loaded loaded;
    private static final boolean GEYSER = present();

    private BedrockLooks() {}

    record Loaded(JsonObject humanoid, Map<String, BedrockSkin.Piece> pieces) {}

    private static boolean present() {
        try {
            Class.forName("org.geysermc.geyser.api.GeyserApi", false, BedrockLooks.class.getClassLoader());
            return true;
        } catch (ClassNotFoundException | LinkageError e) {
            return false;
        }
    }

    private static boolean hooked;
    private static int tries;

    /**
     * Starts listening for skins once Geyser is up; nothing without Geyser. Geyser starts after the server is done
     * loading, so this is tried every second (from the server tick) until it works, for up to a minute.
     */
    static void tick(net.minecraft.server.MinecraftServer server) {
        if (!GEYSER || hooked || tries >= 60 || server.getTickCount() % 20 != 0) return;
        tries++;
        try {
            GeyserSkins.register();
            hooked = true;
        } catch (RuntimeException | LinkageError e) {
            if (tries >= 60) FoundryMod.LOG.warn("Foundry: could not hook Geyser skins, Bedrock players will not see race looks", e);
        }
    }

    /** Sets the looks a player wears (item ids); when they change, Bedrock players are sent the new skin. */
    static void set(UUID player, List<String> items) {
        List<String> drawn = new ArrayList<>();
        for (String id : items) if (pieces().containsKey(id)) drawn.add(id);
        List<String> before = drawn.isEmpty() ? WORN.remove(player) : WORN.put(player, List.copyOf(drawn));
        if (GEYSER && !drawn.equals(before == null ? List.of() : before)) GeyserSkins.refresh(player);
    }

    static void forget(UUID player) {
        WORN.remove(player);
        if (GEYSER) GeyserSkins.forget(player);
    }

    static void clear() {
        WORN.clear();
        hooked = false;
        tries = 0;
    }

    /** The looks this player wears that Bedrock can draw (empty for none). */
    public static List<String> worn(UUID player) {
        return WORN.getOrDefault(player, List.of());
    }

    /** The pieces for these looks, in order. */
    static List<BedrockSkin.Piece> piecesFor(List<String> items) {
        List<BedrockSkin.Piece> out = new ArrayList<>();
        for (String id : items) {
            var p = pieces().get(id);
            if (p != null) out.add(p);
        }
        return out;
    }

    static JsonObject humanoid() {
        return load().humanoid();
    }

    /** Every look the jar has Bedrock bones for, by item id. */
    public static Map<String, BedrockSkin.Piece> pieces() {
        return load().pieces();
    }

    private static Loaded load() {
        Loaded l = loaded;
        if (l != null) return l;
        synchronized (BedrockLooks.class) {
            if (loaded != null) return loaded;
            Map<String, BedrockSkin.Piece> pieces = new HashMap<>();
            JsonObject humanoid = new JsonObject();
            try {
                humanoid = json("humanoid.json").getAsJsonObject();
                for (var e : json("looks.json").getAsJsonArray()) {
                    String id = e.getAsString();
                    String name = id.substring(id.indexOf(':') + 1);
                    JsonObject look = json(name + ".json").getAsJsonObject();
                    try (InputStream in = resource(name + ".png")) {
                        var img = javax.imageio.ImageIO.read(in);
                        int w = img.getWidth(), h = img.getHeight();
                        int[] argb = img.getRGB(0, 0, w, h, null, 0, w);
                        pieces.put(id, new BedrockSkin.Piece(id, look.getAsJsonArray("bones"), w, h, argb));
                    }
                }
            } catch (Exception e) {
                FoundryMod.LOG.warn("Foundry: could not read the Bedrock race looks from the jar", e);
            }
            loaded = new Loaded(humanoid, Map.copyOf(pieces));
            return loaded;
        }
    }

    private static com.google.gson.JsonElement json(String name) throws java.io.IOException {
        try (InputStream in = resource(name)) {
            return JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8));
        }
    }

    private static InputStream resource(String name) throws java.io.IOException {
        InputStream in = BedrockLooks.class.getResourceAsStream("/foundry/bedrock/" + name);
        if (in == null) throw new java.io.FileNotFoundException("foundry/bedrock/" + name);
        return in;
    }
}
