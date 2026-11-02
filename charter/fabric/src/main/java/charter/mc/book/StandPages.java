package charter.mc.book;

import charter.contract.Contract;
import charter.contract.Justice;
import charter.contract.Term;
import charter.gov.PowerGroup;
import charter.gov.Standing;
import charter.id.PlayerId;
import charter.mc.CharterRuntime;
import charter.mc.CharterServer;
import charter.mc.Text;
import charter.mc.cmd.Chat;
import charter.org.Organization;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * "Where I stand": what you may do, who decides for you, and your status, on one screen. It replaces three read only
 * pages (Rights, Freedoms, Situation) and their round trips. Each section is one paragraph of at most
 * {@value #SECTION_LINES} lines; a longer one is cut with "and N more" and a button to the full page. A "line" of the
 * screen rules is a paragraph, so three sections stay within the three lines a body may have.
 */
public final class StandPages {

    static final int SECTION_LINES = 4;

    private StandPages() {
    }

    public static void page(ServerPlayer player, String notice) {
        if (!CharterServer.running()) return;
        CharterRuntime rt = CharterServer.runtime();
        PlayerId me = new PlayerId(player.getUUID());
        long now = rt.clock.now();
        Optional<Standing.View> found = Standing.of(rt.state, rt.engine, me, now);
        if (found.isEmpty()) {
            NewcomerPages.home(player, null);
            return;
        }
        Standing.View v = found.get();
        List<String> may = StandingText.joined(v.rights().stream().filter(l -> l.group() != PowerGroup.CHARTER).toList());
        boolean mayCut = may.size() > SECTION_LINES;
        if (may.isEmpty()) may = List.of(Text.get(v.form() == Standing.Form.COMMUNE ? "book.rights.none" : "book.rights.none_ruled"));
        List<String> decides = StandingText.joined(v.freedoms().stream().filter(l -> l.group() != PowerGroup.CHARTER).toList());
        boolean decidesCut = decides.size() > SECTION_LINES;
        if (decides.isEmpty()) decides = List.of(Text.get("book.freedoms.none"));

        List<Component> body = new ArrayList<>();
        body.add(section("book.stand.may", may));
        body.add(section("book.stand.decides", decides));
        body.add(section("book.stand.status", status(rt, me, v, now)));
        List<BookButton> buttons = new ArrayList<>();
        if (mayCut) buttons.add(BookDialogs.act(player, Text.get("book.stand.see_may"), null, p -> MemberPages.rights(p, null)));
        if (decidesCut) buttons.add(BookDialogs.act(player, Text.get("book.stand.see_decides"), null, p -> MemberPages.freedoms(p, null)));
        BookDialogs.show(player, BookDialogs.list(Text.get("book.stand.title"), BookDialogs.withNotice(notice, body), buttons,
                BookDialogs.back(player, p -> MemberPages.home(p, null))));
    }

    /** A gold heading, then the lines, cut to the section limit. */
    static Component section(String headingKey, List<String> lines) {
        StringBuilder text = new StringBuilder();
        List<String> shown = lines.size() <= SECTION_LINES ? lines : lines.subList(0, SECTION_LINES - 1);
        for (String line : shown) text.append('\n').append(line);
        if (lines.size() > SECTION_LINES) text.append('\n').append(Text.get("book.stand.more", "n", lines.size() - shown.size()));
        return Component.literal(Text.get(headingKey)).withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD)
                .append(Component.literal(text.toString()).withStyle(ChatFormatting.WHITE));
    }

    private static List<String> status(CharterRuntime rt, PlayerId me, Standing.View v, long now) {
        List<String> out = new ArrayList<>();
        out.add(Text.get("book.situation.member", "org", v.orgName(), "time", Chat.duration(now - v.memberSince())));
        rt.state.ranks.rankOf(rt.state, me).ifPresent(rank -> out.add(Text.get("book.stand.rank", "rank", rank.name())));
        Optional<Contract> held = Justice.holding(rt.state, rt.knobs, me, now);
        if (held.isPresent()) {
            Contract c = held.get();
            Term term = c.term(Term.Confinement.class) != null ? c.term(Term.Confinement.class) : c.term(Term.Tether.class);
            long left = term == null ? 0 : Justice.until(c, term, rt.knobs) - now;
            out.add(Text.get("book.situation.held", "time", Chat.duration(left)));
        }
        for (Organization org : rt.state.orgs()) {
            if (org.isTopLevel() && org.exists() && Justice.banished(rt.state, rt.knobs, me, org.id, now)) {
                out.add(Text.get("book.situation.banished", "org", org.name()));
            }
        }
        long respawn = Justice.respawnDelayLeft(rt.state, rt.knobs, me, now);
        if (respawn > 0) out.add(Text.get("book.situation.respawn", "time", Chat.duration(respawn)));
        Map<String, Integer> owed = rt.mail.owedTo(me);
        if (!owed.isEmpty()) {
            List<String> things = new ArrayList<>();
            owed.forEach((item, n) -> things.add(n + " " + item.substring(item.indexOf(':') + 1).replace('_', ' ')));
            out.add(Text.get("book.situation.owed", "list", String.join(", ", things)));
        }
        return out;
    }
}
