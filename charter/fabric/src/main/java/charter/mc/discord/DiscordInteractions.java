package charter.mc.discord;

import charter.id.PlayerId;
import charter.mc.Text;
import charter.time.Clock;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.Optional;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * What the bot does with a slash command or a button click. Gets the interaction
 * as Discord sent it and returns the message to show in reply; the caller has
 * already acknowledged it and edits the reply in. Runs on the server thread.
 *
 * <p>Who is acting is only ever the Discord account the interaction came from
 * (which Discord vouches for), turned into a player through the {@link LinkBook}.
 */
public final class DiscordInteractions {

    static final int TYPE_COMMAND = 2;
    static final int TYPE_COMPONENT = 3;

    private final LinkBook links;
    private final Supplier<JoinDesk> desk;
    private final Function<PlayerId, String> names;
    private final Clock clock;
    private final Runnable linksChanged;

    /**
     * @param desk         the running world's join desk, or null while Charter is not running
     * @param linksChanged called after a link is made or removed, to save the links
     */
    public DiscordInteractions(LinkBook links, Supplier<JoinDesk> desk, Function<PlayerId, String> names, Clock clock,
                               Runnable linksChanged) {
        this.links = links;
        this.desk = desk;
        this.names = names;
        this.clock = clock;
        this.linksChanged = linksChanged;
    }

    /** The Discord account that sent the interaction: {@code member.user} in a server, {@code user} in a DM. */
    public static String userId(JsonObject interaction) {
        JsonObject user = null;
        if (interaction.has("member") && interaction.getAsJsonObject("member").has("user")) {
            user = interaction.getAsJsonObject("member").getAsJsonObject("user");
        } else if (interaction.has("user")) {
            user = interaction.getAsJsonObject("user");
        }
        return user == null || !user.has("id") ? null : user.get("id").getAsString();
    }

    public static int type(JsonObject interaction) {
        return interaction.has("type") ? interaction.get("type").getAsInt() : 0;
    }

    public JsonObject handle(JsonObject interaction) {
        String user = userId(interaction);
        if (user == null) return DiscordMessages.text(Text.get("discord.unknown"));
        JsonObject data = interaction.has("data") ? interaction.getAsJsonObject("data") : new JsonObject();
        return switch (type(interaction)) {
            case TYPE_COMMAND -> command(user, string(data, "name"), data);
            case TYPE_COMPONENT -> click(user, string(data, "custom_id"));
            default -> DiscordMessages.text(Text.get("discord.unknown"));
        };
    }

    private JsonObject command(String user, String name, JsonObject data) {
        return switch (name == null ? "" : name) {
            case "link" -> link(user, option(data, "code"));
            case "unlink" -> DiscordMessages.text(Text.get(changed(links.unlinkDiscord(user)) ? "discord.unlinked" : "discord.not_linked"));
            case "requests" -> list(user, null);
            default -> DiscordMessages.text(Text.get("discord.unknown"));
        };
    }

    private boolean changed(boolean changed) {
        if (changed) linksChanged.run();
        return changed;
    }

    private JsonObject link(String user, String code) {
        LinkBook.Result result = links.redeem(user, code, clock.now());
        return DiscordMessages.text(switch (result.kind()) {
            case LINKED -> {
                linksChanged.run();
                yield Text.get("discord.linked", "player", names.apply(result.player()));
            }
            case EXPIRED -> Text.get("discord.link.expired");
            case NO_SUCH_CODE -> Text.get("discord.link.wrong");
            case SLOW_DOWN -> Text.get("discord.link.slow_down");
        });
    }

    private JsonObject click(String user, String customId) {
        if (DiscordMessages.LIST.equals(customId)) return list(user, null);
        DiscordMessages.Click click = DiscordMessages.parse(customId);
        if (click == null) return DiscordMessages.text(Text.get("discord.unknown"));
        Optional<PlayerId> me = links.playerOf(user);
        if (me.isEmpty()) return DiscordMessages.text(Text.get("discord.not_linked"));
        JoinDesk d = desk.get();
        if (d == null) return DiscordMessages.text(Text.get("discord.not_running"));
        if (click.askReject()) {
            return DiscordMessages.confirmReject(click.org(), click.player(), names.apply(click.player()));
        }
        JoinDesk.Answer answer = d.answer(me.get(), click.org(), click.player(), click.admit());
        return DiscordMessages.requests(d.answerable(me.get()), answer.line());
    }

    private JsonObject list(String user, String notice) {
        Optional<PlayerId> me = links.playerOf(user);
        if (me.isEmpty()) return DiscordMessages.text(Text.get("discord.not_linked"));
        JoinDesk d = desk.get();
        if (d == null) return DiscordMessages.text(Text.get("discord.not_running"));
        return DiscordMessages.requests(d.answerable(me.get()), notice);
    }

    private static String string(JsonObject o, String key) {
        JsonElement e = o.get(key);
        return e == null || e.isJsonNull() ? null : e.getAsString();
    }

    private static String option(JsonObject data, String name) {
        if (!data.has("options")) return null;
        for (JsonElement e : data.getAsJsonArray("options")) {
            JsonObject o = e.getAsJsonObject();
            if (name.equals(string(o, "name"))) return string(o, "value");
        }
        return null;
    }
}
