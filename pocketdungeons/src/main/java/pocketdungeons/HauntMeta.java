package pocketdungeons;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

/**
 * Parses one {@code dungeon_haunt/*.json} file into a {@link HauntDefinition}. The same shape as
 * {@link AffixMeta}: a static {@link #fromJson} that reads the version, validates every field and throws naming
 * the file on any violation, so {@link HauntManifest} can report it and carry on. Pure: registry checks (does
 * the mob, particle or sound exist) belong to the manifest, which has a server.
 */
final class HauntMeta {

    /** The longest rule line, in characters: it is one chat line a player reads once. */
    static final int MAX_RULE_LINE = 60;

    private HauntMeta() {}

    static HauntDefinition fromJson(JsonObject obj, String fileIdentity) {
        JsonPackSupport.parseVersion(obj, fileIdentity);
        String label = required(obj, "label", fileIdentity);
        Set<String> dungeons = new LinkedHashSet<>();
        JsonElement list = obj.get("dungeons");
        if (list == null || !list.isJsonArray() || list.getAsJsonArray().isEmpty()) {
            throw new IllegalArgumentException(fileIdentity + ": dungeons must be a non-empty array");
        }
        for (JsonElement item : list.getAsJsonArray()) {
            String dungeon = item.getAsString().trim();
            if (dungeon.isEmpty()) {
                throw new IllegalArgumentException(fileIdentity + ": a dungeon id must not be blank");
            }
            dungeons.add(JsonPackSupport.qualify(dungeon));
        }
        int minCompass = obj.has("min_compass") ? obj.get("min_compass").getAsInt() : 0;
        if (minCompass < 0) {
            throw new IllegalArgumentException(fileIdentity + ": min_compass must be 0 or more");
        }
        HauntDefinition.Behaviour behaviour = behaviour(required(obj, "behaviour", fileIdentity), fileIdentity);
        String body = required(obj, "body", fileIdentity);
        String particle = required(obj, "tell_particle", fileIdentity);
        String sound = required(obj, "tell_sound", fileIdentity);
        String ruleLine = required(obj, "rule_line", fileIdentity);
        if (ruleLine.length() > MAX_RULE_LINE) {
            throw new IllegalArgumentException(fileIdentity + ": rule_line is over " + MAX_RULE_LINE
                    + " characters: " + ruleLine);
        }
        String diary = obj.has("diary") && !obj.get("diary").isJsonNull() ? obj.get("diary").getAsString().trim() : "";
        return new HauntDefinition(fileIdentity, label, dungeons, minCompass, behaviour, body, particle, sound,
                ruleLine, diary);
    }

    /** The behaviour kind named, or an error listing the closed set. */
    static HauntDefinition.Behaviour behaviour(String name, String fileIdentity) {
        try {
            return HauntDefinition.Behaviour.valueOf(name.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException(fileIdentity + ": behaviour must be one of "
                    + java.util.Arrays.toString(HauntDefinition.Behaviour.values()) + ", not '" + name + "'");
        }
    }

    private static String required(JsonObject obj, String key, String fileIdentity) {
        JsonElement element = obj.get(key);
        if (element == null || element.isJsonNull() || !element.isJsonPrimitive()) {
            throw new IllegalArgumentException(fileIdentity + ": missing required field: " + key);
        }
        String value = element.getAsString().trim();
        if (value.isEmpty()) {
            throw new IllegalArgumentException(fileIdentity + ": field must not be blank: " + key);
        }
        return value;
    }
}
