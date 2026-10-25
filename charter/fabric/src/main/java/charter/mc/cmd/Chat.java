package charter.mc.cmd;

import charter.engine.Reason;
import charter.mc.Text;
import charter.time.Durations;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.level.ServerPlayer;

import java.util.function.Consumer;

/** Chat page pieces in one style, as in {@code ballot}'s Screens: gold headers, yellow links, gray detail. */
final class Chat {

    private Chat() {
    }

    static Component header(String text) {
        return Component.literal(text).withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD);
    }

    static MutableComponent line(String text) {
        return Component.literal(text).withStyle(ChatFormatting.WHITE);
    }

    static MutableComponent dim(String text) {
        return Component.literal(text).withStyle(ChatFormatting.GRAY);
    }

    static Component good(String text) {
        return Component.literal(text).withStyle(ChatFormatting.GREEN);
    }

    static Component bad(String text) {
        return Component.literal(text).withStyle(ChatFormatting.RED);
    }

    /** A link that runs a one-shot token bound to this player. */
    static MutableComponent act(ServerPlayer player, String label, String hover, Consumer<ServerPlayer> run) {
        String token = CharterCommands.TOKENS.issue(player.getUUID(), label, System.currentTimeMillis(), run);
        return Component.literal("[" + label + "]").withStyle(style -> style
                .withColor(ChatFormatting.YELLOW)
                .withClickEvent(new ClickEvent.RunCommand("/charter act " + token))
                .withHoverEvent(new HoverEvent.ShowText(Component.literal(hover))));
    }

    /** A link that runs a plain command, for pages anyone may open. */
    static MutableComponent run(String label, String command) {
        return Component.literal("[" + label + "]").withStyle(style -> style
                .withColor(ChatFormatting.YELLOW)
                .withClickEvent(new ClickEvent.RunCommand(command))
                .withHoverEvent(new HoverEvent.ShowText(Component.literal(command))));
    }

    /** A link that puts a command in the chat box for the player to finish. */
    static MutableComponent suggest(String label, String command) {
        return Component.literal("[" + label + "]").withStyle(style -> style
                .withColor(ChatFormatting.AQUA)
                .withClickEvent(new ClickEvent.SuggestCommand(command))
                .withHoverEvent(new HoverEvent.ShowText(Component.literal(command))));
    }

    /** The player's sentence for a refusal: the server's text for its key, else core's own. */
    static String reason(Reason reason) {
        String key = "reason." + reason.key();
        return Text.has(key) ? Text.get(key) : reason.text();
    }

    /** "2 days, 3 hours", "47 hours", "12 minutes". */
    static String duration(long millis) {
        if (millis <= 0) return Text.get("time.now");
        long days = millis / Durations.DAY;
        long hours = (millis % Durations.DAY) / Durations.HOUR;
        long minutes = (millis % Durations.HOUR) / Durations.MINUTE;
        if (days > 0) return Text.get("time.days_hours", "days", days, "hours", hours);
        if (hours > 0) return Text.get("time.hours_minutes", "hours", hours, "minutes", minutes);
        return Text.get("time.minutes", "minutes", Math.max(1, minutes));
    }
}
