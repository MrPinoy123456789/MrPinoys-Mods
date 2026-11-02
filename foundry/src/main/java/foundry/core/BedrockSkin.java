package foundry.core;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.List;

/**
 * A player's skin as Bedrock players see it, with their race look added: the skin image grows to 128 by 128 with the
 * original 64 by 64 skin in its top left quarter (the humanoid uvs are in pixels, so they still point at it), each look's
 * texture in one of the other three quarters, and the standard humanoid geometry plus the looks' bones, their uvs moved
 * to their quarter. Pure, so it can be tested; foundry.mc.GeyserSkins hands the result to Geyser.
 */
public final class BedrockSkin {
    public static final int SIZE = 128;
    /** The quarters a look's texture can go in, in order (the top left one is the skin). */
    static final int[][] QUARTERS = {{64, 0}, {0, 64}, {64, 64}};

    /** One look: its id, its bones (uvs into its own texture) and that texture, at most 64 by 64, as ARGB. */
    public record Piece(String item, JsonArray bones, int width, int height, int[] argb) {}

    /** The new skin: RGBA bytes 128 by 128, the geometry's name and data as Geyser wants them, and a unique skin id. */
    public record Made(byte[] rgba, String geometryName, String geometryData, String id) {}

    private BedrockSkin() {}

    /** A skin's RGBA bytes as a 64 by 64 ARGB image; a 64 by 32 (old style) skin is widened; any other size gives null. */
    public static int[] base(byte[] rgba) {
        if (rgba == null) return null;
        int pixels = rgba.length / 4;
        if (pixels != 64 * 64 && pixels != 64 * 32) return null;
        int[] out = new int[64 * 64];
        for (int i = 0; i < pixels; i++) {
            int r = rgba[i * 4] & 0xff, g = rgba[i * 4 + 1] & 0xff, b = rgba[i * 4 + 2] & 0xff, a = rgba[i * 4 + 3] & 0xff;
            out[i] = a << 24 | r << 16 | g << 8 | b;
        }
        if (pixels == 64 * 32) {
            // an old style skin has no left arm or leg: they wear the right ones
            copy(out, 0, 16, 16, 16, out, 16, 48);
            copy(out, 40, 16, 16, 16, out, 32, 48);
        }
        return out;
    }

    private static void copy(int[] from, int fx, int fy, int w, int h, int[] to, int tx, int ty) {
        for (int y = 0; y < h; y++) System.arraycopy(from, (fy + y) * 64 + fx, to, (ty + y) * 64 + tx, w);
    }

    /**
     * The skin with these looks added, or null when the skin is not a size this can extend or there is nothing to add.
     * {@code humanoid} holds the standard bones ("wide" and "slim"); {@code skinId} is the original skin's id, so the
     * new id is unique to the look and the skin (a Bedrock client caches skins by id).
     */
    public static Made make(byte[] skinRgba, String skinId, boolean slim, JsonObject humanoid, List<Piece> pieces) {
        int[] skin = base(skinRgba);
        if (skin == null || pieces.isEmpty()) return null;
        int[] canvas = new int[SIZE * SIZE];
        for (int y = 0; y < 64; y++) System.arraycopy(skin, y * 64, canvas, y * SIZE, 64);
        JsonArray bones = humanoid.getAsJsonArray(slim ? "slim" : "wide").deepCopy();
        StringBuilder key = new StringBuilder();
        for (int i = 0; i < pieces.size() && i < QUARTERS.length; i++) {
            Piece piece = pieces.get(i);
            int qx = QUARTERS[i][0], qy = QUARTERS[i][1];
            int w = Math.min(64, piece.width()), h = Math.min(64, piece.height());
            for (int y = 0; y < h; y++) System.arraycopy(piece.argb(), y * piece.width(), canvas, (qy + y) * SIZE + qx, w);
            for (JsonElement b : piece.bones()) {
                JsonObject bone = b.getAsJsonObject().deepCopy();
                for (JsonElement c : bone.getAsJsonArray("cubes")) {
                    for (var face : c.getAsJsonObject().getAsJsonObject("uv").entrySet()) {
                        JsonArray uv = face.getValue().getAsJsonObject().getAsJsonArray("uv");
                        uv.set(0, new com.google.gson.JsonPrimitive(uv.get(0).getAsInt() + qx));
                        uv.set(1, new com.google.gson.JsonPrimitive(uv.get(1).getAsInt() + qy));
                    }
                }
                bones.add(bone);
            }
            key.append(key.length() == 0 ? "" : "_").append(slug(piece.item()));
        }
        String identifier = "geometry.foundry." + key + (slim ? "_slim" : "");
        JsonObject description = new JsonObject();
        description.addProperty("identifier", identifier);
        description.addProperty("texture_width", SIZE);
        description.addProperty("texture_height", SIZE);
        // tall looks (the hat and its feather) would be culled at the edge of the screen with the default bounds
        description.addProperty("visible_bounds_width", 3);
        description.addProperty("visible_bounds_height", 4);
        JsonArray offset = new JsonArray();
        offset.add(0);
        offset.add(1.5);
        offset.add(0);
        description.add("visible_bounds_offset", offset);
        JsonObject geometry = new JsonObject();
        geometry.add("description", description);
        geometry.add("bones", bones);
        JsonArray list = new JsonArray();
        list.add(geometry);
        JsonObject data = new JsonObject();
        data.addProperty("format_version", "1.12.0");
        data.add("minecraft:geometry", list);
        String name = "{\"geometry\":{\"default\":\"" + identifier + "\"}}";
        byte[] rgba = new byte[SIZE * SIZE * 4];
        for (int i = 0; i < canvas.length; i++) {
            int p = canvas[i];
            rgba[i * 4] = (byte) (p >> 16);
            rgba[i * 4 + 1] = (byte) (p >> 8);
            rgba[i * 4 + 2] = (byte) p;
            rgba[i * 4 + 3] = (byte) (p >>> 24);
        }
        return new Made(rgba, name, data.toString(), "foundry:" + key + ":" + skinId);
    }

    /** True when a Bedrock player's own geometry is the plain classic or slim shape (not a persona with its own parts). */
    public static boolean plain(String geometryName) {
        return geometryName == null || geometryName.isBlank()
                || geometryName.matches("(?s).*\"geometry\\.humanoid\\.custom(Slim)?\".*");
    }

    static String slug(String id) {
        return id.substring(id.indexOf(':') + 1).replaceAll("[^a-z0-9_]", "_");
    }
}
