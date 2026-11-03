package fortune.mc;

import fortune.core.Claims;
import fortune.core.Line;
import fortune.core.Paytable;
import fortune.core.PlayerRecord;
import fortune.core.Settings;
import fortune.core.Stake;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/** The paytable in words, read from the same compiled tables the roll uses. */
final class Odds {

    private Odds() {
    }

    /** The table today's pull rolls on (floored for the week day), or the normal table once today is spun. */
    static Paytable todaysTable(FortuneService service, PlayerRecord record) {
        Optional<Claims.Plan> plan = record == null ? Optional.empty() : service.machine().plan(record, service.today());
        return plan.map(p -> service.machine().tableFor(p)).orElse(service.machine().normal());
    }

    static String rowText(FortuneService service, Paytable table, Paytable.Row row) {
        String pct = String.format(Locale.ROOT, "%.1f%%", table.percent(row));
        StringBuilder out = new StringBuilder(lineName(service, row.line())).append(": ").append(pct);
        if (FortuneService.currencyLoaded()) {
            out.append(", ").append(Stake.valor(row.level(), service.settings())).append(" Valor");
        }
        for (Settings.Extra extra : row.extras()) {
            out.append(", ").append(extra.count).append(" ").append(shortItem(extra.item));
        }
        return out.toString();
    }

    static List<Component> lines(FortuneService service, PlayerRecord record) {
        List<Component> out = new ArrayList<>();
        out.add(Messages.info("Fantasy's Fortune: the odds"));
        Paytable today = todaysTable(service, record);
        out.add(Messages.plain(today == service.machine().normal() ? "A normal spin:" : "Your next spin:"));
        for (Paytable.Row row : today.rows()) out.add(Component.literal("  " + rowText(service, today, row)).withStyle(ChatFormatting.WHITE));
        if (today != service.machine().normal()) {
            out.add(Messages.plain("A normal spin:"));
            for (Paytable.Row row : service.machine().normal().rows()) {
                out.add(Component.literal("  " + rowText(service, service.machine().normal(), row)).withStyle(ChatFormatting.WHITE));
            }
        }
        out.add(Messages.plain("The Golden Spin (day 7):"));
        for (Paytable.Row row : service.machine().golden().rows()) {
            out.add(Component.literal("  " + rowText(service, service.machine().golden(), row)).withStyle(ChatFormatting.WHITE));
        }
        out.add(Messages.plain("Days 3 to 6 pay at least a pair. The week moves one day per spin, not per date."));
        out.add(Messages.plain("A stake raises the rewards, never these odds. The result is decided before the reels move."));
        return out;
    }

    private static String lineName(FortuneService service, Line line) {
        return switch (line) {
            case NO_MATCH -> "No match";
            case PAIR -> "Any pair";
            case LOW_TRIPLE, MID_TRIPLE -> "Three " + groupNames(service, line.group());
            case DIAMONDS -> "Three Diamonds";
            case CROWNS -> "Three Crowns";
        };
    }

    private static String groupNames(FortuneService service, int group) {
        List<String> names = new ArrayList<>();
        for (Settings.SymbolDef s : service.settings().symbols) if (s.group == group) names.add(s.name);
        return String.join(" or ", names);
    }

    private static String shortItem(String id) {
        String path = id.contains(":") ? id.substring(id.indexOf(':') + 1) : id;
        return path.replace('_', ' ');
    }
}
