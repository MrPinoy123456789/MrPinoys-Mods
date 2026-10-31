package charter.mc.discord;

import charter.id.OrgId;
import charter.id.PlayerId;
import charter.mc.Text;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.util.List;
import java.util.UUID;

/**
 * The JSON Discord is sent: the bot's slash commands, and the join request list
 * with a row of buttons per request. A button's id names the action, the faction
 * and the applicant; nothing else in it is trusted, since every click is run as the
 * clicker's linked player and checked by the engine.
 */
public final class DiscordMessages {

    /** Discord shows at most five rows of buttons on a message. */
    public static final int ROWS_MAX = 5;
    /** The ephemeral flag: only the person who used the command sees the reply. */
    public static final int EPHEMERAL = 64;

    static final String PREFIX = "charter:join:";
    static final String ADMIT = "a";
    static final String ASK_REJECT = "r";
    static final String REJECT = "R";
    static final String LIST = "charter:join:list";

    private static final int BUTTON_SECONDARY = 2;
    private static final int BUTTON_SUCCESS = 3;
    private static final int BUTTON_DANGER = 4;
    private static final int LABEL_MAX = 80;

    private DiscordMessages() {
    }

    /** A button click, read back from its id. */
    public record Click(String what, OrgId org, PlayerId player) {
        public boolean admit() {
            return ADMIT.equals(what);
        }

        public boolean askReject() {
            return ASK_REJECT.equals(what);
        }

        public boolean reject() {
            return REJECT.equals(what);
        }
    }

    static String buttonId(String what, OrgId org, PlayerId player) {
        return PREFIX + what + ":" + org.value() + ":" + player.value();
    }

    /** The click a button id stands for, or null if it is not one of ours (or the list button). */
    public static Click parse(String customId) {
        if (customId == null || !customId.startsWith(PREFIX)) return null;
        String[] parts = customId.substring(PREFIX.length()).split(":");
        if (parts.length != 3) return null;
        if (!parts[0].equals(ADMIT) && !parts[0].equals(ASK_REJECT) && !parts[0].equals(REJECT)) return null;
        try {
            return new Click(parts[0], new OrgId(UUID.fromString(parts[1])), new PlayerId(UUID.fromString(parts[2])));
        } catch (IllegalArgumentException bad) {
            return null;
        }
    }

    // ---- the commands ----

    /** The bot's slash commands, for a bulk overwrite. Usable in a server and in the bot's DMs. */
    public static JsonArray commands() {
        JsonArray out = new JsonArray();
        JsonObject link = command("link", Text.get("discord.command.link"));
        JsonObject code = new JsonObject();
        code.addProperty("type", 3); // STRING
        code.addProperty("name", "code");
        code.addProperty("description", Text.get("discord.command.link.code"));
        code.addProperty("required", true);
        code.addProperty("min_length", 4);
        code.addProperty("max_length", 12);
        JsonArray options = new JsonArray();
        options.add(code);
        link.add("options", options);
        out.add(link);
        out.add(command("unlink", Text.get("discord.command.unlink")));
        out.add(command("requests", Text.get("discord.command.requests")));
        return out;
    }

    private static JsonObject command(String name, String description) {
        JsonObject c = new JsonObject();
        c.addProperty("name", name);
        c.addProperty("type", 1); // CHAT_INPUT
        c.addProperty("description", clip(description, 100));
        JsonArray contexts = new JsonArray();
        contexts.add(0); // in a server
        contexts.add(1); // in the bot's DMs
        c.add("contexts", contexts);
        JsonArray install = new JsonArray();
        install.add(0); // installed to a server
        c.add("integration_types", install);
        return c;
    }

    // ---- messages ----

    /** Plain text and no buttons. */
    public static JsonObject text(String content) {
        JsonObject m = new JsonObject();
        m.addProperty("content", content);
        m.add("components", new JsonArray());
        return m;
    }

    /**
     * The requests the reader may answer, a notice above them if any, and a row of
     * Admit and Turn away buttons for each of the first five.
     */
    public static JsonObject requests(List<JoinDesk.Entry> entries, String notice) {
        StringBuilder content = new StringBuilder();
        if (notice != null && !notice.isBlank()) content.append(notice).append("\n\n");
        JsonArray rows = new JsonArray();
        if (entries.isEmpty()) {
            content.append(Text.get("discord.requests.none"));
        } else {
            content.append("**").append(Text.get("discord.requests.title")).append("**");
            int shown = Math.min(entries.size(), ROWS_MAX);
            for (int i = 0; i < shown; i++) {
                JoinDesk.Entry e = entries.get(i);
                content.append("\n").append(Text.get("notice.asked", "player", e.playerName(), "org", e.orgName()));
                JsonArray buttons = new JsonArray();
                buttons.add(button(BUTTON_SUCCESS, Text.get("book.button.admit", "player", e.playerName()),
                        buttonId(ADMIT, e.org(), e.player())));
                buttons.add(button(BUTTON_DANGER, Text.get("book.button.reject", "player", e.playerName()),
                        buttonId(ASK_REJECT, e.org(), e.player())));
                rows.add(row(buttons));
            }
            if (entries.size() > shown) {
                content.append("\n").append(Text.get("discord.requests.more", "count", entries.size() - shown));
            }
        }
        JsonObject m = new JsonObject();
        m.addProperty("content", content.toString());
        m.add("components", rows);
        return m;
    }

    /** Are you sure: turning a player away, as the book asks it. */
    public static JsonObject confirmReject(OrgId org, PlayerId player, String playerName) {
        JsonArray buttons = new JsonArray();
        buttons.add(button(BUTTON_DANGER, Text.get("book.button.yes"), buttonId(REJECT, org, player)));
        buttons.add(button(BUTTON_SECONDARY, Text.get("discord.button.back"), LIST));
        JsonArray rows = new JsonArray();
        rows.add(row(buttons));
        JsonObject m = new JsonObject();
        m.addProperty("content", Text.get("book.reject.confirm", "player", playerName));
        m.add("components", rows);
        return m;
    }

    private static JsonObject row(JsonArray buttons) {
        JsonObject r = new JsonObject();
        r.addProperty("type", 1); // ACTION_ROW
        r.add("components", buttons);
        return r;
    }

    private static JsonObject button(int style, String label, String customId) {
        JsonObject b = new JsonObject();
        b.addProperty("type", 2); // BUTTON
        b.addProperty("style", style);
        b.addProperty("label", clip(label, LABEL_MAX));
        b.addProperty("custom_id", customId);
        return b;
    }

    static String clip(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max - 3) + "...";
    }
}
