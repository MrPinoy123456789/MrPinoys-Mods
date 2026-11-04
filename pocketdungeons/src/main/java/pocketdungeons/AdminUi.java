package pocketdungeons;

import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;

import java.util.List;

/**
 * The look of the operator screens (2026-11-04): a gold title, grey labels, white values, and bracketed buttons that
 * either run a command at once (aqua, for safe things such as a teleport or a page) or put it in the chat box for the
 * operator to check and send (red, for anything that closes, collapses or removes). Every button says on hover what it
 * does and shows the command. Chat only, so it works from any client and in the server log.
 */
final class AdminUi {

    private AdminUi() {}

    static MutableComponent title(String text) {
        return Component.literal("» ").withStyle(ChatFormatting.DARK_GRAY)
                .append(Component.literal(text).withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD));
    }

    static MutableComponent label(String text) {
        return Component.literal(text).withStyle(ChatFormatting.GRAY);
    }

    static MutableComponent value(String text) {
        return Component.literal(text).withStyle(ChatFormatting.WHITE);
    }

    static MutableComponent text(String text, ChatFormatting colour) {
        return Component.literal(text).withStyle(colour);
    }

    /** A button that runs {@code command} when clicked. */
    static MutableComponent run(String label, String command, String hover) {
        return button(label, ChatFormatting.AQUA, new ClickEvent.RunCommand(command), hover, command);
    }

    /** A button that puts {@code command} in the chat box, for the operator to read before sending. */
    static MutableComponent suggest(String label, String command, String hover) {
        return button(label, ChatFormatting.YELLOW, new ClickEvent.SuggestCommand(command), hover, command);
    }

    /** A red button that puts a destructive command in the chat box; nothing happens until it is sent. */
    static MutableComponent danger(String label, String command, String hover) {
        return button(label, ChatFormatting.RED, new ClickEvent.SuggestCommand(command), hover, command);
    }

    private static MutableComponent button(String label, ChatFormatting colour, ClickEvent click, String hover,
                                           String command) {
        Component tip = Component.literal(hover).withStyle(ChatFormatting.WHITE)
                .append(Component.literal("\n" + command).withStyle(ChatFormatting.DARK_GRAY));
        return Component.literal("[" + label + "]").withStyle(style -> style.withColor(colour)
                .withClickEvent(click).withHoverEvent(new HoverEvent.ShowText(tip)));
    }

    /** Parts joined with a space. */
    static MutableComponent line(Component... parts) {
        MutableComponent out = Component.literal("  ");
        for (int i = 0; i < parts.length; i++) {
            if (i > 0) {
                out.append(" ");
            }
            out.append(parts[i]);
        }
        return out;
    }

    /** Parts joined with a grey dot: "48 of 60 standing . 39 Ready". */
    static MutableComponent dotted(List<Component> parts) {
        MutableComponent out = Component.literal("  ");
        for (int i = 0; i < parts.size(); i++) {
            if (i > 0) {
                out.append(Component.literal(" · ").withStyle(ChatFormatting.DARK_GRAY));
            }
            out.append(parts.get(i));
        }
        return out;
    }

    static void send(CommandSourceStack source, Component line) {
        source.sendSuccess(() -> line, false);
    }

    static int fail(CommandSourceStack source, String message) {
        source.sendFailure(Component.literal(message).withStyle(ChatFormatting.RED));
        return 0;
    }

    /**
     * {@code /dungeon admin help [section]}: every admin command that applies on this server, grouped, each a click
     * that fills the chat box with the command up to its arguments. Without a section, the section list.
     */
    static int help(CommandSourceStack source, String sectionWord) {
        boolean wild = WildMode.active();
        String only = sectionWord == null ? null : AdminText.sectionNamed(sectionWord);
        if (sectionWord != null && only == null) {
            return fail(source, "No help section " + sectionWord + ". Try wild, reports, testing, content or players.");
        }
        send(source, title("Dungeon admin commands" + (only == null ? "" : ": " + only)));
        for (String section : AdminText.SECTIONS) {
            if (only != null && !only.equals(section)) {
                continue;
            }
            List<AdminText.Entry> entries = AdminText.section(section, wild);
            if (entries.isEmpty()) {
                continue;
            }
            send(source, Component.literal(" " + section).withStyle(ChatFormatting.YELLOW)
                    .append(only == null ? Component.literal(" ").append(run("only these",
                            "/dungeon admin help " + AdminText.helpWord(section), "Show this section alone"))
                            : Component.empty()));
            for (AdminText.Entry e : entries) {
                MutableComponent usage = Component.literal("/dungeon admin " + e.usage()).withStyle(style -> style
                        .withColor(ChatFormatting.AQUA)
                        .withClickEvent(new ClickEvent.SuggestCommand(e.stem()))
                        .withHoverEvent(new HoverEvent.ShowText(Component.literal("Click to type it"))));
                send(source, line(usage, label(e.what())));
            }
        }
        if (only == null && wild) {
            send(source, line(label("Start here:"), run("Wild overview", "/dungeon admin wild",
                    "How many lodestones stand and in what state")));
        }
        return 1;
    }
}
