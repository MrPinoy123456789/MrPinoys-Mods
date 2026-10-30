package charter.mc.book;

import charter.gov.Standing;
import charter.mc.Text;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Words for the standing model. Lines that share a holder are joined into one
 * (C4), so a usual Lordship reads as two or three lines rather than thirteen.
 */
public final class StandingText {

    private StandingText() {
    }

    /** One sentence per holder, in the order holders first appear: the holder, then what they hold. */
    public static List<String> joined(List<Standing.Line> lines) {
        return joined(lines, false);
    }

    /** Food in and out of granaries is not a power while keeps need no food; outposts keep their own store. */
    private static boolean hiddenLine(Standing.Line line) {
        boolean granary = line.power() == charter.grant.Power.GRANARY_DEPOSIT || line.power() == charter.grant.Power.GRANARY_WITHDRAW
                || line.group() == charter.gov.PowerGroup.GRANARY_IN || line.group() == charter.gov.PowerGroup.GRANARY_OUT;
        return granary && charter.mc.CharterServer.running() && !charter.mc.CharterServer.runtime().knobs.keepGranary;
    }

    /**
     * The same lines as {@link #joined(List, boolean)}, laid out to read: each holder is a coloured heading with a
     * hover that says what holding a power that way means, and what they hold is one bullet per line beneath it.
     */
    public static List<Component> components(List<Standing.Line> lines, boolean newcomer) {
        Map<String, List<Standing.Line>> byHolder = new LinkedHashMap<>();
        for (Standing.Line line : lines) {
            if (hiddenLine(line)) continue;
            String key = line.kind() + "|" + line.name() + "|" + line.holders() + "|" + line.together() + "|" + line.youCanVote();
            byHolder.computeIfAbsent(key, k -> new ArrayList<>()).add(line);
        }
        List<Component> out = new ArrayList<>();
        for (List<Standing.Line> group : byHolder.values()) {
            Standing.Line first = group.get(0);
            Component tip = Component.literal(holderTip(first));
            MutableComponent block = Component.literal("");
            block.append(Component.literal(holder(first, newcomer)).withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD)
                    .withStyle(style -> style.withHoverEvent(new HoverEvent.ShowText(tip))));
            for (Standing.Line line : group) {
                block.append(Component.literal("\n  • " + label(line)).withStyle(ChatFormatting.WHITE));
            }
            out.add(block);
        }
        return out;
    }

    /** What it means that someone holds a power this way, for the hover on their name. */
    static String holderTip(Standing.Line line) {
        return switch (line.kind()) {
            case YOU -> Text.get("book.tip.you");
            case OFFICE -> line.holders().size() <= 1 ? Text.get("book.tip.office_one", "name", String.join(", ", line.holders()))
                    : Text.get(line.together() ? "book.tip.office_together" : "book.tip.office_any");
            case MEMBERS_BY_VOTE -> Text.get("book.tip.vote");
            case PLAYER -> Text.get("book.tip.player");
            case ORG -> Text.get("book.tip.org");
        };
    }

    /** As {@link #joined(List)}; {@code newcomer} words the vote as something the reader could do once a member. */
    public static List<String> joined(List<Standing.Line> lines, boolean newcomer) {
        Map<String, List<Standing.Line>> byHolder = new LinkedHashMap<>();
        for (Standing.Line line : lines) {
            if (hiddenLine(line)) continue;
            String key = line.kind() + "|" + line.name() + "|" + line.holders() + "|" + line.together() + "|" + line.youCanVote();
            byHolder.computeIfAbsent(key, k -> new ArrayList<>()).add(line);
        }
        List<String> out = new ArrayList<>();
        for (List<Standing.Line> group : byHolder.values()) {
            List<String> things = new ArrayList<>();
            for (Standing.Line line : group) things.add(label(line));
            out.add(holder(group.get(0), newcomer) + ": " + String.join(", ", things));
        }
        return out;
    }

    /** What a line is about, lower case, with the spend limit after the treasury. */
    static String label(Standing.Line line) {
        String label = line.power() == null
                ? Text.get("book.group." + line.group().name().toLowerCase(Locale.ROOT))
                : Text.get("book.power." + line.power().name().toLowerCase(Locale.ROOT));
        if (line.spendLimitDiamonds() >= 0) label += " (" + Text.get("book.limit", "n", line.spendLimitDiamonds()) + ")";
        return label;
    }

    /** Who holds it, said to the reader. */
    static String holder(Standing.Line line) {
        return holder(line, false);
    }

    static String holder(Standing.Line line, boolean newcomer) {
        return switch (line.kind()) {
            case YOU -> line.name().isEmpty() ? Text.get("book.holder.you_grant")
                    : Text.get("book.holder.you", "name", line.name());
            case OFFICE -> {
                String names = String.join(", ", line.holders());
                if (line.holders().size() <= 1) yield Text.get("book.holder.office_one", "holder", names, "name", line.name());
                yield Text.get(line.together() ? "book.holder.office_together" : "book.holder.office_any",
                        "holders", names, "name", line.name());
            }
            case PLAYER -> Text.get("book.holder.player", "name", line.name());
            case ORG -> Text.get("book.holder.org", "name", line.name());
            case MEMBERS_BY_VOTE -> Text.get(line.youCanVote() ? "book.holder.vote_you"
                    : newcomer ? "book.holder.vote_newcomer" : "book.holder.vote_not_yet");
        };
    }
}
