package pocketdungeons;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.ChatFormatting;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Crew model (decision 2026-10-21, spec 5): one act's terminal data and
 * completion rewards, parsed from {@code data/<pack>/act_reward/act_<n>.json}
 * by {@link ActRewardManifest}. Carries everything the terminal and the
 * reward drop need: the act colour (a {@link ChatFormatting} name), the
 * crest item id, the two story lines, the 8 by 6 stained glass window art
 * (top row first, as the viewer sees it), and the optional reward set
 * (title, music disc, decor bundle, room shell). Act 0 ships {@code
 * "rewards": {}} and pays nothing.
 */
final class ActReward {

    /** One stack inside the decor bundle. */
    record DecorItem(String item, int count) {}

    /** The optional reward set; every field may be absent (act 0). */
    record Rewards(String title, String disc, List<DecorItem> decor, String shell) {
        static final Rewards NONE = new Rewards(null, null, List.of(), null);
    }

    record Act(int act, String colour, String crest, List<String> story,
               List<String> window, Rewards rewards) {}

    /**
     * Window letters to glass colours (spec 5.3). {@link #glassId} turns a
     * letter into {@code minecraft:<colour>_stained_glass}.
     */
    private static final Map<Character, String> GLASS = Map.ofEntries(
            Map.entry('K', "black"), Map.entry('W', "white"),
            Map.entry('L', "light_gray"), Map.entry('G', "gray"),
            Map.entry('B', "brown"), Map.entry('O', "orange"),
            Map.entry('Y', "yellow"), Map.entry('R', "red"),
            Map.entry('N', "pink"), Map.entry('M', "magenta"),
            Map.entry('P', "purple"), Map.entry('U', "blue"),
            Map.entry('A', "light_blue"), Map.entry('C', "cyan"),
            Map.entry('E', "green"), Map.entry('I', "lime"));

    /** The stained glass item id for window letter {@code c}. */
    static String glassId(char c) {
        return "minecraft:" + GLASS.get(c) + "_stained_glass";
    }

    /** Whether {@code c} is a valid window letter (pure-test surface). */
    static boolean isWindowLetter(char c) {
        return GLASS.containsKey(c);
    }

    /** The act colour as a {@link ChatFormatting}; the name is validated at parse. */
    static ChatFormatting colourOf(Act act) {
        return colourByName(act.colour());
    }

    private static ChatFormatting colourByName(String name) {
        try {
            return ChatFormatting.valueOf(name.toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    static Act fromJson(JsonObject obj, String fileIdentity) {
        JsonPackSupport.parseVersion(obj, fileIdentity);
        int act = intField(obj, "act", fileIdentity);
        if (act < DungeonDef.PROLOGUE_ACT || act > DungeonDef.MAX_ACT) {
            throw new IllegalArgumentException(fileIdentity
                    + ": act must be " + DungeonDef.PROLOGUE_ACT + " to " + DungeonDef.MAX_ACT);
        }
        String colour = JsonPackSupport.requiredString(obj, "colour");
        if (colourByName(colour) == null) {
            throw new IllegalArgumentException(fileIdentity
                    + ": colour is not a ChatFormatting name: " + colour);
        }
        String crest = JsonPackSupport.requiredString(obj, "crest");
        List<String> story = stringList(obj, "story", fileIdentity);
        List<String> window = windowRows(obj, fileIdentity);
        Rewards rewards = rewards(obj, fileIdentity);
        return new Act(act, colour, crest, story, window, rewards);
    }

    private static int intField(JsonObject obj, String key, String fileIdentity) {
        JsonElement el = obj.get(key);
        if (el == null || el.isJsonNull()) {
            throw new IllegalArgumentException(fileIdentity + ": missing required field: " + key);
        }
        return el.getAsInt();
    }

    private static List<String> stringList(JsonObject obj, String key, String fileIdentity) {
        JsonElement el = obj.get(key);
        if (el == null || el.isJsonNull()) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        for (JsonElement line : el.getAsJsonArray()) {
            String s = line.getAsString();
            if (s == null || s.isBlank()) {
                throw new IllegalArgumentException(fileIdentity + ": blank " + key + " line");
            }
            out.add(s);
        }
        return out;
    }

    /** The window: exactly 6 rows of 8 letters drawn from {@link #GLASS}. */
    private static List<String> windowRows(JsonObject obj, String fileIdentity) {
        List<String> rows = stringList(obj, "window", fileIdentity);
        if (rows.size() != 6) {
            throw new IllegalArgumentException(fileIdentity
                    + ": window must have 6 rows, has " + rows.size());
        }
        for (String row : rows) {
            if (row.length() != 8) {
                throw new IllegalArgumentException(fileIdentity
                        + ": window row must have 8 letters: " + row);
            }
            for (char c : row.toCharArray()) {
                if (!GLASS.containsKey(c)) {
                    throw new IllegalArgumentException(fileIdentity
                            + ": window letter not in 5.3: " + c);
                }
            }
        }
        return rows;
    }

    /**
     * The optional {@code rewards} object: title, disc, decor bundle lines
     * and a shell unlock name. Decor lines need an item and a count of at
     * least 1; the bundle may hold at most 64 items (spec 5.2). Item ids
     * are resolved against the registry by {@link ActRewardManifest#parse},
     * which runs with a server.
     */
    private static Rewards rewards(JsonObject obj, String fileIdentity) {
        JsonElement el = obj.get("rewards");
        if (el == null || el.isJsonNull()) {
            return Rewards.NONE;
        }
        JsonObject r = el.getAsJsonObject();
        String title = optString(r, "title");
        String disc = optString(r, "disc");
        String shell = optString(r, "shell");
        List<DecorItem> decor = new ArrayList<>();
        int weight = 0;
        JsonElement decorEl = r.get("decor");
        if (decorEl != null && !decorEl.isJsonNull()) {
            for (JsonElement line : decorEl.getAsJsonArray()) {
                JsonObject d = line.getAsJsonObject();
                String item = optString(d, "item");
                if (item == null) {
                    throw new IllegalArgumentException(fileIdentity
                            + ": decor line with no item");
                }
                int count = d.has("count") ? d.get("count").getAsInt() : 1;
                if (count < 1) {
                    throw new IllegalArgumentException(fileIdentity
                            + ": decor " + item + " with count " + count);
                }
                weight += count;
                decor.add(new DecorItem(item, count));
            }
        }
        if (weight > 64) {
            throw new IllegalArgumentException(fileIdentity
                    + ": decor bundle holds " + weight + " items, over the 64 limit");
        }
        return new Rewards(title, disc, List.copyOf(decor), shell);
    }

    private static String optString(JsonObject obj, String key) {
        JsonElement el = obj.get(key);
        if (el == null || el.isJsonNull()) {
            return null;
        }
        String s = el.getAsString().trim();
        return s.isEmpty() ? null : s;
    }
}
